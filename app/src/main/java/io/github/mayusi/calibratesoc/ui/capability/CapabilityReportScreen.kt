package io.github.mayusi.calibratesoc.ui.capability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.FanProbe
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.ThermalZoneProbe
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter

/**
 * Device Info / capability diagnostic screen: dumps the full probed capability
 * report so we can verify the probe layer on real hardware. The Dashboard
 * (Phase 3) is the primary home screen; this screen surfaces the raw probe
 * detail and is accessible from Settings → Device Info.
 */
@Composable
fun CapabilityReportScreen(
    viewModel: CapabilityReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is CapabilityReportViewModel.UiState.Loading -> LoadingPane()
        is CapabilityReportViewModel.UiState.Ready -> ReadyPane(
            report = s.report,
            adapter = s.adapter,
            onRefresh = viewModel::refresh,
        )
    }
}

@Composable
private fun LoadingPane() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Probing device…", style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReadyPane(
    report: CapabilityReport,
    adapter: DeviceAdapter?,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: CapabilityReportViewModel = hiltViewModel()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    "Device Info",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "What the probe found on this device. Tap Report unknown device if there's no matching adapter so a future release can ship one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = onRefresh) { Text("Re-probe") }
                OutlinedButton(onClick = {
                    val intent = viewModel.buildReportShareIntent() ?: return@OutlinedButton
                    runCatching { context.startActivity(intent) }
                }) { Text("Report unknown device") }
                // Disk-export for multi-device adapter authoring. Drops
                // device_report_<model>.json next to the rest of our
                // generated artifacts so it can be pulled via adb:
                //   adb pull /sdcard/CalibrateSoC/device_report_*.json
                OutlinedButton(onClick = {
                    val path = viewModel.saveReportToDisk()
                    val msg = if (path != null) "Saved → $path"
                        else "Save failed — external storage not writable"
                    android.widget.Toast.makeText(
                        context, msg, android.widget.Toast.LENGTH_LONG,
                    ).show()
                }) { Text("Save report to file") }
            }
        }

        item { DeviceCard(report) }
        item { SoCCard(report) }
        item { PrivilegeCard(report.privilege, report.shizuku, report.rootKind.name) }
        if (adapter != null) item { AdapterCard(adapter) }
        items(report.cpuPolicies) { CpuPolicyCard(it) }
        report.gpu?.let { item { GpuCard(it) } }
        if (report.thermalZones.isNotEmpty()) item { ThermalCard(report.thermalZones) }
        report.fan?.let { item { FanCard(it) } }
        item { VendorAppCard(report.vendorApps) }
    }
}

@Composable
private fun DeviceCard(report: CapabilityReport) = SectionCard("Device") {
    val d = report.device
    LabelValue("Manufacturer", d.manufacturer.ifBlank { "—" })
    LabelValue("Brand / model", "${d.brand} / ${d.model}")
    LabelValue("device.hardware", "${d.device} / ${d.hardware}")
    LabelValue("Android", "${d.androidVersion} (API ${d.sdkInt})")
    LabelValue("Known handheld key", d.knownHandheldKey ?: "(generic)")
}

@Composable
private fun SoCCard(report: CapabilityReport) = SectionCard("SoC") {
    val s = report.soc
    LabelValue("SoC manufacturer", s.socManufacturer.ifBlank { "—" })
    LabelValue("SoC model", s.socModel.ifBlank { "—" })
    LabelValue("GPU family (heuristic)", s.gpuFamily.name)
}

@Composable
private fun PrivilegeCard(tier: PrivilegeTier, shizuku: ShizukuStatus, rootKind: String) =
    SectionCard("Privilege tier") {
        LabelValue("Tier", tier.name)
        LabelValue("Root", if (rootKind == "NONE") "no" else rootKind)
        LabelValue("Shizuku installed", shizuku.installed.toString())
        LabelValue("Shizuku running", shizuku.running.toString())
        LabelValue("Shizuku perm granted", shizuku.permissionGranted.toString())
        LabelValue(
            "Sysfs write probe",
            when (shizuku.sysfsWriteAllowed) {
                true -> "allowed"
                false -> "blocked (SELinux)"
                null -> "not yet probed"
            },
        )
    }

@Composable
private fun AdapterCard(adapter: DeviceAdapter) = SectionCard("Matched device adapter") {
    LabelValue("Adapter key", adapter.key)
    LabelValue("Display name", adapter.displayName)
    LabelValue("Vendor app", adapter.vendorAppPackage ?: "(none)")
    LabelValue(
        "Performance tuning adapter",
        adapter.perfPresetAdapter?.let { "${it.kind} -> ${it.target}" } ?: "(none)",
    )
    LabelValue(
        "Fan adapter",
        adapter.fanAdapter?.let { "${it.kind} -> ${it.target}" } ?: "(none)",
    )
    adapter.notes?.let { LabelValue("Notes", it) }
}

@Composable
private fun CpuPolicyCard(policy: CpuPolicyProbe) =
    SectionCard("CPU policy ${policy.policyId}") {
        LabelValue("Cores", policy.onlineCores.joinToString(", "))
        LabelValue(
            "Available freqs (MHz)",
            policy.availableFreqsKhz.joinToString(", ") { (it / 1000).toString() }
                .ifBlank { "—" },
        )
        LabelValue("Governors", policy.availableGovernors.joinToString(", ").ifBlank { "—" })
        LabelValue(
            "Current min / max (MHz)",
            "${policy.currentMinKhz / 1000} / ${policy.currentMaxKhz / 1000}",
        )
        LabelValue("Current governor", policy.currentGovernor.ifBlank { "—" })
        LabelValue(
            "Hardware limits (MHz)",
            policy.hardwareLimitsKhz?.let { "${it.lowKhz / 1000}..${it.highKhz / 1000}" } ?: "—",
        )
    }

@Composable
private fun GpuCard(gpu: GpuProbe) = SectionCard("GPU (${gpu.family})") {
    LabelValue("Root path", gpu.rootPath)
    LabelValue(
        "Available freqs (MHz)",
        gpu.availableFreqsHz.joinToString(", ") { (it / 1_000_000L).toString() }
            .ifBlank { "—" },
    )
    LabelValue("Governors", gpu.availableGovernors.joinToString(", ").ifBlank { "—" })
    LabelValue(
        "Current min / max (MHz)",
        "${gpu.currentMinHz / 1_000_000L} / ${gpu.currentMaxHz / 1_000_000L}",
    )
    LabelValue("Current governor", gpu.currentGovernor.ifBlank { "—" })
    LabelValue(
        "Power level range",
        gpu.powerLevelRange?.let { "${it.low}..${it.high}" } ?: "—",
    )
}

@Composable
private fun ThermalCard(zones: List<ThermalZoneProbe>) =
    SectionCard("Thermal zones (${zones.size})") {
        zones.forEach { z ->
            LabelValue(
                "tz${z.id} [${z.role}]  ${z.type}",
                "${"%.1f".format(z.currentTempMilliC / 1000.0)} °C",
            )
        }
    }

@Composable
private fun FanCard(fan: FanProbe) = SectionCard("Fan") {
    LabelValue("Source", fan.source.name)
    LabelValue("Control path", fan.controlPath)
    LabelValue("Curve support", fan.supportsCurve.toString())
    LabelValue(
        "Fan modes",
        fan.availablePresets.joinToString(", ").ifBlank { "—" },
    )
    LabelValue("Current RPM", fan.currentRpm?.toString() ?: "—")
}

@Composable
private fun VendorAppCard(v: VendorAppPresence) = SectionCard("Vendor companion apps") {
    LabelValue("AYN Game Assistant", v.aynGameAssistant.toString())
    LabelValue("langerhans OdinTools", v.langerhansOdinTools.toString())
    LabelValue("AYANEO AYASpace", v.ayaSpace.toString())
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
