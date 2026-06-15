package io.github.mayusi.calibratesoc.data.advisory

import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW

/**
 * Advisory recommendation, surfaced when the app cannot write sysfs
 * (the ADVISORY rung — fully-stock, zero-privilege devices).
 *
 * HONESTY INVARIANTS:
 *  - [estimatedSavingMw] is ALWAYS null or labeled as an estimate; it is
 *    derived from a simple draw model and can be wrong. The UI MUST show
 *    it with the word "estimated".
 *  - [confidence] communicates how grounded the estimate is.
 *  - This advice never implies the app controls clocks. It is a suggestion
 *    to the user or an advisory to the OS (via hints); the OS may or may
 *    not honor those hints.
 *  - [actionable] is false when the advice is informational only (no user
 *    action can be taken on THIS device, e.g. a tunable that needs root).
 */
data class Advice(
    /** Short title. Shown in bold as the advice card header. */
    val title: String,
    /**
     * Full explanation including the bound-detection rationale and what the
     * user CAN do on a stock device. Never claims guaranteed clock changes.
     */
    val detail: String,
    /**
     * Estimated power saving in milliwatts if the advice were followed, or
     * null when the model cannot derive a grounded estimate. ALWAYS labeled
     * "estimated" in the UI — this is a model output, not a measurement.
     */
    val estimatedSavingMw: Long?,
    /**
     * How confident the engine is in the estimate:
     *  "HIGH"   — based on live data with clear signal (gpu/cpu bound sustained)
     *  "MEDIUM" — partial signal or mixed workload
     *  "LOW"    — insufficient data or ambiguous workload
     */
    val confidence: String,
    /** True when the user has a concrete action to take (game mode, FPS cap, etc.). */
    val actionable: Boolean,
)

/**
 * ADVISORY engine — the pure decision brain for the ADVISORY rung.
 *
 * PURE: no Android, no I/O, no coroutines. All inputs are value objects;
 * outputs are [Advice] items the UI renders. Fully testable without a device.
 *
 * Reuses [AutoTdpEngine]'s bound-detection thresholds (same constants) to
 * phrase honest, workload-specific advice. On a live-write device these
 * signals drive actual kernel writes; here they drive textual guidance and
 * OS-level advisory hints.
 *
 * ESTIMATION MODEL (deliberately simple + labeled):
 *   - Big-cluster idle contribution proxy: assume each big/prime core at
 *     its current average load draws ~[BIG_CORE_DRAW_PROXY_MW] mW beyond
 *     the little-cluster baseline. Parking prime cores cuts that share.
 *   - This is a rough first-order estimate. The real curve is device-specific.
 *     The UI MUST prefix any saving shown with "est." or "estimated".
 */
object AdvisoryEngine {

    // ── Thresholds (mirrored from AutoTdpEngine to keep signal detection consistent) ──

    /** GPU load above which the workload is considered GPU-bound. */
    private const val GPU_BOUND_THRESHOLD = 80

    /** Big/prime cluster average load below which GPU-bound is confirmed. */
    private const val CPU_LOW_THRESHOLD = 40

    /** Big/prime core load above which the CPU is the bottleneck. */
    private const val CPU_SATURATED_THRESHOLD = 85

    /**
     * Minimum samples in the window before we generate high-confidence advice.
     * Fewer samples → LOW confidence.
     */
    private const val MIN_WINDOW_FOR_HIGH_CONFIDENCE = 3

    /**
     * Rough draw proxy per big/prime core at full load (mW).
     * A Snapdragon 8 Gen 2 prime core at full load draws ~400–700 mW from the
     * SoC rail; we use a conservative mid estimate. This is deliberately labeled
     * "estimated" — the Efficiency Curve Finder (another component) measures
     * the real curve.
     */
    private const val BIG_CORE_DRAW_PROXY_MW = 350L

    /**
     * GPU battery-saver contribution: GPU battery mode typically reduces GPU
     * power by 20–30 %. We use a conservative 20 % of current draw as the
     * estimated saving from enabling battery game mode.
     */
    private const val GPU_BATTERY_MODE_SAVING_FRACTION = 0.20

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Generate a list of advisory recommendations from a telemetry window.
     *
     * @param window  Recent telemetry samples (last 3–5). May be empty.
     * @param report  The device capability report (used for governor name,
     *                cluster layout, and available frequencies).
     * @return        List of [Advice] items, ordered by estimated impact
     *                descending. Empty when data is insufficient.
     */
    fun advise(window: List<Telemetry>, report: CapabilityReport): List<Advice> {
        if (window.isEmpty()) return emptyList()

        // Derive the prime-core set from the report the same way TdpCaps.from() does.
        val policies = report.cpuPolicies
        val policyTopOpp = policies.associateWith { p -> p.availableFreqsKhz.maxOrNull() ?: 0 }
        val maxTopOpp = policyTopOpp.values.maxOrNull() ?: 0
        val primePolicies = policies.filter { policyTopOpp[it] == maxTopOpp }
        val primeCoreIndices = primePolicies.flatMap { it.onlineCores }.filter { it != 0 }.toSet()

        // Identify the big policy (same heuristic as TdpCaps.from).
        val bigPolicy = if (policies.size <= 2) {
            primePolicies.firstOrNull() ?: policies.firstOrNull()
        } else {
            policies.sortedByDescending { policyTopOpp[it] ?: 0 }
                .firstOrNull { policyTopOpp[it] != maxTopOpp }
                ?: primePolicies.firstOrNull()
                ?: policies.firstOrNull()
        }
        val bigGovernor = bigPolicy?.currentGovernor ?: "unknown"
        val bigMaxKhz = bigPolicy?.currentMaxKhz ?: 0
        val bigTopOppKhz = bigPolicy?.availableFreqsKhz?.maxOrNull() ?: 0

        val signals = computeSignals(window, primeCoreIndices)
        val confidence = if (window.size >= MIN_WINDOW_FOR_HIGH_CONFIDENCE &&
            signals.allGpuBound || signals.anySaturated
        ) "HIGH" else if (window.size >= MIN_WINDOW_FOR_HIGH_CONFIDENCE) "MEDIUM" else "LOW"

        val advice = mutableListOf<Advice>()

        when {
            signals.anySaturated -> {
                // CPU-bound path: big/prime cores saturated.
                advice += buildCpuBoundAdvice(signals, primeCoreIndices.size, bigGovernor, confidence)
            }
            signals.allGpuBound -> {
                // GPU-bound path: GPU high, CPU low → classic emulation workload.
                advice += buildGpuBoundAdvice(
                    signals, primeCoreIndices.size, bigGovernor,
                    bigMaxKhz, bigTopOppKhz, confidence,
                )
            }
            signals.smoothedGpuLoad >= GPU_BOUND_THRESHOLD / 2 &&
                signals.smoothedBigPrimeLoad >= CPU_LOW_THRESHOLD -> {
                // Mixed: both GPU and CPU are moderately active.
                advice += buildMixedAdvice(signals, bigGovernor, confidence)
            }
            else -> {
                // Balanced / idle: no strong signal.
                advice += buildBalancedAdvice(signals, confidence)
            }
        }

        // Always add a general draw note when we have battery current data.
        val recentDraw = window.lastOrNull()?.batteryDrawMilliW
        if (recentDraw != null && recentDraw > 0) {
            advice += buildDrawContextAdvice(recentDraw)
        }

        // Sort by actionable-first, then by estimated saving descending.
        return advice.sortedWith(
            compareByDescending<Advice> { it.actionable }
                .thenByDescending { it.estimatedSavingMw ?: 0L }
        )
    }

    // ── Signal extraction ──────────────────────────────────────────────────────

    /**
     * Internal signal bundle extracted from the telemetry window.
     * Mirrors AutoTdpEngine's WindowSignals structure.
     */
    data class WindowSignals(
        val smoothedGpuLoad: Int,
        val smoothedBigPrimeLoad: Int,
        /** True when ALL samples in the window are GPU-bound. */
        val allGpuBound: Boolean,
        /** True when ANY sample has a big/prime core above the saturation threshold. */
        val anySaturated: Boolean,
        /** Mean battery draw across the window, null when current/voltage absent. */
        val meanDrawMw: Long?,
    )

    /** Visible for tests. */
    internal fun computeSignals(
        window: List<Telemetry>,
        primeCoreIndices: Set<Int>,
    ): WindowSignals {
        var gpuLoadSum = 0
        var bigPrimeLoadSum = 0
        var gpuBoundSamples = 0
        var anySaturated = false
        var drawSum = 0L
        var drawCount = 0

        for (sample in window) {
            val gpuLoad = sample.gpuLoadPct ?: 0
            gpuLoadSum += gpuLoad

            val bigPrimeLoads = primeCoreIndices
                .mapNotNull { idx -> sample.perCoreLoadPct.getOrNull(idx) }
            val bigPrimeAvg = if (bigPrimeLoads.isNotEmpty()) {
                bigPrimeLoads.average().toInt()
            } else {
                sample.perCoreLoadPct.let {
                    if (it.isNotEmpty()) it.average().toInt() else 0
                }
            }
            bigPrimeLoadSum += bigPrimeAvg

            if (gpuLoad >= GPU_BOUND_THRESHOLD && bigPrimeAvg < CPU_LOW_THRESHOLD) {
                gpuBoundSamples++
            }

            val saturated = primeCoreIndices.any { idx ->
                (sample.perCoreLoadPct.getOrNull(idx) ?: 0) >= CPU_SATURATED_THRESHOLD
            }
            if (saturated) anySaturated = true

            sample.batteryDrawMilliW?.let { draw ->
                if (draw > 0) {
                    drawSum += draw
                    drawCount++
                }
            }
        }

        val n = window.size
        return WindowSignals(
            smoothedGpuLoad = gpuLoadSum / n,
            smoothedBigPrimeLoad = bigPrimeLoadSum / n,
            allGpuBound = gpuBoundSamples == n,
            anySaturated = anySaturated,
            meanDrawMw = if (drawCount > 0) drawSum / drawCount else null,
        )
    }

    // ── Advice builders ────────────────────────────────────────────────────────

    private fun buildGpuBoundAdvice(
        signals: WindowSignals,
        primeCount: Int,
        governor: String,
        bigMaxKhz: Int,
        bigTopOppKhz: Int,
        confidence: String,
    ): Advice {
        // Estimate: parking `primeCount` prime cores at BIG_CORE_DRAW_PROXY_MW each.
        // This is the most actionable case — GPU is the bottleneck, prime cores are idle.
        val parkedSaving = if (primeCount > 0) primeCount * BIG_CORE_DRAW_PROXY_MW else null

        // GPU battery mode saving: fraction of mean GPU-attributed draw.
        val gpuSaving = signals.meanDrawMw?.let { draw ->
            (draw * GPU_BATTERY_MODE_SAVING_FRACTION).toLong()
        }

        // Total estimate is the larger of the two.
        val estimatedSaving = when {
            parkedSaving != null && gpuSaving != null -> maxOf(parkedSaving, gpuSaving)
            parkedSaving != null -> parkedSaving
            gpuSaving != null -> gpuSaving
            else -> null
        }

        val capHint = if (bigMaxKhz > 0 && bigTopOppKhz > 0 && bigMaxKhz >= bigTopOppKhz) {
            " Your big cluster is running at its top OPP (${bigMaxKhz / 1_000} MHz)" +
                " — an FPS cap or in-game resolution drop is the easiest free saving."
        } else ""

        val governorNote = if (governor == "schedutil" || governor == "walt" || governor == "sugov") {
            " Your governor ($governor) already scales with demand, which is good."
        } else if (governor.isNotBlank() && governor != "unknown") {
            " Your governor is $governor — consider whether a demand-tracking governor" +
                " (schedutil) would reduce unnecessary boosting."
        } else ""

        return Advice(
            title = "GPU-bound at ${signals.smoothedGpuLoad}% — prime cores are idle",
            detail = buildString {
                append("Your GPU is heavily loaded (${signals.smoothedGpuLoad}%) while your")
                append(" prime CPU cores are only at ~${signals.smoothedBigPrimeLoad}% average.")
                append(" On a device with root or the one-time unlock, the AutoTDP engine")
                append(" would park $primeCount prime core(s) and save an estimated")
                if (parkedSaving != null) {
                    append(" ~$parkedSaving mW (estimated — not measured on this device).")
                } else {
                    append(" power (unknown — no prime core data).")
                }
                append("\n\nOn THIS stock device, you can:")
                append("\n• Enable Battery Game Mode — the OS advisory hint (ADPF / GameManager)")
                append(" may reduce GPU/CPU clock headroom, with a system-estimated saving.")
                append("\n• Lower the in-game resolution or enable FPS cap to directly reduce GPU load.")
                append("\n• Use Sustained Performance Mode if supported — prevents thermal throttle")
                append(" bursts and holds a steady (lower) TDP.")
                append(capHint)
                append(governorNote)
                append("\n\nNote: All saving figures are estimated from a simple draw model,")
                append(" not measured. Actual results depend on your specific silicon and workload.")
            },
            estimatedSavingMw = estimatedSaving,
            confidence = confidence,
            actionable = true,
        )
    }

    private fun buildCpuBoundAdvice(
        signals: WindowSignals,
        primeCount: Int,
        governor: String,
        confidence: String,
    ): Advice {
        // CPU is saturated — unparking advice, but primarily: the user needs perf.
        // Battery saving is less relevant here; the advice is about sustained performance.
        val governorNote = if (governor.isNotBlank() && governor != "unknown" &&
            governor != "performance"
        ) {
            " Your governor is $governor, which should handle burst demand automatically."
        } else if (governor == "performance") {
            " Your governor is locked to performance mode — the OS will not step down"
            " clocks even at low load. Consider switching to schedutil for adaptive scaling."
        } else ""

        return Advice(
            title = "CPU-bound at ${signals.smoothedBigPrimeLoad}% — all cores in use",
            detail = buildString {
                append("Your prime CPU cores are saturated (~${signals.smoothedBigPrimeLoad}% avg).")
                append(" There is no clock headroom to trade for battery savings right now —")
                append(" doing so would cause stutter.")
                append("\n\nOn THIS stock device:")
                append("\n• Sustained Performance Mode may help prevent thermal throttle-induced")
                append(" stutter by limiting peak draw before heat builds up.")
                append("\n• Ensure Battery Game Mode is OFF — the OS may unnecessarily cap clocks.")
                append("\n• If you see frame drops, this is a CPU bottleneck, not a tuning gap.")
                append(governorNote)
                append("\n\nEstimated power saving: none advisable (CPU is the limiting resource).")
            },
            estimatedSavingMw = null,
            confidence = confidence,
            actionable = true,
        )
    }

    private fun buildMixedAdvice(
        signals: WindowSignals,
        governor: String,
        confidence: String,
    ): Advice {
        val gpuSaving = signals.meanDrawMw?.let { draw ->
            (draw * GPU_BATTERY_MODE_SAVING_FRACTION * 0.5).toLong()
        }

        return Advice(
            title = "Mixed GPU+CPU load — moderate tuning opportunity",
            detail = buildString {
                append("GPU load is at ${signals.smoothedGpuLoad}% and prime CPU cores")
                append(" are at ~${signals.smoothedBigPrimeLoad}%. Both subsystems are")
                append(" active; aggressive clock changes would hurt CPU tasks.")
                append("\n\nOn THIS stock device:")
                append("\n• Try Balanced Game Mode if available — the OS may find a middle")
                append(" ground without sacrificing frame rate.")
                append("\n• An FPS cap (if the game supports it) is the safest lever:")
                append(" it lets the SoC spend less time at max boost.")
                append(" Governor: $governor.")
                if (gpuSaving != null) {
                    append("\n\nEstimated advisory saving with game mode: ~$gpuSaving mW")
                    append(" (estimated from draw model — not measured).")
                }
            },
            estimatedSavingMw = gpuSaving,
            confidence = confidence,
            actionable = true,
        )
    }

    private fun buildBalancedAdvice(
        signals: WindowSignals,
        confidence: String,
    ): Advice {
        return Advice(
            title = "Balanced workload — no dominant bottleneck detected",
            detail = buildString {
                append("GPU at ${signals.smoothedGpuLoad}%,")
                append(" prime CPU cores ~${signals.smoothedBigPrimeLoad}%.")
                append(" The workload is balanced; no single subsystem dominates.")
                append("\n\nNo specific advisory action is needed right now.")
                append(" If battery life is a concern:")
                append("\n• Battery Game Mode nudges the OS toward efficiency and is low-risk.")
                append("\n• Sustained Performance Mode avoids thermal-burst draw spikes.")
                append("\n\nEstimated saving from game mode: small (low-load workload already efficient).")
            },
            estimatedSavingMw = signals.meanDrawMw?.let { it / 10 }, // ~10 % wild guess, low conf
            confidence = "LOW",
            actionable = false,
        )
    }

    private fun buildDrawContextAdvice(currentDrawMw: Long): Advice {
        val hoursMw = currentDrawMw // 1 mW = 1 mWh/h
        val context = when {
            currentDrawMw > 8_000 -> "very high (${currentDrawMw} mW) — thermal throttle risk"
            currentDrawMw > 5_000 -> "high (${currentDrawMw} mW) — consider an FPS cap"
            currentDrawMw > 3_000 -> "moderate (${currentDrawMw} mW)"
            else -> "low (${currentDrawMw} mW)"
        }
        return Advice(
            title = "Live draw: $context",
            detail = buildString {
                append("Current battery draw is ~$currentDrawMw mW.")
                append(" This is a live reading, not an average.")
                append(" An advisory OS hint (Battery Game Mode / ADPF session)")
                append(" may reduce this; the OS may or may not honor the hint.")
                append("\n\nThis figure is used as the baseline for estimated savings above.")
            },
            estimatedSavingMw = null,
            confidence = "HIGH",
            actionable = false,
        )
    }
}
