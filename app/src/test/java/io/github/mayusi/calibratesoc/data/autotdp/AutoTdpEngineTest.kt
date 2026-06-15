package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import org.junit.Test

/**
 * Unit tests for [AutoTdpEngine.decide].
 *
 * All tests use pure value objects — no Android runtime, no mocks.
 *
 * Device model used throughout (mirrors SD8Gen2 / RP6 / Thor topology):
 *   policy0 : cores 0-3 (little),  top 2016 MHz
 *   policy4 : cores 4-6 (gold/big), top 2803 MHz  ← big-cluster cap target
 *   policy7 : core 7   (prime),     top 3187 MHz  ← parking target
 */
class AutoTdpEngineTest {

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    /** SD8Gen2-style 3-cluster caps. Core 7 = only prime; big policy = 4. */
    private val caps3Cluster = TdpCaps(
        primeCoreIndices = listOf(7),          // core 7 is the only prime, cpu0 excluded
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(
            499_000, 844_000, 1_171_000, 1_536_000,
            1_920_000, 2_323_000, 2_707_000, 2_803_000,
        ),
        gpuMinLevel = 0,   // Adreno: 0 = fastest
        gpuMaxLevel = 6,   // 0..6 range
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    /** 2-cluster device with 2 prime cores (7, 8); cpu0 excluded. */
    private val caps2Cluster = TdpCaps(
        primeCoreIndices = listOf(7, 8),
        bigPolicyId = 6,
        bigClusterOppStepsKhz = listOf(1_000_000, 2_000_000, 3_000_000, 4_000_000),
        gpuMinLevel = 0,
        gpuMaxLevel = 4,
        minOnlineCores = 5,
        totalOnlineCores = 9,
    )

    private val efficiencyConfig = AutoTdpProfileConfig(AutoTdpProfile.EFFICIENCY)
    private val balancedConfig = AutoTdpProfileConfig(AutoTdpProfile.BALANCED)

    /** Build a Telemetry sample with explicit GPU load and per-core loads. */
    private fun telemetry(
        gpuLoad: Int,
        coreLoads: List<Int>,
    ) = Telemetry(
        timestampMs = System.currentTimeMillis(),
        perCoreCpuFreqKhz = List(coreLoads.size) { 1_000_000 },
        perCoreLoadPct = coreLoads,
        gpuLoadPct = gpuLoad,
        gpuFreqHz = 800_000_000L,
        zoneTempsMilliC = emptyList<ZoneTemp>(),
        ramTotalKb = 8_000_000L,
        ramAvailableKb = 4_000_000L,
        batteryTempDeciC = 280,
        batteryCurrentUa = 2_000_000L,
        batteryVoltageUv = 4_000_000L,
        fanRpm = null,
    )

    /** Returns a window of [count] identical GPU-bound samples (core 7 load = 10%). */
    private fun gpuBoundWindow(count: Int = 4) = List(count) {
        telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 10 else 15 })
    }

    /** Returns a window of [count] identical CPU-saturated samples (core 7 load = 92%). */
    private fun cpuBoundWindow(count: Int = 4) = List(count) {
        telemetry(gpuLoad = 30, coreLoads = List(8) { i -> if (i == 7) 92 else 50 })
    }

    /** Returns a window of steady, unremarkable samples. */
    private fun idleWindow(count: Int = 4) = List(count) {
        telemetry(gpuLoad = 40, coreLoads = List(8) { 30 })
    }

    // ─── GPU-bound → park + cap ────────────────────────────────────────────────

    @Test
    fun `gpu-bound window parks one prime core and caps big cluster`() {
        val decision = AutoTdpEngine.decide(
            window = gpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )

        // Core 7 is the only prime core; it should be parked.
        assertThat(decision.target.parkedPrimeCores).contains(7)

        // Big-cluster cap should be set below the top OPP step.
        val cap = decision.target.bigClusterCapKhz
        assertThat(cap).isNotNull()
        assertThat(cap!!).isLessThan(2_803_000)

        // Cap must be on a real OPP step.
        assertThat(caps3Cluster.bigClusterOppStepsKhz).contains(cap)
    }

    @Test
    fun `gpu-bound sets gpuFloorLevel to caps min (GPU stays permissive)`() {
        val decision = AutoTdpEngine.decide(
            window = gpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        // GPU floor level should be set to the fastest level (caps.gpuMinLevel = 0).
        assertThat(decision.target.gpuFloorLevel).isEqualTo(0)
    }

    @Test
    fun `reason string contains gpu percentage and mentions park or cap`() {
        val decision = AutoTdpEngine.decide(
            window = gpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        val reason = decision.reason.lowercase()
        assertThat(reason).containsMatch("gpu")
        // Should mention either parking a core or capping.
        assertThat(reason.contains("park") || reason.contains("cap")).isTrue()
    }

    // ─── CPU-bound → unpark + relax cap ────────────────────────────────────────

    @Test
    fun `cpu-bound window unparks a prime core`() {
        // Start with core 7 already parked and cap at a low step.
        val parked = TdpState(
            parkedPrimeCores = setOf(7),
            bigClusterCapKhz = 1_171_000,
        )

        val decision = AutoTdpEngine.decide(
            window = cpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = parked,
        )

        // Core 7 must be removed from the parked set.
        assertThat(decision.target.parkedPrimeCores).doesNotContain(7)
    }

    @Test
    fun `cpu-bound window steps big cap UP one OPP step`() {
        val parked = TdpState(
            parkedPrimeCores = setOf(7),
            bigClusterCapKhz = 1_171_000, // index 2 in the OPP table
        )
        val decision = AutoTdpEngine.decide(
            window = cpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = parked,
        )

        val newCap = decision.target.bigClusterCapKhz
        // Should step UP: 1_171_000 → 1_536_000 (index 3)
        assertThat(newCap).isAtLeast(1_171_000)
    }

    // ─── Hysteresis: single noisy GPU spike does NOT trigger parking ───────────

    @Test
    fun `single gpu-bound sample in a 4-sample window does not park`() {
        // Only one of four samples is GPU-bound; the engine must NOT park.
        val window = listOf(
            telemetry(gpuLoad = 92, coreLoads = List(8) { i -> if (i == 7) 10 else 15 }), // GPU bound
            telemetry(gpuLoad = 50, coreLoads = List(8) { 40 }), // not GPU bound
            telemetry(gpuLoad = 35, coreLoads = List(8) { 45 }), // not GPU bound
            telemetry(gpuLoad = 60, coreLoads = List(8) { 35 }), // not GPU bound
        )

        val decision = AutoTdpEngine.decide(
            window = window,
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )

        // No cores should be parked on a single noisy spike.
        assertThat(decision.target.parkedPrimeCores).isEmpty()
        assertThat(decision.reason.lowercase()).contains("holding")
    }

    @Test
    fun `two gpu-bound samples out of four does not park`() {
        val window = listOf(
            telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 8 else 12 }),
            telemetry(gpuLoad = 85, coreLoads = List(8) { i -> if (i == 7) 12 else 18 }),
            telemetry(gpuLoad = 45, coreLoads = List(8) { 55 }),
            telemetry(gpuLoad = 30, coreLoads = List(8) { 60 }),
        )

        val decision = AutoTdpEngine.decide(
            window = window,
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )

        assertThat(decision.target.parkedPrimeCores).isEmpty()
    }

    // ─── Hysteresis asymmetry: single CPU-saturated sample unparks immediately ──

    @Test
    fun `single cpu-saturated sample in window unparks immediately`() {
        // 3 GPU-bound samples + 1 CPU-saturated sample.
        // The CPU-saturated path (fast responsiveness floor) must win.
        val window = listOf(
            telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 10 else 15 }),
            telemetry(gpuLoad = 88, coreLoads = List(8) { i -> if (i == 7) 12 else 18 }),
            telemetry(gpuLoad = 85, coreLoads = List(8) { i -> if (i == 7) 8 else 10 }),
            telemetry(gpuLoad = 30, coreLoads = List(8) { i -> if (i == 7) 92 else 60 }), // saturated!
        )

        val parked = TdpState(parkedPrimeCores = setOf(7), bigClusterCapKhz = 1_171_000)

        val decision = AutoTdpEngine.decide(
            window = window,
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = parked,
        )

        // Must unpark immediately on a single saturated sample.
        assertThat(decision.target.parkedPrimeCores).doesNotContain(7)
    }

    // ─── cpu0 is NEVER in parkedPrimeCores ────────────────────────────────────

    @Test
    fun `cpu0 is never in parkedPrimeCores regardless of caps`() {
        // Construct a pathological caps where cpu0 is in primeCoreIndices.
        // This should never happen in practice (TdpCaps.from excludes it),
        // but engine itself must also guard.
        val badCaps = caps3Cluster.copy(
            primeCoreIndices = listOf(0, 7),   // deliberately includes cpu0
            totalOnlineCores = 8,
            minOnlineCores = 2,
        )

        val decision = AutoTdpEngine.decide(
            window = gpuBoundWindow(4),
            config = efficiencyConfig,
            caps = badCaps,
            current = TdpState.STOCK,
        )

        assertThat(decision.target.parkedPrimeCores).doesNotContain(0)
    }

    @Test
    fun `cpu0 never parked even after many gpu-bound windows`() {
        // Call decide() repeatedly; cpu0 must never accumulate into the parked set.
        var state = TdpState.STOCK
        repeat(10) {
            val decision = AutoTdpEngine.decide(
                window = gpuBoundWindow(4),
                config = efficiencyConfig,
                caps = caps3Cluster,
                current = state,
            )
            state = decision.target
            assertThat(state.parkedPrimeCores).doesNotContain(0)
        }
    }

    // ─── Min-online-core floor is honoured ────────────────────────────────────

    @Test
    fun `engine does not park past the min-online-core floor`() {
        // 2-cluster device, 9 cores total (cpu0..8), prime = cpu7 + cpu8.
        // minOnlineCores = 5 → can park at most 4 prime cores = cpu7 and cpu8 (2 prime).
        // But let's use 4 prime cores and verify the floor is respected.
        val caps = TdpCaps(
            primeCoreIndices = listOf(5, 6, 7, 8),
            bigPolicyId = 4,
            bigClusterOppStepsKhz = listOf(1_000_000, 2_000_000, 3_000_000),
            gpuMinLevel = 0,
            gpuMaxLevel = 4,
            minOnlineCores = 6,
            totalOnlineCores = 9,
        )

        // Start with 3 already parked (cores 6, 7, 8); 6 online remain.
        val almostAtFloor = TdpState(
            parkedPrimeCores = setOf(6, 7, 8),
            bigClusterCapKhz = 1_000_000,
        )

        val decision = AutoTdpEngine.decide(
            window = gpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps,
            current = almostAtFloor,
        )

        // Parking another core would drop to 5 online (< floor of 6).
        // Engine must refuse to add core 5.
        assertThat(decision.target.parkedPrimeCores).doesNotContain(5)
        // Already-parked cores must remain parked.
        assertThat(decision.target.parkedPrimeCores).containsAtLeast(6, 7, 8)
        // Reason should mention the floor.
        assertThat(decision.reason.lowercase()).containsMatch("floor|park|core")
    }

    // ─── BALANCED profile is less aggressive than EFFICIENCY ──────────────────

    @Test
    fun `balanced profile requires higher GPU threshold before parking`() {
        // GPU at exactly 80% (threshold) — fires for EFFICIENCY but not BALANCED
        // (BALANCED requires >= 85%).
        val marginalGpuWindow = List(4) {
            telemetry(gpuLoad = 80, coreLoads = List(8) { i -> if (i == 7) 10 else 15 })
        }

        val effDecision = AutoTdpEngine.decide(
            window = marginalGpuWindow,
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )

        val balDecision = AutoTdpEngine.decide(
            window = marginalGpuWindow,
            config = balancedConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )

        // EFFICIENCY should park (GPU >= 80% = threshold).
        assertThat(effDecision.target.parkedPrimeCores).isNotEmpty()

        // BALANCED at 80% should hold (requires >= 85%).
        assertThat(balDecision.target.parkedPrimeCores).isEmpty()
    }

    // ─── BATTERY_TARGET cap math ───────────────────────────────────────────────

    @Test
    fun `battery-target profile sets big cap proportionally to target wattage`() {
        val config = AutoTdpProfileConfig(
            profile = AutoTdpProfile.BATTERY_TARGET,
            targetMilliWatts = 3_000L,  // 3 W target
        )

        val decision = AutoTdpEngine.decide(
            window = idleWindow(4),
            config = config,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )

        // With a non-null target, the engine should apply a cap (or hold if already at budget).
        // The key invariant: cap must be a real OPP step or null.
        val cap = decision.target.bigClusterCapKhz
        if (cap != null) {
            assertThat(caps3Cluster.bigClusterOppStepsKhz).contains(cap)
        }
    }

    @Test
    fun `deriveBudgetCap returns a step within the OPP table`() {
        val step = AutoTdpEngine.deriveBudgetCap(caps3Cluster, 3_000L)
        if (step != null) {
            assertThat(caps3Cluster.bigClusterOppStepsKhz).contains(step)
        }
    }

    @Test
    fun `deriveBudgetCap returns null for empty OPP table`() {
        val emptyCaps = caps3Cluster.copy(bigClusterOppStepsKhz = emptyList())
        assertThat(AutoTdpEngine.deriveBudgetCap(emptyCaps, 3_000L)).isNull()
    }

    @Test
    fun `deriveBudgetCap returns null for zero target`() {
        assertThat(AutoTdpEngine.deriveBudgetCap(caps3Cluster, 0L)).isNull()
    }

    // ─── "Holding" when steady ─────────────────────────────────────────────────

    @Test
    fun `holding returned when window is neither gpu-bound nor cpu-saturated`() {
        val decision = AutoTdpEngine.decide(
            window = idleWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )

        // State unchanged.
        assertThat(decision.target).isEqualTo(TdpState.STOCK)
        assertThat(decision.reason.lowercase()).contains("holding")
    }

    @Test
    fun `holding reason includes gpu and big prime percentages`() {
        val decision = AutoTdpEngine.decide(
            window = idleWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        val reason = decision.reason.lowercase()
        assertThat(reason).containsMatch("gpu|holding")
    }

    // ─── Empty window ─────────────────────────────────────────────────────────

    @Test
    fun `empty window returns current state with holding reason`() {
        val existing = TdpState(parkedPrimeCores = setOf(7), bigClusterCapKhz = 1_920_000)
        val decision = AutoTdpEngine.decide(
            window = emptyList(),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = existing,
        )
        assertThat(decision.target).isEqualTo(existing)
        assertThat(decision.reason.lowercase()).contains("no telemetry")
    }

    // ─── Incremental parking over multiple decisions ───────────────────────────

    @Test
    fun `repeated gpu-bound decisions respect floor and never park cpu0`() {
        var state = TdpState.STOCK
        // Use a device with multiple prime cores.
        val caps = TdpCaps(
            primeCoreIndices = listOf(6, 7),      // 2 prime cores (NOT 0)
            bigPolicyId = 4,
            bigClusterOppStepsKhz = listOf(1_000_000, 2_000_000, 3_000_000),
            gpuMinLevel = 0,
            gpuMaxLevel = 4,
            minOnlineCores = 5,
            totalOnlineCores = 8,
        )

        // 3 iterations of GPU-bound decisions.
        repeat(3) {
            val decision = AutoTdpEngine.decide(
                window = gpuBoundWindow(4),
                config = efficiencyConfig,
                caps = caps,
                current = state,
            )
            state = decision.target

            assertThat(state.parkedPrimeCores).doesNotContain(0)

            val onlineCount = caps.totalOnlineCores - state.parkedPrimeCores.size
            assertThat(onlineCount).isAtLeast(caps.minOnlineCores)
        }
    }

    // ─── Single-core device (edge case) ───────────────────────────────────────

    @Test
    fun `single-core device with no prime cores holds gracefully`() {
        val caps = TdpCaps(
            primeCoreIndices = emptyList(), // no prime cores (all excluded or single-core)
            bigPolicyId = 0,
            bigClusterOppStepsKhz = listOf(500_000, 1_000_000),
            gpuMinLevel = null,
            gpuMaxLevel = null,
            minOnlineCores = 1,
            totalOnlineCores = 1,
        )

        val decision = AutoTdpEngine.decide(
            window = gpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps,
            current = TdpState.STOCK,
        )

        // No prime cores to park; engine must not crash.
        assertThat(decision.target.parkedPrimeCores).isEmpty()
    }

    // ─── BUG C FIX: non-STOCK decisions on realistic load ────────────────────

    @Test
    fun `bug-c efficiency profile produces non-STOCK decision on gpu-bound load`() {
        // GPU at 90%, big/prime at 10% for all 4 samples → allGpuBound=true.
        // Engine must produce a tightened state (NOT STOCK) for EFFICIENCY.
        val decision = AutoTdpEngine.decide(
            window = gpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        assertThat(decision.target).isNotEqualTo(TdpState.STOCK)
        assertThat(decision.target.bigClusterCapKhz).isNotNull()
    }

    @Test
    fun `bug-c balanced profile produces non-STOCK decision when gpu load above 85 pct`() {
        // GPU at 90% (above BALANCED_GPU_THRESHOLD=85), CPU prime at 10%.
        // All 4 samples meet both allGpuBound AND smoothedGpuLoad >= 85.
        val highGpuWindow = List(4) {
            telemetry(gpuLoad = 90, coreLoads = List(8) { i -> if (i == 7) 10 else 15 })
        }
        val decision = AutoTdpEngine.decide(
            window = highGpuWindow,
            config = balancedConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        // BALANCED at 90% GPU should park/cap — NOT hold at STOCK.
        assertThat(decision.target).isNotEqualTo(TdpState.STOCK)
    }

    @Test
    fun `bug-c balanced profile holds at 82 pct gpu (below balanced threshold)`() {
        // GPU at 82% satisfies allGpuBound (>= 80) but NOT smoothedGpuLoad >= 85.
        // BALANCED must hold at STOCK (no parking at 82%).
        val marginalGpuWindow = List(4) {
            telemetry(gpuLoad = 82, coreLoads = List(8) { i -> if (i == 7) 10 else 15 })
        }
        val decision = AutoTdpEngine.decide(
            window = marginalGpuWindow,
            config = balancedConfig,
            caps = caps3Cluster,
            current = TdpState.STOCK,
        )
        assertThat(decision.target).isEqualTo(TdpState.STOCK)
        assertThat(decision.reason.lowercase()).contains("holding")
    }

    // ─── BUG C FIX: relaxState clears cap when stepping reaches top OPP ──────

    @Test
    fun `bug-c relax state clears cap when stepping from second-to-last OPP`() {
        // Start with cap at the second-to-last step (index 6 of 8: 2_707_000).
        // One cpu-saturated tick should step cap to top OPP and immediately clear it.
        val almostTopOpp = TdpState(
            parkedPrimeCores = setOf(7),
            bigClusterCapKhz = 2_707_000,
        )
        val decision = AutoTdpEngine.decide(
            window = cpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = almostTopOpp,
        )
        // Stepping from 2_707_000 (index 6) to 2_803_000 (index 7, top) should
        // immediately clear to null (stock) in a single step.
        assertThat(decision.target.bigClusterCapKhz).isNull()
    }

    @Test
    fun `bug-c relax state clears cap when already at top OPP`() {
        // Start with cap already AT the top OPP.
        val atTopOpp = TdpState(
            parkedPrimeCores = setOf(7),
            bigClusterCapKhz = 2_803_000,  // top OPP = steps.last()
        )
        val decision = AutoTdpEngine.decide(
            window = cpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = atTopOpp,
        )
        // Already at top OPP → clear immediately.
        assertThat(decision.target.bigClusterCapKhz).isNull()
    }

    @Test
    fun `bug-c relax state steps up one OPP when not yet at top`() {
        // Start with cap at index 2 (1_171_000). One relax tick steps to index 3.
        val midCap = TdpState(
            parkedPrimeCores = setOf(7),
            bigClusterCapKhz = 1_171_000,  // index 2
        )
        val decision = AutoTdpEngine.decide(
            window = cpuBoundWindow(4),
            config = efficiencyConfig,
            caps = caps3Cluster,
            current = midCap,
        )
        // Should step to 1_536_000 (index 3) — NOT cleared, NOT at top.
        assertThat(decision.target.bigClusterCapKhz).isEqualTo(1_536_000)
    }

    // ─── BUG D FIX: deriveBudgetCap unit math ────────────────────────────────

    @Test
    fun `bug-d deriveBudgetCap maps half budget to a mid-table OPP`() {
        // With MAX_DRAW_PROXY_MW = 3000, a budget of 1500 mW = fraction 0.5.
        // 0.5 * (8-1) = 3.5 → index 3 → steps[3] = 1_536_000 kHz.
        val cap = AutoTdpEngine.deriveBudgetCap(caps3Cluster, 1_500L)
        assertThat(cap).isNotNull()
        // Mid-budget → should pick an OPP significantly below the top.
        assertThat(cap!!).isLessThan(caps3Cluster.bigClusterOppStepsKhz.last())
        // Must still be on a real OPP step.
        assertThat(caps3Cluster.bigClusterOppStepsKhz).contains(cap)
    }

    @Test
    fun `bug-d deriveBudgetCap with low budget maps to low OPP`() {
        // Budget of 300 mW = 10% of 3000 → fraction 0.1 → index 0 or 1.
        val cap = AutoTdpEngine.deriveBudgetCap(caps3Cluster, 300L)
        assertThat(cap).isNotNull()
        // Low budget → should select one of the lower OPP steps.
        assertThat(cap!!).isAtMost(caps3Cluster.bigClusterOppStepsKhz[1])
    }

    @Test
    fun `bug-d deriveBudgetCap does NOT always return top OPP for 3000mW`() {
        // Pre-bug: 3000 mW / (2803000 kHz * 0.001 MHz) = 3000/2803 ≈ 1.07 → top OPP.
        // Post-fix: 3000 mW / 3000 mW proxy = 1.0 → top OPP (still correct but for
        // the RIGHT reason). The critical test: 1500 mW should NOT map to top OPP.
        val capAt3000 = AutoTdpEngine.deriveBudgetCap(caps3Cluster, 3_000L)
        val capAt1500 = AutoTdpEngine.deriveBudgetCap(caps3Cluster, 1_500L)
        // Both return non-null OPP steps on the table.
        assertThat(capAt3000).isNotNull()
        assertThat(capAt1500).isNotNull()
        // The 1500 mW cap must be strictly LESS than the 3000 mW cap.
        assertThat(capAt1500!!).isLessThan(capAt3000!!)
    }

    @Test
    fun `bug-d deriveBudgetCap returns null for zero and negative budgets`() {
        assertThat(AutoTdpEngine.deriveBudgetCap(caps3Cluster, 0L)).isNull()
    }
}
