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
     * Compact GPU clock label, scaled by magnitude so it reads like the CPU clock:
     *
     *  - sub-GHz  → "220M"  (MHz with an "M" suffix; the common GPU range)
     *  - 1 GHz+   → "1.10G" (GHz with two decimals, mirroring [formatGhzFromMhz])
     *  - null/0   → "—"     (honest absence — never a fabricated value)
     *
     * The input is GPU clock in **MHz** (the HUD's `gpuMhz`, already converted from
     * the kgsl/devfreq `cur_freq` Hz value upstream in [HudStateAssembler]). The
     * GHz branch matters because some GPUs (and any future 1 GHz+ part) would
     * otherwise render an unreadable "1100M"; switching to "1.10G" at the GHz
     * boundary keeps the GPU cell as glanceable as the CPU's "1.46G".
     */
    @JvmStatic
    fun formatGpuClock(mhz: Int?): String =
        mhz?.takeIf { it > 0 }?.let {
            if (it >= 1000) "%.2fG".format(it / 1000.0) else "${it}M"
        } ?: "—"

    /**
     * Compact load-percent label with the honest "~" proxy prefix.
     *
     * [pct] null → "—" (no reading); otherwise "{pct}%" prefixed by "~" when the
     * value is a frequency-ratio PROXY (so an estimate never reads as a measured
     * utilisation). Examples: (88,false) → "88%", (88,true) → "~88%", (null,_) → "—".
     */
    @JvmStatic
    fun formatLoadPct(pct: Int?, isProxy: Boolean): String =
        pct?.let { "${if (isProxy) "~" else ""}${it.coerceIn(0, 100)}%" } ?: "—"

    /**
     * Like [formatLoadPct] but returns NULL (not "—") when there is no reading, so
     * the compact bar can DROP the load sub-line entirely instead of showing a
     * dead dash. Examples: (88,false) → "88%", (88,true) → "~88%", (null,_) → null.
     */
    @JvmStatic
    fun formatLoadPctOrNull(pct: Int?, isProxy: Boolean): String? =
        pct?.let { "${if (isProxy) "~" else ""}${it.coerceIn(0, 100)}%" }

    /**
     * Small secondary "Hz tag" for the compact bar's no-game state.
     *
     * When [gameFps] is NOT a real in-game framerate, the bar must NOT show a
     * giant FPS hero — it shows this tiny tag instead so a meaningless panel
     * refresh-rate never dominates. Example: 60 → "60HZ". Null → "—HZ" (sensor
     * unavailable — still honest, never a fabricated number).
     */
    @JvmStatic
    fun formatHzTag(gameFps: Int?): String = "${gameFps ?: "—"}HZ"

    /**
     * Frame-time string derived from a REAL game FPS, for the verbose hero's
     * "fps · 17.2 ms" line. Honest: only meaningful for a true in-game framerate,
     * so the caller must gate on [HudUiState.gameFpsIsReal]; a null/≤0 fps yields
     * null so the row is HIDDEN (never a fabricated frame time).
     * Examples: 60 → "16.7 ms", 144 → "6.9 ms", null/0 → null.
     */
    @JvmStatic
    fun formatFrameMs(fps: Int?): String? =
        fps?.takeIf { it > 0 }?.let { "%.1f ms".format(1000f / it) }

    /**
     * Verbose-hero refresh label for the NO-game state: "REFRESH 60Hz" (or
     * "REFRESH —Hz" when the panel rate is unknown). Used in place of a giant FPS
     * number so a meaningless panel-refresh value never dominates the panel.
     */
    @JvmStatic
    fun formatRefreshTag(gameFps: Int?): String = "REFRESH ${gameFps ?: "—"}Hz"

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
     * Format a temperature with NO unit suffix — used for the dense inline
     * "clock + temp" pairing where space is at a premium and the degree symbol
     * is implied by context. Examples: "72°", "—".
     */
    @JvmStatic
    fun formatTempBare(tempC: Float?): String = tempC?.let { "%.0f°".format(it) } ?: "—"

    // ── cool → hot temperature colour tiers ───────────────────────────────────
    // The single most glanceable signal on a gaming HUD: each temperature is
    // coloured by magnitude so the user reads thermal state without parsing the
    // number. Three honest bands, mapped to Arsenal-theme accents by the caller:
    //   COOL  (< 70°C)  → cool accent (emerald/blue)
    //   WARM  (70–90°C) → amber accent
    //   HOT   (≥ 90°C)  → red/error accent
    // A NULL temperature has no tier (NONE) so the caller renders the muted "—"
    // colour — never a fabricated "cool" green for missing data.

    /** Lower bound (inclusive) of the WARM band, in °C. */
    const val TEMP_WARM_C = 70f

    /** Lower bound (inclusive) of the HOT band, in °C. */
    const val TEMP_HOT_C = 90f

    enum class TempTier { NONE, COOL, WARM, HOT }

    /**
     * Classify a temperature into its colour tier.
     *
     * Null → [TempTier.NONE] (render muted "—"); never coerced to a real band so
     * a missing sensor can never *look* cool. Thresholds: < 70 COOL, 70–<90 WARM,
     * ≥ 90 HOT.
     */
    @JvmStatic
    fun tempTier(tempC: Float?): TempTier = when {
        tempC == null        -> TempTier.NONE
        tempC >= TEMP_HOT_C  -> TempTier.HOT
        tempC >= TEMP_WARM_C -> TempTier.WARM
        else                 -> TempTier.COOL
    }

    /**
     * Format a battery state-of-charge percent for the HUD.
     *
     * Honest: null (sensor unavailable) → "—", never a default like "100%".
     * Examples: 87 → "87%", null → "—".
     */
    @JvmStatic
    fun formatBatteryPct(pct: Int?): String = pct?.let { "${it.coerceIn(0, 100)}%" } ?: "—"

    /**
     * Whether a running clock exceeds its cap — the "boost" condition. Appends a
     * `+` to the clock display when true (no extra horizontal space).
     *
     * Both arguments are MHz. Returns false when either is null (can't prove
     * boost) or when the running clock is at/below the cap. A small tolerance
     * (25 MHz) absorbs OPP-table rounding so we don't flag a phantom boost when
     * the core is merely sitting one OPP step under an exact cap.
     */
    @JvmStatic
    fun isBoosting(runningMhz: Int?, capMhz: Int?): Boolean {
        if (runningMhz == null || capMhz == null) return false
        return runningMhz > capMhz + 25
    }

    /**
     * Compose a clock label with an optional trailing boost `+`.
     * E.g. running 3010 over cap 2918 → "3.01G+"; otherwise just the GHz string.
     */
    @JvmStatic
    fun formatClockWithBoost(runningMhz: Int?, capMhz: Int?): String {
        val base = formatGhzFromMhz(runningMhz?.takeIf { it > 0 })
        return if (isBoosting(runningMhz, capMhz)) "$base+" else base
    }

    /**
     * Whether the kernel is actively throttling right now.
     *
     * Driven by the honest [io.github.mayusi.calibratesoc.data.monitor.Telemetry.coolingDeviceMaxState]
     * signal: any cooling device with cur_state > 0 means a mitigation is engaged
     * NOW. Null (not probed on this device) → false (we never *claim* throttling we
     * can't observe). This is the single most useful gaming-HUD signal: "am I being
     * throttled?".
     */
    @JvmStatic
    fun isThrottlingNow(coolingDeviceMaxState: Int?): Boolean =
        (coolingDeviceMaxState ?: 0) > 0

    /**
     * Format a GHz value from MHz — returns e.g. "2.92G" or "—".
     */
    @JvmStatic
    fun formatGhzFromMhz(mhz: Int?): String = mhz?.let { "%.2fG".format(it / 1000.0) } ?: "—"

    /**
     * Verbose HUD panel width in dp for a given size index.
     *
     * The rebuilt verbose panel is a VERTICAL structured card (header → FPS hero →
     * 2×2 metric grid → thermal → per-core → AutoTDP footer → controls), so it no
     * longer needs the very wide footprint the old single-row 4-tile layout did.
     * The approved design calls for a ~330dp panel; these presets bracket that so
     * the panel reads as a compact premium card, not a sprawling banner.
     *
     * 0 = small  (300dp)
     * 1 = medium (330dp, default — the approved width)
     * 2 = large  (372dp)
     */
    @JvmStatic
    fun hudWidthDp(sizeIndex: Int): Int = when (sizeIndex.coerceIn(0, 2)) {
        0 -> 300
        1 -> 330
        else -> 372
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
