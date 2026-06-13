package io.github.mayusi.calibratesoc.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an APK from a direct URL and launches the system package installer.
 *
 * The APK lands at:
 *   [Context.filesDir]/updates/CalibrateSoC-update.apk  (internal storage)
 *
 * That path is covered by the `apk_updates_internal` entry in
 * file_provider_paths.xml, so [FileProvider.getUriForFile] can produce a
 * content:// URI the installer can read without MANAGE_EXTERNAL_STORAGE.
 *
 * Installation is always handled by the *system* installer UI — we never
 * silently side-load. The user sees the standard "Do you want to install
 * this application?" confirmation dialog.
 *
 * Security properties:
 *  1. URL allowlist: only https:// URLs whose host ends with `.github.com`
 *     or `.githubusercontent.com` are accepted (fix 2).
 *  2. Signature verification: the downloaded APK must be signed by the same
 *     key as the currently-installed build before the installer is launched
 *     (fix 1). This guarantees "the update is signed by whoever signed the
 *     build you're running." With a release keystore both builds share the
 *     release key; when no keystore.properties is present both are debug-
 *     signed from the same machine debug.keystore — they still match.
 *  3. Internal storage: APK is written to filesDir (fix 3) so it cannot be
 *     swapped by another process via the sdcard path.
 *  4. Size sanity check: if the server advertises a content-length that
 *     differs from the asset's expected size by more than 1 MiB, the
 *     download is rejected (fix 5).
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── URL allowlist ─────────────────────────────────────────────────────────

    // (Delegate to companion so the logic is unit-testable without Android context.)

    // ── Signature verification ────────────────────────────────────────────────

    /**
     * Returns true when the APK at [file] is signed by at least one
     * certificate that also signs the currently-installed build.
     *
     * Uses [PackageManager.getPackageArchiveInfo] with
     * [PackageManager.GET_SIGNING_CERTIFICATES] (available since API 28;
     * minSdk is 29 so the deprecated int-flags overload is fine here).
     *
     * Security note: this verifies *certificate identity*, not just byte
     * equality of the cert chain. We compare SHA-256 digests of the raw DER
     * bytes so the comparison is robust against object-identity differences.
     *
     * This does NOT replace the Android installer's own signature check —
     * it is a defense-in-depth gate that prevents us from ever *launching*
     * the installer for a tampered APK, which matters because the user could
     * have sideloaded a malicious APK to the downloads directory before we
     * read it.
     */
    // getPackageInfo(String, Int) and getPackageArchiveInfo(String, Int) are
    // deprecated on API 33+ in favour of PackageInfoFlags variants. The int
    // overloads still work on all API levels including 33+; suppressing here
    // avoids noise while keeping the code readable. minSdk is 29 so there is
    // no risk of calling an API that doesn't exist.
    @Suppress("DEPRECATION")
    fun verifySignature(file: File): Boolean = runCatching {
        val pm = context.packageManager

        // --- Certs of the downloaded APK ---
        val archiveInfo = pm.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: run {
            Log.w(TAG, "getPackageArchiveInfo returned null for ${file.name}")
            return@runCatching false
        }
        val archiveSigners: Array<Signature> =
            archiveInfo.signingInfo?.apkContentsSigners
                ?: run {
                    Log.w(TAG, "signingInfo is null in downloaded APK")
                    return@runCatching false
                }

        // --- Certs of the currently-installed build ---
        val installedInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val installedSigners: Array<Signature> =
            installedInfo.signingInfo?.apkContentsSigners
                ?: run {
                    Log.w(TAG, "signingInfo is null for installed package")
                    return@runCatching false
                }

        // Build a set of SHA-256 fingerprints from the installed app's certs.
        val installedFingerprints = installedSigners.mapTo(HashSet()) { sha256(it.toByteArray()) }

        // Every cert in the downloaded APK must be present in the installed set.
        // (In practice all APKs have exactly one signer unless using v3 rotation.)
        val allMatch = archiveSigners.all { sha256(it.toByteArray()) in installedFingerprints }
        if (!allMatch) {
            Log.e(TAG, "Signature mismatch: downloaded APK is NOT signed by the installed app's key")
        }
        allMatch
    }.getOrElse { e ->
        Log.e(TAG, "Signature verification threw an exception", e)
        false
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Downloads the APK at [url] and writes it to the updates directory
     * (internal storage).
     *
     * Progress is reported via [onProgress] as (downloadedBytes, totalBytes).
     * [totalBytes] may be -1 if the server doesn't send Content-Length.
     *
     * [expectedSize] is compared against the Content-Length header as a sanity
     * check: if both are known and differ by more than [SIZE_TOLERANCE_BYTES],
     * the download is rejected (returns null). Pass 0 to skip the check.
     *
     * @return the [File] on success, null on any network/IO/validation failure.
     */
    suspend fun download(
        url: String,
        expectedSize: Long = 0L,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        // Fix 2: URL allowlist check before making any network call.
        if (!isAllowedUrl(url)) {
            Log.e(TAG, "Rejected download URL (not HTTPS or not a GitHub host): $url")
            return@withContext null
        }

        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .build()

            // Fix 3: Use internal storage (filesDir) instead of external.
            val updateDir = File(context.filesDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val destFile = File(updateDir, "CalibrateSoC-update.apk")
            if (destFile.exists()) destFile.delete()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body ?: return@runCatching null
                val total = body.contentLength() // -1 if unknown

                // Fix 5: size sanity — reject if Content-Length diverges from
                // the expected size by more than 1 MiB.
                if (expectedSize > 0 && total > 0) {
                    val delta = Math.abs(total - expectedSize)
                    if (delta > SIZE_TOLERANCE_BYTES) {
                        Log.e(
                            TAG,
                            "Size mismatch: Content-Length=$total, expected=$expectedSize, " +
                                "delta=$delta > tolerance=$SIZE_TOLERANCE_BYTES"
                        )
                        return@runCatching null
                    }
                }

                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
                destFile
            }
        }.getOrNull()
    }

    // ── Install ───────────────────────────────────────────────────────────────

    /**
     * Builds a FileProvider content URI for [file] and fires the system
     * package installer via [Intent.ACTION_VIEW] + the package-archive MIME
     * type. The system shows its own installation confirmation; we never
     * silently install.
     *
     * **Defense-in-depth gate:** the signature of [file] is verified against
     * the installed app's signing certificate before the installer intent is
     * fired. Returns false if verification fails so the caller can surface an
     * error to the user.
     *
     * Wrapped in [runCatching] so a missing FileProvider authority or a
     * device that blocks installer intents won't crash the app.
     */
    fun installApk(file: File): Boolean {
        // Defense-in-depth: verify even if the call site already verified.
        if (!verifySignature(file)) {
            Log.e(TAG, "installApk refused: signature verification failed for ${file.name}")
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ApkDownloader"

        /**
         * Maximum allowed deviation between the server-advertised
         * Content-Length and the GitHub asset's expected size.  1 MiB gives
         * headroom for minor server-side differences while still catching a
         * completely wrong file.
         */
        const val SIZE_TOLERANCE_BYTES = 1 * 1024 * 1024L // 1 MiB

        /**
         * Returns true when [url] is a safe download source:
         *  - scheme must be `https` (not http, file, content, etc.)
         *  - host must end with `.github.com` or `.githubusercontent.com`
         *    (GitHub release assets are served from
         *    `objects.githubusercontent.com`; the API/redirect is on
         *    `api.github.com` / `github.com`).
         *
         * Placed in the companion object so it can be unit-tested without an
         * Android runtime context.
         */
        fun isAllowedUrl(url: String): Boolean = runCatching {
            val parsed = URL(url)
            if (parsed.protocol != "https") return@runCatching false
            val host = parsed.host?.lowercase() ?: return@runCatching false
            // Accept the exact roots and all their subdomains.
            // host == "github.com"           → the root itself
            // host.endsWith(".github.com")   → e.g. "api.github.com", "objects.github.com"
            // Similarly for "githubusercontent.com" (e.g. "objects.githubusercontent.com",
            // "raw.githubusercontent.com").
            host == "github.com" ||
                host.endsWith(".github.com") ||
                host == "githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
        }.getOrDefault(false)
    }
}
