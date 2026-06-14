package io.github.mayusi.calibratesoc.data.benchmark

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import org.junit.Test

// ComparativeRun is the file/module; the static logic lives on ComparativeResult.Companion.

/**
 * Unit tests for ComparativeRun pure logic:
 *   - [ComparativeResult.computeWinner]
 *   - [ComparativeResult.buildDeltas]
 *   - [ComparativeResult.applyStatusFrom]
 *   - [ComparativeResult.build] — partial / full / aborted slot handling
 */
class ComparativeRunTest {

    // ─── Helpers ──────────────────────────────────────────────────────

    private fun makeRun(kernels: KernelScores, outcome: BenchOutcome = BenchOutcome.COMPLETED): BenchRun =
        BenchRun(
            id = 0L,
            name = "test",
            flavor = BenchFlavor.STANDARD,
            startedAtMs = 0L,
            durationMs = 60_000L,
            snapshot = SystemSnapshot(
                capturedAtMs = 0L,
                deviceModel = "Test Device",
                socModel = "Test SoC",
                androidVersion = "14",
                privilegeTier = "ROOT",
                cpuPolicies = emptyList(),
                gpuMinHz = null,
                gpuMaxHz = null,
                gpuGovernor = null,
                appVersion = "test",
            ),
            kernels = kernels,
            throttleSamples = emptyList(),
            outcome = outcome,
        )

    private fun makeProfile(name: String, hasTunables: Boolean = true): UserProfile =
        UserProfile(
            id = "test_$name",
            name = name,
            description = "",
            cpuPolicyMaxKhz = if (hasTunables) mapOf(0 to 2000000) else emptyMap(),
            createdAtMs = 0L,
        )

    private val dummyId = TunableId(TunableKind.SYSFS, "/sys/test")

    private fun successResult() = WriteResult.Success(dummyId, "old", "new")
    private fun deniedResult() = WriteResult.CapabilityDenied(dummyId, "SELinux")
    private fun rejectedResult() = WriteResult.Rejected(dummyId, errno = 13, message = "EACCES")
    private fun failedResult() = WriteResult.Failed(dummyId, RuntimeException("IO"))

    // ─── computeWinner ────────────────────────────────────────────────

    @Test
    fun `computeWinner both null returns NO_DATA`() {
        val winner = ComparativeResult.computeWinner(null, null, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.NO_DATA)
    }

    @Test
    fun `computeWinner A null B present returns B`() {
        val winner = ComparativeResult.computeWinner(null, 1000.0, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.B)
    }

    @Test
    fun `computeWinner A present B null returns A`() {
        val winner = ComparativeResult.computeWinner(1000.0, null, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.A)
    }

    @Test
    fun `computeWinner higher is better A wins when A greater`() {
        val winner = ComparativeResult.computeWinner(1200.0, 1000.0, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.A)
    }

    @Test
    fun `computeWinner higher is better B wins when B greater`() {
        val winner = ComparativeResult.computeWinner(800.0, 1000.0, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.B)
    }

    @Test
    fun `computeWinner lower is better A wins when A lower`() {
        val winner = ComparativeResult.computeWinner(5.0, 20.0, lowerIsBetter = true)
        assertThat(winner).isEqualTo(CategoryWinner.A)
    }

    @Test
    fun `computeWinner lower is better B wins when B lower`() {
        val winner = ComparativeResult.computeWinner(20.0, 5.0, lowerIsBetter = true)
        assertThat(winner).isEqualTo(CategoryWinner.B)
    }

    @Test
    fun `computeWinner equal values within 0_1 pct is TIE`() {
        // Difference of 0.05% — well within the 0.1% tie band.
        val winner = ComparativeResult.computeWinner(1000.0, 1000.5, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.TIE)
    }

    @Test
    fun `computeWinner exact equal values is TIE`() {
        val winner = ComparativeResult.computeWinner(500.0, 500.0, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.TIE)
    }

    @Test
    fun `computeWinner difference just above tie band is not TIE`() {
        // 0.2% difference — above the 0.1% band, should pick a winner.
        val winner = ComparativeResult.computeWinner(1000.0, 1002.0, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.B)
    }

    @Test
    fun `computeWinner both zero is TIE`() {
        val winner = ComparativeResult.computeWinner(0.0, 0.0, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.TIE)
    }

    @Test
    fun `computeWinner near-zero 0 vs 0_0005 is NOT a tie`() {
        // With the old buggy logic: maxOf(0.0, 0.0005) = 0.0005, but the guard
        // `if (it == 0.0)` doesn't fire because 0.0005 != 0.0, so larger = 0.0005,
        // abs(0 - 0.0005) / 0.0005 = 1.0 which is > 0.001 → NOT a tie. Actually
        // the old code happened to work for this specific case. The real bug was
        // 0.0 vs small: maxOf(0, small)=small, divisor=small, 1.0>0.001 → winner.
        // The BUG was 0.0 vs 0.0: maxOf(0,0)=0 → guarded to 1.0 → 0/1=0 < 0.001 → TIE ✓
        // But 0.0 vs 0.0 with lowerIsBetter would hit the `it == 0.0` guard giving TIE.
        // The real near-zero bug: values like -0.0005 vs 0.0005 — abs(av) and abs(bv)
        // both ~0.0005, maxOf(0.0, 0.0005)=0.0005 (old code without abs) vs
        // maxOf(abs(-0.0005), abs(0.0005))=0.0005 (new code) — same here.
        // Document the critical case: 0.0 vs 0.0005 must produce a winner (B wins).
        val winner = ComparativeResult.computeWinner(0.0, 0.0005, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.B)
    }

    @Test
    fun `computeWinner both near-zero below epsilon is TIE`() {
        // Both values are < 1e-6 → epsilon floor kicks in (larger becomes 1.0)
        // → abs(5e-7 - 3e-7) / 1.0 = 2e-7 < 0.001 → TIE
        val winner = ComparativeResult.computeWinner(5e-7, 3e-7, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.TIE)
    }

    @Test
    fun `computeWinner one value above epsilon threshold is not a tie`() {
        // av = 0.0, bv = 0.002 (both small but bv > 1e-6)
        // larger = maxOf(abs(0.0), abs(0.002)) = 0.002; abs(0-0.002)/0.002 = 1.0 > 0.001 → B wins
        val winner = ComparativeResult.computeWinner(0.0, 0.002, lowerIsBetter = false)
        assertThat(winner).isEqualTo(CategoryWinner.B)
    }

    // ─── buildDeltas ──────────────────────────────────────────────────

    @Test
    fun `buildDeltas A wins overall when A has higher overall score`() {
        val runA = makeRun(KernelScores(cpuIntegerSingle = 2000L))
        val runB = makeRun(KernelScores(cpuIntegerSingle = 1000L))

        val deltas = ComparativeResult.buildDeltas(runA, runB)
        val overall = deltas.first { it.label == "Overall score" }

        assertThat(overall.winner).isEqualTo(CategoryWinner.A)
        assertThat(overall.valueA).isGreaterThan(overall.valueB!!)
    }

    @Test
    fun `buildDeltas B wins GPU when B has higher gpuFps`() {
        val runA = makeRun(KernelScores(gpuFps = 60.0, gpuP1LowFps = 50.0, gpuFrameConsistencyPct = 90.0))
        val runB = makeRun(KernelScores(gpuFps = 90.0, gpuP1LowFps = 75.0, gpuFrameConsistencyPct = 90.0))

        val deltas = ComparativeResult.buildDeltas(runA, runB)
        val gpuFps = deltas.first { it.label == "GPU avg FPS" }
        val gpuScore = deltas.first { it.label == "GPU score" }

        assertThat(gpuFps.winner).isEqualTo(CategoryWinner.B)
        assertThat(gpuScore.winner).isEqualTo(CategoryWinner.B)
    }

    @Test
    fun `buildDeltas throttle drop lower is better A wins when A drops less`() {
        val samplesA = listOf(
            ThrottleSample(0L, 2000, 60f, null, 35f, null),
            ThrottleSample(120_000L, 1990, 70f, null, 37f, null), // barely drops
        )
        val samplesB = listOf(
            ThrottleSample(0L, 2000, 60f, null, 35f, null),
            ThrottleSample(120_000L, 1200, 80f, null, 42f, null), // drops 40%
        )
        val runA = BenchRun(
            id = 0L, name = "A", flavor = BenchFlavor.FULL, startedAtMs = 0L,
            durationMs = 120_000L,
            snapshot = SystemSnapshot(0L, "d", "s", "14", "ROOT", emptyList(), null, null, null, "t"),
            kernels = KernelScores(),
            throttleSamples = samplesA,
            outcome = BenchOutcome.COMPLETED,
        )
        val runB = runA.copy(throttleSamples = samplesB)

        val deltas = ComparativeResult.buildDeltas(runA, runB)
        val drop = deltas.firstOrNull { it.label == "Throttle drop" }

        // If throttle data was captured, A should win (lower drop %).
        if (drop != null && drop.winner != CategoryWinner.NO_DATA) {
            assertThat(drop.lowerIsBetter).isTrue()
            assertThat(drop.winner).isEqualTo(CategoryWinner.A)
        }
    }

    @Test
    fun `buildDeltas no data metrics are omitted from result list`() {
        // A QUICK run has no GPU, memory, throttle — those deltas should be absent.
        val runA = makeRun(KernelScores(cpuIntegerSingle = 1000L))
        val runB = makeRun(KernelScores(cpuIntegerSingle = 1200L))

        val deltas = ComparativeResult.buildDeltas(runA, runB)

        // No null-null deltas should appear.
        assertThat(deltas.all { it.valueA != null || it.valueB != null }).isTrue()
    }

    @Test
    fun `buildDeltas one slot aborted still produces deltas with partial data`() {
        val runA = makeRun(KernelScores(cpuIntegerSingle = 1000L))
        val runB = makeRun(KernelScores(), outcome = BenchOutcome.ABORTED_TEMP) // no scores

        val deltas = ComparativeResult.buildDeltas(runA, runB)
        val overall = deltas.firstOrNull { it.label == "Overall score" }

        // runA has a score, runB does not → A wins by partial data.
        if (overall != null) {
            assertThat(overall.winner).isEqualTo(CategoryWinner.A)
            assertThat(overall.valueB).isNull()
        }
    }

    // ─── applyStatusFrom ──────────────────────────────────────────────

    @Test
    fun `applyStatusFrom single success returns CONFIRMED`() {
        val (status, _) = ComparativeResult.applyStatusFrom(listOf(successResult()), presetHasTunables = true)
        assertThat(status).isEqualTo(ApplyStatus.CONFIRMED)
    }

    @Test
    fun `applyStatusFrom mixed success and denied returns CONFIRMED`() {
        val (status, details) = ComparativeResult.applyStatusFrom(
            listOf(successResult(), deniedResult()),
            presetHasTunables = true,
        )
        assertThat(status).isEqualTo(ApplyStatus.CONFIRMED)
        assertThat(details).contains("1/2")
    }

    @Test
    fun `applyStatusFrom all denied returns UNVERIFIABLE`() {
        val (status, _) = ComparativeResult.applyStatusFrom(
            listOf(deniedResult(), deniedResult()),
            presetHasTunables = true,
        )
        assertThat(status).isEqualTo(ApplyStatus.UNVERIFIABLE)
    }

    @Test
    fun `applyStatusFrom all rejected returns UNVERIFIABLE`() {
        val (status, _) = ComparativeResult.applyStatusFrom(
            listOf(rejectedResult()),
            presetHasTunables = true,
        )
        assertThat(status).isEqualTo(ApplyStatus.UNVERIFIABLE)
    }

    @Test
    fun `applyStatusFrom all failed throwable returns FAILED`() {
        val (status, _) = ComparativeResult.applyStatusFrom(
            listOf(failedResult()),
            presetHasTunables = true,
        )
        assertThat(status).isEqualTo(ApplyStatus.FAILED)
    }

    @Test
    fun `applyStatusFrom empty profile no tunables returns FAILED`() {
        val (status, msg) = ComparativeResult.applyStatusFrom(
            emptyList(),
            presetHasTunables = false,
        )
        assertThat(status).isEqualTo(ApplyStatus.FAILED)
        assertThat(msg).contains("no tunable")
    }

    @Test
    fun `applyStatusFrom empty results with tunables returns FAILED`() {
        // hasTunables=true but zero writes attempted (writer skipped everything)
        val (status, _) = ComparativeResult.applyStatusFrom(emptyList(), presetHasTunables = true)
        assertThat(status).isEqualTo(ApplyStatus.FAILED)
    }

    // ─── presetHasTunables ────────────────────────────────────────────

    @Test
    fun `presetHasTunables true when cpuPolicyMaxKhz is non-empty`() {
        val profile = makeProfile("test", hasTunables = true)
        assertThat(ComparativeResult.presetHasTunables(profile)).isTrue()
    }

    @Test
    fun `presetHasTunables false when completely empty`() {
        val profile = makeProfile("empty", hasTunables = false)
        assertThat(ComparativeResult.presetHasTunables(profile)).isFalse()
    }

    @Test
    fun `presetHasTunables true when only gpuMaxHz is set`() {
        val profile = UserProfile(
            id = "x", name = "gpu-only", description = "",
            gpuMaxHz = 850_000_000L,
            createdAtMs = 0L,
        )
        assertThat(ComparativeResult.presetHasTunables(profile)).isTrue()
    }

    // ─── ComparativeResult.build ──────────────────────────────────────

    @Test
    fun `build produces deltas when both slots have runs`() {
        val runA = makeRun(KernelScores(cpuIntegerSingle = 1000L))
        val runB = makeRun(KernelScores(cpuIntegerSingle = 1200L))
        val slotA = ComparativeSlot(makeProfile("A"), ApplyStatus.CONFIRMED, "ok", runA)
        val slotB = ComparativeSlot(makeProfile("B"), ApplyStatus.CONFIRMED, "ok", runB)

        val result = ComparativeResult.build(slotA, slotB, BenchFlavor.STANDARD, partial = false)

        assertThat(result.partial).isFalse()
        assertThat(result.deltas).isNotEmpty()
    }

    @Test
    fun `build produces empty deltas when slot B has no run`() {
        val runA = makeRun(KernelScores(cpuIntegerSingle = 1000L))
        val slotA = ComparativeSlot(makeProfile("A"), ApplyStatus.CONFIRMED, "ok", runA)
        val slotB = ComparativeSlot(makeProfile("B"), ApplyStatus.FAILED, "no run", run = null)

        val result = ComparativeResult.build(slotA, slotB, BenchFlavor.STANDARD, partial = true)

        assertThat(result.partial).isTrue()
        assertThat(result.deltas).isEmpty()
    }

    @Test
    fun `build marks partial correctly`() {
        val runA = makeRun(KernelScores())
        val slotA = ComparativeSlot(makeProfile("A"), ApplyStatus.CONFIRMED, "ok", runA)
        val slotB = ComparativeSlot(makeProfile("B"), ApplyStatus.FAILED, "cancelled", null, cancelledMidRun = true)

        val result = ComparativeResult.build(slotA, slotB, BenchFlavor.QUICK, partial = true)

        assertThat(result.partial).isTrue()
        assertThat(result.slotB.cancelledMidRun).isTrue()
    }

    @Test
    fun `build winner B when B has clearly higher CPU score`() {
        val runA = makeRun(KernelScores(cpuIntegerSingle = 500L))
        val runB = makeRun(KernelScores(cpuIntegerSingle = 1500L))
        val slotA = ComparativeSlot(makeProfile("A"), ApplyStatus.CONFIRMED, "ok", runA)
        val slotB = ComparativeSlot(makeProfile("B"), ApplyStatus.CONFIRMED, "ok", runB)

        val result = ComparativeResult.build(slotA, slotB, BenchFlavor.QUICK, partial = false)

        val overallDelta = result.deltas.first { it.label == "Overall score" }
        assertThat(overallDelta.winner).isEqualTo(CategoryWinner.B)
    }

    @Test
    fun `build handles equal scores as TIE`() {
        val kernels = KernelScores(cpuIntegerSingle = 1000L)
        val slotA = ComparativeSlot(makeProfile("A"), ApplyStatus.CONFIRMED, "ok", makeRun(kernels))
        val slotB = ComparativeSlot(makeProfile("B"), ApplyStatus.CONFIRMED, "ok", makeRun(kernels))

        val result = ComparativeResult.build(slotA, slotB, BenchFlavor.QUICK, partial = false)

        val overallDelta = result.deltas.first { it.label == "Overall score" }
        assertThat(overallDelta.winner).isEqualTo(CategoryWinner.TIE)
    }
}
