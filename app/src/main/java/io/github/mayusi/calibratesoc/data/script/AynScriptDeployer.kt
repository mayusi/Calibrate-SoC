package io.github.mayusi.calibratesoc.data.script

import android.content.Context
import android.os.Environment
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.util.toSafeFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generated-script disk-handling. Two destinations:
 *
 *   1. [deploy] writes to /sdcard/CalibrateSoC/<name>.sh — visible to
 *      Odin Settings' "Run script as Root" picker for one-shot
 *      invocation. No root required to create the file.
 *
 *   2. [deployForBoot] writes to /data/adb/service.d/<name>.sh (Magisk)
 *      or /data/adb/ksu/post-fs-data.d/<name>.sh (KernelSU). Runs at
 *      every boot before the user logs in, before perfd starts. ROOT
 *      REQUIRED — uses libsu to do the file write.
 *
 * Both methods are idempotent on the filename: re-deploying a preset
 * overwrites the previous version of its script.
 */
@Singleton
class AynScriptDeployer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * One-shot deploy. Writes [scriptBody] to disk and returns the
     * absolute path + whether the path is visible to Odin's picker.
     * Falls back to the app-private external dir on storage-restricted
     * setups (where Odin's picker likely can't reach it — the caller
     * surfaces an explanatory dialog).
     */
    fun deploy(preset: Preset, scriptBody: String): Deployed {
        val filename = "${toSafeFilename(preset.name, "preset")}.sh"

        // Preferred: /sdcard/CalibrateSoC/<filename> — visible to every
        // file-picker on the device.
        runCatching {
            @Suppress("DEPRECATION")
            val publicRoot = Environment.getExternalStorageDirectory()
            val dir = File(publicRoot, "CalibrateSoC").apply { mkdirs() }
            val out = File(dir, filename)
            out.writeText(scriptBody)
            return Deployed(path = out.absolutePath, visibleToOdinPicker = true)
        }

        // Fallback: app-private external-files dir. Always works.
        val priv = File(context.getExternalFilesDir(null), filename)
        priv.writeText(scriptBody)
        return Deployed(path = priv.absolutePath, visibleToOdinPicker = false)
    }

    /**
     * Boot-install. Drops the script into Magisk's service.d or
     * KernelSU's post-fs-data.d via libsu (root). Returns success +
     * which manager we used, or an error string explaining what went
     * wrong (no root, no service dir, write denied).
     */
    suspend fun deployForBoot(preset: Preset, scriptBody: String): BootDeployed =
        withContext(Dispatchers.IO) {
            val filename = "calibratesoc-${toSafeFilename(preset.id, "preset")}.sh"
            val candidates = listOf(
                "/data/adb/service.d" to "Magisk",
                "/data/adb/ksu/post-fs-data.d" to "KernelSU",
            )
            for ((dir, manager) in candidates) {
                val present = Shell.cmd("test -d $dir").exec().isSuccess
                if (!present) continue
                val target = "$dir/$filename"
                // Base64 the body so quoting characters in the script
                // can't confuse our `sh -c` invocation.
                val b64 = android.util.Base64.encodeToString(
                    scriptBody.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                )
                // Single-quote the target path: toSafeFilename() already strips most hostile
                // characters, but single-quoting is defence-in-depth and ensures the path
                // is safe even if a future caller passes an unsanitised filename, or if the
                // sanitiser logic ever changes. The base64 blob is already protected by the
                // surrounding single-quote in `echo '$b64'`.
                val res = Shell.cmd(
                    "echo '$b64' | base64 -d > '$target'",
                    "chmod 755 '$target'",
                ).exec()
                return@withContext if (res.isSuccess) {
                    BootDeployed(path = target, manager = manager, success = true, error = null)
                } else {
                    BootDeployed(
                        path = target,
                        manager = manager,
                        success = false,
                        error = res.err.joinToString("\n").ifBlank { "shell exit ${res.code}" },
                    )
                }
            }
            BootDeployed(
                path = "",
                manager = null,
                success = false,
                error = "No Magisk or KernelSU service directory found. Install one of them (or use Apply once and run via Odin Settings each boot).",
            )
        }

    data class Deployed(
        val path: String,
        val visibleToOdinPicker: Boolean,
    )

    data class BootDeployed(
        val path: String,
        val manager: String?,
        val success: Boolean,
        val error: String?,
    )
}
