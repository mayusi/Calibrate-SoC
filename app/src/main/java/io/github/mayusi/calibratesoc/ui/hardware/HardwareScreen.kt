package io.github.mayusi.calibratesoc.ui.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.baseline.BaselineDegradation
import io.github.mayusi.calibratesoc.data.baseline.DegradationReport
import io.github.mayusi.calibratesoc.data.baseline.DegradationStatus
import io.github.mayusi.calibratesoc.data.hardware.BatteryInfo
import io.github.mayusi.calibratesoc.data.hardware.Confidence
import io.github.mayusi.calibratesoc.data.hardware.DisplayInfo
import io.github.mayusi.calibratesoc.data.hardware.HardwareReport
import io.github.mayusi.calibratesoc.data.hardware.MemoryInfo
import io.github.mayusi.calibratesoc.data.hardware.NetworkTestResult
import io.github.mayusi.calibratesoc.data.hardware.RadioInfo
import io.github.mayusi.calibratesoc.data.hardware.SocInfo
import io.github.mayusi.calibratesoc.data.hardware.StorageVolume
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.SectionHeader
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Hardware tab: identifies what's in the device + speed-tests
 * storage / memory / network. Distinct from Benchmark, which
 * stress-tests sustained throughput under load — this answers
 * "what hardware do I actually have and how fast is it".
 */
@Composable
fun HardwareScreen(viewModel: HardwareViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val tests by viewModel.testState.collectAsStateWithLifecycle()
    val degradation by viewModel.degradationReport.collectAsStateWithLifecycle()

    val r = report
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Text(
                    "HARDWARE",
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White,
                    letterSpacing = 0.04.sp,
                )
                Text(
                    "Identify what's in your device + speed-test storage, memory, and network. Different from Benchmark — which stress-tests sustained CPU/GPU throughput.",
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color(0xFF999999),
                )
            }
        }

        // Baseline degradation card — shown whenever a baseline exists.
        degradation?.let { item { BaselineDegradationCard(it) } }

        item { SocCard(r.soc) }
        item {
            MemoryCard(
                memory = r.memory,
                running = tests.memoryRunning,
                error = tests.memoryError,
                onRun = { viewModel.runMemoryTest() },
            )
        }
        items(r.storage, key = { it.label }) { vol ->
            StorageCard(
                volume = vol,
                running = tests.storageRunning,
                error = tests.storageError,
                onRun = { viewModel.runStorageTest() },
                isPrimary = vol == r.storage.firstOrNull(),
            )
        }
        item {
            NetworkCard(
                running = tests.networkRunning,
                result = tests.networkResult,
                error = tests.networkError,
                onRun = { viewModel.runNetworkTest() },
            )
        }
        item { DisplayCard(r.display) }
        item { BatteryCard(r.battery) }
        item { RadioCard(r.radios) }
    }
}

// --- Baseline Degradation Card -----------------------------------

/**
 * Shows the result of comparing the factory-baseline clock ceilings
 * against the device's current state.
 *
 * Status colours:
 *   OK              → tertiary (green)
 *   MINOR           → secondary (amber)
 *   DEGRADED        → error (red)
 *   INSUFFICIENT_DATA → muted (onSurfaceVariant) with a teaching message
 */
@Composable
private fun BaselineDegradationCard(report: DegradationReport) {
    val accent = when (report.status) {
        DegradationStatus.OK -> AccentBar.Emerald
        DegradationStatus.MINOR -> AccentBar.Amber
        DegradationStatus.DEGRADED -> AccentBar.Red
        DegradationStatus.INSUFFICIENT_DATA -> AccentBar.Neutral
    }
    SectionCard(
        title = "Baseline health check",
        accent = accent,
        explainer = "Compares current clock ceilings against your factory baseline.",
        icon = Icons.Outlined.HealthAndSafety,
    ) {
        when (report.status) {
            DegradationStatus.INSUFFICIENT_DATA -> {
                Text(
                    "Not enough data to compare",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentBar.Neutral,
                )
                report.insufficientDataReason?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color(0xFF999999),
                    )
                }
            }

            DegradationStatus.OK -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "OK — clock ceilings match factory baseline",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentBar.Emerald,
                    )
                }
                report.findings.forEach { finding ->
                    DegradationFindingRow(finding)
                }
                report.limitationNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color(0xFF999999),
                    )
                }
            }

            DegradationStatus.MINOR -> {
                AlertCard(
                    type = AlertType.WARNING,
                    title = "Minor clock drift detected",
                    message = "One or more clock ceilings are 1–10 % below the factory baseline. " +
                        "This may indicate an OTA tuned the OPP table slightly, or a third-party " +
                        "tool adjusted limits before the baseline was captured.",
                )
                report.findings.forEach { finding ->
                    DegradationFindingRow(finding)
                }
            }

            DegradationStatus.DEGRADED -> {
                AlertCard(
                    type = AlertType.ERROR,
                    title = "Clock ceiling dropped > 10 % from baseline",
                    message = "One or more clock ceilings are significantly lower than what was " +
                        "observed on first launch. This most commonly means a vendor OTA silently " +
                        "reduced an OPP table entry, or a tuning tool wrote a permanent cap before " +
                        "the factory baseline was captured. It does NOT directly measure thermal " +
                        "paste dry-out or battery wear — run a Full benchmark for that.",
                )
                report.findings.forEach { finding ->
                    DegradationFindingRow(finding)
                }
            }
        }
    }
}

@Composable
private fun DegradationFindingRow(finding: io.github.mayusi.calibratesoc.data.baseline.DegradationFinding) {
    val valueColor = when {
        finding.isDrop && finding.changePct >= BaselineDegradation.DEGRADED_THRESHOLD_PCT -> AccentBar.Red
        finding.isDrop && finding.changePct >= BaselineDegradation.MINOR_THRESHOLD_PCT -> AccentBar.Amber
        else -> AccentBar.Emerald
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                finding.signal,
                style = MaterialTheme.typography.labelMedium,
                color = androidx.compose.ui.graphics.Color(0xFF999999),
            )
            Text(
                "${finding.baselineValue} -> ${finding.currentValue}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
        Text(
            finding.formattedPct,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
        )
    }
}

// --- Cards --------------------------------------------------------

@Composable
private fun SocCard(soc: SocInfo) = SectionCard(
    title = "SoC",
    accent = AccentBar.Blue,
    explainer = "The chip, its GPU, and core layout.",
    icon = Icons.Outlined.DeveloperBoard,
) {
    KV("Model", "${soc.friendlyName}  ", soc.confidence)
    KV("Vendor codename", "${soc.manufacturer} ${soc.model}")
    soc.gpuName?.let { KV("GPU", it) }
    KV("Cores", soc.coreCount.toString())
}

@Composable
private fun MemoryCard(memory: MemoryInfo, running: Boolean, error: String?, onRun: () -> Unit) =
    SectionCard(
        title = "Memory",
        accent = AccentBar.Blue,
        explainer = "RAM type and measured bandwidth.",
        icon = Icons.Outlined.Memory,
    ) {
        KV("Total", "${advertisedGb(memory.totalMb)} GB (${memory.totalMb} MB kernel-visible)")
        KV("Available", "%.1f GB".format(memory.availableMb / 1024f))
        KV("Type (inferred)", memory.inferredType, memory.inferredConfidence)
        if (memory.measuredBandwidthMBps != null) {
            KV("Measured bandwidth", "%.1f MB/s".format(memory.measuredBandwidthMBps))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArsenalButton(
                label = if (running) "Testing..." else "Measure RAM bandwidth",
                onClick = onRun,
                style = ArsenalButtonStyle.Primary,
                accent = AccentBar.Blue,
                enabled = !running,
            )
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = Spacing.dense),
                    strokeWidth = 2.dp,
                )
            }
        }
        TestError("RAM bandwidth test failed", error)
    }

@Composable
private fun StorageCard(volume: StorageVolume, running: Boolean, error: String?, onRun: () -> Unit, isPrimary: Boolean) =
    SectionCard(
        title = "Storage — ${volume.label}",
        accent = AccentBar.Neutral,
        explainer = "Storage class and real read/write speeds.",
        icon = Icons.Outlined.Storage,
    ) {
        KV("Capacity", "%.1f GB total · %.1f GB free".format(volume.totalGb, volume.freeGb))
        KV("Class (inferred)", volume.inferredClass, volume.inferredConfidence)
        volume.vendorModel?.let { KV("Vendor model", it) }
        if (volume.seqReadMBps != null) {
            HorizontalDivider(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
            KV("Sequential read", "%.0f MB/s".format(volume.seqReadMBps))
            KV("Sequential write", "%.0f MB/s".format(volume.seqWriteMBps ?: 0.0))
            KV("Random 4K read", "${volume.randomReadIOPS ?: 0} IOPS")
            KV("Random 4K write", "${volume.randomWriteIOPS ?: 0} IOPS")
        }
        if (isPrimary) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArsenalButton(
                    label = if (running) "Testing..." else "Run storage test",
                    onClick = onRun,
                    style = ArsenalButtonStyle.Primary,
                    accent = AccentBar.Neutral,
                    enabled = !running,
                )
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(start = Spacing.dense),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Text(
                "Writes ~512 MB to internal storage during the test, deleted immediately after. Negligible wear.",
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color(0xFF999999),
            )
            TestError("Storage test failed", error)
        }
    }

@Composable
private fun NetworkCard(running: Boolean, result: NetworkTestResult?, error: String?, onRun: () -> Unit) =
    SectionCard(
        title = "Network",
        accent = AccentBar.Emerald,
        explainer = "Real-world download, upload, and latency.",
        icon = Icons.Outlined.Wifi,
    ) {
        if (result != null) {
            result.downloadMbps?.let { KV("Download", "%.1f Mbps".format(it)) }
            result.uploadMbps?.let { KV("Upload", "%.1f Mbps".format(it)) }
            result.latencyCloudflareMs?.let { KV("Latency · Cloudflare", "$it ms") }
            result.latencyGoogleMs?.let { KV("Latency · Google", "$it ms") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArsenalButton(
                label = if (running) "Testing..." else "Run network test",
                onClick = onRun,
                style = ArsenalButtonStyle.Primary,
                accent = AccentBar.Emerald,
                enabled = !running,
            )
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = Spacing.dense),
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            "Downloads 50 MB + uploads 25 MB to speed.cloudflare.com. ~15s on a 5G connection, longer on slower links.",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(0xFF999999),
        )
        TestError("Network test failed", error)
    }

@Composable
private fun DisplayCard(d: DisplayInfo) = SectionCard(
    title = "Display",
    accent = AccentBar.Neutral,
    explainer = "Panel resolution, refresh rates, and HDR.",
    icon = Icons.Outlined.Brightness6,
) {
    KV("Resolution", "${d.widthPx} x ${d.heightPx}")
    KV("Density", "${d.densityDpi} dpi")
    KV("Refresh", "%.0f Hz".format(d.refreshHz))
    if (d.supportedRefreshHz.size > 1) {
        KV("Supported", d.supportedRefreshHz.joinToString(", ") { "%.0f".format(it) } + " Hz")
    }
    KV("HDR", if (d.hdrSupported) "yes" else "no")
}

@Composable
private fun BatteryCard(b: BatteryInfo) = SectionCard(
    title = "Battery",
    accent = AccentBar.Amber,
    explainer = "Capacity, health, and live draw.",
    icon = Icons.Outlined.BatteryChargingFull,
) {
    // Technology and health status come from the BatteryManager framework
    // API and are available on every device regardless of SELinux policy.
    b.technology?.let { KV("Technology", it) }
    b.healthStatus?.let { KV("Health status", it) }

    // Design capacity: from sysfs (precise) or device-DB (approximate).
    b.designCapacityMah?.let { mah ->
        val confidence = if (b.designCapacityFromSysfs) null else Confidence.MEDIUM
        KV("Design capacity", "$mah mAh", confidence)
    }
    // Current full capacity + derived health % are only available from sysfs.
    b.currentCapacityMah?.let { KV("Current full capacity", "$it mAh") }
    b.healthPercent?.let { KV("Health (charge)", "$it%") }
    b.cycleCount?.let { KV("Cycle count", "$it") }

    // Only show the restricted message when the CAPACITY numbers are
    // genuinely absent — health/technology from the API are always present
    // and do not count as missing data.
    if (b.designCapacityMah == null && b.currentCapacityMah == null) {
        Text(
            "Battery details aren't readable on this device — most stock Android kernels keep these sysfs nodes restricted.",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(0xFF999999),
        )
    }
}

@Composable
private fun RadioCard(r: RadioInfo) = SectionCard(
    title = "Radios",
    accent = AccentBar.Neutral,
    explainer = "Wireless connectivity and positioning.",
    icon = Icons.Outlined.SettingsInputAntenna,
) {
    KV("Wi-Fi", r.wifiStandard)
    KV("Bluetooth", r.bluetoothVersion)
    KV("NFC", if (r.nfcPresent) "present" else "not present")
    if (r.gpsConstellations.isNotEmpty()) {
        KV("GPS", r.gpsConstellations.joinToString(", "))
    }
}

// --- Shared helpers ----------------------------------------------

/**
 * Local section card — wraps [ArsenalPanel] with a semantic accent color.
 * An optional explainer is rendered as muted bodySmall below the panel header.
 */
@Composable
private fun SectionCard(
    title: String,
    accent: androidx.compose.ui.graphics.Color = AccentBar.Neutral,
    explainer: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit,
) {
    ArsenalPanel(accent = accent, title = title) {
        if (explainer != null) {
            Text(
                explainer,
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFF999999),
            )
        }
        content()
    }
}

/**
 * Inline error row shown under a speed-test card when the test threw.
 * Renders nothing when [error] is null so the success path is untouched.
 */
@Composable
private fun TestError(prefix: String, error: String?) {
    if (error == null) return
    Text(
        "$prefix: $error",
        style = MaterialTheme.typography.labelMedium,
        color = AccentBar.Red,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun KV(label: String, value: String, confidence: Confidence? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.padding(end = Spacing.item),
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color(0xFF999999),
        )
        Text(
            value,
            modifier = Modifier.padding(end = Spacing.group),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color.White,
        )
        confidence?.let { ConfidenceChip(it) }
    }
}

@Composable
private fun ConfidenceChip(c: Confidence) {
    val (label, accent) = when (c) {
        Confidence.HIGH -> "verified" to AccentBar.Emerald
        Confidence.MEDIUM -> "inferred" to AccentBar.Amber
        Confidence.LOW -> "guess" to AccentBar.Neutral
        Confidence.UNKNOWN -> "unknown" to AccentBar.Red
    }
    StatusPill(text = label, accent = accent)
}

/**
 * Snap kernel-visible MB to the closest power-of-2 tier marketed by
 * OEMs (4/6/8/12/16/24/32 GB). Reserved memory (modem, GPU, secure
 * world) explains the ~1 GB gap between MemTotal and the marketing
 * number — for example 15504 MB kernel-visible ≈ 16 GB advertised.
 *
 * Without this users see "14 GB" on a "16 GB" device and assume the
 * tool is broken.
 */
private fun advertisedGb(totalMb: Long): Int {
    val gb = totalMb / 1024.0
    val tiers = intArrayOf(2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64)
    // Round UP to the next tier so a 15.14 GB read becomes 16, not 12.
    return tiers.firstOrNull { it >= gb - 0.5 } ?: gb.toInt()
}
