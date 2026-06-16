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
    // --- Extended kernel-manager knobs (added in v0.1.11 foundation) ---------
    /** Per-policy governor tunables discovered dynamically from sysfs dirs. */
    val cpuGovernorTunables: List<CpuGovernorTunablesProbe> = emptyList(),
    /** time_in_state entries per CPU policy (read-only monitoring). */
    val cpuTimeInState: List<CpuTimeInStateProbe> = emptyList(),
    /** Adreno-specific extra power-level data: per-level freq map + throttling flag. */
    val adrenoExtras: AdrenoExtrasProbe? = null,
    /** GPU devfreq governor tunables (e.g. msm-adreno-tz: upthreshold, polling_interval). */
    val gpuGovernorTunables: List<GpuGovernorTunableProbe> = emptyList(),
    /** Thermal zone extra data: mode + trip points. */
    val thermalExtras: List<ThermalZoneExtras> = emptyList(),
    /** Cooling devices present on this device. */
    val coolingDevices: List<CoolingDeviceProbe> = emptyList(),
    /** Bus / DDR devfreq devices (qcom,cpubw / llccbw / etc.). */
    val devfreqDevices: List<DevfreqDeviceProbe> = emptyList(),
    /** Block devices present for I/O scheduler tuning. */
    val blockDevices: List<BlockDeviceProbe> = emptyList(),
    /** VM sysctl current values. Null when /proc/sys/vm is inaccessible. */
    val vmSysctls: VmSysctlsProbe? = null,
    /** Whether schedtune (/dev/stune) or uclamp (/dev/cpuctl) interface is present. */
    val schedBoostInterface: SchedBoostInterface = SchedBoostInterface.NONE,
    /** SchedTune/uclamp current values per slice. */
    val schedBoostValues: List<SchedBoostProbe> = emptyList(),
    /** Whether cpu_boost module is present. */
    val inputBoostPresent: Boolean = false,
    /** Current input boost parameters (null when module absent). */
    val inputBoost: InputBoostProbe? = null,
    /**
     * True when the one-time unlock script has been run AND the cpufreq nodes
     * are chmod 666 (app-UID-writable without root). Derived at probe time by
     * [AdvancedPermissionsScript.grantsCurrentlyHeld().sysfsWritable].
     *
     * When true, [Tunables.whyWriteDenied] returns null for any sysfs node that
     * the unlock script actually chmod'd, routing those writes to
     * [UnlockedFileWriter] instead of [NoopWriter].
     */
    val sysfsDirectlyWritable: Boolean = false,
    /**
     * True when AYN's PServerBinder is present AND our package is in its
     * `app_whiteList` (i.e. transact() succeeded on the probe no-op).
     *
     * When true, [WriterRegistry] routes SYSFS tunables on AYN/Odin devices
     * to [PServerWriter] instead of [UnlockedFileWriter]/[NoopWriter].
     * PServer runs as root, so no per-boot chmod is needed — this is the
     * best live-write tier available on non-rooted AYN handhelds.
     *
     * Set by [CapabilityProbe] via [PServerWriter.isTransactable()]. The result
     * is memoised for the session; false before the whitelist step, true after.
     *
     * HONESTY: This field is only true when a REAL transact round-trip
     * confirmed PServer ran our command. `binder != null` is NOT sufficient
     * (the Odin 3 registers the service even when it blocks our UID).
     */
    val pserverSysfsLive: Boolean = false,
    /**
     * True when AYANEO's vendor perf binder is LIVE: `com.ayaneo.gamewindow`'s exported
     * `AyaAidlService` is installed AND a real bind succeeded (verified live on the
     * AYANEO Pocket DS). When true, [WriterRegistry] routes the bindable SYSFS tunables
     * (CPU cluster scaling_max_freq / scaling_governor, GPU devfreq max_freq, fan) to
     * [io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter].
     *
     * This is the AYANEO analog of [pserverSysfsLive] — a ZERO-SETUP live-write tier
     * (no root, no Shizuku, no unlock script): the overlay (uid=system) actuates the
     * privileged sysfs write on the app's behalf.
     *
     * Set by [CapabilityProbe] via
     * [io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient.isAvailable].
     * Memoised for the session; false on non-AYANEO devices (gamewindow absent → no IPC).
     *
     * HONESTY: only true when a REAL bind round-trip confirmed the service is bindable.
     * "package installed" alone is NOT sufficient.
     *
     * NOTE: the binder cannot drive core parking (cpu/online); it drives the CPU cluster
     * CAP, governor, GPU max, and fan. AutoTDP runs LIVE on the cap path — the engine
     * simply never uses the park lever on this device (it skips to the cap lever).
     */
    val ayaneoBinderLive: Boolean = false,
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
    /** Any vendor handheld (AYN/Odin, AYANEO, Retroid, …) whose perf
     *  companion app is present AND we hold WRITE_SECURE_SETTINGS
     *  (granted once via `adb shell pm grant`). Unlocks the vendor's
     *  performance_mode / fan_mode preset flips via Settings.System keys
     *  — the same surface the vendor's own Quick Settings tile uses.
     *  No root, no Magisk.
     *
     *  HONESTY: this tier means "the vendor preset keys are writable",
     *  NOT "live cpufreq tuning is available". On some vendors (e.g.
     *  AYANEO, whose fan/perf ride a private binder rather than these
     *  Settings keys) a key write succeeds but moves no kernel node — so
     *  this tier is NEVER assumed to be a live cpufreq write path. The
     *  live-write decision goes through [WriterRegistry.isLiveWritable]
     *  against the actual sysfs node, which returns NoopWriter for this
     *  tier on a SYSFS cap. The headline "useful out of the box" tier on
     *  Odin 2/3 / Thor and Retroid, where the vendor subscribes to the keys. */
    VENDOR_SETTINGS,
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

// =============================================================================
// Extended kernel-manager probe data classes (added in v0.1.11 foundation)
// =============================================================================

/**
 * Tunables discovered in the per-governor sub-directory of a CPU policy.
 * Each [tunables] entry is (name → currentValue). The UI should render
 * each as a RAW_STRING knob; metadata lookup via [TunableMetadata] refines
 * to INT_RANGE or BOOL when the tunable is well-known.
 */
@Serializable
data class CpuGovernorTunablesProbe(
    val policyId: Int,
    val governor: String,
    /** Map of tunable filename → current string value. */
    val tunables: Map<String, String>,
)

/** One freq–jiffies pair from time_in_state (read-only). */
@Serializable
data class CpuTimeInStateEntry(val freqKhz: Int, val jiffies: Long)

@Serializable
data class CpuTimeInStateProbe(
    val policyId: Int,
    val entries: List<CpuTimeInStateEntry>,
)

/**
 * Adreno-specific extras beyond what [GpuProbe] already holds:
 *   - per-level freq map (index → Hz)
 *   - current throttling toggle state
 *   - force_clk_on / idle_timer values
 */
@Serializable
data class AdrenoExtrasProbe(
    /** Maps power level index (0 = fastest) → frequency in Hz. May be empty
     *  when devfreq/available_frequencies can't be correlated to levels. */
    val pwrLevelFreqHz: Map<Int, Long>,
    val currentMinPwrLevel: Int?,
    val currentMaxPwrLevel: Int?,
    val currentDefaultPwrLevel: Int?,
    val throttlingEnabled: Boolean?,
    val forceClkOn: Boolean?,
    val idleTimerMs: Int?,
)

/** One GPU devfreq governor tunable (name → currentValue). */
@Serializable
data class GpuGovernorTunableProbe(
    val governor: String,
    val name: String,
    val currentValue: String,
)

/**
 * Extra thermal-zone data: writable mode + trip points.
 * Extends [ThermalZoneProbe] (existing) without breaking its serialization.
 */
@Serializable
data class ThermalTripPoint(
    val index: Int,
    val tempMilliC: Int,
    val type: String,
)

@Serializable
data class ThermalZoneExtras(
    val zoneId: Int,
    /** "enabled" / "disabled". Null when the mode file is absent. */
    val mode: String?,
    val tripPoints: List<ThermalTripPoint>,
)

/** A /sys/class/thermal/cooling_deviceN — max_state is read-only; cur_state is writable. */
@Serializable
data class CoolingDeviceProbe(
    val id: Int,
    val type: String,
    val maxState: Int,
    val currentState: Int,
)

/** A /sys/class/devfreq/ device (bus, DDR, etc.) with its current capabilities. */
@Serializable
data class DevfreqDeviceProbe(
    /** Device name as it appears in /sys/class/devfreq/. E.g. "qcom,cpubw". */
    val deviceName: String,
    val curFreqHz: Long,
    val minFreqHz: Long,
    val maxFreqHz: Long,
    val currentGovernor: String,
    val availableGovernors: List<String>,
)

/** A /sys/block/ device with queue scheduler info. */
@Serializable
data class BlockDeviceProbe(
    /** E.g. "sda", "mmcblk0". */
    val deviceName: String,
    /** Full scheduler string from the kernel, brackets included. E.g. "none [mq-deadline] kyber". */
    val schedulerRaw: String,
    val currentScheduler: String,
    val availableSchedulers: List<String>,
    val readAheadKb: Int,
    val nrRequests: Int,
)

/** Current /proc/sys/vm sysctl values. */
@Serializable
data class VmSysctlsProbe(
    val swappiness: Int?,
    val vfsCachePressure: Int?,
    val dirtyRatio: Int?,
    val dirtyBackgroundRatio: Int?,
)

/** Which cgroup-boost interface the kernel exposes. Mutually exclusive. */
@Serializable
enum class SchedBoostInterface {
    /** /dev/stune/{slice}/schedtune.boost — older kernels. */
    STUNE,
    /** /dev/cpuctl/{slice}/cpu.uclamp.{min,max} — newer kernels. */
    UCLAMP,
    /** Neither interface found. */
    NONE,
}

/** Current schedtune.boost or uclamp values for one cgroup slice. */
@Serializable
data class SchedBoostProbe(
    val slice: String,
    /** schedtune.boost (0–100) when interface == STUNE; cpu.uclamp.min when UCLAMP. */
    val boostOrUclampMin: Int?,
    /** schedtune.prefer_idle (0/1) when STUNE; cpu.uclamp.max when UCLAMP. Null when absent. */
    val preferIdleOrUclampMax: Int?,
)

/** Current input_boost_freq / input_boost_ms values. */
@Serializable
data class InputBoostProbe(
    /** Raw value string (may be "0:1209600 4:0 7:0" format). */
    val inputBoostFreqRaw: String?,
    val inputBoostMs: Int?,
)
