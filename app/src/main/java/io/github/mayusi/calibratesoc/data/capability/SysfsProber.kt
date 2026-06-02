package io.github.mayusi.calibratesoc.data.capability

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads kernel sysfs and /proc to enumerate tunable surfaces. The
 * [FileSystem] indirection lets unit tests inject Okio FakeFileSystem so we
 * can exercise the parsers without touching the host kernel.
 *
 * Important: every method here only READS. Capability detection never
 * writes; the write-probe lives in [ShizukuProbe] and uses a known
 * write-safe target (re-writing the current value of one CPU policy's
 * scaling_min_freq), separately from this read-only enumerator.
 *
 * Failure mode: when a path is missing or unreadable (SELinux denial,
 * stripped-down kernel, emulator) we degrade silently to null/empty and
 * the upstream report reflects the absence honestly. We do not throw.
 */
@Singleton
class SysfsProber @Inject constructor(
    private val fs: FileSystem,
) {

    // --- CPU policies ----------------------------------------------------

    /**
     * Modern GKI kernels expose per-policy directories under
     * /sys/devices/system/cpu/cpufreq/policy{N}/. Older kernels still
     * have per-cpu cpufreq/ directories which we fall back to.
     */
    fun probeCpuPolicies(): List<CpuPolicyProbe> {
        val policyRoot = "/sys/devices/system/cpu/cpufreq".toPath()
        val policies = listOrEmpty(policyRoot)
            .filter { it.name.startsWith("policy") }
            .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }

        if (policies.isNotEmpty()) {
            return policies.mapNotNull { dir -> readPolicy(dir) }
        }

        // Pre-GKI fallback: enumerate /sys/devices/system/cpu/cpu*/cpufreq/.
        // Group by `related_cpus` so a single cluster surfaces as one policy.
        return readLegacyPerCpuPolicies()
    }

    private fun readPolicy(policyDir: Path): CpuPolicyProbe? {
        val idStr = policyDir.name.removePrefix("policy")
        val id = idStr.toIntOrNull() ?: return null

        val onlineCores = parseRelatedCpus(policyDir / "related_cpus")
        val availableFreqs = parseSpaceSeparatedInts(policyDir / "scaling_available_frequencies")
        val availableGovernors = parseSpaceSeparatedStrings(policyDir / "scaling_available_governors")
        val governor = readStringOrNull(policyDir / "scaling_governor").orEmpty()
        val hwLow = readIntOrNull(policyDir / "cpuinfo_min_freq")
        val hwHigh = readIntOrNull(policyDir / "cpuinfo_max_freq")
        val hwRange = if (hwLow != null && hwHigh != null && hwLow <= hwHigh) {
            FreqRange(hwLow, hwHigh)
        } else null

        // Some firmwares (AYN Thor, observed live) SELinux-block reads of
        // scaling_min_freq / scaling_max_freq from our app's domain even
        // though scaling_available_frequencies IS readable — and the
        // block is inconsistent PER POLICY (policy0 denied, policy3/7
        // allowed). When the current-value read fails, fall back to the
        // OPP table's bounds so the Tune card / monitor still shows a
        // sane number instead of "0 MHz". The user can still apply
        // presets; only the live "current cap" display is affected.
        val oppFloor = availableFreqs.minOrNull() ?: hwRange?.lowKhz
        val oppCeil = availableFreqs.maxOrNull() ?: hwRange?.highKhz
        val minKhz = readIntOrNull(policyDir / "scaling_min_freq") ?: oppFloor ?: 0
        val maxKhz = readIntOrNull(policyDir / "scaling_max_freq") ?: oppCeil ?: 0

        // A policy with zero available freqs is useless; skip rather than
        // misrepresent it.
        if (availableFreqs.isEmpty() && hwRange == null) return null

        return CpuPolicyProbe(
            policyId = id,
            onlineCores = onlineCores,
            availableFreqsKhz = availableFreqs,
            availableGovernors = availableGovernors,
            currentMinKhz = minKhz,
            currentMaxKhz = maxKhz,
            currentGovernor = governor,
            hardwareLimitsKhz = hwRange,
        )
    }

    private fun readLegacyPerCpuPolicies(): List<CpuPolicyProbe> {
        val cpuRoot = "/sys/devices/system/cpu".toPath()
        val cpuDirs = listOrEmpty(cpuRoot)
            .filter { it.name.matches(Regex("cpu\\d+")) }
            .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }

        val seenClusters = mutableSetOf<List<Int>>()
        val results = mutableListOf<CpuPolicyProbe>()

        for (cpu in cpuDirs) {
            val cpuFreq = cpu / "cpufreq"
            if (!fs.exists(cpuFreq)) continue
            val relatedFromFile = parseRelatedCpus(cpuFreq / "related_cpus")
            // If `related_cpus` is missing or empty, fall back to the cpuN
            // directory name itself. Pulled out of an .ifEmpty {} lambda
            // because `continue` is only legal in inline lambdas in Kotlin
            // 2.2+, and we target 2.1.
            val related = if (relatedFromFile.isNotEmpty()) {
                relatedFromFile
            } else {
                val cpuIndex = cpu.name.removePrefix("cpu").toIntOrNull() ?: continue
                listOf(cpuIndex)
            }
            if (!seenClusters.add(related)) continue

            val policyId = related.first()
            val freqs = parseSpaceSeparatedInts(cpuFreq / "scaling_available_frequencies")
            val govs = parseSpaceSeparatedStrings(cpuFreq / "scaling_available_governors")
            val min = readIntOrNull(cpuFreq / "scaling_min_freq") ?: 0
            val max = readIntOrNull(cpuFreq / "scaling_max_freq") ?: 0
            val gov = readStringOrNull(cpuFreq / "scaling_governor").orEmpty()
            val hwLow = readIntOrNull(cpuFreq / "cpuinfo_min_freq")
            val hwHigh = readIntOrNull(cpuFreq / "cpuinfo_max_freq")
            val hwRange = if (hwLow != null && hwHigh != null && hwLow <= hwHigh) {
                FreqRange(hwLow, hwHigh)
            } else null
            if (freqs.isEmpty() && hwRange == null) continue

            results += CpuPolicyProbe(
                policyId = policyId,
                onlineCores = related,
                availableFreqsKhz = freqs,
                availableGovernors = govs,
                currentMinKhz = min,
                currentMaxKhz = max,
                currentGovernor = gov,
                hardwareLimitsKhz = hwRange,
            )
        }
        return results
    }

    // --- GPU -------------------------------------------------------------

    /**
     * Adreno first (Snapdragon) → Mali devfreq second → MediaTek GED third.
     * Returns first that yields a usable freq list.
     */
    fun probeGpu(family: GpuFamily): GpuProbe? {
        return when (family) {
            GpuFamily.ADRENO -> probeAdreno() ?: probeMali() ?: probeMediatekGed()
            GpuFamily.MALI -> probeMali() ?: probeAdreno() ?: probeMediatekGed()
            GpuFamily.POWERVR_OR_MALI_MTK -> probeMediatekGed() ?: probeMali() ?: probeAdreno()
            GpuFamily.XCLIPSE, GpuFamily.UNKNOWN -> probeAdreno() ?: probeMali() ?: probeMediatekGed()
        }
    }

    private fun probeAdreno(): GpuProbe? {
        val root = "/sys/class/kgsl/kgsl-3d0".toPath()
        if (!fs.exists(root)) return null

        val devfreq = root / "devfreq"

        // Adreno exposes the frequency table in several places depending
        // on firmware. Try them in order:
        //   1. <root>/gpu_available_frequencies (Hz) — most kernels
        //   2. <root>/devfreq/available_frequencies (Hz) — AYN Thor lives
        //      HERE; the root-level file is absent / denied
        //   3. <root>/freq_table_mhz (MHz) — older builds
        val freqs = parseSpaceSeparatedLongs(root / "gpu_available_frequencies")
            .ifEmpty { parseSpaceSeparatedLongs(devfreq / "available_frequencies") }
            .ifEmpty {
                parseSpaceSeparatedInts(root / "freq_table_mhz")
                    .map { (it.toLong()) * 1_000_000L }
            }

        val availableGovs = parseSpaceSeparatedStrings(devfreq / "available_governors")
        val currentGov = readStringOrNull(devfreq / "governor").orEmpty()
        // min_freq / max_freq can be SELinux-denied per-file (Thor blocks
        // min_freq but allows max_freq). Fall back to the OPP table bounds
        // so the GPU card shows a sane min/max instead of 0.
        val oppMin = freqs.minOrNull() ?: 0L
        val oppMax = freqs.maxOrNull() ?: 0L
        val curMin = readLongOrNull(devfreq / "min_freq") ?: oppMin
        val curMax = readLongOrNull(devfreq / "max_freq") ?: oppMax

        val numLevels = readIntOrNull(root / "num_pwrlevels")
        val pwrRange = if (numLevels != null && numLevels > 0) {
            LevelRange(0, numLevels - 1)
        } else null

        if (freqs.isEmpty() && pwrRange == null) return null

        return GpuProbe(
            family = GpuFamily.ADRENO,
            rootPath = root.toString(),
            availableFreqsHz = freqs,
            availableGovernors = availableGovs,
            currentMinHz = curMin,
            currentMaxHz = curMax,
            currentGovernor = currentGov,
            powerLevelRange = pwrRange,
        )
    }

    private fun probeMali(): GpuProbe? {
        val devfreqRoot = "/sys/class/devfreq".toPath()
        val maliDir = listOrEmpty(devfreqRoot).firstOrNull { dir ->
            // Parens here are load-bearing: `&&` binds tighter than `||`, so
            // without them the uevent fallback only fires when the name
            // match fails AND the file happens to exist — easy to misread.
            dir.name.contains("mali", ignoreCase = true) ||
                (fs.exists(dir / "device" / "uevent") &&
                    runCatching {
                        fs.read(dir / "device" / "uevent") { readUtf8() }
                            .contains("mali", ignoreCase = true)
                    }.getOrDefault(false))
        } ?: return null

        val freqs = parseSpaceSeparatedLongs(maliDir / "available_frequencies")
        val govs = parseSpaceSeparatedStrings(maliDir / "available_governors")
        val curMin = readLongOrNull(maliDir / "min_freq") ?: 0L
        val curMax = readLongOrNull(maliDir / "max_freq") ?: 0L
        val curGov = readStringOrNull(maliDir / "governor").orEmpty()
        if (freqs.isEmpty()) return null

        return GpuProbe(
            family = GpuFamily.MALI,
            rootPath = maliDir.toString(),
            availableFreqsHz = freqs,
            availableGovernors = govs,
            currentMinHz = curMin,
            currentMaxHz = curMax,
            currentGovernor = curGov,
            powerLevelRange = null,
        )
    }

    private fun probeMediatekGed(): GpuProbe? {
        val root = "/proc/gpufreq".toPath()
        if (!fs.exists(root)) return null
        // MediaTek's GED interface is a mix of /proc files. We surface the
        // path; the writer module knows the per-kernel quirks.
        val freqs = parseSpaceSeparatedLongs(root / "gpufreq_opp_dump")
            .ifEmpty {
                parseSpaceSeparatedInts(root / "gpufreq_var_dump").map { it.toLong() }
            }
        return GpuProbe(
            family = GpuFamily.POWERVR_OR_MALI_MTK,
            rootPath = root.toString(),
            availableFreqsHz = freqs,
            availableGovernors = emptyList(),
            currentMinHz = 0L,
            currentMaxHz = 0L,
            currentGovernor = "",
            powerLevelRange = null,
        )
    }

    // --- Thermal ---------------------------------------------------------

    fun probeThermalZones(): List<ThermalZoneProbe> {
        val root = "/sys/class/thermal".toPath()
        return listOrEmpty(root)
            .filter { it.name.startsWith("thermal_zone") }
            .mapNotNull { zone ->
                val idStr = zone.name.removePrefix("thermal_zone")
                val id = idStr.toIntOrNull() ?: return@mapNotNull null
                val type = readStringOrNull(zone / "type").orEmpty()
                val temp = readIntOrNull(zone / "temp") ?: return@mapNotNull null
                ThermalZoneProbe(
                    id = id,
                    type = type,
                    currentTempMilliC = temp,
                    role = classifyZone(type),
                )
            }
            .sortedBy { it.id }
    }

    private fun classifyZone(type: String): ThermalRole {
        val t = type.lowercase()
        return when {
            "cpu" in t || "soc-cpu" in t || "cpu0" in t || "cpu-0-" in t -> ThermalRole.CPU
            "gpu" in t || "kgsl" in t || "mali" in t -> ThermalRole.GPU
            "batt" in t -> ThermalRole.BATTERY
            "skin" in t || "case" in t || "shell" in t -> ThermalRole.SKIN
            "modem" in t || "mdm" in t -> ThermalRole.MODEM
            "amb" in t -> ThermalRole.AMBIENT
            else -> ThermalRole.UNKNOWN
        }
    }

    // --- Fan -------------------------------------------------------------

    /**
     * Generic discovery only: looks for the first hwmon node that exposes a
     * `pwm1` file. Vendor-specific fans (AYN's Settings.System key,
     * AYANEO's AYASpace binder) are wired in by the device-db adapter, NOT
     * here — sysfs discovery is the universal fallback.
     */
    fun probeGenericPwmFan(): FanProbe? {
        val hwmonRoot = "/sys/class/hwmon".toPath()
        val node = listOrEmpty(hwmonRoot)
            .map { it.resolve("pwm1") }
            .firstOrNull { fs.exists(it) }
            ?: return null
        val rpm = node.parent?.let { readIntOrNull(it / "fan1_input") }
        return FanProbe(
            source = FanSource.HWMON_PWM,
            controlPath = node.toString(),
            supportsCurve = true,
            availablePresets = emptyList(),
            currentRpm = rpm,
        )
    }

    // --- Helpers ---------------------------------------------------------

    private fun listOrEmpty(p: Path): List<Path> = try {
        if (fs.exists(p)) fs.list(p) else emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    private fun readStringOrNull(p: Path): String? = try {
        if (!fs.exists(p)) null else fs.read(p) { readUtf8() }.trim().ifBlank { null }
    } catch (_: IOException) {
        null
    }

    private fun readIntOrNull(p: Path): Int? = readStringOrNull(p)?.toIntOrNull()
    private fun readLongOrNull(p: Path): Long? = readStringOrNull(p)?.toLongOrNull()

    private fun parseSpaceSeparatedInts(p: Path): List<Int> =
        readStringOrNull(p)?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }?.distinct()?.sorted() ?: emptyList()

    private fun parseSpaceSeparatedLongs(p: Path): List<Long> =
        readStringOrNull(p)?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.distinct()?.sorted() ?: emptyList()

    private fun parseSpaceSeparatedStrings(p: Path): List<String> =
        readStringOrNull(p)?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()

    private fun parseRelatedCpus(p: Path): List<Int> =
        parseSpaceSeparatedInts(p).sorted()
}
