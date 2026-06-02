package io.github.mayusi.calibratesoc.data.benchmark

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport

/**
 * BenchRating — honest, self-relative benchmark rating.
 *
 * Two-part design (see plan § "BenchRating — EXACT spec"):
 *
 * Part 1 — SoC marketing class (publicly known, stable).
 *   A small map from SoC family substring → a class word. This is the
 *   ONLY hardcoded knowledge and it's safe because it's the chip's
 *   market tier, not a benchmark number.
 *
 * Part 2 — "% of this chip's own ceiling" (self-relative, no magic numbers).
 *   cpuCeilingPct = (sum of snapshot policy maxKhz) / (sum of report policy
 *   hardware ceiling). Falls back to availableFreqsKhz.maxOrNull() per
 *   policy when hardwareLimitsKhz is null. Null when no ceiling source
 *   is available for any policy.
 *
 * Rating word = function of (aborted vs completed) + cpuCeilingPct thresholds.
 */
object BenchRating {

    // ─── SoC class map ────────────────────────────────────────────────

    enum class BenchClass(val phrase: String) {
        FLAGSHIP("flagship-class silicon"),
        UPPER_MID("upper-mid silicon"),
        MID("mid-range silicon"),
        UNKNOWN("your device"),
    }

    /** Derive SoC marketing class from the soc identity strings.
     *  Uses lowercase substring matching — same style as SocFriendlyNames.
     *
     *  IMPORTANT: report.soc.socModel is the RAW codename ("QCS8550",
     *  "CQ8725S", "SM8550"), NOT the marketing name — so we resolve the
     *  friendly name ("Snapdragon 8 Gen 2") via SocFriendlyNames FIRST and
     *  fold it into the match string. Without this, every real handheld
     *  (Odin 3 / Thor / RP6) falls through to UNKNOWN because the raw
     *  codename never contains "8 gen 2" / "8 elite". */
    fun benchClass(report: CapabilityReport): BenchClass {
        val manuf = report.soc.socManufacturer.lowercase()
        val model = report.soc.socModel.lowercase()
        val friendly = io.github.mayusi.calibratesoc.data.hardware.SocFriendlyNames
            .lookup(report.soc.socModel)?.friendly?.lowercase().orEmpty()
        val combined = "$manuf $model $friendly"
        return when {
            // Qualcomm Flagship: 8 Elite, 8 Gen 3, 8 Gen 2
            "8 elite" in combined || "8gen3" in combined ||
                "8 gen 3" in combined || "8gen2" in combined ||
                "8 gen 2" in combined -> BenchClass.FLAGSHIP

            // MediaTek Flagship: Dimensity 9xxx
            "dimensity 9" in combined -> BenchClass.FLAGSHIP

            // Samsung Exynos 2400+
            "exynos 24" in combined || "exynos 25" in combined -> BenchClass.FLAGSHIP

            // Qualcomm Upper-mid: G3x Gen 2, 7+ Gen 2/3, 778G
            "g3x gen 2" in combined || "g3x gen2" in combined ||
                "7+ gen 2" in combined || "7+ gen 3" in combined ||
                "7+gen2" in combined || "7+gen3" in combined ||
                "778g" in combined -> BenchClass.UPPER_MID

            // MediaTek Upper-mid: Dimensity 8xxx
            "dimensity 8" in combined -> BenchClass.UPPER_MID

            // Qualcomm Mid: 6xx series, G3x Gen 1
            "snapdragon 6" in combined || "g3x gen 1" in combined ||
                "g3x gen1" in combined -> BenchClass.MID

            // MediaTek Mid: Helio G99, Unisoc T-series, Tegra
            "helio g9" in combined || "helio g8" in combined ||
                "unisoc t" in combined || "tegra" in combined -> BenchClass.MID

            else -> BenchClass.UNKNOWN
        }
    }

    // ─── Rating bands ─────────────────────────────────────────────────

    enum class RatingColor { TERTIARY, PRIMARY, SECONDARY, OUTLINE }

    /**
     * The returned rating from [rate].
     *
     * @param word         Human-readable rating word, or null if the run
     *                     was aborted (show [abortReason] instead).
     * @param color        Material3 color enum for the rating chip.
     * @param oneSentence  Headline sentence combining class + ceiling info.
     * @param benchClass   SoC marketing class.
     * @param classPhrase  Phrase for [benchClass], e.g. "flagship-class silicon".
     * @param cpuCeilingPct Fraction (0..1) of hardware ceiling used, or null.
     * @param abortReason  Non-null only when the run was aborted.
     */
    data class Rating(
        val word: String?,
        val color: RatingColor,
        val oneSentence: String,
        val benchClass: BenchClass,
        val classPhrase: String,
        val cpuCeilingPct: Double?,
        val abortReason: String?,
    )

    /**
     * Compute the rating for a completed benchmark run.
     *
     * [run]    — the BenchRun record.
     * [report] — the CapabilityReport captured at run time (used for
     *            hardware ceiling look-up + SoC class).
     */
    fun rate(run: BenchRun, report: CapabilityReport): Rating {
        val bc = benchClass(report)
        val phrase = bc.phrase

        // ── Aborted runs ──────────────────────────────────────────────
        val abortReason: String? = when (run.outcome) {
            BenchOutcome.ABORTED_TEMP -> "Aborted — thermal limit reached"
            BenchOutcome.ABORTED_BATTERY -> "Aborted — low battery"
            BenchOutcome.ABORTED_DURATION -> "Aborted — time limit"
            BenchOutcome.ABORTED_USER -> "Aborted by user"
            BenchOutcome.FAILED_NATIVE -> "Benchmark failed (native crash)"
            BenchOutcome.COMPLETED -> null
        }
        if (abortReason != null) {
            val sentence = buildString {
                append(abortReason)
                if (bc != BenchClass.UNKNOWN) append(" — $phrase.")
            }
            return Rating(
                word = null,
                color = RatingColor.OUTLINE,
                oneSentence = sentence,
                benchClass = bc,
                classPhrase = phrase,
                cpuCeilingPct = null,
                abortReason = abortReason,
            )
        }

        // ── CPU ceiling % (Part 2) ────────────────────────────────────
        val cpuCeilingPct = computeCpuCeilingPct(run, report)

        // ── Rating word ───────────────────────────────────────────────
        val (word, color) = when {
            cpuCeilingPct == null -> Pair("Completed", RatingColor.OUTLINE)
            cpuCeilingPct >= 0.97 -> Pair("Full power", RatingColor.TERTIARY)
            cpuCeilingPct >= 0.80 -> Pair("Strong", RatingColor.PRIMARY)
            cpuCeilingPct >= 0.55 -> Pair("Tuned down", RatingColor.SECONDARY)
            else -> Pair("Heavily underclocked", RatingColor.OUTLINE)
        }

        // ── Headline sentence ─────────────────────────────────────────
        val pctStr = cpuCeilingPct?.let { "~%.0f%%".format(it * 100) }
        val sentence = when {
            cpuCeilingPct == null -> {
                if (bc != BenchClass.UNKNOWN) "Completed — $phrase." else "Completed."
            }
            cpuCeilingPct >= 0.97 -> {
                val base = "Running at full power — stock ceiling."
                if (bc != BenchClass.UNKNOWN) "Running at full power — $phrase at its stock ceiling." else base
            }
            cpuCeilingPct >= 0.80 -> {
                val base = "Running at $pctStr of this chip's ceiling — mild underclock or normal governor behavior."
                if (bc != BenchClass.UNKNOWN) "$base $phrase.".replaceFirstChar { it.uppercase() } else base
            }
            cpuCeilingPct >= 0.55 -> {
                val base = "Tuned down to $pctStr of this chip's ceiling — cooler & longer battery, lower peak score (expected)."
                if (bc != BenchClass.UNKNOWN) "$base $phrase.".replaceFirstChar { it.uppercase() } else base
            }
            else -> {
                val base = "Heavily underclocked at $pctStr of this chip's ceiling."
                if (bc != BenchClass.UNKNOWN) "$base $phrase." else base
            }
        }

        return Rating(
            word = word,
            color = color,
            oneSentence = sentence,
            benchClass = bc,
            classPhrase = phrase,
            cpuCeilingPct = cpuCeilingPct,
            abortReason = null,
        )
    }

    /**
     * Compute cpuCeilingPct = sum(snapshot maxKhz) / sum(hardware ceiling).
     *
     * Per-policy ceiling priority:
     *   1. report.cpuPolicies[i].hardwareLimitsKhz?.highKhz
     *   2. report.cpuPolicies[i].availableFreqsKhz.maxOrNull()
     *   3. null → skip this policy from both numerator and denominator
     *
     * Policies matched by policyId. Returns null when no ceiling is
     * available for ANY policy (→ rating word "Completed", no ceiling phrasing).
     */
    private fun computeCpuCeilingPct(run: BenchRun, report: CapabilityReport): Double? {
        // Build lookup: policyId → ceiling kHz from the report
        val ceilingByPolicy: Map<Int, Int> = report.cpuPolicies.mapNotNull { p ->
            val ceiling = p.hardwareLimitsKhz?.highKhz
                ?: p.availableFreqsKhz.maxOrNull()
                ?: return@mapNotNull null
            p.policyId to ceiling
        }.toMap()

        if (ceilingByPolicy.isEmpty()) return null

        // Match snapshot policies to report ceilings
        var sumSnapshot = 0L
        var sumCeiling = 0L
        var matched = 0
        for (sp in run.snapshot.cpuPolicies) {
            val ceiling = ceilingByPolicy[sp.policyId] ?: continue
            sumSnapshot += sp.maxKhz
            sumCeiling += ceiling
            matched++
        }

        if (matched == 0 || sumCeiling == 0L) return null
        return (sumSnapshot.toDouble() / sumCeiling.toDouble()).coerceIn(0.0, 1.0)
    }
}
