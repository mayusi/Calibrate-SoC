package io.github.mayusi.calibratesoc.ui.dashboard

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DevfreqDeviceProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.ThermalZoneExtras
import io.github.mayusi.calibratesoc.data.capability.ThermalZoneProbe
import io.github.mayusi.calibratesoc.data.monitor.BatteryEstimate
import io.github.mayusi.calibratesoc.data.monitor.EstimateBasis
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.components.StatTile
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Live monitoring home screen. Reads from [DashboardViewModel]'s rolling
 * history buffer and renders five Vico line charts + a few discrete
 * widgets. Designed to be readable on a small handheld display, so each
 * card is its own row at full width rather than a dense grid.
 *
 * Anything that depends on a writer (current preset chip, "apply test
 * write" debug button) gates on the privilege tier — Phase 3b shows
 * read-only widgets only; writes land in Phase 5.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onOpenSessions: () -> Unit = {},
    onOpenAutoTdp: () -> Unit = {},
) {
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val activeTuneState by viewModel.activeTuneState.collectAsStateWithLifecycle()
    val batteryEstimate by viewModel.batteryEstimate.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordingElapsed by viewModel.recordingElapsedSeconds.collectAsStateWithLifecycle()
    val autoTdpState by viewModel.autoTdpState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item { Header(capability, activeTuneState) }
        // AutoTDP status strip — only when daemon is not IDLE so the strip
        // never clutters the default state.
        if (autoTdpState.status != AutoTdpStatus.IDLE) {
            item { AutoTdpStatusStrip(autoTdpState, onOpenAutoTdp) }
        }
        item { HudLauncherCard() }
        item {
            SessionRecordingCard(
                isRecording = isRecording,
                elapsedSeconds = recordingElapsed,
                onToggleRecord = { viewModel.toggleRecording() },
                onOpenSessions = onOpenSessions,
            )
        }
        if (latest == null) {
            item { Text("Sampling…", style = MaterialTheme.typography.bodyMedium) }
            return@LazyColumn
        }
        val current = latest!!

        item { AtAGlanceCard(current) }
        item { PerCoreFreqCard(current) }
        item { CpuLoadCard(history) }
        item { GpuCard(history, current) }
        item { ThermalCard(current) }

        // === New read-only monitoring cards (no-root differentiators) ===

        // CPU time-in-state: per-cluster freq residency histogram (cumulative
        // since boot). Shows WHERE the SoC actually spends its cycles.
        capability?.let { cap ->
            if (cap.cpuTimeInState.isNotEmpty()) {
                item { CpuTimeInStateCard(cap) }
            }
        }

        // Thermal detail: trip points per zone so users can see WHEN throttling fires.
        capability?.let { cap ->
            if (cap.thermalExtras.isNotEmpty()) {
                item { ThermalDetailCard(cap.thermalZones, cap.thermalExtras) }
            }
        }

        // DDR / bus frequency: cur_freq + governor for each devfreq device.
        capability?.let { cap ->
            if (cap.devfreqDevices.isNotEmpty()) {
                item { DdrBusCard(cap.devfreqDevices) }
            }
        }

        item { BatteryCard(current, batteryEstimate) }
        item { RamCard(current) }
    }
}

// --- AutoTDP status strip -----------------------------------------------

/**
 * Pure helper: formats the AutoTDP strip detail line from the applied TDP
 * state and savings measurement.
 *
 * Returns null when there is nothing meaningful to display (no applied
 * state, no savings, and no reason text).
 *
 * Factored out so it can be unit-tested without Compose.
 */
internal fun autoTdpStripDetail(
    parkedCores: Set<Int>,
    bigClusterCapKhz: Int?,
    deltaMw: Long?,
    enoughData: Boolean?,
    fallbackReason: String,
): String? {
    val parkedLabel = parkedCores.takeIf { it.isNotEmpty() }
        ?.sorted()
        ?.joinToString(",") { "cpu$it" }
        ?.let { "parked $it" }
    val capLabel = bigClusterCapKhz?.let { khz ->
        "cap ${"%.1f".format(khz / 1_000_000.0)}G"
    }
    val savingsLabel = when {
        deltaMw == null -> null
        enoughData == false -> "measuring…"
        else -> deltaMw.let { "saving -${it} mW" }
    }
    val parts = listOfNotNull(parkedLabel, capLabel, savingsLabel)
    return if (parts.isEmpty()) fallbackReason.take(60).ifBlank { null }
    else parts.joinToString(" · ")
}

/**
 * Compact top-of-screen strip shown whenever the AutoTDP daemon is not
 * IDLE. Displays the daemon's last known applied state (parked cores, freq
 * cap) and the measured savings when they are ready.  Tap "Details →" to
 * navigate to the AutoTDP screen.
 *
 * Honesty-first: savings are shown only when [AutoTdpRunState.savings]
 * has [enoughData] == true.  Before that, a "measuring…" placeholder is
 * used instead of showing zeros.
 */
@Composable
private fun AutoTdpStatusStrip(state: AutoTdpRunState, onTap: () -> Unit) {
    val statusLabel = when (state.status) {
        AutoTdpStatus.RUNNING -> "AutoTDP running"
        AutoTdpStatus.STOPPED -> "AutoTDP stopped"
        AutoTdpStatus.KILLED_BY_SAFETY -> "AutoTDP killed (safety)"
        AutoTdpStatus.WRITE_DENIED -> "AutoTDP write denied"
        AutoTdpStatus.LIVE_UNAVAILABLE -> "AutoTDP unavailable"
        AutoTdpStatus.IDLE -> "AutoTDP"
    }

    val applied = state.appliedState
    val savings = state.savings

    val detail = autoTdpStripDetail(
        parkedCores = applied?.parkedPrimeCores ?: emptySet(),
        bigClusterCapKhz = applied?.bigClusterCapKhz,
        deltaMw = savings?.deltaMw,
        enoughData = savings?.enoughData,
        fallbackReason = state.lastReason,
    )

    // Tinted surface strip reusing theme colors
    val tintColor = when (state.status) {
        AutoTdpStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        AutoTdpStatus.KILLED_BY_SAFETY, AutoTdpStatus.WRITE_DENIED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.item))
            .then(
                Modifier.padding(horizontal = Spacing.group, vertical = Spacing.dense),
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tintColor,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        TextButton(onClick = onTap) {
            Text("Details →", style = MaterialTheme.typography.labelMedium)
        }
    }
}

// --- Header -------------------------------------------------------------

@Composable
private fun Header(capability: CapabilityReport?, activeTuneState: ActiveTuneState?) {
    Column {
        Text(
            "Calibrate SoC",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Spacing.dense))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            val tier = capability?.privilege ?: PrivilegeTier.NONE
            val tierColor = when (tier) {
                PrivilegeTier.ROOT -> MaterialTheme.colorScheme.tertiary
                PrivilegeTier.AYN_SETTINGS -> MaterialTheme.colorScheme.tertiary
                PrivilegeTier.SHIZUKU -> MaterialTheme.colorScheme.secondary
                PrivilegeTier.NONE -> MaterialTheme.colorScheme.outline
            }
            AssistChip(
                onClick = {},
                label = { Text(tier.name) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = tierColor,
                ),
            )
            capability?.device?.knownHandheldKey?.let { key ->
                AssistChip(onClick = {}, label = { Text(key) })
            }
        }
        // Active tune chip. The label is chosen to be honest:
        //  - No history → "Stock (factory)"
        //  - Last apply was THIS boot session → "Active: <name>" (likely still in effect)
        //  - Last apply was a PREVIOUS boot → "Last applied: <name>" (kernel has likely reverted)
        Spacer(Modifier.height(Spacing.dense))
        val activeTuneLabel = when (activeTuneState) {
            null -> "Stock (factory)"
            is ActiveTuneState.Current -> "Active: ${activeTuneState.name}"
            is ActiveTuneState.MayHaveReverted -> "Last applied: ${activeTuneState.name}"
        }
        AssistChip(
            onClick = {},
            label = { Text(activeTuneLabel, style = MaterialTheme.typography.labelMedium) },
        )
        // Tier-mismatch hint: surfaced when the user is on NONE but the
        // device is a vendor handheld (AYN/AYANEO/Retroid) where the
        // vendor-controls tier is one adb command away. We never
        // auto-grant — the user must run the command from a PC — but we
        // tell them exactly what it is.
        if (capability != null &&
            capability.privilege == PrivilegeTier.NONE &&
            capability.vendorApps.anyVendorPerfApp
        ) {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "Tip: grant once via adb to unlock vendor performance + fan controls (no root needed):\n" +
                    "adb shell pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

// --- HUD launcher card ------------------------------------------------

@Composable
private fun HudLauncherCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val canDraw = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        canDraw.value = android.provider.Settings.canDrawOverlays(context)
    }
    var showLogs by remember { mutableStateOf(false) }
    SectionCard("Floating HUD") {
        Text(
            "Draws a draggable performance panel on top of any app. Drag anywhere to move it; tap the swap icon for compact ↔ verbose. The ±/Next buttons step the big-core clock or cycle saved profiles via your device's script runner.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.group))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            androidx.compose.material3.Button(onClick = {
                if (!android.provider.Settings.canDrawOverlays(context)) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } else {
                    io.github.mayusi.calibratesoc.ui.overlay.OverlayService.start(context)
                }
            }) {
                Text(if (canDraw.value) "Show HUD" else "Grant overlay access")
            }
            androidx.compose.material3.OutlinedButton(onClick = {
                io.github.mayusi.calibratesoc.ui.overlay.OverlayService.stop(context)
            }) { Text("Hide HUD") }
            androidx.compose.material3.OutlinedButton(onClick = { showLogs = true }) {
                Text("HUD logs")
            }
        }
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "Tip: add the \"HUD\" Quick Settings tile to toggle without opening the app.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showLogs) {
        HudLogsDialog(onDismiss = { showLogs = false })
    }
}

@Composable
private fun HudLogsDialog(onDismiss: () -> Unit) {
    val viewModel: io.github.mayusi.calibratesoc.ui.overlay.HudLogsViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val dateFmt = remember {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HUD logs") },
        text = {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.height(360.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        "Nothing yet. Open the HUD, tap a chip or a ± stepper, and events show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(entries, key = { it.timestampMs }) { entry ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
                                Text(
                                    dateFmt.format(java.util.Date(entry.timestampMs)),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    entry.level.name.take(4),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (entry.level) {
                                        io.github.mayusi.calibratesoc.ui.overlay.HudEventLog.Level.ACTION ->
                                            MaterialTheme.colorScheme.tertiary
                                        io.github.mayusi.calibratesoc.ui.overlay.HudEventLog.Level.WARN ->
                                            MaterialTheme.colorScheme.secondary
                                        io.github.mayusi.calibratesoc.ui.overlay.HudEventLog.Level.ERROR ->
                                            MaterialTheme.colorScheme.error
                                        io.github.mayusi.calibratesoc.ui.overlay.HudEventLog.Level.INFO ->
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Text(
                                    entry.message,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { viewModel.clear() }) { Text("Clear") }
        },
    )
}

// --- At a glance summary ----------------------------------------------

/**
 * Compact top-of-screen summary: the four numbers that answer "is my
 * device OK right now" at a glance — peak SoC temp (hottest of any
 * thermal zone), total power draw, peak CPU clock, and GPU load. Each
 * is a StatTile; the temp tile passes a severity valueColor so a hot
 * device is obvious without reading the number.
 */
@Composable
private fun AtAGlanceCard(t: Telemetry) = SectionCard("At a glance") {
    // Peak SoC temp: hottest reading across all reachable zones (CPU,
    // GPU, etc.), filtered to a sane range so a bogus 0/250 °C sensor
    // doesn't dominate.
    val peakTempC = t.zoneTempsMilliC
        .filter { it.tempMilliC in 1_000..150_000 }
        .maxOfOrNull { it.tempMilliC / 1000.0 }
    val totalW = t.batteryDrawMilliW?.let { it / 1000.0 }
    val peakCpuMhz = t.perCoreCpuFreqKhz.maxOrNull()?.let { it / 1000 }
    val gpuLoad = t.gpuLoadPct
    val gpuMhz = t.gpuFreqHz?.let { it / 1_000_000L }

    // Battery level via sticky intent — unprivileged, available everywhere.
    val context = LocalContext.current
    val battPct = remember(t.timestampMs) {
        runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?.takeIf { it >= 0 }
        }.getOrNull()
    }

    // Row 1: original four glance tiles.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        StatTile(
            label = "SoC temp",
            value = peakTempC?.let { "%.0f°".format(it) } ?: "—",
            valueColor = peakTempC?.let { glanceTempColor(it) },
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Power",
            value = totalW?.let { "%.1f".format(it) } ?: "—",
            unit = if (totalW != null) "W" else null,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "CPU peak",
            value = peakCpuMhz?.let { "$it" } ?: "—",
            unit = if (peakCpuMhz != null) "MHz" else null,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "GPU load",
            value = gpuLoad?.let { "$it%" } ?: "—",
            modifier = Modifier.weight(1f),
        )
    }

    // Row 2: GPU MHz + battery % — new glance tiles aligned to the grid above.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        StatTile(
            label = "GPU",
            value = gpuMhz?.let { "$it" } ?: "—",
            unit = if (gpuMhz != null) "MHz" else null,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Battery",
            value = battPct?.let { "$it%" } ?: "—",
            modifier = Modifier.weight(1f),
        )
        // Two invisible weight spacers keep tiles left-aligned in the same
        // four-column grid as the row above.
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1f))
    }
}

/** Severity bands for the at-a-glance SoC temp tile: emerald < 60,
 *  purple 60–75, red > 75 °C. */
@Composable
private fun glanceTempColor(c: Double): Color = when {
    c > 75 -> MaterialTheme.colorScheme.error
    c >= 60 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
}

// --- Per-core CPU frequency (current values, bar-ish display) ----------

@Composable
private fun PerCoreFreqCard(t: Telemetry) = SectionCard(title = "CPU frequency (MHz, live)") {
    if (t.perCoreCpuFreqKhz.isEmpty()) {
        Text("scaling_cur_freq unreadable", style = MaterialTheme.typography.bodyMedium)
        return@SectionCard
    }
    val maxKhz = (t.perCoreCpuFreqKhz.max().coerceAtLeast(1))
    t.perCoreCpuFreqKhz.forEachIndexed { idx, khz ->
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            Text("cpu$idx", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(
                progress = { (khz.toFloat() / maxKhz.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(Spacing.dense)),
            )
            Text(
                "${khz / 1000}",
                modifier = Modifier.width(56.dp),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// --- CPU load chart ---------------------------------------------------

/**
 * Shows a mean-aggregate line chart of CPU load over the rolling history
 * window, plus a per-core snapshot bar view beneath it for the latest
 * sample. The chart gives trend; the bars give instant per-core granularity.
 */
@Composable
private fun CpuLoadCard(history: List<Telemetry>) = SectionCard("CPU load (%)") {
    if (history.isEmpty() || history.last().perCoreLoadPct.isEmpty()) {
        Text("warming up…", style = MaterialTheme.typography.bodyMedium)
        return@SectionCard
    }

    // Aggregate across cores → mean for the time-series chart.
    val series = history.map { t ->
        val loads = t.perCoreLoadPct
        if (loads.isEmpty()) 0f else loads.sum().toFloat() / loads.size
    }
    MetricLineChart(points = series, heightDp = 100)

    // Per-core snapshot bars for the most recent sample.
    val latestLoads = history.last().perCoreLoadPct
    if (latestLoads.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.group))
        Text(
            "Per-core (live)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        latestLoads.forEachIndexed { idx, pct ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                Text(
                    "cpu$idx",
                    modifier = Modifier.width(40.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                LinearProgressIndicator(
                    progress = { (pct.toFloat() / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(Spacing.dense)),
                )
                Text(
                    "$pct%",
                    modifier = Modifier.width(36.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// --- GPU card -------------------------------------------------------

/**
 * GPU card: load + frequency displayed as labeled StatTile cells (with an
 * amber color-band when load > 90 %), followed by a load-over-time line
 * chart. Replaces the previous raw "$current% $freqMhz MHz" text dump with
 * proper StatTile treatment to match the rest of the dashboard's style.
 */
@Composable
private fun GpuCard(history: List<Telemetry>, latest: Telemetry) = SectionCard("GPU") {
    val current = latest.gpuLoadPct
    val freqMhz = latest.gpuFreqHz?.let { it / 1_000_000L }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        StatTile(
            label = "Load",
            value = current?.let { "$it%" } ?: "—",
            // Amber band at > 90 % load to flag GPU contention.
            valueColor = current?.let { if (it > 90) MaterialTheme.colorScheme.secondary else null },
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Freq",
            value = freqMhz?.let { "$it" } ?: "—",
            unit = if (freqMhz != null) "MHz" else null,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.weight(1f))
    }

    val series = history.map { it.gpuLoadPct?.toFloat() ?: 0f }
    if (series.all { it == 0f } && current == null) {
        Text("GPU load unreadable on this device", style = MaterialTheme.typography.bodySmall)
    } else {
        MetricLineChart(points = series, heightDp = 120)
    }
}

// --- Thermal ---------------------------------------------------------

@Composable
private fun ThermalCard(t: Telemetry) = SectionCard("Temperatures") {
    if (t.zoneTempsMilliC.isEmpty()) {
        Text("No thermal zones reachable", style = MaterialTheme.typography.bodyMedium)
        return@SectionCard
    }
    // Show the hottest few. Full list lives on Device Info.
    val top = t.zoneTempsMilliC
        .filter { it.tempMilliC in 1_000..150_000 }
        .sortedByDescending { it.tempMilliC }
        .take(6)
    top.forEach { z ->
        Row {
            Text(z.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${"%.1f".format(z.tempMilliC / 1000.0)} °C",
                fontFamily = FontFamily.Monospace,
                color = tempColor(z),
            )
        }
    }
}

@Composable
private fun tempColor(z: ZoneTemp): Color {
    val c = z.tempMilliC / 1000
    return when {
        c >= 80 -> MaterialTheme.colorScheme.error
        c >= 65 -> Color(0xFFFCD34D)
        else -> MaterialTheme.colorScheme.onSurface
    }
}

// --- Battery ---------------------------------------------------------

@Composable
private fun BatteryCard(t: Telemetry, estimate: BatteryEstimate) = SectionCard("Battery") {
    val tempC = t.batteryTempDeciC?.let { "%.1f °C".format(it / 10.0) } ?: "—"
    val volts = t.batteryVoltageUv?.let { "%.2f V".format(it / 1_000_000.0) } ?: "—"
    val watts = t.batteryDrawMilliW?.let { "%.2f W".format(it / 1000.0) } ?: "—"
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.screen)) {
        StatTile(label = "Temp", value = tempC)
        StatTile(label = "Voltage", value = volts)
        StatTile(label = "Draw", value = watts)
    }
    Spacer(Modifier.height(Spacing.group))
    BatteryEstimateRow(estimate)
}

/**
 * Time-remaining estimate row inside the battery card. Keeps the layout
 * honest: labelled "estimate", never shown when data is absent or the
 * device is charging.
 */
@Composable
private fun BatteryEstimateRow(estimate: BatteryEstimate) {
    when (estimate.basis) {
        EstimateBasis.INSUFFICIENT_DATA -> {
            Text(
                "Battery time estimate unavailable on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        EstimateBasis.CHARGING -> {
            val drawLabel = estimate.watts?.let { " (%.1f W)".format(it) } ?: ""
            Text(
                "Charging$drawLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        EstimateBasis.LIVE_DRAW -> {
            val hours = estimate.hoursRemaining
            val timeLabel: String = when {
                hours == null -> "—"
                hours >= 1.0 -> {
                    val h = hours.toInt()
                    val m = ((hours - h) * 60).toInt()
                    if (m > 0) "~${h}h ${m}m" else "~${h}h"
                }
                else -> "~${(hours * 60).toInt()} min"
            }
            val drawLabel = estimate.watts?.let { "%.1f W".format(it) }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                if (drawLabel != null) {
                    Text(
                        "$drawLabel at this load · Estimate only — varies with game activity",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --- Session recording card ------------------------------------------

/**
 * Dashboard fallback: start/stop a recording without needing the HUD.
 * Note: without the HUD, FPS data is not available — this card says so
 * explicitly. The "View sessions" button is always visible.
 */
@Composable
private fun SessionRecordingCard(
    isRecording: Boolean,
    elapsedSeconds: Long,
    onToggleRecord: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    SectionCard("Session recorder") {
        Text(
            "Record FPS, temps, clocks, and power over a play session, then review " +
                "the timeline to diagnose drops or throttle events.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isRecording) {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "Note: real in-game FPS is captured only while the HUD is also running. " +
                    "Without the HUD, a session still records temps, clocks, and power.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isRecording) {
            Spacer(Modifier.height(Spacing.dense))
            val mins = elapsedSeconds / 60
            val secs = elapsedSeconds % 60
            Text(
                "Recording — %d:%02d elapsed".format(mins, secs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(Spacing.group))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            androidx.compose.material3.Button(
                onClick = onToggleRecord,
                colors = if (isRecording) {
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (isRecording) "Stop recording" else "Start recording")
            }
            androidx.compose.material3.OutlinedButton(onClick = onOpenSessions) {
                Text("View sessions")
            }
        }
    }
}

// --- CPU time-in-state histogram --------------------------------------

/**
 * Shows, per CPU policy cluster, the percentage of time (jiffies) the kernel
 * has spent at each available frequency since boot.  This is a READ-ONLY,
 * no-root metric derived from cpufreq policyN stats/time_in_state.
 *
 * "Cumulative since boot" is labelled honestly — it is NOT a live window.
 * Reboot resets all counters to zero.
 */
@Composable
private fun CpuTimeInStateCard(cap: CapabilityReport) =
    SectionCard("CPU freq residency (since boot)") {
        Text(
            "How long each cluster has spent at each clock speed since the last reboot (read-only, no root needed).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.group))
        cap.cpuTimeInState.forEach { probe ->
            val totalJiffies = probe.entries.sumOf { it.jiffies }.takeIf { it > 0L } ?: 1L
            Text(
                "Cluster / policy${probe.policyId}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Spacing.dense))
            // Show top 5 frequencies by jiffies to keep the card compact.
            val topEntries = probe.entries
                .filter { it.jiffies > 0L }
                .sortedByDescending { it.jiffies }
                .take(5)
            if (topEntries.isEmpty()) {
                Text(
                    "No data yet — device may not have been under load",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                topEntries.forEach { entry ->
                    val pct = (entry.jiffies.toDouble() / totalJiffies * 100).coerceIn(0.0, 100.0)
                    val mhz = entry.freqKhz / 1000
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "$mhz MHz",
                            modifier = Modifier.width(72.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        LinearProgressIndicator(
                            progress = { pct.toFloat() / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        )
                        Text(
                            "${"%.1f".format(pct)}%",
                            modifier = Modifier.width(44.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.group))
        }
    }

// --- Thermal trip-point detail ----------------------------------------

/**
 * Shows the thermal trip points for each zone alongside its live
 * temperature — users can see at what temperature their device will
 * throttle, without needing root.  All data is READ-ONLY from the
 * capability probe snapshot taken at launch.
 */
@Composable
private fun ThermalDetailCard(
    zones: List<ThermalZoneProbe>,
    extras: List<ThermalZoneExtras>,
) = SectionCard("Thermal trip points (read-only)") {
    Text(
        "Where your device starts throttling. All data is read-only — no root needed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.group))

    // Only show zones that have actual trip points.
    val extrasWithTrips = extras.filter { it.tripPoints.isNotEmpty() }
    if (extrasWithTrips.isEmpty()) {
        Text(
            "Trip point data not available on this kernel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@SectionCard
    }

    extrasWithTrips.forEach { extra ->
        // Find matching zone probe for the human-readable label.
        val zone = zones.firstOrNull { it.id == extra.zoneId }
        val label = zone?.type ?: "zone${extra.zoneId}"
        val liveC = zone?.currentTempMilliC?.let { "  (now %.0f °C)".format(it / 1000.0) } ?: ""

        Text(
            "$label$liveC",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // Mode badge if zone has a mode file.
        extra.mode?.let { mode ->
            Text(
                "mode: $mode",
                style = MaterialTheme.typography.labelSmall,
                color = if (mode == "disabled") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Trip points sorted by temperature.
        extra.tripPoints.sortedBy { it.tempMilliC }.forEach { tp ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                Text(
                    tp.type,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "%.0f °C".format(tp.tempMilliC / 1000.0),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        tp.tempMilliC >= 95_000 -> MaterialTheme.colorScheme.error
                        tp.tempMilliC >= 80_000 -> Color(0xFFFCD34D)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        Spacer(Modifier.height(Spacing.group))
    }
}

// --- DDR / bus frequency ----------------------------------------------

/**
 * Shows the current frequency and governor for each devfreq device
 * (bus interconnect, DDR, etc.).  All data is READ-ONLY from the
 * capability probe snapshot taken at launch.  DDR frequency changes
 * asynchronously with workloads; this card shows the snapshot value
 * at last probe refresh.
 */
@Composable
private fun DdrBusCard(devices: List<DevfreqDeviceProbe>) =
    SectionCard("Memory / bus bandwidth (read-only)") {
        Text(
            "DDR and bus interconnect operating frequencies at last probe refresh (read-only, no root).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.group))
        devices.forEach { dev ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        dev.deviceName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "gov: ${dev.currentGovernor}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    val curMhz = dev.curFreqHz / 1_000_000L
                    val maxMhz = dev.maxFreqHz / 1_000_000L
                    Text(
                        "$curMhz / $maxMhz MHz",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    // Visual fill ratio for how close to max we are.
                    val ratio = if (dev.maxFreqHz > 0L) {
                        (dev.curFreqHz.toFloat() / dev.maxFreqHz.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier
                            .width(80.dp)
                            .height(4.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.dense))
        }
    }

// --- RAM -------------------------------------------------------------

@Composable
private fun RamCard(t: Telemetry) = SectionCard("RAM") {
    if (t.ramTotalKb == 0L) {
        Text("/proc/meminfo unreadable", style = MaterialTheme.typography.bodyMedium)
        return@SectionCard
    }
    val usedKb = (t.ramTotalKb - t.ramAvailableKb).coerceAtLeast(0)
    val frac = (usedKb.toFloat() / t.ramTotalKb).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { frac },
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    )
    Text(
        "${usedKb / 1024} / ${t.ramTotalKb / 1024} MB",
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
    )
}
