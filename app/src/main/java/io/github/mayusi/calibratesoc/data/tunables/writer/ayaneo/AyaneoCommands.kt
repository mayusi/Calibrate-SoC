package io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo

/**
 * PURE command builders for the AYANEO `AyaAidlService` performance channel.
 *
 * Every method returns the EXACT payload string that [AyaneoBinderClient.sendCommand]
 * hands to the binder's `transact(1, …)` — no Android, no I/O, fully unit-testable.
 *
 * ## Wire protocol (decompiled from `com.ayaneo.gamewindow`'s AYAWindow app, then
 *    verified LIVE on the AYANEO Pocket DS — SG8275 / SD8Gen2 / Android 13)
 *
 * The server splits the received string on `":"` with `limit = 3` into
 * `[clientId, tag, msg]`. For a performance command the `tag` MUST be
 * [TAG_PERFORMANCE] (`msg_type_performance`) and `msg` keeps any further colons
 * (the limit-3 split). So every payload is:
 *
 *   `<CLIENT_ID>:<TAG_PERFORMANCE>:<command>`
 *
 * where `<command>` is itself a `key:value` pair the AYAWindow server matches on.
 *
 * ## Commands (the `<command>` portion)
 *  - `com_set_performance_cpu:<repCpu>_<freqHz>`  — cap a CPU cluster. `repCpu` is a
 *    representative core of the cluster; `freqHz` is in **HERTZ** (the AIDL wants Hz,
 *    cpufreq sysfs is kHz — the writer converts).
 *  - `com_set_performance_gpu:<maxFreqHz>`        — GPU max clock ceiling in Hz.
 *  - `com_set_performance_gpu_is_fixed:true|false`— pin the GPU at a fixed clock.
 *  - `com_set_performance_scheduler:<governor>`   — HIGH_PERFORMANCE / BALANCED /
 *    POWER_SAVING (maps to the cpufreq governor the overlay writes).
 *  - `com_set_performance_fan:<FAN_MODE_*>`        — fan preset.
 *  - `com_set_fan_speed_strategy:FAN_MODE_CUSTOM-<temp,duty|temp,duty|…>` — a fan curve.
 *  - `com_set_fan_speed_is_linear:LINEAR|STEP`     — fan-curve interpolation mode.
 *  - `com_set_performance_reset:<modeInt>`         — apply / reset a preset bundle.
 *
 * The overlay (uid=system) actuates the privileged sysfs writes; the `send` is
 * fire-and-forget on the server (GlobalScope coroutine, no perf reply) — so honesty
 * comes from READING BACK the actuated node, NOT from the transact return. See
 * [AyaneoVendorWriter] for the readback-verify layer.
 */
object AyaneoCommands {

    /** Our arbitrary clientId. The server only uses it to route replies (there are none). */
    const val CLIENT_ID = "calibrate"

    /** The performance-channel tag the server matches on (the `tag` of the 3-way split). */
    const val TAG_PERFORMANCE = "msg_type_performance"

    /** kHz → Hz: cpufreq sysfs values are kHz; the AIDL wants Hz. */
    const val KHZ_TO_HZ = 1_000L

    // ── Governor mapping ─────────────────────────────────────────────────────────

    /** AYANEO scheduler tokens the server understands. */
    const val SCHED_HIGH_PERFORMANCE = "HIGH_PERFORMANCE"
    const val SCHED_BALANCED = "BALANCED"
    const val SCHED_POWER_SAVING = "POWER_SAVING"

    // ── Fan preset tokens ────────────────────────────────────────────────────────

    const val FAN_MODE_OFF = "FAN_MODE_OFF"
    const val FAN_MODE_MUTE = "FAN_MODE_MUTE"
    const val FAN_MODE_BALANCE = "FAN_MODE_BALANCE"
    const val FAN_MODE_TURBO = "FAN_MODE_TURBO"
    const val FAN_MODE_CUSTOM = "FAN_MODE_CUSTOM"

    // ── Fan-curve linearity tokens ───────────────────────────────────────────────

    const val FAN_LINEAR = "LINEAR"
    const val FAN_STEP = "STEP"

    /**
     * Wrap a bare `<command>` (e.g. `com_set_performance_gpu:680000000`) into the full
     * `<clientId>:<tag>:<command>` payload the server's 3-way split expects.
     */
    fun wrap(command: String): String = "$CLIENT_ID:$TAG_PERFORMANCE:$command"

    /**
     * Cap a CPU cluster to [freqHz] Hz, addressed by [repCpu] (a representative core
     * of the cluster — e.g. the policy's first online core).
     *
     * Payload: `…:com_set_performance_cpu:<repCpu>_<freqHz>`
     */
    fun setCpuFreq(repCpu: Int, freqHz: Long): String =
        wrap("com_set_performance_cpu:${repCpu}_$freqHz")

    /**
     * Convenience for the cpufreq sysfs path: takes a kHz value (what the TunableId
     * carries) and a representative core, converts kHz → Hz, and builds the command.
     */
    fun setCpuFreqFromKhz(repCpu: Int, freqKhz: Long): String =
        setCpuFreq(repCpu, freqKhz * KHZ_TO_HZ)

    /** GPU max-clock ceiling in Hz. Payload: `…:com_set_performance_gpu:<maxFreqHz>`. */
    fun setGpuMaxFreq(maxFreqHz: Long): String =
        wrap("com_set_performance_gpu:$maxFreqHz")

    /** Pin (or unpin) the GPU at a fixed clock. */
    fun setGpuIsFixed(fixed: Boolean): String =
        wrap("com_set_performance_gpu_is_fixed:$fixed")

    /** CPU governor preset. Use [governorToken] to map a Linux governor name. */
    fun setScheduler(token: String): String =
        wrap("com_set_performance_scheduler:$token")

    /** Fan preset (one of the FAN_MODE_* tokens). */
    fun setFanMode(token: String): String =
        wrap("com_set_performance_fan:$token")

    /**
     * A custom fan curve. [points] are (tempC, dutyPct) pairs serialized as
     * `temp,duty|temp,duty|…` after the `FAN_MODE_CUSTOM-` prefix.
     *
     * Payload: `…:com_set_fan_speed_strategy:FAN_MODE_CUSTOM-<temp,duty|…>`
     */
    fun setFanCurve(points: List<Pair<Int, Int>>): String {
        val body = points.joinToString("|") { (temp, duty) -> "$temp,$duty" }
        return wrap("com_set_fan_speed_strategy:$FAN_MODE_CUSTOM-$body")
    }

    /** Fan-curve interpolation mode: LINEAR (interpolate) or STEP (hold). */
    fun setFanCurveLinear(linear: Boolean): String =
        wrap("com_set_fan_speed_is_linear:${if (linear) FAN_LINEAR else FAN_STEP}")

    /** Apply / reset a preset bundle by its integer mode. Useful for revert-to-stock. */
    fun reset(modeInt: Int): String =
        wrap("com_set_performance_reset:$modeInt")

    /**
     * Map a Linux cpufreq governor name to the AYANEO scheduler token.
     *
     *  - `performance`        → HIGH_PERFORMANCE
     *  - `powersave`          → POWER_SAVING
     *  - everything else (schedutil / walt / ondemand / conservative / …) → BALANCED
     *
     * Case-insensitive; an unknown / blank governor maps to BALANCED (the safe middle).
     */
    fun governorToken(governor: String): String = when (governor.trim().lowercase()) {
        "performance" -> SCHED_HIGH_PERFORMANCE
        "powersave" -> SCHED_POWER_SAVING
        else -> SCHED_BALANCED
    }
}
