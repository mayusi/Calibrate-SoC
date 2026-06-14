package io.github.mayusi.calibratesoc.data.benchmark

/**
 * Pure predicate: should the runner abort because battery charge is critically
 * low?
 *
 * Abort conditions (ALL must be true):
 *   1. [percent] is non-null — we have a real, confirmed reading.
 *   2. [percent] < 15 — charge is below the 15% threshold.
 *   3. [charging] != true — we are NOT confirmed to be charging.
 *
 * Charging-state interpretation:
 *   - charging == true  → definitely charging → do NOT abort (safe to continue).
 *   - charging == false → definitely discharging → abort if percent < 15.
 *   - charging == null  → status unknown → abort if percent < 15 (safer: the
 *     device is running down and we cannot confirm it is charging).
 *
 * This is the SAFER option: we abort unless we KNOW the battery is charging.
 * The rationale is that a device with 12% charge and unknown charging state is
 * more likely to shut off mid-run (damaging the result) than a false abort.
 *
 * HONESTY: a null [percent] always returns false — we never abort on a missing
 * reading. Only a confirmed low-charge reading triggers an abort.
 */
internal fun shouldAbortLowBattery(percent: Int?, charging: Boolean?): Boolean {
    if (percent == null) return false       // unknown reading → do not abort
    if (percent >= 15) return false         // charge is fine → do not abort
    return charging != true                 // abort unless we KNOW it's charging
}
