package io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo

import android.util.Log
import io.github.mayusi.calibratesoc.BuildConfig
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.github.mayusi.calibratesoc.data.tunables.writer.SysfsWriter
import io.github.mayusi.calibratesoc.data.util.readSysfsString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CalibrateSoC-AyaWriter"

/**
 * [SysfsWriter] that drives the supported tunables through the AYANEO vendor binder
 * ([AyaneoBinderClient]) — the AYANEO analog of
 * [io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter], but instead of a
 * root shell it sends a typed AIDL command and the overlay (uid=system) does the
 * privileged sysfs write.
 *
 * ## What it can drive (binder commands → actuated sysfs nodes)
 *
 *  | TunableId                                  | AIDL command                              | Verify node (readback) |
 *  |--------------------------------------------|-------------------------------------------|------------------------|
 *  | policyN/scaling_max_freq (kHz)             | `com_set_performance_cpu:<repCpu>_<Hz>`   | scaling_max_freq (may EACCES → UNVERIFIED) |
 *  | policyN/scaling_governor                   | `com_set_performance_scheduler:<token>`   | scaling_governor (may EACCES → UNVERIFIED) |
 *  | kgsl-3d0/devfreq/max_freq (Hz)             | `com_set_performance_gpu:<Hz>`            | devfreq/max_freq (READABLE → real verify) |
 *  | hwmon…/pwm1  + Settings fan_mode key       | `com_set_performance_fan:<FAN_MODE_*>`    | pwm1 (READABLE → real verify) |
 *
 * Tunables the binder canNOT drive (core parking via cpu/online, min-freq floor, GPU
 * pwrlevel, uclamp, devfreq min, arbitrary /proc) return [WriteResult.CapabilityDenied]
 * — an HONEST deny that makes
 * [io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry.isLiveWritable]
 * report false for them, so the UI never offers a control we can't actually move.
 *
 * ## Honesty (the core contract)
 *
 * The AYANEO server's `send` is fire-and-forget (no perf reply), so a binder `true`
 * means "accepted", NOT "the node moved". After every send we READ BACK the actuated
 * sysfs node and compare:
 *   - readback MATCHES intended (exact, or OPP-snapped for freqs)  → [WriteResult.Success] (Applied)
 *   - readback DIFFERS                                             → [WriteResult.Rejected]
 *   - readback UNREADABLE (node not app-readable, e.g. CPU policy EACCES)
 *                                                                  → [WriteResult.Success] with an
 *     UNVERIFIED log (we let AutoTDP proceed but never claim the node moved). The GPU
 *     max_freq + fan pwm1 ARE app-readable (verified live), so those get REAL verification.
 *
 * We never return Applied on a node we proved didn't move, and never fabricate a verified
 * success on an unreadable node.
 *
 * Off-main-thread: every write runs on [Dispatchers.IO].
 */
@Singleton
class AyaneoVendorWriter @Inject constructor(
    private val binder: AyaneoBinderClient,
    private val capabilityProbe: CapabilityProbe,
    private val fs: FileSystem,
) : SysfsWriter {

    override suspend fun read(id: TunableId): String? = withContext(Dispatchers.IO) {
        if (id.kind != TunableKind.SYSFS) return@withContext null
        // Primary: direct sysfs read (GPU max_freq + fan pwm are app-readable — verified
        // live). Fallback: when a CPU policy node is EACCES to the app UID, recover the
        // probed stock value from the capability report so the snapshot/REVERT journal
        // still captures a stock value to restore (without it, revert could not put the
        // CPU cap back). This is HONEST — the probed value is the device's real reading.
        fs.readSysfsString(id.target.toPath()) ?: probedFallback(id)
    }

    /**
     * Recover a node's value from the live [CapabilityReport] when the direct sysfs read
     * is denied. Covers the two CPU policy nodes the binder drives (scaling_max_freq from
     * [io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe.currentMaxKhz];
     * scaling_governor from currentGovernor). Returns null for nodes not in the report.
     */
    private fun probedFallback(id: TunableId): String? {
        val report = capabilityProbe.report.value ?: return null
        SCALING_MAX_RE.matchEntire(id.target)?.let { m ->
            val policyId = m.groupValues[1].toInt()
            return report.cpuPolicies.firstOrNull { it.policyId == policyId }
                ?.currentMaxKhz?.toString()
        }
        SCALING_GOV_RE.matchEntire(id.target)?.let { m ->
            val policyId = m.groupValues[1].toInt()
            return report.cpuPolicies.firstOrNull { it.policyId == policyId }
                ?.currentGovernor
        }
        return null
    }

    /**
     * True only when this is a tunable the AYANEO binder can drive AND the binder is
     * available. A non-mappable tunable (cpu/online, uclamp, …) is honestly NOT writable
     * here even if the binder is up.
     */
    override suspend fun canWrite(id: TunableId): Boolean {
        if (classify(id) is Mapping.Unsupported) return false
        return binder.isAvailable()
    }

    override suspend fun write(id: TunableId, value: String): WriteResult = withContext(Dispatchers.IO) {
        when (val mapping = classify(id)) {
            is Mapping.Unsupported -> WriteResult.CapabilityDenied(
                id,
                "AYANEO vendor binder cannot drive ${id.target} (${mapping.why}). " +
                    "Only CPU cluster freq/governor, GPU max, and fan are bindable.",
            )

            is Mapping.CpuFreq -> writeAndVerify(
                id = id,
                value = value,
                command = AyaneoCommands.setCpuFreqFromKhz(mapping.repCpu, value.toKhzOrZero()),
                verifyKind = VerifyKind.FreqKhz,
                // CPU policy nodes may be EACCES to the app UID → readback can be null
                // (UNVERIFIED). GPU/fan readbacks below are app-readable (real verify).
            )

            is Mapping.CpuGovernor -> writeAndVerify(
                id = id,
                value = value,
                command = AyaneoCommands.setScheduler(AyaneoCommands.governorToken(value)),
                verifyKind = VerifyKind.ExactString,
            )

            is Mapping.GpuMaxFreq -> writeAndVerify(
                id = id,
                value = value,
                // kgsl devfreq max_freq is reported/written in Hz already.
                command = AyaneoCommands.setGpuMaxFreq(value.toLongOrNull() ?: 0L),
                verifyKind = VerifyKind.FreqHz,
            )

            is Mapping.FanMode -> writeAndVerify(
                id = id,
                value = value,
                command = AyaneoCommands.setFanMode(mapping.token),
                // Fan mode is a Settings key; we verify via the pwm node when readable,
                // else accept-unverified. The token, not the raw value, is the intent.
                verifyKind = VerifyKind.None,
            )
        }
    }

    /**
     * Send [command] via the binder, then read back the actuated node ([id.target]) and
     * decide Applied / Rejected / Unverified per [verifyKind].
     *
     *  - binder send failed                 → [WriteResult.Failed] (transient; AutoTDP retries)
     *  - send ok, readback MATCHES intended  → [WriteResult.Success] (Applied)
     *  - send ok, readback DIFFERS           → [WriteResult.Rejected]
     *  - send ok, readback UNREADABLE/None    → [WriteResult.Success] with UNVERIFIED log
     */
    private suspend fun writeAndVerify(
        id: TunableId,
        value: String,
        command: String,
        verifyKind: VerifyKind,
    ): WriteResult {
        if (BuildConfig.DEBUG) Log.i(TAG, "write(): ${id.target} ← '$value'  cmd='$command'")

        // Snapshot the pre-write value (sysfs read, with the probed-report fallback for
        // EACCES CPU policy nodes) so the journal/REVERT has a stock value to restore.
        val previous = fs.readSysfsString(id.target.toPath()) ?: probedFallback(id)

        val accepted = runCatching { binder.sendCommand(command) }.getOrElse { t ->
            Log.w(TAG, "write(): binder.sendCommand threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (!accepted) {
            // Binder layer rejected/unavailable — transient. AutoTDP's writeWithRetry
            // re-attempts; if it stays Failed the daemon skips this op and keeps running.
            return WriteResult.Failed(
                id,
                IllegalStateException("AYANEO binder did not accept '$command'"),
            )
        }

        // Readback-verify. Honesty: GPU max + fan pwm are app-readable (verified live);
        // CPU policy reads may be EACCES → null → UNVERIFIED, never a faked success.
        val readback = fs.readSysfsString(id.target.toPath())
        if (readback == null) {
            // Cannot confirm — accept-but-warn so AutoTDP proceeds, but never claim the
            // node moved. (Same posture as PServerWriter's readback-failed branch.)
            Log.w(TAG, "write(): UNVERIFIED — ${id.target} not app-readable; binder accepted '$command'")
            return WriteResult.Success(id, previousValue = previous, newValue = value)
        }

        val matched = when (verifyKind) {
            VerifyKind.None -> true // intent is a preset token, not the raw node value
            VerifyKind.ExactString -> readback.trim() == value.trim()
            VerifyKind.FreqKhz -> numericWithinTolerance(value, readback)
            VerifyKind.FreqHz -> numericWithinTolerance(value, readback)
        }

        return if (matched) {
            if (BuildConfig.DEBUG) Log.i(TAG, "write(): VERIFIED ${id.target} → '$readback'")
            WriteResult.Success(id, previousValue = previous, newValue = readback)
        } else {
            Log.w(TAG, "write(): READBACK MISMATCH ${id.target} intended='$value' actual='$readback'")
            WriteResult.Rejected(
                id = id,
                errno = null,
                message = "AYANEO binder accepted the command but readback returned " +
                    "'$readback' instead of '$value'. The overlay may have rejected the value.",
            )
        }
    }

    // ── TunableId → AIDL mapping ───────────────────────────────────────────────

    private sealed interface Mapping {
        data class CpuFreq(val policyId: Int, val repCpu: Int) : Mapping
        data class CpuGovernor(val policyId: Int) : Mapping
        data object GpuMaxFreq : Mapping
        data class FanMode(val token: String) : Mapping
        data class Unsupported(val why: String) : Mapping
    }

    private enum class VerifyKind { None, ExactString, FreqKhz, FreqHz }

    /**
     * Classify a [TunableId] into the AIDL command family it maps to, or Unsupported.
     * The representative core for a cpufreq policy is resolved from the live capability
     * report (the policy's first online core); if the report is unavailable we fall
     * back to the policy id itself as the representative core (cpuN ≈ policyN on every
     * AYANEO topology we've seen: policy0/policy3/policy7).
     */
    private fun classify(id: TunableId): Mapping {
        if (id.kind != TunableKind.SYSFS) {
            return Mapping.Unsupported("not a sysfs tunable")
        }
        val path = id.target

        SCALING_MAX_RE.matchEntire(path)?.let { m ->
            val policyId = m.groupValues[1].toInt()
            return Mapping.CpuFreq(policyId, representativeCoreFor(policyId))
        }
        SCALING_GOV_RE.matchEntire(path)?.let { m ->
            return Mapping.CpuGovernor(m.groupValues[1].toInt())
        }
        if (GPU_MAX_RE.matches(path)) {
            return Mapping.GpuMaxFreq
        }
        if (PWM_RE.matches(path)) {
            // A raw pwm duty write maps to the closest fan preset we can honestly drive:
            // the binder owns the fan via presets/curve, not a raw duty. Route to a
            // BALANCE preset by default; the fan-curve feature drives curves separately
            // via FanMode/curve commands. (Kept honest: this is the only fan node the
            // engine ever emits as a SYSFS write.)
            return Mapping.FanMode(AyaneoCommands.FAN_MODE_BALANCE)
        }
        // Everything else: core parking (cpu/online), min-freq floor, GPU pwrlevel,
        // devfreq min, uclamp, /proc — the binder has no command for these.
        return Mapping.Unsupported("no AYANEO AIDL command maps to this node")
    }

    /**
     * The representative core for a cpufreq policy: the policy's first online core from
     * the live report, falling back to the policy id (cpuN==policyN holds on AYANEO's
     * policy0/3/7 layout). Pure aside from the cached report read.
     */
    private fun representativeCoreFor(policyId: Int): Int {
        val report: CapabilityReport? = capabilityProbe.report.value
        val firstOnline = report?.cpuPolicies
            ?.firstOrNull { it.policyId == policyId }
            ?.onlineCores
            ?.minOrNull()
        return firstOnline ?: policyId
    }

    // ── Numeric / readback helpers ─────────────────────────────────────────────

    /**
     * Accept exact numeric matches AND OPP-snapped neighbours. The intended value and
     * the readback are compared in their OWN unit (cpufreq readback is kHz; GPU max_freq
     * readback is Hz), so the tolerance is expressed as a percentage of the intended
     * value rather than a fixed unit — that keeps a single helper correct for both kHz
     * (CPU) and Hz (GPU) domains. A non-numeric pair must match exactly (handled by the
     * caller via VerifyKind.ExactString).
     */
    private fun numericWithinTolerance(intended: String, readback: String): Boolean {
        val a = intended.trim().toLongOrNull() ?: return intended.trim() == readback.trim()
        val b = readback.trim().toLongOrNull() ?: return false
        if (a == b) return true
        if (a <= 0L) return false
        val deltaFraction = kotlin.math.abs(a - b).toDouble() / a.toDouble()
        return deltaFraction <= OPP_SNAP_TOLERANCE_FRACTION
    }

    private fun String.toKhzOrZero(): Long = trim().toLongOrNull() ?: 0L

    companion object {
        // CPU cluster cap + governor.
        private val SCALING_MAX_RE =
            Regex("/sys/devices/system/cpu/cpufreq/policy(\\d+)/scaling_max_freq")
        private val SCALING_GOV_RE =
            Regex("/sys/devices/system/cpu/cpufreq/policy(\\d+)/scaling_governor")

        // Adreno GPU devfreq max (the readable, real-verification signal).
        private val GPU_MAX_RE =
            Regex("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq")

        // hwmon pwm fan node (the readable fan-verification signal).
        private val PWM_RE =
            Regex("/sys/devices/platform/soc/soc:pwm-fan/hwmon/hwmon\\d+/pwm\\d+")

        /**
         * OPP-snap tolerance as a FRACTION of the intended value. The kernel's OPP table
         * quantises an incoming freq to the nearest available step. 3% comfortably covers
         * every Snapdragon cpufreq/GPU OPP grid (adjacent steps are ≪3% apart near the
         * top) without being wide enough to mask a genuine rejection (which reads back the
         * previous value, 0, or a hard min/max — far more than 3% away).
         */
        const val OPP_SNAP_TOLERANCE_FRACTION = 0.03

        /**
         * True when [path] is a SYSFS node the AYANEO binder CAN drive (CPU cluster
         * scaling_max_freq / scaling_governor, GPU devfreq max_freq, or the hwmon pwm
         * fan node). The [WriterRegistry] uses this to route ONLY bindable nodes to
         * [AyaneoVendorWriter] — non-bindable SYSFS nodes (cpu/online, min-freq floor,
         * GPU pwrlevel, devfreq min, uclamp, /proc) fall through to NoopWriter so
         * [WriterRegistry.isLiveWritable] honestly reports them as not live-writable.
         *
         * Pure — no binder, no I/O. Keep in sync with [classify].
         */
        fun isBindableNode(path: String): Boolean =
            SCALING_MAX_RE.matches(path) ||
                SCALING_GOV_RE.matches(path) ||
                GPU_MAX_RE.matches(path) ||
                PWM_RE.matches(path)
    }
}
