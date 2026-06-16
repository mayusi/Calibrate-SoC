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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.WorkloadContext
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * The 5-mode Smart-AutoTDP goal picker panel.
 *
 * Replaces / augments the legacy 3-profile Efficiency/Balanced/Battery chips.
 * The picker is a row of 5 chips (Auto | Balanced smart | Max FPS | Cool & quiet |
 * Battery saver) followed by a one-line description of the selected mode.
 *
 * HONESTY: this widget controls the GOAL (user intent / config tier). If the goal
 * is AUTO and the daemon is running, [DetectedContextBadge] below it shows the
 * classifier's DETECTED context. These are styled differently from MEASURED proof.
 *
 * @param selectedGoal    Currently selected goal chip.
 * @param onSelectGoal    Called when the user taps a chip.
 * @param isRunning       True when the AutoTDP daemon is RUNNING.
 * @param detectedContext The classifier's current DETECTED belief (non-null when
 *                        AUTO is running and a tick has completed).
 * @param activeGoal      The CONCRETE goal the daemon resolved to this tick (non-null
 *                        when RUNNING). For non-AUTO goals this equals [selectedGoal].
 */
@Composable
fun GoalPickerPanel(
    selectedGoal: GoalProfile,
    onSelectGoal: (GoalProfile) -> Unit,
    isRunning: Boolean,
    detectedContext: WorkloadContext?,
    activeGoal: GoalProfile?,
    modifier: Modifier = Modifier,
) {
    ArsenalPanel(
        accent = AccentBar.Purple,
        title = "Goal mode",
        modifier = modifier,
    ) {
        GoalChipRow(selected = selectedGoal, onSelect = onSelectGoal)

        Spacer(Modifier.height(Spacing.dense))
        Text(
            text = GoalProfileUi.goalDescription(selectedGoal),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )

        // Detected-context strip — shown only when AUTO is selected and the
        // daemon is running (so a real classifier tick has occurred).
        if (selectedGoal == GoalProfile.AUTO && isRunning && detectedContext != null && activeGoal != null) {
            Spacer(Modifier.height(Spacing.dense))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(Spacing.dense))
            DetectedContextBadge(
                detectedContext = detectedContext,
                activeGoal = activeGoal,
            )
        }
    }
}

/**
 * Row of 5 goal chips in a 2-3 or 5-wide horizontal layout.
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

    // Lay them out in a 5-wide row; short labels keep them readable on landscape.
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
