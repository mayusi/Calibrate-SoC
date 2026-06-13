package io.github.mayusi.calibratesoc.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an APK from a direct URL and launches the system package installer.
 *
 * The APK lands at:
 *   [Context.getExternalFilesDir](null)/updates/CalibrateSoC-update.apk
 *
 * That path is covered by the `apk_updates` entry in file_provider_paths.xml,
 * so [FileProvider.getUriForFile] can produce a content:// URI the installer
 * can read without MANAGE_EXTERNAL_STORAGE.
 *
 * Installation is always handled by the *system* installer UI — we never
 * silently side-load. The user sees the standard "Do you want to install
 * this application?" confirmation dialog.
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

    /**
     * Downloads the APK at [url] and writes it to the updates directory.
     *
     * Progress is reported via [onProgress] as (downloadedBytes, totalBytes).
     * [totalBytes] may be -1 if the server doesn't send Content-Length.
     *
     * @return the [File] on success, null on any network/IO failure.
     */
    suspend fun download(
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .build()

            val updateDir = File(context.getExternalFilesDir(null), "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val destFile = File(updateDir, "CalibrateSoC-update.apk")
            if (destFile.exists()) destFile.delete()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body ?: return@runCatching null
                val total = body.contentLength() // -1 if unknown

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

    /**
     * Builds a FileProvider content URI for [file] and fires the system
     * package installer via [Intent.ACTION_VIEW] + the package-archive MIME
     * type. The system shows its own installation confirmation; we never
     * silently install.
     *
     * Wrapped in [runCatching] so a missing FileProvider authority or a
     * device that blocks installer intents won't crash the app.
     */
    fun installApk(file: File) {
        runCatching {
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
        }
    }
}
