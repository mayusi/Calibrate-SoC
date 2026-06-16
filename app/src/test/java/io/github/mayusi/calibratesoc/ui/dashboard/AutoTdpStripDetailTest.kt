package io.github.mayusi.calibratesoc.ui.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [autoTdpStripDetail].
 *
 * The function is pure (no Android, no Compose) so it can be tested with
 * plain JUnit. It drives the detail line of the AutoTDP status strip on the
 * Dashboard, where honesty-first display rules apply.
 */
class AutoTdpStripDetailTest {

    // ─── No applied state, no savings ───────────────────────────────────────────

    @Test
    fun `all nulls with empty reason returns null`() {
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = null,
            deltaMw = null,
            enoughData = null,
            fallbackReason = "",
        )
        assertThat(result).isNull()
    }

    @Test
    fun `all nulls with a reason returns trimmed reason`() {
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = null,
            deltaMw = null,
            enoughData = null,
            fallbackReason = "waiting for baseline",
        )
        assertThat(result).isEqualTo("waiting for baseline")
    }

    // ─── Parked cores ────────────────────────────────────────────────────────────

    @Test
    fun `parked cores formatted correctly`() {
        val result = autoTdpStripDetail(
            parkedCores = setOf(4, 5),
            bigClusterCapKhz = null,
            deltaMw = null,
            enoughData = null,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("parked cpu4,cpu5")
    }

    @Test
    fun `parked cores are sorted ascending`() {
        val result = autoTdpStripDetail(
            parkedCores = setOf(7, 4, 6),
            bigClusterCapKhz = null,
            deltaMw = null,
            enoughData = null,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("parked cpu4,cpu6,cpu7")
    }

    // ─── Freq cap ────────────────────────────────────────────────────────────────

    @Test
    fun `cap label formatted as GHz with one decimal`() {
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = 1_800_000, // 1.8 GHz
            deltaMw = null,
            enoughData = null,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("cap 1.8G")
    }

    @Test
    fun `cap label rounds to one decimal place`() {
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = 2_419_200, // 2.4192 GHz → "2.4G"
            deltaMw = null,
            enoughData = null,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("cap 2.4G")
    }

    // ─── Savings ─────────────────────────────────────────────────────────────────

    @Test
    fun `savings shows measuring when not enough data`() {
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = null,
            deltaMw = 800L,
            enoughData = false,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("measuring…")
    }

    @Test
    fun `positive delta shows saving with no double sign`() {
        // deltaMw positive = power SAVED vs stock. Must read "saving 800 mW" —
        // never "saving -800" (the old double-negative bug).
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = null,
            deltaMw = 800L,
            enoughData = true,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("saving 800 mW")
    }

    @Test
    fun `negative delta is honestly shown as using more, not a saving`() {
        // deltaMw negative = drawing MORE than stock (e.g. unparked under CPU load).
        // Must NOT call it a "saving" and must not produce "--416".
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = null,
            deltaMw = -416L,
            enoughData = true,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("using 416 mW more")
        assertThat(result).doesNotContain("saving")
        assertThat(result).doesNotContain("--")
    }

    @Test
    fun `zero delta reads as no change`() {
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = null,
            deltaMw = 0L,
            enoughData = true,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("no change vs stock")
    }

    // ─── Combined ────────────────────────────────────────────────────────────────

    @Test
    fun `all three parts joined with separator`() {
        val result = autoTdpStripDetail(
            parkedCores = setOf(4, 5),
            bigClusterCapKhz = 1_800_000,
            deltaMw = 500L,
            enoughData = true,
            fallbackReason = "",
        )
        assertThat(result).isEqualTo("parked cpu4,cpu5 · cap 1.8G · saving 500 mW")
    }

    @Test
    fun `reason is ignored when there are parts to display`() {
        val result = autoTdpStripDetail(
            parkedCores = setOf(6),
            bigClusterCapKhz = null,
            deltaMw = null,
            enoughData = null,
            fallbackReason = "should not appear",
        )
        assertThat(result).isEqualTo("parked cpu6")
    }

    // ─── Reason truncation ───────────────────────────────────────────────────────

    @Test
    fun `reason is truncated to 60 characters`() {
        val longReason = "A".repeat(80)
        val result = autoTdpStripDetail(
            parkedCores = emptySet(),
            bigClusterCapKhz = null,
            deltaMw = null,
            enoughData = null,
            fallbackReason = longReason,
        )
        assertThat(result).hasLength(60)
    }
}
