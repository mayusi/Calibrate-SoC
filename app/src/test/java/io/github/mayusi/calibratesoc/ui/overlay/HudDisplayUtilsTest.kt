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

    // ── formatClusterMhz ─────────────────────────────────────────────────────

    @Test
    fun `formatClusterMhz formats non-null value with MHz suffix`() {
        assertThat(HudDisplayUtils.formatClusterMhz(2918)).isEqualTo("2918MHz")
    }

    @Test
    fun `formatClusterMhz returns dash for null`() {
        assertThat(HudDisplayUtils.formatClusterMhz(null)).isEqualTo("—")
    }

    // ── formatWatts ──────────────────────────────────────────────────────────

    @Test
    fun `formatWatts formats value with one decimal`() {
        assertThat(HudDisplayUtils.formatWatts(4.2)).isEqualTo("4.2W")
    }

    @Test
    fun `formatWatts returns dash for null`() {
        assertThat(HudDisplayUtils.formatWatts(null)).isEqualTo("—")
    }

    @Test
    fun `formatWatts shows one decimal place`() {
        // %.1f on JVM rounds half-up: 0.85 → 0.9
        assertThat(HudDisplayUtils.formatWatts(0.85)).isEqualTo("0.9W")
        assertThat(HudDisplayUtils.formatWatts(0.84)).isEqualTo("0.8W")
        assertThat(HudDisplayUtils.formatWatts(10.0)).isEqualTo("10.0W")
    }

    // ── formatTemp ───────────────────────────────────────────────────────────

    @Test
    fun `formatTemp formats temperature with degree-C suffix`() {
        assertThat(HudDisplayUtils.formatTemp(72.4f)).isEqualTo("72°C")
    }

    @Test
    fun `formatTemp returns dash for null`() {
        assertThat(HudDisplayUtils.formatTemp(null)).isEqualTo("—")
    }

    // ── formatGhzFromMhz ─────────────────────────────────────────────────────

    @Test
    fun `formatGhzFromMhz converts 2920 to 2_92G`() {
        assertThat(HudDisplayUtils.formatGhzFromMhz(2920)).isEqualTo("2.92G")
    }

    @Test
    fun `formatGhzFromMhz returns dash for null`() {
        assertThat(HudDisplayUtils.formatGhzFromMhz(null)).isEqualTo("—")
    }

    @Test
    fun `formatGhzFromMhz handles sub-GHz value`() {
        assertThat(HudDisplayUtils.formatGhzFromMhz(768)).isEqualTo("0.77G")
    }

    // ── hudWidthDp ────────────────────────────────────────────────────────────

    // Full-panel widths were widened (420/480/540) so the horizontal HUD lays
    // the FPS hero block and the 4-wide metric-tile row out on one row.
    @Test
    fun `hudWidthDp returns 420 for index 0`() {
        assertThat(HudDisplayUtils.hudWidthDp(0)).isEqualTo(420)
    }

    @Test
    fun `hudWidthDp returns 480 for index 1`() {
        assertThat(HudDisplayUtils.hudWidthDp(1)).isEqualTo(480)
    }

    @Test
    fun `hudWidthDp returns 540 for index 2`() {
        assertThat(HudDisplayUtils.hudWidthDp(2)).isEqualTo(540)
    }

    @Test
    fun `hudWidthDp clamps out-of-range indices`() {
        assertThat(HudDisplayUtils.hudWidthDp(-1)).isEqualTo(420)
        assertThat(HudDisplayUtils.hudWidthDp(99)).isEqualTo(540)
    }

    // ── hudSizeLabel ──────────────────────────────────────────────────────────

    @Test
    fun `hudSizeLabel returns correct labels`() {
        assertThat(HudDisplayUtils.hudSizeLabel(0)).isEqualTo("SM")
        assertThat(HudDisplayUtils.hudSizeLabel(1)).isEqualTo("MD")
        assertThat(HudDisplayUtils.hudSizeLabel(2)).isEqualTo("LG")
    }

    // ── formatAutoTdpProfileShort ─────────────────────────────────────────────

    @Test
    fun `formatAutoTdpProfileShort maps known profiles`() {
        assertThat(HudDisplayUtils.formatAutoTdpProfileShort("EFFICIENCY")).isEqualTo("EFF")
        assertThat(HudDisplayUtils.formatAutoTdpProfileShort("BALANCED")).isEqualTo("BAL")
        assertThat(HudDisplayUtils.formatAutoTdpProfileShort("BATTERY_TARGET")).isEqualTo("TGT")
    }

    @Test
    fun `formatAutoTdpProfileShort is case insensitive`() {
        assertThat(HudDisplayUtils.formatAutoTdpProfileShort("efficiency")).isEqualTo("EFF")
    }

    @Test
    fun `formatAutoTdpProfileShort truncates unknown to 3 chars`() {
        assertThat(HudDisplayUtils.formatAutoTdpProfileShort("CUSTOM_MODE")).isEqualTo("CUS")
    }

    // ── formatAutoTdpSavings ──────────────────────────────────────────────────

    @Test
    fun `formatAutoTdpSavings returns measuring when not ready`() {
        assertThat(
            HudDisplayUtils.formatAutoTdpSavings(null, null, savingsReady = false)
        ).isEqualTo("measuring…")
    }

    @Test
    fun `formatAutoTdpSavings returns measuring even when savingsMw present but not ready`() {
        assertThat(
            HudDisplayUtils.formatAutoTdpSavings(1500, null, savingsReady = false)
        ).isEqualTo("measuring…")
    }

    @Test
    fun `formatAutoTdpSavings formats watts and pct when ready`() {
        val result = HudDisplayUtils.formatAutoTdpSavings(1800, 12.5, savingsReady = true)
        assertThat(result).isEqualTo("−1.8W (12%)")
    }

    @Test
    fun `formatAutoTdpSavings omits pct when null`() {
        val result = HudDisplayUtils.formatAutoTdpSavings(1800, null, savingsReady = true)
        assertThat(result).isEqualTo("−1.8W")
    }

    // ── formatHz ─────────────────────────────────────────────────────────────

    @Test
    fun `formatHz returns integer Hz with Hz suffix`() {
        assertThat(HudDisplayUtils.formatHz(120.0f)).isEqualTo("120Hz")
        assertThat(HudDisplayUtils.formatHz(60.0f)).isEqualTo("60Hz")
        assertThat(HudDisplayUtils.formatHz(90.0f)).isEqualTo("90Hz")
    }

    // ── formatOpacityPct ─────────────────────────────────────────────────────

    @Test
    fun `formatOpacityPct returns integer percentage`() {
        assertThat(HudDisplayUtils.formatOpacityPct(0.94f)).isEqualTo("94%")
        assertThat(HudDisplayUtils.formatOpacityPct(1.0f)).isEqualTo("100%")
        assertThat(HudDisplayUtils.formatOpacityPct(0.5f)).isEqualTo("50%")
    }
}
