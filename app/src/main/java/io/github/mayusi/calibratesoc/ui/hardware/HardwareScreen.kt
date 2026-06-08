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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.hardware.BatteryInfo
import io.github.mayusi.calibratesoc.data.hardware.Confidence
import io.github.mayusi.calibratesoc.data.hardware.DisplayInfo
import io.github.mayusi.calibratesoc.data.hardware.HardwareReport
import io.github.mayusi.calibratesoc.data.hardware.MemoryInfo
import io.github.mayusi.calibratesoc.data.hardware.NetworkTestResult
import io.github.mayusi.calibratesoc.data.hardware.RadioInfo
import io.github.mayusi.calibratesoc.data.hardware.SocInfo
import io.github.mayusi.calibratesoc.data.hardware.StorageVolume

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

    val r = report
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Hardware", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Identify what's in your device + speed-test storage, memory, and network. Different from Benchmark — which stress-tests sustained CPU/GPU throughput.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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

// --- Cards --------------------------------------------------------

@Composable
private fun SocCard(soc: SocInfo) = SectionCard("SoC", "The chip, its GPU, and core layout.") {
    KV("Model", "${soc.friendlyName}  ", soc.confidence)
    KV("Vendor codename", "${soc.manufacturer} ${soc.model}")
    soc.gpuName?.let { KV("GPU", it) }
    KV("Cores", soc.coreCount.toString())
}

@Composable
private fun MemoryCard(memory: MemoryInfo, running: Boolean, error: String?, onRun: () -> Unit) =
    SectionCard("Memory", "RAM type and measured bandwidth.") {
        KV("Total", "${advertisedGb(memory.totalMb)} GB (${memory.totalMb} MB kernel-visible)")
        KV("Available", "%.1f GB".format(memory.availableMb / 1024f))
        KV("Type (inferred)", memory.inferredType, memory.inferredConfidence)
        if (memory.measuredBandwidthMBps != null) {
            KV("Measured bandwidth", "%.1f MB/s".format(memory.measuredBandwidthMBps))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun, enabled = !running) {
                Text(if (running) "Testing…" else "Measure RAM bandwidth")
            }
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        TestError("RAM bandwidth test failed", error)
    }

@Composable
private fun StorageCard(volume: StorageVolume, running: Boolean, error: String?, onRun: () -> Unit, isPrimary: Boolean) =
    SectionCard("Storage — ${volume.label}", "Storage class and real read/write speeds.") {
        KV("Capacity", "%.1f GB total · %.1f GB free".format(volume.totalGb, volume.freeGb))
        KV("Class (inferred)", volume.inferredClass, volume.inferredConfidence)
        volume.vendorModel?.let { KV("Vendor model", it) }
        if (volume.seqReadMBps != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            KV("Sequential read", "%.0f MB/s".format(volume.seqReadMBps))
            KV("Sequential write", "%.0f MB/s".format(volume.seqWriteMBps ?: 0.0))
            KV("Random 4K read", "${volume.randomReadIOPS ?: 0} IOPS")
            KV("Random 4K write", "${volume.randomWriteIOPS ?: 0} IOPS")
        }
        if (isPrimary) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun, enabled = !running) {
                    Text(if (running) "Testing…" else "Run storage test")
                }
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(start = 4.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Text(
                "Writes ~512 MB to internal storage during the test, deleted immediately after. Negligible wear.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TestError("Storage test failed", error)
        }
    }

@Composable
private fun NetworkCard(running: Boolean, result: NetworkTestResult?, error: String?, onRun: () -> Unit) =
    SectionCard("Network", "Real-world download, upload, and latency.") {
        if (result != null) {
            result.downloadMbps?.let { KV("Download", "%.1f Mbps".format(it)) }
            result.uploadMbps?.let { KV("Upload", "%.1f Mbps".format(it)) }
            result.latencyCloudflareMs?.let { KV("Latency · Cloudflare", "$it ms") }
            result.latencyGoogleMs?.let { KV("Latency · Google", "$it ms") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun, enabled = !running) {
                Text(if (running) "Testing…" else "Run network test")
            }
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            "Downloads 50 MB + uploads 25 MB to speed.cloudflare.com. ~15s on a 5G connection, longer on slower links.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TestError("Network test failed", error)
    }

@Composable
private fun DisplayCard(d: DisplayInfo) = SectionCard("Display", "Panel resolution, refresh rates, and HDR.") {
    KV("Resolution", "${d.widthPx} × ${d.heightPx}")
    KV("Density", "${d.densityDpi} dpi")
    KV("Refresh", "%.0f Hz".format(d.refreshHz))
    if (d.supportedRefreshHz.size > 1) {
        KV("Supported", d.supportedRefreshHz.joinToString(", ") { "%.0f".format(it) } + " Hz")
    }
    KV("HDR", if (d.hdrSupported) "yes" else "no")
}

@Composable
private fun BatteryCard(b: BatteryInfo) = SectionCard("Battery", "Capacity, health, and live draw.") {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RadioCard(r: RadioInfo) = SectionCard("Radios", "Wireless connectivity and positioning.") {
    KV("Wi-Fi", r.wifiStandard)
    KV("Bluetooth", r.bluetoothVersion)
    KV("NFC", if (r.nfcPresent) "present" else "not present")
    if (r.gpsConstellations.isNotEmpty()) {
        KV("GPS", r.gpsConstellations.joinToString(", "))
    }
}

// --- Shared helpers ----------------------------------------------

/**
 * Local section card that wraps the shared [SectionCard] and adds an
 * optional one-line explainer under the title (onSurfaceVariant /
 * labelSmall), matching the benchmark cards' "what is this" subtitle.
 */
@Composable
private fun SectionCard(title: String, explainer: String? = null, content: @Composable () -> Unit) {
    io.github.mayusi.calibratesoc.ui.components.SectionCard(title) {
        if (explainer != null) {
            Text(
                explainer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun KV(label: String, value: String, confidence: Confidence? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.padding(end = 6.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        confidence?.let { ConfidenceChip(it) }
    }
}

@Composable
private fun ConfidenceChip(c: Confidence) {
    val (label, color) = when (c) {
        Confidence.HIGH -> "verified" to MaterialTheme.colorScheme.tertiary
        Confidence.MEDIUM -> "inferred" to MaterialTheme.colorScheme.secondary
        Confidence.LOW -> "guess" to MaterialTheme.colorScheme.outline
        Confidence.UNKNOWN -> "unknown" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
    )
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
