package io.github.mayusi.calibratesoc.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.CutCorner
import io.github.mayusi.calibratesoc.ui.components.CutCornerShape
import io.github.mayusi.calibratesoc.ui.components.StatusPill

// ── Arsenal-aligned HUD palette ───────────────────────────────────────────────
// Mirrors AccentBar tokens but kept as private vals for fast lookup in this file.

// Fully opaque: the hudOpacity modifier on the container is the SOLE alpha control.
// A non-FF alpha here compounds with hudOpacity (0.94 × opacity) and makes the HUD
// more transparent than the user's chosen value.
private val HudBg        = Color(0xFF0B1220) // near-black with slight blue tint
private val HudSurface   = Color(0xFF111827) // panel surface (slightly lighter)
private val HudBorder    = Color(0xFF1F2937) // outer border
private val HudBarBg     = Color(0xFF1E293B) // track/bar background
private val HudLabel     = Color(0xFF64748B) // muted label gray
private val HudValue     = Color(0xFFE2E8F0) // value text
private val HudDim       = Color(0xFF475569) // parked / disabled

// Arsenal accent tokens (same hex as AccentBar to ensure visual consistency)
private val HudRed       = Color(0xFFFF4D6D) // primary / danger / alert
private val HudBlue      = Color(0xFF5C93F0) // CPU
private val HudPurple    = Color(0xFFA98BF5) // GPU / power
private val HudAmber     = Color(0xFFE0A93D) // thermal / battery caution
private val HudEmerald   = Color(0xFF2EE6A6) // live / good / FPS-real

// AutoTDP panel accent
private val HudAutoTdpBg     = Color(0xFF081A12)
private val HudAutoTdpBorder = Color(0xFF0F3020)

// ─────────────────────────────────────────────────────────────────────────────
//  Root composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Direction-C HUD overlay root.
 *
 * COMPACT: FPS hero (big, emerald when real / muted REFRESH) + tight MetricTile row
 *          (CPU GHz, GPU MHz, Power W, Temp) + AutoTDP one-line strip when running.
 *
 * VERBOSE: Full control panel — live clock steppers, AutoTDP toggle + profile,
 *          quick-profile chips, FPS-cap stepper, layout/size/opacity controls.
 *          All controls are tappable in overlay using existing FLAG_NOT_FOCUSABLE +
 *          clickable{} pattern (same as existing steppers which already work).
 *
 * Callbacks are wired through [OverlayService.attachOverlay] → the actual
 * implementations live in [HudTuneController] / [AutoTdpController] /
 * [RefreshRateController] / [HudPrefs] — never reimplemented here.
 */
@Composable
fun HudOverlayContent(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onApplyProfile: (String) -> Unit,
    onStepMhz: (Int) -> Unit,
    onCycleNextProfile: () -> Unit,
    onTogglePolicy: (Int) -> Unit,
    onPickStepSize: (Int) -> Unit,
    onToggleRecord: () -> Unit,
    // New callbacks for Direction-C verbose control panel
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
    onSetRefreshHz: (Float?) -> Unit,
    onCycleHudSize: () -> Unit,
    onSetOpacity: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val widthDp: Dp = HudDisplayUtils.hudWidthDp(state.hudSizeIndex).dp

    Box(
        modifier = Modifier
            .widthIn(min = widthDp, max = widthDp)
            .alpha(state.hudOpacity)
            .clip(RoundedCornerShape(6.dp))
            .background(HudBg)
            .border(0.5.dp, HudBorder, RoundedCornerShape(6.dp)),
    ) {
        // Red left-edge accent bar — Direction C primary accent on the container.
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(HudDisplayUtils.hudWidthDp(state.hudSizeIndex).dp * 0f) // full-height via drawBehind
                .matchParentSize()
                .drawBehind {
                    val barW = 2.dp.toPx()
                    drawRect(HudRed, size = size.copy(width = barW))
                },
        )

        Column(modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)) {
            HudHeader(
                profile = state.profile,
                isRecording = state.isRecording,
                recordingElapsedSeconds = state.recordingElapsedSeconds,
                hudSizeIndex = state.hudSizeIndex,
                onCycleLayout = onCycleLayout,
                onToggleRecord = onToggleRecord,
                onCycleHudSize = onCycleHudSize,
                onClose = onClose,
            )
            Spacer(Modifier.height(4.dp))
            when (state.profile) {
                HudProfile.COMPACT -> HudCompactContent(state)
                HudProfile.VERBOSE -> HudVerboseContent(
                    state = state,
                    onStepMhz = onStepMhz,
                    onPickStepSize = onPickStepSize,
                    onTogglePolicy = onTogglePolicy,
                    onToggleAutoTdp = onToggleAutoTdp,
                    onSetAutoTdpProfile = onSetAutoTdpProfile,
                    onApplyProfile = onApplyProfile,
                    onCycleNextProfile = onCycleNextProfile,
                    onSetRefreshHz = onSetRefreshHz,
                    onSetOpacity = onSetOpacity,
                )
            }
            // Flash message (both layouts)
            state.lastActionMessage?.let { msg ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg,
                    color = HudAmber,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HudHeader(
    profile: HudProfile,
    isRecording: Boolean,
    recordingElapsedSeconds: Long,
    hudSizeIndex: Int,
    onCycleLayout: () -> Unit,
    onToggleRecord: () -> Unit,
    onCycleHudSize: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Brand label — angular uppercase
        Text(
            text = "CALIBRATE",
            color = HudRed,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.width(4.dp))
        // Current layout pill
        StatusPill(
            text = profile.name,
            accent = if (profile == HudProfile.VERBOSE) HudBlue else HudLabel,
        )
        Spacer(Modifier.weight(1f))

        // Size toggle (verbose only to save space in compact)
        if (profile == HudProfile.VERBOSE) {
            HudIconChip(
                label = HudDisplayUtils.hudSizeLabel(hudSizeIndex),
                onClick = onCycleHudSize,
                accent = HudLabel,
            )
            Spacer(Modifier.width(2.dp))
        }

        // Recording dot
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleRecord)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "●",
                color = if (isRecording) HudRed else HudDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (isRecording) {
            val mins = recordingElapsedSeconds / 60
            val secs = recordingElapsedSeconds % 60
            Text(
                text = "%d:%02d".format(mins, secs),
                color = HudRed,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(3.dp))
        }

        // Layout swap
        IconButton(onClick = onCycleLayout, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Outlined.SwapVert,
                contentDescription = "Swap layout",
                tint = HudValue,
                modifier = Modifier.size(14.dp),
            )
        }

        // Close
        IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Hide overlay",
                tint = HudDim,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Compact layout
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HudCompactContent(state: HudUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // FPS hero
        CompactFpsHero(
            gameFps = state.gameFps,
            gameFpsIsReal = state.gameFpsIsReal,
        )
        // Tight MetricTile-style row: CPU GHz · GPU MHz · Power W · Temp
        CompactMetricRow(state)
        // AutoTDP one-liner when running
        if (state.autoTdpRunning) {
            AutoTdpCompactStrip(state)
        }
    }
}

@Composable
private fun CompactFpsHero(
    gameFps: Int?,
    gameFpsIsReal: Boolean,
) {
    val fpsLabel = if (gameFpsIsReal) "FPS" else "REFRESH"
    val fpsColor = if (gameFpsIsReal) HudEmerald else HudDim
    val frameMs = gameFps?.takeIf { it > 0 }?.let { "%.1fms".format(1000f / it) }

    val a11y = if (gameFps != null) {
        "$gameFps ${if (gameFpsIsReal) "game frames per second" else "refresh Hz"}"
    } else "FPS unavailable"

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11y },
    ) {
        // Big FPS number — emerald when real, dim when REFRESH
        Text(
            text = gameFps?.toString() ?: "—",
            color = fpsColor,
            fontSize = 42.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            lineHeight = 42.sp,
        )
        Spacer(Modifier.width(5.dp))
        Column(modifier = Modifier.padding(bottom = 5.dp)) {
            // FPS / REFRESH label
            Text(
                text = fpsLabel,
                color = fpsColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            // Frametime sub-label
            if (frameMs != null) {
                Text(
                    text = frameMs,
                    color = HudLabel,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactMetricRow(state: HudUiState) {
    // 4 tiles in a row: CPU · GPU · POWER · TEMP
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MiniMetricTile(
            label = "CPU",
            value = HudDisplayUtils.formatGhzFromMhz(state.cpuMaxMhz.takeIf { it > 0 }),
            accent = HudBlue,
            modifier = Modifier.weight(1f),
        )
        MiniMetricTile(
            label = "GPU",
            value = state.gpuMhz?.let { "${it}M" } ?: "—",
            accent = HudPurple,
            modifier = Modifier.weight(1f),
        )
        MiniMetricTile(
            label = "PWR",
            value = HudDisplayUtils.formatWatts(state.batteryW),
            accent = HudAmber,
            modifier = Modifier.weight(1f),
        )
        MiniMetricTile(
            label = "TEMP",
            value = HudDisplayUtils.formatTemp(state.cpuTempC),
            accent = if (state.cpuTempC != null && state.cpuTempC >= 80f) HudAmber else HudEmerald,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Compact metric tile: accent bottom bar, uppercase label, mono value.
 * Direction-C angular look — matches MetricTile from ArsenalComponents
 * but compact enough for a 4-across row in the HUD.
 */
@Composable
private fun MiniMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xFF0B1525), RoundedCornerShape(4.dp))
            .border(0.5.dp, HudBorder, RoundedCornerShape(4.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = HudLabel,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                color = HudValue,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // 2dp accent bottom bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(accent, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  AutoTDP compact strip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AutoTdpCompactStrip(state: HudUiState) {
    val savingsText = HudDisplayUtils.formatAutoTdpSavings(
        state.autoTdpSavingsMw,
        state.autoTdpSavingsPct,
        state.autoTdpSavingsReady,
    )
    val parkedStr = if (state.autoTdpParkedCores.isNotEmpty()) {
        "park ${state.autoTdpParkedCores.sorted().joinToString(",")} · "
    } else ""
    val capStr = state.autoTdpBigCapMhz?.let { HudDisplayUtils.formatGhzFromMhz(it) } ?: ""

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(HudAutoTdpBg)
            .border(0.5.dp, HudAutoTdpBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Icons.Outlined.Bolt,
            contentDescription = null,
            tint = HudEmerald,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = "AutoTDP",
            color = HudEmerald,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
        val mid = "$parkedStr$capStr".trimEnd(' ', '·').trim()
        if (mid.isNotEmpty()) {
            Text(
                text = mid,
                color = HudLabel,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = savingsText,
            color = if (state.autoTdpSavingsReady) HudEmerald else HudLabel,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose layout — full control panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HudVerboseContent(
    state: HudUiState,
    onStepMhz: (Int) -> Unit,
    onPickStepSize: (Int) -> Unit,
    onTogglePolicy: (Int) -> Unit,
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
    onApplyProfile: (String) -> Unit,
    onCycleNextProfile: () -> Unit,
    onSetRefreshHz: (Float?) -> Unit,
    onSetOpacity: (Float) -> Unit,
) {
    // BUG E FIX: verticalScroll makes the verbose control panel scrollable so all
    // sections are reachable even when the HUD is under FLAG_NOT_FOCUSABLE (scroll
    // still works via touch events on the overlay). Max height is tightened to
    // 380 dp so the verbose panel doesn't consume an absurd amount of screen space;
    // the user scrolls to reach lower sections (thermal, per-core bars, display).
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .heightIn(max = 380.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── 1. FPS hero (verbose variant with battery W) ─────────────────────
        VerboseFpsHero(state)

        HudDivider()

        // ── 2. Live CPU tuning (gated when AutoTDP running) ──────────────────
        VerboseCpuTuneSection(
            state = state,
            onStepMhz = onStepMhz,
            onPickStepSize = onPickStepSize,
            onTogglePolicy = onTogglePolicy,
        )

        HudDivider()

        // ── 3. AutoTDP control panel ─────────────────────────────────────────
        VerboseAutoTdpSection(
            state = state,
            onToggleAutoTdp = onToggleAutoTdp,
            onSetAutoTdpProfile = onSetAutoTdpProfile,
        )

        // ── 4. Quick profiles ────────────────────────────────────────────────
        if (state.quickProfiles.isNotEmpty()) {
            HudDivider()
            VerboseProfileChips(
                chips = state.quickProfiles,
                onApplyProfile = onApplyProfile,
                onCycleNextProfile = onCycleNextProfile,
            )
        }

        // ── 5. FPS cap / refresh rate ─────────────────────────────────────────
        if (state.availableHzOptions.isNotEmpty()) {
            HudDivider()
            VerboseRefreshRateSection(
                availableHz = state.availableHzOptions,
                pinnedHz = state.pinnedHz,
                onSetRefreshHz = onSetRefreshHz,
            )
        }

        // ── 6. Layout/display controls ──────────────────────────────────────
        HudDivider()
        VerboseDisplaySection(
            hudOpacity = state.hudOpacity,
            onSetOpacity = onSetOpacity,
        )

        // ── 7. Per-core bars (informational) ────────────────────────────────
        if (state.perCoreMhz.isNotEmpty()) {
            HudDivider()
            VerbosePerCoreSection(state)
        }

        // ── 8. Thermal row ───────────────────────────────────────────────────
        if (state.cpuTempC != null || state.gpuTempC != null || state.batteryTempC != null) {
            HudDivider()
            VerboseThermalRow(
                cpuTempC = state.cpuTempC,
                gpuTempC = state.gpuTempC,
                batteryTempC = state.batteryTempC,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose: FPS hero
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VerboseFpsHero(state: HudUiState) {
    val fpsLabel = if (state.gameFpsIsReal) "FPS" else "REFRESH"
    val fpsColor = if (state.gameFpsIsReal) HudEmerald else HudDim
    val frameMs = state.gameFps?.takeIf { it > 0 }?.let { "%.1fms".format(1000f / it) }

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = state.gameFps?.toString() ?: "—",
            color = fpsColor,
            fontSize = 44.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            lineHeight = 44.sp,
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.padding(bottom = 6.dp)) {
            Text(
                text = fpsLabel,
                color = fpsColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            if (frameMs != null) {
                Text(
                    text = frameMs,
                    color = HudLabel,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Battery W (right side)
        if (state.batteryW != null) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                Text(
                    text = HudDisplayUtils.formatWatts(state.batteryW),
                    color = HudAmber,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "DRAW",
                    color = HudLabel,
                    fontSize = 7.sp,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose: CPU tuning section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VerboseCpuTuneSection(
    state: HudUiState,
    onStepMhz: (Int) -> Unit,
    onPickStepSize: (Int) -> Unit,
    onTogglePolicy: (Int) -> Unit,
) {
    VerboseSectionLabel("CPU CLOCK", HudBlue)
    Spacer(Modifier.height(4.dp))

    if (HudDisplayUtils.shouldGateSteppers(state.autoTdpRunning)) {
        // AutoTDP owns the clocks — show status, not steppers.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(HudAutoTdpBg)
                .border(0.5.dp, HudAutoTdpBorder, RoundedCornerShape(4.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Outlined.Bolt, null, tint = HudEmerald, modifier = Modifier.size(11.dp))
            Text(
                "AutoTDP is managing clocks — stop it below to tune",
                color = HudLabel,
                fontSize = 9.sp,
                lineHeight = 12.sp,
            )
        }
        return
    }

    // Cluster chip row (select which policies to step)
    if (state.allPolicies.size > 1) {
        ClusterChipRow(
            allPolicies = state.allPolicies,
            enabledPolicies = state.enabledPolicies.ifEmpty { state.allPolicies.toSet() },
            onTogglePolicy = onTogglePolicy,
        )
        Spacer(Modifier.height(4.dp))
    }

    // Big-cluster stepper row: [−] [step▾] [+]  2918MHz
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneBtn(label = "−", enabled = state.bigCorePolicy != null) { onStepMhz(-state.stepMhz) }
        StepSizeChip(currentMhz = state.stepMhz, onPick = onPickStepSize)
        TuneBtn(label = "+", enabled = state.bigCorePolicy != null) { onStepMhz(state.stepMhz) }
        Spacer(Modifier.width(4.dp))
        Text(
            text = HudDisplayUtils.formatClusterMhz(state.bigCoreCurrentMhz),
            color = HudBlue,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose: AutoTDP control section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VerboseAutoTdpSection(
    state: HudUiState,
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
) {
    val statusColor = when (state.autoTdpStatus) {
        AutoTdpStatus.RUNNING -> HudEmerald
        AutoTdpStatus.KILLED_BY_SAFETY, AutoTdpStatus.WRITE_DENIED -> HudRed
        AutoTdpStatus.LIVE_UNAVAILABLE -> HudAmber
        else -> HudDim
    }
    val statusLabel = when (state.autoTdpStatus) {
        AutoTdpStatus.RUNNING -> "RUNNING"
        AutoTdpStatus.IDLE -> "IDLE"
        AutoTdpStatus.LIVE_UNAVAILABLE -> "UNAVAILABLE"
        AutoTdpStatus.KILLED_BY_SAFETY -> "SAFETY KILL"
        AutoTdpStatus.WRITE_DENIED -> "WRITE DENIED"
        AutoTdpStatus.STOPPED -> "STOPPED"
    }

    VerboseSectionLabel("AUTOTDP", HudEmerald)
    Spacer(Modifier.height(4.dp))

    // Status + toggle row
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Bolt, null, tint = statusColor, modifier = Modifier.size(12.dp))
        Text(
            text = statusLabel,
            color = statusColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.weight(1f),
        )
        // Savings display (when ready)
        if (state.autoTdpRunning) {
            Text(
                text = HudDisplayUtils.formatAutoTdpSavings(
                    state.autoTdpSavingsMw,
                    state.autoTdpSavingsPct,
                    state.autoTdpSavingsReady,
                ),
                color = if (state.autoTdpSavingsReady) HudEmerald else HudLabel,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        // Start / Stop button
        TuneBtn(
            label = if (state.autoTdpRunning) "STOP" else "START",
            enabled = state.autoTdpStatus != AutoTdpStatus.LIVE_UNAVAILABLE,
            accent = if (state.autoTdpRunning) HudRed else HudEmerald,
            onClick = onToggleAutoTdp,
        )
    }

    Spacer(Modifier.height(4.dp))

    // Profile picker row: EFF · BAL · TGT
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "PROFILE",
            color = HudLabel,
            fontSize = 8.sp,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(end = 2.dp),
        )
        listOf(AutoTdpProfile.EFFICIENCY, AutoTdpProfile.BALANCED, AutoTdpProfile.BATTERY_TARGET)
            .forEach { profile ->
                val isActive = state.autoTdpActiveProfile == profile
                ProfileChip(
                    label = HudDisplayUtils.formatAutoTdpProfileShort(profile.name),
                    selected = isActive,
                    accent = HudEmerald,
                    onClick = { onSetAutoTdpProfile(profile) },
                )
            }
    }

    // Detail line when running
    if (state.autoTdpRunning) {
        Spacer(Modifier.height(3.dp))
        val detailParts = buildList {
            if (state.autoTdpParkedCores.isNotEmpty()) {
                add("parked cpu${state.autoTdpParkedCores.sorted().joinToString(",")}")
            }
            state.autoTdpBigCapMhz?.let { add("cap ${HudDisplayUtils.formatGhzFromMhz(it)}") }
            state.autoTdpGpuLevel?.let { add("GPU lvl $it") }
        }
        if (detailParts.isNotEmpty()) {
            Text(
                text = detailParts.joinToString(" · "),
                color = HudLabel,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 11.sp,
            )
        }
        if (state.autoTdpReason.isNotBlank()) {
            Text(
                text = state.autoTdpReason.take(60),
                color = HudDim,
                fontSize = 8.sp,
                lineHeight = 11.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose: Quick profiles
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerboseProfileChips(
    chips: List<Pair<String, String>>,
    onApplyProfile: (String) -> Unit,
    onCycleNextProfile: () -> Unit,
) {
    VerboseSectionLabel("PROFILES", HudBlue)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { (id, name) ->
            ProfileChip(
                label = name.take(12),
                selected = false,
                accent = HudBlue,
                onClick = { onApplyProfile(id) },
            )
        }
        // Cycle button
        TuneBtn(label = "NEXT", enabled = chips.size > 1, onClick = onCycleNextProfile)
    }
    Text(
        text = "tap writes script — open vendor settings to run",
        color = HudDim,
        fontSize = 8.sp,
        lineHeight = 11.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose: Refresh rate / FPS cap
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerboseRefreshRateSection(
    availableHz: List<Float>,
    pinnedHz: Float?,
    onSetRefreshHz: (Float?) -> Unit,
) {
    VerboseSectionLabel("DISPLAY HZ", HudPurple)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // "Auto" chip = clear pin
        ProfileChip(
            label = "AUTO",
            selected = pinnedHz == null,
            accent = HudPurple,
            onClick = { onSetRefreshHz(null) },
        )
        availableHz.forEach { hz ->
            ProfileChip(
                label = HudDisplayUtils.formatHz(hz),
                selected = pinnedHz != null && kotlin.math.abs(hz - pinnedHz) < 0.5f,
                accent = HudPurple,
                onClick = { onSetRefreshHz(hz) },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose: Display controls (opacity)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VerboseDisplaySection(
    hudOpacity: Float,
    onSetOpacity: (Float) -> Unit,
) {
    VerboseSectionLabel("DISPLAY", HudLabel)
    Spacer(Modifier.height(4.dp))
    // Opacity stepper row: [−10%] [94%] [+10%]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("OPACITY", color = HudLabel, fontSize = 8.sp, letterSpacing = 0.4.sp)
        Spacer(Modifier.weight(1f))
        TuneBtn(label = "−", enabled = hudOpacity > 0.2f) {
            onSetOpacity((hudOpacity - 0.1f).coerceAtLeast(0.1f))
        }
        Text(
            text = HudDisplayUtils.formatOpacityPct(hudOpacity),
            color = HudValue,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        TuneBtn(label = "+", enabled = hudOpacity < 1f) {
            onSetOpacity((hudOpacity + 0.1f).coerceAtMost(1f))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose: Per-core bars (informational)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VerbosePerCoreSection(state: HudUiState) {
    VerboseSectionLabel("PER-CORE", HudBlue)
    Spacer(Modifier.height(3.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        state.perCoreMhz.forEachIndexed { idx, mhz ->
            val loadPct = state.perCoreLoadPct.getOrNull(idx) ?: 0
            // cpu0 is never parked per spec
            val isParked = idx != 0 && idx in state.autoTdpParkedCores
            PerCoreBar(idx = idx, mhz = mhz, loadPct = loadPct, isParked = isParked)
        }
    }
}

@Composable
private fun PerCoreBar(idx: Int, mhz: Int, loadPct: Int, isParked: Boolean) {
    val loadFrac = (loadPct / 100f).coerceIn(0f, 1f)
    val textColor = if (isParked) HudDim else HudValue
    val barFillColor = if (isParked) Color.Transparent else HudBlue

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (isParked) "Core $idx parked"
                else "Core $idx — $mhz MHz, $loadPct percent load"
            },
    ) {
        Text(
            text = "c$idx",
            color = textColor,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(18.dp),
        )
        Spacer(Modifier.width(3.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(HudBarBg),
        ) {
            if (!isParked && loadFrac > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(loadFrac)
                        .height(6.dp)
                        .drawBehind {
                            // Slanted right edge — Direction C bar look
                            val skew = if (loadFrac < 1f) 3f else 0f
                            val path = Path().apply {
                                moveTo(0f, 0f)
                                lineTo(size.width - skew, 0f)
                                lineTo(size.width, size.height)
                                lineTo(0f, size.height)
                                close()
                            }
                            drawPath(path, HudBlue)
                        },
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (isParked) "PARKED" else "$mhz·$loadPct%",
            color = textColor,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(66.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verbose: Thermal row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VerboseThermalRow(
    cpuTempC: Float?,
    gpuTempC: Float?,
    batteryTempC: Float?,
) {
    VerboseSectionLabel("THERMAL", HudAmber)
    Spacer(Modifier.height(3.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ThermalMini(label = "CPU", tempC = cpuTempC, modifier = Modifier.weight(1f))
        ThermalMini(label = "GPU", tempC = gpuTempC, modifier = Modifier.weight(1f))
        ThermalMini(label = "BATT", tempC = batteryTempC, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ThermalMini(label: String, tempC: Float?, modifier: Modifier = Modifier) {
    val hot = tempC != null && tempC >= 80f
    val valueColor = if (hot) HudAmber else HudValue
    val accentColor = if (hot) HudAmber else HudLabel

    Column(
        modifier = modifier
            .background(HudBarBg, RoundedCornerShape(4.dp))
            .border(0.5.dp, if (hot) HudAmber.copy(alpha = 0.4f) else HudBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = HudLabel, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(
            text = HudDisplayUtils.formatTemp(tempC),
            color = valueColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Reusable micro-components
// ─────────────────────────────────────────────────────────────────────────────

/** Section label — uppercase, accent tick, tight tracking. */
@Composable
private fun VerboseSectionLabel(title: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(12.dp)
                .background(accent, RoundedCornerShape(1.dp)),
        )
        Text(
            text = title,
            color = HudValue,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
    }
}

/** Thin horizontal rule. */
@Composable
private fun HudDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = HudBorder,
    )
}

/**
 * Arsenal-style small angular button for the HUD.
 * Uses [CutCornerShape] (TopEnd cut) for the angular look.
 * Touch works because the overlay uses FLAG_NOT_FOCUSABLE + clickable{}.
 */
@Composable
private fun TuneBtn(
    label: String,
    enabled: Boolean,
    accent: Color = HudBlue,
    onClick: () -> Unit,
) {
    val shape = CutCornerShape(corner = CutCorner.TopEnd, cutSize = 8.dp, cornerRadius = 3.dp)
    val bg = if (enabled) accent.copy(alpha = 0.18f) else Color.Transparent
    val border = if (enabled) accent.copy(alpha = 0.6f) else HudBorder
    val fg = if (enabled) accent else HudDim

    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(0.5.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = fg,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Step-size chip. Tapping CYCLES to the next step value (50→100→200→300→500→1000→50).
 *
 * Deliberately NOT a DropdownMenu: a Material DropdownMenu spawns a Popup window, and a
 * Popup over a FLAG_NOT_FOCUSABLE overlay does not reliably receive outside-tap dismissal,
 * so the menu can stay pinned open. A cycle-on-tap chip needs no second window and matches
 * the proven clickable{} pattern every other HUD control uses.
 */
@Composable
private fun StepSizeChip(currentMhz: Int, onPick: (Int) -> Unit) {
    val steps = remember { listOf(50, 100, 200, 300, 500, 1000) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(HudBlue.copy(alpha = 0.18f))
            .border(0.5.dp, HudBlue.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .clickable {
                val idx = steps.indexOf(currentMhz).let { if (it < 0) 0 else it }
                onPick(steps[(idx + 1) % steps.size])
            }
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Text(
            "±${currentMhz}↻",
            color = HudBlue,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Cluster toggle chip for the policy selector. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClusterChipRow(
    allPolicies: List<Int>,
    enabledPolicies: Set<Int>,
    onTogglePolicy: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("CLUSTER:", color = HudLabel, fontSize = 8.sp, modifier = Modifier.padding(end = 2.dp))
        allPolicies.forEach { pid ->
            val on = pid in enabledPolicies
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) HudBlue.copy(alpha = 0.22f) else Color.Transparent)
                    .border(0.5.dp, if (on) HudBlue else HudBorder, RoundedCornerShape(4.dp))
                    .clickable { onTogglePolicy(pid) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    "p$pid",
                    color = if (on) HudBlue else HudDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Generic selectable chip — used for profiles, Hz, AutoTDP profiles.
 * When [selected] the background is tinted with [accent].
 */
@Composable
private fun ProfileChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color(0xFF0D1825))
            .border(
                0.5.dp,
                if (selected) accent else HudBorder,
                RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = if (selected) accent else HudValue,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Tiny icon-like chip for HUD header buttons (size toggle). */
@Composable
private fun HudIconChip(
    label: String,
    onClick: () -> Unit,
    accent: Color = HudLabel,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(0.5.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
        )
    }
}
