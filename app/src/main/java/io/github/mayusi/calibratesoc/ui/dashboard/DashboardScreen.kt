package io.github.mayusi.calibratesoc.ui.dashboard

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
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
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
import io.github.mayusi.calibratesoc.ui.components.MetricTile
import io.github.mayusi.calibratesoc.ui.components.PanelAccentEdge
import io.github.mayusi.calibratesoc.ui.components.StatBar
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Live monitoring home screen — Direction-C Arsenal restyle.
 *
 * Every card is now an [ArsenalPanel] with a categorical accent edge.
 * The header uses [StatusPill] for tier and device. The at-a-glance
 * grid uses [MetricTile] with colour-coded accents. Per-core load
 * and freq are [StatBar] rows. Charts are framed in [ArsenalPanel]
 * with the matching accent color.
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
        item { DashHeader(capability, activeTuneState) }

        // AutoTDP status strip — only when daemon is not IDLE
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
            item {
                ArsenalPanel(accent = AccentBar.Neutral) {
                    Text(
                        "SAMPLING…",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentBar.Neutral,
                        letterSpacing = 0.08.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            return@LazyColumn
        }
        val current = latest!!

        item { AtAGlanceCard(current) }
        item { PerCoreFreqCard(current) }
        item { CpuLoadCard(history) }
        item { GpuCard(history, current) }
        item { ThermalCard(current) }

        capability?.let { cap ->
            if (cap.cpuTimeInState.isNotEmpty()) {
                item { CpuTimeInStateCard(cap) }
            }
        }

        capability?.let { cap ->
            if (cap.thermalExtras.isNotEmpty()) {
                item { ThermalDetailCard(cap.thermalZones, cap.thermalExtras) }
            }
        }

        capability?.let { cap ->
            if (cap.devfreqDevices.isNotEmpty()) {
                item { DdrBusCard(cap.devfreqDevices) }
            }
        }

        item { BatteryCard(current, batteryEstimate) }
        item { RamCard(current) }
    }
}

// --- AutoTDP strip detail helper (unit-testable) -----------------------

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

// --- AutoTDP status strip (Arsenal emerald panel, tappable) -----------

@Composable
private fun AutoTdpStatusStrip(state: AutoTdpRunState, onTap: () -> Unit) {
    val statusLabel = when (state.status) {
        AutoTdpStatus.RUNNING -> "AUTOTDP RUNNING"
        AutoTdpStatus.STOPPED -> "AUTOTDP STOPPED"
        AutoTdpStatus.KILLED_BY_SAFETY -> "AUTOTDP KILLED (SAFETY)"
        AutoTdpStatus.WRITE_DENIED -> "AUTOTDP WRITE DENIED"
        AutoTdpStatus.LIVE_UNAVAILABLE -> "AUTOTDP UNAVAILABLE"
        AutoTdpStatus.IDLE -> "AUTOTDP"
    }
    val stripAccent = when (state.status) {
        AutoTdpStatus.RUNNING -> AccentBar.Emerald
        AutoTdpStatus.KILLED_BY_SAFETY, AutoTdpStatus.WRITE_DENIED -> AccentBar.Red
        else -> AccentBar.Neutral
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

    ArsenalPanel(
        accent = stripAccent,
        modifier = Modifier.clickable(onClick = onTap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = stripAccent,
                    letterSpacing = 0.08.sp,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF999999),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                "DETAILS →",
                style = MaterialTheme.typography.labelSmall,
                color = stripAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.sp,
            )
        }
    }
}

// --- Header (device name + tier pill + active tune) -------------------

@Composable
private fun DashHeader(capability: CapabilityReport?, activeTuneState: ActiveTuneState?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        // Device name as big mono headline
        val deviceName = capability?.device?.knownHandheldKey ?: "CALIBRATE SOC"
        Text(
            deviceName.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = 0.04.sp,
        )

        // Tier + device chip row
        val tier = capability?.privilege ?: PrivilegeTier.NONE
        val tierAccent = when (tier) {
            PrivilegeTier.ROOT -> AccentBar.Emerald
            PrivilegeTier.AYN_SETTINGS -> AccentBar.Emerald
            PrivilegeTier.SHIZUKU -> AccentBar.Blue
            PrivilegeTier.NONE -> AccentBar.Neutral
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            StatusPill(text = tier.name, accent = tierAccent)
            capability?.device?.knownHandheldKey?.let { key ->
                StatusPill(text = key, accent = AccentBar.Neutral)
            }
        }

        // Active tune state
        val activeTuneLabel = when (activeTuneState) {
            null -> "STOCK (FACTORY)"
            is ActiveTuneState.Current -> "ACTIVE: ${activeTuneState.name}"
            is ActiveTuneState.MayHaveReverted -> "LAST: ${activeTuneState.name}"
        }
        StatusPill(
            text = activeTuneLabel,
            accent = when (activeTuneState) {
                is ActiveTuneState.Current -> AccentBar.Emerald
                is ActiveTuneState.MayHaveReverted -> AccentBar.Amber
                null -> AccentBar.Neutral
            },
        )

        // ADB grant hint for NONE tier on vendor handhelds
        if (capability != null &&
            capability.privilege == PrivilegeTier.NONE &&
            capability.vendorApps.anyVendorPerfApp
        ) {
            ArsenalPanel(accent = AccentBar.Blue) {
                Text(
                    "GRANT ONCE VIA ADB TO UNLOCK VENDOR PERFORMANCE + FAN CONTROLS (NO ROOT NEEDED):",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBar.Blue,
                    letterSpacing = 0.06.sp,
                )
                Text(
                    "adb shell pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF999999),
                )
            }
        }
    }
}

// --- HUD launcher card ------------------------------------------------

@Composable
private fun HudLauncherCard() {
    val context = LocalContext.current
    val canDraw = remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) { canDraw.value = android.provider.Settings.canDrawOverlays(context) }
    var showLogs by remember { mutableStateOf(false) }

    ArsenalPanel(accent = AccentBar.Neutral, title = "Floating HUD") {
        Text(
            "Draws a draggable performance panel on top of any app. Drag anywhere to move it; " +
                "tap the swap icon to switch between the compact bar and the full panel. The full " +
                "panel can start AutoTDP and switch its profile right from your game.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.group))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            ArsenalButton(
                label = if (canDraw.value) "Show HUD" else "Grant overlay",
                onClick = {
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
                },
                style = ArsenalButtonStyle.Primary,
                accent = AccentBar.Red,
            )
            ArsenalButton(
                label = "Hide HUD",
                onClick = { io.github.mayusi.calibratesoc.ui.overlay.OverlayService.stop(context) },
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
            )
            ArsenalButton(
                label = "HUD Logs",
                onClick = { showLogs = true },
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
            )
        }
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "TIP: ADD THE \"HUD\" QUICK SETTINGS TILE TO TOGGLE WITHOUT OPENING THE APP.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
            letterSpacing = 0.06.sp,
        )
    }
    if (showLogs) {
        HudLogsDialog(onDismiss = { showLogs = false })
    }
}

@Composable
private fun HudLogsDialog(onDismiss: () -> Unit) {
    val viewModel: io.github.mayusi.calibratesoc.ui.overlay.HudLogsViewModel =
        hiltViewModel()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val dateFmt = remember {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HUD logs") },
        text = {
            Box(modifier = Modifier.height(360.dp)) {
                if (entries.isEmpty()) {
                    Text(
                        "Nothing yet. Open the HUD, tap a chip or a ± stepper, and events show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF999999),
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(entries, key = { it.timestampMs }) { entry ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
                                Text(
                                    dateFmt.format(java.util.Date(entry.timestampMs)),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF999999),
                                )
                                Text(
                                    entry.level.name.take(4),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (entry.level) {
                                        io.github.mayusi.calibratesoc.ui.overlay.HudEventLog.Level.ACTION -> AccentBar.Emerald
                                        io.github.mayusi.calibratesoc.ui.overlay.HudEventLog.Level.WARN -> AccentBar.Amber
                                        io.github.mayusi.calibratesoc.ui.overlay.HudEventLog.Level.ERROR -> AccentBar.Red
                                        io.github.mayusi.calibratesoc.ui.overlay.HudEventLog.Level.INFO -> Color(0xFF999999)
                                    },
                                )
                                Text(entry.message, style = MaterialTheme.typography.bodySmall)
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

// --- At a glance summary — MetricTile grid ----------------------------

/**
 * Dense 2-row grid of [MetricTile]s using categorical AccentBar colors:
 *   CPU peak → Blue, GPU MHz → Purple, Power → Amber, Temp → Amber, Battery % → Emerald
 */
@Composable
private fun AtAGlanceCard(t: Telemetry) {
    val peakTempC = t.zoneTempsMilliC
        .filter { it.tempMilliC in 1_000..150_000 }
        .maxOfOrNull { it.tempMilliC / 1000.0 }
    val totalW = t.batteryDrawMilliW?.let { it / 1000.0 }
    val peakCpuMhz = t.perCoreCpuFreqKhz.maxOrNull()?.let { it / 1000 }
    val gpuLoad = t.gpuLoadPct
    val gpuMhz = t.gpuFreqHz?.let { it / 1_000_000L }

    val context = LocalContext.current
    val battPct = remember(t.timestampMs) {
        runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?.takeIf { it >= 0 }
        }.getOrNull()
    }

    val tempAccent = peakTempC?.let { glanceTempAccent(it) } ?: AccentBar.Amber

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
        // Row 1: CPU / GPU load / Power / Temp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            MetricTile(
                label = "CPU",
                value = peakCpuMhz?.toString() ?: "—",
                unit = if (peakCpuMhz != null) "MHz" else null,
                accent = AccentBar.Blue,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "GPU",
                value = gpuMhz?.toString() ?: "—",
                unit = if (gpuMhz != null) "MHz" else null,
                accent = AccentBar.Purple,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "DRAW",
                value = totalW?.let { "%.1f".format(it) } ?: "—",
                unit = if (totalW != null) "W" else null,
                accent = AccentBar.Amber,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "TEMP",
                value = peakTempC?.let { "%.0f".format(it) } ?: "—",
                unit = if (peakTempC != null) "°C" else null,
                accent = tempAccent,
                valueColor = if (tempAccent == AccentBar.Red) AccentBar.Red else null,
                modifier = Modifier.weight(1f),
            )
        }
        // Row 2: GPU Load + Battery
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            MetricTile(
                label = "GPU LOAD",
                value = gpuLoad?.let { "$it" } ?: "—",
                unit = if (gpuLoad != null) "%" else null,
                accent = AccentBar.Purple,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "BATTERY",
                value = battPct?.let { "$it" } ?: "—",
                unit = if (battPct != null) "%" else null,
                accent = AccentBar.Emerald,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Severity-keyed accent: emerald < 60, amber 60–75, red > 75 °C. */
private fun glanceTempAccent(c: Double): Color = when {
    c > 75 -> AccentBar.Red
    c >= 60 -> AccentBar.Amber
    else    -> AccentBar.Emerald
}

// --- Per-core CPU frequency — StatBar rows ----------------------------

@Composable
private fun PerCoreFreqCard(t: Telemetry) {
    ArsenalPanel(accent = AccentBar.Blue, title = "CPU FREQUENCY (MHz, LIVE)") {
        if (t.perCoreCpuFreqKhz.isEmpty()) {
            Text(
                "scaling_cur_freq unreadable",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF999999),
            )
            return@ArsenalPanel
        }
        val maxKhz = t.perCoreCpuFreqKhz.max().coerceAtLeast(1)
        t.perCoreCpuFreqKhz.forEachIndexed { idx, khz ->
            StatBar(
                label = "CPU$idx",
                value = "${khz / 1000} MHz",
                fraction = khz.toFloat() / maxKhz.toFloat(),
                accent = AccentBar.Blue,
            )
        }
    }
}

// --- CPU load chart ---------------------------------------------------

@Composable
private fun CpuLoadCard(history: List<Telemetry>) {
    ArsenalPanel(accent = AccentBar.Blue, title = "CPU LOAD (%)") {
        if (history.isEmpty() || history.last().perCoreLoadPct.isEmpty()) {
            Text("WARMING UP…", style = MaterialTheme.typography.labelSmall, color = Color(0xFF999999), letterSpacing = 0.08.sp)
            return@ArsenalPanel
        }

        val series = history.map { t ->
            val loads = t.perCoreLoadPct
            if (loads.isEmpty()) 0f else loads.sum().toFloat() / loads.size
        }
        MetricLineChart(points = series, heightDp = 100)

        // Per-core snapshot StatBars for the latest sample
        val latestLoads = history.last().perCoreLoadPct
        if (latestLoads.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.group))
            Text(
                "PER-CORE (LIVE)",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF999999),
                letterSpacing = 0.08.sp,
            )
            latestLoads.forEachIndexed { idx, pct ->
                StatBar(
                    label = "CPU$idx",
                    value = "$pct%",
                    fraction = pct.toFloat() / 100f,
                    accent = AccentBar.Blue,
                )
            }
        }
    }
}

// --- GPU card — purple accent ----------------------------------------

@Composable
private fun GpuCard(history: List<Telemetry>, latest: Telemetry) {
    val current = latest.gpuLoadPct
    val freqMhz = latest.gpuFreqHz?.let { it / 1_000_000L }

    ArsenalPanel(accent = AccentBar.Purple, title = "GPU") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            MetricTile(
                label = "LOAD",
                value = current?.let { "$it" } ?: "—",
                unit = if (current != null) "%" else null,
                accent = AccentBar.Purple,
                valueColor = current?.let { if (it > 90) AccentBar.Amber else null },
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "FREQ",
                value = freqMhz?.toString() ?: "—",
                unit = if (freqMhz != null) "MHz" else null,
                accent = AccentBar.Purple,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }

        val series = history.map { it.gpuLoadPct?.toFloat() ?: 0f }
        if (series.all { it == 0f } && current == null) {
            Text(
                "GPU load unreadable on this device",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
        } else {
            Spacer(Modifier.height(Spacing.group))
            MetricLineChart(points = series, heightDp = 120)
        }
    }
}

// --- Thermal card — amber accent -------------------------------------

@Composable
private fun ThermalCard(t: Telemetry) {
    ArsenalPanel(accent = AccentBar.Amber, title = "TEMPERATURES") {
        if (t.zoneTempsMilliC.isEmpty()) {
            Text(
                "No thermal zones reachable",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
            return@ArsenalPanel
        }
        val maxMilliC = t.zoneTempsMilliC
            .filter { it.tempMilliC in 1_000..150_000 }
            .maxOfOrNull { it.tempMilliC }?.coerceAtLeast(1) ?: 1
        val top = t.zoneTempsMilliC
            .filter { it.tempMilliC in 1_000..150_000 }
            .sortedByDescending { it.tempMilliC }
            .take(6)
        top.forEach { z ->
            StatBar(
                label = z.label,
                value = "${"%.1f".format(z.tempMilliC / 1000.0)} °C",
                fraction = z.tempMilliC.toFloat() / maxMilliC.toFloat(),
                accent = tempAccentForZone(z),
            )
        }
    }
}

private fun tempAccentForZone(z: ZoneTemp): Color {
    val c = z.tempMilliC / 1000
    return when {
        c >= 80 -> AccentBar.Red
        c >= 65 -> AccentBar.Amber
        else    -> AccentBar.Emerald
    }
}

// --- Battery card — amber accent -------------------------------------

@Composable
private fun BatteryCard(t: Telemetry, estimate: BatteryEstimate) {
    ArsenalPanel(accent = AccentBar.Amber, title = "BATTERY") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            MetricTile(
                label = "TEMP",
                value = t.batteryTempDeciC?.let { "%.1f".format(it / 10.0) } ?: "—",
                unit = if (t.batteryTempDeciC != null) "°C" else null,
                accent = AccentBar.Amber,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "VOLTAGE",
                value = t.batteryVoltageUv?.let { "%.2f".format(it / 1_000_000.0) } ?: "—",
                unit = if (t.batteryVoltageUv != null) "V" else null,
                accent = AccentBar.Amber,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "DRAW",
                value = t.batteryDrawMilliW?.let { "%.2f".format(it / 1000.0) } ?: "—",
                unit = if (t.batteryDrawMilliW != null) "W" else null,
                accent = AccentBar.Amber,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Spacing.group))
        BatteryEstimateRow(estimate)
    }
}

@Composable
private fun BatteryEstimateRow(estimate: BatteryEstimate) {
    when (estimate.basis) {
        EstimateBasis.INSUFFICIENT_DATA -> {
            Text(
                "Battery time estimate unavailable on this device",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
        }
        EstimateBasis.CHARGING -> {
            val drawLabel = estimate.watts?.let { " (%.1f W)".format(it) } ?: ""
            StatusPill(text = "CHARGING$drawLabel", accent = AccentBar.Emerald)
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
            val drawLabel = estimate.watts?.let { "%.1f W".format(it) }
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
                Text(
                    "REMAINING",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF999999),
                    letterSpacing = 0.08.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (drawLabel != null) {
                Text(
                    "$drawLabel at this load · Estimate only — varies with game activity",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF777777),
                )
            }
        }
    }
}

// --- Session recording card ------------------------------------------

@Composable
private fun SessionRecordingCard(
    isRecording: Boolean,
    elapsedSeconds: Long,
    onToggleRecord: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val accent = if (isRecording) AccentBar.Red else AccentBar.Neutral
    ArsenalPanel(accent = accent, title = "SESSION RECORDER") {
        Text(
            "Record FPS, temps, clocks, and power over a play session, then review " +
                "the timeline to diagnose drops or throttle events.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        if (!isRecording) {
            Text(
                "Note: real in-game FPS is captured only while the HUD is also running. " +
                    "Without the HUD, a session still records temps, clocks, and power.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF777777),
            )
        }
        if (isRecording) {
            val mins = elapsedSeconds / 60
            val secs = elapsedSeconds % 60
            StatusPill(
                text = "REC %d:%02d".format(mins, secs),
                accent = AccentBar.Red,
            )
        }
        Spacer(Modifier.height(Spacing.group))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            ArsenalButton(
                label = if (isRecording) "Stop Recording" else "Start Recording",
                onClick = onToggleRecord,
                style = ArsenalButtonStyle.Primary,
                accent = if (isRecording) AccentBar.Red else AccentBar.Emerald,
            )
            ArsenalButton(
                label = "View Sessions",
                onClick = onOpenSessions,
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
            )
        }
    }
}

// --- CPU time-in-state histogram --------------------------------------

@Composable
private fun CpuTimeInStateCard(cap: CapabilityReport) {
    ArsenalPanel(accent = AccentBar.Blue, title = "CPU FREQ RESIDENCY (SINCE BOOT)") {
        Text(
            "How long each cluster has spent at each clock speed since the last reboot (read-only, no root needed).",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.group))
        cap.cpuTimeInState.forEach { probe ->
            val totalJiffies = probe.entries.sumOf { it.jiffies }.takeIf { it > 0L } ?: 1L
            Text(
                "CLUSTER / POLICY${probe.policyId}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = AccentBar.Blue,
                letterSpacing = 0.08.sp,
            )
            Spacer(Modifier.height(Spacing.dense))
            val topEntries = probe.entries
                .filter { it.jiffies > 0L }
                .sortedByDescending { it.jiffies }
                .take(5)
            if (topEntries.isEmpty()) {
                Text(
                    "No data yet — device may not have been under load",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            } else {
                topEntries.forEach { entry ->
                    val pct = (entry.jiffies.toDouble() / totalJiffies * 100).coerceIn(0.0, 100.0)
                    val mhz = entry.freqKhz / 1000
                    StatBar(
                        label = "$mhz MHz",
                        value = "${"%.1f".format(pct)}%",
                        fraction = pct.toFloat() / 100f,
                        accent = AccentBar.Blue,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.group))
        }
    }
}

// --- Thermal trip-point detail ----------------------------------------

@Composable
private fun ThermalDetailCard(
    zones: List<ThermalZoneProbe>,
    extras: List<ThermalZoneExtras>,
) {
    ArsenalPanel(accent = AccentBar.Amber, title = "THERMAL TRIP POINTS (READ-ONLY)") {
        Text(
            "Where your device starts throttling. All data is read-only — no root needed.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.group))

        val extrasWithTrips = extras.filter { it.tripPoints.isNotEmpty() }
        if (extrasWithTrips.isEmpty()) {
            Text(
                "Trip point data not available on this kernel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
            return@ArsenalPanel
        }

        extrasWithTrips.forEach { extra ->
            val zone = zones.firstOrNull { it.id == extra.zoneId }
            val label = zone?.type ?: "zone${extra.zoneId}"
            val liveC = zone?.currentTempMilliC?.let { "  (now %.0f °C)".format(it / 1000.0) } ?: ""

            Text(
                "${label.uppercase()}$liveC",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 0.06.sp,
            )
            extra.mode?.let { mode ->
                StatusPill(
                    text = "MODE: $mode",
                    accent = if (mode == "disabled") AccentBar.Red else AccentBar.Neutral,
                )
            }
            extra.tripPoints.sortedBy { it.tempMilliC }.forEach { tp ->
                val tripAccent = when {
                    tp.tempMilliC >= 95_000 -> AccentBar.Red
                    tp.tempMilliC >= 80_000 -> AccentBar.Amber
                    else -> AccentBar.Neutral
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        tp.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF999999),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%.0f °C".format(tp.tempMilliC / 1000.0),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = tripAccent,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.group))
        }
    }
}

// --- DDR / bus frequency card -----------------------------------------

@Composable
private fun DdrBusCard(devices: List<DevfreqDeviceProbe>) {
    ArsenalPanel(accent = AccentBar.Blue, title = "MEMORY / BUS BANDWIDTH (READ-ONLY)") {
        Text(
            "DDR and bus interconnect operating frequencies at last probe refresh (read-only, no root).",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.group))
        devices.forEach { dev ->
            val ratio = if (dev.maxFreqHz > 0L) {
                (dev.curFreqHz.toFloat() / dev.maxFreqHz.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val curMhz = dev.curFreqHz / 1_000_000L
            val maxMhz = dev.maxFreqHz / 1_000_000L
            StatBar(
                label = dev.deviceName,
                value = "$curMhz / $maxMhz MHz",
                fraction = ratio,
                accent = AccentBar.Blue,
            )
            Text(
                "gov: ${dev.currentGovernor}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF777777),
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(Spacing.dense))
        }
    }
}

// --- RAM card --------------------------------------------------------

@Composable
private fun RamCard(t: Telemetry) {
    ArsenalPanel(accent = AccentBar.Neutral, title = "RAM") {
        if (t.ramTotalKb == 0L) {
            Text(
                "/proc/meminfo unreadable",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
            return@ArsenalPanel
        }
        val usedKb = (t.ramTotalKb - t.ramAvailableKb).coerceAtLeast(0)
        val frac = (usedKb.toFloat() / t.ramTotalKb).coerceIn(0f, 1f)
        StatBar(
            label = "USED",
            value = "${usedKb / 1024} / ${t.ramTotalKb / 1024} MB",
            fraction = frac,
            accent = if (frac > 0.85f) AccentBar.Red else if (frac > 0.70f) AccentBar.Amber else AccentBar.Neutral,
        )
    }
}
