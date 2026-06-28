package io.github.mayusi.calibratesoc.data.util

/** kHz → MHz (integer division). CPU policy frequencies are reported in kHz. */
fun Int.khzToMhz(): Int = this / 1_000

/** Hz → MHz (integer division). GPU frequencies are reported in Hz. */
fun Long.hzToMhz(): Long = this / 1_000_000L

/** milli-Celsius → Celsius as a Float. Thermal zone temps are reported in milli-°C. */
fun Int.milliCToC(): Float = this / 1_000f

/** Hz → MHz as Int (integer division). Convenience variant when an Int result is needed. */
fun Long.hzToMhzInt(): Int = (this / 1_000_000L).toInt()

/**
 * Instantaneous power in milliwatts from microamps and microvolts.
 * Formula: µA × µV / 1_000_000_000 = mW (the 1e9 factor collapses µA×µV → µW → mW).
 * Sign convention: caller normalises [ua] to a positive value before calling.
 */
fun Long.mwFromUaUv(uv: Long): Long = (this * uv) / 1_000_000_000L
