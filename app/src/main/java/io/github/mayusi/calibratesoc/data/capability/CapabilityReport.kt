package io.github.mayusi.calibratesoc.data.capability

import kotlinx.serialization.Serializable

/**
 * Snapshot of everything the app discovered about the device. Built once on
 * launch by [CapabilityProbe] and after each resume. Every other module
 * consumes this — the UI hides any control whose underlying tunable is
 * unreachable. There is no "try the write and see if it sticks" path:
 * if it's not in the report, the user never sees a knob for it.
 *
 * Serializable so we can cache it to disk and ship anonymised dumps to the
 * crowdsourced device DB (with PII fields stripped at upload time).
 */
@Serializable
data class CapabilityReport(
    val device: DeviceIdentity,
    val soc: SoCIdentity,
    val privilege: PrivilegeTier,
    val rootKind: RootKind,
    val shizuku: ShizukuStatus,
    val cpuPolicies: List<CpuPolicyProbe>,
    val gpu: GpuProbe?,
    val thermalZones: List<ThermalZoneProbe>,
    val fan: FanProbe?,
    val vendorApps: VendorAppPresence,
)

@Serializable
data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val hardware: String,
    val androidVersion: String,
    val sdkInt: Int,
    /** EmuTran-style coarse vendor bucket; null for unknown. */
    val knownHandheldKey: String?,
)

@Serializable
data class SoCIdentity(
    /** Build.SOC_MANUFACTURER on API 31+, else `ro.soc.manufacturer`. May be blank. */
    val socManufacturer: String,
    /** Build.SOC_MODEL on API 31+, else `ro.soc.model`. May be blank. */
    val socModel: String,
    /** Heuristic family classification used to pick GPU probe path. */
    val gpuFamily: GpuFamily,
)

@Serializable
enum class GpuFamily {
    /** Qualcomm Snapdragon — Adreno via /sys/class/kgsl/kgsl-3d0/. */
    ADRENO,
    /** MediaTek / Samsung / others using Mali — `/sys/class/devfreq/<addr>.mali/`. */
    MALI,
    /** Samsung Exynos 2400+ Xclipse — undocumented, no public path. */
    XCLIPSE,
    /** MediaTek with proprietary GED — /proc/gpufreq. */
    POWERVR_OR_MALI_MTK,
    UNKNOWN,
}

@Serializable
enum class PrivilegeTier {
    /** Root present (Magisk or KernelSU) AND user opted into root mode in
     *  Settings — full kernel writes. v1 makes this opt-in because most
     *  handheld users don't run Magisk, and the AYN-native paths below
     *  cover the common cases without it. */
    ROOT,
    /** Shizuku bound + permission granted — monitoring + Settings.System
     *  writes + Shizuku-shell sysfs writes (where the kernel allows them).
     *  Universal across vendors. */
    SHIZUKU,
    /** AYN handheld with vendor "game assistant" present AND we hold
     *  WRITE_SECURE_SETTINGS (granted once via `adb shell pm grant`).
     *  Unlocks performance_mode / fan_mode / fan_thermal_management_area
     *  flips — same surface AYN's own Quick Settings tile uses.
     *  No root, no Magisk. The headline "useful out of the box" tier
     *  on Odin 2/3 / Thor. */
    AYN_SETTINGS,
    /** Neither — monitor + benchmark + read-only. */
    NONE,
}

@Serializable
enum class RootKind { MAGISK, KERNELSU, OTHER, NONE }

@Serializable
data class ShizukuStatus(
    val installed: Boolean,
    val running: Boolean,
    val permissionGranted: Boolean,
    /** Result of the no-op sysfs write-probe. NULL when not probed (no Shizuku, or skipped). */
    val sysfsWriteAllowed: Boolean?,
)

@Serializable
data class CpuPolicyProbe(
    val policyId: Int,
    val onlineCores: List<Int>,
    val availableFreqsKhz: List<Int>,
    val availableGovernors: List<String>,
    val currentMinKhz: Int,
    val currentMaxKhz: Int,
    val currentGovernor: String,
    /** Hardware OPP-table limits in kHz: (lowest, highest). Null when not exposed. */
    val hardwareLimitsKhz: FreqRange?,
)

/** Serializable substitute for IntRange — kotlinx.serialization can't synthesize a serializer
 *  for kotlin.ranges.IntRange. Same closed-interval semantics. */
@Serializable
data class FreqRange(val lowKhz: Int, val highKhz: Int)

@Serializable
data class GpuProbe(
    val family: GpuFamily,
    /** Filesystem root from which freq/governor paths are derived. */
    val rootPath: String,
    val availableFreqsHz: List<Long>,
    val availableGovernors: List<String>,
    val currentMinHz: Long,
    val currentMaxHz: Long,
    val currentGovernor: String,
    /** Adreno-specific: power level range (0=fastest .. N=slowest). NULL on Mali. */
    val powerLevelRange: LevelRange?,
)

/** Serializable substitute for IntRange used for Adreno power levels (0..N). */
@Serializable
data class LevelRange(val low: Int, val high: Int)

@Serializable
data class ThermalZoneProbe(
    val id: Int,
    val type: String,
    val currentTempMilliC: Int,
    /** Heuristic classification from the `type` string. */
    val role: ThermalRole,
)

@Serializable
enum class ThermalRole { CPU, GPU, BATTERY, SKIN, MODEM, AMBIENT, UNKNOWN }

@Serializable
data class FanProbe(
    val source: FanSource,
    /** sysfs node we'll write to, OR the Settings.System key for vendor presets. */
    val controlPath: String,
    val supportsCurve: Boolean,
    val availablePresets: List<String>,
    val currentRpm: Int?,
)

@Serializable
enum class FanSource {
    /** hwmon PWM (e.g. `/sys/class/hwmon/hwmonN/pwm1`) — full curve support with root. */
    HWMON_PWM,
    /** Thermal cooling_device (binary on/off levels). */
    THERMAL_COOLING_DEVICE,
    /** Vendor preset via Settings.System key (AYN-style). */
    VENDOR_SETTINGS_KEY,
    /** Vendor service via intent/binder (AYANEO AYASpace). */
    VENDOR_SERVICE_INTENT,
    NONE,
}

@Serializable
data class VendorAppPresence(
    val aynGameAssistant: Boolean,
    val langerhansOdinTools: Boolean,
    val ayaSpace: Boolean,
    /** Retroid's com.rp.gameassistant (RP6 + later). Same role as the
     *  AYN/Odin game assistant — owns fan + perf presets. */
    val retroidGameAssistant: Boolean = false,
) {
    /** True when ANY vendor performance companion is present. Drives the
     *  vendor-controls privilege tier — the app can offer fan/perf
     *  preset switching via Settings.System keys regardless of WHICH
     *  OEM's app is installed, because they all use the same
     *  fan_mode / performance_mode convention. */
    val anyVendorPerfApp: Boolean
        get() = aynGameAssistant || ayaSpace || retroidGameAssistant
}
