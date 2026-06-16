package io.github.mayusi.calibratesoc.ui.overlay

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.HoldReason
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

    // ── holdReasonLabel ──────────────────────────────────────────────────────

    @Test
    fun `holdReasonLabel maps every reason to a non-empty label`() {
        for (reason in HoldReason.values()) {
            assertThat(HudDisplayUtils.holdReasonLabel(reason)).isNotEmpty()
        }
    }

    @Test
    fun `holdReasonLabel LOAD_BLIND reads unreadable not idle`() {
        // HONESTY INVARIANT: load-blind must NEVER claim the device is idle.
        val label = HudDisplayUtils.holdReasonLabel(HoldReason.LOAD_BLIND_HOLDING)
        assertThat(label.lowercase()).contains("unreadable")
        assertThat(label.lowercase()).doesNotContain("idle")
    }

    @Test
    fun `holdReasonLabel IDLE is the only one that may say lightly loaded`() {
        assertThat(HudDisplayUtils.holdReasonLabel(HoldReason.IDLE_HOLDING).lowercase())
            .contains("lightly loaded")
        // Load-blind must not borrow the idle wording.
        assertThat(HudDisplayUtils.holdReasonLabel(HoldReason.LOAD_BLIND_HOLDING).lowercase())
            .doesNotContain("lightly")
    }

    @Test
    fun `holdReasonLabel distinguishes cpu-bound and gpu-bound`() {
        assertThat(HudDisplayUtils.holdReasonLabel(HoldReason.CPU_BOUND_RELAXING).lowercase())
            .contains("cpu-bound")
        assertThat(HudDisplayUtils.holdReasonLabel(HoldReason.GPU_BOUND_CAPPING).lowercase())
            .contains("gpu-bound")
    }

    @Test
    fun `holdReasonLabel NO_TELEMETRY reads as starting`() {
        assertThat(HudDisplayUtils.holdReasonLabel(HoldReason.NO_TELEMETRY).lowercase())
            .contains("starting")
    }

    // ── heartbeatLabel ───────────────────────────────────────────────────────

    @Test
    fun `heartbeatLabel returns null when no tick yet`() {
        assertThat(HudDisplayUtils.heartbeatLabel(null, nowMs = 10_000L)).isNull()
    }

    @Test
    fun `heartbeatLabel reads just now under one second`() {
        val now = 10_000L
        assertThat(HudDisplayUtils.heartbeatLabel(now - 500L, now)).isEqualTo("adjusted just now")
    }

    @Test
    fun `heartbeatLabel reads seconds ago`() {
        val now = 10_000L
        assertThat(HudDisplayUtils.heartbeatLabel(now - 4_000L, now)).isEqualTo("adjusted 4s ago")
    }

    @Test
    fun `heartbeatLabel reads minutes ago`() {
        val now = 200_000L
        assertThat(HudDisplayUtils.heartbeatLabel(now - 125_000L, now)).isEqualTo("adjusted 2m ago")
    }

    @Test
    fun `heartbeatLabel clamps a future timestamp to just now`() {
        val now = 10_000L
        // Clock skew: epoch slightly ahead of now must not produce negative age.
        assertThat(HudDisplayUtils.heartbeatLabel(now + 5_000L, now)).isEqualTo("adjusted just now")
    }

    // ── heartbeatIsLive ──────────────────────────────────────────────────────

    @Test
    fun `heartbeatIsLive false when null`() {
        assertThat(HudDisplayUtils.heartbeatIsLive(null, nowMs = 10_000L)).isFalse()
    }

    @Test
    fun `heartbeatIsLive true within window`() {
        val now = 10_000L
        assertThat(HudDisplayUtils.heartbeatIsLive(now - 2_000L, now)).isTrue()
    }

    @Test
    fun `heartbeatIsLive false beyond window`() {
        val now = 10_000L
        assertThat(HudDisplayUtils.heartbeatIsLive(now - 5_000L, now)).isFalse()
    }

    @Test
    fun `heartbeatIsLive true exactly at window boundary`() {
        val now = 10_000L
        assertThat(
            HudDisplayUtils.heartbeatIsLive(now - HudDisplayUtils.HEARTBEAT_LIVE_WINDOW_MS, now)
        ).isTrue()
    }

    // ── formatProofChipCap ───────────────────────────────────────────────────

    @Test
    fun `formatProofChipCap returns STOCK when null`() {
        assertThat(HudDisplayUtils.formatProofChipCap(null)).isEqualTo("STOCK")
    }

    @Test
    fun `formatProofChipCap formats GHz with one decimal`() {
        assertThat(HudDisplayUtils.formatProofChipCap(3000)).isEqualTo("3.0G")
        assertThat(HudDisplayUtils.formatProofChipCap(1690)).isEqualTo("1.7G")
    }

    // ── formatCapLine ────────────────────────────────────────────────────────

    @Test
    fun `formatCapLine returns null when uncapped`() {
        assertThat(HudDisplayUtils.formatCapLine(null, 420)).isNull()
    }

    @Test
    fun `formatCapLine includes delta when positive`() {
        assertThat(HudDisplayUtils.formatCapLine(3000, 420)).isEqualTo("3.0 GHz - 420 MHz vs max")
    }

    @Test
    fun `formatCapLine omits delta when null or non-positive`() {
        assertThat(HudDisplayUtils.formatCapLine(3000, null)).isEqualTo("3.0 GHz")
        assertThat(HudDisplayUtils.formatCapLine(3000, 0)).isEqualTo("3.0 GHz")
    }

    // ── formatParkedCoresLine ────────────────────────────────────────────────

    @Test
    fun `formatParkedCoresLine returns null when empty`() {
        assertThat(HudDisplayUtils.formatParkedCoresLine(emptySet())).isNull()
    }

    @Test
    fun `formatParkedCoresLine singular and plural`() {
        assertThat(HudDisplayUtils.formatParkedCoresLine(setOf(7))).isEqualTo("1 prime core off")
        assertThat(HudDisplayUtils.formatParkedCoresLine(setOf(6, 7))).isEqualTo("2 prime cores off")
    }

    // ── formatGpuLevelLine ───────────────────────────────────────────────────

    @Test
    fun `formatGpuLevelLine returns null when no floor`() {
        assertThat(HudDisplayUtils.formatGpuLevelLine(null)).isNull()
    }

    @Test
    fun `formatGpuLevelLine tags level 0 as max perf`() {
        assertThat(HudDisplayUtils.formatGpuLevelLine(0)).isEqualTo("GPU lvl 0 - max perf")
    }

    @Test
    fun `formatGpuLevelLine plain label for non-zero level`() {
        assertThat(HudDisplayUtils.formatGpuLevelLine(3)).isEqualTo("GPU lvl 3")
    }

    // ── isHoldingAtStock ─────────────────────────────────────────────────────

    @Test
    fun `isHoldingAtStock true only when nothing applied`() {
        assertThat(HudDisplayUtils.isHoldingAtStock(null, emptySet(), null)).isTrue()
    }

    @Test
    fun `isHoldingAtStock false when any field present`() {
        assertThat(HudDisplayUtils.isHoldingAtStock(3000, emptySet(), null)).isFalse()
        assertThat(HudDisplayUtils.isHoldingAtStock(null, setOf(7), null)).isFalse()
        assertThat(HudDisplayUtils.isHoldingAtStock(null, emptySet(), 0)).isFalse()
    }

    // ── formatSessionWhLine ──────────────────────────────────────────────────

    @Test
    fun `formatSessionWhLine returns null when unmeasured`() {
        assertThat(HudDisplayUtils.formatSessionWhLine(null)).isNull()
    }

    @Test
    fun `formatSessionWhLine formats three decimals`() {
        assertThat(HudDisplayUtils.formatSessionWhLine(0.012)).isEqualTo("saved 0.012 Wh this session")
    }

    // ── formatDecisionCap ────────────────────────────────────────────────────

    @Test
    fun `formatDecisionCap returns stock when null`() {
        assertThat(HudDisplayUtils.formatDecisionCap(null)).isEqualTo("stock")
    }

    @Test
    fun `formatDecisionCap formats kHz to GHz`() {
        // 3_000_000 kHz = 3.0 GHz
        assertThat(HudDisplayUtils.formatDecisionCap(3_000_000)).isEqualTo("3.0G")
        assertThat(HudDisplayUtils.formatDecisionCap(1_690_000)).isEqualTo("1.7G")
    }
}
