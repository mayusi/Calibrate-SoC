package io.github.mayusi.calibratesoc.data.vendor

import android.content.ContentResolver
import android.util.Log
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult

private const val TAG = "FanModeIo"

/**
 * Shared fan-mode Settings.System I/O helpers used by both
 * [io.github.mayusi.calibratesoc.data.profiles.ForegroundAppWatcher] and
 * [io.github.mayusi.calibratesoc.data.autotdp.ChargingTuneTrigger].
 *
 * Extracted to eliminate byte-for-byte duplication. Both callers own their own
 * fan-key resolution logic (different dependencies) — only the read/write
 * primitives are shared here.
 */

/**
 * Read the current fan_mode Settings.System value.
 * Returns null on any error (safe — callers log the gap as needed).
 */
fun readFanMode(contentResolver: ContentResolver, key: String): String? = runCatching {
    android.provider.Settings.System.getString(contentResolver, key)
}.getOrNull()

/**
 * Write a fan_mode value through [TunableWriter]. Routes to the PServer/SETTINGS_SYSTEM
 * path. Logs a warning on any non-success result.
 *
 * Returns the [WriteResult] so callers can record revert state ONLY on success
 * (a recorded before-state for a failed write would corrupt revert).
 */
suspend fun writeFanMode(
    tunableWriter: TunableWriter,
    key: String,
    value: String,
    report: CapabilityReport,
    reason: String,
): WriteResult {
    val id = TunableId(kind = TunableKind.SETTINGS_SYSTEM, target = key)
    val result = tunableWriter.write(id = id, value = value, report = report, reason = reason)
    if (result !is WriteResult.Success) {
        Log.w(TAG, "writeFanMode($key=$value): ${result::class.simpleName}")
    }
    return result
}
