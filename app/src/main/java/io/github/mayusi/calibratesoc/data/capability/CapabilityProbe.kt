package io.github.mayusi.calibratesoc.data.capability

import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the probes into a single [CapabilityReport] and caches
 * it as a [StateFlow] so every consumer (Dashboard, Tune UI, Profile
 * engine) reads from one place. The report is re-computed on demand.
 *
 * Privilege-tier selection — order matters:
 *   1. ROOT only when (a) root is present AND (b) the user opted into
 *      root mode in Settings. Off by default.
 *   2. AYN_SETTINGS when the AYN vendor app is present AND we already
 *      hold WRITE_SECURE_SETTINGS (granted once via adb / Shizuku).
 *      This is the headline experience on Odin handhelds: same
 *      controls as AYN's own Quick Settings tile, no root.
 *   3. SHIZUKU when Shizuku is bound + permission granted. Universal.
 *   4. NONE — read-only.
 *
 * GPU family upgrade: SoCDetector infers family from text strings, but
 * Odin 3's CQ8725S codename doesn't match any of our heuristic
 * substrings. We override UNKNOWN → ADRENO when /sys/class/kgsl/
 * exists, since that's a Qualcomm-only kernel surface. Same logic
 * exists for Mali. The text-based detector remains the fast path.
 */
@Singleton
class CapabilityProbe @Inject constructor(
    private val socDetector: SoCDetector,
    private val sysfsProber: SysfsProber,
    private val rootProbe: RootProbe,
    private val shizukuProbe: ShizukuProbe,
    private val settingsWriteProbe: SettingsWriteProbe,
    private val vendorAppDetector: VendorAppDetector,
    private val fileSystem: FileSystem,
    private val userPrefs: UserPrefs,
    private val advancedPermissionsScript: AdvancedPermissionsScript,
) {
    private val _report = MutableStateFlow<CapabilityReport?>(null)
    val report: StateFlow<CapabilityReport?> = _report.asStateFlow()

    suspend fun refresh(): CapabilityReport = withContext(Dispatchers.IO) {
        val (device, rawSoc) = socDetector.detect()
        val soc = upgradeFamilyByPathPresence(rawSoc)

        val (hasRoot, rootKind) = rootProbe.probe()
        val shizuku = shizukuProbe.probe()
        val cpuPolicies = sysfsProber.probeCpuPolicies()
        val gpu = sysfsProber.probeGpu(soc.gpuFamily)
        val thermal = sysfsProber.probeThermalZones()
        val fan = sysfsProber.probeGenericPwmFan()
        val vendor = vendorAppDetector.detect()

        // Extended kernel-manager probes.
        val cpuGovernorTunables = sysfsProber.probeCpuGovernorTunables(cpuPolicies)
        val cpuTimeInState = sysfsProber.probeCpuTimeInState(cpuPolicies)
        val adrenoExtras = sysfsProber.probeAdrenoExtras(gpu)
        val gpuGovernorTunables = sysfsProber.probeGpuGovernorTunables(gpu)
        val thermalExtras = sysfsProber.probeThermalExtras(thermal)
        val coolingDevices = sysfsProber.probeCoolingDevices()
        val devfreqDevices = sysfsProber.probeDevfreqDevices()
        val blockDevices = sysfsProber.probeBlockDevices()
        val vmSysctls = sysfsProber.probeVmSysctls()
        val schedIface = sysfsProber.probeSchedBoostInterface()
        val schedBoostValues = sysfsProber.probeSchedBoostValues(
            iface = schedIface,
            slices = listOf("top-app", "foreground", "background", "system-background"),
        )
        val inputBoost = sysfsProber.probeInputBoost()

        val rootOptIn = userPrefs.rootModeEnabledBlocking()
        val hasSecureSettings = settingsWriteProbe.hasWriteSecureSettings()

        // Unlock-script tier: the one-time script chmod 666'd cpufreq nodes
        // so the app can write them without root. Probe it on every refresh
        // so the UI lights up automatically after the user runs the script.
        val sysfsDirectlyWritable = advancedPermissionsScript.grantsCurrentlyHeld().sysfsWritable

        val tier = when {
            hasRoot && rootOptIn -> PrivilegeTier.ROOT
            // Any OEM game-assistant app (AYN/Odin, AYANEO, Retroid) +
            // WRITE_SECURE_SETTINGS unlocks vendor fan/perf preset
            // switching. They all share the fan_mode / performance_mode
            // Settings.System convention.
            vendor.anyVendorPerfApp && hasSecureSettings -> PrivilegeTier.AYN_SETTINGS
            shizuku.running && shizuku.permissionGranted -> PrivilegeTier.SHIZUKU
            else -> PrivilegeTier.NONE
        }

        CapabilityReport(
            device = device,
            soc = soc,
            privilege = tier,
            rootKind = rootKind,
            shizuku = shizuku,
            cpuPolicies = cpuPolicies,
            gpu = gpu,
            thermalZones = thermal,
            fan = fan,
            vendorApps = vendor,
            cpuGovernorTunables = cpuGovernorTunables,
            cpuTimeInState = cpuTimeInState,
            adrenoExtras = adrenoExtras,
            gpuGovernorTunables = gpuGovernorTunables,
            thermalExtras = thermalExtras,
            coolingDevices = coolingDevices,
            devfreqDevices = devfreqDevices,
            blockDevices = blockDevices,
            vmSysctls = vmSysctls,
            schedBoostInterface = schedIface,
            schedBoostValues = schedBoostValues,
            inputBoostPresent = inputBoost != null,
            inputBoost = inputBoost,
            sysfsDirectlyWritable = sysfsDirectlyWritable,
        ).also { _report.value = it }
    }

    /**
     * When the text-based detector returns UNKNOWN, check whether the
     * device's filesystem exposes a known vendor-specific GPU control
     * surface. This catches Snapdragons whose SoC model string is the
     * marketing codename (Odin 3 reports `CQ8725S`, no `snapdragon` /
     * `sm8` substring). Same idea for Mali (`/sys/class/devfreq/`).
     */
    private fun upgradeFamilyByPathPresence(soc: SoCIdentity): SoCIdentity {
        if (soc.gpuFamily != GpuFamily.UNKNOWN) return soc
        val adrenoPresent = fileSystem.exists("/sys/class/kgsl/kgsl-3d0".toPath())
        if (adrenoPresent) return soc.copy(gpuFamily = GpuFamily.ADRENO)
        val maliPresent = runCatching {
            val root = "/sys/class/devfreq".toPath()
            fileSystem.exists(root) && fileSystem.list(root).any {
                it.name.contains("mali", ignoreCase = true)
            }
        }.getOrDefault(false)
        if (maliPresent) return soc.copy(gpuFamily = GpuFamily.MALI)
        return soc
    }
}
