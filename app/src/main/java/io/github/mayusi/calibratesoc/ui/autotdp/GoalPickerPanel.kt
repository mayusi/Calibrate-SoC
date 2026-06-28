package io.github.mayusi.calibratesoc.ui.autotdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.calibratesoc.data.autotdp.GoalParams
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.WorkloadContext
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

private val OBJECTIVE_GOALS = setOf(
    GoalProfile.TARGET_TEMP_CEILING,
    GoalProfile.TARGET_FPS_FLOOR,
    GoalProfile.TARGET_RUNTIME,
)

/**
 * The Smart-AutoTDP goal picker panel.
 *
 * Shows a PRIMARY row of 5 common goals and an EXPANDABLE "Advanced goals" section
 * below with the 3 objective modes (TARGET_TEMP_CEILING, TARGET_FPS_FLOOR,
 * TARGET_RUNTIME). When an objective goal is selected, a single per-mode slider is
 * revealed directly beneath the advanced chips.
 *
 * HONESTY: this widget controls the GOAL (user intent / config tier). If the goal is
 * AUTO and the daemon is running, [DetectedContextBadge] shows the classifier's
 * DETECTED context. For TARGET_FPS_FLOOR, an amber banner is shown when the engine
 * has degraded to Balanced because no real frame-rate source is available. For
 * TARGET_RUNTIME, the modelled projection line from the engine is rendered when
 * non-null.
 *
 * @param selectedGoal           Currently selected goal chip.
 * @param onSelectGoal           Called when the user taps a chip.
 * @param isRunning              True when the AutoTDP daemon is RUNNING.
 * @param detectedContext        The classifier's current DETECTED belief (AUTO mode).
 * @param activeGoal             The CONCRETE goal the daemon resolved to this tick.
 * @param fpsFloor               Current TARGET_FPS_FLOOR setpoint (one of FPS_FLOOR_STEPS).
 * @param tempCeilingC           Current TARGET_TEMP_CEILING setpoint (°C).
 * @param targetRuntimeHours     Current TARGET_RUNTIME setpoint (hours).
 * @param onSetFpsFloor          Called when the user moves the FPS floor slider.
 * @param onSetTempCeiling       Called when the user moves the temp ceiling slider.
 * @param onSetRuntimeHours      Called when the user moves the runtime slider.
 * @param fpsFloorDegraded       True when TARGET_FPS_FLOOR is active but degraded to Balanced.
 * @param runtimeProjectionNote  Modelled runtime projection string from the engine, or null.
 */
@Composable
fun GoalPickerPanel(
    selectedGoal: GoalProfile,
    onSelectGoal: (GoalProfile) -> Unit,
    isRunning: Boolean,
    detectedContext: WorkloadContext?,
    activeGoal: GoalProfile?,
    fpsFloor: Int,
    tempCeilingC: Int,
    targetRuntimeHours: Float,
    onSetFpsFloor: (Int) -> Unit,
    onSetTempCeiling: (Int) -> Unit,
    onSetRuntimeHours: (Float) -> Unit,
    fpsFloorDegraded: Boolean,
    runtimeProjectionNote: String?,
    modifier: Modifier = Modifier,
) {
    // Auto-expand the Advanced section when the persisted selection is an objective goal.
    var advancedExpanded by remember(selectedGoal) {
        mutableStateOf(selectedGoal in OBJECTIVE_GOALS)
    }

    ArsenalPanel(
        accent = AccentBar.Purple,
        title = "Goal mode",
        modifier = modifier,
    ) {
        // ── Primary 5-chip row ────────────────────────────────────────────────
        GoalChipRow(selected = selectedGoal, onSelect = onSelectGoal)

        Spacer(Modifier.height(Spacing.dense))
        Text(
            text = GoalProfileUi.goalDescription(selectedGoal),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )

        // ── Detected-context strip (AUTO only) ────────────────────────────────
        if (selectedGoal == GoalProfile.AUTO && isRunning && detectedContext != null && activeGoal != null) {
            Spacer(Modifier.height(Spacing.dense))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(Spacing.dense))
            DetectedContextBadge(
                detectedContext = detectedContext,
                activeGoal = activeGoal,
            )
        }

        // ── Advanced goals expandable section ─────────────────────────────────
        Spacer(Modifier.height(Spacing.group))
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(Spacing.dense))

        // Expandable header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = Spacing.dense),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
        ) {
            Text(
                text = "Advanced goals",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFAAAAAA),
                modifier = Modifier.weight(1f),
                letterSpacing = 0.04.sp,
            )
            Text(
                text = if (advancedExpanded) "−" else "+",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = AccentBar.Purple,
            )
        }

        if (advancedExpanded) {
            Spacer(Modifier.height(Spacing.dense))

            // 3-chip row for objective goals
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
            ) {
                OBJECTIVE_GOALS.forEach { goal ->
                    GoalChip(
                        goal = goal,
                        isSelected = goal == selectedGoal,
                        onSelect = { onSelectGoal(goal) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Per-mode slider — revealed only when the selected goal is an objective mode
            if (selectedGoal in OBJECTIVE_GOALS) {
                Spacer(Modifier.height(Spacing.group))

                when (selectedGoal) {
                    GoalProfile.TARGET_TEMP_CEILING -> {
                        TempCeilingSlider(
                            tempCeilingC = tempCeilingC,
                            onSetTempCeiling = onSetTempCeiling,
                        )
                    }
                    GoalProfile.TARGET_FPS_FLOOR -> {
                        FpsFloorSlider(
                            fpsFloor = fpsFloor,
                            onSetFpsFloor = onSetFpsFloor,
                        )
                        // Honesty banner: degraded to Balanced when no FPS source available
                        if (fpsFloorDegraded) {
                            Spacer(Modifier.height(Spacing.dense))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                            ) {
                                StatusPill(text = "USING BALANCED", accent = AccentBar.Amber)
                                Text(
                                    text = "FPS floor needs a real frame-rate source; using Balanced.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFBBBBBB),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    GoalProfile.TARGET_RUNTIME -> {
                        RuntimeSlider(
                            targetRuntimeHours = targetRuntimeHours,
                            onSetRuntimeHours = onSetRuntimeHours,
                        )
                        // Modelled projection line from the engine
                        Spacer(Modifier.height(Spacing.dense))
                        if (runtimeProjectionNote != null) {
                            Text(
                                text = runtimeProjectionNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF999999),
                            )
                        } else {
                            Text(
                                text = "Projection will appear once running.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF666666),
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

// ── Sliders ───────────────────────────────────────────────────────────────────

@Composable
private fun TempCeilingSlider(
    tempCeilingC: Int,
    onSetTempCeiling: (Int) -> Unit,
) {
    Column {
        Text(
            text = "Temperature ceiling: ${tempCeilingC}°C",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFCCCCCC),
        )
        Spacer(Modifier.height(Spacing.dense))
        Slider(
            value = tempCeilingC.toFloat(),
            onValueChange = { onSetTempCeiling(it.toInt()) },
            valueRange = GoalParams.TEMP_CEILING_MIN_C.toFloat()..GoalParams.TEMP_CEILING_MAX_C.toFloat(),
            steps = GoalParams.TEMP_CEILING_MAX_C - GoalParams.TEMP_CEILING_MIN_C - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FpsFloorSlider(
    fpsFloor: Int,
    onSetFpsFloor: (Int) -> Unit,
) {
    val steps = GoalParams.FPS_FLOOR_STEPS
    val currentIndex = steps.indexOfFirst { it == fpsFloor }.coerceAtLeast(0)

    Column {
        Text(
            text = "Minimum FPS: $fpsFloor",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFCCCCCC),
        )
        Spacer(Modifier.height(Spacing.dense))
        Slider(
            value = currentIndex.toFloat(),
            onValueChange = { rawIndex ->
                val snapped = steps.getOrElse(rawIndex.toInt()) { steps.last() }
                onSetFpsFloor(snapped)
            },
            valueRange = 0f..(steps.size - 1).toFloat(),
            steps = steps.size - 2,
            modifier = Modifier.fillMaxWidth(),
        )
        // Tick labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            steps.forEach { fps ->
                Text(
                    text = fps.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fps == fpsFloor) AccentBar.Emerald else Color(0xFF666666),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RuntimeSlider(
    targetRuntimeHours: Float,
    onSetRuntimeHours: (Float) -> Unit,
) {
    // Snap to 0.5h increments
    val totalHalfHours = ((GoalParams.RUNTIME_HOURS_MAX - GoalParams.RUNTIME_HOURS_MIN) / 0.5f).toInt()

    Column {
        Text(
            text = "Target runtime: ${"%.1f".format(targetRuntimeHours)} h",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFCCCCCC),
        )
        Spacer(Modifier.height(Spacing.dense))
        Slider(
            value = targetRuntimeHours,
            onValueChange = { raw ->
                // Snap to nearest 0.5h
                val snapped = (kotlin.math.round(raw * 2) / 2f)
                    .coerceIn(GoalParams.RUNTIME_HOURS_MIN, GoalParams.RUNTIME_HOURS_MAX)
                onSetRuntimeHours(snapped)
            },
            valueRange = GoalParams.RUNTIME_HOURS_MIN..GoalParams.RUNTIME_HOURS_MAX,
            steps = totalHalfHours - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Primary chip row ──────────────────────────────────────────────────────────

/**
 * Row of 5 common goal chips in a 5-wide horizontal layout.
 * Scrolls with the parent LazyColumn — no nested scroll needed.
 */
@Composable
private fun GoalChipRow(
    selected: GoalProfile,
    onSelect: (GoalProfile) -> Unit,
) {
    val goals = listOf(
        GoalProfile.AUTO,
        GoalProfile.BALANCED_SMART,
        GoalProfile.MAX_FPS,
        GoalProfile.COOL_QUIET,
        GoalProfile.BATTERY_SAVER,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
    ) {
        goals.forEach { goal ->
            GoalChip(
                goal = goal,
                isSelected = goal == selected,
                onSelect = { onSelect(goal) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GoalChip(
    goal: GoalProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = goalChipAccent(goal)
    Box(
        modifier = modifier
            .background(
                if (isSelected) accent.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                RoundedCornerShape(4.dp),
            )
            .border(
                1.dp,
                if (isSelected) accent else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(4.dp),
            )
            .clickable { onSelect() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = GoalProfileUi.goalShortLabel(goal),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) accent else Color(0xFF888888),
            letterSpacing = 0.05.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Accent color keyed to each goal for the chip border + bg tint. */
internal fun goalChipAccent(goal: GoalProfile): Color = when (goal) {
    GoalProfile.AUTO -> AccentBar.Purple
    GoalProfile.BALANCED_SMART -> AccentBar.Blue
    GoalProfile.MAX_FPS -> AccentBar.Red
    GoalProfile.COOL_QUIET -> AccentBar.Emerald
    GoalProfile.BATTERY_SAVER -> AccentBar.Amber
    // UNIT 4 — objective goal modes. Emerald is the active/healthy accent (NOT Green).
    GoalProfile.TARGET_TEMP_CEILING -> AccentBar.Amber
    GoalProfile.TARGET_FPS_FLOOR -> AccentBar.Emerald
    GoalProfile.TARGET_RUNTIME -> AccentBar.Blue
}

/**
 * The DETECTED honesty badge.
 *
 * Renders: "Detected: Heavy 3D -> Balanced smart"
 *
 * Styled with a BLUE neutral pill (not Emerald) to distinguish classifier BELIEF
 * from MEASURED proof-of-effect. The pill label says "DETECTED" — never "MEASURED".
 *
 * INVARIANT: this composable must never be rendered with Emerald accent or
 * labelled "MEASURED". The DETECTED tier is a classifier belief (GPU busy% +
 * foreground package heuristic); it is NOT a probe-backed measurement.
 */
@Composable
fun DetectedContextBadge(
    detectedContext: WorkloadContext,
    activeGoal: GoalProfile,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        // Blue pill = DETECTED (classifier belief), not Emerald (measured).
        StatusPill(text = "DETECTED", accent = AccentBar.Blue)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = GoalProfileUi.autoDetectedLine(detectedContext, activeGoal),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFCCCCCC),
            )
            Text(
                text = "Classifier belief after hysteresis — not a measurement.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF666666),
            )
        }
    }
}
