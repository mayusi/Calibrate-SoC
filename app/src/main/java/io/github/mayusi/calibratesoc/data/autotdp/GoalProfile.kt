package io.github.mayusi.calibratesoc.data.autotdp

/**
 * The five curated Smart-AutoTDP goal modes.
 *
 * Each goal is a GOAL-GATED UTILIZATION BAND: a target window for the GPU busy%
 * (the bottleneck-resource signal) plus a soft die-temperature for thermal
 * pre-emption and a bias that tunes the loosen/tighten asymmetry. The band IS the
 * goal — the controller hunts the lowest-power operating point that keeps GPU busy%
 * inside the band and holds there.
 *
 * ## These numbers are LAW
 *
 * The band edges and soft temps below are the TIGHTENED CONTROL SPEC values from
 * SMART-AUTOTDP-DESIGN.md (post adversarial review). They are NOT the looser
 * first-draft prose values. Do not "round" them.
 *
 *   | Mode            | GPU band | Soft die-temp | Bias                          |
 *   |-----------------|----------|---------------|-------------------------------|
 *   | MAX_FPS         | 70–95    | 95°C          | loosen-biased                 |
 *   | BALANCED_SMART  | 63–85    | 88°C          | hold the knee (DEFAULT)       |
 *   | COOL_QUIET      | 48–70    | 80°C          | aggressive pre-empt           |
 *   | BATTERY_SAVER   | 35–60    | 85°C          | strongest tighten + power cap |
 *   | AUTO            | delegates to the context classifier (no band of its own) |
 *
 * Every band is ≥ 22 points wide (the dead-band floor that — provably > 15pt max
 * OPP-step swing + 6pt 2σ noise — makes a single one-notch correction unable to
 * cross the band, killing oscillation).
 *
 * PURE: this is a value enum with no Android, I/O, or time. The classifier and the
 * engine read it; nothing here writes a sysfs node.
 */
enum class GoalProfile(
    /** Lower edge of the GPU busy% band (inclusive). Below → tighten one notch. */
    val gpuBandLowPct: Int,
    /** Upper edge of the GPU busy% band (inclusive). Above → loosen one notch. */
    val gpuBandHighPct: Int,
    /** Smoothed GPU die-temp (°C) at/above which thermal pre-empt tightens. */
    val softDieTempC: Int,
    /** Loosen/tighten asymmetry tuning for this goal. */
    val bias: Bias,
    /**
     * True when this goal enforces a HARD power ceiling (BATTERY_SAVER /
     * BATTERY_TARGET back-compat). When true the engine caps the big cluster via
     * [AutoTdpEngine.deriveBudgetCap] using [AutoTdpProfileConfig.targetMilliWatts]
     * in addition to the band-following behaviour.
     */
    val hasHardPowerCeiling: Boolean,
    /**
     * Per-goal LOOSEN lever order (Wave 2). The controller rides ONE lever per
     * direction-episode (ACT-1) in this fixed priority, skipping levers whose
     * actuator is unavailable on the device. Built from the global priority but
     * reordered to express the goal's preference (e.g. MAX_FPS raises the min-freq
     * floor early for smoothness; COOL_QUIET prefers GPU-devfreq over unparking).
     */
    val loosenLeverOrder: List<Lever>,
    /** Per-goal TIGHTEN lever order (Wave 2). Same contract as [loosenLeverOrder]. */
    val tightenLeverOrder: List<Lever>,
    /**
     * Which CPU headroom actuator this goal uses (ACT-2: {park} XOR {uclamp}).
     * A goal either PARKS prime cores or UCLAMP-hints the top app — never both on
     * the same cluster. When [usesUclampNotPark] is true the engine selects UCLAMP
     * in place of PARK/UNPARK (and only when [TdpCaps.uclampAvailable]); otherwise
     * it parks. This is the single switch that keeps ACT-2 honest.
     */
    val usesUclampNotPark: Boolean,
    /**
     * UNIT 4 (RICHER GOAL MODES) — per-mode OBJECTIVE-SETPOINT metadata. These three
     * fields are null for the original six modes (→ ZERO behaviour change: the engine
     * and classifier never read them for those goals), and carry the DEFAULT setpoint
     * for the three new objective modes. They are informational defaults only — the
     * LIVE per-user value comes from [GoalParams] (DataStore sliders), threaded into the
     * service's OUTER-SETPOINT clamp, NEVER mutated onto the enum (the enum stays pure
     * and serializable). The band/lever/soft-temp values above ARE the control engine
     * each objective mode rides; these fields just name the user-facing target.
     *
     *  - [defaultFpsFloor]        TARGET_FPS_FLOOR's default minimum FPS (the "hold ≥ N").
     *  - [defaultTempCeilingC]    TARGET_TEMP_CEILING's default die-temp ceiling (°C).
     *  - [defaultTargetRuntimeHours] TARGET_RUNTIME's default "make it last H hours".
     */
    val defaultFpsFloor: Int? = null,
    val defaultTempCeilingC: Int? = null,
    val defaultTargetRuntimeHours: Float? = null,
) {
    /**
     * Never bottleneck the game; only back off to dodge the kill. SMOOTHNESS-first:
     * raises the min-freq FLOOR early (kills down-clock stutter) and perf-hints the
     * top app via UCLAMP instead of parking cores (smoother, EAS-native). Fan goes
     * Sport when hot.
     */
    MAX_FPS(
        gpuBandLowPct = 70,
        gpuBandHighPct = 95,
        softDieTempC = 95,
        bias = Bias.LOOSEN,
        hasHardPowerCeiling = false,
        // Loosen toward more headroom: lift the floor first (smoothness), then GPU,
        // then the cap, then push the uclamp hint up. No core un-parking — MAX_FPS
        // never parks (ACT-2: uclamp branch).
        loosenLeverOrder = listOf(Lever.MIN_FREQ_FLOOR, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.CAP, Lever.UCLAMP),
        // Tighten only to dodge the kill: ease the cap and GPU first, drop the floor,
        // then the uclamp hint — never park (uclamp is park's XOR sibling here).
        tightenLeverOrder = listOf(Lever.CAP, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.MIN_FREQ_FLOOR, Lever.UCLAMP),
        usesUclampNotPark = true,
    ),

    /** Best sustainable FPS *and* good temps — holds the knee. The default. */
    BALANCED_SMART(
        gpuBandLowPct = 63,
        gpuBandHighPct = 85,
        softDieTempC = 88,
        bias = Bias.HOLD_KNEE,
        hasHardPowerCeiling = false,
        // The canonical priority from the spec: loosen GPU→floor→cap→unpark.
        loosenLeverOrder = listOf(Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.MIN_FREQ_FLOOR, Lever.CAP, Lever.UNPARK),
        // Tighten cap→floor→GPU→park.
        tightenLeverOrder = listOf(Lever.CAP, Lever.MIN_FREQ_FLOOR, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.PARK),
        usesUclampNotPark = false,
    ),

    /**
     * Lowest temps + fan noise, accept some FPS. Aggressive pre-empt. Prefers the
     * cap + GPU-devfreq levers over parking (smoother thermal control), and runs the
     * fan Smart/Quiet by temperature.
     */
    COOL_QUIET(
        gpuBandLowPct = 48,
        gpuBandHighPct = 70,
        softDieTempC = 80,
        bias = Bias.PREEMPT,
        hasHardPowerCeiling = false,
        loosenLeverOrder = listOf(Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.CAP, Lever.MIN_FREQ_FLOOR, Lever.UNPARK),
        // Cap + GPU-devfreq FIRST (preferred over parking); park only as a last resort.
        tightenLeverOrder = listOf(Lever.CAP, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.MIN_FREQ_FLOOR, Lever.PARK),
        usesUclampNotPark = false,
    ),

    /** Max battery while staying responsive. Strongest tighten + hard power cap. */
    BATTERY_SAVER(
        gpuBandLowPct = 35,
        gpuBandHighPct = 60,
        softDieTempC = 85,
        bias = Bias.TIGHTEN,
        hasHardPowerCeiling = true,
        loosenLeverOrder = listOf(Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.CAP, Lever.MIN_FREQ_FLOOR, Lever.UNPARK),
        // Strongest tighten: cap then park aggressively for power, GPU/floor between.
        tightenLeverOrder = listOf(Lever.CAP, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.PARK, Lever.MIN_FREQ_FLOOR),
        usesUclampNotPark = false,
    ),

    /**
     * Reads the situation and delegates to the context classifier to pick one of
     * the four concrete goals every tick. Carries no band of its own — [resolve]
     * returns the classifier's choice. The sentinel edges below are never used as
     * a band (the engine resolves AUTO before reading any band edge); they are set
     * to BALANCED_SMART's so any accidental direct read degrades sanely.
     */
    AUTO(
        gpuBandLowPct = 63,
        gpuBandHighPct = 85,
        softDieTempC = 88,
        bias = Bias.HOLD_KNEE,
        hasHardPowerCeiling = false,
        loosenLeverOrder = listOf(Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.MIN_FREQ_FLOOR, Lever.CAP, Lever.UNPARK),
        tightenLeverOrder = listOf(Lever.CAP, Lever.MIN_FREQ_FLOOR, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.PARK),
        usesUclampNotPark = false,
    ),

    // ════════════════════════════════════════════════════════════════════════════
    //  UNIT 4 — RICHER OBJECTIVE GOAL MODES (setpoint generators)
    // ════════════════════════════════════════════════════════════════════════════
    // Each of the three modes below is a CONCRETE goal the band controller runs
    // DIRECTLY — its band/bias/soft-temp/lever-orders are REUSED from an existing
    // curated mode (those numbers are LAW; we do not invent new band values). The
    // mode's user-facing OBJECTIVE (temp ceiling / fps floor / runtime hours) is a
    // setpoint applied by the service's OUTER clamp ON TOP of this band controller —
    // it can only TIGHTEN the cap (strictly safer), never loosen past safety. The
    // 40% hard floor, the thermal kill, and revert all run underneath, unchanged.

    /**
     * UNIT 4 — TARGET_TEMP_CEILING: hold the die at/below a user temperature ceiling.
     *
     * Control engine = COOL_QUIET (aggressive thermal pre-empt): identical band
     * (48–70), bias (PREEMPT), soft die-temp (80), and lever orders. The user's
     * ceiling slider (70–95 °C, default 80) is enforced as an OUTER cap-ceiling guard
     * in the service: as the smoothed die approaches the ceiling the cap-ceiling
     * tightens (leaning on the committed fast-tighten urgency too), and relaxes when
     * the die is comfortably below — never above what is safe. The inner COOL_QUIET
     * pre-empt is the safety floor; the outer guard makes the arbitrary ceiling real.
     */
    TARGET_TEMP_CEILING(
        gpuBandLowPct = 48,
        gpuBandHighPct = 70,
        softDieTempC = 80,
        bias = Bias.PREEMPT,
        hasHardPowerCeiling = false,
        loosenLeverOrder = listOf(Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.CAP, Lever.MIN_FREQ_FLOOR, Lever.UNPARK),
        tightenLeverOrder = listOf(Lever.CAP, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.MIN_FREQ_FLOOR, Lever.PARK),
        usesUclampNotPark = false,
        defaultTempCeilingC = 80,
    ),

    /**
     * UNIT 4 — TARGET_FPS_FLOOR: the lowest power that still holds ≥ N FPS.
     *
     * Control engine = BATTERY_SAVER's aggressive tighten band (35–60, TIGHTEN bias,
     * its lever orders) — but WITHOUT the hard watts ceiling (hasHardPowerCeiling =
     * false: this mode walks the cap down by FPS feedback, not a power budget). The
     * fps-floor BLOCK is an OUTER anti-tighten guard in the service: when the frame
     * source is REAL (isRealFps) and realFps < N, it prevents any further tighten
     * (holds the cap at the last value) — the controller walks down until FPS just
     * touches the floor, then holds the knee. When NO real FPS source is available the
     * goal-resolution region DEGRADES this to BALANCED_SMART and the UI shows a banner
     * (honesty: never claim an FPS guarantee the device can't measure). User slider:
     * 30/40/45/60/90, default 60.
     */
    TARGET_FPS_FLOOR(
        gpuBandLowPct = 35,
        gpuBandHighPct = 60,
        softDieTempC = 85,
        bias = Bias.TIGHTEN,
        hasHardPowerCeiling = false,
        loosenLeverOrder = listOf(Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.CAP, Lever.MIN_FREQ_FLOOR, Lever.UNPARK),
        tightenLeverOrder = listOf(Lever.CAP, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.PARK, Lever.MIN_FREQ_FLOOR),
        usesUclampNotPark = false,
        defaultFpsFloor = 60,
    ),

    /**
     * UNIT 4 — TARGET_RUNTIME: make the battery last H hours.
     *
     * Control engine = BATTERY_SAVER's aggressive tighten band (35–60, TIGHTEN) without
     * the watts ceiling. The OUTER 60-second [RuntimeBudgetController] loop computes
     * budgetW = remainingWh / H, picks the largest OPP whose PowerModel-modelled draw ≤
     * budgetW, and sets that as a HARD CAP CEILING: the band controller may tighten
     * BELOW it but never loosen ABOVE it. Recomputed every 60 s as the battery drains.
     * The projection is MODELLED (PowerModel MEASURED/ESTIMATED honesty) and labelled —
     * never a guarantee. User slider: 1–6 h, default 3.
     */
    TARGET_RUNTIME(
        gpuBandLowPct = 35,
        gpuBandHighPct = 60,
        softDieTempC = 85,
        bias = Bias.TIGHTEN,
        hasHardPowerCeiling = false,
        loosenLeverOrder = listOf(Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.CAP, Lever.MIN_FREQ_FLOOR, Lever.UNPARK),
        tightenLeverOrder = listOf(Lever.CAP, Lever.GPU_DEVFREQ, Lever.GPU_FLOOR, Lever.PARK, Lever.MIN_FREQ_FLOOR),
        usesUclampNotPark = false,
        defaultTargetRuntimeHours = 3f,
    ),
    ;

    /** Band width in points. Asserted ≥ 22 for every concrete goal in tests. */
    val bandWidthPct: Int get() = gpuBandHighPct - gpuBandLowPct

    /**
     * The fan preset this goal wants for the given smoothed die temp (°C), or null
     * when the goal does not drive the fan (it leaves the vendor default / Smart in
     * place). The rate-limit + hysteresis + monotonic LAWS are applied AFTER this by
     * [FanGovernor.decide] — this only expresses INTENT.
     *
     * Preset scale ([FanPresets]): higher index = more cooling.
     *   - COOL_QUIET: Quiet when cool → Smart when warming (keeps it quiet, lifts the
     *     fan only as the die climbs). Never Sport (noise is the point of this goal).
     *   - MAX_FPS: Smart normally → Sport when hot (let it roar to hold clocks).
     *   - BALANCED_SMART / BATTERY_SAVER / AUTO: leave the fan alone (null) — they
     *     manage heat through clocks, not noise.
     *
     * @param dieTempC smoothed die temp, or null when unknown (→ no fan intent).
     */
    fun fanModeFor(dieTempC: Int?): Int? {
        if (dieTempC == null) return null
        return when (this) {
            COOL_QUIET, TARGET_TEMP_CEILING -> if (dieTempC >= softDieTempC - 5) FanPresets.SMART else FanPresets.QUIET
            MAX_FPS -> if (dieTempC >= softDieTempC - 8) FanPresets.SPORT else FanPresets.SMART
            // The objective FPS-floor / runtime modes manage heat through clocks, not
            // fan noise (like BALANCED/BATTERY/AUTO) — leave the fan at the vendor default.
            BALANCED_SMART, BATTERY_SAVER, AUTO, TARGET_FPS_FLOOR, TARGET_RUNTIME -> null
        }
    }

    /**
     * Loosen/tighten asymmetry knob.
     *
     * The base timing law (loosen on 1 confirming tick, tighten on 2) is fixed for
     * all goals. [Bias] only nudges the soft-temp pre-empt eagerness and which
     * direction the controller favours when a tick is genuinely ambiguous; the
     * dead-band and one-lever law are bias-independent (safety > preference).
     */
    enum class Bias {
        /** Favour loosening; pre-empt only near the soft temp. (MAX_FPS) */
        LOOSEN,
        /** Symmetric; hold inside the band aggressively. (BALANCED_SMART) */
        HOLD_KNEE,
        /** Favour pre-emptive tighten well before the soft temp. (COOL_QUIET) */
        PREEMPT,
        /** Favour tightening; strongest power reduction. (BATTERY_SAVER) */
        TIGHTEN,
    }

    /**
     * AYN/Retroid `fan_mode` Settings.System presets. The Odin exposes ONLY these
     * discrete presets — NOT a raw PWM curve — so we honestly map thermal intent to
     * a preset rather than faking a curve (per the honest-"no" in the design doc).
     *
     * Probed values on the AYN Odin 3: fan_mode default = 4 (Smart). The scale is
     * ordered by cooling aggressiveness so [FanGovernor]'s monotonic rule can compare
     * presets numerically (higher = more cooling). 0/1 are the quiet end, 4 Smart,
     * 5 Sport (max cooling).
     */
    object FanPresets {
        const val QUIET = 0
        const val SMART = 4
        const val SPORT = 5
    }

    companion object {

        /** The product default when nothing else is specified. */
        val DEFAULT = BALANCED_SMART

        /**
         * Map a legacy [AutoTdpProfile] to a [GoalProfile] for back-compat. The
         * daemon still hands the engine an [AutoTdpProfileConfig] built from the
         * old enum; the engine resolves it to a goal through this map so callers
         * (the service, intents, persisted prefs) keep working unchanged this wave.
         *
         *   EFFICIENCY     → COOL_QUIET   (mild-parking battery/thermal lean,
         *                                  no hard watts ceiling — closest to the
         *                                  old "park + cap for GPU-bound" intent)
         *   BALANCED       → BALANCED_SMART
         *   BATTERY_TARGET → BATTERY_SAVER (keeps the watts ceiling via
         *                                  targetMilliWatts; see hasHardPowerCeiling)
         *
         * NOTE: the spec offered "EFFICIENCY → BATTERY_SAVER or COOL_QUIET". We map
         * EFFICIENCY → COOL_QUIET because EFFICIENCY historically carried NO watts
         * budget (targetMilliWatts == null), so routing it to BATTERY_SAVER — which
         * expects a ceiling — would leave the hard-ceiling branch inert anyway while
         * losing COOL_QUIET's aggressive thermal pre-empt. COOL_QUIET preserves the
         * battery/thermal intent without a phantom power target. Flagged in report.
         */
        fun fromLegacyProfile(profile: AutoTdpProfile): GoalProfile = when (profile) {
            AutoTdpProfile.EFFICIENCY -> COOL_QUIET
            AutoTdpProfile.BALANCED -> BALANCED_SMART
            AutoTdpProfile.BATTERY_TARGET -> BATTERY_SAVER
        }
    }
}
