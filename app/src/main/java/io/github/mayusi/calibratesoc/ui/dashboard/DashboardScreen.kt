package io.github.mayusi.calibratesoc.ui.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.ThermalRole
import io.github.mayusi.calibratesoc.data.monitor.Telemetry
import io.github.mayusi.calibratesoc.data.monitor.ZoneTemp
import io.github.mayusi.calibratesoc.data.monitor.batteryDrawMilliW

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
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(capability) }
        item { HudLauncherCard() }
        if (latest == null) {
            item { Text("Sampling…", style = MaterialTheme.typography.bodyMedium) }
            return@LazyColumn
        }
        val current = latest!!

        item { PerCoreFreqCard(current) }
        item { CpuLoadCard(history) }
        item { GpuCard(history, current) }
        item { ThermalCard(current) }
        item { BatteryCard(current) }
        item { RamCard(current) }
    }
}

// --- Header -------------------------------------------------------------

@Composable
private fun Header(capability: CapabilityReport?) {
    Column {
        Text(
            "Calibrate SoC",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        // Tier-mismatch hint: surfaced when the user is on NONE but the
        // device is a vendor handheld (AYN/AYANEO/Retroid) where the
        // vendor-controls tier is one adb command away. We never
        // auto-grant — the user must run the command from a PC — but we
        // tell them exactly what it is.
        if (capability != null &&
            capability.privilege == PrivilegeTier.NONE &&
            capability.vendorApps.anyVendorPerfApp
        ) {
            Spacer(Modifier.height(4.dp))
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
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Spacer(Modifier.height(4.dp))
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("cpu$idx", modifier = Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(
                progress = { (khz.toFloat() / maxKhz.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
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

@Composable
private fun CpuLoadCard(history: List<Telemetry>) = SectionCard("CPU load (%)") {
    if (history.isEmpty() || history.last().perCoreLoadPct.isEmpty()) {
        Text("warming up…", style = MaterialTheme.typography.bodyMedium)
        return@SectionCard
    }
    // Aggregate across cores → mean. Per-core stack lands in Phase 5
    // once we have screen real estate for it.
    val series = history.map { t ->
        val loads = t.perCoreLoadPct
        if (loads.isEmpty()) 0f else loads.sum().toFloat() / loads.size
    }
    LineChartBox(series)
}

// --- GPU chart -------------------------------------------------------

@Composable
private fun GpuCard(history: List<Telemetry>, latest: Telemetry) = SectionCard("GPU") {
    val current = latest.gpuLoadPct
    val freqMhz = latest.gpuFreqHz?.let { it / 1_000_000L }
    Text(
        "${current?.let { "$it%" } ?: "—"}   ${freqMhz?.let { "$it MHz" } ?: ""}",
        style = MaterialTheme.typography.bodyLarge,
        fontFamily = FontFamily.Monospace,
    )
    val series = history.map { it.gpuLoadPct?.toFloat() ?: 0f }
    if (series.all { it == 0f } && current == null) {
        Text("GPU load unreadable on this device", style = MaterialTheme.typography.bodySmall)
    } else {
        LineChartBox(series)
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
private fun BatteryCard(t: Telemetry) = SectionCard("Battery") {
    val tempC = t.batteryTempDeciC?.let { "%.1f °C".format(it / 10.0) } ?: "—"
    val volts = t.batteryVoltageUv?.let { "%.2f V".format(it / 1_000_000.0) } ?: "—"
    val watts = t.batteryDrawMilliW?.let { "%.2f W".format(it / 1000.0) } ?: "—"
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatColumn("Temp", tempC)
        StatColumn("Voltage", volts)
        StatColumn("Draw", watts)
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

// --- Shared bits -----------------------------------------------------

@Composable
private fun StatColumn(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/**
 * Minimal Vico line chart wrapped in a fixed-height box. Skips rendering
 * for empty/single-point histories — Vico bombs on a degenerate series.
 */
@Composable
private fun LineChartBox(values: List<Float>) {
    if (values.size < 2) {
        Box(Modifier.height(80.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surface))
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        producer.runTransaction { lineSeries { series(values) } }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = producer,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    )
}
