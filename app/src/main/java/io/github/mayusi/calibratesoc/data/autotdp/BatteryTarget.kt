package io.github.mayusi.calibratesoc.data.autotdp

/**
 * Battery-Target mode: pure cap math (no Android, fully unit-testable).
 *
 * The user says "I want N more hours." From:
 *   - remaining battery capacity (mAh from BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
 *   - live battery voltage (mV, converted from Telemetry.batteryVoltageUv)
 *   - current draw (mW, from Telemetry.batteryDrawMilliW)
 *   - the device OPP table (from TdpCaps.bigClusterOppStepsKhz)
 *
 * …compute the watt budget that would sustain [targetHours], map it to the nearest
 * lower OPP step on the big cluster, and honestly report whether the target is
 * achievable or not.
 *
 * HONESTY is the core contract:
 *   - If even the lowest OPP step draws too much to hit the target, say so explicitly.
 *   - Always report the ACTUAL estimated life at current draw.
 *   - Never return a cap that claims to achieve the target when it cannot.
 *
 * Math:
 *   remainingWh   = (remainingCapacityMah / 1000.0) * (batteryVoltageMv / 1000.0)
 *   budgetW       = remainingWh / targetHours
 *   mappedCapKhz  = largest OPP step whose estimated draw ≤ budgetW
 *
 * OPP-to-draw mapping: We use a linear interpolation across the OPP table as a
 * first approximation (same conservative proxy as AutoTdpEngine.deriveBudgetCap).
 * The EfficiencyCurve sweep provides a calibrated curve when available; the BatteryTarget
 * accepts an optional [drawCurve] of (capKhz → estimatedDrawMw) pairs for a better mapping.
 */
object BatteryTarget {

    /**
     * Result of the battery-target cap computation.
     *
     * @param remainingWh        Estimated remaining energy in watt-hours.
     * @param currentDrawW       Current discharge rate in watts (positive = discharging).
     * @param actualHoursAtCurrent  Estimated life at the CURRENT draw. Honest even when the
     *                              target is not achievable.
     * @param budgetW            Watt budget that would achieve [targetHours]. May be negative
     *                           when [targetHours] exceeds remaining capacity entirely.
     * @param mappedCapKhz       Nearest OPP step at or below the draw budget. Null when
     *                           the OPP table is empty or the budget is negative.
     * @param achievable         True only when [mappedCapKhz] is non-null AND the budget
     *                           is positive AND there is an OPP step low enough to hit it.
     * @param honestyNote        Human-readable explanation (always shown, regardless of achievable).
     *                           Examples:
     *                           "At current draw (4.2 W) you'll get ~2h40m; 3h needs ≤3.5 W → cap big to 1536 MHz."
     *                           "Target 5h is not achievable — even at the lowest cap (499 MHz) estimated draw is 2.8 W
     *                            (maximum ~3h42m). Recommend charging."
     */
    data class BatteryTargetResult(
        val remainingWh: Double,
        val currentDrawW: Double,
        val actualHoursAtCurrent: Double?,
        val budgetW: Double,
        val mappedCapKhz: Int?,
        val achievable: Boolean,
        val honestyNote: String,
    )

    /**
     * Compute the big-cluster cap that would achieve [targetHours] of battery life.
     *
     * @param targetHours           The user's desired battery life in hours. Must be > 0.
     * @param remainingCapacityMah  Remaining charge in milliamp-hours (from BatteryManager
     *                              BATTERY_PROPERTY_CHARGE_COUNTER). Must be > 0 to compute.
     * @param batteryVoltageMv      Live battery voltage in millivolts (from Telemetry.batteryVoltageUv
     *                              / 1000). Used to convert mAh → Wh. Must be > 0.
     * @param currentDrawMw         Current steady-state discharge rate in milliwatts
     *                              (from Telemetry.batteryDrawMilliW). Must be > 0 to compute life.
     * @param caps                  Device OPP envelope. bigClusterOppStepsKhz must be non-empty
     *                              for a cap to be returned.
     * @param drawCurve             Optional measured OPP→draw map from [EfficiencyCurveSweep].
     *                              Keys are capKhz (ascending), values are estimated draw in mW.
     *                              When provided, the OPP mapping uses real measured draw values
     *                              rather than the linear heuristic.
     * @return                      [BatteryTargetResult] — always honest, never fabricates achievability.
     */
    fun capForTarget(
        targetHours: Double,
        remainingCapacityMah: Int,
        batteryVoltageMv: Int,
        currentDrawMw: Long,
        caps: TdpCaps,
        drawCurve: Map<Int, Long> = emptyMap(),
        /**
         * Unit 3: optional fitted [PowerModel.FitResult] from this session's measured
         * (cap, draw) pairs. When non-null the OPP→draw mapping uses the non-linear
         * draw ∝ f^n model instead of the linear heuristic, giving a more accurate
         * runtime estimate on real DVFS curves. When null (< 2 measured points yet)
         * the original linear proxy is used, unchanged. [drawCurve] takes priority
         * when non-empty (measured sweep is the gold standard).
         */
        powerModelFit: PowerModel.FitResult? = null,
    ): BatteryTargetResult {
        require(targetHours > 0) { "targetHours must be > 0, got $targetHours" }

        // ── Energy remaining ──────────────────────────────────────────────────
        // remainingWh = mAh / 1000 * V = Wh
        val remainingWh = (remainingCapacityMah / 1_000.0) * (batteryVoltageMv / 1_000.0)

        // ── Current draw ──────────────────────────────────────────────────────
        val currentDrawW = currentDrawMw / 1_000.0

        // ── Actual life at current draw ───────────────────────────────────────
        val actualHours: Double? = when {
            currentDrawW <= 0.0 -> null // charging or no data
            remainingWh <= 0.0 -> null  // no remaining capacity reading
            else -> remainingWh / currentDrawW
        }

        // ── Budget needed for the target ──────────────────────────────────────
        // budgetW = Wh / hours → W we can spend
        val budgetW = if (remainingWh > 0.0) remainingWh / targetHours else 0.0
        val budgetMw = (budgetW * 1_000.0).toLong()

        // ── OPP mapping ───────────────────────────────────────────────────────
        val steps = caps.bigClusterOppStepsKhz.sorted()
        val mappedCapKhz: Int? = when {
            steps.isEmpty() -> null
            budgetMw <= 0   -> null
            drawCurve.isNotEmpty() -> {
                // Use measured curve: find highest cap whose draw <= budget
                steps
                    .filter { capKhz ->
                        val estimatedDraw = drawCurve[capKhz]
                            ?: interpolateDraw(capKhz, drawCurve)
                        estimatedDraw != null && estimatedDraw <= budgetMw
                    }
                    .maxOrNull()
            }
            else -> {
                // Unit 3: prefer PowerModel fit (non-linear draw ∝ f^n) when available.
                // Falls back to the original linear heuristic when powerModelFit is null.
                // drawCurve (measured sweep) takes priority above — only reaches here when empty.
                val topKhz = steps.last().toDouble()
                steps
                    .filter { capKhz ->
                        val estimatedDrawMw: Long = if (powerModelFit != null) {
                            PowerModel.estimateDrawMilliW(
                                capKhz = capKhz,
                                fitResult = powerModelFit,
                                referenceCapKhz = steps.last(),
                                referenceDrawMilliW = currentDrawMw,
                            )?.drawMilliW ?: ((capKhz / topKhz) * currentDrawMw).toLong()
                        } else {
                            // Original linear proxy — unchanged behaviour when no fit.
                            ((capKhz / topKhz) * currentDrawMw).toLong()
                        }
                        estimatedDrawMw <= budgetMw
                    }
                    .maxOrNull()
            }
        }

        // ── Check achievability honestly ──────────────────────────────────────
        // achievable = we found a real OPP step that would hit the budget.
        val achievable = mappedCapKhz != null && budgetW > 0.0

        // ── Lowest-cap draw estimate (for the "not achievable" message) ───────
        val lowestCapEstimatedHours: Double? = if (steps.isNotEmpty() && currentDrawW > 0.0) {
            val lowestCap = steps.first()
            val lowestFraction = lowestCap / steps.last().toDouble()
            val lowestDrawW = if (drawCurve.isNotEmpty()) {
                val lowestDraw = drawCurve[lowestCap] ?: interpolateDraw(lowestCap, drawCurve)
                lowestDraw?.let { it / 1_000.0 }
            } else {
                lowestFraction * currentDrawW
            }
            if (lowestDrawW != null && lowestDrawW > 0.0) remainingWh / lowestDrawW else null
        } else null

        // ── Honesty note ──────────────────────────────────────────────────────
        val note = buildHonestyNote(
            targetHours = targetHours,
            currentDrawW = currentDrawW,
            actualHours = actualHours,
            budgetW = budgetW,
            mappedCapKhz = mappedCapKhz,
            achievable = achievable,
            lowestCapEstimatedHours = lowestCapEstimatedHours,
            steps = steps,
        )

        return BatteryTargetResult(
            remainingWh = remainingWh,
            currentDrawW = currentDrawW,
            actualHoursAtCurrent = actualHours,
            budgetW = budgetW,
            mappedCapKhz = mappedCapKhz,
            achievable = achievable,
            honestyNote = note,
        )
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Linear interpolation between the two nearest measured draw-curve entries.
     * Returns null when the curve has fewer than 2 entries or no bracketing points.
     */
    private fun interpolateDraw(capKhz: Int, drawCurve: Map<Int, Long>): Long? {
        val sorted = drawCurve.entries.sortedBy { it.key }
        if (sorted.size < 2) return sorted.firstOrNull()?.value
        val lo = sorted.lastOrNull { it.key <= capKhz } ?: return sorted.first().value
        val hi = sorted.firstOrNull { it.key > capKhz } ?: return sorted.last().value
        val fraction = (capKhz - lo.key).toDouble() / (hi.key - lo.key)
        return (lo.value + fraction * (hi.value - lo.value)).toLong()
    }

    private fun buildHonestyNote(
        targetHours: Double,
        currentDrawW: Double,
        actualHours: Double?,
        budgetW: Double,
        mappedCapKhz: Int?,
        achievable: Boolean,
        lowestCapEstimatedHours: Double?,
        steps: List<Int>,
    ): String = buildString {
        // Always show actual life estimate first.
        if (actualHours != null && currentDrawW > 0.0) {
            append("At current draw (${formatW(currentDrawW)}) you'll get ~${formatHours(actualHours)}. ")
        } else if (currentDrawW <= 0.0) {
            append("Device appears to be charging — no discharge estimate available. ")
        } else {
            append("Remaining capacity data unavailable — cannot estimate current life. ")
        }

        val targetLabel = "target ${formatHours(targetHours)}"

        if (achievable && mappedCapKhz != null) {
            val capMhz = mappedCapKhz / 1000
            append("$targetLabel needs ≤${formatW(budgetW)} → cap big cluster to ${capMhz} MHz.")
        } else if (budgetW <= 0.0 || remainingCapacityIsZero(actualHours, currentDrawW)) {
            append("$targetLabel is not achievable — no remaining capacity data or invalid budget.")
        } else {
            // Not achievable — tell the user what the maximum achievable life actually is.
            val maxHoursNote = if (lowestCapEstimatedHours != null) {
                val lowestMhz = steps.firstOrNull()?.let { it / 1000 }
                if (lowestMhz != null) {
                    "even at the lowest cap (${lowestMhz} MHz) estimated life is ~${formatHours(lowestCapEstimatedHours)}"
                } else {
                    "estimated maximum ~${formatHours(lowestCapEstimatedHours)}"
                }
            } else {
                "no achievable cap in the OPP table"
            }
            append("$targetLabel is NOT achievable — $maxHoursNote. Consider reducing load or charging.")
        }
    }

    private fun remainingCapacityIsZero(actualHours: Double?, currentDrawW: Double) =
        actualHours == null && currentDrawW > 0.0

    // ─── Formatting helpers (pure) ────────────────────────────────────────────

    internal fun formatHours(h: Double): String {
        val totalMin = (h * 60).toLong()
        val hr = totalMin / 60
        val min = totalMin % 60
        return if (hr > 0) "${hr}h${if (min > 0) "${"${min}m"}" else ""}" else "${min}m"
    }

    internal fun formatW(w: Double): String = "%.1fW".format(w)
}
