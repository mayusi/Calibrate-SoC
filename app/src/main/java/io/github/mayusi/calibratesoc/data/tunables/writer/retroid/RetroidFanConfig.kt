package io.github.mayusi.calibratesoc.data.tunables.writer.retroid

/**
 * THE TWO CALIBRATE-LIVE CONSTANTS — pin these on the RP6 then ship.
 * ====================================================================
 *
 * Everything the Retroid fan path needs that is NOT yet confirmed on hardware
 * lives HERE, in one obvious place, with the best-guess defaults. After the live
 * calibration pass (set custom mode, sweep speed, watch the binder read-back +
 * the physical fan), pinning the real numbers is a ONE-LINE constant change each.
 *
 * ## What is PROVEN (decompiled from `com.rp.gameassistant` + LIVE on RP6 13f60c13)
 *  - Binding `SettingsController` → `FanProvider` works from a normal app (no root).
 *  - txn 7 `r(int)` (set custom speed) is ACCEPTED at the binder layer.
 *  - txn 2 `b()` (read current fan value) returns an int (returned 25000 — the
 *    configured `fan_speed` pref). This is the readback-verify signal.
 *  - txn 5 `c(int)` (set fan MODE) and txn 6 `j(boolean)` (enable/toggle) exist.
 *
 * ## What is UNKNOWN (handled honestly, calibrate live)
 *  1. [CUSTOM_MODE] — the integer that puts the fan in CUSTOM mode so the manual
 *     speed from txn 7 actually takes. The probe ran in fan_mode=4 (Smart/auto),
 *     where the governor IGNORES the manual speed, so txn 2 always read back the
 *     stock 25000. The R.java fan modes are `fan_mode_{disable,quiet,smart,sport,
 *     custom}_text`; the exact int for "custom" is NOT pinned. Most-likely mapping
 *     is `0=disable, 1=quiet, 2=smart, 3=sport, 4=custom` (so CUSTOM=4), but the
 *     prefs default was 0 or 1 and the device was sitting in mode 4 — so 4 could
 *     equally be Smart. [CUSTOM_MODE_CANDIDATES] lists the values to try in order;
 *     the writer tries the first and the readback proves whether it took.
 *  2. [SPEED_MIN]/[SPEED_MAX] — the working range of the txn-7 speed value. It is
 *     NOT 0-255 PWM and NOT 0-100%: the `fan_speed` pref default is 25000, so the
 *     scale is a duty/RPM-ish value in roughly the 0..25000-ish band. We model the
 *     curve's representative duty% onto [SPEED_MIN]..[SPEED_MAX] and enforce a hard
 *     [SPEED_SAFE_MIN] floor so the fan can never be set dangerously low.
 *
 * SAFETY: [SPEED_SAFE_MIN] is a hard floor on the actuated speed — the curve→speed
 * mapping ([RetroidFanSpeedMapper]) clamps UP to it so a low representative duty can
 * never spin the fan below a safe minimum on the device's own scale.
 */
object RetroidFanConfig {

    // ── CALIBRATE LIVE #1: the CUSTOM fan-mode integer ───────────────────────────
    //
    // The int passed to txn 5 `c(int)` to enter CUSTOM mode so txn 7 `r(int)` takes
    // effect. Default is the most-likely value; if the live readback proves it did
    // NOT take, swap this for the next [CUSTOM_MODE_CANDIDATES] entry.
    //
    // >>> CALIBRATE THIS LIVE: set this to the int that makes the manual speed stick. <<<
    const val CUSTOM_MODE: Int = 4

    /**
     * Candidate CUSTOM-mode ints to try, MOST-LIKELY FIRST. The writer applies
     * [CUSTOM_MODE] (= the head of this list by default) and the binder read-back
     * tells us, honestly, whether it took. Kept as a list so the live pass can
     * confirm which one is real without rebuilding logic — just reorder / repoint
     * [CUSTOM_MODE].
     *
     * Order rationale: 4 (custom is the 5th/last mode token, 0-indexed) is the
     * leading guess; 3 (if "disable" isn't a real mode and the list is
     * quiet/smart/sport/custom) and 1 are fallbacks.
     */
    val CUSTOM_MODE_CANDIDATES: List<Int> = listOf(4, 3, 1, 2, 0)

    // ── CALIBRATE LIVE #2: the SPEED range (≈25000 scale) ────────────────────────
    //
    // txn 7 `r(int)` speed unit. The `fan_speed` pref default is 25000, so the scale
    // is NOT 0-255 and NOT 0-100 — it is a duty/RPM-ish value around the 25000 mark.
    // We map a curve's representative duty% (0..100) linearly onto SPEED_MIN..SPEED_MAX.
    //
    // >>> CALIBRATE THESE LIVE: set SPEED_MIN/SPEED_MAX to the range that actually
    //     moves the fan from its slowest safe spin to full tilt on the RP6. <<<
    /** Speed value that corresponds to 0% representative duty (slowest the curve maps to). */
    const val SPEED_MIN: Int = 0

    /** Speed value that corresponds to 100% representative duty (full tilt). The
     *  configured stock `fan_speed` was 25000, so full scale is at least that high. */
    const val SPEED_MAX: Int = 25_000

    /**
     * HARD safety floor on the actuated speed (on the SPEED_MIN..SPEED_MAX scale).
     * The curve→speed mapping clamps UP to this so the user can never set the
     * Retroid fan dangerously low. Chosen as ~20% of full scale to mirror the
     * 20% duty floor the curve model already documents
     * ([io.github.mayusi.calibratesoc.data.fancurve.FanCurve.SAFE_MIN_DUTY_PCT]).
     *
     * >>> CALIBRATE THIS LIVE if 20% of the real range turns out to be unsafe. <<<
     */
    const val SPEED_SAFE_MIN: Int = 5_000

    // ── PROVEN constants (do NOT need calibration) ───────────────────────────────

    /** Mode int to hand fan control BACK to the stock governor on restore. Per the
     *  RP6 gameassistant prefs, 2 = Smart/auto. Used by the "turn custom fan off"
     *  path. If the live pass shows a different Smart int, repoint here. */
    const val SMART_MODE: Int = 2

    /**
     * Clamp [speed] into the safe actuatable band: never below [SPEED_SAFE_MIN],
     * never above [SPEED_MAX]. Pure.
     */
    fun clampSpeed(speed: Int): Int = speed.coerceIn(SPEED_SAFE_MIN, SPEED_MAX)
}
