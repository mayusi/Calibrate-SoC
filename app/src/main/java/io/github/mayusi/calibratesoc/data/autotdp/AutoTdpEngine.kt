package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.monitor.Telemetry

/**
 * The AutoTDP decision brain.
 *
 * PURE: no Android, no I/O, no time calls. All inputs are value objects;
 * the output is a [TdpDecision] that the daemon applies via TunableWriter.
 * This makes every decision path fully unit-testable without a device or
 * Android runtime.
 *
 * Algorithm overview — see AUTOTDP-DESIGN.md for the full rationale:
 *
 * 1. Smooth the telemetry window into a set of signals:
 *      gpuBound   = smoothed GPU load >= 80 % AND smoothed big/prime load < 40 %
 *      cpuBound   = ANY big/prime core sustained load >= 85 % (single sample enough)
 *
 * 2. Hysteresis (asymmetric):
 *      - Park / clamp: requires ALL window samples to agree (conservative).
 *      - Unpark / relax: fires on a SINGLE saturated sample (fast responsiveness floor).
 *
 * 3. State transitions:
 *      gpuBound & can park → park one more prime core (highest index first),
 *                            step big-cluster cap DOWN one OPP, hold GPU permissive.
 *      cpuBound           → unpark one prime core (lowest-parked first),
 *                            step big-cluster cap UP one OPP.
 *      otherwise          → hold current state ("holding").
 *
 * 4. Safety invariants (enforced on every path):
 *      - cpu0 never in parkedPrimeCores.
 *      - totalOnlineCores - |parkedPrimeCores| >= caps.minOnlineCores.
 *      - bigClusterCapKhz must be one of caps.bigClusterOppStepsKhz (or null).
 *      - BATTERY_TARGET: cap derived proportionally from targetMilliWatts vs the
 *        device's measured max draw proxy (top OPP draw).
 */
object AutoTdpEngine {

    // ── Thresholds ────────────────────────────────────────────────────────────

    /** GPU load % above which we consider the workload GPU-bound. */
    private const val GPU_BOUND_THRESHOLD = 80

    /** Big/prime cluster average load below which GPU-bound is confirmed. */
    private const val CPU_LOW_THRESHOLD = 40

    /** Big/prime core load above which we consider the CPU the bottleneck. */
    private const val CPU_SATURATED_THRESHOLD = 85

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Core decision function.
     *
     * @param window  Rolling window of recent [Telemetry] samples (~3-5).
     *                Caller is responsible for maintaining this window.
     * @param config  Active profile + optional target-watts budget.
     * @param caps    Immutable device envelope derived from [TdpCaps.from].
     * @param current The TdpState that was last applied (the daemon's current
     *                write target — NOT what the kernel actually has right now).
     * @return        A [TdpDecision] with the new desired state and a human-
     *                readable reason string for the HUD/log.
     */
    fun decide(
        window: List<Telemetry>,
        config: AutoTdpProfileConfig,
        caps: TdpCaps,
        current: TdpState,
    ): TdpDecision {
        if (window.isEmpty()) {
            return TdpDecision(current, "no telemetry — holding")
        }

        // ── Compute smoothed signals from window ──────────────────────────────
        val signals = computeSignals(window, caps)

        // ── Detect CPU saturation (fast path — asymmetric hysteresis) ─────────
        // cpuBound fires on ANY single sample with a saturated big/prime core.
        // This is the "fast unpark" path — we never leave the user in a stutter.
        if (signals.anySaturated) {
            return relaxState(current, caps, signals)
        }

        // ── Detect GPU-bound (slow path — requires full-window agreement) ─────
        // All samples in the window must agree that we're GPU-bound before we park.
        val shouldPark = when (config.profile) {
            AutoTdpProfile.EFFICIENCY -> signals.allGpuBound
            AutoTdpProfile.BALANCED   -> signals.allGpuBound && signals.smoothedGpuLoad >= 85
            AutoTdpProfile.BATTERY_TARGET -> signals.allGpuBound
        }

        if (shouldPark) {
            return tightenState(current, caps, config, signals)
        }

        // ── BATTERY_TARGET: apply proportional cap even when neither signal ────
        // fires, so the budget is held during mixed/idle workloads.
        if (config.profile == AutoTdpProfile.BATTERY_TARGET && config.targetMilliWatts != null) {
            val budgetCap = deriveBudgetCap(caps, config.targetMilliWatts)
            val currentCap = current.bigClusterCapKhz
            if (budgetCap != null && budgetCap != currentCap) {
                val reason = buildString {
                    append("battery-target ${config.targetMilliWatts} mW → ")
                    append("big cap ${budgetCap / 1000} MHz")
                }
                return TdpDecision(current.copy(bigClusterCapKhz = budgetCap), reason)
            }
        }

        return TdpDecision(current, "holding — ${signals.holdReason()}")
    }

    // ── Signal extraction ─────────────────────────────────────────────────────

    private data class WindowSignals(
        /** Smoothed (mean) GPU load across the window. */
        val smoothedGpuLoad: Int,
        /** Smoothed mean load of big+prime cores across the window. */
        val smoothedBigPrimeLoad: Int,
        /** True when ALL samples in the window are GPU-bound. */
        val allGpuBound: Boolean,
        /** True when ANY single sample has a big/prime core above the saturation threshold. */
        val anySaturated: Boolean,
        /** The indices of the big/prime cores (from caps — carried for reason strings). */
        val bigPrimeCoreSet: Set<Int>,
    ) {
        fun holdReason(): String = "GPU ${smoothedGpuLoad}%, big/prime ${smoothedBigPrimeLoad}%"
    }

    private fun computeSignals(window: List<Telemetry>, caps: TdpCaps): WindowSignals {
        // Identify big+prime core indices from caps.
        // bigPrimeSet = primeCoreIndices union any cores in the same big policy.
        // For simplicity (and because primeCoreIndices already excludes cpu0),
        // we treat primeCoreIndices as the monitoring set. Cores 0..N not in
        // the prime cluster are little/gold and are not parking candidates.
        val monitoredCores = caps.primeCoreIndices.toSet()

        var gpuLoadSum = 0
        var bigPrimeLoadSum = 0
        var gpuBoundSamples = 0
        var anySaturated = false

        for (sample in window) {
            val gpuLoad = sample.gpuLoadPct ?: 0
            gpuLoadSum += gpuLoad

            // Compute average load of the monitored prime cores for this sample.
            val bigPrimeLoads = monitoredCores
                .mapNotNull { idx -> sample.perCoreLoadPct.getOrNull(idx) }
            val bigPrimeAvg = if (bigPrimeLoads.isNotEmpty()) {
                bigPrimeLoads.average().toInt()
            } else {
                // Fallback: use overall CPU average when we can't identify cores.
                if (sample.perCoreLoadPct.isNotEmpty()) {
                    sample.perCoreLoadPct.average().toInt()
                } else 0
            }
            bigPrimeLoadSum += bigPrimeAvg

            // GPU-bound for this sample?
            if (gpuLoad >= GPU_BOUND_THRESHOLD && bigPrimeAvg < CPU_LOW_THRESHOLD) {
                gpuBoundSamples++
            }

            // CPU saturation — any prime core above threshold?
            val saturatedCore = monitoredCores.any { idx ->
                (sample.perCoreLoadPct.getOrNull(idx) ?: 0) >= CPU_SATURATED_THRESHOLD
            }
            if (saturatedCore) anySaturated = true
        }

        val n = window.size
        val smoothedGpuLoad = gpuLoadSum / n
        val smoothedBigPrimeLoad = bigPrimeLoadSum / n
        val allGpuBound = gpuBoundSamples == n

        return WindowSignals(
            smoothedGpuLoad = smoothedGpuLoad,
            smoothedBigPrimeLoad = smoothedBigPrimeLoad,
            allGpuBound = allGpuBound,
            anySaturated = anySaturated,
            bigPrimeCoreSet = monitoredCores,
        )
    }

    // ── State tightening (GPU-bound path) ─────────────────────────────────────

    private fun tightenState(
        current: TdpState,
        caps: TdpCaps,
        config: AutoTdpProfileConfig,
        signals: WindowSignals,
    ): TdpDecision {
        var newParked = current.parkedPrimeCores.toMutableSet()
        var newCap = current.bigClusterCapKhz

        val reasonParts = mutableListOf<String>()
        reasonParts += "GPU-bound ${signals.smoothedGpuLoad}%, big/prime ${signals.smoothedBigPrimeLoad}%"

        // ── Attempt to park one more prime core ───────────────────────────────
        // Find the highest-indexed prime core not yet parked, respecting the floor.
        val unparkable = caps.primeCoreIndices
            .filter { it != 0 && it !in newParked } // safety: never cpu0
            .sortedDescending()

        val onlineAfterMorePark = caps.totalOnlineCores - newParked.size - 1
        val canParkMore = unparkable.isNotEmpty() && onlineAfterMorePark >= caps.minOnlineCores

        if (canParkMore) {
            val toPark = unparkable.first()
            newParked.add(toPark)
            reasonParts += "→ park cpu$toPark"
        } else if (unparkable.isEmpty()) {
            reasonParts += "→ all prime cores parked"
        } else {
            reasonParts += "→ min-online floor (${caps.minOnlineCores}) reached"
        }

        // ── Step the big-cluster cap DOWN one OPP ─────────────────────────────
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isNotEmpty()) {
            val currentStep = newCap ?: steps.last()
            val currentIdx = steps.indexOfFirst { it >= currentStep }.let {
                if (it < 0) steps.lastIndex else it
            }
            val targetIdx = when (config.profile) {
                AutoTdpProfile.EFFICIENCY -> maxOf(0, currentIdx - 1)
                AutoTdpProfile.BALANCED   -> maxOf(steps.size / 4, currentIdx - 1)
                AutoTdpProfile.BATTERY_TARGET -> {
                    val budgetCap = deriveBudgetCap(caps, config.targetMilliWatts ?: 0)
                    if (budgetCap != null) {
                        steps.indexOfFirst { it >= budgetCap }.let { if (it < 0) steps.lastIndex else it }
                    } else {
                        maxOf(0, currentIdx - 1)
                    }
                }
            }
            newCap = steps[targetIdx]
            reasonParts += "cap big ${newCap / 1000} MHz"
        }

        val newState = current.copy(
            parkedPrimeCores = newParked,
            bigClusterCapKhz = newCap,
            gpuFloorLevel = caps.gpuMinLevel, // keep GPU permissive (prioritise GPU)
        )
        return TdpDecision(newState, reasonParts.joinToString(", "))
    }

    // ── State relaxing (CPU-bound path) ───────────────────────────────────────

    private fun relaxState(
        current: TdpState,
        caps: TdpCaps,
        signals: WindowSignals,
    ): TdpDecision {
        var newParked = current.parkedPrimeCores.toMutableSet()
        var newCap = current.bigClusterCapKhz
        val reasonParts = mutableListOf<String>()
        reasonParts += "CPU-saturated (prime core >= ${CPU_SATURATED_THRESHOLD}%)"

        // ── Unpark one prime core (lowest-parked index first = restore gently) ─
        val parkedSorted = newParked.sorted()
        if (parkedSorted.isNotEmpty()) {
            val toUnpark = parkedSorted.first()
            newParked.remove(toUnpark)
            reasonParts += "→ unpark cpu$toUnpark"
        }

        // ── Step the big-cluster cap UP one OPP ──────────────────────────────
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isNotEmpty() && newCap != null) {
            val currentIdx = steps.indexOfFirst { it >= newCap }.let {
                if (it < 0) steps.lastIndex else it
            }
            val targetIdx = minOf(steps.lastIndex, currentIdx + 1)
            newCap = steps[targetIdx]
            reasonParts += "relax big cap → ${newCap / 1000} MHz"
        } else if (steps.isNotEmpty() && newCap == null) {
            // No cap active — nothing to relax on the freq side.
            reasonParts += "big cap already stock"
        }

        val newState = current.copy(
            parkedPrimeCores = newParked,
            bigClusterCapKhz = if (newCap == steps.lastOrNull()) null else newCap,
        )
        return TdpDecision(newState, reasonParts.joinToString(", "))
    }

    // ── BATTERY_TARGET cap derivation ─────────────────────────────────────────

    /**
     * Derives a big-cluster OPP cap from a milliwatt budget.
     *
     * Strategy: assume power scales roughly linearly with freq ratio across the
     * OPP table (a deliberate simplification — the real curve is measured by
     * the Efficiency Curve Finder companion). The cap is:
     *   cap = steps[floor(budget_fraction * (steps.size - 1))]
     * where budget_fraction = targetMilliWatts / (top-OPP proxy).
     *
     * The "top-OPP proxy" is synthetic: we use the top step as a stand-in for
     * the device's max draw. The EfficiencyCurveFinder (Component 5) will
     * calibrate this properly with measured data later.
     *
     * Returns null when the OPP table is empty or the budget is unconstrained.
     */
    internal fun deriveBudgetCap(caps: TdpCaps, targetMilliWatts: Long): Int? {
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isEmpty() || targetMilliWatts <= 0) return null

        // Budget as a fraction of "full performance" (top OPP = 1.0).
        // Clamp to [0.0, 1.0] — a very high budget should not raise the cap above stock.
        val fraction = (targetMilliWatts.toDouble() / (steps.last() * 0.001)).coerceIn(0.0, 1.0)
        val idx = (fraction * (steps.size - 1)).toInt().coerceIn(0, steps.lastIndex)
        return steps[idx]
    }
}

/**
 * The output of [AutoTdpEngine.decide].
 *
 * [target] is the full desired [TdpState] the daemon should write.
 * [reason] is a short human-readable explanation for the HUD/log — e.g.
 * "GPU-bound 92%, big/prime 18% → park cpu7, cap big 1804 MHz".
 */
data class TdpDecision(
    val target: TdpState,
    val reason: String,
)
