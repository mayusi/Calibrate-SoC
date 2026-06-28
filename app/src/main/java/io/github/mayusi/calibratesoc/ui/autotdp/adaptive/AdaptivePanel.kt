package io.github.mayusi.calibratesoc.ui.autotdp.adaptive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptiveIntent
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptivePreset
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.GpuOcTier
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.SectionHeader
import io.github.mayusi.calibratesoc.ui.components.StatusPill

// ── Palette constants (not in AccentBar; local to this file) ─────────────────
private val ColorSurface   = Color(0xFF141419)
private val ColorBase      = Color(0xFF0C0C10)
private val ColorYellow    = AccentBar.Amber          // honest "degraded" state
private val ColorActive    = AccentBar.Emerald        // active / healthy
private val ColorDisabled  = AccentBar.Neutral
private val ColorText      = Color(0xFFE8E8EE)
private val ColorSubtext   = Color(0xFF9999AA)
private val BorderSelected = 1.dp
private val BorderIdle     = 0.5.dp
private val CardRadius     = RoundedCornerShape(4.dp)

/**
 * UNIT 4 (ADAPTIVE MODE) — the full Adaptive-mode panel.
 *
 * Composed of four sub-sections:
 *  1. Preset spectrum picker — 5-segment row (MAX_PERFORMANCE → MAX_BATTERY).
 *  2. Advanced / Custom disclosure — expandable card with 4 normalized sliders.
 *  3. GPU overclock tier chooser — 3 radio cards (+ beyond-stock consent dialog).
 *  4. Live readout card — CPU cap / GPU level / temp / why, from real run-state.
 *
 * All state is lifted; this composable is pure / side-effect-free.
 *
 * @param selectedPreset      currently selected [AdaptivePreset].
 * @param customIntent        raw custom weights if in Custom mode, else null.
 * @param effectiveIntent     the normalized intent the policy consumes (always valid).
 * @param nearestPreset       closest preset to current effective intent (for reset chip).
 * @param gpuOcTier           the chosen [GpuOcTier].
 * @param beyondStockConsent  whether the user has granted beyond-stock consent.
 * @param beyondStockVerdict  the cached probe verdict string (null = not yet probed).
 * @param isRunning           whether the daemon is RUNNING.
 * @param liveCpuCapLabel     e.g. "2.1 GHz" — null when not available.
 * @param liveGpuLabel        e.g. "540 MHz" — null when not available.
 * @param liveTemp            die temperature in °C — null when not available.
 * @param liveWhyLabel        last decision reason — empty string when not available.
 * @param adaptiveModeActive  whether adaptive mode is currently active.
 * @param onSelectPreset      called when the user taps a preset segment.
 * @param onUpdateWeight      called with (axisIndex 0-3, newValue 0f..1f).
 * @param onEnterCustom       called when the user first expands the slider card.
 * @param onExitToPreset      called when the user taps "Reset to {preset}".
 * @param onSetGpuOcTier      called with the new tier (BEYOND_STOCK only after consent).
 * @param onGrantConsent      called after the user confirms the beyond-stock dialog.
 * @param onSetAdaptiveActive called when the master toggle changes.
 */
@Composable
fun AdaptivePanel(
    selectedPreset: AdaptivePreset,
    customIntent: AdaptiveIntent?,
    effectiveIntent: AdaptiveIntent,
    nearestPreset: AdaptivePreset,
    gpuOcTier: GpuOcTier,
    beyondStockConsent: Boolean,
    beyondStockVerdict: String?,
    isRunning: Boolean,
    liveCpuCapLabel: String?,
    liveGpuLabel: String?,
    liveTemp: Int?,
    liveWhyLabel: String,
    adaptiveModeActive: Boolean,
    onSelectPreset: (AdaptivePreset) -> Unit,
    onUpdateWeight: (axisIndex: Int, value: Float) -> Unit,
    onEnterCustom: () -> Unit,
    onExitToPreset: () -> Unit,
    onSetGpuOcTier: (GpuOcTier) -> Unit,
    onGrantConsent: () -> Unit,
    onSetAdaptiveActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 1. Preset spectrum ────────────────────────────────────────────────
        ArsenalPanel(accent = ColorActive, title = "Adaptive Mode") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetSpectrumRow(
                    selected = selectedPreset,
                    inCustomMode = customIntent != null,
                    onSelect = onSelectPreset,
                )
                // Description of the selected preset
                Text(
                    text = if (customIntent != null) {
                        "Custom priorities — adjust the sliders below."
                    } else {
                        selectedPreset.description
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorSubtext,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }

        // ── 2. Custom sliders (expandable) ────────────────────────────────────
        CustomWeightDisclosure(
            effectiveIntent = effectiveIntent,
            isCustom = customIntent != null,
            nearestPreset = nearestPreset,
            onEnterCustom = onEnterCustom,
            onUpdateWeight = onUpdateWeight,
            onResetToPreset = onExitToPreset,
        )

        // ── 3. GPU overclock tier ─────────────────────────────────────────────
        GpuOcTierSection(
            selected = gpuOcTier,
            consent = beyondStockConsent,
            verdict = beyondStockVerdict,
            onSelect = onSetGpuOcTier,
            onGrantConsent = onGrantConsent,
        )

        // ── 4. Live readout ───────────────────────────────────────────────────
        LiveReadoutCard(
            isRunning = isRunning,
            cpuCapLabel = liveCpuCapLabel,
            gpuLabel = liveGpuLabel,
            tempC = liveTemp,
            whyLabel = liveWhyLabel,
            gpuOcTier = gpuOcTier,
            beyondStockVerdict = beyondStockVerdict,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  1. Preset spectrum
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PresetSpectrumRow(
    selected: AdaptivePreset,
    inCustomMode: Boolean,
    onSelect: (AdaptivePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AdaptivePreset.entries.forEach { preset ->
            val isSelected = !inCustomMode && preset == selected
            PresetSegment(
                preset = preset,
                isSelected = isSelected,
                onClick = { onSelect(preset) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PresetSegment(
    preset: AdaptivePreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (isSelected) ColorActive else ColorDisabled
    val bg     = if (isSelected) ColorActive.copy(alpha = 0.14f) else ColorBase
    val border = if (isSelected) BorderSelected else BorderIdle

    Box(
        modifier = modifier
            .clip(CardRadius)
            .background(bg)
            .border(border, accent, CardRadius)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = presetShortLabel(preset),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) ColorActive else ColorText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 9.sp,
                lineHeight = 11.sp,
            )
        }
    }
}

private fun presetShortLabel(preset: AdaptivePreset) = when (preset) {
    AdaptivePreset.MAX_PERFORMANCE -> "Max\nPerf"
    AdaptivePreset.PERFORMANCE     -> "Perf"
    AdaptivePreset.BALANCED        -> "Balanced"
    AdaptivePreset.EFFICIENCY      -> "Efficiency"
    AdaptivePreset.MAX_BATTERY     -> "Max\nBattery"
}

// ─────────────────────────────────────────────────────────────────────────────
//  2. Custom weight disclosure
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CustomWeightDisclosure(
    effectiveIntent: AdaptiveIntent,
    isCustom: Boolean,
    nearestPreset: AdaptivePreset,
    onEnterCustom: () -> Unit,
    onUpdateWeight: (Int, Float) -> Unit,
    onResetToPreset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // Auto-expand when entering custom mode from outside
    if (isCustom && !expanded) expanded = true

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardRadius)
            .background(ColorSurface)
            .border(BorderIdle, ColorDisabled.copy(alpha = 0.3f), CardRadius),
    ) {
        // Header row — always visible
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (!expanded) onEnterCustom()
                    expanded = !expanded
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Customize priorities",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ColorText,
                modifier = Modifier.weight(1f),
            )
            if (isCustom) {
                StatusPill(text = "CUSTOM", accent = AccentBar.Amber)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = ColorSubtext,
                modifier = Modifier.size(18.dp),
            )
        }

        // Expandable content
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 4 labeled sliders
                WeightSlider(
                    label = "Performance",
                    value = effectiveIntent.wPerformance,
                    accentColor = AccentBar.Blue,
                    onValueChange = { onUpdateWeight(0, it) },
                )
                WeightSlider(
                    label = "Battery",
                    value = effectiveIntent.wBattery,
                    accentColor = AccentBar.Amber,
                    onValueChange = { onUpdateWeight(1, it) },
                )
                WeightSlider(
                    label = "Stability",
                    value = effectiveIntent.wStability,
                    accentColor = AccentBar.Purple,
                    onValueChange = { onUpdateWeight(2, it) },
                )
                WeightSlider(
                    label = "Cool & Quiet",
                    value = effectiveIntent.wThermalHeadroom,
                    accentColor = ColorActive,
                    onValueChange = { onUpdateWeight(3, it) },
                )

                // Reset chip
                if (isCustom) {
                    TextButton(
                        onClick = onResetToPreset,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            text = "Reset to ${nearestPreset.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ColorSubtext,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightSlider(
    label: String,
    value: Float,
    accentColor: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ColorSubtext,
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = accentColor.copy(alpha = 0.2f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  3. GPU OC tier section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GpuOcTierSection(
    selected: GpuOcTier,
    consent: Boolean,
    verdict: String?,
    onSelect: (GpuOcTier) -> Unit,
    onGrantConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExperimental by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }

    // Parse the cached verdict to determine if BEYOND_STOCK should show rejected state
    val verdictClass = parseVerdictClass(verdict)
    val beyondStockRejected = verdictClass == VerdictClass.REJECTED ||
            verdictClass == VerdictClass.INEFFECTIVE ||
            verdictClass == VerdictClass.UNSUPPORTED

    ArsenalPanel(accent = AccentBar.Purple, title = "GPU Overclock") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // OFF
            GpuOcRadioCard(
                isSelected = selected == GpuOcTier.OFF,
                label = "Off",
                description = "GPU runs at its normal speeds. Calibrate only steers it " +
                        "within what your device already does. Safest option.",
                onClick = { onSelect(GpuOcTier.OFF) },
                accentColor = ColorDisabled,
            )

            // WITHIN_VENDOR
            GpuOcRadioCard(
                isSelected = selected == GpuOcTier.WITHIN_VENDOR,
                label = "Within-vendor limits (recommended)",
                description = "Lets Calibrate run your GPU at the highest speed your " +
                        "manufacturer ships and hold it there when a game needs it. This " +
                        "stays inside your device's official limits — no extra heat risk " +
                        "beyond normal gaming. Recommended for the best performance.",
                onClick = { onSelect(GpuOcTier.WITHIN_VENDOR) },
                accentColor = ColorActive,
                badge = if (selected == GpuOcTier.WITHIN_VENDOR) null else "RECOMMENDED",
            )

            // BEYOND_STOCK — toggle to reveal
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showExperimental = !showExperimental }) {
                    Text(
                        text = if (showExperimental) "Hide experimental" else "Show experimental",
                        style = MaterialTheme.typography.labelSmall,
                        color = ColorSubtext,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.Science,
                        contentDescription = null,
                        tint = ColorSubtext,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = showExperimental,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                if (selected == GpuOcTier.BEYOND_STOCK && beyondStockRejected) {
                    // Honest rejected state — yellow, not selectable as "active"
                    BeyondStockRejectedCard(verdictClass = verdictClass)
                } else {
                    GpuOcRadioCard(
                        isSelected = selected == GpuOcTier.BEYOND_STOCK && !beyondStockRejected,
                        label = "Beyond-stock (experimental)",
                        description = "Tries to push your GPU past the speed your manufacturer " +
                                "set, if your device's kernel allows it. This can mean more " +
                                "performance — but also more heat and battery drain, and some " +
                                "games may not be more stable. Calibrate tests whether your " +
                                "device actually accepts this. If it doesn't, we stay within " +
                                "vendor limits and tell you. A strong heat guard backs the GPU " +
                                "off well before any unsafe temperature, and everything resets " +
                                "to normal when you stop. Use only if you understand the " +
                                "trade-off. Not all devices support it.",
                        onClick = {
                            if (!consent) {
                                showConsentDialog = true
                            } else {
                                onSelect(GpuOcTier.BEYOND_STOCK)
                            }
                        },
                        accentColor = AccentBar.Amber,
                        experimentalBadge = true,
                    )
                }
            }
        }
    }

    // Consent dialog
    if (showConsentDialog) {
        BeyondStockConsentDialog(
            onConfirm = {
                showConsentDialog = false
                onGrantConsent()
            },
            onDismiss = {
                showConsentDialog = false
                onSelect(GpuOcTier.WITHIN_VENDOR)
            },
        )
    }
}

@Composable
private fun GpuOcRadioCard(
    isSelected: Boolean,
    label: String,
    description: String,
    onClick: () -> Unit,
    accentColor: Color,
    badge: String? = null,
    experimentalBadge: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bg     = if (isSelected) accentColor.copy(alpha = 0.10f) else ColorBase
    val border = if (isSelected) BorderSelected else BorderIdle
    val bColor = if (isSelected) accentColor else ColorDisabled.copy(alpha = 0.3f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardRadius)
            .background(bg)
            .border(border, bColor, CardRadius)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (isSelected) Icons.Outlined.CheckCircle
                          else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) accentColor else ColorDisabled,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) accentColor else ColorText,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill(text = badge, accent = ColorActive)
                }
                if (experimentalBadge) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill(text = "EXPERIMENTAL", accent = AccentBar.Amber)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = ColorSubtext,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun BeyondStockRejectedCard(
    verdictClass: VerdictClass,
    modifier: Modifier = Modifier,
) {
    val honestMessage = when (verdictClass) {
        VerdictClass.UNSUPPORTED ->
            "Your device's kernel has no GPU frequencies above the stock ceiling. " +
            "Beyond-stock is not possible here. Calibrate is keeping your GPU within vendor limits."
        VerdictClass.INEFFECTIVE ->
            "Your device's kernel refused beyond-stock speeds (or they made no difference). " +
            "Calibrate is keeping your GPU within vendor limits."
        else ->
            "Your device's kernel refused beyond-stock speeds (or they made no difference). " +
            "Calibrate is keeping your GPU within vendor limits."
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardRadius)
            .background(ColorYellow.copy(alpha = 0.08f))
            .border(BorderSelected, ColorYellow.copy(alpha = 0.5f), CardRadius)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = "Probe rejected",
            tint = ColorYellow,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Beyond-stock unavailable on this device",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ColorYellow,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = honestMessage,
                style = MaterialTheme.typography.bodySmall,
                color = ColorSubtext,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun BeyondStockConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Beyond-stock GPU overclock",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                "This mode tries to push your GPU past the speed your manufacturer set. " +
                "It can mean more performance, but also more heat and battery drain. " +
                "Calibrate will test your device first — if the kernel refuses, we stay " +
                "within vendor limits and tell you. A heat guard backs the GPU off " +
                "automatically, and everything resets when you stop.\n\n" +
                "Only proceed if you understand and accept these trade-offs.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("I understand — try beyond-stock", color = AccentBar.Amber)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Stay within vendor limits")
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  4. Live readout card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LiveReadoutCard(
    isRunning: Boolean,
    cpuCapLabel: String?,
    gpuLabel: String?,
    tempC: Int?,
    whyLabel: String,
    gpuOcTier: GpuOcTier,
    beyondStockVerdict: String?,
    modifier: Modifier = Modifier,
) {
    val verdictClass = parseVerdictClass(beyondStockVerdict)
    val beyondStockActive = isRunning &&
            gpuOcTier == GpuOcTier.BEYOND_STOCK &&
            verdictClass == VerdictClass.ACCEPTED

    ArsenalPanel(
        accent = if (isRunning) ColorActive else ColorDisabled,
        title = if (isRunning) "Live" else "Inactive",
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!isRunning) {
                Text(
                    text = "Start Adaptive mode to see the live readout.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorSubtext,
                )
            } else {
                // Primary readout line
                val parts = buildList {
                    if (cpuCapLabel != null) add("CPU cap $cpuCapLabel")
                    if (gpuLabel != null)    add("GPU $gpuLabel")
                    if (tempC != null)       add("${tempC}°C")
                    if (whyLabel.isNotBlank()) add("why: $whyLabel")
                }
                Text(
                    text = if (parts.isEmpty()) "Governing…" else parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = ColorText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // Beyond-stock honesty annotation
                if (beyondStockActive) {
                    Text(
                        text = "Beyond-stock OC active — backing off above ${BEYOND_STOCK_THERMAL_GUARD_C}°C.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBar.Amber,
                    )
                }
            }
        }
    }
}

private const val BEYOND_STOCK_THERMAL_GUARD_C = 88

// ─────────────────────────────────────────────────────────────────────────────
//  Verdict parsing helpers (pure, no Android)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Simplified verdict classification for the UI; only the class matters for rendering.
 * Full parsing lives in Unit 3's coordinator.
 */
internal enum class VerdictClass { NONE, UNSUPPORTED, REJECTED, INEFFECTIVE, ACCEPTED }

internal fun parseVerdictClass(verdictRecord: String?): VerdictClass {
    if (verdictRecord.isNullOrBlank()) return VerdictClass.NONE
    val verdict = verdictRecord.substringAfter("|").trim()
    return when {
        verdict.startsWith("Accepted")    -> VerdictClass.ACCEPTED
        verdict.startsWith("Rejected")    -> VerdictClass.REJECTED
        verdict == "Ineffective"          -> VerdictClass.INEFFECTIVE
        verdict == "Unsupported"          -> VerdictClass.UNSUPPORTED
        else                              -> VerdictClass.NONE
    }
}
