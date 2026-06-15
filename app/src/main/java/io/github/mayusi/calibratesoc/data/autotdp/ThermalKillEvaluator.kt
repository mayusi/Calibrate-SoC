package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp

/**
 * Stateful thermal kill evaluator for the AutoTDP daemon.
 *
 * Design rationale (three fixes):
 *
 * 1. THRESHOLD raised from 95 °C → 105 °C.
 *    Snapdragon 8 Gen 3 / Odin 3 routinely reaches 85–95 °C on die sensors
 *    during sustained gaming. A 95 °C abort kills the daemon the instant a
 *    game gets warm, then reverts writes — which RAISES clocks back to stock
 *    and makes the device run hotter. That is the opposite of AutoTDP's job.
 *    105 °C is still safely below the kernel's own thermal trip (~110 °C) and
 *    only fires in genuine thermal emergencies.
 *
 * 2. DEBOUNCE: require [requiredConsecutive] consecutive over-threshold
 *    samples before declaring a kill.
 *    Instantaneous sensors (cpu-N-N-N junction zones) can spike to 105+ for a
 *    single sample and then drop back. A one-sample kill + full clock revert is
 *    the wrong response — it removes the cap that was keeping the device cool.
 *    Two consecutive samples gives ~2 s (at 1 Hz polling) of confirmation before
 *    aborting, which is enough to distinguish a transient spike from sustained
 *    danger.
 *
 * 3. ZONE COVERAGE: the kill checks ALL zones that arrive in the telemetry
 *    sample (same as before) — this is intentional. Any zone reaching 105 °C
 *    is a genuine emergency regardless of type. We do not filter to CPU-only
 *    because a GPU or modem zone at 105 °C is equally dangerous.
 *
 * Thread safety: this object must only be called from a single coroutine
 * (the daemon loop). It is NOT thread-safe.
 */
class ThermalKillEvaluator(
    /** Temperature at which a kill is declared (milli-°C). */
    val killThresholdMilliC: Int = KILL_THRESHOLD_MILLI_C,
    /**
     * Number of back-to-back over-threshold samples required before declaring
     * a kill. Default = 2 (current sample + 1 predecessor).
     */
    val requiredConsecutive: Int = REQUIRED_CONSECUTIVE,
) {
    private var consecutiveOverThreshold = 0

    /**
     * Feed one [Telemetry] sample. Returns a human-readable kill reason string
     * when the kill should be declared, or null when the daemon should continue.
     *
     * The kill is declared only after [requiredConsecutive] consecutive calls
     * where the hottest zone exceeds [killThresholdMilliC]. A single below-
     * threshold sample resets the counter (the thermal situation improved).
     */
    fun evaluate(sample: Telemetry): String? {
        val hotZone = sample.zoneTempsMilliC.maxByOrNull { it.tempMilliC }
            ?: return null // no zone data — don't kill (can't read = can't confirm danger)

        if (hotZone.tempMilliC >= killThresholdMilliC) {
            consecutiveOverThreshold++
            if (consecutiveOverThreshold >= requiredConsecutive) {
                return buildKillReason(hotZone, consecutiveOverThreshold)
            }
            // Not yet enough consecutive samples — warn but hold.
            return null
        } else {
            // Below threshold — reset counter.
            consecutiveOverThreshold = 0
            return null
        }
    }

    /** Reset the consecutive counter (call when restarting the daemon loop). */
    fun reset() {
        consecutiveOverThreshold = 0
    }

    private fun buildKillReason(hotZone: ZoneTemp, consecutive: Int): String {
        val tempC = hotZone.tempMilliC / 1000
        val threshC = killThresholdMilliC / 1000
        return "Thermal kill: ${hotZone.label} ${tempC}°C ≥ ${threshC}°C " +
            "(${consecutive} consecutive samples)"
    }

    companion object {
        /**
         * Kill threshold in milli-°C: 105 000 = 105 °C.
         *
         * Rationale: Snapdragon 8 Gen 3 (Odin 3) junction sensors reach 85–95 °C
         * during sustained gaming. The kernel's own thermal throttle trip point is
         * ~110 °C. 105 °C leaves a 5 °C margin before kernel throttle fires, while
         * being well above normal sustained-gaming temps so AutoTDP stays active
         * and can do its actual job (capping clocks to keep the device cool).
         *
         * Do not lower this below ~100 °C for SD8Gen3 devices without also
         * narrowing the kill check to sustained-die zones only — instantaneous
         * sensors spike above 95 °C under normal load.
         */
        const val KILL_THRESHOLD_MILLI_C = 105_000

        /**
         * Number of consecutive over-threshold samples required before aborting.
         *
         * At the default 1 Hz polling rate this equates to ≥ 2 seconds of
         * confirmed over-threshold temperature. One transient spike does NOT kill
         * the daemon. Two in a row is a sustained emergency.
         */
        const val REQUIRED_CONSECUTIVE = 2
    }
}
