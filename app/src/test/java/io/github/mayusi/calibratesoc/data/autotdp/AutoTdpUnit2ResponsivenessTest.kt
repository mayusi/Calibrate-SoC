package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import org.junit.Test

/**
 * UNIT 2 — CONTROL-LOOP RESPONSIVENESS & STABILITY (Axis 2).
 *
 * Deterministic replay of synthetic [Telemetry] traces against the PURE engine. Covers
 * the three Unit-2 changes plus the adaptive-cadence hint, and re-asserts the
 * SAFETY-is-LAW invariants are unbroken:
 *
 *   2a. ASYMMETRIC reaction — a genuine thermal/throttle tighten lands in 1 tick (FAST,
 *       0-confirm) while a band-only tighten on calm thermals keeps the 2-tick confirm so
 *       noisy GPU% can never hunt the cap. LOOSEN stays at 1 confirm (fast-down/slow-up).
 *   2b. RATE-LIMITED multi-OPP swing — a single tick steps the cap by ≤ 2 OPPs.
 *   2c. ADAPTIVE cadence — nextTickHintMs is 500 ms when warming, 1000 ms when calm.
 *
 * Plus: the 40% hard cap floor, the multi-OPP clamp running BEFORE enforceInvariants, and
 * the no-hunting-on-noise guarantee all hold. The 105°C kill + NonCancellable revert are
 * the daemon's separate paths (verified in ThermalKillEvaluatorTest / AutoTdpRevertTest)
 * and are UNAFFECTED by this layer — re-asserted at the boundary here.
 */
class AutoTdpUnit2ResponsivenessTest {

    // Odin-style big OPP ladder (kHz), ascending. 40% of 2_803_000 ≈ 1_121_200 →
    // first OPP ≥ that = 1_171_000 (index 2), the hard cap floor.
    private val bigOpps = listOf(
        499_000, 844_000, 1_171_000, 1_536_000,
        1_920_000, 2_323_000, 2_707_000, 2_803_000,
    )
    private val hardFloorKhz = bigOpps.first { it >= (bigOpps.last() * 0.40).toInt() }

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = bigOpps,
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
        gpuDevfreqFloorHz = 160_000_000L,
        gpuDevfreqCeilHz = 1_100_000_000L,
        gpuDevfreqStepsHz = listOf(160_000_000L, 1_100_000_000L),
        gpuRootPath = "/sys/class/kgsl/kgsl-3d0",
        uclampAvailable = true,
        fanModeAvailable = true,
    )

    // BALANCED_SMART: GPU band 63-85, soft die 88°C. Default goal, no hard ceiling.
    private val config = AutoTdpProfileConfig(AutoTdpProfile.BALANCED)

    /**
     * One synthetic sample. [gpu] busy%, [dieC] die temp, optional [coolingState].
     * TRUE jiffie load (DIRECT_PROC_STAT) at a modest 30% so the controller is NOT
     * load-blind and a real CPU core is not saturated (so the band path runs, not the
     * CPU-saturation unpark). die is a sane milli-C value so the engine uses the die.
     */
    private fun tel(gpu: Int, dieC: Int, coolingState: Int? = null) = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 30 },
        cpuLoadSource = CpuLoadReading.Source.DIRECT_PROC_STAT,
        gpuLoadPct = gpu,
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = emptyList(),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 280,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = null,
        gpuDieTempMilliC = dieC * 1000,
        coolingDeviceMaxState = coolingState,
        realFpsX10 = null,
        isRealFps = false,
    )

    private fun decide(window: List<Telemetry>, state: ControllerState, current: TdpState = TdpState.STOCK) =
        AutoTdpEngine.decide(
            window = window,
            config = config,
            caps = caps,
            current = current,
            controllerState = state,
        )

    // ════════════════════════════════════════════════════════════════════════════
    //  2a. ASYMMETRIC REACTION — fast down on a real thermal signal, slow on noise
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * A genuine FAST-tighten signal (die heating ≥2°C/s) with GPU below the band tightens
     * in a SINGLE tick — no 2-tick confirm. We isolate the FAST *band* tighten (not the
     * thermal pre-empt): the die (~50°C) is far below soft (88°C) and below soft-8 (80),
     * and the slope (2.0) is below the pre-empt arm-2 threshold (3.0), so the pre-empt
     * arms do NOT fire; only the band tighten's FAST urgency arm (dTemp ≥ 2.0) does.
     */
    @Test
    fun `2a fast regime — die heating fast plus GPU below band tightens in one tick`() {
        // Rising die [44,46,48,50] → seeded slope = 2.0 °C/s (FAST). GPU 40 < band.low 63.
        val window = listOf(44, 46, 48, 50).map { tel(gpu = 40, dieC = it) }
        val d = decide(window, ControllerState.INITIAL)

        // Acted THIS tick: the big cap stepped down one OPP from stock (top), no confirm wait.
        assertThat(d.target.bigClusterCapKhz).isNotNull()
        assertThat(d.target.bigClusterCapKhz!!).isLessThan(bigOpps.last())
        assertThat(d.holdReason).isEqualTo(HoldReason.GPU_BOUND_CAPPING)
        // Slope is genuinely ≥ the FAST threshold (sanity on the trace).
        assertThat(d.controllerState.dTempSlopeEwma!!).isAtLeast(2.0)
    }

    /**
     * coolingState > 0 (kernel throttling NOW) is a FAST signal. With GPU below the band
     * and calm temps otherwise, the controller still tightens in 1 tick. (A cooling state
     * also satisfies the thermal pre-empt arm-3; either way the cap moves THIS tick — the
     * point is fast reaction, not which arm fired.)
     */
    @Test
    fun `2a fast regime — kernel throttling tightens in one tick`() {
        val window = listOf(45, 45, 45, 45).map { tel(gpu = 40, dieC = it, coolingState = 1) }
        val d = decide(window, ControllerState.INITIAL)
        assertThat(d.target.bigClusterCapKhz).isNotNull()
        assertThat(d.target.bigClusterCapKhz!!).isLessThan(bigOpps.last())
    }

    /**
     * The SLOW (band-only) regime: GPU below the band but thermals are CALM (flat ~45°C,
     * ~0 slope, no throttle). The tighten must NOT act on the first qualifying tick — it
     * needs the conservative 2-tick confirm so noisy GPU% can't hunt the cap.
     */
    @Test
    fun `2a band regime — calm-thermal tighten waits the 2-tick confirm`() {
        val flat = listOf(45, 45, 45, 45)
        // Tick 1: GPU 40 < band, die flat → BAND urgency, confirmTicks 1 < 2 → HOLD (no cap).
        val w1 = flat.map { tel(gpu = 40, dieC = it) }
        val d1 = decide(w1, ControllerState.INITIAL)
        assertThat(d1.target.bigClusterCapKhz).isNull() // did not act — still confirming
        assertThat(d1.controllerState.confirmTicks).isEqualTo(1)

        // Tick 2: same calm trace → confirm reaches 2 → NOW it acts.
        val w2 = flat.map { tel(gpu = 40, dieC = it) }
        val d2 = decide(w2, d1.controllerState, current = d1.target)
        assertThat(d2.target.bigClusterCapKhz).isNotNull()
        assertThat(d2.target.bigClusterCapKhz!!).isLessThan(bigOpps.last())
    }

    /**
     * NO HUNTING on a noisy GPU% trace that wanders around/below the band with calm
     * thermals. Replay 12 ticks of jittery GPU% (the kind of 2σ noise the dead-band is
     * built to absorb). The cap must only ever move DOWN in conservative single OPP steps
     * (band tighten) and NEVER oscillate up-then-down-then-up (hunting). We assert the cap
     * index is monotonic non-increasing across the whole replay.
     */
    @Test
    fun `2a band regime — noisy GPU inside-ish the band does not hunt`() {
        // GPU jitter straddling the low edge (band.low = 63): 60,66,58,64,61,67,59,62,65,57,63,60.
        val noisyGpu = listOf(60, 66, 58, 64, 61, 67, 59, 62, 65, 57, 63, 60)
        var state = ControllerState.INITIAL
        var current = TdpState.STOCK
        var lastCapIdx = bigOpps.lastIndex // uncapped == top OPP
        var movedUpEver = false
        for (g in noisyGpu) {
            // Calm flat die so urgency is BAND, never FAST.
            val window = listOf(45, 45, 45, 45).map { tel(gpu = g, dieC = it) }
            val d = decide(window, state, current)
            val capIdx = d.target.bigClusterCapKhz?.let { bigOpps.indexOf(it) } ?: bigOpps.lastIndex
            if (capIdx > lastCapIdx) movedUpEver = true // a loosen step = potential hunt
            lastCapIdx = capIdx
            state = d.controllerState
            current = d.target
        }
        // The cap never loosened back up on noise → no tighten↔loosen hunting.
        assertThat(movedUpEver).isFalse()
    }

    /**
     * LOOSEN is untouched by Unit 2: GPU above the band still loosens on a SINGLE confirm
     * tick (the asymmetry is fast-DOWN / slow-UP — loosen stays at 1 confirm). We assert
     * the controller ACTED in one tick (state changed) with the relax hold reason, without
     * pinning which lever moved (BALANCED_SMART's loosen order leads with GPU/min, not cap).
     */
    @Test
    fun `2a loosen unchanged — acts on a single confirm`() {
        // Pre-cap the cluster so there IS something to loosen, GPU above band → loosen.
        val current = TdpState(bigClusterCapKhz = bigOpps[3])
        val window = listOf(45, 45, 45, 45).map { tel(gpu = 95, dieC = it) }
        val d = decide(window, ControllerState.INITIAL, current)
        // One confirm tick is enough — SOME loosen lever moved this tick (state changed).
        assertThat(d.target).isNotEqualTo(current)
        assertThat(d.holdReason).isEqualTo(HoldReason.CPU_BOUND_RELAXING)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  2b. RATE-LIMITED multi-OPP swing — cap never falls > 2 OPPs in one tick
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Across a SUSTAINED FAST-tighten run (die heating fast, GPU pinned below the band),
     * the cap is driven down as hard as the engine wants — but every single tick may drop
     * it by at most MAX_CAP_STEP_PER_TICK (2) OPPs. We replay many ticks and assert no
     * per-tick cap fall ever exceeds 2 OPP indices, AND the cap still honours the 40% hard
     * floor at the end (never collapses to the bottom).
     */
    @Test
    fun `2b cap steps by at most 2 OPPs per tick under a sustained fast tighten`() {
        var state = ControllerState.INITIAL
        var current = TdpState.STOCK
        var dieBase = 44
        var prevCapIdx = bigOpps.lastIndex // start uncapped (top)
        repeat(20) {
            // A continuously-rising die keeps the FAST regime engaged every tick.
            val window = listOf(dieBase, dieBase + 2, dieBase + 4, dieBase + 6)
                .map { tel(gpu = 35, dieC = it) }
            dieBase += 2
            val d = decide(window, state, current)
            val capIdx = d.target.bigClusterCapKhz?.let { bigOpps.indexOf(it) } ?: bigOpps.lastIndex
            // Per-tick fall ≤ 2 OPPs (the clamp). Rises (none expected) are also fine.
            val fall = prevCapIdx - capIdx
            assertThat(fall).isAtMost(2)
            prevCapIdx = capIdx
            state = d.controllerState
            current = d.target
        }
        // 40% hard floor held — the cap never sank below the floor OPP (no 384 MHz collapse).
        assertThat(current.bigClusterCapKhz!!).isAtLeast(hardFloorKhz)
    }

    /**
     * Direct unit of the clamp boundary: a (hypothetical) lever proposing a cap 4 OPPs
     * below the current cap is rate-limited back up to exactly 2 OPPs below. We drive this
     * through the public engine by pre-seeding a high cap and confirming the realized step
     * never exceeds 2 in a single decision, even when the engine "wants" more.
     */
    @Test
    fun `2b single-tick cap fall is clamped to 2 OPPs even from the top`() {
        // Uncapped start (top OPP idx 7). One FAST tighten tick.
        val window = listOf(44, 46, 48, 50).map { tel(gpu = 35, dieC = it) }
        val d = decide(window, ControllerState.INITIAL, TdpState.STOCK)
        val capIdx = bigOpps.indexOf(d.target.bigClusterCapKhz)
        // From idx 7, a clamped fall of ≤2 means the cap lands at idx ≥ 5.
        assertThat(capIdx).isAtLeast(bigOpps.lastIndex - 2)
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  2c. ADAPTIVE CADENCE — nextTickHintMs: 500 warming, 1000 calm
    // ════════════════════════════════════════════════════════════════════════════

    /** Calm (flat die, gentle slope, in-band GPU) → the default 1000 ms hint. */
    @Test
    fun `2c calm thermals request the 1000ms cadence`() {
        // In-band GPU (~74 of 63-85), flat cool die well below soft-5 (83).
        val window = listOf(45, 45, 45, 45).map { tel(gpu = 74, dieC = it) }
        val d = decide(window, ControllerState.INITIAL)
        assertThat(d.nextTickHintMs).isEqualTo(1000)
    }

    /** Warming (die slope ≥ 1°C/s) → the faster 500 ms hint. */
    @Test
    fun `2c warming slope requests the 500ms cadence`() {
        // Rising die [44,45,46,47] → slope ~1.0 (≥ WARM 1.0). GPU in band so the hint, not
        // a tighten, is what we are checking.
        val window = listOf(44, 45, 46, 47).map { tel(gpu = 74, dieC = it) }
        val d = decide(window, ControllerState.INITIAL)
        assertThat(d.nextTickHintMs).isEqualTo(500)
    }

    /** Near-soft (die within 5°C of soft 88 → ≥83) → the faster 500 ms hint even if flat. */
    @Test
    fun `2c die near soft requests the 500ms cadence`() {
        val window = listOf(84, 84, 84, 84).map { tel(gpu = 74, dieC = it) }
        val d = decide(window, ControllerState.INITIAL)
        assertThat(d.nextTickHintMs).isEqualTo(500)
    }

    /** The hint is ALWAYS at the 500 ms floor or above — never faster than 2 Hz. */
    @Test
    fun `2c cadence never requests faster than the 500ms floor`() {
        // Extreme: die well over soft, steep slope. Hint must still be ≥ 500, never lower.
        val window = listOf(80, 86, 92, 98).map { tel(gpu = 40, dieC = it) }
        val d = decide(window, ControllerState.INITIAL)
        assertThat(d.nextTickHintMs!!).isAtLeast(500)
    }

    /** No telemetry → no hint (null) → daemon keeps its default cadence (behaviour-preserving). */
    @Test
    fun `2c empty window leaves the hint null`() {
        val d = AutoTdpEngine.decide(
            window = emptyList(),
            config = config,
            caps = caps,
            current = TdpState.STOCK,
            controllerState = ControllerState.INITIAL,
        )
        assertThat(d.nextTickHintMs).isNull()
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  SAFETY-is-LAW — invariants unbroken by the Unit-2 layer
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * The 40% HARD cap floor holds under the WORST case for Unit 2: BATTERY_SAVER (the
     * strongest tighten) under a sustained FAST tighten with a phantom-cool/low GPU. The
     * cap must stop at/above the hard floor — the FAST regime + multi-OPP clamp can only
     * make the cap FALL SLOWER, never deeper, so the construction-level floor still holds.
     */
    @Test
    fun `safety — 40 percent hard cap floor holds under sustained fast tighten`() {
        val saverConfig = AutoTdpProfileConfig(AutoTdpProfile.BATTERY_TARGET)
        var state = ControllerState.INITIAL
        var current = TdpState.STOCK
        var dieBase = 44
        repeat(40) {
            val window = listOf(dieBase, dieBase + 2, dieBase + 4, dieBase + 6)
                .map { tel(gpu = 20, dieC = it) }
            dieBase += 1
            val d = AutoTdpEngine.decide(
                window = window,
                config = saverConfig,
                caps = caps,
                current = current,
                controllerState = state,
            )
            // INVARIANT: the cap is NEVER below the 40% hard floor on any emitted tick.
            d.target.bigClusterCapKhz?.let { assertThat(it).isAtLeast(hardFloorKhz) }
            state = d.controllerState
            current = d.target
        }
        assertThat(current.bigClusterCapKhz!!).isAtLeast(hardFloorKhz)
    }

    /**
     * MM-1 / MM-2 hold through the new clamp: the min floor (when set) stays strictly below
     * the cap and both remain real OPP members after a FAST tighten run. The clamp runs
     * BEFORE enforceInvariants, so enforceInvariants still has the final say.
     */
    @Test
    fun `safety — MM-1 and MM-2 hold after a fast tighten run`() {
        var state = ControllerState.INITIAL
        var current = TdpState.STOCK
        var dieBase = 44
        repeat(30) {
            val window = listOf(dieBase, dieBase + 2, dieBase + 4, dieBase + 6)
                .map { tel(gpu = 30, dieC = it) }
            dieBase += 1
            val d = decide(window, state, current)
            val cap = d.target.bigClusterCapKhz
            val min = d.target.bigClusterMinKhz
            if (cap != null) assertThat(cap).isIn(bigOpps) // MM-2: cap is a real OPP
            if (min != null) {
                assertThat(min).isIn(bigOpps) // MM-2: min is a real OPP
                if (cap != null) assertThat(min).isLessThan(cap) // MM-1: floor strictly < cap
            }
            state = d.controllerState
            current = d.target
        }
    }
}
