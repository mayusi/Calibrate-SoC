package io.github.mayusi.calibratesoc.data.update

import io.github.mayusi.calibratesoc.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the latest GitHub release for mayusi/Calibrate-SoC and determines
 * whether it is newer than the currently installed build.
 *
 * Builds its own [OkHttpClient] with conservative timeouts (same pattern as
 * [NetworkSpeedTester]) rather than sharing the tester's instance — the
 * update check is rare and having independent lifecycle / timeout settings
 * is cleaner.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val json: Json,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches the latest non-draft, non-prerelease GitHub release.
     *
     * Returns null if the network call or JSON parsing fails — the caller
     * should degrade gracefully to an "Open Releases page" fallback.
     */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/mayusi/Calibrate-SoC/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string() ?: return@runCatching null
            } ?: return@runCatching null

            val release = json.decodeFromString<GitHubRelease>(body)

            // Skip drafts/prereleases (the /releases/latest endpoint already
            // excludes them, but guard defensively in case a future API
            // change includes them).
            if (release.draft || release.prerelease) return@runCatching null

            val remoteVersion = release.tag_name.removePrefix("v")
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }

            // Validate the download URL at the trust boundary, before storing it in
            // UpdateInfo where it could be passed to a downloader or shown in UI.
            // ApkDownloader.isAllowedUrl enforces HTTPS + GitHub-host allowlist.
            // If the asset URL fails validation we store null (no download link) rather
            // than carrying an unvalidated URL; the UI falls back to the Releases page.
            val validatedApkUrl = apkAsset?.browser_download_url?.let { rawUrl ->
                if (ApkDownloader.isAllowedUrl(rawUrl)) rawUrl else null
            }

            UpdateInfo(
                versionName = remoteVersion,
                tag = release.tag_name,
                notes = release.body ?: "",
                apkUrl = validatedApkUrl,
                apkSize = apkAsset?.size ?: 0L,
                isNewer = isNewer(remoteVersion, BuildConfig.VERSION_NAME),
            )
        }.getOrNull()
    }

    companion object {
        /**
         * Returns true when [remoteVersionName] is strictly newer than
         * [currentVersionName] using semver-ish rules:
         *
         *  1. Strip a leading "v" from either string (tag-style input).
         *  2. Split on the first "-" to separate MAJOR.MINOR.PATCH from
         *     pre-release label. A release without a pre-release label
         *     (e.g. "1.0.0") is considered *higher* than one with the same
         *     triple but with a label ("1.0.0-alpha") — standard semver
         *     pre-release ordering.
         *  3. Parse MAJOR, MINOR, PATCH as integers and compare
         *     lexicographically. Non-integer segments are treated as 0.
         *  4. If the numeric triple is equal, the one *without* a pre-release
         *     suffix wins.  If both have or both lack a suffix, they are equal
         *     → return false.
         *  5. Any malformed input (empty string, null-safe) → false (no crash).
         */
        fun isNewer(remoteVersionName: String, currentVersionName: String): Boolean {
            return runCatching {
                // Treat blank inputs as malformed → not newer.
                val remoteClean = remoteVersionName.removePrefix("v").trim()
                val currentClean = currentVersionName.removePrefix("v").trim()
                if (remoteClean.isEmpty() || currentClean.isEmpty()) return@runCatching false

                val (rNumeric, rHasSuffix) = parseVersion(remoteVersionName)
                val (cNumeric, cHasSuffix) = parseVersion(currentVersionName)

                // Compare MAJOR.MINOR.PATCH first
                for (i in 0..2) {
                    val r = rNumeric.getOrElse(i) { 0 }
                    val c = cNumeric.getOrElse(i) { 0 }
                    if (r != c) return@runCatching r > c
                }

                // Numeric triple is equal — standard semver pre-release rule:
                // a release WITHOUT a pre-release suffix is higher.
                // "1.0.0" > "1.0.0-alpha" → remote is newer only if remote has
                // no suffix and current has one.
                return@runCatching !rHasSuffix && cHasSuffix
            }.getOrDefault(false)
        }

        /**
         * Parse a version string like "0.1.5-alpha" or "v1.2.0" into a
         * [Triple] of (numericParts, hasSuffix).
         * Leading "v" is stripped first.
         */
        private fun parseVersion(raw: String): Pair<List<Int>, Boolean> {
            val cleaned = raw.removePrefix("v").trim()
            val dashIdx = cleaned.indexOf('-')
            val numericPart = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
            val hasSuffix = dashIdx >= 0
            val parts = numericPart.split('.').map { it.toIntOrNull() ?: 0 }
            return Pair(parts, hasSuffix)
        }
    }
}
