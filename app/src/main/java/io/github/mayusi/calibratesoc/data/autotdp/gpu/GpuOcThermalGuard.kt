package io.github.mayusi.calibratesoc.data.autotdp.gpu

/**
 * UNIT 3 (ADAPTIVE MODE) — the dedicated HARD thermal guard for the beyond-stock GPU-OC
 * tier (design §6.3).
 *
 * The global thermal kill ([io.github.mayusi.calibratesoc.data.autotdp.ThermalKillEvaluator],
 * 105 °C) still sits ABOVE this guard, untouched and owned elsewhere — it is the device-wide
 * floor of last resort. This guard's whole job is to make sure beyond-stock OC NEVER gets
 * close to it: once the GPU runs beyond stock, a separate, more aggressive controller backs
 * the `max_freq` cap off the moment the GPU die gets warm, and HARD-disarms (latches OC off
 * for the rest of the hot episode) well below the kill.
 *
 * Three escalating responses, evaluated every tick:
 *
 *  1. **SOFT back-off** — `gpuTemp >= gpuSoftTempC`: step `max_freq` DOWN one OPP this tick.
 *     Fast, no confirmation needed (unlike a CPU cap, a single warm GPU sample is enough to
 *     shed one step — it is trivially reversible when the GPU cools).
 *  2. **HARD disarm** — `gpuTemp >= (killC - OC_DISARM_MARGIN_C)`: slam `max_freq` to the
 *     stock ceiling AND LATCH OC off for the session. Nothing re-raises beyond stock within
 *     this hot episode. This is the 8 °C-below-kill safety wall: beyond-stock never enters
 *     the last 8 °C before the global kill.
 *  3. **dTemp ARM** — `dTempSlope >= OC_SLOPE_ARM`: the GPU is heating fast; proactively
 *     disarm to the stock ceiling before temperature alone trips a wall. (This trims the cap
 *     to stock but does not latch — see [GpuOcGuardState] — so a transient slope spike that
 *     cools off can be re-raised by the coordinator, whereas crossing the hard wall latches.)
 *
 * The latch clears only when the coordinator starts a NEW OC episode (a fresh
 * [GpuOcGuardState]); within one hot episode a latched-off guard can NEVER re-raise. This is
 * the self-disarm-below-kill guarantee: the global kill stays a backstop it never reaches.
 *
 * PURE: [evaluate] is a deterministic function of its inputs — no Android, I/O, or time. The
 * caller (Unit 4/5) owns the [GpuOcGuardState] across ticks and actuates the returned target.
 */
object GpuOcThermalGuard {

    /**
     * Per-session guard state, threaded tick-to-tick by the caller.
     *
     * @property ocLatchedOff once true, beyond-stock OC is disarmed for the rest of the hot
     *           episode — no decision will raise `max_freq` above the stock ceiling again
     *           until the caller starts a fresh episode (a new [GpuOcGuardState]).
     */
    data class GpuOcGuardState(
        val ocLatchedOff: Boolean = false,
    )

    /**
     * The guard's per-tick output.
     *
     * @property newMaxHz        the `max_freq` target the caller should actuate this tick
     *                           (Hz). Always within [stock ceiling .. the OPP just above it];
     *                           never below the ceiling (this guard only ever trims
     *                           beyond-stock headroom — it does not throttle stock clocks;
     *                           that is the CPU/GPU band controller's job).
     * @property state           the updated [GpuOcGuardState] to thread into the next tick.
     * @property steppedDown     true when this decision stepped the cap DOWN one OPP (soft).
     * @property disarmedToStock true when this decision slammed the cap to the stock ceiling
     *                           (hard disarm, slope arm, OR a soft step that reached stock).
     * @property reason          short human-readable cause, for logs / the HUD.
     */
    data class GpuOcGuardDecision(
        val newMaxHz: Long,
        val state: GpuOcGuardState,
        val steppedDown: Boolean,
        val disarmedToStock: Boolean,
        val reason: String,
    )

    /**
     * The hard-disarm margin below the global kill, in °C. Beyond-stock OC HARD-disarms (caps
     * to stock + latches off) at `killC - OC_DISARM_MARGIN_C`, so it never enters the last
     * 8 °C before the global 105 °C kill. 105 − 8 = 97 °C hard-disarm on the stock device.
     */
    const val OC_DISARM_MARGIN_C = 8

    /**
     * dTemp slope (°C/s) at/above which the guard proactively disarms to the stock ceiling.
     * A GPU heating ≥ this fast will cross a wall imminently; trimming to stock pre-emptively
     * is cheaper than reacting after the wall. Matches the AutoTDP engine's fast-tighten class
     * of slopes (a few °C/s of sustained rise is already "heating fast" on a handheld).
     */
    const val OC_SLOPE_ARM = 3.0

    /**
     * Evaluate the guard for one tick. PURE — deterministic in its inputs.
     *
     * @param gpuTempC          the smoothed GPU die temperature this tick (°C). When null
     *                          (no GPU die sensor) the guard makes NO temperature-driven
     *                          decision but STILL honours an existing latch (a latched-off
     *                          session stays pinned to stock).
     * @param dTempSlopeCPerS   the GPU die-temp slope (°C/s), or null until enough samples
     *                          exist. Null disables only the slope arm, not the temp walls.
     * @param currentMaxHz      the `max_freq` currently applied (Hz) — the beyond-stock cap
     *                          the coordinator set, which this guard may trim.
     * @param ceilHz            the STOCK devfreq ceiling (Hz). Hard disarm / slope arm slam
     *                          the cap to exactly this; soft back-off never steps below it.
     * @param steps             the device's ascending devfreq OPP steps (Hz), including the
     *                          beyond-stock OPPs. Used to step DOWN exactly one OPP on soft
     *                          back-off. May be empty — then soft back-off falls back to the
     *                          stock ceiling (the only safe "down" we know).
     * @param gpuSoftTempC      the GPU soft die-temp (°C) at/above which soft back-off fires
     *                          (from the resolved adaptive setpoints).
     * @param killC             the global thermal-kill threshold (°C, e.g. 105). The hard
     *                          disarm wall is [killC] − [OC_DISARM_MARGIN_C].
     * @param state             the prior-tick [GpuOcGuardState] (carries the session latch).
     * @return the [GpuOcGuardDecision] — new cap target, updated state, and what fired.
     */
    fun evaluate(
        gpuTempC: Int?,
        dTempSlopeCPerS: Double?,
        currentMaxHz: Long,
        ceilHz: Long,
        steps: List<Long>,
        gpuSoftTempC: Int,
        killC: Int,
        state: GpuOcGuardState,
    ): GpuOcGuardDecision {
        // ── Already latched off: stay pinned to the stock ceiling, full stop ───────────
        // Within a hot episode a latched-off guard can NEVER re-raise. We re-assert the
        // stock cap every tick so even a caller bug that tries to re-raise is overridden.
        if (state.ocLatchedOff) {
            return GpuOcGuardDecision(
                newMaxHz = ceilHz,
                state = state,
                steppedDown = false,
                disarmedToStock = true,
                reason = "OC latched off this session — pinned to stock ceiling",
            )
        }

        val hardDisarmWallC = killC - OC_DISARM_MARGIN_C

        // ── 2. HARD disarm (highest priority temperature wall) ─────────────────────────
        // At killC − 8 °C: slam to stock AND latch off for the session. Beyond-stock never
        // enters the last 8 °C before the global kill. Checked before SOFT so a tick that is
        // hot enough for BOTH always takes the hard path (latch), never just a soft step.
        if (gpuTempC != null && gpuTempC >= hardDisarmWallC) {
            return GpuOcGuardDecision(
                newMaxHz = ceilHz,
                state = state.copy(ocLatchedOff = true),
                steppedDown = false,
                disarmedToStock = true,
                reason = "HARD disarm: GPU ${gpuTempC}°C ≥ ${hardDisarmWallC}°C " +
                    "(kill ${killC}°C − ${OC_DISARM_MARGIN_C}°C) — latched off",
            )
        }

        // ── 3. dTemp slope arm (proactive, before a temp wall is hit) ──────────────────
        // Heating ≥ OC_SLOPE_ARM °C/s: trim to stock pre-emptively. Does NOT latch — a
        // transient slope spike that then cools can be re-raised by the coordinator; only the
        // hard wall latches. (If desired, the coordinator may treat repeated slope arms as an
        // episode signal, but that policy lives above this pure guard.)
        if (dTempSlopeCPerS != null && dTempSlopeCPerS >= OC_SLOPE_ARM) {
            return GpuOcGuardDecision(
                newMaxHz = ceilHz,
                state = state,
                steppedDown = false,
                disarmedToStock = true,
                reason = "dTemp arm: GPU heating ${fmt(dTempSlopeCPerS)}°C/s ≥ " +
                    "${fmt(OC_SLOPE_ARM)}°C/s — disarmed to stock ceiling",
            )
        }

        // ── 1. SOFT back-off (step DOWN one OPP) ───────────────────────────────────────
        // At/above the soft target: shed exactly one OPP this tick. Fast, no confirm. Never
        // steps below the stock ceiling (the band controller owns sub-stock throttling).
        if (gpuTempC != null && gpuTempC >= gpuSoftTempC) {
            val stepped = stepDownOneOpp(currentMaxHz, ceilHz, steps)
            return GpuOcGuardDecision(
                newMaxHz = stepped,
                state = state,
                steppedDown = stepped < currentMaxHz,
                disarmedToStock = stepped <= ceilHz,
                reason = "SOFT back-off: GPU ${gpuTempC}°C ≥ soft ${gpuSoftTempC}°C — " +
                    "stepped max_freq ${currentMaxHz} → ${stepped} Hz",
            )
        }

        // ── No wall, no arm, not latched: hold the current beyond-stock cap ────────────
        return GpuOcGuardDecision(
            newMaxHz = currentMaxHz,
            state = state,
            steppedDown = false,
            disarmedToStock = false,
            reason = "GPU thermally clear — holding OC cap at ${currentMaxHz} Hz",
        )
    }

    /**
     * Step the cap DOWN exactly one OPP from [currentMaxHz], never below [ceilHz].
     *
     *  - When [steps] is known: pick the largest OPP strictly below [currentMaxHz], then
     *    clamp it up to [ceilHz] (so soft back-off never undercuts the stock ceiling — this
     *    guard only trims beyond-stock headroom).
     *  - When [steps] is empty (table unknown): the only safe "down" is the stock ceiling.
     *  - When [currentMaxHz] is already at/below [ceilHz]: there is no beyond-stock headroom
     *    left to shed → return [ceilHz] (idempotent).
     */
    private fun stepDownOneOpp(currentMaxHz: Long, ceilHz: Long, steps: List<Long>): Long {
        if (currentMaxHz <= ceilHz) return ceilHz
        if (steps.isEmpty()) return ceilHz
        val nextDown = steps.filter { it < currentMaxHz }.maxOrNull() ?: ceilHz
        // Never drop below the stock ceiling — that is the band controller's domain.
        return maxOf(nextDown, ceilHz)
    }

    /** Compact 1-decimal formatter for slope values in reason strings (locale-stable). */
    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
