package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import org.junit.Test

/**
 * Tests for [TdpStateTransition.delta] — the pure function that converts a
 * (from, to) TdpState pair into a list of kernel write ops.
 *
 * No Android runtime needed. All inputs are plain value objects.
 *
 * Device model (SD8Gen2-like, matches AutoTdpEngineTest):
 *   policy4  = big cluster (cap target)
 *   core 7   = single prime core (parking target)
 *   GPU root = "/sys/class/kgsl/kgsl-3d0"
 */
class TdpStateTransitionTest {

    private val BIG_POLICY = 4
    private val GPU_ROOT = "/sys/class/kgsl/kgsl-3d0"

    // ── Parking ───────────────────────────────────────────────────────────────

    @Test
    fun `parking a prime core emits cpu_online=0 write`() {
        val from = TdpState.STOCK
        val to = TdpState(parkedPrimeCores = setOf(7))

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)

        val parkOp = ops.single { it.description.contains("park") }
        assertThat(parkOp.id).isEqualTo(Tunables.cpuOnline(7))
        assertThat(parkOp.value).isEqualTo("0")
    }

    @Test
    fun `parking cpu7 then cpu6 produces writes in descending index order`() {
        val from = TdpState.STOCK
        val to = TdpState(parkedPrimeCores = setOf(6, 7))

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)
        val parkOps = ops.filter { it.value == "0" }

        // Highest index first
        assertThat(parkOps.map { it.id }).containsExactly(
            Tunables.cpuOnline(7),
            Tunables.cpuOnline(6),
        ).inOrder()
    }

    @Test
    fun `incrementally parking one more core when one is already parked`() {
        val from = TdpState(parkedPrimeCores = setOf(7))
        val to = TdpState(parkedPrimeCores = setOf(6, 7))

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)
        // Only cpu6 is new — cpu7 was already parked so no redundant write
        val writeIds = ops.map { it.id }
        assertThat(writeIds).contains(Tunables.cpuOnline(6))
        assertThat(writeIds).doesNotContain(Tunables.cpuOnline(7))
        assertThat(ops.first { it.id == Tunables.cpuOnline(6) }.value).isEqualTo("0")
    }

    // ── Unparking ─────────────────────────────────────────────────────────────

    @Test
    fun `unparking a core emits cpu_online=1 write`() {
        val from = TdpState(parkedPrimeCores = setOf(7))
        val to = TdpState.STOCK

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)

        val unpark = ops.single { it.description.contains("unpark") }
        assertThat(unpark.id).isEqualTo(Tunables.cpuOnline(7))
        assertThat(unpark.value).isEqualTo("1")
    }

    @Test
    fun `unparking produces online=1 writes in ascending index order`() {
        val from = TdpState(parkedPrimeCores = setOf(6, 7))
        val to = TdpState.STOCK

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)
        val unparkOps = ops.filter { it.value == "1" }

        assertThat(unparkOps.map { it.id }).containsExactly(
            Tunables.cpuOnline(6),
            Tunables.cpuOnline(7),
        ).inOrder()
    }

    // ── cpu0 safety guard ─────────────────────────────────────────────────────

    @Test(expected = IllegalStateException::class)
    fun `cpu0 in parkedPrimeCores throws IllegalStateException`() {
        val from = TdpState.STOCK
        // Fabricate a state with cpu0 parked — this must never come from the engine,
        // but the guard should catch it if it somehow does.
        val to = TdpState(parkedPrimeCores = setOf(0))

        // Must throw.
        TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)
    }

    // ── Frequency cap ─────────────────────────────────────────────────────────

    @Test
    fun `applying a big-cluster cap emits scaling_max_freq write`() {
        val from = TdpState.STOCK
        val to = TdpState(bigClusterCapKhz = 1_804_000)

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)

        val capOp = ops.single { it.description.contains("big cap") }
        assertThat(capOp.id).isEqualTo(Tunables.cpuMaxFreq(BIG_POLICY))
        assertThat(capOp.value).isEqualTo("1804000")
    }

    @Test
    fun `removing the cap (null) does not emit a freq write`() {
        // Null cap means "let TunableWriter revert on stop" — nothing to write now.
        val from = TdpState(bigClusterCapKhz = 1_804_000)
        val to = TdpState.STOCK // bigClusterCapKhz = null

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)

        val freqOps = ops.filter { it.id == Tunables.cpuMaxFreq(BIG_POLICY) }
        assertThat(freqOps).isEmpty()
    }

    @Test
    fun `no write emitted when cap is unchanged`() {
        val state = TdpState(bigClusterCapKhz = 1_804_000)
        val ops = TdpStateTransition.delta(state, state, BIG_POLICY, GPU_ROOT)

        assertThat(ops).isEmpty()
    }

    // ── GPU floor level ───────────────────────────────────────────────────────

    @Test
    fun `setting gpu floor level emits max_pwrlevel write`() {
        val from = TdpState.STOCK
        val to = TdpState(gpuFloorLevel = 0)

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)

        val gpuOp = ops.single { it.description.contains("GPU max_pwrlevel") }
        assertThat(gpuOp.id.target).isEqualTo("$GPU_ROOT/max_pwrlevel")
        assertThat(gpuOp.value).isEqualTo("0")
    }

    @Test
    fun `no gpu write when gpuRootPath is null`() {
        val from = TdpState.STOCK
        val to = TdpState(gpuFloorLevel = 0)

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, gpuRootPath = null)

        val gpuOps = ops.filter { it.description.contains("GPU") }
        assertThat(gpuOps).isEmpty()
    }

    // ── Governor overrides ────────────────────────────────────────────────────

    @Test
    fun `governor override emits scaling_governor write`() {
        val from = TdpState.STOCK
        val to = TdpState(governorOverrides = mapOf(0 to "conservative"))

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)

        val govOp = ops.single { it.description.contains("governor") }
        assertThat(govOp.id).isEqualTo(Tunables.cpuGovernor(0))
        assertThat(govOp.value).isEqualTo("conservative")
    }

    @Test
    fun `unchanged governor produces no write`() {
        val state = TdpState(governorOverrides = mapOf(0 to "schedutil"))
        val ops = TdpStateTransition.delta(state, state, BIG_POLICY, GPU_ROOT)
        assertThat(ops).isEmpty()
    }

    // ── Ordering guarantee ────────────────────────────────────────────────────

    @Test
    fun `unpark writes come AFTER cap and park writes`() {
        // Transition: park cpu6, cap big cluster, AND unpark cpu7 simultaneously.
        val from = TdpState(parkedPrimeCores = setOf(7), bigClusterCapKhz = null)
        val to = TdpState(parkedPrimeCores = setOf(6), bigClusterCapKhz = 1_804_000)

        val ops = TdpStateTransition.delta(from, to, BIG_POLICY, GPU_ROOT)

        val descriptions = ops.map { it.description }
        val parkIdx  = descriptions.indexOfFirst { it.contains("park cpu6") }
        val capIdx   = descriptions.indexOfFirst { it.contains("big cap") }
        val unparkIdx = descriptions.indexOfFirst { it.contains("unpark cpu7") }

        // Park and cap must precede unpark.
        assertThat(parkIdx).isLessThan(unparkIdx)
        assertThat(capIdx).isLessThan(unparkIdx)
    }

    // ── No-op ─────────────────────────────────────────────────────────────────

    @Test
    fun `identical from and to produces empty op list`() {
        val state = TdpState(
            parkedPrimeCores = setOf(7),
            bigClusterCapKhz = 1_804_000,
            gpuFloorLevel = 0,
            governorOverrides = mapOf(0 to "schedutil"),
        )
        val ops = TdpStateTransition.delta(state, state, BIG_POLICY, GPU_ROOT)
        assertThat(ops).isEmpty()
    }

    @Test
    fun `stock-to-stock produces empty op list`() {
        val ops = TdpStateTransition.delta(TdpState.STOCK, TdpState.STOCK, BIG_POLICY, GPU_ROOT)
        assertThat(ops).isEmpty()
    }
}
