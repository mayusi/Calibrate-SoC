package io.github.mayusi.calibratesoc.ui.overlay

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The HUD's Compose tree. Two layouts:
 *
 * - [HudCompactRow]: single horizontal strip with the gauges a gamer
 *   watches mid-session — peak CPU MHz, load%, CPU temp, GPU MHz+%,
 *   GPU temp, battery temp, battery W, and our own draw rate (HUD Hz).
 *   The HUD Hz is honestly labeled — it's NOT the game's FPS (Android
 *   has no public API for that without rooting + frida-style hooks);
 *   it's our overlay's swap rate, which co-varies with GPU pressure.
 *
 * - [HudVerbose]: scrollable multi-row panel with per-core load+MHz
 *   bars, every thermal zone (not just the first 8), GPU details, RAM
 *   usage, batt details, and the live-tune controls.
 *
 * Both share a header strip with layout-swap + close, and a tune-row
 * with ± MHz steppers + next-profile button. The tune row is on BOTH
 * layouts because the whole reason it exists is in-game iteration —
 * you wouldn't want to swap to verbose just to step the clock.
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
    Box(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 380.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC101418)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            HudHeader(state.profile, state.isRecording, state.recordingElapsedSeconds, onCycleLayout, onToggleRecord, onClose)
            when (state.profile) {
                HudProfile.COMPACT -> HudCompactRows(state)
                HudProfile.VERBOSE -> HudVerbose(state)
            }
            // Live-tune controls only when we have an actual write path.
            // Without it (stock Odin, SELinux enforcing, no root) the
            // ± buttons would just queue scripts that never run — bad
            // UX. Show them on rooted devices OR when Force SELinux is
            // ON and PServer is reachable.
            if (state.canTuneLive) {
                HudClusterChips(state, onTogglePolicy)
                HudTuneRow(state, onStepMhz, onCycleNextProfile, onPickStepSize)
            }
            if (state.profile == HudProfile.VERBOSE && state.quickProfiles.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                HudProfileChips(state.quickProfiles, onApplyProfile)
            }
            state.lastActionMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    color = Color(0xFFFFE0A0),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }
        }
    }
}

/** Per-cluster on/off chips. Selected clusters get stepped together
 *  when the user taps ±. */
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
            color = Color(0x99FFFFFF),
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp, end = 2.dp),
        )
        state.allPolicies.forEach { pid ->
            val on = pid in enabled
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) Color(0x66409EFF) else Color(0x22FFFFFF))
                    .clickable { onTogglePolicy(pid) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text("p$pid", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

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
            text = "HUD · ${profile.name.lowercase()}",
            color = Color(0x99FFFFFF),
            fontSize = 10.sp,
        )
        // Recording elapsed time badge, shown only while recording.
        if (isRecording) {
            Spacer(Modifier.width(6.dp))
            val mins = recordingElapsedSeconds / 60
            val secs = recordingElapsedSeconds % 60
            Text(
                text = "%d:%02d".format(mins, secs),
                color = Color(0xFFFF4D4D),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.weight(1f))
        // Record toggle: ● to start, ■ to stop. Red when active.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleRecord)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isRecording) "■" else "●",
                color = if (isRecording) Color(0xFFFF4D4D) else Color(0x99FFFFFF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        IconButton(onClick = onCycleLayout, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.SwapVert,
                contentDescription = "Swap layout",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Hide overlay",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Compact: two rows so we can fit enough numbers for a real in-game
 * read without going over 380 dp wide. Row 1 = the gauges that change
 * every frame. Row 2 = the slower-changing stats (temps, power).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudCompactRows(state: HudUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // FPS line. When the sampler can read SurfaceFlinger frame
            // timestamps via PServer, this is the real game FPS. When
            // not (PServer unreachable / app frozen), it falls back to
            // the panel's refresh rate. Label switches so the user
            // knows which they're looking at.
            state.gameFps?.let { fps ->
                val isLikelyRealFps = fps != state.hudHz && fps !in 59..61 && fps !in 119..121
                val label = if (isLikelyRealFps) "FPS" else "REFRESH"
                val sublabel = if (isLikelyRealFps) "game" else "Hz"
                Stat(label, "$fps", sublabel, color = Color(0xFF66D9A0))
            }
            Stat("CPU", "${state.cpuMaxMhz}", "MHz")
            Stat("LD", "${state.cpuLoadPct}", "%", loadColor(state.cpuLoadPct))
            state.gpuMhz?.let { Stat("GPU", "$it", "MHz") }
            state.gpuLoadPct?.let { Stat("G%", "$it", "%", loadColor(it)) }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.cpuTempC?.let { avg ->
                // Show avg CPU temp prominently (matches Odin Game
                // Assistant). When a single core is much hotter than
                // the avg (FEX/emulator pinning), append "·peak Nc"
                // so users see the spike without it dominating.
                val peak = state.cpuPeakTempC
                val unit = if (peak != null && peak - avg >= 8f) "°C·pk${peak.toInt()}" else "°C"
                Stat("Tc", "%.0f".format(avg), unit, tempColor(avg))
            }
            state.gpuTempC?.let {
                Stat("Tg", "%.0f".format(it), "°C", tempColor(it))
            }
            state.batteryTempC?.let {
                Stat("Tb", "%.0f".format(it), "°C", tempColor(it))
            }
            state.batteryW?.let { Stat("BAT", "%.1f".format(it), "W") }
            state.ramUsedPct?.let { Stat("RAM", "$it", "%") }
        }
    }
}

/** Verbose: scrollable, capped at 60% of screen so it never eats the
 *  whole display when zones are plentiful. */
@Composable
private fun HudVerbose(state: HudUiState) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .heightIn(max = 360.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HudCompactRows(state)

        if (state.perCoreMhz.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                "Per-core (MHz · load%)",
                color = Color(0xB3FFFFFF),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.perCoreMhz.forEachIndexed { idx, mhz ->
                    val loadPct = state.perCoreLoadPct.getOrNull(idx) ?: 0
                    PerCoreBar(idx, mhz, loadPct, state.cpuMaxMhz)
                }
            }
        }

        if (state.zones.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                "Thermal zones (all)",
                color = Color(0xB3FFFFFF),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                state.zones.forEach { (label, c) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            label.take(28),
                            color = Color(0xCCFFFFFF),
                            fontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "%.1f°".format(c),
                            color = tempColor(c),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tune row: −/+ MHz steppers around the chosen clusters, with a step-
 * size dropdown so the user can pick how much each tap changes, plus
 * a cycle-next-profile button. Lives in both layouts because in-game
 * iteration is the whole point of the HUD.
 */
@Composable
private fun HudTuneRow(
    state: HudUiState,
    onStepMhz: (Int) -> Unit,
    onCycleNextProfile: () -> Unit,
    onPickStepSize: (Int) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuneButton("−", enabled = state.bigCorePolicy != null) { onStepMhz(-state.stepMhz) }
        StepSizeDropdown(state.stepMhz, onPickStepSize)
        TuneButton("+", enabled = state.bigCorePolicy != null) { onStepMhz(state.stepMhz) }
        val centerLabel = state.bigCoreCurrentMhz?.let { "${it}MHz" } ?: "—"
        Text(
            centerLabel,
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        if (state.quickProfiles.isNotEmpty()) {
            TuneButton("Next →", enabled = true, onClick = onCycleNextProfile)
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
                .background(Color(0x55409EFF))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                "${currentMhz}▾",
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            listOf(50, 100, 200, 300, 500, 1000).forEach { mhz ->
                androidx.compose.material3.DropdownMenuItem(
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
    val bg = if (enabled) Color(0x44FFFFFF) else Color(0x22FFFFFF)
    val fg = if (enabled) Color.White else Color(0x66FFFFFF)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudProfileChips(
    chips: List<Pair<String, String>>,
    onApplyProfile: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Apply (writes script · open Odin Settings to run)",
            color = Color(0x99FFFFFF),
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
                        .background(Color(0x33FFFFFF))
                        .clickable { onApplyProfile(id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(name.take(18), color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            color = Color(0xB3FFFFFF),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = color,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                unit,
                color = Color(0x99FFFFFF),
                fontSize = 8.sp,
                modifier = Modifier.padding(bottom = 1.dp),
            )
        }
    }
}

@Composable
private fun PerCoreBar(idx: Int, mhz: Int, loadPct: Int, maxMhz: Int) {
    val frac = if (maxMhz > 0) (mhz.toFloat() / maxMhz).coerceIn(0f, 1f) else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Core $idx — $mhz MHz, $loadPct percent" },
    ) {
        Text(
            "c$idx",
            color = Color(0xCCFFFFFF),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(20.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x22FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .height(7.dp)
                    .background(loadColor(loadPct)),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "$mhz · $loadPct%",
            color = Color.White,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(76.dp),
        )
    }
}

private fun tempColor(c: Float): Color = when {
    c >= 80f -> Color(0xFFFF4D4D)
    c >= 70f -> Color(0xFFFFB000)
    c >= 55f -> Color(0xFFFFE066)
    else -> Color.White
}

private fun loadColor(pct: Int): Color = when {
    pct >= 90 -> Color(0xFFFF4D4D)
    pct >= 60 -> Color(0xFFFFB000)
    pct >= 30 -> Color(0xFF66D9A0)
    else -> Color(0xFF5EA8FF)
}
