package io.github.mayusi.calibratesoc.data.autotdp.adaptive

/**
 * UNIT 1 (ADAPTIVE MODE) — the PRESET SPECTRUM: five curated [AdaptiveIntent]s.
 *
 * These five presets are the user-facing entry points into adaptive mode — a single
 * slider from "everything to the front" to "stretch the charge". Each is just a named
 * point in the four-weight simplex; selecting a preset hands its [intent] straight to
 * [AdaptivePolicy.resolve]. A future fine-tune surface can edit the weights directly,
 * but these five are the sensible anchors.
 *
 * The weight tuples below ARE the approved design values (§3.2). They are normalized
 * (each tuple sums to 1.0) so [AdaptiveIntent.normalized] is a no-op on them. Do not
 * "round" them — Units 2/4/5 assert the resolved setpoints derived from these exact
 * numbers.
 *
 *   | Preset          | perf | batt | stab | thermal |
 *   |-----------------|------|------|------|---------|
 *   | MAX_PERFORMANCE | 0.60 | 0.00 | 0.20 | 0.20    |
 *   | PERFORMANCE     | 0.45 | 0.10 | 0.30 | 0.15    |
 *   | BALANCED        | 0.25 | 0.25 | 0.25 | 0.25    |
 *   | EFFICIENCY      | 0.10 | 0.45 | 0.20 | 0.25    |
 *   | MAX_BATTERY     | 0.00 | 0.60 | 0.15 | 0.25    |
 *
 * PURE: a value enum with no Android, I/O, or time.
 */
enum class AdaptivePreset(
    /** The four-weight intent this preset expresses (already sums to 1.0). */
    val intent: AdaptiveIntent,
    /** Short user-facing label. */
    val displayName: String,
    /** One-line description of the trade-off this preset makes. */
    val description: String,
) {
    /** Everything to the front; heat allowed up to the safety pre-empt. */
    MAX_PERFORMANCE(
        intent = AdaptiveIntent(0.60f, 0.00f, 0.20f, 0.20f),
        displayName = "Max Performance",
        description = "Everything to the front. Heat allowed up to safety.",
    ),

    /** Fast and mostly stable, with a small power sense. */
    PERFORMANCE(
        intent = AdaptiveIntent(0.45f, 0.10f, 0.30f, 0.15f),
        displayName = "Performance",
        description = "Fast and mostly stable — small power sense.",
    ),

    /** Even split across all four axes. The sensible default. */
    BALANCED(
        intent = AdaptiveIntent(0.25f, 0.25f, 0.25f, 0.25f),
        displayName = "Balanced",
        description = "Even split. The sensible default.",
    ),

    /** Save power while keeping it smooth and cool. */
    EFFICIENCY(
        intent = AdaptiveIntent(0.10f, 0.45f, 0.20f, 0.25f),
        displayName = "Efficiency",
        description = "Save power, keep it smooth and cool.",
    ),

    /** Stretch the charge; clocks stay low. */
    MAX_BATTERY(
        intent = AdaptiveIntent(0.00f, 0.60f, 0.15f, 0.25f),
        displayName = "Max Battery",
        description = "Stretch the charge. Clocks stay low.",
    ),
    ;

    companion object {
        /** The product default preset when nothing else is specified. */
        val DEFAULT = BALANCED
    }
}
