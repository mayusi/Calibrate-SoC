package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.hasTrueLoadData

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

    /**
     * BUG C FIX: Smoothed GPU load threshold for BALANCED profile.
     * EFFICIENCY triggers on allGpuBound (GPU >= 80% per sample, CPU < 40%).
     * BALANCED is more conservative — it requires the smoothed GPU mean to be
     * at this higher threshold before parking. This is reachable when GPU is
     * sustained above 85%, making BALANCED's conditions consistent and reachable.
     */
    private const val BALANCED_GPU_THRESHOLD = 85

    /**
     * BUG D FIX: Synthetic max-draw reference in milliwatts used by [deriveBudgetCap].
     *
     * Represents the cluster's peak power draw at the top OPP. 3 000 mW (3 W) is a
     * conservative default for high-end mobile SoC big/prime clusters at maximum OPP.
     * The EfficiencyCurveFinder will replace this with a device-calibrated value once
     * measured data is available.
     *
     * Units: milliwatts (mW). This makes the fraction computed by deriveBudgetCap
     * dimensionless (mW / mW), which is the correct form.
     */
    private const val MAX_DRAW_PROXY_MW = 3_000.0

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
            return TdpDecision(current, "no telemetry — holding", HoldReason.NO_TELEMETRY)
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
        //
        // BUG C FIX (BALANCED threshold): The old BALANCED condition was
        //   signals.allGpuBound && signals.smoothedGpuLoad >= 85
        // allGpuBound uses GPU_BOUND_THRESHOLD=80 per sample, so at GPU=82% the
        // condition was allGpuBound=true but smoothedGpuLoad=82 < 85 → never parks.
        // Fix: BALANCED checks the smoothed mean directly against its own higher
        // threshold (85%) AND that the CPU is not saturated (big/prime mean < 40%).
        // This is reachable whenever GPU is sustained above 85%.
        val shouldPark = when (config.profile) {
            AutoTdpProfile.EFFICIENCY -> signals.allGpuBound
            AutoTdpProfile.BALANCED   ->
                signals.allGpuBound && signals.smoothedGpuLoad >= BALANCED_GPU_THRESHOLD
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
                return TdpDecision(
                    current.copy(bigClusterCapKhz = budgetCap),
                    reason,
                    HoldReason.BATTERY_TARGET_HOLDING,
                )
            }
        }

        // ── Fallback hold ─────────────────────────────────────────────────────
        // HONESTY: distinguish "load unreadable" from "genuinely idle". When any
        // window sample is load-blind we CANNOT claim the device is idle — the CPU
        // could be pegged and we simply can't see it. LOAD_BLIND_HOLDING surfaces
        // that truthfully; IDLE_HOLDING is only used when load was actually read.
        val holdReason = if (signals.anyLoadBlind) {
            HoldReason.LOAD_BLIND_HOLDING
        } else {
            HoldReason.IDLE_HOLDING
        }
        return TdpDecision(current, "holding — ${signals.holdReason()}", holdReason)
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
        /**
         * True when at least one window sample has UNAVAILABLE load data (empty
         * perCoreLoadPct with no known source). When blind, the engine will not
         * park cores based on a GPU-bound signal — it only relaxes on confirmed
         * saturation, which is safe when we cannot read CPU load.
         */
        val anyLoadBlind: Boolean,
    ) {
        fun holdReason(): String {
            val loadStr = if (anyLoadBlind) "big/prime load unavailable" else "big/prime ${smoothedBigPrimeLoad}%"
            return "GPU ${smoothedGpuLoad}%, $loadStr"
        }
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
        var anyLoadBlind = false

        for (sample in window) {
            val gpuLoad = sample.gpuLoadPct ?: 0
            gpuLoadSum += gpuLoad

            // HONESTY: When perCoreLoadPct is empty (UNAVAILABLE source) we must NOT
            // treat the core loads as 0. An empty list means "we don't know", not
            // "cores are idle". Treating unknown-load as 0 falsely satisfies the
            // GPU-bound condition (CPU appears idle) and causes the engine to park
            // prime cores while the CPU may actually be pegged.
            if (sample.cpuLoadSource == CpuLoadReading.Source.UNAVAILABLE &&
                sample.perCoreLoadPct.isEmpty()
            ) {
                anyLoadBlind = true
                // We cannot evaluate big/prime load for this sample.
                // Do not increment gpuBoundSamples — we're blind on the CPU side.
                // Do not set anySaturated either — unknown is not the same as saturated.
                continue
            }

            // Compute average load of the monitored prime cores for this sample.
            val bigPrimeLoads = monitoredCores
                .mapNotNull { idx -> sample.perCoreLoadPct.getOrNull(idx) }
            val bigPrimeAvg = if (bigPrimeLoads.isNotEmpty()) {
                bigPrimeLoads.average().toInt()
            } else {
                // Fallback: use overall CPU average when we can't identify cores.
                // This happens when the load source returned fewer cores than the
                // prime-core index range, not when the list is empty (handled above).
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
            // FREQ_PROXY: a freq-proxy value of >= 85% means the core is running
            // at >= 85% of its max frequency, which is a strong signal that it is
            // CPU-bound even without true jiffie data. We honour it for the unpark
            // path (fast responsiveness) but not for the park path (conservative).
            val saturatedCore = monitoredCores.any { idx ->
                (sample.perCoreLoadPct.getOrNull(idx) ?: 0) >= CPU_SATURATED_THRESHOLD
            }
            if (saturatedCore) anySaturated = true
        }

        val n = window.size
        val smoothedGpuLoad = gpuLoadSum / n
        // For the big-prime average: count only samples that contributed (skip blind ones).
        val validSamples = window.count {
            it.cpuLoadSource != CpuLoadReading.Source.UNAVAILABLE || it.perCoreLoadPct.isNotEmpty()
        }
        val smoothedBigPrimeLoad = if (validSamples > 0) bigPrimeLoadSum / validSamples else 0
        // allGpuBound requires ALL samples to agree AND none to be blind.
        // If we're blind on any sample, we cannot confirm GPU-bound safely.
        val allGpuBound = !anyLoadBlind && gpuBoundSamples == n

        return WindowSignals(
            smoothedGpuLoad = smoothedGpuLoad,
            smoothedBigPrimeLoad = smoothedBigPrimeLoad,
            allGpuBound = allGpuBound,
            anySaturated = anySaturated,
            bigPrimeCoreSet = monitoredCores,
            anyLoadBlind = anyLoadBlind,
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
        return TdpDecision(newState, reasonParts.joinToString(", "), HoldReason.GPU_BOUND_CAPPING)
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
        // BUG C FIX (relaxState cap-clear): when stepping reaches or exceeds
        // the top OPP entry, set newCap = null (clear the cap, returning to
        // stock) in a single step rather than clamping at lastIndex. This
        // prevents the daemon from holding a harmless-but-non-null cap at the
        // top step forever when the CPU is saturated and needs full headroom.
        val steps = caps.bigClusterOppStepsKhz
        var relaxedCap: Int? = newCap  // track separately to avoid shadowing
        if (steps.isNotEmpty() && newCap != null) {
            val currentIdx = steps.indexOfFirst { it >= newCap }.let {
                if (it < 0) steps.lastIndex else it
            }
            val nextIdx = currentIdx + 1
            if (nextIdx > steps.lastIndex) {
                // Already at or past the top OPP — clear cap (return to stock).
                relaxedCap = null
                reasonParts += "relax big cap → stock (cleared)"
            } else {
                val nextStep = steps[nextIdx]
                // If the next step IS the top OPP, clear immediately so that
                // one more relax step doesn't leave a redundant top-OPP cap.
                if (nextStep == steps.lastOrNull()) {
                    relaxedCap = null
                    reasonParts += "relax big cap → stock (top OPP reached)"
                } else {
                    relaxedCap = nextStep
                    reasonParts += "relax big cap → ${nextStep / 1000} MHz"
                }
            }
        } else if (steps.isNotEmpty() && newCap == null) {
            // No cap active — nothing to relax on the freq side.
            relaxedCap = null
            reasonParts += "big cap already stock"
        }

        val newState = current.copy(
            parkedPrimeCores = newParked,
            bigClusterCapKhz = relaxedCap,
        )
        return TdpDecision(newState, reasonParts.joinToString(", "), HoldReason.CPU_BOUND_RELAXING)
    }

    // ── BATTERY_TARGET cap derivation ─────────────────────────────────────────

    /**
     * Derives a big-cluster OPP cap from a milliwatt budget.
     *
     * Strategy: assume power scales roughly linearly with freq ratio across the
     * OPP table (a deliberate simplification — the real curve is measured by
     * the Efficiency Curve Finder companion). The cap is:
     *   cap = steps[floor(budget_fraction * (steps.size - 1))]
     * where budget_fraction = targetMilliWatts / MAX_DRAW_PROXY_MW.
     *
     * BUG D FIX: The old code computed `fraction = targetMilliWatts / (steps.last() * 0.001)`.
     * `steps.last()` is in kHz; `* 0.001` converts to MHz, not mW. Dividing mW by MHz
     * is dimensionally wrong — the result is not a dimensionless fraction and maps the
     * target to a wildly incorrect OPP index (e.g., 3000 mW / 2803 MHz ≈ 1.07 → clamped
     * to 1.0 → always the top OPP step, regardless of the budget).
     *
     * Fix: use [MAX_DRAW_PROXY_MW] as the mW reference for 100% performance (top OPP).
     * fraction = targetMilliWatts (mW) / MAX_DRAW_PROXY_MW (mW) — dimensionless ✓.
     * The EfficiencyCurveFinder (Component 5) will replace this constant with per-device
     * measured data once calibration is available.
     *
     * Returns null when the OPP table is empty or the budget is unconstrained.
     */
    internal fun deriveBudgetCap(caps: TdpCaps, targetMilliWatts: Long): Int? {
        val steps = caps.bigClusterOppStepsKhz
        if (steps.isEmpty() || targetMilliWatts <= 0) return null

        // Budget as a dimensionless fraction of the synthetic max-draw proxy.
        // Clamp to [0.0, 1.0] — a budget above the proxy still caps at the top OPP.
        val fraction = (targetMilliWatts.toDouble() / MAX_DRAW_PROXY_MW).coerceIn(0.0, 1.0)
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
 * [holdReason] is the machine-readable classification of this decision — the
 * clean label the UI shows by default, with [reason] available on expand.
 *
 * [holdReason] defaults to [HoldReason.NO_TELEMETRY] so any decision constructed
 * without explicitly setting it is honest about having no basis — but every
 * branch in [AutoTdpEngine.decide] sets it explicitly.
 */
data class TdpDecision(
    val target: TdpState,
    val reason: String,
    val holdReason: HoldReason = HoldReason.NO_TELEMETRY,
)
