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
    /**
     * Privileged-read fallback for nodes the app UID cannot read directly. Null in unit
     * tests (and on the no-PServer path) — when null we behave exactly as before (Okio
     * read only). Hilt always injects the real [PrivilegedSysfsReader]; the default keeps
     * the `SysfsProber(fs)` test-construction site compiling unchanged.
     *
     * Used ONLY for the two cross-device-broken node families (uclamp + GPU devfreq
     * min/max) where `adb shell cat` / PServer-root succeed but the app's own `open()`
     * gets EACCES. See [readNodeWithPrivilegedFallback] and [PrivilegedSysfsReader].
     */
    private val privilegedReader: PrivilegedSysfsReader? = null,
) {

    /**
     * Read a node, preferring the cheap direct Okio read and falling back to a PServer
     * root `cat` ONLY when the direct read returned null AND a privileged reader is
     * available. This is the cross-device fix for nodes that are app-UID-SELinux-denied
     * yet root-readable (Odin 3 uclamp + kgsl devfreq). When neither path yields a value
     * the result stays null (honest "unreadable").
     *
     * Scoped deliberately to the few probes that need it — we do NOT route every sysfs
     * read through PServer (that would add IPC to dozens of always-app-readable nodes and
     * mask genuine absences). Only [probeSchedBoostInterface]/uclamp and [probeAdreno]'s
     * devfreq bounds call this.
     */
    private fun readNodeWithPrivilegedFallback(p: Path): String? {
        readStringOrNull(p)?.let { return it }
        return privilegedReader?.catOrNull(p.toString())?.trim()?.ifBlank { null }
    }

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
        // Read the raw devfreq bounds FIRST (before the OPP fallback) so we can
        // tell whether the live min/max nodes are genuinely readable — this is what
        // lets the GPU devfreq lever populate on devices where the OPP table and
        // num_pwrlevels are denied but min_freq/max_freq are not (the Odin 3 case).
        //
        // CROSS-DEVICE FIX (Odin 3): on this device `cat /sys/class/kgsl/kgsl-3d0/
        // devfreq/min_freq` returns 160000000 from `adb shell` / PServer-root, but the
        // app's own open() is SELinux-denied (EACCES) — so the plain Okio read below
        // returns null and the whole devfreq envelope came back null at runtime. We read
        // these two bounds with the PServer privileged fallback so a root-readable node
        // populates the lever. On a device where min/max are unreadable even to PServer
        // (RP6), the fallback also returns null and we honestly keep the null.
        val rawMin = readLongWithPrivilegedFallback(devfreq / "min_freq")
        val rawMax = readLongWithPrivilegedFallback(devfreq / "max_freq")
        val oppMin = freqs.minOrNull() ?: 0L
        val oppMax = freqs.maxOrNull() ?: 0L
        val curMin = rawMin ?: oppMin
        val curMax = rawMax ?: oppMax

        val numLevels = readIntOrNull(root / "num_pwrlevels")
        val pwrRange = if (numLevels != null && numLevels > 0) {
            LevelRange(0, numLevels - 1)
        } else null

        // The kgsl root EXISTS (checked above). Return a probe when we have ANY usable
        // signal:
        //   - the OPP frequency table (best — feeds the discrete devfreq steps), OR
        //   - the power-level range (num_pwrlevels), OR
        //   - a readable live devfreq min/max range (rawMin < rawMax).
        // The last clause is the cross-device fix: on the Odin 3 the OPP table and
        // num_pwrlevels are SELinux-denied to the app, but devfreq/min_freq=160 MHz and
        // max_freq=1100 MHz ARE app-readable. Without this clause probeAdreno returned
        // null there, so gpuRootPath/gpuDevfreq* all came back null and the GPU devfreq
        // lever could never engage even though the nodes were readable.
        val hasLiveRange = rawMin != null && rawMax != null && rawMin < rawMax
        if (freqs.isEmpty() && pwrRange == null && !hasLiveRange) return null

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

    // --- Hot-path Wave-2 telemetry reads (cached device lists) ----------------

    /**
     * Cached cur_state file paths for the THERMAL-RELEVANT cooling devices, built once.
     * The Odin 3 has 39 cooling devices; enumerating them every 1 Hz tick would be
     * wasteful, so we cache the path list (same pattern as the thermal-zone cache) and
     * re-read only the cur_state files on the hot path. A 30 s TTL refreshes the list
     * occasionally.
     *
     * CRITICAL FILTER (cross-device bug fix): only CPU/GPU/SoC THERMAL THROTTLE devices
     * are cached here. Non-thermal cooling devices register in /sys/class/thermal too —
     * the screen backlight (Odin `panel0-backlight` cur=262/max=262), the battery-charge
     * limiter, audio (`wsa*`), LEDs, etc. — and many sit pinned at a non-zero cur_state
     * during normal use. Counting THOSE as "the kernel is throttling NOW" made the
     * engine's thermal pre-empt arm 3 fire every tick at idle and tighten forever. We
     * include ONLY devices whose `type` is a genuine perf throttle (see
     * [isThermalThrottleCooling]); everything else is excluded.
     */
    @Volatile private var coolingCurStateCache: List<Path>? = null
    @Volatile private var coolingCurStateCacheBuiltAt: Long = 0L

    private fun coolingCurStatePaths(): List<Path> {
        val now = System.currentTimeMillis()
        val cached = coolingCurStateCache
        if (cached != null && (now - coolingCurStateCacheBuiltAt) < THERMAL_ZONE_CACHE_TTL_MS) {
            return cached
        }
        val root = "/sys/class/thermal".toPath()
        val fresh = listOrEmpty(root)
            .filter { it.name.startsWith("cooling_device") }
            // Read each device's `type` once and keep ONLY real CPU/GPU thermal
            // throttles. Non-thermal devices (backlight, battery, audio, LED, charger)
            // are dropped so a pinned-high backlight can never masquerade as throttling.
            .filter { dir -> isThermalThrottleCooling(readStringOrNull(dir / "type").orEmpty()) }
            .map { it / "cur_state" }
        coolingCurStateCache = fresh
        coolingCurStateCacheBuiltAt = now
        return fresh
    }

    /**
     * MAX cur_state across the THERMAL-RELEVANT cooling devices, read on the hot path.
     * A value > 0 means a CPU/GPU thermal throttle is actively engaged NOW — the AutoTDP
     * engine's thermal pre-empt arm 3 reacts to it. Returns null only when NO relevant
     * cooling device's cur_state could be read (so the engine falls back to die-temp
     * signals rather than treating an unreadable node as "not throttling").
     *
     * The device list is type-filtered in [coolingCurStatePaths] so non-thermal cooling
     * devices (backlight/battery/audio/LED/charger) can never inflate this — that was
     * the constant-tighten bug on both the Odin 3 and the RP6. cur_state is app-readable
     * on these devices (no root needed); when a future firmware SELinux-blocks it, the
     * per-file read returns null and we degrade honestly.
     */
    fun readMaxCoolingCurState(): Int? {
        val paths = coolingCurStatePaths()
        if (paths.isEmpty()) return null
        var max: Int? = null
        for (p in paths) {
            val v = readIntOrNull(p) ?: continue
            if (max == null || v > max!!) max = v
        }
        return max
    }

    /**
     * Classify a cooling_device `type` string as a genuine CPU/GPU thermal THROTTLE
     * (true) vs a non-thermal device that merely registers under /sys/class/thermal
     * (false). Only `true` types contribute to the "kernel throttling NOW" pre-empt
     * signal.
     *
     * INCLUDE — real perf throttles (LIVE Odin 3 / RP6 types):
     *   `cpu-hotplug*`, `thermal-cpufreq*`, `cpufreq*`, `cpu-isolate*`, `cpu-idle*`,
     *   `gpu*` (e.g. `thermal-devfreq-gpu`), `tsens*`, `soc*`, `mdss*`/`isp*` (SoC
     *   compute throttles), and anything containing `cpu`/`gpu`/`devfreq`/`thermal`.
     *
     * EXCLUDE — never a perf throttle, even when pinned high:
     *   `panel*`/`*backlight*` (the Odin `panel0-backlight` cur=262/max=262 bug),
     *   `battery`/`charge*`/`charger*` (charge-current limiters),
     *   `wsa*`/`*audio*`/`spk*` (speaker amps), `led*`, `*haptic*`/`vibrator*`,
     *   `*-blcd*`. Excludes win over includes (a `*backlight*` never counts).
     *
     * DEFAULT for an unknown/blank type: EXCLUDE (false). Honesty + safety: an
     * unrecognised device must not be allowed to force a permanent tighten. The
     * recognised throttle families above cover the real CPU/GPU throttles on every
     * device we have evidence for; a genuinely new throttle name can be added when
     * a device surfaces it.
     */
    internal fun isThermalThrottleCooling(type: String): Boolean {
        val t = type.lowercase().trim()
        if (t.isBlank()) return false

        // ── DENY first — a non-thermal device pinned high must never count. ──────
        val deny = listOf(
            "backlight", "panel", "blcd",
            "battery", "charge", "charger",
            "wsa", "audio", "spk", "speaker",
            "led", "haptic", "vibrator", "vib-",
        )
        if (deny.any { it in t }) return false

        // ── ALLOW (1): explicit KNOWN-THROTTLE prefixes — the strongest signal. ──
        // A cooling_device whose type STARTS with one of these is unambiguously a
        // CPU/GPU DVFS or core-isolation throttle. Prefixes avoid the broad-substring
        // false-positive class (e.g. a "thermal-..." backlight node, or a board name
        // that merely CONTAINS "soc"/"isp") that previously let a non-DVFS device count
        // as "throttling now".
        val knownPrefixes = listOf(
            "thermal-cpufreq-", "thermal-devfreq-", "thermal-cpu-", "thermal-gpu-",
            "cpufreq-", "devfreq-", "cpu-isolate", "cpu-isolat", "cpu-hotplug",
            "tsens", "cdsp", "cluster",
        )
        if (knownPrefixes.any { t.startsWith(it) }) return true

        // ── ALLOW (2): narrowed substrings for the genuine perf throttles that don't
        // share a stable prefix across vendors. We DROP the broad bare "thermal" and "isp"
        // substrings (which matched non-DVFS nodes — "thermal" matches nearly every cooling
        // device, including a backlight node that slipped the deny-list; "isp" is the image
        // signal processor, not a CPU/GPU perf throttle). We KEEP "soc" — "soc_cooling" is a
        // real SoC-level thermal throttle observed on Odin 3 / RP6. "cpu"/"gpu" still match
        // the per-core/per-rail throttle names every vendor uses (e.g. "cpu0-cpu-step",
        // "gpu-step", "mdss-gpu"). ──────────────────────────────────────────────────────
        val narrowedSubstrings = listOf(
            "cpufreq", "devfreq", "cpu-idle", "isolate", "hotplug",
            "cpu", "gpu", "mdss", "soc",
        )
        return narrowedSubstrings.any { it in t }
    }

    /**
     * GPU die temperature in milli-°C from the Adreno node (e.g.
     * /sys/class/kgsl/kgsl-3d0/temp), or null when no GPU root is known / the node is
     * unreadable / the reading is implausible. App-readable on the Odin 3 (observed value
     * 37800 = 37.8°C). Lets the engine relax the GPU before the skin sensor reacts.
     *
     * HIGH-3 — UNIT NORMALIZATION + SANITY BOUNDS. The kernel `temp` node has NO universal
     * unit: most expose milli-°C (37800 = 37.8°C), but some expose deci-°C (378 = 37.8°C),
     * whole-°C (38 = 38°C), or micro-°C (37_800_000). Without normalization a deci-°C device
     * reads 378 → /1000 = 0°C → the engine prefers this "0°C" die over the real skin zones
     * and the thermal pre-empt NEVER fires on a hot GPU; a micro-°C device reads 37_800_000
     * → 37800°C → PERMANENT pre-empt. We normalize to milli-°C and reject anything still
     * implausible (returns null → the engine falls back to skin zones, which are safe).
     *
     * @param gpuRootPath the GPU sysfs root from the [GpuProbe], or null.
     * @return die temperature in milli-°C within the plausible band, or null.
     */
    fun readGpuDieTempMilliC(gpuRootPath: String?): Int? {
        if (gpuRootPath == null) return null
        val raw = readIntOrNull("$gpuRootPath/temp".toPath()) ?: return null
        return normalizeDieTempMilliC(raw)
    }

    /**
     * Normalize a raw kernel `temp` reading to milli-°C, or null if implausible.
     *
     * Heuristic by magnitude (the bands don't overlap for realistic die temps ~20–130°C):
     *  - micro-°C (|raw| > 1_000_000): ÷1000 → milli-°C.
     *  - milli-°C (|raw| in [PLAUSIBLE_MILLI_MIN, PLAUSIBLE_MILLI_MAX]): pass through.
     *  - whole-°C (|raw| in [DIE_PLAUSIBLE_MIN_C, DIE_PLAUSIBLE_MAX_C], i.e. ~10..200): ×1000.
     *  - deci-°C (|raw| in [DIE_PLAUSIBLE_MIN_C*10, DIE_PLAUSIBLE_MAX_C*10], i.e. ~100..2000): ×100.
     *
     * After scaling, the result must land within the plausible milli-°C band or we return
     * null (a sensor we can't trust must never win over the skin zones).
     */
    internal fun normalizeDieTempMilliC(raw: Int): Int? {
        val a = kotlin.math.abs(raw.toLong())
        val milli: Long = when {
            a > 1_000_000L -> raw / 1000L                                  // micro-°C → milli-°C
            a in PLAUSIBLE_MILLI_MIN..PLAUSIBLE_MILLI_MAX -> raw.toLong()  // already milli-°C
            a in (DIE_PLAUSIBLE_MIN_C.toLong())..(DIE_PLAUSIBLE_MAX_C.toLong()) ->
                raw * 1000L                                                // whole-°C → milli-°C
            a in (DIE_PLAUSIBLE_MIN_C * 10L)..(DIE_PLAUSIBLE_MAX_C * 10L) ->
                raw * 100L                                                 // deci-°C → milli-°C
            else -> return null                                           // implausible
        }
        // Final guard: even after scaling, reject anything outside the plausible band so an
        // out-of-range/zero die read can NEVER win over the skin zones in the engine.
        return if (milli in PLAUSIBLE_MILLI_MIN..PLAUSIBLE_MILLI_MAX) milli.toInt() else null
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
        val uclampMin = "/dev/cpuctl".toPath() / "top-app" / "cpu.uclamp.min"
        return when {
            fs.exists(stuneRoot) -> SchedBoostInterface.STUNE
            // uclamp presence: detect by actually READING the well-known slice's
            // cpu.uclamp.min, NOT by fs.exists().
            //
            // CROSS-DEVICE BUG FIX: /dev/cpuctl is a cgroup mount and cpu.uclamp.min is
            // a cgroup pseudo-file. On the Odin 3 AND the RP6, `cat
            // /dev/cpuctl/top-app/cpu.uclamp.min` returns "0.00" (the node EXISTS and is
            // app-readable), yet Okio's fs.exists() — which stats the path — reports it
            // absent on cgroupfs, so uclampAvailable came back false on both devices and
            // the UCLAMP lever could never engage. A successful read is the honest,
            // authoritative presence check: if we can read a value (even the float
            // "0.00"), the interface is genuinely usable.
            isUclampNodeReadable(uclampMin) -> SchedBoostInterface.UCLAMP
            else -> SchedBoostInterface.NONE
        }
    }

    /**
     * True when the uclamp.min node yields a readable value, accepting the kernel's
     * FLOAT format. cgroup `cpu.uclamp.min` is printed as a percentage with two
     * decimals (e.g. "0.00", "100.00") — NOT a bare integer — so an integer-only parse
     * would reject the live value and wrongly conclude the interface is absent. We
     * accept any non-blank numeric content (Int OR Double); a blank/parse-failed read
     * means the node is not genuinely present/readable.
     */
    private fun isUclampNodeReadable(uclampMinPath: Path): Boolean {
        // CROSS-DEVICE FIX (Odin 3): `cat /dev/cpuctl/top-app/cpu.uclamp.min` returns
        // "0.00" from `adb shell` / PServer-root, but the app's own open() is
        // SELinux-denied on cgroupfs → the direct Okio read returns null and uclamp came
        // back NONE at runtime. Read with the PServer privileged fallback so a
        // root-readable slice is honestly detected as present. When the node is unreadable
        // to BOTH the app and PServer the fallback returns null and we report absent.
        val raw = readNodeWithPrivilegedFallback(uclampMinPath) ?: return false
        // Accept "0", "0.00", "100.00", etc. Reject non-numeric noise.
        return raw.toIntOrNull() != null || raw.toDoubleOrNull() != null
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
                    // Do NOT gate on fs.exists(dir): /dev/cpuctl is cgroupfs where a
                    // stat can fail even when the node is readable (same root cause as
                    // probeSchedBoostInterface). Read the values directly; a slice with
                    // no readable cpu.uclamp.min is dropped honestly.
                    val dir = "/dev/cpuctl/$slice".toPath()
                    // cgroup cpu.uclamp.{min,max} are FLOAT percentages ("0.00".."100.00").
                    // readUclampPercentOrNull accepts the float and rounds to an Int %;
                    // an integer-only read would reject "0.00" and lose the value.
                    val uclampMin = readUclampPercentOrNull(dir / "cpu.uclamp.min")
                    val uclampMax = readUclampPercentOrNull(dir / "cpu.uclamp.max")
                    if (uclampMin == null && uclampMax == null) return@mapNotNull null
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
        // Read DIRECTLY rather than gating on fs.exists() first. On real devices some
        // pseudo-filesystems — notably cgroupfs at /dev/cpuctl (uclamp) — let an app
        // OPEN+READ a node while a stat()/exists() probe of the same path reports it
        // absent. Gating on exists() therefore made readable cgroup nodes (e.g.
        // cpu.uclamp.min == "0.00") look missing. Opening straight away and treating a
        // FileNotFoundException (a subtype of IOException) as "absent" is both more
        // robust here and equivalent on FakeFileSystem, where a missing path throws
        // FileNotFoundException too.
        fs.read(p) { readUtf8() }.trim().ifBlank { null }
    } catch (_: IOException) {
        null
    }

    private fun readIntOrNull(p: Path): Int? = readStringOrNull(p)?.toIntOrNull()
    private fun readLongOrNull(p: Path): Long? = readStringOrNull(p)?.toLongOrNull()

    /**
     * Long read with the PServer privileged fallback (see [readNodeWithPrivilegedFallback]).
     * Used for the GPU devfreq min/max bounds, which are root-readable but app-UID-denied
     * on the Odin 3. Falls back to plain Okio (and honest null) everywhere else.
     */
    private fun readLongWithPrivilegedFallback(p: Path): Long? =
        readNodeWithPrivilegedFallback(p)?.toLongOrNull()

    /**
     * Read a cgroup `cpu.uclamp.{min,max}` value, which the kernel prints as a FLOAT
     * percentage (e.g. "0.00", "37.50", "100.00") rather than a bare integer. Returns
     * the rounded integer percent, or null when the node is unreadable / non-numeric.
     * Accepts a plain integer too (defensive — some kernels/back-ports print "0").
     */
    private fun readUclampPercentOrNull(p: Path): Int? {
        // Uses the privileged fallback for the same reason as [isUclampNodeReadable]: the
        // cgroup uclamp slices are root-readable but app-UID-denied on the Odin 3. Keeps
        // schedBoostValues consistent with schedBoostInterface == UCLAMP (otherwise the
        // interface would report present but every per-slice value would be null).
        val raw = readNodeWithPrivilegedFallback(p) ?: return null
        raw.toIntOrNull()?.let { return it }
        return raw.toDoubleOrNull()?.let { Math.round(it).toInt() }
    }

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

        // ── HIGH-3: GPU die-temp plausibility bands (see normalizeDieTempMilliC) ──────
        // Band ~20–130 °C: a powered SoC GPU die sits above ambient; the kernel kill is
        // ~105–110 °C. Anything outside this band (after unit normalization) is rejected
        // so a bogus 0 °C / off-scale read can never win over the skin zones.
        /** Lowest plausible die temperature in whole °C. Below this a "die temp" is noise. */
        private const val DIE_PLAUSIBLE_MIN_C = 20
        /** Highest plausible die temperature in whole °C (kernel kill is ~105–110 °C). */
        private const val DIE_PLAUSIBLE_MAX_C = 130
        /** Plausible band lower bound in milli-°C (DIE_PLAUSIBLE_MIN_C * 1000 = 20 000). */
        private const val PLAUSIBLE_MILLI_MIN = DIE_PLAUSIBLE_MIN_C * 1000L
        /** Plausible band upper bound in milli-°C (DIE_PLAUSIBLE_MAX_C * 1000 = 130 000). */
        private const val PLAUSIBLE_MILLI_MAX = DIE_PLAUSIBLE_MAX_C * 1000L
    }
}
