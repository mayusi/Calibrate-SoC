package io.github.mayusi.calibratesoc.data.devicedb

import kotlinx.serialization.Serializable

/**
 * Per-device quirk record. Joins on the lowercase `knownHandheldKey` we
 * compute in [io.github.mayusi.calibratesoc.data.capability.SoCDetector].
 *
 * The whole point of this file is to encode the vendor-specific stuff that
 * generic sysfs probing CAN'T discover: which `Settings.System` key flips
 * AYN's performance mode, which AYANEO intent toggles the fan, which
 * thermal-zone label names a particular device's "SoC" sensor that the
 * vendor wires up under a weird non-standard name (e.g. "soc_max"
 * instead of "cpu-0-0"). Unknown devices fall back to pure sysfs
 * discovery, which works fine for clocking on most Snapdragon kernels
 * but loses fan + preset interop.
 */
@Serializable
data class DeviceAdapter(
    val key: String,
    val displayName: String,
    val vendorAppPackage: String?,
    val fanAdapter: FanAdapter?,
    val perfPresetAdapter: PerfPresetAdapter?,
    /** Optional override of which thermal-zone labels mean what — when
     *  the kernel uses non-obvious names ("Tboard", "TZ_MAX"). */
    val thermalLabelOverrides: Map<String, String> = emptyMap(),
    /** Vendor perf daemons to stop before any cpufreq write and restart
     *  after. Empty list = stock AOSP kernel, no daemon dance needed.
     *  Odin 3 ships three (perfd + vendor.perf-hal-1-0 + vendor.perf-hal-2-0);
     *  Odin 2 ships only perfd. */
    val perfDaemonsToStopOnWrite: List<String> = emptyList(),
    /** When true, the RootWriter does chmod 666 → write → chmod 444 on
     *  every cpufreq scaling_*_freq write to keep the value sticky
     *  against late-firing daemons. Matches TheOldTaylor's Odin 3
     *  scripts (https://github.com/TheOldTaylor/Odin3-CPU-Underclock). */
    val chmodLockCpuFreqWrites: Boolean = false,
    /** Curated "Community Tuned" presets shipped for this device. The
     *  Profile engine in Phase 4 turns these into selectable presets. */
    val communityPresets: List<CommunityPreset> = emptyList(),
    val notes: String? = null,
)

/**
 * A pre-tuned profile that ships in the device-DB. Sourced from community
 * (e.g. TheOldTaylor's Odin 3 scripts) with attribution. The user can
 * apply, clone-and-edit, or ignore.
 *
 * Only CPU caps in v1 — GPU + fan land in Phase 4 when the FanController /
 * GpuController writers wire up.
 */
@Serializable
data class CommunityPreset(
    val name: String,
    val description: String,
    val sourceUrl: String,
    val cpuPolicyMaxKhz: Map<String, Int> = emptyMap(),
)


@Serializable
data class FanAdapter(
    val kind: FanAdapterKind,
    /** When kind == SETTINGS_KEY: the Settings.System key name.
     *  When kind == HWMON_PWM: the explicit sysfs path override (if the
     *  generic prober misses it).
     *  When kind == SERVICE_INTENT: the action string.
     *  When kind == NONE: ignored. */
    val target: String,
    val supportsCurve: Boolean,
    val presets: List<String> = emptyList(),
)

@Serializable
enum class FanAdapterKind { SETTINGS_KEY, HWMON_PWM, SERVICE_INTENT, NONE }

@Serializable
data class PerfPresetAdapter(
    val kind: PerfAdapterKind,
    /** Settings.System key (kind == SETTINGS_KEY) or intent action. */
    val target: String,
    val presets: List<PerfPresetMapping>,
)

@Serializable
enum class PerfAdapterKind { SETTINGS_KEY, SERVICE_INTENT }

@Serializable
data class PerfPresetMapping(
    val display: String,
    val value: String,
)
