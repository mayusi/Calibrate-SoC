package io.github.mayusi.calibratesoc.ui.autotdp

import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.WorkloadContext

/**
 * Pure UI helpers for the Smart-AutoTDP goal picker and detected-context display.
 *
 * All functions are pure (no Android, no I/O) and unit-testable.
 *
 * HONESTY CONTRACT:
 *  - [goalLabel] / [goalDescription] describe the GOAL (user intent / config tier).
 *  - [detectedContextLabel] / [detectedContextShort] describe DETECTED context
 *    (classifier belief, not a measurement). These must never be presented with
 *    the same visual treatment as MEASURED proof-of-effect fields.
 *  - [autoDetectedLine] composes the "Detected: <context> -> <goal>" string.
 */
object GoalProfileUi {

    /**
     * Short user-facing name for a [GoalProfile].
     * Sentence case, no emoji, no abbreviations.
     */
    fun goalLabel(goal: GoalProfile): String = when (goal) {
        GoalProfile.AUTO -> "Auto"
        GoalProfile.BALANCED_SMART -> "Balanced smart"
        GoalProfile.MAX_FPS -> "Max FPS"
        GoalProfile.COOL_QUIET -> "Cool & quiet"
        GoalProfile.BATTERY_SAVER -> "Battery saver"
    }

    /**
     * One-line plain-language description of what each goal does.
     * Shown below the picker chip to help the user choose.
     */
    fun goalDescription(goal: GoalProfile): String = when (goal) {
        GoalProfile.AUTO ->
            "Reads the workload and picks a goal automatically each tick."
        GoalProfile.BALANCED_SMART ->
            "Best sustainable FPS with good thermals — holds the efficiency knee. Default."
        GoalProfile.MAX_FPS ->
            "Never bottleneck the game; only backs off to dodge the thermal kill."
        GoalProfile.COOL_QUIET ->
            "Lowest temps and fan noise; accepts some FPS reduction."
        GoalProfile.BATTERY_SAVER ->
            "Maximum battery life while staying responsive; applies a hard power ceiling."
    }

    /**
     * Human-readable label for a detected [WorkloadContext].
     * Used in the "Detected: <X>" line.
     */
    fun detectedContextLabel(context: WorkloadContext): String = when (context) {
        WorkloadContext.IDLE -> "Idle"
        WorkloadContext.VIDEO -> "Video"
        WorkloadContext.LIGHT_GAME -> "Light game"
        WorkloadContext.HEAVY_GAME -> "Heavy 3D"
        WorkloadContext.UNKNOWN -> "Unknown"
    }

    /**
     * Short classifier-belief label for inline use (e.g. HUD).
     */
    fun detectedContextShort(context: WorkloadContext): String = when (context) {
        WorkloadContext.IDLE -> "IDLE"
        WorkloadContext.VIDEO -> "VIDEO"
        WorkloadContext.LIGHT_GAME -> "LT GAME"
        WorkloadContext.HEAVY_GAME -> "HVY 3D"
        WorkloadContext.UNKNOWN -> "UNKNWN"
    }

    /**
     * Compose the "Detected: <context> -> <goal>" line shown when AUTO is running.
     *
     * @param detectedContext the classifier's committed belief ([WorkloadContext]).
     * @param activeGoal      the CONCRETE goal the classifier resolved to (not AUTO).
     */
    fun autoDetectedLine(detectedContext: WorkloadContext, activeGoal: GoalProfile): String =
        "Detected: ${detectedContextLabel(detectedContext)} -> ${goalLabel(activeGoal)}"

    /**
     * Short HUD goal label (fits in tight space).
     */
    fun goalShortLabel(goal: GoalProfile): String = when (goal) {
        GoalProfile.AUTO -> "AUTO"
        GoalProfile.BALANCED_SMART -> "BAL"
        GoalProfile.MAX_FPS -> "MAX"
        GoalProfile.COOL_QUIET -> "COOL"
        GoalProfile.BATTERY_SAVER -> "BATT"
    }
}
