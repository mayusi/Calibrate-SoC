package io.github.mayusi.calibratesoc.data.util

/** kHz → MHz (integer division). CPU policy frequencies are reported in kHz. */
fun Int.khzToMhz(): Int = this / 1_000

/** Hz → MHz (integer division). GPU frequencies are reported in Hz. */
fun Long.hzToMhz(): Long = this / 1_000_000L

/** milli-Celsius → Celsius as a Float. Thermal zone temps are reported in milli-°C. */
fun Int.milliCToC(): Float = this / 1_000f
