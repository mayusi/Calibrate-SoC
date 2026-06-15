package io.github.mayusi.calibratesoc.ui.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit tests for [HudDisplayUtils] — pure formatting and gate helpers.
 *
 * No Android, no mocks, no Compose harness. All functions are pure.
 */
class HudDisplayUtilsTest {

    // ── formatAutoTdpCompactLine ──────────────────────────────────────────────

    @Test
    fun `formatAutoTdpCompactLine returns empty when not running`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = false,
            parkedCores = setOf(6, 7),
            bigCapMhz = 1690,
            savingsMw = 1800,
            savingsReady = true,
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `formatAutoTdpCompactLine includes savings when ready`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = emptySet(),
            bigCapMhz = null,
            savingsMw = 1200,
            savingsReady = true,
        )
        assertThat(result).contains("−1200mW")
        assertThat(result).doesNotContain("measuring")
    }

    @Test
    fun `formatAutoTdpCompactLine shows measuring when savings not ready`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = emptySet(),
            bigCapMhz = null,
            savingsMw = null,
            savingsReady = false,
        )
        assertThat(result).contains("measuring")
        assertThat(result).doesNotContain("mW")
    }

    @Test
    fun `formatAutoTdpCompactLine shows measuring when enoughData false even if savingsMw set`() {
        // SavingsResult.enoughData=false means we must not display the delta.
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = emptySet(),
            bigCapMhz = null,
            savingsMw = 500,  // present but not ready
            savingsReady = false,
        )
        assertThat(result).contains("measuring")
        assertThat(result).doesNotContain("500")
    }

    @Test
    fun `formatAutoTdpCompactLine includes parked core list when non-empty`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = setOf(7, 6), // unordered — should be sorted in output
            bigCapMhz = null,
            savingsMw = null,
            savingsReady = false,
        )
        assertThat(result).contains("parked cpu6,7")
    }

    @Test
    fun `formatAutoTdpCompactLine omits parked section when cores empty`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = emptySet(),
            bigCapMhz = null,
            savingsMw = null,
            savingsReady = false,
        )
        assertThat(result).doesNotContain("parked")
    }

    @Test
    fun `formatAutoTdpCompactLine includes cap in GHz when set`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = emptySet(),
            bigCapMhz = 1690,
            savingsMw = null,
            savingsReady = false,
        )
        // 1690 MHz → 1.69 G
        assertThat(result).contains("cap 1.69G")
    }

    @Test
    fun `formatAutoTdpCompactLine omits cap when null`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = emptySet(),
            bigCapMhz = null,
            savingsMw = null,
            savingsReady = false,
        )
        assertThat(result).doesNotContain("cap")
    }

    @Test
    fun `formatAutoTdpCompactLine starts with AutoTDP bullet when running`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = emptySet(),
            bigCapMhz = null,
            savingsMw = null,
            savingsReady = false,
        )
        assertThat(result).startsWith("AutoTDP ●")
    }

    @Test
    fun `formatAutoTdpCompactLine full example with all fields`() {
        val result = HudDisplayUtils.formatAutoTdpCompactLine(
            running = true,
            parkedCores = setOf(6, 7),
            bigCapMhz = 1690,
            savingsMw = 1800,
            savingsReady = true,
        )
        assertThat(result).contains("AutoTDP ●")
        assertThat(result).contains("parked cpu6,7")
        assertThat(result).contains("cap 1.69G")
        assertThat(result).contains("−1800mW")
    }

    // ── shouldGateSteppers ────────────────────────────────────────────────────

    @Test
    fun `shouldGateSteppers returns true when AutoTDP is running`() {
        assertThat(HudDisplayUtils.shouldGateSteppers(autoTdpRunning = true)).isTrue()
    }

    @Test
    fun `shouldGateSteppers returns false when AutoTDP is not running`() {
        assertThat(HudDisplayUtils.shouldGateSteppers(autoTdpRunning = false)).isFalse()
    }

    // ── fpsTileLabel ──────────────────────────────────────────────────────────

    @Test
    fun `fpsTileLabel returns FPS-game when fps differs clearly from hudHz`() {
        val (label, sub) = HudDisplayUtils.fpsTileLabel(fps = 45, hudHz = 60)
        assertThat(label).isEqualTo("FPS")
        assertThat(sub).isEqualTo("game")
    }

    @Test
    fun `fpsTileLabel returns REFRESH-Hz when fps equals hudHz`() {
        val (label, sub) = HudDisplayUtils.fpsTileLabel(fps = 60, hudHz = 60)
        assertThat(label).isEqualTo("REFRESH")
        assertThat(sub).isEqualTo("Hz")
    }

    @Test
    fun `fpsTileLabel returns REFRESH for fps in 59-61 band`() {
        assertThat(HudDisplayUtils.fpsTileLabel(fps = 59, hudHz = 90).first).isEqualTo("REFRESH")
        assertThat(HudDisplayUtils.fpsTileLabel(fps = 60, hudHz = 90).first).isEqualTo("REFRESH")
        assertThat(HudDisplayUtils.fpsTileLabel(fps = 61, hudHz = 90).first).isEqualTo("REFRESH")
    }

    @Test
    fun `fpsTileLabel returns REFRESH for fps in 119-121 band`() {
        assertThat(HudDisplayUtils.fpsTileLabel(fps = 119, hudHz = 60).first).isEqualTo("REFRESH")
        assertThat(HudDisplayUtils.fpsTileLabel(fps = 120, hudHz = 60).first).isEqualTo("REFRESH")
        assertThat(HudDisplayUtils.fpsTileLabel(fps = 121, hudHz = 60).first).isEqualTo("REFRESH")
    }

    @Test
    fun `fpsTileLabel returns FPS for fps outside all refresh bands`() {
        // 45 fps is clearly not a panel refresh rate.
        assertThat(HudDisplayUtils.fpsTileLabel(fps = 45, hudHz = 120).first).isEqualTo("FPS")
        // 30 fps gaming
        assertThat(HudDisplayUtils.fpsTileLabel(fps = 30, hudHz = 60).first).isEqualTo("FPS")
    }

    @Test
    fun `fpsTileLabel returns FPS for 144 fps`() {
        // 144 is not in 59-61 or 119-121 and differs from hudHz.
        val (label, _) = HudDisplayUtils.fpsTileLabel(fps = 144, hudHz = 60)
        assertThat(label).isEqualTo("FPS")
    }
}
