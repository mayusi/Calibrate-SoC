package io.github.mayusi.calibratesoc.data.tunables

/**
 * Safety brain for the extended kernel-manager tunable set.
 *
 * Given a [TunableId] (or a knob category via the static helpers), returns:
 *   - [TunableInfo.name] / [TunableInfo.description]  — human-readable
 *   - [TunableInfo.risk]        — [Risk] enum (SAFE…DANGEROUS)
 *   - [TunableInfo.valueKind]   — [ValueKind] (controls which UI widget to render)
 *   - [TunableInfo.validate]    — value validation function → error String or null
 *   - [TunableInfo.isReversibleSafely] — always true (TunableWriter snapshots before write);
 *     set false only for conceptually irreversible operations (none here)
 *
 * Resolution order:
 *   1. Exact path match (handles paths that need precise semantics)
 *   2. Path prefix / pattern match (handles dynamic tunable families)
 *   3. Fall through → [unknownSysfsTunable] with RAW_STRING + LOW risk
 *
 * Keep this pure (no Android, no IO) so it can be exercised in JVM unit tests.
 */
object TunableMetadata {

    // =========================================================================
    // Public API
    // =========================================================================

    /** Look up metadata for any [TunableId]. Never returns null. */
    fun forId(id: TunableId): TunableInfo = resolveByTarget(id.target)

    /**
     * Validate a user-supplied custom sysfs path BEFORE constructing a
     * [TunableId] for it. Returns an error message when invalid, null when
     * safe to proceed.
     *
     * Rules:
     *   1. Must start with /sys or /proc.
     *   2. Must not contain null bytes or shell metacharacters that could
     *      escape the shell-quote sandwich in RootWriter (single-quote
     *      escaping already handles apostrophes, but we refuse them here
     *      to keep audit logs readable).
     *   3. Must not target well-known dangerous pseudo-files.
     *   4. If the path currently exists on the device, its canonical (symlink-
     *      resolved) form must also satisfy rules 1-3. This catches symlinks
     *      that escape /sys or /proc (e.g. /sys/foo -> /proc/sys/kernel/panic).
     *      Non-existent paths (targeting another SoC) are allowed through -- the
     *      string checks in rules 1-3 already guard them.
     *
     * Delegates to [validateCustomSysfsPath(String, (String)->String?)] with a
     * real [java.io.File.canonicalPath] resolver.
     */
    fun validateCustomSysfsPath(path: String): String? =
        validateCustomSysfsPath(path) { p ->
            runCatching { java.io.File(p).canonicalPath }.getOrNull()
        }

    /**
     * Internal overload that accepts an injectable [canonicalResolver] so the
     * symlink-resolution logic can be exercised in JVM unit tests without a
     * real filesystem.
     *
     * [canonicalResolver] maps a path string to its canonical (symlink-resolved,
     * normalised) form, or returns `null` if the path cannot be resolved (does
     * not exist, or an [java.io.IOException] was thrown). A `null` result means
     * "can't tell" -- fall back to string-only validation (safe, because the
     * downstream write is existence-guarded by the root script).
     */
    internal fun validateCustomSysfsPath(
        path: String,
        canonicalResolver: (String) -> String?,
    ): String? {
        // --- String checks (rules 1-3) ---
        if (!path.startsWith("/sys/") && !path.startsWith("/proc/")) {
            return "Path must start with /sys/ or /proc/. Got: $path"
        }
        // Reject real NUL bytes.
        if (path.any { it.code == 0 }) return "Path contains null byte."
        // Reject spaces -- a sysfs path with a space cannot be safely interpolated
        // in a shell command, and confuses many kernel interfaces and audit logs.
        if (path.contains(' ')) return "Path contains space."
        if (path.contains('\n') || path.contains('\r')) return "Path contains newline."
        // Reject the full shell-metacharacter set: backtick, single/double quote, dollar,
        // semicolon, ampersand, pipe, angle brackets, parens, braces, brackets, glob chars
        // (* ?), exclamation mark, tilde, backslash, plus all remaining control chars.
        // A metachar in a path could escape quoting in the generated root script, confuse
        // kernel parsers, or corrupt audit logs.
        if (SHELL_META_PATH.containsMatchIn(path)) {
            return "Path contains a disallowed shell metacharacter."
        }
        // Reject sysrq-trigger, reboot, panic etc. Uses component-aware matching to
        // avoid false positives like "/sys/devices/virtual/reboot_mode/reboot_mode".
        if (isDangerousPath(path)) {
            return "Path $path is on the dangerous-path block list."
        }
        // Require no path traversal.
        if (path.contains("..")) return "Path contains '..' -- path traversal not allowed."

        // --- Canonical-resolution check (rule 4) ---
        // Only runs when the path exists on THIS device (resolver returns non-null).
        // Non-existent paths (targeting another SoC's kernel layout) are intentionally
        // allowed through -- the string checks above have already guarded them.
        val resolved = canonicalResolver(path)
        // Only meaningful when the resolver returns a Unix-absolute path (starts with
        // '/'). On a non-Linux build host (e.g. the JVM unit-test runner on Windows)
        // File.canonicalPath mangles "/sys/x" into "C:\sys\x", which is NOT a real
        // symlink resolution -- treat any non-'/'-rooted result as "can't resolve"
        // and fall back to the string checks. On a real Android device, /sys and
        // /proc canonicalise to '/'-rooted paths, so genuine symlink-escape detection
        // still works.
        if (resolved != null && resolved.startsWith("/") && resolved != path) {
            // Re-validate the resolved target with the same prefix + dangerous-path rules.
            if (!resolved.startsWith("/sys/") && !resolved.startsWith("/proc/")) {
                return "Path resolves to '$resolved' which is outside /sys or /proc."
            }
            if (resolved.contains("..")) {
                return "Resolved path '$resolved' contains '..' -- path traversal not allowed."
            }
            if (isDangerousPath(resolved)) {
                return "Path resolves to '$resolved' which is on the dangerous-path block list."
            }
        }

        return null
    }

    /**
     * Shell metacharacters that are disallowed in custom sysfs paths.
     *
     * Covers: single-quote, double-quote, backtick, dollar sign, semicolon, ampersand,
     * pipe, angle brackets, parentheses, curly braces, square brackets, glob chars
     * (* ?), exclamation mark, tilde, backslash. The trailing \p{Cntrl} catches NUL,
     * tab, and any other control character not handled by the explicit checks above.
     *
     * This regex is intentionally conservative: a legitimate sysfs path only ever
     * contains [A-Za-z0-9._/-], so no valid path should ever match.
     */
    private val SHELL_META_PATH: Regex = Regex("""['"`${'$'};&|<>(){}\[\]*?!~\\]|\p{Cntrl}""")

    // =========================================================================
    // Risk + ValueKind enums
    // =========================================================================

    /** How bad could misuse be? Used by the UI to colour warnings. */
    enum class Risk {
        /** Read-only or trivially reversible writes (e.g. raise readahead). */
        SAFE,
        /** Reduces performance or wastes power but causes no lasting harm. */
        LOW,
        /** May destabilise the system or drain battery significantly. */
        MEDIUM,
        /** Can cause a reboot, kernel panic, or significant data loss. */
        HIGH,
        /** DEVICE DAMAGE possible (e.g. thermal runaway, storage corruption). */
        DANGEROUS,
    }

    /** The value space, used by the UI to pick the right input widget. */
    sealed interface ValueKind {
        /** One of a fixed set of strings (e.g. governor names, "enabled"/"disabled"). */
        data class ENUM(val options: List<String>) : ValueKind
        /** Integer in min..max with optional step and unit label. */
        data class INT_RANGE(val min: Int, val max: Int, val step: Int = 1, val unit: String = "") : ValueKind
        /** Boolean: only "0" or "1" accepted. */
        data object BOOL : ValueKind
        /** Frequency in kHz (integer). */
        data class FREQ_KHZ(val minKhz: Int, val maxKhz: Int) : ValueKind
        /** Arbitrary string (no validation beyond path safety). */
        data object RAW_STRING : ValueKind
    }

    /** All metadata for one tunable knob. */
    data class TunableInfo(
        val name: String,
        val description: String,
        val risk: Risk,
        val valueKind: ValueKind,
        /** Whether the boot-revert journal covers this (always true via TunableWriter). */
        val isReversibleSafely: Boolean = true,
        /** Internal validator — null return = valid; non-null = error message. */
        internal val _validate: (String) -> String? = { null },
    ) {
        /**
         * Validates [value] against the declared [valueKind] constraints.
         * Returns null when valid, an error string when not.
         *
         * Validation order:
         *   1. The per-knob [_validate] lambda runs first — this is where
         *      cpu0-offline protection lives. A non-null result here is
         *      immediately returned so the value kind check below never fires.
         *   2. Then the [valueKind] range / set constraint.
         *
         * Callers must invoke this before writing — [TunableWriter] itself
         * does NOT call validate (it is a write-primitive; policy lives here).
         */
        fun validate(value: String): String? {
            // Per-knob override runs first (e.g. cpu0 offline guard).
            _validate(value)?.let { return it }

            return when (valueKind) {
                is ValueKind.BOOL -> if (value == "0" || value == "1") null
                    else "Boolean tunables accept only '0' or '1'. Got: '$value'"
                is ValueKind.INT_RANGE -> {
                    val v = value.toIntOrNull()
                        ?: return "Expected an integer. Got: '$value'"
                    when {
                        v < valueKind.min -> "Value $v is below minimum ${valueKind.min}."
                        v > valueKind.max -> "Value $v is above maximum ${valueKind.max}."
                        valueKind.step > 1 && (v - valueKind.min) % valueKind.step != 0 ->
                            "Value $v is not on the step grid (step=${valueKind.step}, min=${valueKind.min})."
                        else -> null
                    }
                }
                is ValueKind.FREQ_KHZ -> {
                    val v = value.toIntOrNull()
                        ?: return "Expected a frequency in kHz (integer). Got: '$value'"
                    when {
                        v < valueKind.minKhz -> "Frequency $v kHz is below min ${valueKind.minKhz} kHz."
                        v > valueKind.maxKhz -> "Frequency $v kHz is above max ${valueKind.maxKhz} kHz."
                        else -> null
                    }
                }
                is ValueKind.ENUM -> {
                    if (valueKind.options.isEmpty() || value in valueKind.options) null
                    else "Value '$value' is not in allowed set: ${valueKind.options}."
                }
                is ValueKind.RAW_STRING -> null
            }
        }
    }

    // =========================================================================
    // Resolution
    // =========================================================================

    private fun resolveByTarget(target: String): TunableInfo = when {
        // --- CPU core online ---
        target.matches(Regex("/sys/devices/system/cpu/cpu(\\d+)/online")) -> {
            val cpuNum = target.removePrefix("/sys/devices/system/cpu/cpu").removeSuffix("/online").toIntOrNull() ?: 0
            if (cpuNum == 0) {
                TunableInfo(
                    name = "CPU0 Online",
                    description = "CPU0 cannot be offlined — it is the boot processor required for IRQ handling.",
                    risk = Risk.DANGEROUS,
                    valueKind = ValueKind.BOOL,
                    _validate = { value ->
                        if (value == "0") "CPU0 cannot be offlined. This would crash the device." else null
                    },
                )
            } else {
                TunableInfo(
                    name = "CPU$cpuNum Online",
                    description = "Take CPU$cpuNum online (1) or offline (0). Reduces heat and power when idle cores are unneeded.",
                    risk = Risk.MEDIUM,
                    valueKind = ValueKind.BOOL,
                )
            }
        }

        // --- CPU governor tunables (anything under policy{N}/{governor}/) ---
        target.matches(Regex("/sys/devices/system/cpu/cpufreq/policy\\d+/[^/]+/[^/]+")) -> {
            val tunable = target.substringAfterLast("/")
            cpuGovernorTunableMeta(tunable)
        }

        // --- GPU power levels ---
        target.endsWith("/min_pwrlevel") -> TunableInfo(
            name = "GPU Min Power Level",
            description = "Minimum GPU power level (0=fastest). Raising this caps the GPU performance floor.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 15),
        )
        target.endsWith("/max_pwrlevel") -> TunableInfo(
            name = "GPU Max Power Level",
            description = "Maximum GPU power level (0=fastest). Raising this limits peak GPU power draw.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 15),
        )
        target.endsWith("/default_pwrlevel") -> TunableInfo(
            name = "GPU Default Power Level",
            description = "Default GPU power level used after idle or on GPU init.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 15),
        )

        // --- GPU throttling ---
        target.endsWith("/throttling") -> TunableInfo(
            name = "GPU Thermal Throttling",
            description = "Disable GPU thermal throttling (0). The GPU will run at full speed even under thermal load. " +
                "Risk: device may overheat. ONLY disable this temporarily for benchmarking.",
            risk = Risk.HIGH,
            valueKind = ValueKind.BOOL,
        )

        // --- GPU force_clk_on ---
        target.endsWith("/force_clk_on") -> TunableInfo(
            name = "GPU Force Clocks On",
            description = "Prevent GPU from idle clock-gating (1). Reduces latency of GPU wake-up at the cost of " +
                "higher idle power draw.",
            risk = Risk.MEDIUM,
            valueKind = ValueKind.BOOL,
        )

        // --- GPU idle_timer ---
        target.endsWith("/idle_timer") -> TunableInfo(
            name = "GPU Idle Timer (ms)",
            description = "Milliseconds before the GPU enters clock-gating after becoming idle. " +
                "Lower = faster to idle (saves power). Higher = lower latency on next wake.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 10_000, unit = "ms"),
        )

        // --- GPU devfreq governor tunables ---
        target.matches(Regex(".+/devfreq/[^/]+/[^/]+")) && !target.endsWith("/governor") &&
            !target.endsWith("/available_governors") -> {
            val tunable = target.substringAfterLast("/")
            gpuGovernorTunableMeta(tunable)
        }

        // --- Thermal zone mode ---
        target.matches(Regex("/sys/class/thermal/thermal_zone\\d+/mode")) -> TunableInfo(
            name = "Thermal Zone Mode",
            description = "Enable or disable a thermal zone. Disabling removes its throttle policy entirely. " +
                "The device may overheat if the wrong zone is disabled.",
            risk = Risk.HIGH,
            valueKind = ValueKind.ENUM(listOf("enabled", "disabled")),
        )

        // --- Thermal trip point temp ---
        target.matches(Regex("/sys/class/thermal/thermal_zone\\d+/trip_point_\\d+_temp")) -> TunableInfo(
            name = "Thermal Trip Point Temperature",
            description = "Temperature in millidegrees Celsius at which this trip fires. " +
                "Writing a HIGHER value raises (delays) the throttle threshold. " +
                "Writing an excessively high value may permanently damage the SoC.",
            risk = Risk.DANGEROUS,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 120_000, unit = "m°C"),
        )

        // --- Cooling device cur_state ---
        target.matches(Regex("/sys/class/thermal/cooling_device\\d+/cur_state")) -> TunableInfo(
            name = "Cooling Device State",
            description = "Current cooling state of a thermal cooling device. " +
                "0 = fully uncapped (removes the thermal cap). Higher = more cooling applied.",
            risk = Risk.HIGH,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 1024),
        )

        // --- Devfreq min/max/governor (bus, DDR) ---
        target.matches(Regex("/sys/class/devfreq/.+/min_freq")) -> TunableInfo(
            name = "Bus/DDR Min Frequency",
            description = "Minimum operating frequency for this bus/memory device in Hz.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
        target.matches(Regex("/sys/class/devfreq/.+/max_freq")) -> TunableInfo(
            name = "Bus/DDR Max Frequency",
            description = "Maximum operating frequency for this bus/memory device in Hz.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
        target.matches(Regex("/sys/class/devfreq/.+/governor")) -> TunableInfo(
            name = "Bus/DDR Governor",
            description = "Frequency scaling governor for this bus/memory device.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )

        // --- I/O scheduler ---
        target.matches(Regex("/sys/block/.+/queue/scheduler")) -> TunableInfo(
            name = "I/O Scheduler",
            description = "Block device I/O scheduler. Write the bare name (no brackets), e.g. 'mq-deadline'.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
        target.matches(Regex("/sys/block/.+/queue/read_ahead_kb")) -> TunableInfo(
            name = "Read-Ahead Buffer (kB)",
            description = "Read-ahead buffer size. Higher values improve sequential reads at the cost of memory.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 16384, unit = "kB"),
        )
        target.matches(Regex("/sys/block/.+/queue/nr_requests")) -> TunableInfo(
            name = "I/O Queue Depth",
            description = "Maximum number of requests in the I/O scheduler queue per request list. " +
                "Higher = more throughput; lower = lower latency.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 1, max = 4096),
        )

        // --- VM sysctls ---
        target == "/proc/sys/vm/swappiness" -> TunableInfo(
            name = "VM Swappiness",
            description = "Kernel preference for swapping memory pages (0=avoid swap, 100=swap aggressively). " +
                "Lowering this keeps more data in RAM.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 200),
        )
        target == "/proc/sys/vm/vfs_cache_pressure" -> TunableInfo(
            name = "VFS Cache Pressure",
            description = "Controls tendency to reclaim memory used for dentries/inodes. " +
                "Lower = more caching; higher = more aggressive reclaim.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 1000),
        )
        target == "/proc/sys/vm/dirty_ratio" -> TunableInfo(
            name = "Dirty Page Ratio (%)",
            description = "Percentage of RAM that can be dirty before processes start writing back. " +
                "Higher = more burst write bandwidth; lower = more predictable latency.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 1, max = 90, unit = "%"),
        )
        target == "/proc/sys/vm/dirty_background_ratio" -> TunableInfo(
            name = "Dirty Background Ratio (%)",
            description = "Percentage of RAM at which background writeback starts. Must be ≤ dirty_ratio.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 1, max = 90, unit = "%"),
        )

        // --- Kernel scheduler sysctls ---
        target == "/proc/sys/kernel/sched_migration_cost_ns" -> TunableInfo(
            name = "Scheduler Migration Cost (ns)",
            description = "Nanoseconds a task is considered cache-hot after running on a CPU. " +
                "Higher = fewer task migrations; lower = faster load balancing.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 50_000_000, unit = "ns"),
        )
        target == "/proc/sys/kernel/sched_min_granularity_ns" -> TunableInfo(
            name = "Scheduler Min Granularity (ns)",
            description = "Minimum time a task runs before being preempted.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 50_000_000, unit = "ns"),
        )
        target == "/proc/sys/kernel/sched_wakeup_granularity_ns" -> TunableInfo(
            name = "Scheduler Wakeup Granularity (ns)",
            description = "Wakeup preemption granularity. Smaller = tighter latency; larger = less preemption.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 50_000_000, unit = "ns"),
        )

        // --- SchedTune ---
        target.matches(Regex("/dev/stune/.+/schedtune\\.boost")) -> TunableInfo(
            name = "SchedTune Boost",
            description = "CPU boost level for this cgroup (0–100). Higher values migrate tasks to faster cores " +
                "and raise their utilisation estimates.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 100),
        )
        target.matches(Regex("/dev/stune/.+/schedtune\\.prefer_idle")) -> TunableInfo(
            name = "SchedTune Prefer Idle",
            description = "When 1, the scheduler tries to place tasks from this cgroup on idle CPUs to save power.",
            risk = Risk.LOW,
            valueKind = ValueKind.BOOL,
        )

        // --- uclamp ---
        target.matches(Regex("/dev/cpuctl/.+/cpu\\.uclamp\\.min")) -> TunableInfo(
            name = "CPU uclamp.min",
            description = "Minimum CPU utilisation clamp for this cgroup (0–1024). " +
                "Higher forces the scheduler to assume at least this much CPU utilisation.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 1024),
        )
        target.matches(Regex("/dev/cpuctl/.+/cpu\\.uclamp\\.max")) -> TunableInfo(
            name = "CPU uclamp.max",
            description = "Maximum CPU utilisation clamp for this cgroup (0–1024). " +
                "Caps how much CPU utilisation the scheduler uses for tasks in this group.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 1024),
        )

        // --- Input boost ---
        target == "/sys/module/cpu_boost/parameters/input_boost_freq" -> TunableInfo(
            name = "Input Boost Frequency",
            description = "Per-cluster CPU frequency during input boost window. Format: 'cluster:freqKHz …'. " +
                "The boost fires on touch/key events to reduce input latency.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
        target == "/sys/module/cpu_boost/parameters/input_boost_ms" -> TunableInfo(
            name = "Input Boost Duration (ms)",
            description = "How long the input boost frequency is maintained after an input event.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 5000, unit = "ms"),
        )

        // --- Fallthrough ---
        else -> unknownSysfsTunable(target)
    }

    // =========================================================================
    // Well-known governor tunable metadata helpers
    // =========================================================================

    private fun cpuGovernorTunableMeta(name: String): TunableInfo = when (name) {
        // ── WALT (Qualcomm Window-Assisted Load Tracker) ──────────────────────
        // Safe ranges validated by device probe; none of these touch voltages.
        "up_rate_limit_us" -> TunableInfo(
            name = "WALT Up Rate Limit (µs)",
            description = "Minimum µs between UP frequency steps. 0 = immediate ramp-up (snappiest, most power). " +
                "Safe range 0–20000 µs. Lower = faster boost under burst load.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 20_000, unit = "µs"),
        )
        "down_rate_limit_us" -> TunableInfo(
            name = "WALT Down Rate Limit (µs)",
            description = "Minimum µs between DOWN frequency steps. 0 = immediate ramp-down (saves power fastest). " +
                "Safe range 0–20000 µs. Higher = holds frequency longer after load drops.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 20_000, unit = "µs"),
        )
        "hispeed_load" -> TunableInfo(
            name = "WALT Hi-Speed Load (%)",
            description = "CPU load % that triggers an immediate jump to hispeed_freq. " +
                "Lower values boost sooner (more responsive, more power). Safe range 50–99 %.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 50, max = 99, unit = "%"),
        )
        "hispeed_freq" -> TunableInfo(
            name = "WALT Hi-Speed Frequency",
            description = "The frequency (kHz) the governor jumps to when hispeed_load is exceeded. " +
                "Pick from the cluster's available OPP table. Higher = snappier burst but more power.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
        "rtg_boost_freq" -> TunableInfo(
            name = "WALT RTG Boost Frequency",
            description = "Minimum frequency (kHz) pinned when a Related Task Group (RTG) is active (foreground app threads). " +
                "0 disables the pin. Match to hispeed_freq for consistent foreground boost.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
        "pl" -> TunableInfo(
            name = "WALT Power Limit Override",
            description = "When 1, allows the WALT governor to exceed the normal power limit for burst performance. " +
                "When 0 (default), power limit is enforced. Toggle — safe scheduling knob.",
            risk = Risk.LOW,
            valueKind = ValueKind.BOOL,
        )
        "boost" -> TunableInfo(
            name = "WALT Boost",
            description = "When 1, forces the cluster to hispeed_freq immediately regardless of load. " +
                "Resets to 0 automatically after the boost duration. " +
                "Use for sustained high-throughput burst (interactive governor: same semantics).",
            risk = Risk.LOW,
            valueKind = ValueKind.BOOL,
        )
        // ── schedutil ─────────────────────────────────────────────────────────
        "rate_limit_us" -> TunableInfo(
            name = "schedutil Rate Limit (µs)",
            description = "Minimum time between governor frequency decisions in microseconds.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 1_000_000, unit = "µs"),
        )
        // ── interactive ───────────────────────────────────────────────────────
        "timer_rate" -> TunableInfo(
            name = "Interactive Timer Rate (µs)",
            description = "Sampling interval of the interactive governor.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 1_000_000, unit = "µs"),
        )
        "min_sample_time" -> TunableInfo(
            name = "Interactive Min Sample Time (µs)",
            description = "Minimum time before the governor can lower the frequency.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 1_000_000, unit = "µs"),
        )
        "go_hispeed_load" -> TunableInfo(
            name = "Interactive Go Hi-Speed Load (%)",
            description = "Load threshold that triggers the hispeed frequency jump.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 1, max = 100, unit = "%"),
        )
        "target_loads" -> TunableInfo(
            name = "Interactive Target Loads",
            description = "Space-separated per-OPP target utilisation values.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
        "above_hispeed_delay" -> TunableInfo(
            name = "Interactive Above Hi-Speed Delay",
            description = "Delay before scaling up from hispeed_freq.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
        else -> TunableInfo(
            name = "CPU Governor Tunable: $name",
            description = "Per-governor kernel tunable '$name'. Consult the governor documentation for valid values.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
    }

    private fun gpuGovernorTunableMeta(name: String): TunableInfo = when (name) {
        "upthreshold" -> TunableInfo(
            name = "GPU Governor Up Threshold (%)",
            description = "GPU load threshold above which the governor scales up frequency.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 1, max = 100, unit = "%"),
        )
        "downdifferential" -> TunableInfo(
            name = "GPU Governor Down Differential",
            description = "How much load must drop below upthreshold before scaling down.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 0, max = 99),
        )
        "polling_interval" -> TunableInfo(
            name = "GPU Governor Polling Interval (ms)",
            description = "How often the GPU governor samples load.",
            risk = Risk.LOW,
            valueKind = ValueKind.INT_RANGE(min = 1, max = 10_000, unit = "ms"),
        )
        else -> TunableInfo(
            name = "GPU Governor Tunable: $name",
            description = "GPU devfreq governor tunable '$name'.",
            risk = Risk.LOW,
            valueKind = ValueKind.RAW_STRING,
        )
    }

    private fun unknownSysfsTunable(path: String) = TunableInfo(
        name = "Custom Sysfs: ${path.substringAfterLast("/")}",
        description = "Custom sysfs/procfs tunable at $path. No built-in validation — use with care.",
        risk = Risk.LOW,
        valueKind = ValueKind.RAW_STRING,
    )

    // =========================================================================
    // Dangerous proc paths block-list + component-aware matcher
    // =========================================================================

    /**
     * Bare node names that must never be written to via a custom sysfs/procfs path.
     *
     * Every entry is matched against a WHOLE path component (split on '/'), never as a
     * substring. Whole-component matching is the consistent rule for the entire blocklist
     * so that:
     *   - `reboot` blocks `/proc/sys/kernel/reboot` but NOT `/sys/.../reboot_mode/reboot_mode`.
     *   - `mem` / `kmem` block `/proc/mem` and `/proc/kmem` but NOT `/proc/kmem_stats` or
     *     `/proc/meminfo` (those have distinct components).
     *   - `panic` blocks `/proc/sys/kernel/panic` (and `panic_on_oops` etc. are NOT matched
     *     because they are different components — add them explicitly if ever needed).
     *
     * Categories covered:
     *   - Reboot / panic / sysrq — instant device kill or crash primitives.
     *   - kcore / kmem / mem — raw kernel/physical memory windows (info-leak + corruption).
     *   - drop_caches — forced cache drop, unsafe without quiescing the FS.
     *   - core_pattern — a write here pipes core dumps to an arbitrary program → classic
     *     root-escalation primitive.
     *   - kptr_restrict / dmesg_restrict / perf_event_paranoid — relaxing these lowers the
     *     kernel's own exploit-hardening (kernel-pointer disclosure, dmesg leak, perf abuse).
     *   - modules_disabled — irreversibly locks/unlocks module loading; writing it can either
     *     brick module loading or (mis)used to gate security.
     *
     * Use [isDangerousPath] to evaluate a path — never call `any { path.contains(it) }` directly.
     */
    private val DANGEROUS_PROC_PATHS = listOf(
        // Reboot / panic / sysrq
        "sysrq-trigger",
        "reboot",
        "panic",
        // Raw memory windows
        "kcore",
        "kmem",
        "mem",
        // Cache drop
        "drop_caches",
        // Root-escalation + kernel-hardening sysctls (SEC-1 expansion)
        "core_pattern",
        "kptr_restrict",
        "perf_event_paranoid",
        "dmesg_restrict",
        "modules_disabled",
    )

    /**
     * Power-supply node-name fragments whose write could DAMAGE the battery: forcing a
     * higher constant-charge current, raising the max charge voltage, or overriding the
     * charge-control / input-current limits. Matched as a PREFIX of a whole path component
     * (so `charge_control` blocks `charge_control_limit`, `charge_control_start_threshold`,
     * etc.) but ONLY when the path is under `/sys/class/power_supply/`. Restricting to that
     * root avoids false-blocking unrelated nodes that merely start with `voltage_max`.
     */
    private const val POWER_SUPPLY_ROOT = "/sys/class/power_supply/"
    private val DANGEROUS_POWER_SUPPLY_PREFIXES = listOf(
        "constant_charge_current",   // covers constant_charge_current[_max]
        "voltage_max",               // raising the charge-voltage ceiling
        "charge_control",            // charge_control_limit / *_start_threshold / *_end_threshold
        "input_current_limit",       // forcing more input current than the cell is rated for
    )

    /**
     * Thermal-zone trip-point node prefix. Writing a trip point raises/lowers the temperature
     * at which the kernel throttles — raising one can MASK an over-temperature condition and let
     * the SoC run hot enough to damage it. The app never legitimately writes these (the matching
     * [TunableInfo] is tagged DANGEROUS and is not exposed in the UI), so they are categorically
     * blocked at the guard as defense-in-depth: even if a future code path tried to write one, the
     * root channel refuses it. Matched as a component PREFIX (covers trip_point_0_temp,
     * trip_point_1_hyst, …) but ONLY under `/sys/class/thermal/`.
     */
    private const val THERMAL_ROOT = "/sys/class/thermal/"
    private const val THERMAL_TRIP_PREFIX = "trip_point_"

    /**
     * Returns true when [p] refers to a known-dangerous kernel node.
     *
     * Matching is WHOLE-COMPONENT only (split on '/'): an entry blocks a path iff some path
     * component equals it exactly. This prevents substring false-positives (e.g. "reboot"
     * inside "reboot_mode", or "kmem" inside "kmem_stats") while still erring toward blocking
     * the real nodes.
     *
     * Additionally, under `/sys/class/power_supply/` any component that STARTS WITH one of
     * [DANGEROUS_POWER_SUPPLY_PREFIXES] is blocked — a write to the charge-current / charge-
     * voltage family can physically damage the battery.
     */
    internal fun isDangerousPath(p: String): Boolean {
        val components = p.split('/')
        if (DANGEROUS_PROC_PATHS.any { entry -> components.any { it == entry } }) {
            return true
        }
        if (p.startsWith(POWER_SUPPLY_ROOT)) {
            if (components.any { comp ->
                    DANGEROUS_POWER_SUPPLY_PREFIXES.any { prefix -> comp.startsWith(prefix) }
                }
            ) {
                return true
            }
        }
        if (p.startsWith(THERMAL_ROOT)) {
            if (components.any { comp -> comp.startsWith(THERMAL_TRIP_PREFIX) }) {
                return true
            }
        }
        return false
    }
}
