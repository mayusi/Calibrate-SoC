package io.github.mayusi.calibratesoc.data.benchmark

import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.tunables.WriteResult

/**
 * Honesty-first data model for a comparative A/B benchmark.
 *
 * Two profiles are applied in sequence on the same device and the same
 * benchmark flavor is run under each. The result carries full apply-status
 * information so the UI can honestly report whether a profile was
 * confirmed-applied or merely attempted.
 *
 * HONESTY CONTRACT:
 *   - [ApplyStatus.CONFIRMED]     — at least one CPU-policy write succeeded (Success).
 *   - [ApplyStatus.UNVERIFIABLE]  — device has no writable sysfs paths (no-root /
 *                                   SELinux denial); the benchmark ran but the profile
 *                                   may not have taken effect. Surfaces clearly in UI.
 *   - [ApplyStatus.FAILED]        — every write was CapabilityDenied/Rejected/Failed
 *                                   and a benchmark was not attempted for this slot.
 *
 * Callers inspect [ComparativeSlot.applyStatus] before trusting the [BenchRun] and
 * must NOT present a failed/unverifiable slot as a valid profile-benchmark result.
 */

/** How confident we are that a profile was active when its benchmark ran. */
enum class ApplyStatus {
    /**
     * At least one WriteResult.Success was returned for a CPU-policy node.
     * The profile was verifiably applied before the benchmark ran.
     */
    CONFIRMED,

    /**
     * All writes came back CapabilityDenied or Rejected (SELinux, no-root, etc.)
     * but at least one write was ATTEMPTED for a non-empty preset. The benchmark
     * ran anyway so the user gets a number, but the label "may not have applied"
     * is shown prominently. This is the truthful experience on a locked device.
     */
    UNVERIFIABLE,

    /**
     * The profile has an empty preset (no CPU/GPU knobs at all — e.g. a profile
     * saved with no tuning), OR every write returned Failed (crash/IO). The
     * benchmark was skipped for this slot; [ComparativeSlot.run] is null.
     */
    FAILED,
}

/**
 * One half of a comparative run — profile + how well it applied + the run it produced.
 *
 * @param profile      The UserProfile that was meant to be applied.
 * @param applyStatus  Honesty gate: CONFIRMED / UNVERIFIABLE / FAILED.
 * @param applyDetails Human-readable summary of write results (e.g. "3/4 policies applied").
 * @param run          The BenchRun captured under this profile, or null when [applyStatus]
 *                     is FAILED and the benchmark was intentionally skipped.
 * @param cancelledMidRun True if the user cancelled while this slot's benchmark was running.
 *                     [run] may still be non-null (partial ABORTED_USER result) but the
 *                     comparison is partial.
 */
data class ComparativeSlot(
    val profile: UserProfile,
    val applyStatus: ApplyStatus,
    val applyDetails: String,
    val run: BenchRun?,
    val cancelledMidRun: Boolean = false,
)

/**
 * Which of two slots won a given metric category. TIE when scores are equal or
 * neither slot has data for that category.
 */
enum class CategoryWinner { A, B, TIE, NO_DATA }

/**
 * Per-category winner comparison for a single metric.
 *
 * For CPU/GPU/Memory/Overall the "higher is better" rule applies universally.
 * For throttle drop% "lower is better" — indicated by [lowerIsBetter] = true.
 * For sustained MHz "higher is better" — lowerIsBetter = false.
 */
data class CategoryDelta(
    val label: String,
    val valueA: Double?,
    val valueB: Double?,
    val valFmt: String,          // e.g. "%.0f" or "%.1f"
    val unit: String = "",       // e.g. " MHz", " MB/s", " FPS", "%"
    val lowerIsBetter: Boolean = false,
    val winner: CategoryWinner,
)

/**
 * The full result of a comparative A/B benchmark sequence.
 *
 * Built by [io.github.mayusi.calibratesoc.ui.benchmark.ComparativeABViewModel] after
 * both slots complete (or after a cancellation). The UI renders this directly.
 *
 * @param slotA   Profile A slot (applied first).
 * @param slotB   Profile B slot (applied second).
 * @param flavor  Benchmark flavor used for both runs.
 * @param deltas  Per-category deltas + winner. Empty when either run is missing.
 * @param partial True when the sequence was cancelled before slot B ran. [slotB.run]
 *                may be null in this case.
 */
data class ComparativeResult(
    val slotA: ComparativeSlot,
    val slotB: ComparativeSlot,
    val flavor: BenchFlavor,
    val deltas: List<CategoryDelta>,
    val partial: Boolean,
) {
    companion object {

        /**
         * Build a [ComparativeResult] from two complete/partial slots.
         *
         * Delta + winner logic:
         *   - Both runs must be non-null and have COMPLETED outcome for a delta to count
         *     as a clean winner. Aborted runs still produce numbers but are flagged
         *     with their outcome in the slot.
         *   - For each category, if both values are null → NO_DATA.
         *   - If one is null → the other wins (partial data).
         *   - Equal values (within 0.1%) → TIE.
         *   - Otherwise the higher value wins unless [lowerIsBetter].
         */
        fun build(
            slotA: ComparativeSlot,
            slotB: ComparativeSlot,
            flavor: BenchFlavor,
            partial: Boolean,
        ): ComparativeResult {
            val deltas = if (slotA.run != null && slotB.run != null) {
                buildDeltas(slotA.run, slotB.run)
            } else {
                emptyList()
            }
            return ComparativeResult(slotA, slotB, flavor, deltas, partial)
        }

        /**
         * Compute all per-category deltas given two BenchRuns.
         * Pure function — no I/O, no side effects. Fully unit-testable.
         */
        fun buildDeltas(runA: BenchRun, runB: BenchRun): List<CategoryDelta> {
            val scoresA = BenchScores.from(runA)
            val scoresB = BenchScores.from(runB)
            val throttleA = ThrottleAnalysis.from(runA.throttleSamples)
            val throttleB = ThrottleAnalysis.from(runB.throttleSamples)

            return listOf(
                // ── Composite + category scores ───────────────────────
                delta(
                    "Overall score",
                    runA.overallScore?.toDouble(), runB.overallScore?.toDouble(),
                    "%.0f",
                ),
                delta(
                    "CPU score",
                    scoresA.cpu?.toDouble(), scoresB.cpu?.toDouble(),
                    "%.0f",
                ),
                delta(
                    "GPU score",
                    scoresA.gpu?.toDouble(), scoresB.gpu?.toDouble(),
                    "%.0f",
                ),
                delta(
                    "Memory score",
                    scoresA.memory?.toDouble(), scoresB.memory?.toDouble(),
                    "%.0f",
                ),
                // ── Raw kernel numbers ────────────────────────────────
                delta(
                    "CPU int 1T",
                    runA.kernels.cpuIntegerSingle?.toDouble(),
                    runB.kernels.cpuIntegerSingle?.toDouble(),
                    "%.0f",
                ),
                delta(
                    "CPU int MT",
                    runA.kernels.cpuIntegerMulti?.toDouble(),
                    runB.kernels.cpuIntegerMulti?.toDouble(),
                    "%.0f",
                ),
                delta(
                    "CPU float",
                    runA.kernels.cpuFloat?.toDouble(),
                    runB.kernels.cpuFloat?.toDouble(),
                    "%.0f",
                ),
                delta(
                    "AES-128",
                    runA.kernels.cpuAes?.toDouble(),
                    runB.kernels.cpuAes?.toDouble(),
                    "%.0f",
                ),
                delta(
                    "RAM bandwidth",
                    runA.kernels.memoryBandwidthMBps,
                    runB.kernels.memoryBandwidthMBps,
                    "%.1f",
                    " MB/s",
                ),
                delta(
                    "GPU avg FPS",
                    runA.kernels.gpuFps,
                    runB.kernels.gpuFps,
                    "%.1f",
                    " FPS",
                ),
                delta(
                    "GPU 1% low",
                    runA.kernels.gpuP1LowFps,
                    runB.kernels.gpuP1LowFps,
                    "%.1f",
                    " FPS",
                ),
                delta(
                    "Consistency",
                    runA.kernels.gpuFrameConsistencyPct,
                    runB.kernels.gpuFrameConsistencyPct,
                    "%.0f",
                    "%",
                ),
                // ── Throttle (FULL runs only) ─────────────────────────
                delta(
                    "Sustained MHz",
                    throttleA?.sustainedMhz?.toDouble(),
                    throttleB?.sustainedMhz?.toDouble(),
                    "%.0f",
                    " MHz",
                ),
                delta(
                    "Throttle drop",
                    throttleA?.dropPct,
                    throttleB?.dropPct,
                    "%.1f",
                    "%",
                    lowerIsBetter = true,
                ),
                delta(
                    "Energy used",
                    throttleA?.energyMwh,
                    throttleB?.energyMwh,
                    "%.1f",
                    " mWh",
                    lowerIsBetter = true,
                ),
                delta(
                    "Avg power",
                    throttleA?.avgPowerMw?.let { it / 1000.0 },
                    throttleB?.avgPowerMw?.let { it / 1000.0 },
                    "%.2f",
                    " W",
                    lowerIsBetter = true,
                ),
            ).filter { it.valueA != null || it.valueB != null }
        }

        /** Build one CategoryDelta and compute the winner. */
        private fun delta(
            label: String,
            av: Double?,
            bv: Double?,
            valFmt: String,
            unit: String = "",
            lowerIsBetter: Boolean = false,
        ): CategoryDelta {
            val winner = computeWinner(av, bv, lowerIsBetter)
            return CategoryDelta(label, av, bv, valFmt, unit, lowerIsBetter, winner)
        }

        /**
         * Determine which slot wins for a single metric.
         *
         * Rules (pure, no side-effects — isolated for unit testing):
         *   - Both null → NO_DATA
         *   - A null, B present → B wins (partial data advantage)
         *   - A present, B null → A wins (partial data advantage)
         *   - Both present, equal within 0.1% → TIE
         *   - lowerIsBetter=false: higher wins
         *   - lowerIsBetter=true:  lower wins
         */
        fun computeWinner(
            av: Double?,
            bv: Double?,
            lowerIsBetter: Boolean,
        ): CategoryWinner {
            if (av == null && bv == null) return CategoryWinner.NO_DATA
            if (av == null) return CategoryWinner.B
            if (bv == null) return CategoryWinner.A

            // Tie-band: within 0.1% of the larger value
            val larger = maxOf(av, bv).let { if (it == 0.0) 1.0 else it }
            if (kotlin.math.abs(av - bv) / larger < 0.001) return CategoryWinner.TIE

            return when {
                lowerIsBetter -> if (av < bv) CategoryWinner.A else CategoryWinner.B
                else -> if (av > bv) CategoryWinner.A else CategoryWinner.B
            }
        }

        /**
         * Derive [ApplyStatus] from the list of [WriteResult]s returned by [ProfileApplier].
         *
         * - Any Success → CONFIRMED (at least one write landed).
         * - All CapabilityDenied/Rejected/Failed, but the preset was non-empty → UNVERIFIABLE.
         * - Preset was empty (no writes attempted) OR all writes were Failed (Throwable) → FAILED.
         *
         * Also returns a human-readable summary string.
         */
        fun applyStatusFrom(
            results: List<WriteResult>,
            presetHasTunables: Boolean,
        ): Pair<ApplyStatus, String> {
            if (!presetHasTunables) {
                return Pair(ApplyStatus.FAILED, "Profile has no tunable knobs — nothing to apply.")
            }
            if (results.isEmpty()) {
                return Pair(ApplyStatus.FAILED, "No writes were attempted.")
            }

            val successCount = results.count { it is WriteResult.Success }
            val failedCount  = results.count { it is WriteResult.Failed }
            val deniedCount  = results.count {
                it is WriteResult.CapabilityDenied || it is WriteResult.Rejected
            }
            val total = results.size

            return when {
                successCount > 0 -> Pair(
                    ApplyStatus.CONFIRMED,
                    "$successCount/$total writes succeeded.",
                )
                failedCount == total -> Pair(
                    ApplyStatus.FAILED,
                    "All $total writes failed (IO/crash). Profile not applied.",
                )
                (deniedCount + failedCount) == total -> Pair(
                    ApplyStatus.UNVERIFIABLE,
                    "All $total writes were denied (SELinux / no-root). " +
                        "Profile may not have taken effect — results are labelled accordingly.",
                )
                else -> Pair(
                    ApplyStatus.UNVERIFIABLE,
                    "$deniedCount/$total writes denied, $failedCount failed. Profile may not be active.",
                )
            }
        }

        /** True if the UserProfile has at least one tunable set (non-empty preset). */
        fun presetHasTunables(profile: UserProfile): Boolean =
            profile.cpuPolicyMaxKhz.isNotEmpty() ||
                profile.cpuPolicyMinKhz.isNotEmpty() ||
                profile.cpuPolicyGovernor.isNotEmpty() ||
                profile.gpuMaxHz != null ||
                profile.gpuMinHz != null ||
                profile.gpuGovernor != null ||
                profile.extraSysfs.isNotEmpty()
    }
}
