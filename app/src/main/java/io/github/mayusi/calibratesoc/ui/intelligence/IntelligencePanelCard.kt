package io.github.mayusi.calibratesoc.ui.intelligence

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.monitor.BatteryEstimate
import io.github.mayusi.calibratesoc.data.monitor.EstimateBasis
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.MetricTile
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Battery & Thermal Intelligence card — surfaces existing computations.
 *
 * Three elements, all read-only from existing computation paths:
 *
 *  1. Time-to-empty  — from [BatteryEstimate] (already in DashboardViewModel).
 *     The parent DashboardScreen passes [batteryEstimate] directly to avoid
 *     a duplicate charge-counter binder call. Honest basis labels respected.
 *
 *  2. Thermal headroom gauge  — live °C-until-throttle from
 *     [IntelligencePanelViewModel.thermalHeadroom]. Colored bar: Emerald
 *     when headroom > 20 °C, Amber 10–20 °C, Red < 10 °C. Honest when
 *     temps are unavailable (shows "temp unavailable").
 *
 *  3. Efficiency win  — surfaces [IntelligencePanelViewModel.efficiencyWin].
 *     MEASURED: shows "Cap big cluster at X MHz → ~N% less draw [MEASURED]"
 *     with a one-tap Apply button.
 *     ESTIMATED: shows "~N% estimated draw reduction [ESTIMATED]" without
 *     an apply button — never claims a measured win without data.
 *     UNAVAILABLE: efficiency section is omitted.
 *
 * [batteryEstimate] is passed from the parent DashboardScreen (which already
 * holds it from DashboardViewModel) to avoid duplicating the BatteryChargeReader
 * binder call. The remaining state is owned by [IntelligencePanelViewModel].
 *
 * @param batteryEstimate Live estimate from DashboardViewModel — never null,
 *   but [BatteryEstimate.basis] may be [EstimateBasis.INSUFFICIENT_DATA] when
 *   the device does not expose a charge counter. Honesty is preserved.
 */
@Composable
fun IntelligencePanelCard(
    batteryEstimate: BatteryEstimate,
    viewModel: IntelligencePanelViewModel = hiltViewModel(),
) {
    val thermalHeadroom by viewModel.thermalHeadroom.collectAsStateWithLifecycle()
    val efficiencyWin by viewModel.efficiencyWin.collectAsStateWithLifecycle()
    val applyResult by viewModel.applyResult.collectAsStateWithLifecycle()

    ArsenalPanel(accent = AccentBar.Amber, title = "BATTERY & THERMAL INTELLIGENCE") {

        // ── 1. Time-to-empty ─────────────────────────────────────────────────
        TimeToEmptySection(batteryEstimate)

        // ── 2. Thermal headroom gauge ────────────────────────────────────────
        Spacer(Modifier.height(Spacing.group))
        ThermalHeadroomSection(thermalHeadroom)

        // ── 3. Efficiency win ────────────────────────────────────────────────
        if (efficiencyWin !is EfficiencyWinState.Unavailable) {
            Spacer(Modifier.height(Spacing.group))
            EfficiencyWinSection(
                state = efficiencyWin,
                onApply = { viewModel.applyEfficiencyKnee() },
            )
        }

        // Footer caveat — honest about the panel's approximate nature
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "ESTIMATES VARY WITH WORKLOAD — AT THIS LOAD ONLY",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF555566),
            letterSpacing = 0.06.sp,
        )
    }

    // Apply result banner (outside the panel, appears as a dialog)
    applyResult?.let { result ->
        ApplyResultDialog(
            result = result,
            onDismiss = { viewModel.clearApplyResult() },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  1. Time-to-empty section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TimeToEmptySection(estimate: BatteryEstimate) {
    // Section label
    Text(
        "TIME REMAINING",
        style = MaterialTheme.typography.labelSmall,
        color = AccentBar.Amber,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.08.sp,
    )
    Spacer(Modifier.height(Spacing.dense))

    when (estimate.basis) {
        EstimateBasis.INSUFFICIENT_DATA -> {
            // Honest: do NOT show a number. The device doesn't expose a charge counter.
            StatusPill(
                text = "NEED MORE DATA",
                accent = AccentBar.Neutral,
            )
            Text(
                "Battery time estimate requires a charge counter reading — not available on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF888888),
            )
        }

        EstimateBasis.CHARGING -> {
            // Device is plugged in — no discharge estimate makes sense.
            val drawLabel = estimate.watts?.let { " · %.1f W draw".format(it) } ?: ""
            StatusPill(
                text = "CHARGING$drawLabel",
                accent = AccentBar.Emerald,
            )
        }

        EstimateBasis.LIVE_DRAW -> {
            val hours = estimate.hoursRemaining
            val timeLabel: String = when {
                hours == null -> "—"
                hours >= 1.0  -> {
                    val h = hours.toInt()
                    val m = ((hours - h) * 60).toInt()
                    if (m > 0) "~${h}h ${m}m" else "~${h}h"
                }
                else -> "~${(hours * 60).toInt()} min"
            }
            val drawLabel = estimate.watts?.let { "at %.1f W".format(it) }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                )
                if (drawLabel != null) {
                    Text(
                        drawLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF888888),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text(
                    "[LIVE]",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBar.Emerald,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  2. Thermal headroom gauge
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ThermalHeadroomSection(state: ThermalHeadroomState) {
    Text(
        "THERMAL HEADROOM",
        style = MaterialTheme.typography.labelSmall,
        color = AccentBar.Amber,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.08.sp,
    )
    Spacer(Modifier.height(Spacing.dense))

    when (state) {
        is ThermalHeadroomState.Unavailable -> {
            StatusPill(text = "TEMP UNAVAILABLE", accent = AccentBar.Neutral)
            Text(
                "No thermal zone readings available — temps may be restricted on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF888888),
            )
        }

        is ThermalHeadroomState.Available -> {
            val headroom = state.headroomC
            val peakC = state.peakZoneTempC
            val killC = state.killTempC

            // Color: Emerald when lots of headroom, Amber when shrinking, Red when critical
            val gaugeAccent = when {
                headroom < 10f -> AccentBar.Red
                headroom < 20f -> AccentBar.Amber
                else           -> AccentBar.Emerald
            }

            // Fraction for the gauge bar: 0 = at kill temp, 1 = cool
            // Clamp to [0, 1]; we treat killC as the 0-point
            val fraction = (headroom / killC).coerceIn(0f, 1f)
            val animatedFraction by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(durationMillis = 600),
                label = "headroomGauge",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetricTile(
                    label = "PEAK ZONE",
                    value = "%.0f".format(peakC),
                    unit = "°C",
                    accent = gaugeAccent,
                    modifier = Modifier.width(90.dp),
                )
                MetricTile(
                    label = "HEADROOM",
                    value = "%.0f".format(headroom),
                    unit = "°C",
                    accent = gaugeAccent,
                    modifier = Modifier.width(90.dp),
                )
                MetricTile(
                    label = "THROTTLE AT",
                    value = "%.0f".format(killC),
                    unit = "°C",
                    accent = AccentBar.Neutral,
                    modifier = Modifier.width(90.dp),
                )
            }

            Spacer(Modifier.height(Spacing.dense))

            // Headroom gauge bar
            HeadroomGaugeBar(
                fraction = animatedFraction,
                accent = gaugeAccent,
            )

            Spacer(Modifier.height(Spacing.dense))
            val urgency = when {
                headroom < 10f -> "CRITICAL — thermal throttle imminent"
                headroom < 20f -> "CAUTION — approaching throttle point"
                else           -> "CLEAR"
            }
            Text(
                urgency,
                style = MaterialTheme.typography.labelSmall,
                color = gaugeAccent,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.06.sp,
            )
        }
    }
}

@Composable
private fun HeadroomGaugeBar(
    fraction: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  3. Efficiency win section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EfficiencyWinSection(
    state: EfficiencyWinState,
    onApply: () -> Unit,
) {
    Text(
        "EFFICIENCY WIN",
        style = MaterialTheme.typography.labelSmall,
        color = AccentBar.Emerald,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.08.sp,
    )
    Spacer(Modifier.height(Spacing.dense))

    when (state) {
        is EfficiencyWinState.Measured -> {
            // MEASURED — show apply button. Never claims measured without data.
            val pctLabel = state.drawReductionPct?.let { "~$it% less draw" } ?: "reduced draw"
            Text(
                "Cap big cluster at ${state.kneeMhz} MHz → $pctLabel, no measurable perf loss",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCCCCCC),
            )
            Text(
                "[MEASURED via efficiency sweep]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = AccentBar.Emerald,
            )
            Spacer(Modifier.height(Spacing.group))
            ArsenalButton(
                label = "Apply Efficiency Knee",
                onClick = onApply,
                style = ArsenalButtonStyle.Primary,
                accent = AccentBar.Emerald,
            )
        }

        is EfficiencyWinState.Estimated -> {
            // ESTIMATED — no apply button. Honest about the data source.
            Text(
                "~${state.drawReductionPct}% draw reduction possible",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCCCCCC),
            )
            Text(
                "[ESTIMATED — run Efficiency Sweep for measured data]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = AccentBar.Amber,
            )
        }

        is EfficiencyWinState.Unavailable -> {
            // Should not reach here (caller gates on !Unavailable), but be safe.
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Apply result dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ApplyResultDialog(
    result: ApplyResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (result is ApplyResult.Success) "Efficiency knee applied" else "Apply failed",
                color = if (result is ApplyResult.Success) AccentBar.Emerald else AccentBar.Red,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            when (result) {
                is ApplyResult.Success -> {
                    val capsText = result.kneeCaps.entries
                        .sortedBy { it.key }
                        .joinToString(", ") { (policy, khz) ->
                            "policy${policy}: ${khz / 1000} MHz"
                        }
                    Text(
                        "Big cluster capped at: $capsText\n\n" +
                            "The kernel will remain at this cap until a reboot or another preset is applied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCCCCCC),
                    )
                }
                is ApplyResult.Failed -> {
                    Text(
                        result.reasons.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentBar.Red,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK", color = AccentBar.Emerald)
            }
        },
    )
}
