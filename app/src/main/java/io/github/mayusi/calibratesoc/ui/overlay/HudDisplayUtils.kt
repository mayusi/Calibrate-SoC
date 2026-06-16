package io.github.mayusi.calibratesoc.ui.overlay

import io.github.mayusi.calibratesoc.data.autotdp.HoldReason

/**
 * Pure display-formatting helpers for the HUD overlay.
 *
 * Kept out of [HudOverlayContent] so they can be unit-tested on the JVM
 * without a Compose harness.
 */
object HudDisplayUtils {

    /**
     * Format the AutoTDP compact status line text.
     *
     * Examples:
     *  - Running, savings ready:   "AutoTDP ● −1800mW parked cpu6,7 cap 1.69G"
     *  - Running, measuring:       "AutoTDP ● measuring…  parked cpu6,7"
     *  - Not running:              ""
     *
     * Honesty contract: savings text is only included when [savingsReady] is
     * true and [savingsMw] is non-null. Never fabricate.
     *
     * @param running         true when AutoTDP status is RUNNING
     * @param parkedCores     set of core indices AutoTDP has offlined
     * @param bigCapMhz       big-cluster MHz cap, null = uncapped
     * @param savingsMw       measured power delta in mW, null = not yet measured
     * @param savingsReady    true only when enough samples exist to show savings
     * @return                display string; empty when not running
     */
    @JvmStatic
    fun formatAutoTdpCompactLine(
        running: Boolean,
        parkedCores: Set<Int>,
        bigCapMhz: Int?,
        savingsMw: Int?,
        savingsReady: Boolean,
    ): String {
        if (!running) return ""
        val parkedStr = if (parkedCores.isEmpty()) ""
            else " parked cpu${parkedCores.sorted().joinToString(",")}"
        val capStr = bigCapMhz?.let { " cap ${"%.2f".format(it / 1000.0)}G" } ?: ""
        // savingsMw is baseline-tuned, so POSITIVE = saved, NEGATIVE = drawing
        // more than stock (e.g. just unparked under CPU load). Honest sign — never
        // render a power increase as a saving, and never produce a double "−−".
        val savingsStr = when {
            !savingsReady || savingsMw == null -> "  measuring…"
            savingsMw > 0 -> "  −${savingsMw}mW"
            savingsMw < 0 -> "  +${-savingsMw}mW"
            else -> "  ±0mW"
        }
        return "AutoTDP ●$parkedStr$capStr$savingsStr"
    }

    /**
     * Decide whether the manual tune steppers should be gated (disabled)
     * because AutoTDP is currently managing the CPU clocks.
     *
     * Retained for backward-compat (tested) and for any future manual-tune
     * surface that may wish to respect the same gate rule.
     *
     * A simple pure function so we can unit-test the gate rule without
     * any Android or coroutine dependencies.
     *
     * @param autoTdpRunning  true when AutoTDP status is RUNNING
     * @return                true when the steppers must be gated
     */
    @JvmStatic
    fun shouldGateSteppers(autoTdpRunning: Boolean): Boolean = autoTdpRunning

    /**
     * Format the FPS honesty label shown in the compact HUD.
     *
     * Returns "FPS" (real game framerate) or "REFRESH" (HUD cadence) based
     * on whether the sampled value is distinguishable from known refresh
     * rates. Also returns the sub-label ("game" or "Hz").
     *
     * Heuristic: if fps matches hudHz exactly, or falls in the 59-61 or
     * 119-121 band (common panel refresh rates), we label it REFRESH.
     *
     * @param fps    sampled FPS value
     * @param hudHz  HUD Choreographer rate
     * @return       Pair(label, sublabel) — e.g. ("FPS", "game") or ("REFRESH", "Hz")
     */
    @JvmStatic
    fun fpsTileLabel(fps: Int, hudHz: Int): Pair<String, String> {
        val isLikelyReal = fps != hudHz && fps !in 59..61 && fps !in 119..121
        return if (isLikelyReal) "FPS" to "game" else "REFRESH" to "Hz"
    }

    /**
     * Format the per-cluster cap label for a stepper button row.
     *
     * Example: "2918MHz" or "—" when null.
     */
    @JvmStatic
    fun formatClusterMhz(mhz: Int?): String = mhz?.let { "${it}MHz" } ?: "—"

    /**
     * Format a watts reading concisely for the HUD.
     *
     * Examples: "4.2W", "0.8W", "—".
     */
    @JvmStatic
    fun formatWatts(watts: Double?): String = watts?.let { "%.1fW".format(it) } ?: "—"

    /**
     * Format a temperature reading with one decimal.
     *
     * Examples: "72°C", "—".
     */
    @JvmStatic
    fun formatTemp(tempC: Float?): String = tempC?.let { "%.0f°C".format(it) } ?: "—"

    /**
     * Format a GHz value from MHz — returns e.g. "2.92G" or "—".
     */
    @JvmStatic
    fun formatGhzFromMhz(mhz: Int?): String = mhz?.let { "%.2fG".format(it / 1000.0) } ?: "—"

    /**
     * Full HUD panel width in dp for a given size index.
     *
     * The full panel lays the FPS hero block and the 4-wide metric-tile row out
     * HORIZONTALLY, so it needs real width to keep all four tiles on one row
     * beside the framerate block instead of wrapping into a cramped column.
     *
     * 0 = small  (420dp)
     * 1 = medium (480dp, default)
     * 2 = large  (540dp)
     */
    @JvmStatic
    fun hudWidthDp(sizeIndex: Int): Int = when (sizeIndex.coerceIn(0, 2)) {
        0 -> 420
        1 -> 480
        else -> 540
    }

    /**
     * Label for the HUD size cycle button shown in verbose mode.
     *
     * Examples: "SM", "MD", "LG".
     */
    @JvmStatic
    fun hudSizeLabel(sizeIndex: Int): String = when (sizeIndex.coerceIn(0, 2)) {
        0 -> "SM"
        1 -> "MD"
        else -> "LG"
    }

    /**
     * Format an AutoTDP profile name concisely.
     *
     * "EFFICIENCY" → "EFF"
     * "BALANCED"   → "BAL"
     * "BATTERY_TARGET" → "TGT"
     */
    @JvmStatic
    fun formatAutoTdpProfileShort(profileName: String): String = when (profileName.uppercase()) {
        "EFFICIENCY"     -> "EFF"
        "BALANCED"       -> "BAL"
        "BATTERY_TARGET" -> "TGT"
        else -> profileName.take(3).uppercase()
    }

    /**
     * Format an AutoTDP savings value for the verbose panel.
     *
     * [savingsMw] null or [savingsReady] false → "measuring…"
     * POSITIVE savingsMw = saved → "−1.8W (12%)"; NEGATIVE = drawing more than
     * stock → "+1.8W (more)"; never a double sign, never a "saving" that's a cost.
     */
    @JvmStatic
    fun formatAutoTdpSavings(savingsMw: Int?, savingsPct: Double?, savingsReady: Boolean): String {
        if (!savingsReady || savingsMw == null) return "measuring…"
        val w = "%.1f".format(kotlin.math.abs(savingsMw) / 1000.0)
        val pctStr = savingsPct?.let { " (${kotlin.math.abs(it).toInt()}%)" } ?: ""
        return when {
            savingsMw > 0 -> "−${w}W$pctStr"
            savingsMw < 0 -> "+${w}W$pctStr more"
            else -> "±0W"
        }
    }

    /**
     * Format a Hz value for the refresh-rate picker chip label.
     *
     * Example: 120.0f → "120Hz"
     */
    @JvmStatic
    fun formatHz(hz: Float): String = "${hz.toInt()}Hz"

    /**
     * Compute the opacity percentage string for display.
     * E.g. 0.94f → "94%"
     */
    @JvmStatic
    fun formatOpacityPct(opacity: Float): String = "${(opacity * 100).toInt()}%"

    // ── AutoTDP proof-of-effect helpers ──────────────────────────────────────
    // These power the "is AutoTDP REALLY working" HUD section. Each formats a
    // DERIVED or MEASURED field into a short honest string, returning null when
    // the backing field is absent so the composable can HIDE the row entirely
    // instead of rendering a placeholder.

    /** Heartbeat age threshold (ms). Beyond this the daemon is "stalled", not "live". */
    const val HEARTBEAT_LIVE_WINDOW_MS = 3_000L

    /**
     * Clean one-line WHY label for the AutoTDP hold-reason.
     *
     * HONESTY INVARIANT: [HoldReason.LOAD_BLIND_HOLDING] must read as
     * "CPU load unreadable - holding", NEVER "idle" — the CPU may actually be
     * pegged; we simply cannot measure it. [HoldReason.IDLE_HOLDING] is the only
     * state that may say "lightly loaded", because load IS readable there.
     */
    @JvmStatic
    fun holdReasonLabel(reason: HoldReason): String = when (reason) {
        HoldReason.CPU_BOUND_RELAXING    -> "CPU-bound - full clocks"
        HoldReason.GPU_BOUND_CAPPING     -> "GPU-bound - capping"
        HoldReason.BATTERY_TARGET_HOLDING -> "Battery-target - holding cap"
        HoldReason.LOAD_BLIND_HOLDING    -> "CPU load unreadable - holding"
        HoldReason.IDLE_HOLDING          -> "Lightly loaded - holding"
        HoldReason.NO_TELEMETRY          -> "Starting - no telemetry yet"
    }

    /**
     * Heartbeat-age string derived from the daemon's last applied epoch.
     *
     * Returns null when [lastAppliedEpochMs] is null (no tick yet → HIDE).
     * Examples: "adjusted just now", "adjusted 2s ago", "adjusted 1m ago".
     *
     * @param lastAppliedEpochMs daemon's last write time, null = none yet
     * @param nowMs              current wall-clock epoch (caller supplies for testability)
     */
    @JvmStatic
    fun heartbeatLabel(lastAppliedEpochMs: Long?, nowMs: Long): String? {
        if (lastAppliedEpochMs == null) return null
        val ageMs = (nowMs - lastAppliedEpochMs).coerceAtLeast(0L)
        val ageSec = ageMs / 1000L
        val whenStr = when {
            ageMs < 1_000L -> "just now"
            ageSec < 60L   -> "${ageSec}s ago"
            else           -> "${ageSec / 60L}m ago"
        }
        return "adjusted $whenStr"
    }

    /**
     * Whether the heartbeat is "live" (a write happened within the last
     * [HEARTBEAT_LIVE_WINDOW_MS]). Drives the pulse dot colour: live = emerald,
     * stalled = muted. Null epoch → not live (nothing has been applied yet).
     */
    @JvmStatic
    fun heartbeatIsLive(lastAppliedEpochMs: Long?, nowMs: Long): Boolean {
        if (lastAppliedEpochMs == null) return false
        val ageMs = (nowMs - lastAppliedEpochMs).coerceAtLeast(0L)
        return ageMs <= HEARTBEAT_LIVE_WINDOW_MS
    }

    /**
     * Compact cap label for the COMPACT proof chip: "3.0G" or "STOCK" when
     * uncapped (null). Always returns a string — the chip is always shown while
     * running; "STOCK" is the honest label for "no cap applied".
     */
    @JvmStatic
    fun formatProofChipCap(bigCapMhz: Int?): String =
        bigCapMhz?.let { "%.1fG".format(it / 1000.0) } ?: "STOCK"

    /**
     * "WHAT IT CHANGED NOW" cap line: big cap + delta-vs-max.
     *
     * Returns null when there is no cap (uncapped → caller hides this part and
     * may fall back to a "holding at stock" line). Examples:
     *  - cap 3000, delta 420  → "3.0 GHz - 420 MHz vs max"
     *  - cap 3000, delta null → "3.0 GHz"
     */
    @JvmStatic
    fun formatCapLine(bigCapMhz: Int?, capDeltaMhz: Int?): String? {
        if (bigCapMhz == null) return null
        val ghz = "%.1f GHz".format(bigCapMhz / 1000.0)
        return if (capDeltaMhz != null && capDeltaMhz > 0) {
            "$ghz - $capDeltaMhz MHz vs max"
        } else {
            ghz
        }
    }

    /**
     * Parked-cores line: "2 prime cores off" / "1 prime core off".
     *
     * Returns null when no cores are parked (→ HIDE the row).
     */
    @JvmStatic
    fun formatParkedCoresLine(parkedCores: Set<Int>): String? {
        val n = parkedCores.size
        if (n == 0) return null
        val noun = if (n == 1) "prime core" else "prime cores"
        return "$n $noun off"
    }

    /**
     * GPU-floor line: "GPU lvl 0 - max perf" (0 = highest performance level).
     * Higher levels are lower clocks, so only level 0 gets the "max perf" tag.
     *
     * Returns null when no GPU floor is applied (→ HIDE the row).
     */
    @JvmStatic
    fun formatGpuLevelLine(gpuLevel: Int?): String? {
        if (gpuLevel == null) return null
        return if (gpuLevel == 0) "GPU lvl 0 - max perf" else "GPU lvl $gpuLevel"
    }

    /**
     * Whether the "WHAT IT CHANGED NOW" section should show the "Holding at
     * stock" fallback: true only when there is no cap AND no parked cores AND no
     * GPU floor. (When any of those is present, the specific rows render instead.)
     */
    @JvmStatic
    fun isHoldingAtStock(bigCapMhz: Int?, parkedCores: Set<Int>, gpuLevel: Int?): Boolean =
        bigCapMhz == null && parkedCores.isEmpty() && gpuLevel == null

    /**
     * MEASURED session energy line: "saved 0.012 Wh this session".
     *
     * Returns null when [sessionWh] is null (probe not complete → show the
     * "measuring…" hint instead, never a fabricated number).
     */
    @JvmStatic
    fun formatSessionWhLine(sessionWh: Double?): String? {
        if (sessionWh == null) return null
        // Show 3 decimals so small in-game savings (mWh-scale) are visible.
        return "saved %.3f Wh this session".format(sessionWh)
    }

    /**
     * Short cap label for one decision-ticker entry: "3.0G" or "stock".
     * [bigCapKhz] is the DecisionRecord field (kHz), null = uncapped.
     */
    @JvmStatic
    fun formatDecisionCap(bigCapKhz: Int?): String =
        bigCapKhz?.let { "%.1fG".format(it / 1_000_000.0) } ?: "stock"
}
