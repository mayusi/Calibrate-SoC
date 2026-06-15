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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus

// ── Brand palette ─────────────────────────────────────────────────────────────

private val ColorEmerald   = Color(0xFF34D399) // FPS / AutoTDP / good
private val ColorBlue      = Color(0xFF60A5FA) // CPU/GPU accent, per-core bars, header
private val ColorPurple    = Color(0xFFA78BFA) // power/watts
private val ColorAmber     = Color(0xFFFBBF24) // hot temp
private val ColorLabel     = Color(0xFF64748B) // muted labels
private val ColorValue     = Color(0xFFE2E8F0) // value text
private val ColorDim       = Color(0xFF475569) // dim / parked
private val ColorBgMain    = Color(0xF00B1220) // container background
private val ColorBorder    = Color(0xFF1F2937) // container border
private val ColorAutoTdpBg = Color(0xFF0F2A22) // AutoTDP panel bg
private val ColorAutoTdpBorder = Color(0xFF16543F) // AutoTDP panel border
private val ColorBarBg     = Color(0xFF1E293B) // per-core bar background
private val ColorRed       = Color(0xFFEF4444) // error states

/**
 * The HUD's Compose tree. Two layouts:
 *
 * **Compact** — FPS hero row, 3-col metric grid, AutoTDP strip.
 * **Verbose** — FPS hero, AutoTDP block, per-core section, thermal cards.
 *
 * Both share the same header and the live-tune controls at the bottom.
 * Honesty-first: only real state fields are displayed; nulls render as "—".
 * 1%-low and time-left estimates are omitted (no backing fields in HudUiState).
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
    onClose: () -> Unit,
) {
    val width = if (state.profile == HudProfile.COMPACT) 250.dp else 270.dp
    Box(
        modifier = Modifier
            .widthIn(min = width, max = width)
            .clip(RoundedCornerShape(12.dp))
            .background(ColorBgMain)
            .border(0.5.dp, ColorBorder, RoundedCornerShape(12.dp)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            HudHeader(
                profile = state.profile,
                isRecording = state.isRecording,
                recordingElapsedSeconds = state.recordingElapsedSeconds,
                onCycleLayout = onCycleLayout,
                onToggleRecord = onToggleRecord,
                onClose = onClose,
            )
            Spacer(Modifier.height(4.dp))
            when (state.profile) {
                HudProfile.COMPACT -> HudCompactContent(state)
                HudProfile.VERBOSE -> HudVerboseContent(state)
            }
            // Live-tune controls — gated behind canTuneLive flag.
            // When AutoTDP is running, HudTuneRow shows explanatory text
            // instead of steppers (shouldGateSteppers gate).
            if (state.canTuneLive) {
                HudClusterChips(state, onTogglePolicy)
                HudTuneRow(
                    state = state,
                    onStepMhz = onStepMhz,
                    onCycleNextProfile = onCycleNextProfile,
                    onPickStepSize = onPickStepSize,
                )
            }
            if (state.profile == HudProfile.VERBOSE && state.quickProfiles.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                HudProfileChips(state.quickProfiles, onApplyProfile)
            }
            state.lastActionMessage?.let { msg ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg,
                    color = Color(0xFFFFE0A0),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun HudHeader(
    profile: HudProfile,
    isRecording: Boolean,
    recordingElapsedSeconds: Long,
    onCycleLayout: () -> Unit,
    onToggleRecord: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "CALIBRATE · ${profile.name.lowercase()}",
            color = ColorBlue,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.weight(1f))
        // Recording indicator: red dot + elapsed timer
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleRecord)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isRecording) "●" else "●",
                color = if (isRecording) Color(0xFFEF4444) else ColorDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (isRecording) {
            val mins = recordingElapsedSeconds / 60
            val secs = recordingElapsedSeconds % 60
            Text(
                text = "%d:%02d".format(mins, secs),
                color = Color(0xFFEF4444),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onCycleLayout, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.SwapVert,
                contentDescription = "Swap layout",
                tint = ColorValue,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Hide overlay",
                tint = ColorValue,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ── Compact layout ────────────────────────────────────────────────────────────

@Composable
private fun HudCompactContent(state: HudUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 1. FPS hero row
        FpsHero(
            gameFps = state.gameFps,
            gameFpsIsReal = state.gameFpsIsReal,
            compact = true,
        )
        // 2. 3-col metric grid
        MetricGrid(state)
        // 3. AutoTDP strip — only when running
        if (state.autoTdpRunning) {
            AutoTdpCompactStrip(state)
        }
    }
}

// ── Verbose layout ─────────────────────────────────────────────────────────────

@Composable
private fun HudVerboseContent(state: HudUiState) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .heightIn(max = 400.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. FPS hero row (verbose variant)
        FpsHero(
            gameFps = state.gameFps,
            gameFpsIsReal = state.gameFpsIsReal,
            compact = false,
            batteryW = state.batteryW,
        )
        // 2. Divider
        HorizontalDivider(thickness = 0.5.dp, color = ColorBorder)
        // 3. AutoTDP block — when not IDLE
        if (state.autoTdpStatus != AutoTdpStatus.IDLE) {
            AutoTdpPanel(state)
        }
        // 4. Per-core section
        if (state.perCoreMhz.isNotEmpty()) {
            PerCoreSection(state)
        }
        // 5. Thermal section
        if (state.cpuTempC != null || state.gpuTempC != null || state.batteryTempC != null) {
            ThermalCards(
                cpuTempC = state.cpuTempC,
                gpuTempC = state.gpuTempC,
                batteryTempC = state.batteryTempC,
            )
        }
    }
}

// ── FPS Hero ──────────────────────────────────────────────────────────────────

/**
 * FPS hero row.
 *
 * Compact: big gameFps + "FPS"/"REFRESH" label + frametime right-aligned.
 * Verbose: same hero + batteryW block far right.
 *
 * Honesty:
 * - gameFpsIsReal == true → label "FPS" in emerald; false → "REFRESH" muted.
 * - frametime = 1000f / gameFps. Only shown when gameFps != null && > 0.
 * - 1%-low omitted (no HudUiState field).
 * - batteryW shown as-is; no time-left estimate (no HudUiState field).
 */
@Composable
private fun FpsHero(
    gameFps: Int?,
    gameFpsIsReal: Boolean,
    compact: Boolean,
    batteryW: Double? = null,
) {
    val heroSize = if (compact) 40.sp else 44.sp
    val fpsLabel = if (gameFpsIsReal) "FPS" else "REFRESH"
    val fpsLabelColor = if (gameFpsIsReal) ColorEmerald else ColorLabel
    val frametimeText: String? = if (gameFps != null && gameFps > 0) {
        "%.1fms".format(1000f / gameFps)
    } else null

    val a11yDesc = if (gameFps != null) {
        "$gameFps ${if (gameFpsIsReal) "game frames per second" else "refresh Hz"}"
    } else "FPS unavailable"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yDesc },
    ) {
        // Big FPS number
        Text(
            text = gameFps?.toString() ?: "—",
            color = ColorEmerald,
            fontSize = heroSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(6.dp))
        // FPS / REFRESH label + frametime stacked
        Column {
            Text(
                text = fpsLabel,
                color = fpsLabelColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            frametimeText?.let {
                Text(
                    text = if (compact) it else "avg $it",
                    color = ColorLabel,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Verbose only: battery watts block on far right
        if (!compact && batteryW != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%.1fW".format(batteryW),
                    color = ColorPurple,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                // Note: no time-left estimate — HudUiState has no such field.
            }
        } else if (compact && frametimeText != null) {
            // Frametime already embedded in label column above
        }
    }
}

// ── Metric Grid (Compact only) ────────────────────────────────────────────────

/**
 * 3-col metric grid for the compact layout.
 * Labels: CPU, GPU, PWR, Tc, Tg, RAM — skips nulls gracefully (shows "—").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricGrid(state: HudUiState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 3,
    ) {
        MetricCell(
            label = "CPU",
            value = "${state.cpuMaxMhz}",
            unit = "MHz",
            color = ColorBlue,
        )
        MetricCell(
            label = "GPU",
            value = state.gpuMhz?.toString() ?: "—",
            unit = if (state.gpuMhz != null) "MHz" else "",
            color = ColorBlue,
        )
        MetricCell(
            label = "PWR",
            value = state.batteryW?.let { "%.1f".format(it) } ?: "—",
            unit = if (state.batteryW != null) "W" else "",
            color = ColorPurple,
        )
        MetricCell(
            label = "Tc",
            value = state.cpuTempC?.let { "%.0f".format(it) } ?: "—",
            unit = if (state.cpuTempC != null) "°" else "",
            color = if (state.cpuTempC != null && state.cpuTempC >= 80f) ColorAmber else ColorValue,
        )
        MetricCell(
            label = "Tg",
            value = state.gpuTempC?.let { "%.0f".format(it) } ?: "—",
            unit = if (state.gpuTempC != null) "°" else "",
            color = if (state.gpuTempC != null && state.gpuTempC >= 80f) ColorAmber else ColorValue,
        )
        MetricCell(
            label = "RAM",
            value = state.ramUsedPct?.let { "$it" } ?: "—",
            unit = if (state.ramUsedPct != null) "%" else "",
            color = ColorValue,
        )
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    unit: String,
    color: Color = ColorValue,
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            color = ColorLabel,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    color = ColorLabel,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(start = 1.dp, bottom = 1.dp),
                )
            }
        }
    }
}

// ── AutoTDP compact strip ─────────────────────────────────────────────────────

/**
 * Compact-layout AutoTDP pill. Shown only when autoTdpRunning.
 *
 * Example: "AutoTDP  park 6,7 · 1.69G  −1.8W" (savings only when ready).
 * Reuses [HudDisplayUtils.formatAutoTdpCompactLine] for the core text.
 * Honesty: savings shown only when autoTdpSavingsReady; else "measuring…".
 */
@Composable
private fun AutoTdpCompactStrip(state: HudUiState) {
    val savingsText: String = when {
        state.autoTdpSavingsReady && state.autoTdpSavingsMw != null ->
            "−${"%.1f".format(state.autoTdpSavingsMw / 1000.0)}W"
        else -> "measuring…"
    }
    val parkedStr = if (state.autoTdpParkedCores.isNotEmpty()) {
        "park ${state.autoTdpParkedCores.sorted().joinToString(",")} · "
    } else ""
    val capStr = state.autoTdpBigCapMhz?.let { "${"%.2f".format(it / 1000.0)}G" } ?: ""
    val middleText = buildString {
        append(parkedStr)
        if (capStr.isNotEmpty()) append(capStr)
    }.trimEnd(' ', '·').trim()

    val a11y = buildString {
        append("AutoTDP running")
        if (state.autoTdpParkedCores.isNotEmpty()) {
            append(", cores parked: ${state.autoTdpParkedCores.sorted().joinToString()}")
        }
        state.autoTdpBigCapMhz?.let { append(", cap $it MHz") }
        if (state.autoTdpSavingsReady && state.autoTdpSavingsMw != null) {
            append(", saving ${state.autoTdpSavingsMw} milliwatts")
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ColorAutoTdpBg)
            .border(0.5.dp, ColorAutoTdpBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Outlined.Bolt,
            contentDescription = null,
            tint = ColorEmerald,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = "AutoTDP",
            color = ColorEmerald,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (middleText.isNotEmpty()) {
            Text(
                text = middleText,
                color = ColorLabel,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = savingsText,
            color = if (state.autoTdpSavingsReady) ColorEmerald else ColorLabel,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ── AutoTDP verbose panel ─────────────────────────────────────────────────────

/**
 * Full AutoTDP block for the verbose layout. Shown when autoTdpStatus != IDLE.
 *
 * Header: bolt + "AutoTDP · RUNNING" (color-coded by status) + savings on right.
 * Body: parked cores · big cap · GPU level · reason (truncated 50 chars).
 * Honesty: savings numbers never shown unless autoTdpSavingsReady.
 */
@Composable
private fun AutoTdpPanel(state: HudUiState) {
    val statusLabel = when (state.autoTdpStatus) {
        AutoTdpStatus.RUNNING -> "RUNNING"
        AutoTdpStatus.IDLE -> "IDLE"
        AutoTdpStatus.LIVE_UNAVAILABLE -> "UNAVAILABLE"
        AutoTdpStatus.KILLED_BY_SAFETY -> "SAFETY KILL"
        AutoTdpStatus.WRITE_DENIED -> "WRITE DENIED"
        AutoTdpStatus.STOPPED -> "STOPPED"
    }
    val statusColor = when (state.autoTdpStatus) {
        AutoTdpStatus.RUNNING -> ColorEmerald
        AutoTdpStatus.KILLED_BY_SAFETY,
        AutoTdpStatus.WRITE_DENIED -> ColorRed
        AutoTdpStatus.LIVE_UNAVAILABLE -> ColorAmber
        else -> ColorDim
    }

    val savingsText: String? = when {
        state.autoTdpSavingsReady && state.autoTdpSavingsMw != null && state.autoTdpSavingsPct != null ->
            "−${"%.1f".format(state.autoTdpSavingsMw / 1000.0)}W · ${"%.0f".format(state.autoTdpSavingsPct)}%"
        state.autoTdpRunning -> "measuring…"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ColorAutoTdpBg)
            .border(0.5.dp, ColorAutoTdpBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Header row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "AutoTDP · $statusLabel",
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            savingsText?.let {
                Text(
                    text = it,
                    color = if (state.autoTdpSavingsReady) ColorEmerald else ColorLabel,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Detail rows (only when running)
        if (state.autoTdpRunning) {
            val detailParts = buildList {
                if (state.autoTdpParkedCores.isNotEmpty()) {
                    add("parked cpu${state.autoTdpParkedCores.sorted().joinToString(",")}")
                }
                state.autoTdpBigCapMhz?.let {
                    add("big cap ${"%.2f".format(it / 1000.0)}G")
                }
                state.autoTdpGpuLevel?.let {
                    add("GPU lvl $it")
                }
            }
            if (detailParts.isNotEmpty()) {
                Text(
                    text = detailParts.joinToString(" · "),
                    color = ColorLabel,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp,
                )
            }
            if (state.autoTdpReason.isNotBlank()) {
                Text(
                    text = state.autoTdpReason.take(50),
                    color = ColorLabel,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }
        }
    }
}

// ── Per-core section ──────────────────────────────────────────────────────────

/**
 * Verbose per-core section.
 *
 * One row per core: "cN" + bar filled by load% + "MHz·load%" right-aligned.
 * Parked cores (index in autoTdpParkedCores): greyed row, empty bar, "PARKED" label.
 * cpu0 is never treated as parked even if the set contains 0.
 */
@Composable
private fun PerCoreSection(state: HudUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = "PER-CORE · MHz / load",
            color = ColorLabel,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        state.perCoreMhz.forEachIndexed { idx, mhz ->
            val loadPct = state.perCoreLoadPct.getOrNull(idx) ?: 0
            // cpu0 is never parked per spec
            val isParked = idx != 0 && idx in state.autoTdpParkedCores
            PerCoreBar(
                idx = idx,
                mhz = mhz,
                loadPct = loadPct,
                isParked = isParked,
            )
        }
    }
}

@Composable
private fun PerCoreBar(
    idx: Int,
    mhz: Int,
    loadPct: Int,
    isParked: Boolean,
) {
    val loadFrac = (loadPct / 100f).coerceIn(0f, 1f)
    val textColor = if (isParked) ColorDim else ColorValue
    val barFillColor = if (isParked) Color.Transparent else ColorBlue

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (isParked) {
                    "Core $idx parked"
                } else {
                    "Core $idx — $mhz MHz, $loadPct percent load"
                }
            },
    ) {
        Text(
            text = "c$idx",
            color = textColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ColorBarBg),
        ) {
            if (!isParked) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(loadFrac)
                        .height(7.dp)
                        .background(barFillColor),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (isParked) "PARKED" else "$mhz·$loadPct%",
            color = textColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(72.dp),
        )
    }
}

// ── Thermal cards ─────────────────────────────────────────────────────────────

/**
 * Verbose thermal section: 3 mini-cards in a row for CPU / GPU / BATT.
 * CPU card turns amber-bg (0xFF1A130A) when temp >= 80°C.
 * Null temps render as "—" card.
 */
@Composable
private fun ThermalCards(
    cpuTempC: Float?,
    gpuTempC: Float?,
    batteryTempC: Float?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "THERMAL",
            color = ColorLabel,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ThermalCard(
                label = "CPU",
                tempC = cpuTempC,
                modifier = Modifier.weight(1f),
            )
            ThermalCard(
                label = "GPU",
                tempC = gpuTempC,
                modifier = Modifier.weight(1f),
            )
            ThermalCard(
                label = "BATT",
                tempC = batteryTempC,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ThermalCard(
    label: String,
    tempC: Float?,
    modifier: Modifier = Modifier,
) {
    val hot = tempC != null && tempC >= 80f
    val cardBg = if (hot) Color(0xFF1A130A) else ColorBarBg
    val valueColor = if (hot) ColorAmber else ColorValue

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cardBg)
            .border(0.5.dp, ColorBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = ColorLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            Text(
                text = tempC?.let { "%.0f°".format(it) } ?: "—",
                color = valueColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── Cluster chips ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudClusterChips(state: HudUiState, onTogglePolicy: (Int) -> Unit) {
    if (state.allPolicies.size <= 1) return
    Spacer(Modifier.height(4.dp))
    val enabled = state.enabledPolicies.ifEmpty { state.allPolicies.toSet() }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "clusters:",
            color = ColorLabel,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp, end = 2.dp),
        )
        state.allPolicies.forEach { pid ->
            val on = pid in enabled
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) Color(0x3360A5FA) else Color(0x22FFFFFF))
                    .border(
                        0.5.dp,
                        if (on) ColorBlue else ColorBorder,
                        RoundedCornerShape(5.dp),
                    )
                    .clickable { onTogglePolicy(pid) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    "p$pid",
                    color = if (on) ColorBlue else ColorValue,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// ── Tune row ──────────────────────────────────────────────────────────────────

/**
 * ± MHz steppers for manual live-tuning.
 *
 * Gated when autoTdpRunning ([HudDisplayUtils.shouldGateSteppers]) — shows
 * explanatory text so the user knows why the controls are absent.
 */
@Composable
private fun HudTuneRow(
    state: HudUiState,
    onStepMhz: (Int) -> Unit,
    onCycleNextProfile: () -> Unit,
    onPickStepSize: (Int) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    if (HudDisplayUtils.shouldGateSteppers(state.autoTdpRunning)) {
        Text(
            text = "AutoTDP is managing clocks — stop it to tune",
            color = ColorLabel,
            fontSize = 9.sp,
            lineHeight = 11.sp,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneButton(label = "−", enabled = state.bigCorePolicy != null) {
            onStepMhz(-state.stepMhz)
        }
        StepSizeDropdown(state.stepMhz, onPickStepSize)
        TuneButton(label = "+", enabled = state.bigCorePolicy != null) {
            onStepMhz(state.stepMhz)
        }
        val centerLabel = state.bigCoreCurrentMhz?.let { "${it}MHz" } ?: "—"
        Text(
            centerLabel,
            color = ColorValue,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        if (state.quickProfiles.isNotEmpty()) {
            TuneButton(label = "Next →", enabled = true, onClick = onCycleNextProfile)
        }
    }
}

@Composable
private fun StepSizeDropdown(currentMhz: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0x3360A5FA))
                .border(0.5.dp, ColorBlue, RoundedCornerShape(5.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                "${currentMhz}▾",
                color = ColorBlue,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            listOf(50, 100, 200, 300, 500, 1000).forEach { mhz ->
                DropdownMenuItem(
                    text = { Text("± $mhz MHz") },
                    onClick = {
                        onPick(mhz)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TuneButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Color(0x3360A5FA) else Color(0x22FFFFFF)
    val fg = if (enabled) ColorBlue else ColorDim
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.5.dp, if (enabled) ColorBlue else ColorBorder, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Profile chips ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudProfileChips(
    chips: List<Pair<String, String>>,
    onApplyProfile: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Apply (writes script · open Odin Settings to run)",
            color = ColorLabel,
            fontSize = 9.sp,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            chips.forEach { (id, name) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x1A60A5FA))
                        .border(0.5.dp, ColorBorder, RoundedCornerShape(6.dp))
                        .clickable { onApplyProfile(id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(name.take(18), color = ColorValue, fontSize = 10.sp)
                }
            }
        }
    }
}

// ── Color helpers ─────────────────────────────────────────────────────────────

private fun tempColor(c: Float): Color = when {
    c >= 80f -> ColorAmber
    c >= 70f -> Color(0xFFFCD34D)
    c >= 55f -> ColorValue
    else -> ColorValue
}

private fun loadColor(pct: Int): Color = when {
    pct >= 90 -> ColorRed
    pct >= 60 -> ColorAmber
    pct >= 30 -> ColorEmerald
    else -> ColorBlue
}
