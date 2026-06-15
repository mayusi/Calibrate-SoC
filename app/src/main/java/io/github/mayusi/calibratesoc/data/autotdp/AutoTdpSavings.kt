package io.github.mayusi.calibratesoc.data.autotdp

/**
 * Measured draw-delta computation for the AutoTDP honesty proof.
 *
 * PURE math: no Android, no coroutines, no sampling loop. The daemon
 * (AutoTdpService — a later agent) handles the sampling using
 * [Telemetry.batteryDrawMilliW] and passes the collected lists here.
 *
 * The sampling contract expected by this class:
 *   baseline = list of mW readings taken BEFORE AutoTDP was enabled, under
 *              representative load (20 s at 1-4 Hz → 20-80 samples).
 *   tuned    = list of mW readings taken AFTER AutoTDP was enabled, under
 *              the SAME load.
 *
 * Results are always labelled "measured on your device, this session" in
 * the UI. We never fabricate a delta.
 */
object AutoTdpSavings {

    /** Minimum sample count before we claim the measurement is meaningful. */
    const val MIN_SAMPLES_FOR_REPORT = 10

    /**
     * Compute the draw delta between a baseline run and a tuned run.
     *
     * @param baseline  List of milliwatt readings without AutoTDP.
     * @param tuned     List of milliwatt readings with AutoTDP active.
     * @return          [SavingsResult] — check [SavingsResult.enoughData] before
     *                  surfacing the delta to the user.
     */
    fun computeSavings(baseline: List<Long>, tuned: List<Long>): SavingsResult {
        val validBaseline = baseline.filter { it > 0 }
        val validTuned = tuned.filter { it > 0 }

        val sampleCount = minOf(validBaseline.size, validTuned.size)
        val enoughData = sampleCount >= MIN_SAMPLES_FOR_REPORT

        if (!enoughData) {
            return SavingsResult(
                baselineMw = if (validBaseline.isNotEmpty()) validBaseline.average().toLong() else 0L,
                tunedMw = if (validTuned.isNotEmpty()) validTuned.average().toLong() else 0L,
                deltaMw = 0L,
                deltaPct = 0.0,
                sampleCount = sampleCount,
                enoughData = false,
            )
        }

        val baselineMw = validBaseline.average().toLong()
        val tunedMw = validTuned.average().toLong()
        val deltaMw = baselineMw - tunedMw // positive = AutoTDP saved power

        val deltaPct = if (baselineMw > 0) {
            (deltaMw.toDouble() / baselineMw.toDouble()) * 100.0
        } else {
            0.0
        }

        return SavingsResult(
            baselineMw = baselineMw,
            tunedMw = tunedMw,
            deltaMw = deltaMw,
            deltaPct = deltaPct,
            sampleCount = sampleCount,
            enoughData = true,
        )
    }
}

/**
 * The measured draw-delta between a baseline (AutoTDP off) and a tuned run
 * (AutoTDP on) under comparable load.
 *
 * [baselineMw]  — mean draw without AutoTDP (milliwatts).
 * [tunedMw]     — mean draw with AutoTDP active (milliwatts).
 * [deltaMw]     — baselineMw − tunedMw: positive = AutoTDP saved power.
 * [deltaPct]    — deltaMw / baselineMw * 100, or 0.0 when baseline is 0.
 * [sampleCount] — effective sample count (min of baseline and tuned sample counts).
 * [enoughData]  — false when sampleCount < [AutoTdpSavings.MIN_SAMPLES_FOR_REPORT].
 *                 When false, [deltaMw] and [deltaPct] must NOT be displayed as a
 *                 claimed saving — the UI should show "not enough data".
 */
data class SavingsResult(
    val baselineMw: Long,
    val tunedMw: Long,
    val deltaMw: Long,
    val deltaPct: Double,
    val sampleCount: Int,
    val enoughData: Boolean,
)
