package io.github.mayusi.calibratesoc.data.hardware

import kotlinx.serialization.Serializable

/**
 * Static + inferred hardware-identification snapshot. Distinct from
 * [io.github.mayusi.calibratesoc.data.capability.CapabilityReport]
 * which is about tunable surfaces (what we can write to). This is
 * "what hardware is in this device" — answers the user question:
 * "is my UFS 3.1 or 4.0, what RAM type do I have, what panel is in
 * here".
 *
 * Confidence:  HIGH = directly read from a public Android API.
 *              MEDIUM = read from /sys or system property + verified
 *                       against a per-SoC table.
 *              LOW = inferred from per-SoC mapping table only.
 *              UNKNOWN = couldn't determine. UI renders "—".
 */
@Serializable
data class HardwareReport(
    val soc: SocInfo,
    val memory: MemoryInfo,
    val storage: List<StorageVolume>,
    val display: DisplayInfo,
    val battery: BatteryInfo,
    val radios: RadioInfo,
)

@Serializable
data class SocInfo(
    val manufacturer: String,
    val model: String,
    val friendlyName: String,
    val confidence: Confidence,
    val coreCount: Int,
    val gpuName: String?,
)

@Serializable
data class MemoryInfo(
    val totalMb: Long,
    val availableMb: Long,
    val inferredType: String,          // "LPDDR5X" / "LPDDR4X" / "DDR4" / "—"
    val inferredConfidence: Confidence,
    /** MB/s measured via STREAM-triad. NULL until the user runs the
     *  test. */
    val measuredBandwidthMBps: Double? = null,
)

@Serializable
data class StorageVolume(
    val label: String,                // "Internal" or "SD card 1"
    val totalGb: Double,
    val freeGb: Double,
    val inferredClass: String,         // "UFS 4.0" / "UFS 3.1" / "eMMC" / "—"
    val inferredConfidence: Confidence,
    val vendorModel: String?,         // e.g. "KLUEG8U1EA-B0C1" — when readable
    /** Speed-test results (MB/s). NULL until the user runs the test. */
    val seqReadMBps: Double? = null,
    val seqWriteMBps: Double? = null,
    val randomReadIOPS: Int? = null,
    val randomWriteIOPS: Int? = null,
)

@Serializable
data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshHz: Float,
    val supportedRefreshHz: List<Float>,
    val hdrSupported: Boolean,
)

@Serializable
data class BatteryInfo(
    val designCapacityMah: Int?,
    val currentCapacityMah: Int?,
    val cycleCount: Int?,
    val healthPercent: Int?,
    val technology: String?,           // "Li-poly" etc
    /** Health status string from BatteryManager.EXTRA_HEALTH (API-sourced,
     *  available even when sysfs is SELinux-denied). E.g. "Good", "Overheat". */
    val healthStatus: String? = null,
    /** Design capacity source: true = read from sysfs, false = device-DB fallback. */
    val designCapacityFromSysfs: Boolean = true,
)

@Serializable
data class RadioInfo(
    val wifiStandard: String,         // "Wi-Fi 6E", "Wi-Fi 7", "Wi-Fi 5", "—"
    val bluetoothVersion: String,
    val nfcPresent: Boolean,
    val gpsConstellations: List<String>,
)

@Serializable
data class NetworkTestResult(
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val latencyCloudflareMs: Long?,
    val latencyGoogleMs: Long?,
    val measuredAtMs: Long,
)

@Serializable
enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }
