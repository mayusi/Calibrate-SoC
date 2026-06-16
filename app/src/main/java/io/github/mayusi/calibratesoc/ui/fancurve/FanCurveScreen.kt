package io.github.mayusi.calibratesoc.ui.fancurve

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.fancurve.ApplyResult
import io.github.mayusi.calibratesoc.data.fancurve.FanCurve
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveAvailability
import io.github.mayusi.calibratesoc.data.fancurve.FanCurvePoint
import io.github.mayusi.calibratesoc.data.fancurve.FanCurvePreset
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveValidation
import io.github.mayusi.calibratesoc.data.fancurve.LiveFanReading
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.MetricTile
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Fan Curve screen — Direction C Arsenal styling.
 *
 * Top-to-bottom:
 *  1. Page header + availability status pill.
 *  2. Availability gate: if unavailable, an honest AlertCard with the reason +
 *     nothing else editable.
 *  3. Live fan readout (current duty %, raw duty/period, fan mode).
 *  4. Preset chips (Quiet / Balanced / Cool / Max Cooling).
 *  5. Curve preview chart.
 *  6. Numeric point editors (per-point duty sliders + temp readout).
 *  7. Warnings (sub-20%, runaway, duty-decrease) + the sub-floor opt-in switch.
 *  8. Apply button + apply-on-open switch + honest apply status.
 *  9. Safety note.
 */
@Composable
fun FanCurveScreen(
    onBack: () -> Unit = {},
    viewModel: FanCurveViewModel = hiltViewModel(),
) {
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    val curve by viewModel.curve.collectAsStateWithLifecycle()
    val selectedPresetId by viewModel.selectedPresetId.collectAsStateWithLifecycle()
    val warnings by viewModel.warnings.collectAsStateWithLifecycle()
    val validation by viewModel.validationState.collectAsStateWithLifecycle()
    val allowSubFloor by viewModel.allowSubFloor.collectAsStateWithLifecycle()
    val applyOnOpen by viewModel.applyOnOpen.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val applying by viewModel.applying.collectAsStateWithLifecycle()
    val lastApply by viewModel.lastApply.collectAsStateWithLifecycle()

    val isAvailable = availability is FanCurveAvailability.Available

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        // ── 1. Header ───────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "FAN CURVE",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.04.sp,
                    )
                    Text(
                        "Custom smart fan curve for the AYN Odin 3.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AvailabilityPill(availability)
            }
        }

        // ── 2. Availability gate ────────────────────────────────────────────
        if (!isAvailable) {
            val reason = (availability as? FanCurveAvailability.Unavailable)?.reason
                ?: "Feature unavailable."
            item {
                AlertCard(
                    type = AlertType.INFO,
                    title = "Not available",
                    message = reason,
                )
            }
            // Stop here — nothing below is actionable without privilege.
            return@LazyColumn
        }

        // ── 3. Live readout ─────────────────────────────────────────────────
        item { LiveFanReadout(live) }

        // ── 4. Presets ──────────────────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Blue, title = "PRESETS") {
                PresetChips(
                    selectedId = selectedPresetId,
                    onSelect = viewModel::selectPreset,
                )
                val selected = FanCurvePreset.byId(selectedPresetId)
                Text(
                    text = selected?.tagline
                        ?: "Custom curve (hand-edited). Tap a preset to reset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    ArsenalButton(
                        label = "Load device curve",
                        onClick = viewModel::loadCurrentFromDevice,
                        style = ArsenalButtonStyle.Secondary,
                        accent = AccentBar.Blue,
                    )
                }
            }
        }

        // ── 5. Curve preview ────────────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Purple, title = "CURVE PREVIEW") {
                Text(
                    "Temperature (°C) → fan duty (%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FanCurveChart(curve = curve)
            }
        }

        // ── 6. Point editors ────────────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Emerald, title = "POINTS") {
                Text(
                    "Drag each slider to set the fan duty at that temperature. " +
                        "The final point is the catch-all ceiling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexedPoints(curve) { index, point ->
            PointEditorRow(
                index = index,
                point = point,
                onDuty = { viewModel.setPointDuty(index, it) },
            )
        }

        // ── 7. Warnings + sub-floor opt-in ──────────────────────────────────
        if (validation is FanCurveValidation.Invalid) {
            item {
                AlertCard(
                    type = AlertType.ERROR,
                    title = "Curve is not valid",
                    message = (validation as FanCurveValidation.Invalid).reason,
                )
            }
        }
        items(warnings.size) { i ->
            val w = warnings[i]
            AlertCard(
                type = AlertType.WARNING,
                title = "Heads up",
                message = w.message,
            )
        }
        item {
            ToggleRow(
                title = "Allow sub-20% / 0% duty",
                subtitle = "The stock UI hides this. The fan can stop entirely — only " +
                    "enable if you understand your device's thermals.",
                checked = allowSubFloor,
                onChange = viewModel::setAllowSubFloor,
            )
        }

        // ── 8. Apply ────────────────────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Red, title = "APPLY") {
                Text(
                    "This controls your device's cooling. Calibrate edits the Odin fan " +
                        "curve, reloads the fan service, and verifies the result.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ArsenalButton(
                    label = if (applying) "Applying…" else "Apply curve",
                    onClick = viewModel::applyCurrentCurve,
                    accent = AccentBar.Red,
                    enabled = !applying && validation is FanCurveValidation.Valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    title = "Re-apply when the app opens",
                    subtitle = "Re-asserts your curve on app launch (it survives reboots " +
                        "only while the device keeps it; this guarantees it).",
                    checked = applyOnOpen,
                    onChange = viewModel::setApplyOnOpen,
                )
                lastApply?.let { ApplyStatusCard(it) }
            }
        }

        // ── 9. Safety note ──────────────────────────────────────────────────
        item {
            Text(
                "Calibrate preserves your other Odin settings when writing the curve, " +
                    "and never reports success it could not verify.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Pieces
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AvailabilityPill(availability: FanCurveAvailability) {
    if (availability is FanCurveAvailability.Available) {
        StatusPill(text = "Ready", accent = AccentBar.Emerald)
    } else {
        StatusPill(text = "Unavailable", accent = AccentBar.Neutral)
    }
}

@Composable
private fun LiveFanReadout(live: LiveFanReading?) {
    ArsenalPanel(accent = AccentBar.Amber, title = "LIVE FAN") {
        val dutyPct = live?.dutyPct
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            MetricTile(
                label = "Fan duty",
                value = dutyPct?.toString() ?: "—",
                unit = "%",
                accent = AccentBar.Amber,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Raw duty",
                value = live?.dutyRaw?.toString() ?: "—",
                accent = AccentBar.Amber,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Period",
                value = live?.periodRaw?.toString() ?: "—",
                accent = AccentBar.Amber,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = when {
                live == null -> "Reading the live fan node…"
                live.fanMode == 4 -> "Fan mode: Smart (curve-driven)."
                live.fanMode != null -> "Fan mode: ${live.fanMode} (not Smart — apply a curve to engage Smart)."
                else -> "Fan mode unknown."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetChips(
    selectedId: String?,
    onSelect: (FanCurvePreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        FanCurvePreset.entries.chunked(2).forEach { rowPresets ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                rowPresets.forEach { preset ->
                    PresetChip(
                        label = preset.displayName,
                        selected = preset.id == selectedId,
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowPresets.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = AccentBar.Blue
    val bg = if (selected) accent.copy(alpha = 0.18f) else Color(0xFF0C0C10)
    val border = if (selected) accent else Color.White.copy(alpha = 0.10f)
    val textColor = if (selected) Color.White else Color(0xFF9AA0AA)
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            letterSpacing = 0.05.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun PointEditorRow(
    index: Int,
    point: FanCurvePoint,
    onDuty: (Int) -> Unit,
) {
    val tempLabel = if (point.isSentinel) "≥ ceiling" else "${point.tempC}°C"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141419), RoundedCornerShape(4.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .padding(horizontal = Spacing.card, vertical = Spacing.group),
        verticalArrangement = Arrangement.spacedBy(Spacing.dense),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tempLabel,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
            Text(
                text = "${point.dutyPct}%",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (point.dutyPct < FanCurve.SAFE_MIN_DUTY_PCT) AccentBar.Amber else AccentBar.Emerald,
            )
        }
        Slider(
            value = point.dutyPct.toFloat(),
            onValueChange = { onDuty(it.toInt()) },
            valueRange = 0f..100f,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = AccentBar.Emerald,
                activeTrackColor = AccentBar.Emerald,
            ),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ApplyStatusCard(result: ApplyResult) {
    val (type, title, message) = when (result) {
        is ApplyResult.Applied -> Triple(
            if (result.liveConfirmed) AlertType.INFO else AlertType.WARNING,
            if (result.liveConfirmed) "Applied & verified" else "Applied (live effect unconfirmed)",
            if (result.liveConfirmed) {
                "The curve is in place and the fan node confirms it " +
                    "(duty ${result.fanDuty}, period ${result.fanPeriod})."
            } else {
                "The curve was written to config.xml and Smart mode was set, but the " +
                    "live fan node couldn't be read to confirm the effect."
            },
        )
        is ApplyResult.Unverified -> Triple(
            AlertType.WARNING, "Applied — UNVERIFIED", result.reason,
        )
        is ApplyResult.Failed -> Triple(
            AlertType.ERROR, "Not applied", result.reason,
        )
        is ApplyResult.Invalid -> Triple(
            AlertType.ERROR, "Curve rejected", result.reason,
        )
        is ApplyResult.Unavailable -> Triple(
            AlertType.INFO, "Not available", result.reason,
        )
    }
    AlertCard(type = type, title = title, message = message)
}

// ─────────────────────────────────────────────────────────────────────────────
//  LazyListScope helpers (point list)
// ─────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.itemsIndexedPoints(
    curve: FanCurve,
    content: @Composable (index: Int, point: FanCurvePoint) -> Unit,
) {
    items(curve.points.size) { i ->
        content(i, curve.points[i])
    }
}
