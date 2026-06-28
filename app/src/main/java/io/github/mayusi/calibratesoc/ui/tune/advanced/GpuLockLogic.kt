package io.github.mayusi.calibratesoc.ui.tune.advanced

/**
 * Pure-Kotlin logic for the Manual GPU Lock feature.
 *
 * Adreno kgsl power-level index convention (counterintuitive):
 *   index 0      = highest GPU clock (max performance)
 *   index N-1    = lowest GPU clock (power-save / stock floor)
 *
 * So:
 *   min_pwrlevel (floor index)   — higher index = lower clock = more restrictive floor
 *   max_pwrlevel (ceiling index) — lower index  = higher clock = more permissive ceiling
 *
 * Valid invariant: floorIdx >= ceilingIdx
 *   (ceiling must allow a clock >= the floor clock, meaning ceilingIdx <= floorIdx)
 *
 * Stock/reset defaults:
 *   floor   → numLevels - 1  (lowest clock, most permissive — driver free to go up)
 *   ceiling → 0              (allow max clock)
 */
object GpuLockLogic {

    /**
     * Build the human-readable MHz label for a power level index.
     *
     * @param idx         Power level index (0 = max clock).
     * @param freqMap     Map of index → Hz from [AdrenoExtrasProbe.pwrLevelFreqHz].
     * @return            e.g. "700 MHz", or just "6" if the Hz entry is missing.
     */
    fun levelLabel(idx: Int, freqMap: Map<Int, Long>): String {
        val hz = freqMap[idx] ?: return idx.toString()
        val mhz = (hz / 1_000_000L).toInt()
        return "$mhz MHz"
    }

    /**
     * Build ordered labels for all levels, highest-to-lowest clock (index 0 first).
     * This is the natural display order for a "ceiling" picker.
     */
    fun allLevelLabels(numLevels: Int, freqMap: Map<Int, Long>): List<Pair<Int, String>> =
        (0 until numLevels).map { idx -> idx to levelLabel(idx, freqMap) }

    /**
     * Validate a proposed (floorIdx, ceilingIdx) pair.
     *
     * @return null if valid, or an error message string if invalid.
     */
    fun validatePair(floorIdx: Int, ceilingIdx: Int, numLevels: Int): String? {
        if (numLevels <= 0) return "No power levels available."
        if (floorIdx < 0 || floorIdx >= numLevels)
            return "Floor index $floorIdx out of range 0..${numLevels - 1}."
        if (ceilingIdx < 0 || ceilingIdx >= numLevels)
            return "Ceiling index $ceilingIdx out of range 0..${numLevels - 1}."
        // Floor index must be >= ceiling index so that floor clock <= ceiling clock.
        if (floorIdx < ceilingIdx)
            return "Floor clock must be ≤ ceiling clock. " +
                "(Floor index $floorIdx < ceiling index $ceilingIdx means floor is faster than ceiling — invalid.)"
        return null
    }

    /**
     * Default (stock) floor index = highest index = lowest clock.
     * The driver is free to run any clock up to its own max, so this is the
     * most permissive floor (no real constraint).
     */
    fun defaultFloorIdx(numLevels: Int): Int = (numLevels - 1).coerceAtLeast(0)

    /**
     * Default (stock) ceiling index = 0 = allow max clock.
     */
    fun defaultCeilingIdx(): Int = 0

    /**
     * Derive the effective number of levels from the extras probe.
     *
     * Mirrors the calculation in ArsenalGpuAdvancedSection so both UIs are consistent.
     */
    fun effectiveNumLevels(
        freqMapSize: Int,
        currentMinPwrLevel: Int?,
        currentMaxPwrLevel: Int?,
        currentDefaultPwrLevel: Int?,
    ): Int = freqMapSize
        .coerceAtLeast(
            maxOf(
                currentMinPwrLevel ?: 0,
                currentMaxPwrLevel ?: 0,
                currentDefaultPwrLevel ?: 0,
            ) + 1,
        )
        .coerceAtLeast(1)
}
