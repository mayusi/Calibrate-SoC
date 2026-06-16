package io.github.mayusi.calibratesoc.data.autotdp

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * Unit tests for [AutoTdpController.updateProbeResult] — the savings-wiring patch
 * that lands a completed probe's MEASURED fields onto the live effect.
 *
 * [Context] is mocked because updateProbeResult / updateState never touch it
 * (only start/stop route to the service).
 */
class AutoTdpControllerProbeTest {

    private val context = mockk<Context>(relaxed = true)

    private val caps = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(1_171_000, 1_920_000, 2_803_000),
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

    private val notEnough = SavingsResult(
        baselineMw = 0L, tunedMw = 0L, deltaMw = 0L, deltaPct = 0.0,
        sampleCount = 3, enoughData = false,
    )

    /** Seed the controller with a RUNNING state that already has a DERIVED effect. */
    private fun seededController(): AutoTdpController {
        val controller = AutoTdpController(context)
        val applied = TdpState(parkedPrimeCores = setOf(7), bigClusterCapKhz = 1_920_000)
        val effect = AutoTdpEffect.from(applied, caps, savings = null, sessionElapsedMs = 0L)
        controller.updateState(
            AutoTdpRunState(
                status = AutoTdpStatus.RUNNING,
                appliedState = applied,
                effect = effect,
                sessionStartEpochMs = 1_000L,
                lastAppliedEpochMs = 1_000L + 3_600_000L, // +1 hour
            )
        )
        return controller
    }

    @Test
    fun `probe with enough data patches MEASURED power and source onto the effect`() {
        val controller = seededController()
        controller.updateProbeResult(
            ProbeResult(savings = enoughSavings(800L), tempDeltaC = 4.5f, fpsDelta = 6),
        )

        val state = controller.state.value
        assertThat(state.savings?.deltaMw).isEqualTo(800L)
        val effect = state.effect!!
        assertThat(effect.effectSource).isEqualTo(EffectSource.MEASURED)
        assertThat(effect.powerSavedMw).isEqualTo(800L)
        assertThat(effect.tempDeltaC).isWithin(0.01f).of(4.5f)
        assertThat(effect.fpsDelta).isEqualTo(6)
        // DERIVED fields preserved from the seeded effect.
        assertThat(effect.parkedPrimeCores).containsExactly(7)
        assertThat(effect.capDeltaKhz).isEqualTo(2_803_000 - 1_920_000)
        // Session energy: 800 mW * 1 h = 800 mWh.
        assertThat(effect.sessionEnergySavedMilliWh!!).isWithin(0.5).of(800.0)
    }

    @Test
    fun `probe without enough data leaves power null and source ESTIMATED`() {
        val controller = seededController()
        controller.updateProbeResult(
            ProbeResult(savings = notEnough, tempDeltaC = null, fpsDelta = null),
        )

        val effect = controller.state.value.effect!!
        assertThat(effect.effectSource).isEqualTo(EffectSource.ESTIMATED)
        assertThat(effect.powerSavedMw).isNull()
        assertThat(effect.powerSavedPct).isNull()
        assertThat(effect.tempDeltaC).isNull()
        assertThat(effect.fpsDelta).isNull()
        assertThat(effect.sessionEnergySavedMilliWh).isNull()
    }

    @Test
    fun `savings is always stored even when effect is absent`() {
        val controller = AutoTdpController(context) // no seeded effect
        controller.updateProbeResult(
            ProbeResult(savings = enoughSavings(500L), tempDeltaC = 3f, fpsDelta = 4),
        )
        // savings stored; effect stays null (nothing applied yet).
        assertThat(controller.state.value.savings?.deltaMw).isEqualTo(500L)
        assertThat(controller.state.value.effect).isNull()
    }
}
