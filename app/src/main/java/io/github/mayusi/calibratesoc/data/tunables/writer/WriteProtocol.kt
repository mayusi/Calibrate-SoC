package io.github.mayusi.calibratesoc.data.tunables.writer

/**
 * Per-device write recipe for a tunable. Some kernels (notably AYN's
 * Snapdragon 8 Elite kernel on the Odin 3) have a perf-hint daemon that
 * races back and overwrites our cpufreq cap milliseconds after we write
 * it. TheOldTaylor's underclock scripts handle this by:
 *
 *   1. stop the responsible vendor services
 *   2. chmod 666 the target so we have write permission
 *   3. echo > target
 *   4. chmod 444 to lock the value against late-firing daemons that
 *      restart themselves (or that we couldn't stop)
 *
 * That recipe doesn't apply on stock AOSP kernels — chmod-ing sysfs
 * doesn't even make sense there. So we make it opt-in per device via
 * the [DeviceAdapter] and let the writer chain pick it up.
 *
 * The "pre" / "post" hooks are arbitrary shell commands run as root.
 * Each command is executed serially; a failure on any one is logged but
 * does not abort the write — the user usually wants their tune attempted
 * even if a daemon was already dead.
 */
data class WriteProtocol(
    /** Shell commands to run before the write — e.g. "stop perfd". */
    val pre: List<String> = emptyList(),
    /** Shell commands to run after the write — e.g. "chmod 444 <target>". */
    val post: List<String> = emptyList(),
    /**
     * True when the target file's mode must be relaxed (chmod 666) before
     * write. Some vendor kernels publish their cpufreq nodes as 0444 by
     * default and a stock `echo > path` ENOENTs out — the AYN Odin 3
     * kernel is one of them.
     */
    val relaxModeBeforeWrite: Boolean = false,
    /** New mode to set AFTER write, in decimal (so 0o444 → 292).
     *  0 = leave as found. Use [MODE_READ_ONLY] for the common case. */
    val sealModeAfterWriteOctal: Int = MODE_LEAVE_ALONE,
) {
    companion object {
        /** Kotlin lacks Python-style octal literals; these are octal
         *  values written in decimal. 292 == 0444, 438 == 0666. */
        const val MODE_LEAVE_ALONE = 0
        const val MODE_READ_ONLY = 292        // 0444
        const val MODE_RW_FOR_OWNER_ONLY = 384 // 0600

        /** No-op recipe used when the device adapter doesn't override. */
        val NONE = WriteProtocol()

        /**
         * The TheOldTaylor pattern, parameterised for any AYN-style
         * device where perfd + vendor.perf-hal-* daemons fight the write.
         *
         * The exact daemon set is per-device — Odin 2 only has perfd;
         * Odin 3 adds the two vendor.perf-hal services. Adapters supply
         * the actual list.
         */
        fun aynLikeLock(daemons: List<String>): WriteProtocol = WriteProtocol(
            pre = daemons.map { "stop $it" },
            post = daemons.map { "start $it" }, // restart on next preset apply / revert
            relaxModeBeforeWrite = true,
            sealModeAfterWriteOctal = MODE_READ_ONLY,
        )
    }
}
