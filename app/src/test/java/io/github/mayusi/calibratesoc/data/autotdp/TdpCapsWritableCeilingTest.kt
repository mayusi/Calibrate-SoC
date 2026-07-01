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
import io.github.mayusi.calibratesoc.data.thermal.CapFloor
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import org.junit.Test

/**
 * Regression tests for the AYANEO AutoTDP writable-ceiling crash (live-diagnosed on
 * the AYANEO Pocket DS): the engine targeted the FULL kernel OPP top (2 592 000 kHz)
 * instead of the vendor-WRITABLE ceiling (stock scaling_max_freq = 1 785 600 kHz).
 * The AYANEO `gamewindow` overlay rejects any scaling_max_freq above the stock max, so
 * the cap write was always rejected → the clock never moved AND the repeated
 * above-ceiling write storm crashed `com.ayaneo.gamewindow`.
 *
 * The fix clamps [TdpCaps.bigClusterOppStepsKhz] — the single cap-target step table the
 * whole engine walks — to the effective writable ceiling in [TdpCaps.from]:
 *   • fully-kernel-writable path (ROOT / PServer live / chmod-direct) → writable ==
 *     kernel top → NO clamp (Odin 3 / RP6 still reach max — unregressed), and
 *   • constrained vendor path (AYANEO binder etc.) → writable == stock scaling_max_freq
 *     → the step table (and every derived cap-target + the 40% floor) tops out there.
 *
 * These tests assert the model at the caps layer AND that the engine's own stepping
 * helpers (via the public [AutoTdpEngine] contract) never target above the writable
 * ceiling.
 */
class TdpCapsWritableCeilingTest {

    // ─── Fixture builders ──────────────────────────────────────────────────────

    /**
     * A CPU policy whose full kernel OPP table tops at [freqsKhz].last(), but whose
     * STOCK scaling_max_freq ([currentMaxKhz]) may be lower — exactly the AYANEO
     * shape (kernel top 2 592 000, stock ceiling 1 785 600).
     */
    private fun policy(
        id: Int,
        freqsKhz: List<Int>,
        onlineCores: List<Int> = listOf(id),
        stockMaxKhz: Int = freqsKhz.last(),
    ) = CpuPolicyProbe(
        policyId = id,
        onlineCores = onlineCores,
        availableFreqsKhz = freqsKhz,
        availableGovernors = listOf("schedutil"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = stockMaxKhz,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

    /**
     * A report with fully-configurable write-tier gating. Defaults model a
     * CONSTRAINED vendor device (no root, no PServer, no chmod) — the AYANEO case.
     */
    private fun reportWith(
        policies: List<CpuPolicyProbe>,
        privilege: PrivilegeTier = PrivilegeTier.VENDOR_SETTINGS,
        pserverSysfsLive: Boolean = false,
        sysfsDirectlyWritable: Boolean = false,
        ayaneoBinderLive: Boolean = false,
        gpu: GpuProbe? = null,
    ): CapabilityReport = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Test",
            brand = "Test",
            model = "TestDevice",
            device = "test",
            hardware = "test",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = null,
        ),
        soc = SoCIdentity("Qualcomm", "Snapdragon", GpuFamily.ADRENO),
        privilege = privilege,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = gpu,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
        pserverSysfsLive = pserverSysfsLive,
        sysfsDirectlyWritable = sysfsDirectlyWritable,
        ayaneoBinderLive = ayaneoBinderLive,
    )

    // The AYANEO Pocket DS big-cluster OPP table (kHz), kernel top 2 592 000, but the
    // vendor overlay's stock scaling_max_freq is 1 785 600 — anything above is rejected.
    private val ayaneoFullOpp = listOf(
        691_200, 940_800, 1_190_400, 1_440_000, 1_555_200,
        1_632_000, 1_785_600, 1_881_600, 2_035_200, 2_265_600, 2_592_000,
    )
    private val ayaneoStockCeilingKhz = 1_785_600
    private val ayaneoKernelTopKhz = 2_592_000

    // ─── The crash fix: constrained vendor path clamps to stock scaling_max_freq ──

    @Test
    fun `AYANEO vendor-binder device clamps cap-target steps to the stock writable ceiling`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            ayaneoBinderLive = true,
        )

        val caps = TdpCaps.from(report)

        // The step table the controller walks NEVER contains an unwritable OPP.
        assertThat(caps.bigClusterOppStepsKhz.last()).isEqualTo(ayaneoStockCeilingKhz)
        assertThat(caps.bigClusterOppStepsKhz).doesNotContain(2_035_200)
        assertThat(caps.bigClusterOppStepsKhz).doesNotContain(2_265_600)
        assertThat(caps.bigClusterOppStepsKhz).doesNotContain(ayaneoKernelTopKhz)
        assertThat(caps.bigClusterWritableMaxKhz).isEqualTo(ayaneoStockCeilingKhz)
        // Every retained step is a real, writable OPP.
        assertThat(caps.bigClusterOppStepsKhz).isInOrder()
        caps.bigClusterOppStepsKhz.forEach { assertThat(it).isAtMost(ayaneoStockCeilingKhz) }
    }

    @Test
    fun `stepping the cap down on AYANEO never targets above the writable ceiling`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            ayaneoBinderLive = true,
        )
        val caps = TdpCaps.from(report)

        // An uncapped cluster (bigClusterCapKhz == null) anchors to steps.lastIndex.
        // Before the fix that was the kernel top (2 592 000) → rejected forever.
        // Now the top of the step table IS the writable ceiling.
        val topStep = caps.bigClusterOppStepsKhz.last()
        assertThat(topStep).isEqualTo(ayaneoStockCeilingKhz)

        // The 40% hard floor is a fraction of the WRITABLE top, and stays <= ceiling.
        val floor = CapFloor.hardFloorKhz(caps.bigClusterOppStepsKhz)!!
        assertThat(floor).isAtMost(caps.bigClusterWritableMaxKhz)
        assertThat(floor).isGreaterThan(0)
        // Floor is a real, writable OPP in the clamped table.
        assertThat(caps.bigClusterOppStepsKhz).contains(floor)

        // deriveBudgetCap (the BATTERY_SAVER budget path) also walks the clamped table,
        // so even a max-budget request can never exceed the writable ceiling.
        val maxBudgetCap = AutoTdpEngine.deriveBudgetCap(caps, Long.MAX_VALUE)!!
        assertThat(maxBudgetCap).isAtMost(caps.bigClusterWritableMaxKhz)
        assertThat(maxBudgetCap).isEqualTo(ayaneoStockCeilingKhz)
    }

    // ─── No regression: fully-kernel-writable devices still reach the kernel top ──

    @Test
    fun `PServer-live device keeps the full kernel OPP table (Odin 3 RP6 unregressed)`() {
        // PServer root runner can write ANY OPP → writable ceiling == kernel top.
        // Even if the stock scaling_max_freq happens to read lower, we must NOT clamp.
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            privilege = PrivilegeTier.VENDOR_SETTINGS,
            pserverSysfsLive = true,
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.bigClusterOppStepsKhz.last()).isEqualTo(ayaneoKernelTopKhz)
        assertThat(caps.bigClusterOppStepsKhz).contains(ayaneoKernelTopKhz)
        assertThat(caps.bigClusterWritableMaxKhz).isEqualTo(ayaneoKernelTopKhz)
    }

    @Test
    fun `ROOT device keeps the full kernel OPP table`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            privilege = PrivilegeTier.ROOT,
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.bigClusterOppStepsKhz.last()).isEqualTo(ayaneoKernelTopKhz)
        assertThat(caps.bigClusterWritableMaxKhz).isEqualTo(ayaneoKernelTopKhz)
    }

    @Test
    fun `chmod-direct writable device keeps the full kernel OPP table`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            privilege = PrivilegeTier.VENDOR_SETTINGS,
            sysfsDirectlyWritable = true,
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.bigClusterOppStepsKhz.last()).isEqualTo(ayaneoKernelTopKhz)
        assertThat(caps.bigClusterWritableMaxKhz).isEqualTo(ayaneoKernelTopKhz)
    }

    // ─── The 40% floor stays <= the writable ceiling on a constrained device ─────

    @Test
    fun `hard cap floor stays at or below the writable ceiling`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            ayaneoBinderLive = true,
        )
        val caps = TdpCaps.from(report)

        val floor = CapFloor.hardFloorKhz(caps.bigClusterOppStepsKhz)!!
        // DEAD-CPU-CAP COLLAPSE: on the AYANEO vendor-binder path the cap-target table
        // collapses to a SINGLE stock step (cap-down is a no-op → the engine falls through
        // to the GPU lever). CapFloor.hardFloorKhz of a single-step table returns that step,
        // so the hard floor equals the single step == the writable ceiling. It must stay a
        // usable OPP at/below the ceiling — never above it.
        assertThat(floor).isAtMost(caps.bigClusterWritableMaxKhz)
        assertThat(floor).isEqualTo(caps.bigClusterOppStepsKhz.last())
        assertThat(floor).isGreaterThan(0)
    }

    // ─── Degenerate: writable ceiling below the lowest OPP keeps ≥1 usable step ──

    @Test
    fun `writable ceiling below the lowest OPP still yields one usable step`() {
        // Pathological stock read far below every OPP — never strand the engine with
        // an empty table; keep the single lowest step so stepping stays valid.
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = 100_000),
            ),
            ayaneoBinderLive = true,
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.bigClusterOppStepsKhz).hasSize(1)
        assertThat(caps.bigClusterOppStepsKhz.first()).isEqualTo(ayaneoFullOpp.first())
    }

    // ─── Missing stock read falls back to kernel top (never under-clamp to zero) ──

    @Test
    fun `zero stock scaling_max falls back to the kernel top`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = 0),
            ),
            ayaneoBinderLive = true,
        )
        val caps = TdpCaps.from(report)

        // A bogus/absent stock read must NOT collapse the table to nothing.
        assertThat(caps.bigClusterOppStepsKhz.last()).isEqualTo(ayaneoKernelTopKhz)
        assertThat(caps.bigClusterWritableMaxKhz).isEqualTo(ayaneoKernelTopKhz)
    }

    // ═════════════════════════════════════════════════════════════════════════════
    //  SHIP-BLOCKER: dead-CPU-cap collapse → cap lever goes quiet → GPU lever drives
    // ═════════════════════════════════════════════════════════════════════════════
    // On AYANEO the vendor overlay accepts a scaling_max_freq write ONLY at the stock
    // ceiling (a no-op); any sub-stock value is rejected. So CPU cap-DOWN is a DEAD lever.
    // Keeping a full sub-stock step table made stepCapDown ALWAYS report changed=true, so
    // applyTightenLever returned on Lever.CAP FIRST every tick and NEVER fell through to
    // the GPU lever that DOES actuate. The fix collapses the cap-target table to a single
    // stock step so stepCapDown returns changed=false and the EXISTING lever fallthrough
    // reaches GPU_DEVFREQ same-tick. These tests pin BOTH the caps-layer contract AND the
    // real engine-loop fallthrough.

    @Test
    fun `AYANEO dead-CPU-cap path collapses the cap-target table to a single stock step`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            ayaneoBinderLive = true,
        )
        val caps = TdpCaps.from(report)

        // Single entry, equal to the stock ceiling. With one step, stepCapDown can find no
        // lower index → returns changed=false → applyTightenLever advances past Lever.CAP.
        assertThat(caps.bigClusterOppStepsKhz).hasSize(1)
        assertThat(caps.bigClusterOppStepsKhz.single()).isEqualTo(ayaneoStockCeilingKhz)
        assertThat(caps.bigClusterWritableMaxKhz).isEqualTo(ayaneoStockCeilingKhz)
        // The collapsed value is a REAL OPP the device reports (so MM-2/OPP-snap still hold).
        assertThat(ayaneoFullOpp).contains(caps.bigClusterOppStepsKhz.single())
    }

    @Test
    fun `fully-kernel-writable devices keep a MULTI-step CPU table (cap still steppable, unregressed)`() {
        // ROOT / PServer-live / chmod-direct all have a LIVE sub-stock cap write → the CPU
        // cap lever is real → the full writable table is kept → CPU still caps down normally.
        val fullyWritable = listOf(
            reportWith(
                policies = listOf(
                    policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                    policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
                ),
                privilege = PrivilegeTier.ROOT,
            ),
            reportWith(
                policies = listOf(
                    policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                    policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
                ),
                pserverSysfsLive = true,
            ),
            reportWith(
                policies = listOf(
                    policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                    policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
                ),
                sysfsDirectlyWritable = true,
            ),
        )
        for (report in fullyWritable) {
            val caps = TdpCaps.from(report)
            // Multiple steps up to the KERNEL top — the collapse must NOT touch these paths.
            assertThat(caps.bigClusterOppStepsKhz.size).isGreaterThan(1)
            assertThat(caps.bigClusterOppStepsKhz.last()).isEqualTo(ayaneoKernelTopKhz)
            assertThat(caps.bigClusterOppStepsKhz).contains(ayaneoKernelTopKhz)
        }
    }

    @Test
    fun `crash-fix invariant holds on both paths — no step ever exceeds the writable ceiling`() {
        val ayaneo = TdpCaps.from(
            reportWith(
                policies = listOf(
                    policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                    policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
                ),
                ayaneoBinderLive = true,
            ),
        )
        val root = TdpCaps.from(
            reportWith(
                policies = listOf(
                    policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                    policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
                ),
                privilege = PrivilegeTier.ROOT,
            ),
        )
        // The clamp guarantee stays intact after the collapse: every retained step is at or
        // below the reported writable ceiling — the engine can never target an unwritable OPP.
        ayaneo.bigClusterOppStepsKhz.forEach { assertThat(it).isAtMost(ayaneo.bigClusterWritableMaxKhz) }
        root.bigClusterOppStepsKhz.forEach { assertThat(it).isAtMost(root.bigClusterWritableMaxKhz) }
    }

    @Test
    fun `every write path yields at least one usable step (including the degenerate stock read)`() {
        val reports = listOf(
            // AYANEO normal
            reportWith(
                policies = listOf(
                    policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                    policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
                ),
                ayaneoBinderLive = true,
            ),
            // AYANEO degenerate (stock read far below every OPP)
            reportWith(
                policies = listOf(
                    policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                    policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = 100_000),
                ),
                ayaneoBinderLive = true,
            ),
            // ROOT (unregressed multi-step)
            reportWith(
                policies = listOf(
                    policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                    policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
                ),
                privilege = PrivilegeTier.ROOT,
            ),
        )
        for (report in reports) {
            assertThat(TdpCaps.from(report).bigClusterOppStepsKhz).isNotEmpty()
        }
    }

    // ─── Engine-loop fallthrough: the tighten reaches the WORKING GPU lever ──────────

    /** The AYANEO GPU sysfs root the daemon writes devfreq through. */
    private val ayaneoGpuRoot = "/sys/class/kgsl/kgsl-3d0"

    /** A real, cool, idle-GPU sample so the band controller genuinely wants to tighten. */
    private fun tightenSample() = Telemetry(
        timestampMs = 0L,
        perCoreCpuFreqKhz = List(8) { 1_000_000 },
        perCoreLoadPct = List(8) { 10 },
        cpuLoadSource = CpuLoadReading.Source.DIRECT_PROC_STAT,
        gpuLoadPct = 1, // below every band low → tighten
        gpuFreqHz = 200_000_000L,
        zoneTempsMilliC = listOf(ZoneTemp(0, "cpu", 60_000)), // cool: no thermal pre-empt
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

    @Test
    fun `AYANEO tighten falls through the dead cap lever to a real GPU devfreq write`() {
        // A full AYANEO report WITH a probed GPU devfreq OPP table, so the GPU_DEVFREQ lever
        // is a real actuator (the one AyaneoVendorWriter routes via com_set_performance_gpu).
        val gpuStepsHz = listOf(
            220_000_000L, 350_000_000L, 490_000_000L, 605_000_000L, 700_000_000L, 840_000_000L,
        )
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            ayaneoBinderLive = true,
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
        )
        val caps = TdpCaps.from(report)

        // Precondition: the CPU cap table is collapsed (dead lever), and the GPU devfreq
        // envelope IS present (the working lever).
        assertThat(caps.bigClusterOppStepsKhz).hasSize(1)
        assertThat(caps.gpuDevfreqStepsHz).isNotEmpty()

        // Replay the daemon's own decide → delta loop under a sustained tighten. If the fix
        // works, the cap lever goes quiet (changed=false on the single-step table) and the
        // tighten falls through to GPU_DEVFREQ, so a GPU devfreq-max write op is emitted.
        val gpuMaxTarget = Tunables.gpuMaxFreq(ayaneoGpuRoot).target
        val capTarget = Tunables.cpuMaxFreq(caps.bigPolicyId).target

        var state = ControllerState.INITIAL
        var current = TdpState.STOCK
        val window = ArrayDeque<Telemetry>(5)
        val config = AutoTdpProfileConfig.forGoal(GoalProfile.COOL_QUIET)
        val allOps = mutableListOf<TdpStateTransition.WriteOp>()
        repeat(60) {
            window.addLast(tightenSample())
            if (window.size > 4) window.removeFirst()
            val d = AutoTdpEngine.decide(
                window = window.toList(),
                config = config,
                caps = caps,
                current = current,
                controllerState = state,
                goalOverride = GoalProfile.COOL_QUIET,
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

        // The WORKING lever fired: at least one GPU devfreq-max write was emitted.
        assertThat(allOps.map { it.id.target }).contains(gpuMaxTarget)
        // The final GPU devfreq max actually moved DOWN off the ceiling (real actuation).
        assertThat(current.gpuDevfreqMaxHz).isNotNull()
        assertThat(current.gpuDevfreqMaxHz!!).isLessThan(gpuStepsHz.last())
        // The dead CPU cap lever contributed NOTHING: no sub-stock CPU cap write was emitted
        // (a cap op would only appear if the cap moved off stock — it can't on one step).
        assertThat(allOps.none { it.id.target == capTarget }).isTrue()
    }
}
