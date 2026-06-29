package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import org.junit.Test

/**
 * Tests for [TdpConvergence] — the pure converge-vs-honest-fail logic that fixes the
 * FINDING 1 "AutoTDP engages but the clocks never change / stays stuck" infinite-spin bug.
 *
 * No Android runtime needed (matches [TdpStateTransitionTest]).
 *
 * Device model (SD8Gen2-like):
 *   policy4  = big cluster (cap + min targets), kHz-valued
 *   GPU root = "/sys/class/kgsl/kgsl-3d0", devfreq min/max Hz-valued
 */
class TdpConvergenceTest {

    private val BIG_POLICY = 4
    private val GPU_ROOT = "/sys/class/kgsl/kgsl-3d0"

    private fun capOp(valueKhz: Long) = TdpStateTransition.WriteOp(
        id = Tunables.cpuMaxFreq(BIG_POLICY),
        value = valueKhz.toString(),
        description = "big cap policy$BIG_POLICY → ${valueKhz / 1000} MHz",
    )

    private fun gpuMaxOp(valueHz: Long) = TdpStateTransition.WriteOp(
        id = Tunables.gpuMaxFreq(GPU_ROOT),
        value = valueHz.toString(),
        description = "GPU devfreq max → ${valueHz / 1_000_000} MHz",
    )

    // ── classifyReject: the converge-vs-honest-fail fork ────────────────────────

    @Test
    fun `kernel snapped to a DIFFERENT valid value classifies as CONVERGE`() {
        // readback moved away from the prior value → the kernel accepted a real alternative.
        val kind = TdpConvergence.classifyReject(readbackValue = 2_400_000L, previousValue = 3_187_200L)
        assertThat(kind).isEqualTo(TdpConvergence.RejectKind.CONVERGE)
    }

    @Test
    fun `write with ZERO effect (readback == previous) classifies as HONEST_FAIL`() {
        // The node did not move at all — this is a real failure, NEVER converged-to.
        val kind = TdpConvergence.classifyReject(readbackValue = 3_187_200L, previousValue = 3_187_200L)
        assertThat(kind).isEqualTo(TdpConvergence.RejectKind.HONEST_FAIL)
    }

    @Test
    fun `non-numeric readback (null) classifies as HONEST_FAIL`() {
        val kind = TdpConvergence.classifyReject(readbackValue = null, previousValue = 3_187_200L)
        assertThat(kind).isEqualTo(TdpConvergence.RejectKind.HONEST_FAIL)
    }

    @Test
    fun `readback present but previous unknown still CONVERGEs (kernel did report a value)`() {
        // Pre-read failed (previous == null) but the node reads back a concrete value: that
        // value IS the truth, so converge to it rather than re-fight forever.
        val kind = TdpConvergence.classifyReject(readbackValue = 2_400_000L, previousValue = null)
        assertThat(kind).isEqualTo(TdpConvergence.RejectKind.CONVERGE)
    }

    // ── convergeToReadback: node → TdpState field mapping ───────────────────────

    @Test
    fun `converge advances the big-cluster CAP field (kHz) to the readback`() {
        val base = TdpState(bigClusterCapKhz = 1_800_000)
        val converged = TdpConvergence.convergeToReadback(
            state = base,
            op = capOp(1_800_000),
            readback = 2_400_000L,   // kernel clamped UP to this valid OPP
            bigPolicyId = BIG_POLICY,
            gpuRootPath = GPU_ROOT,
        )
        assertThat(converged).isNotNull()
        assertThat(converged!!.bigClusterCapKhz).isEqualTo(2_400_000)
    }

    @Test
    fun `converge advances the GPU devfreq MAX field (Hz) to the readback`() {
        val base = TdpState(gpuDevfreqMaxHz = 1_100_000_000L)
        val converged = TdpConvergence.convergeToReadback(
            state = base,
            op = gpuMaxOp(1_100_000_000L),
            readback = 1_099_200_000L,
            bigPolicyId = BIG_POLICY,
            gpuRootPath = GPU_ROOT,
        )
        assertThat(converged).isNotNull()
        // Hz value adopted verbatim (NOT truncated to Int — gpuDevfreqMaxHz is a Long).
        assertThat(converged!!.gpuDevfreqMaxHz).isEqualTo(1_099_200_000L)
    }

    @Test
    fun `converge of an untracked node (pwrlevel) returns null — nothing to converge`() {
        val pwrOp = TdpStateTransition.WriteOp(
            id = Tunables.adrenoMaxPowerLevel(GPU_ROOT),
            value = "3",
            description = "GPU max_pwrlevel → 3",
        )
        val converged = TdpConvergence.convergeToReadback(
            state = TdpState(),
            op = pwrOp,
            readback = 0L,
            bigPolicyId = BIG_POLICY,
            gpuRootPath = GPU_ROOT,
        )
        assertThat(converged).isNull()
    }

    // ── The end-to-end stuck-spin scenario, expressed on the pure logic ─────────

    @Test
    fun `snapped cap converges so the next-tick delta is empty (loop settles, no re-fight)`() {
        // Tick 1: engine intends 1_800_000 kHz; kernel snaps to 2_400_000 and Rejects.
        val intended = 1_800_000
        val readback = 2_400_000L
        val priorApplied = TdpState(bigClusterCapKhz = 3_187_200) // stock-ish current
        val op = capOp(intended.toLong())

        // classifyReject says CONVERGE (readback != prior).
        assertThat(
            TdpConvergence.classifyReject(readback, priorApplied.bigClusterCapKhz?.toLong()),
        ).isEqualTo(TdpConvergence.RejectKind.CONVERGE)

        // Apply convergence onto the accumulator (base = prior applied truth).
        val converged = TdpConvergence.convergeToReadback(
            state = priorApplied, op = op, readback = readback,
            bigPolicyId = BIG_POLICY, gpuRootPath = GPU_ROOT,
        )!!
        // currentState now reflects the KERNEL'S truth (2_400_000), not the unreachable intent.
        assertThat(converged.bigClusterCapKhz).isEqualTo(2_400_000)

        // Tick 2: if the engine re-decides to 2_400_000 (the value the kernel will accept),
        // the delta from the converged state is EMPTY — no infinite re-emission of the write.
        val nextOps = TdpStateTransition.delta(
            from = converged,
            to = converged.copy(),           // engine settled on the achievable value
            bigPolicyId = BIG_POLICY,
            gpuRootPath = GPU_ROOT,
        )
        assertThat(nextOps).isEmpty()
    }

    @Test
    fun `zero-effect write is HONEST_FAIL and is NOT converged (honesty invariant)`() {
        // Node never moved (readback == previous == stock). The controller must NOT adopt the
        // stock value as if it wanted it — classifyReject returns HONEST_FAIL, and the apply
        // loop leaves currentState at the prior applied truth (the accumulator base), so a
        // write that had NO effect is never silently treated as success.
        val stock = 3_187_200L
        assertThat(TdpConvergence.classifyReject(stock, stock))
            .isEqualTo(TdpConvergence.RejectKind.HONEST_FAIL)
    }
}
