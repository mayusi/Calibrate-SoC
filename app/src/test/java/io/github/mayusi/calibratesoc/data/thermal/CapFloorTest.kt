package io.github.mayusi.calibratesoc.data.thermal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * HIGH-2 — the shared big-cluster cap-floor + OPP-snap math. Confirms the predictive
 * throttle guard (and AutoTDP, which shares [CapFloor.HARD_FLOOR_FRACTION]) can never
 * write a value the kernel will silently clamp, and never caps below 40% of the top OPP.
 */
class CapFloorTest {

    // A representative AYN-Odin-3-style big-cluster OPP table (kHz), ascending.
    private val opps = listOf(
        307_200, 614_400, 1_017_600, 1_420_800, 1_804_800,
        2_208_000, 2_400_000, 2_803_200,
    )

    @Test
    fun `hard floor is the first OPP at or above 40 percent of the top OPP`() {
        // top = 2_803_200; 40% = 1_121_280 → first OPP >= that is 1_420_800.
        assertThat(CapFloor.hardFloorKhz(opps)).isEqualTo(1_420_800)
    }

    @Test
    fun `hard floor is null for a degenerate table`() {
        assertThat(CapFloor.hardFloorKhz(emptyList())).isNull()
        assertThat(CapFloor.hardFloorKhz(listOf(1_804_800))).isEqualTo(1_804_800)
    }

    @Test
    fun `snap rounds a between-OPP request DOWN to a real OPP`() {
        // 2_300_000 is between 2_208_000 and 2_400_000 → snap DOWN to 2_208_000.
        assertThat(CapFloor.snapCapToOpp(2_300_000, opps)).isEqualTo(2_208_000)
    }

    @Test
    fun `snap never returns a value below the hard floor`() {
        // A very low desired cap (e.g. the old 300 MHz floor / 384 MHz collapse) must be
        // raised to the 40% hard floor, not written through.
        assertThat(CapFloor.snapCapToOpp(384_000, opps)).isEqualTo(1_420_800)
        assertThat(CapFloor.snapCapToOpp(300_000, opps)).isEqualTo(1_420_800)
        assertThat(CapFloor.snapCapToOpp(1_017_600, opps)).isEqualTo(1_420_800)
    }

    @Test
    fun `snap at or above the top OPP returns the top OPP (no cap)`() {
        assertThat(CapFloor.snapCapToOpp(2_803_200, opps)).isEqualTo(2_803_200)
        assertThat(CapFloor.snapCapToOpp(9_999_999, opps)).isEqualTo(2_803_200)
    }

    @Test
    fun `snap with empty OPP table passes the request through unchanged`() {
        assertThat(CapFloor.snapCapToOpp(1_234_567, emptyList())).isEqualTo(1_234_567)
    }

    @Test
    fun `snap result is always a member of the OPP table`() {
        for (desired in listOf(250_000, 700_000, 1_500_000, 2_250_000, 2_900_000)) {
            val snapped = CapFloor.snapCapToOpp(desired, opps)
            assertThat(opps).contains(snapped)
        }
    }
}
