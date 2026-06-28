package io.github.mayusi.calibratesoc.data.autotdp.adaptive

/**
 * UNIT 1 (ADAPTIVE MODE) — the INTENT MODEL: a single normalized weight vector.
 *
 * Adaptive mode does not pick a fixed goal. The user (via a preset or a future
 * fine-tune surface) expresses an INTENT as four weights — how much they care about
 * raw performance, battery life, frame stability, and thermal headroom. Those four
 * numbers are the ONLY input to [AdaptivePolicy.resolve], which deterministically
 * maps them onto a concrete [AdaptiveSetpoints] the control engine rides.
 *
 * ## What the four weights mean (their lever in the policy)
 *
 *  - [wPerformance]     clock MAGNITUDE — pushes the cpu goal toward MAX_FPS, raises
 *                       the GPU band center + floor fraction, unlocks GPU OC tiers,
 *                       biases DDR high. The "everything to the front" axis.
 *  - [wBattery]         the OPPOSITE pull on clock magnitude — drags the cpu goal
 *                       toward BATTERY_SAVER, lowers the band center, biases DDR low.
 *                       perf and battery oppose on the same magnitude axis
 *                       (perfMinusBatt = wPerformance - wBattery).
 *  - [wStability]       band WIDTH + loosen rate — a wider GPU dead-band and a one-notch
 *                       shift toward the wider-band / slower-loosen goal (kills
 *                       oscillation; trades a little efficiency for steadiness).
 *  - [wThermalHeadroom] soft-temp + pre-empt EARLINESS — tightens the cpu temp ceiling
 *                       and the GPU soft-temp, so the controller pre-empts heat sooner.
 *
 * ## Normalization contract
 *
 * The four weights are conceptually a simplex: each in 0..1, summing to 1.0. Callers
 * may pass un-normalized vectors (e.g. raw slider values); [normalized] renormalizes
 * to sum = 1. An all-zero vector is meaningless, so [normalized] degrades it to
 * [BALANCED] (an even 0.25 split) rather than dividing by zero.
 *
 * PURE: a plain value object. No Android, no I/O, no time. Fully unit-testable, and
 * the STABLE public input type that Units 2/4/5 thread in.
 */
data class AdaptiveIntent(
    /** How much raw performance matters (clock magnitude up). 0..1. */
    val wPerformance: Float,
    /** How much battery life matters (clock magnitude down). 0..1. */
    val wBattery: Float,
    /** How much frame stability matters (wider band, slower loosen). 0..1. */
    val wStability: Float,
    /** How much thermal headroom matters (tighter soft-temps, earlier pre-empt). 0..1. */
    val wThermalHeadroom: Float,
) {

    /**
     * The sum of the four weights as supplied (before renormalization). Exposed for
     * tests / diagnostics; the policy always consumes [normalized].
     */
    val rawSum: Float get() = wPerformance + wBattery + wStability + wThermalHeadroom

    /**
     * Returns a copy whose four weights sum to exactly 1.0 (a true simplex point).
     *
     * If every weight is <= 0 (an all-zero or degenerate vector) the intent is
     * meaningless, so we degrade to the BALANCED default (0.25 each) instead of
     * dividing by zero. Negative inputs are floored to 0 first so a stray negative
     * slider can never invert a weight.
     */
    fun normalized(): AdaptiveIntent {
        val p = wPerformance.coerceAtLeast(0f)
        val b = wBattery.coerceAtLeast(0f)
        val s = wStability.coerceAtLeast(0f)
        val t = wThermalHeadroom.coerceAtLeast(0f)
        val sum = p + b + s + t
        if (sum <= 0f) return AdaptivePreset.BALANCED.intent
        return AdaptiveIntent(
            wPerformance = p / sum,
            wBattery = b / sum,
            wStability = s / sum,
            wThermalHeadroom = t / sum,
        )
    }

    companion object {
        /** Convenience accessor for the even-split default (mirrors BALANCED's intent). */
        val BALANCED: AdaptiveIntent
            get() = AdaptiveIntent(0.25f, 0.25f, 0.25f, 0.25f)
    }
}
