package io.github.mayusi.calibratesoc.data.script

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot refresh-rate script. Sets `peak_refresh_rate` +
 * `min_refresh_rate` via `settings put system`. The user runs it via
 * Odin Settings → Run script as Root, same flow as the unlock
 * script. Used as a fallback when our app can't directly write
 * Settings.System (no WRITE_SECURE_SETTINGS grant after a fresh
 * install).
 */
@Singleton
class RefreshRateScript @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun deploy(targetHz: Float): Deployed {
        val body = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Calibrate SoC — set screen refresh rate to ${targetHz.toInt()} Hz")
            appendLine("settings put system peak_refresh_rate ${targetHz}")
            appendLine("settings put system min_refresh_rate 60.0")
            appendLine("echo 'Calibrate SoC: peak_refresh_rate=${targetHz}'")
        }
        val filename = "calibratesoc_${targetHz.toInt()}hz.sh"
        // Public path first — visible to Odin's runner.
        runCatching {
            @Suppress("DEPRECATION")
            val pub = Environment.getExternalStorageDirectory()
            val dir = File(pub, "CalibrateSoC").apply { mkdirs() }
            val out = File(dir, filename)
            out.writeText(body)
            return Deployed(out.absolutePath, visibleToOdinPicker = true)
        }
        val priv = File(context.getExternalFilesDir(null), filename)
        priv.writeText(body)
        return Deployed(priv.absolutePath, visibleToOdinPicker = false)
    }

    data class Deployed(val path: String, val visibleToOdinPicker: Boolean)
}
