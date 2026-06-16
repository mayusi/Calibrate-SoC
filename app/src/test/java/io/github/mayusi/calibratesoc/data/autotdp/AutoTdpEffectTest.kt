package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [AutoTdpEffect.from] — the pure proof-of-effect factory.
 *
 * Honesty contract under test:
 *  - DERIVED fields (cap delta, parked cores, GPU floor) are always populated.
 *  - MEASURED power fields are null when !enoughData; non-null + MEASURED when enough.
 *  - sessionEnergySavedMilliWh integrates only off a measured baseline.
 *  - temp/fps are null from the factory (no measured A/B baseline passed in).
 */
class AutoTdpEffectTest {

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(
            499_000, 844_000, 1_171_000, 1_536_000,
            1_920_000, 2_323_000, 2_707_000, 2_803_000,
        ),
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
    )

    private fun enoughSavings(deltaMw: Long) = SavingsResult(
        baselineMw = 4_000L,
        tunedMw = 4_000L - deltaMw,
        deltaMw = deltaMw,
        deltaPct = (deltaMw.toDouble() / 4_000.0) * 100.0,
        sampleCount = 20,
        enoughData = true,
    )

    private val notEnoughSavings = SavingsResult(
        baselineMw = 4_000L,
        tunedMw = 3_200L,
        deltaMw = 0L,
        deltaPct = 0.0,
        sampleCount = 3,
        enoughData = false,
    )

    // ── capDelta math ──────────────────────────────────────────────────────────

    @Test
    fun `capDelta is stock ceiling minus applied cap`() {
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        val effect = AutoTdpEffect.from(applied, caps, savings = null, sessionElapsedMs = 0L)

        // stock ceiling = top OPP = 2_803_000; cap = 1_920_000 → delta = 883_000
        assertThat(effect.stockBigCeilingKhz).isEqualTo(2_803_000)
        assertThat(effect.bigCapKhz).isEqualTo(1_920_000)
        assertThat(effect.capDeltaKhz).isEqualTo(883_000)
    }

    @Test
    fun `capDelta is null when cap is null`() {
        val applied = TdpState(bigClusterCapKhz = null)
        val effect = AutoTdpEffect.from(applied, caps, savings = null, sessionElapsedMs = 0L)
        assertThat(effect.capDeltaKhz).isNull()
    }

    @Test
    fun `capDelta is null when caps OPP table is empty (no ceiling)`() {
        val emptyCaps = caps.copy(bigClusterOppStepsKhz = emptyList())
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        val effect = AutoTdpEffect.from(applied, emptyCaps, savings = null, sessionElapsedMs = 0L)
        assertThat(effect.stockBigCeilingKhz).isNull()
        assertThat(effect.capDeltaKhz).isNull()
    }

    // ── DERIVED fields are always populated ─────────────────────────────────────

    @Test
    fun `derived fields populated regardless of savings`() {
        val applied = TdpState(
            parkedPrimeCores = setOf(7),
            bigClusterCapKhz = 1_536_000,
            gpuFloorLevel = 0,
        )
        val effect = AutoTdpEffect.from(applied, caps, savings = null, sessionElapsedMs = 0L)
        assertThat(effect.parkedPrimeCores).containsExactly(7)
        assertThat(effect.gpuFloorLevel).isEqualTo(0)
        assertThat(effect.bigCapKhz).isEqualTo(1_536_000)
    }

    // ── Power fields gated on enoughData ────────────────────────────────────────

    @Test
    fun `power fields null and source ESTIMATED when not enough data`() {
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        val effect = AutoTdpEffect.from(applied, caps, notEnoughSavings, sessionElapsedMs = 60_000L)
        assertThat(effect.powerSavedMw).isNull()
        assertThat(effect.powerSavedPct).isNull()
        assertThat(effect.effectSource).isEqualTo(EffectSource.ESTIMATED)
        assertThat(effect.sessionEnergySavedMilliWh).isNull()
    }

    @Test
    fun `power fields null and source ESTIMATED when savings is null`() {
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        val effect = AutoTdpEffect.from(applied, caps, savings = null, sessionElapsedMs = 60_000L)
        assertThat(effect.powerSavedMw).isNull()
        assertThat(effect.effectSource).isEqualTo(EffectSource.ESTIMATED)
    }

    @Test
    fun `power fields populated and source MEASURED when enough data`() {
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        val effect = AutoTdpEffect.from(applied, caps, enoughSavings(800L), sessionElapsedMs = 0L)
        assertThat(effect.powerSavedMw).isEqualTo(800L)
        assertThat(effect.powerSavedPct).isWithin(0.01).of(20.0)
        assertThat(effect.effectSource).isEqualTo(EffectSource.MEASURED)
    }

    // ── sessionWh integration ───────────────────────────────────────────────────

    @Test
    fun `session energy integrates measured saving over elapsed time`() {
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        // 1000 mW saved over exactly 1 hour = 1000 mWh.
        val effect = AutoTdpEffect.from(
            applied, caps, enoughSavings(1_000L), sessionElapsedMs = 3_600_000L,
        )
        assertThat(effect.sessionEnergySavedMilliWh).isNotNull()
        assertThat(effect.sessionEnergySavedMilliWh!!).isWithin(0.01).of(1_000.0)
    }

    @Test
    fun `session energy is half for half an hour`() {
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        // 1000 mW saved over 30 min = 500 mWh.
        val effect = AutoTdpEffect.from(
            applied, caps, enoughSavings(1_000L), sessionElapsedMs = 1_800_000L,
        )
        assertThat(effect.sessionEnergySavedMilliWh!!).isWithin(0.01).of(500.0)
    }

    @Test
    fun `session energy null when elapsed is zero`() {
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        val effect = AutoTdpEffect.from(applied, caps, enoughSavings(1_000L), sessionElapsedMs = 0L)
        assertThat(effect.sessionEnergySavedMilliWh).isNull()
    }

    // ── temp/fps null when no measured baseline ─────────────────────────────────

    @Test
    fun `temp and fps deltas are null from the factory`() {
        val applied = TdpState(bigClusterCapKhz = 1_920_000)
        val effect = AutoTdpEffect.from(applied, caps, enoughSavings(800L), sessionElapsedMs = 3_600_000L)
        // from() never derives temp/fps — they require a measured A/B window pair
        // the daemon patches in separately.
        assertThat(effect.tempDeltaC).isNull()
        assertThat(effect.fpsDelta).isNull()
    }

    // ── null applied state is handled gracefully ────────────────────────────────

    @Test
    fun `null applied state yields empty derived fields`() {
        val effect = AutoTdpEffect.from(appliedState = null, caps = caps, savings = null, sessionElapsedMs = 0L)
        assertThat(effect.bigCapKhz).isNull()
        assertThat(effect.parkedPrimeCores).isEmpty()
        assertThat(effect.gpuFloorLevel).isNull()
        assertThat(effect.capDeltaKhz).isNull()
    }
}
