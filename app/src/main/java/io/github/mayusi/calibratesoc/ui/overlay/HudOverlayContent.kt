package io.github.mayusi.calibratesoc.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.github.mayusi.calibratesoc.data.autotdp.DecisionRecord
import io.github.mayusi.calibratesoc.ui.components.CutCorner
import io.github.mayusi.calibratesoc.ui.components.CutCornerShape
import io.github.mayusi.calibratesoc.ui.components.StatusPill

// ── Arsenal-aligned HUD palette ───────────────────────────────────────────────
// Mirrors AccentBar tokens exactly. Kept private for fast local lookup.

private val HudBg        = Color(0xFF0B1220)
private val HudSurface   = Color(0xFF111827)
private val HudBorder    = Color(0xFF1C2740)
private val HudBarBg     = Color(0xFF1E293B)
private val HudLabel     = Color(0xFF64748B)
private val HudValue     = Color(0xFFE2E8F0)
private val HudDim       = Color(0xFF475569)

private val HudRed       = Color(0xFFFF4D6D)
private val HudBlue      = Color(0xFF5C93F0)
private val HudPurple    = Color(0xFFA98BF5)
private val HudAmber     = Color(0xFFE0A93D)
private val HudEmerald   = Color(0xFF2EE6A6)

private val HudAutoTdpBg     = Color(0xFF081A12)
private val HudAutoTdpBorder = Color(0xFF0F3020)

// ─────────────────────────────────────────────────────────────────────────────
//  Root composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Horizontal HUD overlay.
 *
 * COMPACT mode: a single thin horizontal bar that hugs a screen edge.
 *   [ 60 FPS ] | dot CPU  dot GPU  dot POWER  dot TEMP | [TDP pill]
 *
 * FULL mode: a wider horizontal panel (~480dp):
 *   Header row: CALIBRATE + AutoTDP pill | layout-swap + close
 *   Body:  LEFT = FPS block  |  RIGHT = 4-wide metric grid (CPU/GPU/POWER/TEMP)
 *   AutoTDP control strip at the bottom.
 *
 * All callbacks are wired in [OverlayService.attachOverlay]. Only the existing
 * [onToggleAutoTdp] and [onSetAutoTdpProfile] callbacks touch AutoTDP -- no new
 * controller calls are introduced here.
 */
@Composable
fun HudOverlayContent(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onApplyProfile: (String) -> Unit,
    onCycleNextProfile: () -> Unit,
    onToggleRecord: () -> Unit,
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
    onSetRefreshHz: (Float?) -> Unit,
    onCycleHudSize: () -> Unit,
    onSetOpacity: (Float) -> Unit,
    onClose: () -> Unit,
) {
    when (state.profile) {
        HudProfile.COMPACT -> HudCompactBar(
            state = state,
            onCycleLayout = onCycleLayout,
            onClose = onClose,
        )
        HudProfile.VERBOSE -> HudFullPanel(
            state = state,
            onCycleLayout = onCycleLayout,
            onToggleRecord = onToggleRecord,
            onApplyProfile = onApplyProfile,
            onCycleNextProfile = onCycleNextProfile,
            onToggleAutoTdp = onToggleAutoTdp,
            onSetAutoTdpProfile = onSetAutoTdpProfile,
            onSetRefreshHz = onSetRefreshHz,
            onCycleHudSize = onCycleHudSize,
            onSetOpacity = onSetOpacity,
            onClose = onClose,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  COMPACT mode -- single thin horizontal bar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thin horizontal bar:
 *   [ 60 FPS ] | dot 3.28G  dot 88%  dot 6.4W  dot 61deg | [TDP]
 * All on one row. Near-black bg, rounded 10dp, ~8px vertical padding.
 */
@Composable
private fun HudCompactBar(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onClose: () -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    val a11y = buildString {
        append(if (state.gameFps != null) "${state.gameFps} ${if (state.gameFpsIsReal) "FPS" else "Hz"}" else "FPS unavailable")
        append(", CPU ${HudDisplayUtils.formatGhzFromMhz(state.cpuMaxMhz.takeIf { it > 0 })}")
        append(", GPU ${state.gpuMhz?.let { "${it}M" } ?: "N/A"}")
        append(", ${HudDisplayUtils.formatWatts(state.batteryW)}")
        append(", ${HudDisplayUtils.formatTemp(state.cpuTempC)}")
        if (state.autoTdpRunning) {
            val capWord = if (state.autoTdpBigCapMhz != null) {
                "cap ${HudDisplayUtils.formatProofChipCap(state.autoTdpBigCapMhz)}"
            } else "stock"
            val live = HudDisplayUtils.heartbeatIsLive(state.autoTdpLastAppliedEpochMs, nowMs)
            append(", AutoTDP $capWord, ${if (live) "live" else "stalled"}")
        }
    }

    Row(
        modifier = Modifier
            .alpha(state.hudOpacity)
            .clip(RoundedCornerShape(10.dp))
            .background(HudBg)
            .border(0.5.dp, HudBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // FPS hero -- big green mono number
        val fpsColor = if (state.gameFpsIsReal) HudEmerald else HudDim
        Text(
            text = state.gameFps?.toString() ?: "--",
            color = fpsColor,
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = if (state.gameFpsIsReal) "FPS" else "HZ",
            color = fpsColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )

        // Thin vertical divider
        CompactDivider()

        // 4 inline metrics with colored dot prefix
        CompactMetric(
            dot = HudBlue,
            value = HudDisplayUtils.formatGhzFromMhz(state.cpuMaxMhz.takeIf { it > 0 }),
        )
        Spacer(Modifier.width(8.dp))
        CompactMetric(
            dot = HudPurple,
            value = state.gpuMhz?.let { "${it}M" } ?: "--",
        )
        Spacer(Modifier.width(8.dp))
        CompactMetric(
            dot = HudAmber,
            value = HudDisplayUtils.formatWatts(state.batteryW),
        )
        Spacer(Modifier.width(8.dp))
        val tempHot = state.cpuTempC != null && state.cpuTempC >= 80f
        CompactMetric(
            dot = if (tempHot) HudAmber else HudEmerald,
            value = HudDisplayUtils.formatTemp(state.cpuTempC),
        )

        // AutoTDP proof chip.
        // RUNNING  → live proof chip: current cap (or STOCK) + heartbeat pulse dot.
        //            Dot is emerald + pulsing when a write landed within the live
        //            window; muted + static "stalled" otherwise. This is the honest
        //            "is it REALLY working" signal — a stalled daemon shows as muted,
        //            never as a fake "live".
        // STOPPED  → plain muted "TDP" pill (unchanged affordance).
        CompactDivider()
        if (state.autoTdpRunning) {
            CompactProofChip(state = state)
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(HudDim.copy(alpha = 0.07f))
                    .border(0.5.dp, HudDim.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "TDP",
                    color = HudDim,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Layout swap -- tap to expand to FULL
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onCycleLayout),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.SwapHoriz,
                contentDescription = "Expand HUD",
                tint = HudDim,
                modifier = Modifier.size(13.dp),
            )
        }
        // Close
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Hide overlay",
                tint = HudDim,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun CompactDivider() {
    Spacer(Modifier.width(8.dp))
    Box(
        modifier = Modifier
            .width(0.5.dp)
            .height(14.dp)
            .background(HudBorder),
    )
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun CompactMetric(dot: Color, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(dot),
        )
        Text(
            text = value,
            color = HudValue,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * COMPACT-bar live AutoTDP proof chip (only rendered while running):
 *   [pulse-dot]  3.0G   (or "STOCK" when uncapped)
 *
 * The pulse dot proves the daemon is alive: emerald + animated when a write
 * landed within the live window, muted + static when stalled (age > ~3s). The
 * cap value is a DERIVED fact (live immediately), so it never hides; "STOCK" is
 * the honest label for "no cap applied".
 */
@Composable
private fun CompactProofChip(state: HudUiState) {
    val nowMs = System.currentTimeMillis()
    val live = HudDisplayUtils.heartbeatIsLive(state.autoTdpLastAppliedEpochMs, nowMs)
    val accent = if (live) HudEmerald else HudDim

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = if (live) 0.15f else 0.07f))
            .border(0.5.dp, accent.copy(alpha = if (live) 0.5f else 0.25f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HeartbeatDot(live = live, size = 5.dp)
            Text(
                text = HudDisplayUtils.formatProofChipCap(state.autoTdpBigCapMhz),
                color = accent,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Heartbeat pulse dot. When [live] it slowly pulses its alpha (emerald) to show
 * the daemon is actively writing; when stalled it renders a static muted dot.
 * The animation is purely cosmetic — colour/liveness is decided by the caller
 * from the real heartbeat age, so a stalled daemon can never *look* alive.
 */
@Composable
private fun HeartbeatDot(live: Boolean, size: Dp) {
    val color = if (live) HudEmerald else HudDim
    val alpha = if (live) {
        val transition = rememberInfiniteTransition(label = "heartbeat")
        val pulse by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "heartbeatAlpha",
        )
        pulse
    } else {
        0.6f
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = alpha)),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  FULL mode -- horizontal panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HudFullPanel(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onToggleRecord: () -> Unit,
    onApplyProfile: (String) -> Unit,
    onCycleNextProfile: () -> Unit,
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
    onSetRefreshHz: (Float?) -> Unit,
    onCycleHudSize: () -> Unit,
    onSetOpacity: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val widthDp: Dp = HudDisplayUtils.hudWidthDp(state.hudSizeIndex).dp

    Column(
        modifier = Modifier
            .widthIn(min = widthDp, max = widthDp)
            .alpha(state.hudOpacity)
            .clip(RoundedCornerShape(10.dp))
            .background(HudBg)
            .border(0.5.dp, HudBorder, RoundedCornerShape(10.dp))
            .drawBehind {
                // Red left-edge accent bar
                val barW = 2.dp.toPx()
                drawRect(HudRed, size = size.copy(width = barW))
            }
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── Header row ──────────────────────────────────────────────────────
        FullPanelHeader(
            state = state,
            onCycleLayout = onCycleLayout,
            onToggleRecord = onToggleRecord,
            onCycleHudSize = onCycleHudSize,
            onClose = onClose,
        )

        HudDivider()

        // ── Body: FPS block | Metric grid ───────────────────────────────────
        FullPanelBody(state)

        // ── AutoTDP proof-of-effect section ──────────────────────────────────
        HudDivider()
        AutoTdpProofSection(
            state = state,
            onToggleAutoTdp = onToggleAutoTdp,
            onSetAutoTdpProfile = onSetAutoTdpProfile,
        )

        // ── Quick profiles (optional) ────────────────────────────────────────
        // Kept inline because it's a one-tap in-game action. Refresh-rate,
        // opacity, per-core bars and the thermal breakdown were intentionally
        // REMOVED from the always-visible full panel — they made it tall and
        // cramped. The horizontal panel now stays compact: header → metrics →
        // AutoTDP → (optional) saved-profile chips. Opacity/size live on the
        // header size chip; per-core/thermal detail lives in the app's Dashboard.
        if (state.quickProfiles.isNotEmpty()) {
            HudDivider()
            FullProfileChips(
                chips = state.quickProfiles,
                onApplyProfile = onApplyProfile,
                onCycleNextProfile = onCycleNextProfile,
            )
        }

        // ── Flash message ────────────────────────────────────────────────────
        state.lastActionMessage?.let { msg ->
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

// ── Full panel: Header ───────────────────────────────────────────────────────

@Composable
private fun FullPanelHeader(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onToggleRecord: () -> Unit,
    onCycleHudSize: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Brand label
        Text(
            text = "CALIBRATE",
            color = HudRed,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.width(5.dp))

        // AutoTDP status pill
        val tdpAccent = when (state.autoTdpStatus) {
            AutoTdpStatus.RUNNING -> HudEmerald
            AutoTdpStatus.KILLED_BY_SAFETY, AutoTdpStatus.WRITE_DENIED -> HudRed
            else -> HudDim
        }
        val tdpLabel = if (state.autoTdpRunning) {
            "AUTOTDP - ${HudDisplayUtils.formatAutoTdpProfileShort(state.autoTdpActiveProfile.name)}"
        } else "AUTOTDP OFF"
        StatusPill(text = tdpLabel, accent = tdpAccent)

        Spacer(Modifier.weight(1f))

        // Size toggle
        HudIconChip(label = HudDisplayUtils.hudSizeLabel(state.hudSizeIndex), onClick = onCycleHudSize)
        Spacer(Modifier.width(2.dp))

        // Recording dot
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onToggleRecord)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state.isRecording) {
                    val m = state.recordingElapsedSeconds / 60
                    val s = state.recordingElapsedSeconds % 60
                    "R"
                } else "R",
                color = if (state.isRecording) HudRed else HudDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (state.isRecording) {
            val m = state.recordingElapsedSeconds / 60
            val s = state.recordingElapsedSeconds % 60
            Text(
                text = "%d:%02d".format(m, s),
                color = HudRed,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(3.dp))
        }

        // Layout swap (to compact)
        IconButton(onClick = onCycleLayout, modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Outlined.SwapHoriz,
                contentDescription = "Collapse to compact",
                tint = HudValue,
                modifier = Modifier.size(13.dp),
            )
        }

        // Close
        IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Hide overlay",
                tint = HudDim,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

// ── Full panel: Body (FPS block left | 4-metric grid right) ─────────────────

@Composable
private fun FullPanelBody(state: HudUiState) {
    val fpsColor = if (state.gameFpsIsReal) HudEmerald else HudDim
    val frameMs = state.gameFps?.takeIf { it > 0 }?.let { "%.1fms".format(1000f / it) }
    val fpsLabel = if (state.gameFpsIsReal) "FPS" else "REFRESH"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LEFT -- FPS hero block
        Column(
            modifier = Modifier
                .width(90.dp)
                .semantics {
                    contentDescription = if (state.gameFps != null) {
                        "${state.gameFps} ${if (state.gameFpsIsReal) "game frames per second" else "refresh Hz"}"
                    } else "FPS unavailable"
                },
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.gameFps?.toString() ?: "--",
                    color = fpsColor,
                    fontSize = 40.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 40.sp,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = fpsLabel,
                    color = fpsColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
            if (frameMs != null) {
                Text(
                    text = "$frameMs",
                    color = HudLabel,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Thin vertical rule
        Box(
            modifier = Modifier
                .width(0.5.dp)
                .height(60.dp)
                .background(HudBorder),
        )

        // RIGHT -- 4-wide metric grid
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            FullMetricTile(
                label = "CPU",
                value = HudDisplayUtils.formatGhzFromMhz(state.cpuMaxMhz.takeIf { it > 0 }),
                subLine = "${state.cpuLoadPct}%",
                accent = HudBlue,
                modifier = Modifier.weight(1f),
            )
            FullMetricTile(
                label = "GPU",
                value = state.gpuMhz?.let { "${it}M" } ?: "--",
                subLine = state.gpuLoadPct?.let { "${it}%" } ?: "--",
                accent = HudPurple,
                modifier = Modifier.weight(1f),
            )
            FullMetricTile(
                label = "POWER",
                value = HudDisplayUtils.formatWatts(state.batteryW),
                subLine = state.ramUsedPct?.let { "RAM ${it}%" } ?: "--",
                accent = HudAmber,
                modifier = Modifier.weight(1f),
            )
            val tempHot = state.cpuTempC != null && state.cpuTempC >= 80f
            FullMetricTile(
                label = "TEMP",
                value = HudDisplayUtils.formatTemp(state.cpuTempC),
                subLine = state.gpuTempC?.let { "gpu %.0f".format(it) } ?: "--",
                accent = if (tempHot) HudAmber else HudEmerald,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Full-panel metric tile: label (9sp muted) + big mono value + tiny sub-line + 2dp accent bar.
 */
@Composable
private fun FullMetricTile(
    label: String,
    value: String,
    subLine: String,
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
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 3.dp),
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
            Text(
                text = subLine,
                color = HudDim,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        // 2dp colored accent bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(accent, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)),
        )
    }
}

// ── AutoTDP proof-of-effect section ──────────────────────────────────────────

/**
 * The "is AutoTDP REALLY working" proof section.
 *
 * STOPPED → Start button + 3-segment profile picker (unchanged controls).
 *
 * RUNNING → a stacked proof block, every row HONESTY-GATED (a row only renders
 * when its backing field is non-null; nulls hide, never fake):
 *   1. HEADER   — "AUTOTDP PROOF" label + heartbeat pulse dot/age + STOP.
 *   2. CHANGED  — cap+delta / parked cores / GPU floor, each hidden if absent;
 *                 "Holding at stock" when nothing is applied.
 *   3. WHY      — clean hold-reason label, tap to expand the raw engine reason
 *                 (carries live %s). LOAD_BLIND reads "load unreadable", never idle.
 *   4. EFFECT   — measured session Wh saved; if null, an honest "measuring…" hint.
 *   5. TICKER   — last-N decisions + a flat-ok cap sparkline (flat = holding steady).
 *   6. CONTROL  — profile picker (EFF/BAL/TGT) so the user can switch while running.
 */
@Composable
private fun AutoTdpProofSection(
    state: HudUiState,
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
) {
    if (!state.autoTdpRunning) {
        // ── STOPPED: Start + profile picker ──────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HudChipBtn(
                label = "Start AutoTDP",
                enabled = state.autoTdpStatus != AutoTdpStatus.LIVE_UNAVAILABLE,
                accent = HudEmerald,
                onClick = onToggleAutoTdp,
            )
            Spacer(Modifier.weight(1f))
            AutoTdpProfilePicker(
                active = state.autoTdpActiveProfile,
                onSetAutoTdpProfile = onSetAutoTdpProfile,
            )
        }
        // LIVE_UNAVAILABLE is an honest, non-faked state worth surfacing.
        if (state.autoTdpStatus == AutoTdpStatus.LIVE_UNAVAILABLE) {
            Text(
                text = "live tuning unavailable on this device",
                color = HudDim,
                fontSize = 8.sp,
                lineHeight = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        return
    }

    // ── RUNNING: proof block ─────────────────────────────────────────────────
    val nowMs = System.currentTimeMillis()
    val live = HudDisplayUtils.heartbeatIsLive(state.autoTdpLastAppliedEpochMs, nowMs)
    val heartbeatText = HudDisplayUtils.heartbeatLabel(state.autoTdpLastAppliedEpochMs, nowMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(HudAutoTdpBg)
            .border(0.5.dp, HudAutoTdpBorder, RoundedCornerShape(5.dp))
            .drawBehind {
                // Emerald left-edge accent bar — "this section is live".
                drawRect(if (live) HudEmerald else HudDim, size = size.copy(width = 2.dp.toPx()))
            }
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 1. HEADER — label + heartbeat + STOP ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Outlined.Bolt, null, tint = HudEmerald, modifier = Modifier.size(11.dp))
            Text(
                text = "AUTOTDP - ${HudDisplayUtils.formatAutoTdpProfileShort(state.autoTdpActiveProfile.name)}",
                color = HudEmerald,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
            Spacer(Modifier.weight(1f))
            // 4. HEARTBEAT — pulse dot + "adjusted Xs ago" (hidden if no tick yet)
            HeartbeatDot(live = live, size = 6.dp)
            if (heartbeatText != null) {
                Text(
                    text = heartbeatText,
                    color = if (live) HudEmerald else HudAmber,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "AutoTDP ${if (live) "live" else "stalled"}, $heartbeatText"
                    },
                )
            }
            Spacer(Modifier.width(2.dp))
            HudChipBtn(label = "STOP", enabled = true, accent = HudRed, onClick = onToggleAutoTdp)
        }

        // 2. WHAT IT CHANGED NOW ──────────────────────────────────────────────
        AutoTdpChangedRows(state)

        // 3. WHY (hold-reason, tap to expand raw) ─────────────────────────────
        AutoTdpWhyRow(state)

        // 5. EFFECT (measured only) ───────────────────────────────────────────
        AutoTdpEffectRow(state)

        // 6. DECISION TICKER + sparkline ──────────────────────────────────────
        if (state.autoTdpDecisions.isNotEmpty()) {
            AutoTdpDecisionTicker(state.autoTdpDecisions)
        }

        // 7. CONTROL — profile picker (switch while running) ──────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PROFILE",
                color = HudLabel,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.weight(1f))
            AutoTdpProfilePicker(
                active = state.autoTdpActiveProfile,
                onSetAutoTdpProfile = onSetAutoTdpProfile,
            )
        }
    }
}

/** 3-segment EFF/BAL/TGT profile picker. Shared by running + stopped states. */
@Composable
private fun AutoTdpProfilePicker(
    active: AutoTdpProfile,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            AutoTdpProfile.EFFICIENCY to "EFF",
            AutoTdpProfile.BALANCED to "BAL",
            AutoTdpProfile.BATTERY_TARGET to "TGT",
        ).forEach { (profile, shortLabel) ->
            AutoTdpProfileChip(
                label = shortLabel,
                selected = active == profile,
                onClick = { onSetAutoTdpProfile(profile) },
            )
        }
    }
}

/**
 * "WHAT IT CHANGED NOW" — DERIVED facts (live immediately). Each line is hidden
 * when its field is absent. When NOTHING is applied, a single honest
 * "Holding at stock" line is shown instead.
 */
@Composable
private fun AutoTdpChangedRows(state: HudUiState) {
    val capLine = HudDisplayUtils.formatCapLine(state.autoTdpBigCapMhz, state.autoTdpCapDeltaMhz)
    val parkedLine = HudDisplayUtils.formatParkedCoresLine(state.autoTdpParkedCores)
    val gpuLine = HudDisplayUtils.formatGpuLevelLine(state.autoTdpGpuLevel)
    val holdingAtStock = HudDisplayUtils.isHoldingAtStock(
        state.autoTdpBigCapMhz, state.autoTdpParkedCores, state.autoTdpGpuLevel,
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (holdingAtStock) {
            ProofLine(dot = HudDim, text = "Holding at stock", color = HudValue)
        } else {
            if (capLine != null) ProofLine(dot = HudEmerald, text = capLine, color = HudValue)
            if (parkedLine != null) ProofLine(dot = HudBlue, text = parkedLine, color = HudValue)
            if (gpuLine != null) ProofLine(dot = HudPurple, text = gpuLine, color = HudValue)
        }
    }
}

/** One DERIVED-fact line: colored dot + mono text. */
@Composable
private fun ProofLine(dot: Color, text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.semantics { contentDescription = text },
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(dot),
        )
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * "WHY" — clean hold-reason label; tap to reveal the raw engine reason (which
 * carries the live %s like "GPU 92%, big/prime 18%"). Honesty: LOAD_BLIND reads
 * "CPU load unreadable - holding", never "idle".
 */
@Composable
private fun AutoTdpWhyRow(state: HudUiState) {
    var expanded by remember { mutableStateOf(false) }
    val label = HudDisplayUtils.holdReasonLabel(state.autoTdpHoldReason)
    val hasRaw = state.autoTdpReason.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasRaw) { expanded = !expanded }
                .semantics {
                    contentDescription =
                        "Reason: $label." + if (hasRaw) " Tap to ${if (expanded) "hide" else "show"} detail." else ""
                },
        ) {
            Text(
                text = "WHY",
                color = HudLabel,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Text(
                text = label,
                color = HudAmber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (hasRaw) {
                Text(
                    text = if (expanded) "[hide]" else "[detail]",
                    color = HudDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (expanded && hasRaw) {
            Text(
                text = state.autoTdpReason,
                color = HudDim,
                fontSize = 8.sp,
                lineHeight = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/**
 * "EFFECT" — MEASURED-only. Shows session Wh saved (+ power-saved when the probe
 * is ready). When no measured number exists yet, shows an honest one-line
 * "measuring…" hint — NEVER a fabricated value.
 */
@Composable
private fun AutoTdpEffectRow(state: HudUiState) {
    val whLine = HudDisplayUtils.formatSessionWhLine(state.autoTdpSessionWh)
    val powerReady = state.autoTdpSavingsReady && state.autoTdpSavingsMw != null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "EFFECT",
            color = HudLabel,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        if (whLine != null || powerReady) {
            // At least one MEASURED number is available — show it.
            val parts = buildList {
                if (powerReady) {
                    add(
                        HudDisplayUtils.formatAutoTdpSavings(
                            state.autoTdpSavingsMw,
                            state.autoTdpSavingsPct,
                            state.autoTdpSavingsReady,
                        ),
                    )
                }
                if (whLine != null) add(whLine)
            }
            Text(
                text = parts.joinToString("  -  "),
                color = HudEmerald,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Measured effect: ${parts.joinToString(", ")}" },
            )
        } else {
            // No measured probe yet — honest hint, not a number.
            Text(
                text = "measuring... (first ~40s)",
                color = HudDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Effect measuring, first reading in about 40 seconds" },
            )
        }
    }
}

/**
 * "DECISION TICKER" — the last few engine decisions (newest-first) plus a tiny
 * cap-vs-time sparkline. A flat sparkline honestly reads as "holding steady".
 */
@Composable
private fun AutoTdpDecisionTicker(decisions: List<DecisionRecord>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "RECENT",
                color = HudLabel,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            CapSparkline(
                decisions = decisions,
                modifier = Modifier
                    .weight(1f)
                    .height(14.dp),
            )
        }
        // Last 3 decisions, newest-first: reason + cap.
        decisions.takeLast(3).reversed().forEach { d ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(holdReasonDot(d.holdReason)),
                )
                Text(
                    text = HudDisplayUtils.holdReasonLabel(d.holdReason),
                    color = HudDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = HudDisplayUtils.formatDecisionCap(d.bigCapKhz),
                    color = HudDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Tiny cap-vs-time sparkline from the decision history. Uncapped (null) decisions
 * sit at the top of the track (= stock ceiling). A flat line is honest: the
 * engine has been holding the cap steady. Needs >= 2 points to draw a line.
 */
@Composable
private fun CapSparkline(decisions: List<DecisionRecord>, modifier: Modifier = Modifier) {
    val caps = decisions.map { it.bigCapKhz }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(HudBarBg)
            .semantics { contentDescription = "AutoTDP cap history sparkline" },
    ) {
        if (caps.size >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .drawBehind {
                        val known = caps.filterNotNull()
                        // Range for normalisation; uncapped -> top (stock ceiling).
                        val maxCap = (known.maxOrNull() ?: 0)
                        val minCap = (known.minOrNull() ?: 0)
                        val span = (maxCap - minCap).coerceAtLeast(1)
                        val n = caps.size
                        val stepX = if (n > 1) size.width / (n - 1) else size.width
                        val pad = 2f
                        val usableH = size.height - pad * 2
                        fun yFor(cap: Int?): Float {
                            // null cap = uncapped = at the ceiling (top, small y).
                            val c = cap ?: maxCap
                            val frac = (c - minCap).toFloat() / span
                            // Higher cap -> higher on screen (smaller y).
                            return pad + (1f - frac) * usableH
                        }
                        val path = Path()
                        caps.forEachIndexed { i, cap ->
                            val x = i * stepX
                            val y = yFor(cap)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = HudEmerald,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                        )
                    },
            )
        }
    }
}

/** Dot colour for a decision's hold-reason in the ticker. */
private fun holdReasonDot(reason: io.github.mayusi.calibratesoc.data.autotdp.HoldReason): Color =
    when (reason) {
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.CPU_BOUND_RELAXING -> HudBlue
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.GPU_BOUND_CAPPING -> HudPurple
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.BATTERY_TARGET_HOLDING -> HudAmber
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.LOAD_BLIND_HOLDING -> HudAmber
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.IDLE_HOLDING -> HudDim
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.NO_TELEMETRY -> HudDim
    }

// ── Full panel: Quick profiles ───────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullProfileChips(
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
            HudSelectChip(
                label = name.take(12),
                selected = false,
                accent = HudBlue,
                onClick = { onApplyProfile(id) },
            )
        }
        HudChipBtn(label = "NEXT", enabled = chips.size > 1, accent = HudBlue, onClick = onCycleNextProfile)
    }
    Text(
        text = "tap writes script - open vendor settings to run",
        color = HudDim,
        fontSize = 8.sp,
        lineHeight = 11.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

// ── Full panel: Refresh rate section ────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullRefreshRateSection(
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
        HudSelectChip(
            label = "AUTO",
            selected = pinnedHz == null,
            accent = HudPurple,
            onClick = { onSetRefreshHz(null) },
        )
        availableHz.forEach { hz ->
            HudSelectChip(
                label = HudDisplayUtils.formatHz(hz),
                selected = pinnedHz != null && kotlin.math.abs(hz - pinnedHz) < 0.5f,
                accent = HudPurple,
                onClick = { onSetRefreshHz(hz) },
            )
        }
    }
}

// ── Full panel: Opacity row ──────────────────────────────────────────────────

@Composable
private fun FullOpacityRow(
    hudOpacity: Float,
    onSetOpacity: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        VerboseSectionLabel("OPACITY", HudLabel)
        Spacer(Modifier.weight(1f))
        HudChipBtn(label = "-", enabled = hudOpacity > 0.2f, accent = HudLabel) {
            onSetOpacity((hudOpacity - 0.1f).coerceAtLeast(0.1f))
        }
        Text(
            text = HudDisplayUtils.formatOpacityPct(hudOpacity),
            color = HudValue,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        HudChipBtn(label = "+", enabled = hudOpacity < 1f, accent = HudLabel) {
            onSetOpacity((hudOpacity + 0.1f).coerceAtMost(1f))
        }
    }
}

// ── Full panel: Per-core bars ─────────────────────────────────────────────────

@Composable
private fun FullPerCoreSection(state: HudUiState) {
    VerboseSectionLabel("PER-CORE", HudBlue)
    Spacer(Modifier.height(3.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        state.perCoreMhz.forEachIndexed { idx, mhz ->
            val loadPct = state.perCoreLoadPct.getOrNull(idx) ?: 0
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
                else "Core $idx $mhz MHz $loadPct percent load"
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
            text = if (isParked) "PARKED" else "$mhz-$loadPct%",
            color = textColor,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(66.dp),
        )
    }
}

// ── Full panel: Thermal row ────────────────────────────────────────────────────

@Composable
private fun FullThermalRow(
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
            color = if (hot) HudAmber else HudValue,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared micro-components
// ─────────────────────────────────────────────────────────────────────────────

/** Section label: colored tick + uppercase title. */
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
    HorizontalDivider(thickness = 0.5.dp, color = HudBorder)
}

/**
 * Arsenal-style small angular action button.
 * Uses [CutCornerShape] (TopEnd cut) for the angular look.
 */
@Composable
private fun HudChipBtn(
    label: String,
    enabled: Boolean,
    accent: Color = HudBlue,
    onClick: () -> Unit,
) {
    val shape = CutCornerShape(corner = CutCorner.TopEnd, cutSize = 7.dp, cornerRadius = 3.dp)
    val bg = if (enabled) accent.copy(alpha = 0.18f) else Color.Transparent
    val border = if (enabled) accent.copy(alpha = 0.6f) else HudBorder
    val fg = if (enabled) accent else HudDim

    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(0.5.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = fg,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Generic selectable chip: profiles, Hz options, etc. */
@Composable
private fun HudSelectChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color(0xFF0D1825))
            .border(0.5.dp, if (selected) accent else HudBorder, RoundedCornerShape(4.dp))
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

/** AutoTDP profile segment chip. */
@Composable
private fun AutoTdpProfileChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = HudEmerald
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color(0xFF0D1825))
            .border(0.5.dp, if (selected) accent else HudBorder, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = if (selected) accent else HudLabel,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Tiny header chip for size label. */
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
