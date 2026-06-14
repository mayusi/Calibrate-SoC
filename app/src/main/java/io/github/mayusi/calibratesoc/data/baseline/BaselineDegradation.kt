package io.github.mayusi.calibratesoc.data.baseline

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableKind

/**
 * Pure-logic baseline degradation analyser.
 *
 * HONESTY CONTRACT
 * ----------------
 * [FactoryBaseline] stores clock ceilings captured on first launch:
 *   - scaling_max_freq / scaling_min_freq per CPU policy (kHz, as strings)
 *   - GPU devfreq/max_freq and devfreq/min_freq (Hz, as strings)
 *   - scaling_governor and devfreq/governor (string — not frequency, skipped)
 *   - Vendor Settings.System keys (not comparable to CapabilityReport fields)
 *
 * It does NOT store:
 *   - Sustained-throttle behaviour (needs a benchmark run)
 *   - Thermal headroom (needs live thermal data during load)
 *   - Battery wear or paste degradation (no kernel surface exposed)
 *
 * Therefore the ONLY honest comparison is clock-ceiling drift: did the
 * kernel-advertised max / min frequency change since first launch?
 * This can happen when a vendor OTA silently lowers an OPP table entry
 * or when a third-party tool wrote a permanent min/max before our baseline
 * was captured.  If no such comparison is possible (e.g. the baseline
 * stored no frequency tunables, or none of the policy IDs still exist in
 * the live report), the result is [DegradationStatus.INSUFFICIENT_DATA].
 *
 * Usage: call [analyze] on the main/IO thread; it's pure computation with
 * no side effects.
 */
object BaselineDegradation {

    /** Drop threshold above which a ceiling change is considered DEGRADED (10 %). */
    const val DEGRADED_THRESHOLD_PCT = 10.0

    /** Drop threshold for a minor but notable change (1 %). */
    const val MINOR_THRESHOLD_PCT = 1.0

    /**
     * Compare [baseline] (captured on first launch) with the device's
     * current state from [current] and return a [DegradationReport].
     */
    fun analyze(baseline: FactoryBaseline, current: CapabilityReport): DegradationReport {
        val findings = mutableListOf<DegradationFinding>()

        // --- CPU max-freq comparisons -----------------------------------
        // The baseline stores scaling_max_freq as a SYSFS tunable whose target
        // path ends with "/scaling_max_freq".  Extract the policy ID from the
        // path and match against the live report.
        val cpuMaxEntries = baseline.tunables.filter { snap ->
            snap.id.kind == TunableKind.SYSFS &&
                snap.id.target.contains("/scaling_max_freq") &&
                snap.previousValue != null
        }

        for (snap in cpuMaxEntries) {
            val policyId = extractPolicyId(snap.id.target) ?: continue
            val baselineKhz = snap.previousValue!!.trim().toLongOrNull() ?: continue
            val livePolicy = current.cpuPolicies.firstOrNull { it.policyId == policyId } ?: continue
            val currentKhz = livePolicy.currentMaxKhz.toLong()

            if (baselineKhz <= 0L) continue  // guard against corrupt baseline

            val deltaPct = (baselineKhz - currentKhz) * 100.0 / baselineKhz
            findings += DegradationFinding(
                signal = "CPU policy$policyId max clock",
                baselineValue = "${baselineKhz / 1000} MHz",
                currentValue = "${currentKhz / 1000} MHz",
                changePct = deltaPct,  // positive = dropped, negative = rose
            )
        }

        // --- CPU min-freq comparisons -----------------------------------
        // A raised min-floor can indicate a vendor tuning tool wrote back a
        // non-factory value before our baseline was captured; we record it
        // as an informational delta (changePct negative = floor rose).
        val cpuMinEntries = baseline.tunables.filter { snap ->
            snap.id.kind == TunableKind.SYSFS &&
                snap.id.target.contains("/scaling_min_freq") &&
                snap.previousValue != null
        }

        for (snap in cpuMinEntries) {
            val policyId = extractPolicyId(snap.id.target) ?: continue
            val baselineKhz = snap.previousValue!!.trim().toLongOrNull() ?: continue
            val livePolicy = current.cpuPolicies.firstOrNull { it.policyId == policyId } ?: continue
            val currentKhz = livePolicy.currentMinKhz.toLong()

            if (baselineKhz <= 0L) continue

            // For min-freq, a raised floor is not "degradation" in the thermal sense,
            // so only report if it changed meaningfully in either direction.
            val deltaPct = (baselineKhz - currentKhz) * 100.0 / baselineKhz
            if (kotlin.math.abs(deltaPct) >= MINOR_THRESHOLD_PCT) {
                findings += DegradationFinding(
                    signal = "CPU policy$policyId min clock floor",
                    baselineValue = "${baselineKhz / 1000} MHz",
                    currentValue = "${currentKhz / 1000} MHz",
                    changePct = deltaPct,
                )
            }
        }

        // --- GPU max-freq comparison ------------------------------------
        val gpuMaxEntry = baseline.tunables.firstOrNull { snap ->
            snap.id.kind == TunableKind.SYSFS &&
                snap.id.target.contains("/devfreq/max_freq") &&
                snap.previousValue != null
        }

        if (gpuMaxEntry != null) {
            val baselineHz = gpuMaxEntry.previousValue!!.trim().toLongOrNull()
            val currentGpu = current.gpu
            if (baselineHz != null && baselineHz > 0L && currentGpu != null) {
                val currentHz = currentGpu.currentMaxHz
                val deltaPct = (baselineHz - currentHz) * 100.0 / baselineHz
                findings += DegradationFinding(
                    signal = "GPU max clock",
                    baselineValue = "${baselineHz / 1_000_000} MHz",
                    currentValue = "${currentHz / 1_000_000} MHz",
                    changePct = deltaPct,
                )
            }
        }

        // --- No comparable signals found --------------------------------
        if (findings.isEmpty()) {
            return DegradationReport(
                status = DegradationStatus.INSUFFICIENT_DATA,
                findings = emptyList(),
                insufficientDataReason = "Your factory baseline recorded clock-ceiling and governor " +
                    "settings, but none of the baseline's CPU/GPU frequency entries could be " +
                    "matched to the device's current policy layout. " +
                    "Run a Full benchmark to capture a thermal-throttle baseline that will " +
                    "enable richer wear detection in a future release.",
            )
        }

        // --- Compute overall status from the worst degraded signal ------
        val worstDrop = findings.maxOf { it.changePct }  // highest positive value = largest drop
        val status = when {
            worstDrop >= DEGRADED_THRESHOLD_PCT -> DegradationStatus.DEGRADED
            worstDrop >= MINOR_THRESHOLD_PCT    -> DegradationStatus.MINOR
            else                                -> DegradationStatus.OK
        }

        // Attach an honest note when we can compare clocks but the clocks
        // look identical — clarify what we're NOT measuring.
        val limitationNote = if (status == DegradationStatus.OK) {
            "Clock ceilings match the factory baseline. Note: this check cannot " +
                "detect paste dry-out, battery wear, or sustained-throttle regression — " +
                "those require a Full benchmark run to capture a thermal baseline."
        } else null

        return DegradationReport(
            status = status,
            findings = findings,
            insufficientDataReason = null,
            limitationNote = limitationNote,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Extract the policy ID from a cpufreq sysfs path such as
     * "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq".
     * Returns null when the path doesn't match the expected pattern.
     */
    private fun extractPolicyId(path: String): Int? {
        val match = Regex("/cpufreq/policy(\\d+)/").find(path)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

// =============================================================================
// Result types
// =============================================================================

/**
 * One signal comparison between the factory baseline and the current state.
 *
 * @param signal Human-readable name ("CPU policy0 max clock").
 * @param baselineValue Formatted value from baseline (e.g. "3187 MHz").
 * @param currentValue Formatted current value.
 * @param changePct Positive = signal dropped (degradation); negative = rose.
 *   Magnitude matters regardless of sign for min-floor changes.
 */
data class DegradationFinding(
    val signal: String,
    val baselineValue: String,
    val currentValue: String,
    val changePct: Double,
) {
    /** True when the clock ceiling dropped — the primary degradation signal. */
    val isDrop: Boolean get() = changePct > 0.0
    val formattedPct: String get() {
        val sign = if (changePct >= 0.0) "-" else "+"
        return "$sign%.1f%%".format(kotlin.math.abs(changePct))
    }
}

enum class DegradationStatus {
    /** All comparable signals match baseline within noise (< 1 % delta). */
    OK,
    /** One or more signals drifted 1–10 % from baseline. */
    MINOR,
    /** One or more signals dropped > 10 % from baseline. */
    DEGRADED,
    /** No comparable signals exist between baseline and current report. */
    INSUFFICIENT_DATA,
}

/**
 * Result of [BaselineDegradation.analyze].
 *
 * @param status Overall health status.
 * @param findings Per-signal comparison results. Empty when status is
 *   INSUFFICIENT_DATA.
 * @param insufficientDataReason User-facing explanation of WHY there is no
 *   data, and what to do to enable it. Non-null only when status is
 *   INSUFFICIENT_DATA.
 * @param limitationNote Optional note (non-null only when status == OK)
 *   clarifying what this check cannot detect.
 */
data class DegradationReport(
    val status: DegradationStatus,
    val findings: List<DegradationFinding>,
    val insufficientDataReason: String? = null,
    val limitationNote: String? = null,
)
