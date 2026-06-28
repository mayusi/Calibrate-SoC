package io.github.mayusi.calibratesoc.ui.tune

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.ui.tune.advanced.GpuLockLogic
import org.junit.Test

/**
 * Unit tests for [GpuLockLogic] — the pure-Kotlin helper backing the
 * Manual GPU Lock card in AdvancedTuningScreen.
 *
 * Adreno index convention:
 *   index 0      = max clock (highest performance)
 *   index N-1    = min clock (lowest power / stock floor)
 *
 * Invariant tested: floorIdx >= ceilingIdx
 *   (ceiling clock >= floor clock, i.e. ceiling index <= floor index)
 */
class GpuLockLogicTest {

    // -------------------------------------------------------------------------
    // Test fixture: mock available_frequencies as would come from a device.
    // Odin 3: 14 levels; RP6: 9 levels. We use a small 5-level map for speed.
    // Level 0 → 900 MHz (fastest), level 4 → 100 MHz (slowest).
    // -------------------------------------------------------------------------
    private val mockFreqMap: Map<Int, Long> = mapOf(
        0 to 900_000_000L,
        1 to 700_000_000L,
        2 to 500_000_000L,
        3 to 300_000_000L,
        4 to 100_000_000L,
    )
    private val numLevels = 5

    // =========================================================================
    // levelLabel — index to MHz string
    // =========================================================================

    @Test
    fun `levelLabel returns MHz for known index`() {
        assertThat(GpuLockLogic.levelLabel(0, mockFreqMap)).isEqualTo("900 MHz")
        assertThat(GpuLockLogic.levelLabel(2, mockFreqMap)).isEqualTo("500 MHz")
        assertThat(GpuLockLogic.levelLabel(4, mockFreqMap)).isEqualTo("100 MHz")
    }

    @Test
    fun `levelLabel returns raw index string when Hz entry missing`() {
        val sparseMap = mapOf(0 to 900_000_000L)
        assertThat(GpuLockLogic.levelLabel(3, sparseMap)).isEqualTo("3")
    }

    @Test
    fun `levelLabel returns raw index string for empty freq map`() {
        assertThat(GpuLockLogic.levelLabel(2, emptyMap())).isEqualTo("2")
    }

    // =========================================================================
    // allLevelLabels — ordered list
    // =========================================================================

    @Test
    fun `allLevelLabels returns one entry per level in ascending index order`() {
        val labels = GpuLockLogic.allLevelLabels(numLevels, mockFreqMap)
        assertThat(labels).hasSize(numLevels)
        assertThat(labels[0]).isEqualTo(0 to "900 MHz")
        assertThat(labels[4]).isEqualTo(4 to "100 MHz")
    }

    @Test
    fun `allLevelLabels is empty for zero levels`() {
        assertThat(GpuLockLogic.allLevelLabels(0, mockFreqMap)).isEmpty()
    }

    // =========================================================================
    // validatePair — floor/ceiling constraint enforcement
    // =========================================================================

    @Test
    fun `validatePair accepts equal floor and ceiling (pinned to same level)`() {
        // floorIdx == ceilingIdx means floor clock == ceiling clock — fully pinned. Valid.
        assertThat(GpuLockLogic.validatePair(floorIdx = 2, ceilingIdx = 2, numLevels)).isNull()
    }

    @Test
    fun `validatePair accepts floor greater than ceiling (floor is lower clock)`() {
        // floorIdx=3 (300 MHz floor), ceilingIdx=1 (700 MHz ceiling): floor <= ceiling. Valid.
        assertThat(GpuLockLogic.validatePair(floorIdx = 3, ceilingIdx = 1, numLevels)).isNull()
    }

    @Test
    fun `validatePair accepts stock defaults`() {
        val floor = GpuLockLogic.defaultFloorIdx(numLevels)   // 4
        val ceil = GpuLockLogic.defaultCeilingIdx()            // 0
        assertThat(GpuLockLogic.validatePair(floor, ceil, numLevels)).isNull()
    }

    @Test
    fun `validatePair rejects inverted pair where floor index is less than ceiling index`() {
        // floorIdx=1 (700 MHz), ceilingIdx=3 (300 MHz) means floor FASTER than ceiling — invalid.
        val error = GpuLockLogic.validatePair(floorIdx = 1, ceilingIdx = 3, numLevels)
        assertThat(error).isNotNull()
        assertThat(error).contains("Floor index 1")
        assertThat(error).contains("ceiling index 3")
    }

    @Test
    fun `validatePair rejects negative floor index`() {
        val error = GpuLockLogic.validatePair(floorIdx = -1, ceilingIdx = 0, numLevels)
        assertThat(error).isNotNull()
    }

    @Test
    fun `validatePair rejects floor index at or above numLevels`() {
        val error = GpuLockLogic.validatePair(floorIdx = 5, ceilingIdx = 0, numLevels)
        assertThat(error).isNotNull()
    }

    @Test
    fun `validatePair rejects ceiling index out of range`() {
        val error = GpuLockLogic.validatePair(floorIdx = 4, ceilingIdx = -1, numLevels)
        assertThat(error).isNotNull()
    }

    @Test
    fun `validatePair returns error for zero numLevels`() {
        val error = GpuLockLogic.validatePair(floorIdx = 0, ceilingIdx = 0, numLevels = 0)
        assertThat(error).isNotNull()
    }

    // =========================================================================
    // defaultFloorIdx / defaultCeilingIdx — reset-to-stock values
    // =========================================================================

    @Test
    fun `defaultFloorIdx returns numLevels minus one`() {
        assertThat(GpuLockLogic.defaultFloorIdx(numLevels)).isEqualTo(4)
        assertThat(GpuLockLogic.defaultFloorIdx(9)).isEqualTo(8)   // RP6
        assertThat(GpuLockLogic.defaultFloorIdx(14)).isEqualTo(13) // Odin 3
    }

    @Test
    fun `defaultFloorIdx clamps to zero for single level`() {
        assertThat(GpuLockLogic.defaultFloorIdx(1)).isEqualTo(0)
    }

    @Test
    fun `defaultCeilingIdx is always zero`() {
        assertThat(GpuLockLogic.defaultCeilingIdx()).isEqualTo(0)
    }

    @Test
    fun `reset defaults form a valid pair`() {
        val floor = GpuLockLogic.defaultFloorIdx(numLevels)
        val ceil = GpuLockLogic.defaultCeilingIdx()
        // Stock: floor=4 (100 MHz minimum constraint), ceiling=0 (allow 900 MHz max). Valid.
        assertThat(GpuLockLogic.validatePair(floor, ceil, numLevels)).isNull()
    }

    // =========================================================================
    // Write value correctness — the values sent to min/max_pwrlevel nodes
    // =========================================================================

    @Test
    fun `floor write sends floorIdx as string to min_pwrlevel`() {
        // min_pwrlevel = 3 → GPU cannot go below 300 MHz.
        val writeValue = 3.toString()
        assertThat(writeValue).isEqualTo("3")
        // Validate pair before writing: floor=3, ceiling=1 (700 MHz max) — valid.
        assertThat(GpuLockLogic.validatePair(3, 1, numLevels)).isNull()
    }

    @Test
    fun `ceiling write sends ceilingIdx as string to max_pwrlevel`() {
        // max_pwrlevel = 2 → GPU cannot exceed 500 MHz.
        val writeValue = 2.toString()
        assertThat(writeValue).isEqualTo("2")
        // Validate pair: floor=4 (100 MHz minimum), ceiling=2 (500 MHz max) — valid.
        assertThat(GpuLockLogic.validatePair(4, 2, numLevels)).isNull()
    }

    @Test
    fun `pinning GPU to single level sends same index for floor and ceiling`() {
        // Lock to 500 MHz: floor=2, ceiling=2.
        val idx = 2
        assertThat(GpuLockLogic.validatePair(idx, idx, numLevels)).isNull()
        assertThat(idx.toString()).isEqualTo("2")
    }

    // =========================================================================
    // effectiveNumLevels — matches ArsenalGpuAdvancedSection derivation
    // =========================================================================

    @Test
    fun `effectiveNumLevels uses freqMapSize when it is largest`() {
        val result = GpuLockLogic.effectiveNumLevels(
            freqMapSize = 9,
            currentMinPwrLevel = 3,
            currentMaxPwrLevel = 0,
            currentDefaultPwrLevel = 8,
        )
        // max(9, 3+1, 0+1, 8+1) = max(9, 9) = 9
        assertThat(result).isEqualTo(9)
    }

    @Test
    fun `effectiveNumLevels uses observed level plus one when larger than freqMapSize`() {
        val result = GpuLockLogic.effectiveNumLevels(
            freqMapSize = 5,
            currentMinPwrLevel = 13,
            currentMaxPwrLevel = 0,
            currentDefaultPwrLevel = null,
        )
        // max(5, 13+1, 0+1, 0+1) = 14
        assertThat(result).isEqualTo(14)
    }

    @Test
    fun `effectiveNumLevels is at least 1 for empty data`() {
        val result = GpuLockLogic.effectiveNumLevels(
            freqMapSize = 0,
            currentMinPwrLevel = null,
            currentMaxPwrLevel = null,
            currentDefaultPwrLevel = null,
        )
        assertThat(result).isEqualTo(1)
    }

    @Test
    fun `effectiveNumLevels handles Odin3 14 levels correctly`() {
        val result = GpuLockLogic.effectiveNumLevels(
            freqMapSize = 14,
            currentMinPwrLevel = 13,
            currentMaxPwrLevel = 0,
            currentDefaultPwrLevel = 4,
        )
        assertThat(result).isEqualTo(14)
    }

    @Test
    fun `effectiveNumLevels handles RP6 9 levels correctly`() {
        val result = GpuLockLogic.effectiveNumLevels(
            freqMapSize = 9,
            currentMinPwrLevel = 8,
            currentMaxPwrLevel = 0,
            currentDefaultPwrLevel = 3,
        )
        assertThat(result).isEqualTo(9)
    }
}
