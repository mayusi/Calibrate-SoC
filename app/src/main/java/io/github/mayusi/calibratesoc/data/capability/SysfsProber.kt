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

    /**
     * Cached list of (tempPath, id, typeName, role) built once on first call.
     * Hot-added zones (e.g. USB-C dock insertion) will be missed until the
     * JVM process restarts — acceptable: zone topology is stable during a game
     * session and the old comment acknowledged re-enumeration was speculative.
     * A 30-second TTL refreshes the list occasionally in case of kernel quirks.
     */
    private data class ThermalZoneEntry(
        val id: Int,
        val typeName: String,
        val tempPath: Path,
        val role: ThermalRole,
    )

    @Volatile private var thermalZoneCache: List<ThermalZoneEntry>? = null
    @Volatile private var thermalZoneCacheBuiltAt: Long = 0L

    private fun buildThermalZoneCache(): List<ThermalZoneEntry> {
        val root = "/sys/class/thermal".toPath()
        return listOrEmpty(root)
            .filter { it.name.startsWith("thermal_zone") }
            .mapNotNull { zone ->
                val id = zone.name.removePrefix("thermal_zone").toIntOrNull() ?: return@mapNotNull null
                val type = readStringOrNull(zone / "type").orEmpty()
                ThermalZoneEntry(
                    id = id,
                    typeName = type,
                    tempPath = zone / "temp",
                    role = classifyZone(type),
                )
            }
            .sortedBy { it.id }
    }

    private fun thermalZones(): List<ThermalZoneEntry> {
        val now = System.currentTimeMillis()
        val cached = thermalZoneCache
        if (cached != null && (now - thermalZoneCacheBuiltAt) < THERMAL_ZONE_CACHE_TTL_MS) {
            return cached
        }
        val fresh = buildThermalZoneCache()
        thermalZoneCache = fresh
        thermalZoneCacheBuiltAt = now
        return fresh
    }

    /**
     * Full probe: enumerates zones, reads types AND current temps. Used for the
     * initial capability report and by [probeThermalExtras].
     */
    fun probeThermalZones(): List<ThermalZoneProbe> {
        return thermalZones().mapNotNull { entry ->
            val temp = readIntOrNull(entry.tempPath) ?: return@mapNotNull null
            ThermalZoneProbe(
                id = entry.id,
                type = entry.typeName,
                currentTempMilliC = temp,
                role = entry.role,
            )
        }
    }

    /**
     * Lightweight hot-path read: uses the cached zone list and only re-reads
     * the temperature files. Call this from [MonitorService]'s tick instead of
     * [probeThermalZones] to avoid re-enumerating the directory every sample.
     *
     * Zones whose temp file returns null (kernel removed the node mid-session)
     * are silently skipped — the caller sees a shorter list rather than a crash.
     */
    fun readThermalZoneTemps(): List<ThermalZoneProbe> {
        return thermalZones().mapNotNull { entry ->
            val temp = readIntOrNull(entry.tempPath) ?: return@mapNotNull null
            ThermalZoneProbe(
                id = entry.id,
                type = entry.typeName,
                currentTempMilliC = temp,
                role = entry.role,
            )
        }
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

    // --- CPU governor tunables (dynamic) -------------------------------------

    /**
     * For each CPU policy, reads the governor's sub-directory and returns
     * every file therein as a tunable. Governor sub-dirs exist on kernels
     * that expose per-governor parameter files (schedutil, interactive, etc.).
     *
     * We do NOT hardcode tunable names — we list the directory so new
     * tunables added by a custom kernel are discovered automatically.
     */
    fun probeCpuGovernorTunables(policies: List<CpuPolicyProbe>): List<CpuGovernorTunablesProbe> {
        val results = mutableListOf<CpuGovernorTunablesProbe>()
        for (policy in policies) {
            val governor = policy.currentGovernor
            if (governor.isBlank()) continue
            val govDir = "/sys/devices/system/cpu/cpufreq/policy${policy.policyId}/$governor".toPath()
            if (!fs.exists(govDir)) continue
            val tunableFiles = listOrEmpty(govDir).filter { it.name.isNotBlank() }
            if (tunableFiles.isEmpty()) continue
            val tunables = mutableMapOf<String, String>()
            for (file in tunableFiles) {
                val value = readStringOrNull(file)
                if (value != null) tunables[file.name] = value
            }
            if (tunables.isNotEmpty()) {
                results += CpuGovernorTunablesProbe(
                    policyId = policy.policyId,
                    governor = governor,
                    tunables = tunables,
                )
            }
        }
        return results
    }

    /**
     * Reads time_in_state for each CPU policy. Returns only policies
     * where the file is present and parseable.
     */
    fun probeCpuTimeInState(policies: List<CpuPolicyProbe>): List<CpuTimeInStateProbe> {
        return policies.mapNotNull { policy ->
            val path = "/sys/devices/system/cpu/cpufreq/policy${policy.policyId}/stats/time_in_state".toPath()
            val raw = readStringOrNull(path) ?: return@mapNotNull null
            val entries = raw.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 2) return@mapNotNull null
                    val freq = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val jiffies = parts[1].toLongOrNull() ?: return@mapNotNull null
                    CpuTimeInStateEntry(freqKhz = freq, jiffies = jiffies)
                }
            if (entries.isEmpty()) null
            else CpuTimeInStateProbe(policyId = policy.policyId, entries = entries)
        }
    }

    // --- Adreno extras -------------------------------------------------------

    /**
     * Reads the extra Adreno power-level data that goes beyond [GpuProbe]:
     * per-level freq map, throttling gate, force_clk_on, idle_timer.
     */
    fun probeAdrenoExtras(gpuProbe: GpuProbe?): AdrenoExtrasProbe? {
        if (gpuProbe == null || gpuProbe.family != GpuFamily.ADRENO) return null
        val root = gpuProbe.rootPath.toPath()

        // Build power-level → freq map by correlating the sorted freq list
        // with the devfreq/available_frequencies file (which is in ascending
        // Hz order, same ordering as level indices in DESCENDING freq, i.e.
        // level 0 = highest freq = last element when sorted ascending).
        val freqs = gpuProbe.availableFreqsHz // already sorted ascending by probeAdreno
        val numLevels = gpuProbe.powerLevelRange?.let { it.high - it.low + 1 } ?: 0
        val pwrLevelFreqHz: Map<Int, Long> = if (freqs.isNotEmpty() && numLevels > 0) {
            // Adreno: level 0 = max performance = highest freq (last in sorted list).
            // Map: index 0 → freqs.last(), index 1 → freqs[last-1], etc.
            val reversed = freqs.reversed()
            (0 until minOf(numLevels, freqs.size)).associate { levelIdx ->
                levelIdx to reversed[levelIdx]
            }
        } else {
            emptyMap()
        }

        val curMin = readIntOrNull(root / "min_pwrlevel")
        val curMax = readIntOrNull(root / "max_pwrlevel")
        val curDefault = readIntOrNull(root / "default_pwrlevel")
        val throttling = readIntOrNull(root / "throttling")?.let { it != 0 }
        val forceClkOn = readIntOrNull(root / "force_clk_on")?.let { it != 0 }
        val idleTimer = readIntOrNull(root / "idle_timer")

        return AdrenoExtrasProbe(
            pwrLevelFreqHz = pwrLevelFreqHz,
            currentMinPwrLevel = curMin,
            currentMaxPwrLevel = curMax,
            currentDefaultPwrLevel = curDefault,
            throttlingEnabled = throttling,
            forceClkOn = forceClkOn,
            idleTimerMs = idleTimer,
        )
    }

    /**
     * GPU devfreq governor tunables — same dynamic approach as CPU governor
     * tunables: list the governor sub-directory and treat each file as a
     * tunable.
     */
    fun probeGpuGovernorTunables(gpuProbe: GpuProbe?): List<GpuGovernorTunableProbe> {
        if (gpuProbe == null) return emptyList()
        val governor = gpuProbe.currentGovernor.ifBlank { return emptyList() }
        val govDir = "${gpuProbe.rootPath}/devfreq/$governor".toPath()
        if (!fs.exists(govDir)) return emptyList()
        return listOrEmpty(govDir).mapNotNull { file ->
            val value = readStringOrNull(file) ?: return@mapNotNull null
            GpuGovernorTunableProbe(governor = governor, name = file.name, currentValue = value)
        }
    }

    // --- Thermal extras (mode + trip points) ---------------------------------

    fun probeThermalExtras(zones: List<ThermalZoneProbe>): List<ThermalZoneExtras> {
        return zones.map { zone ->
            val zoneDir = "/sys/class/thermal/thermal_zone${zone.id}".toPath()
            val mode = readStringOrNull(zoneDir / "mode")
            val tripPoints = probeTripPoints(zoneDir)
            ThermalZoneExtras(zoneId = zone.id, mode = mode, tripPoints = tripPoints)
        }
    }

    private fun probeTripPoints(zoneDir: Path): List<ThermalTripPoint> {
        val results = mutableListOf<ThermalTripPoint>()
        // Enumerate trip_point_{N}_temp; N is usually 0..3 but can be higher.
        // We list the directory and look for matching names rather than
        // iterating 0..MAX_GUESS so we never miss any or iterate pointlessly.
        val tripTemps = listOrEmpty(zoneDir)
            .filter { it.name.matches(Regex("trip_point_\\d+_temp")) }
            .sortedBy { it.name.removePrefix("trip_point_").removeSuffix("_temp").toIntOrNull() ?: 0 }
        for (tempFile in tripTemps) {
            val index = tempFile.name.removePrefix("trip_point_").removeSuffix("_temp").toIntOrNull() ?: continue
            val tempMilliC = readIntOrNull(tempFile) ?: continue
            val typePath = zoneDir / "trip_point_${index}_type"
            val type = readStringOrNull(typePath).orEmpty()
            results += ThermalTripPoint(index = index, tempMilliC = tempMilliC, type = type)
        }
        return results
    }

    // --- Cooling devices -----------------------------------------------------

    fun probeCoolingDevices(): List<CoolingDeviceProbe> {
        val root = "/sys/class/thermal".toPath()
        return listOrEmpty(root)
            .filter { it.name.startsWith("cooling_device") }
            .sortedBy { it.name.removePrefix("cooling_device").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { dir ->
                val id = dir.name.removePrefix("cooling_device").toIntOrNull() ?: return@mapNotNull null
                val type = readStringOrNull(dir / "type").orEmpty()
                val maxState = readIntOrNull(dir / "max_state") ?: return@mapNotNull null
                val curState = readIntOrNull(dir / "cur_state") ?: return@mapNotNull null
                CoolingDeviceProbe(id = id, type = type, maxState = maxState, currentState = curState)
            }
    }

    // --- Bus / DDR devfreq ---------------------------------------------------

    /**
     * Enumerates /sys/class/devfreq/ to find bus/DDR devices. Skips the
     * GPU devfreq entry (already handled by [probeGpu]) — identified by
     * family name presence (kgsl/mali/gpu).
     */
    fun probeDevfreqDevices(): List<DevfreqDeviceProbe> {
        val root = "/sys/class/devfreq".toPath()
        return listOrEmpty(root)
            .filter { dir ->
                val name = dir.name.lowercase()
                // Exclude GPU devfreq entries — they're covered by probeGpu.
                !name.contains("kgsl") && !name.contains("mali") && !name.contains("gpu")
            }
            .sortedBy { it.name }
            .mapNotNull { dir ->
                val curFreq = readLongOrNull(dir / "cur_freq") ?: return@mapNotNull null
                val minFreq = readLongOrNull(dir / "min_freq") ?: 0L
                val maxFreq = readLongOrNull(dir / "max_freq") ?: 0L
                val governor = readStringOrNull(dir / "governor").orEmpty()
                val availableGovs = parseSpaceSeparatedStrings(dir / "available_governors")
                DevfreqDeviceProbe(
                    deviceName = dir.name,
                    curFreqHz = curFreq,
                    minFreqHz = minFreq,
                    maxFreqHz = maxFreq,
                    currentGovernor = governor,
                    availableGovernors = availableGovs,
                )
            }
    }

    // --- Block devices / I/O scheduler ---------------------------------------

    fun probeBlockDevices(): List<BlockDeviceProbe> {
        val root = "/sys/block".toPath()
        // Include common storage devices; exclude loop, dm (device-mapper),
        // and ram devices which don't have meaningful I/O scheduling.
        val blockDevicePattern = Regex("^(sd[a-z]+|mmcblk\\d+|nvme\\d+n\\d+)$")
        return listOrEmpty(root)
            .filter { it.name.matches(blockDevicePattern) }
            .sortedBy { it.name }
            .mapNotNull { dir ->
                val queueDir = dir / "queue"
                if (!fs.exists(queueDir)) return@mapNotNull null
                val schedulerRaw = readStringOrNull(queueDir / "scheduler").orEmpty()
                if (schedulerRaw.isBlank()) return@mapNotNull null
                val currentScheduler = schedulerRaw
                    .split(Regex("\\s+"))
                    .firstOrNull { it.startsWith("[") && it.endsWith("]") }
                    ?.removeSurrounding("[", "]")
                    .orEmpty()
                val availableSchedulers = schedulerRaw
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { it.removeSurrounding("[", "]") }
                val readAhead = readIntOrNull(queueDir / "read_ahead_kb") ?: 0
                val nrRequests = readIntOrNull(queueDir / "nr_requests") ?: 0
                BlockDeviceProbe(
                    deviceName = dir.name,
                    schedulerRaw = schedulerRaw,
                    currentScheduler = currentScheduler,
                    availableSchedulers = availableSchedulers,
                    readAheadKb = readAhead,
                    nrRequests = nrRequests,
                )
            }
    }

    // --- VM sysctls ----------------------------------------------------------

    fun probeVmSysctls(): VmSysctlsProbe? {
        val root = "/proc/sys/vm".toPath()
        if (!fs.exists(root)) return null
        return VmSysctlsProbe(
            swappiness = readIntOrNull(root / "swappiness"),
            vfsCachePressure = readIntOrNull(root / "vfs_cache_pressure"),
            dirtyRatio = readIntOrNull(root / "dirty_ratio"),
            dirtyBackgroundRatio = readIntOrNull(root / "dirty_background_ratio"),
        )
    }

    // --- SchedTune / uclamp --------------------------------------------------

    /**
     * Probe which boost interface exists — stune or uclamp — and read
     * current values for the well-known slices. Returns NONE when neither
     * is present.
     */
    fun probeSchedBoostInterface(): SchedBoostInterface {
        val stuneRoot = "/dev/stune".toPath()
        val uclampRoot = "/dev/cpuctl".toPath()
        return when {
            fs.exists(stuneRoot) -> SchedBoostInterface.STUNE
            // uclamp presence checked by looking for the cpu.uclamp.min file
            // in a well-known slice rather than just the cpuctl dir existing.
            fs.exists(uclampRoot / "top-app" / "cpu.uclamp.min") -> SchedBoostInterface.UCLAMP
            else -> SchedBoostInterface.NONE
        }
    }

    fun probeSchedBoostValues(
        iface: SchedBoostInterface,
        slices: List<String>,
    ): List<SchedBoostProbe> {
        if (iface == SchedBoostInterface.NONE) return emptyList()
        return slices.mapNotNull { slice ->
            when (iface) {
                SchedBoostInterface.STUNE -> {
                    val dir = "/dev/stune/$slice".toPath()
                    if (!fs.exists(dir)) return@mapNotNull null
                    val boost = readIntOrNull(dir / "schedtune.boost")
                    val preferIdle = readIntOrNull(dir / "schedtune.prefer_idle")
                    SchedBoostProbe(slice = slice, boostOrUclampMin = boost, preferIdleOrUclampMax = preferIdle)
                }
                SchedBoostInterface.UCLAMP -> {
                    val dir = "/dev/cpuctl/$slice".toPath()
                    if (!fs.exists(dir)) return@mapNotNull null
                    val uclampMin = readIntOrNull(dir / "cpu.uclamp.min")
                    val uclampMax = readIntOrNull(dir / "cpu.uclamp.max")
                    SchedBoostProbe(slice = slice, boostOrUclampMin = uclampMin, preferIdleOrUclampMax = uclampMax)
                }
                SchedBoostInterface.NONE -> null
            }
        }
    }

    // --- Input boost ---------------------------------------------------------

    fun probeInputBoost(): InputBoostProbe? {
        val paramsDir = "/sys/module/cpu_boost/parameters".toPath()
        if (!fs.exists(paramsDir)) return null
        val freqRaw = readStringOrNull(paramsDir / "input_boost_freq")
        val boostMs = readIntOrNull(paramsDir / "input_boost_ms")
        return InputBoostProbe(inputBoostFreqRaw = freqRaw, inputBoostMs = boostMs)
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

    // parseSpaceSeparatedInts already ends in .sorted(); no second sort needed.
    private fun parseRelatedCpus(p: Path): List<Int> =
        parseSpaceSeparatedInts(p)

    companion object {
        /** How long the thermal zone directory enumeration is cached (ms).
         *  30 s is long enough to avoid per-tick I/O; short enough to catch
         *  a dock insertion within the first sample after the user plugs in. */
        private const val THERMAL_ZONE_CACHE_TTL_MS = 30_000L
    }
}
