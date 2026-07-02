package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.FreqRange
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.LevelRange
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.monitor.CpuLoadReading
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter
import org.junit.Test

/**
 * 0.3.6 — the AYANEO PERFORMANCE-MODE lever, end-to-end through the engine + delta.
 *
 * On the AYANEO vendor-binder tier ([TdpCaps.supportsPerfMode] == true) the raw CPU cap is a
 * dead no-op (the 0.3.5 collapse) AND the vendor MODE owns the GPU bundle, so the engine
 * drives a single durable coarse lever — [Lever.PERF_MODE] — in place of BOTH Lever.CAP and
 * the GPU levers. These tests replay the daemon's own decide → delta loop (the same shape as
 * AyaneoDaemonSurvivalTest / the 0.3.5 fallthrough test) and assert:
 *   1. A sustained tighten steps the mode ordinal DOWN monotonically (stock 1 → 0).
 *   2. A sustained loosen steps the mode ordinal UP monotonically toward 4 / back to stock.
 *   3. delta() emits the pathless ayaPerformanceMode() WriteOp — MODE-FIRST — and emits NO
 *      GPU devfreq / CPU-cap op on this path (so a mode can never clobber a GPU write).
 *   4. On a fully-kernel-writable (ROOT) device the PERF_MODE lever is NEVER used and the
 *      CPU-cap path is unchanged (Odin / RP6 / root unregressed).
 */
class AutoTdpPerfModeLeverTest {

    private val ayaneoGpuRoot = "/sys/class/kgsl/kgsl-3d0"

    // AYANEO Pocket DS big-cluster OPP table (kHz), kernel top 2 592 000, stock 1 785 600.
    private val ayaneoFullOpp = listOf(
        691_200, 940_800, 1_190_400, 1_440_000, 1_555_200,
        1_632_000, 1_785_600, 1_881_600, 2_035_200, 2_265_600, 2_592_000,
    )
    private val ayaneoStockCeilingKhz = 1_785_600

    // GPU devfreq OPP table so a GPU lever WOULD be a real actuator if it were ever selected —
    // proving PERF_MODE genuinely suppresses it rather than the GPU table simply being absent.
    private val gpuStepsHz = listOf(
        220_000_000L, 350_000_000L, 490_000_000L, 605_000_000L, 700_000_000L, 1_000_000_000L,
    )

    private fun policy(
        id: Int,
        freqsKhz: List<Int>,
        onlineCores: List<Int> = listOf(id),
        stockMaxKhz: Int = freqsKhz.last(),
    ) = CpuPolicyProbe(
        policyId = id,
        onlineCores = onlineCores,
        availableFreqsKhz = freqsKhz,
        availableGovernors = listOf("schedutil", "performance", "powersave"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = stockMaxKhz,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

    private fun report(
        privilege: PrivilegeTier = PrivilegeTier.NONE,
        ayaneoBinderLive: Boolean = false,
        pserverSysfsLive: Boolean = false,
    ): CapabilityReport = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "AYANEO", brand = "AYANEO", model = "Pocket DS",
            device = "PocketDS", hardware = "kalama",
            androidVersion = "13", sdkInt = 33, knownHandheldKey = "ayaneo_pocket_ds",
        ),
        soc = SoCIdentity("QTI", "SG8275", GpuFamily.ADRENO),
        privilege = privilege,
        rootKind = if (privilege == PrivilegeTier.ROOT) RootKind.MAGISK else RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = listOf(
            policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
            policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
        ),
        gpu = GpuProbe(
            family = GpuFamily.ADRENO,
            rootPath = ayaneoGpuRoot,
            availableFreqsHz = gpuStepsHz,
            availableGovernors = listOf("msm-adreno-tz"),
            currentMinHz = gpuStepsHz.first(),
            currentMaxHz = gpuStepsHz.last(),
            currentGovernor = "msm-adreno-tz",
            powerLevelRange = LevelRange(0, gpuStepsHz.lastIndex),
        ),
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, ayaSpace = true, retroidGameAssistant = false),
        pserverSysfsLive = pserverSysfsLive,
        ayaneoBinderLive = ayaneoBinderLive,
    )

    private fun tightenSample() = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 10 },
        cpuLoadSource = CpuLoadReading.Source.DIRECT_PROC_STAT, // TRUE load
        gpuLoadPct = 1,                                          // below every band low → tighten
        gpuFreqHz = 200_000_000L,
        zoneTempsMilliC = listOf(ZoneTemp(0, "cpu", 60_000)),   // cool: no thermal pre-empt
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 300,
        batteryCurrentUa = 1_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
        foregroundPackage = null,
        gpuDieTempMilliC = 60_000,
        coolingDeviceMaxState = 0,
        realFpsX10 = null,
        isRealFps = false,
    )

    private fun loosenSample() = tightenSample().copy(
        gpuLoadPct = 99,          // above every band high → loosen
        gpuFreqHz = gpuStepsHz.last(),
    )

    /** Replay a session with the given per-tick sample; return final state + all ops emitted. */
    private fun replay(
        caps: TdpCaps,
        goal: GoalProfile,
        ticks: Int,
        sample: () -> Telemetry,
    ): Pair<TdpState, List<TdpStateTransition.WriteOp>> {
        var state = ControllerState.INITIAL
        var current = TdpState.STOCK
        val window = ArrayDeque<Telemetry>(5)
        val config = AutoTdpProfileConfig.forGoal(goal)
        val allOps = mutableListOf<TdpStateTransition.WriteOp>()
        repeat(ticks) {
            window.addLast(sample())
            if (window.size > 4) window.removeFirst()
            val d = AutoTdpEngine.decide(
                window = window.toList(),
                config = config,
                caps = caps,
                current = current,
                controllerState = state,
                goalOverride = goal,
            )
            if (d.target != current) {
                allOps += TdpStateTransition.delta(
                    from = current,
                    to = d.target,
                    bigPolicyId = caps.bigPolicyId,
                    gpuRootPath = ayaneoGpuRoot,
                    fanModeKey = null,
                )
            }
            current = d.target
            state = d.controllerState
        }
        return current to allOps
    }

    // ── 1. Tighten steps the mode DOWN monotonically to min (0) ─────────────────

    @Test
    fun `AYANEO sustained tighten steps the perf mode DOWN toward min-power`() {
        val caps = TdpCaps.from(report(ayaneoBinderLive = true))
        assertThat(caps.supportsPerfMode).isTrue()

        val (final, ops) = replay(caps, GoalProfile.BATTERY_SAVER, ticks = 200) { tightenSample() }

        // The mode was driven below stock (mode 0 = save is the tighten floor). Stock is
        // represented as null; a tighten from stock lands on the sub-stock override 0.
        assertThat(final.ayaPerfMode).isEqualTo(0)

        // A PERF-MODE write op was emitted through the pathless tunable.
        val modeTarget = Tunables.ayaPerformanceMode().target
        val modeOps = ops.filter { it.id.kind == TunableKind.AYANEO_PERF_MODE }
        assertThat(modeOps).isNotEmpty()
        assertThat(modeOps.first().id.target).isEqualTo(modeTarget)
        // The emitted values are all valid ordinals 0..4.
        modeOps.forEach { assertThat(it.value.toInt()).isIn(0..4) }
    }

    // ── 2. PERF_MODE OWNS CPU+GPU — no cap / GPU op ever emitted on this path ────

    @Test
    fun `AYANEO perf-mode path emits NO CPU-cap and NO GPU devfreq write (mode owns both)`() {
        val caps = TdpCaps.from(report(ayaneoBinderLive = true))
        val (_, ops) = replay(caps, GoalProfile.COOL_QUIET, ticks = 120) { tightenSample() }

        val capTarget = Tunables.cpuMaxFreq(caps.bigPolicyId).target
        val gpuMaxTarget = Tunables.gpuMaxFreq(ayaneoGpuRoot).target
        val gpuMinTarget = Tunables.gpuMinFreq(ayaneoGpuRoot).target

        // The GPU devfreq table IS present (a GPU lever would actuate if selected) — proving
        // suppression is by design, not absence of a table.
        assertThat(caps.gpuDevfreqStepsHz).isNotEmpty()

        // Not a single CPU-cap or GPU-devfreq op — the mode lever owns CPU+GPU atomically.
        assertThat(ops.none { it.id.target == capTarget }).isTrue()
        assertThat(ops.none { it.id.target == gpuMaxTarget }).isTrue()
        assertThat(ops.none { it.id.target == gpuMinTarget }).isTrue()
        // The mode lever DID fire.
        assertThat(ops.any { it.id.kind == TunableKind.AYANEO_PERF_MODE }).isTrue()
    }

    @Test
    fun `every emitted perf-mode op is a pathless AYANEO_PERF_MODE tunable, verify-routable`() {
        val caps = TdpCaps.from(report(ayaneoBinderLive = true))
        val (_, ops) = replay(caps, GoalProfile.BATTERY_SAVER, ticks = 80) { tightenSample() }
        val modeOps = ops.filter { it.id.kind == TunableKind.AYANEO_PERF_MODE }
        assertThat(modeOps).isNotEmpty()
        // Pathless: the target is the synthetic label, NOT a /sys path, and never matches a
        // sysfs classifier (so it can only be driven via the binder mode token).
        modeOps.forEach {
            assertThat(it.id.target).doesNotContain("/sys/")
            assertThat(AyaneoVendorWriter.isBindableNode(it.id.target)).isFalse()
        }
    }

    // ── 3. Loosen steps the mode UP monotonically (reversible ladder) ───────────

    @Test
    fun `AYANEO from a tightened mode, a sustained loosen steps the mode UP toward stock and beyond`() {
        val caps = TdpCaps.from(report(ayaneoBinderLive = true))

        // Start from the tightened floor (mode 0), then loosen for a long run.
        var state = ControllerState.INITIAL
        var current = TdpState(ayaPerfMode = 0)
        val window = ArrayDeque<Telemetry>(5)
        val config = AutoTdpProfileConfig.forGoal(GoalProfile.MAX_FPS)
        val seenModes = mutableListOf<Int?>()
        repeat(200) {
            window.addLast(loosenSample())
            if (window.size > 4) window.removeFirst()
            val d = AutoTdpEngine.decide(
                window = window.toList(),
                config = config,
                caps = caps,
                current = current,
                controllerState = state,
                goalOverride = GoalProfile.MAX_FPS,
            )
            if (d.target.ayaPerfMode != current.ayaPerfMode) seenModes += d.target.ayaPerfMode
            current = d.target
            state = d.controllerState
        }

        // The ladder climbed: it passed through stock (null) on the way up from 0, and rose
        // above stock toward max power (mode 4). Effective ordinals are strictly increasing.
        assertThat(seenModes).isNotEmpty()
        val effective = (listOf(0) + seenModes).map { it ?: 1 } // null ≡ stock (1)
        // Monotonic non-decreasing, and it moved above stock.
        for (i in 1 until effective.size) {
            assertThat(effective[i]).isAtLeast(effective[i - 1])
        }
        assertThat(effective.last()).isEqualTo(4)
        // It genuinely returned to stock (null) at some point while climbing 0 → 1.
        assertThat(seenModes).contains(null)
    }

    // ── 4. ROOT device: PERF_MODE lever NEVER used, CPU cap path unchanged ──────

    @Test
    fun `ROOT device never uses the perf-mode lever and still caps the CPU cluster`() {
        val caps = TdpCaps.from(report(privilege = PrivilegeTier.ROOT))
        assertThat(caps.supportsPerfMode).isFalse()

        val (final, ops) = replay(caps, GoalProfile.BATTERY_SAVER, ticks = 200) { tightenSample() }

        // No PERF-MODE anything.
        assertThat(final.ayaPerfMode).isNull()
        assertThat(ops.none { it.id.kind == TunableKind.AYANEO_PERF_MODE }).isTrue()
        // The CPU cap lever DID fire (unregressed direct-cap behaviour).
        val capTarget = Tunables.cpuMaxFreq(caps.bigPolicyId).target
        assertThat(ops.any { it.id.target == capTarget }).isTrue()
        assertThat(final.bigClusterCapKhz).isNotNull()
    }

    // ── 5. delta() emits the mode op FIRST (ahead of every other op) ────────────

    @Test
    fun `delta emits the perf-mode write ahead of any other op (mode-first ordering)`() {
        // A composed target that changes the mode AND (hypothetically) a park — the mode must
        // come first so it can never clobber a later GPU/other write.
        val from = TdpState.STOCK
        val to = TdpState(ayaPerfMode = 0, parkedPrimeCores = setOf(7))
        val ops = TdpStateTransition.delta(
            from = from,
            to = to,
            bigPolicyId = 4,
            gpuRootPath = ayaneoGpuRoot,
            fanModeKey = null,
        )
        assertThat(ops).isNotEmpty()
        assertThat(ops.first().id.kind).isEqualTo(TunableKind.AYANEO_PERF_MODE)
        assertThat(ops.first().value).isEqualTo("0")
    }

    @Test
    fun `delta emits no perf-mode op when the mode is unchanged`() {
        val ops = TdpStateTransition.delta(
            from = TdpState(ayaPerfMode = 2),
            to = TdpState(ayaPerfMode = 2, bigClusterCapKhz = 1_500_000),
            bigPolicyId = 4,
            gpuRootPath = ayaneoGpuRoot,
            fanModeKey = null,
        )
        assertThat(ops.none { it.id.kind == TunableKind.AYANEO_PERF_MODE }).isTrue()
    }
}
