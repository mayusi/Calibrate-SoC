package io.github.mayusi.calibratesoc.data.baseline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.BuildConfig
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.devicedb.PerfAdapterKind
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableSnapshot
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.SysfsWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the device's stock state to factory_baseline.json on first
 * launch. Subsequent launches no-op — the file is the immutable
 * reference for the lifetime of the install, deleted only when the
 * user explicitly invokes a factory reset OR uninstalls the app.
 *
 * What we capture (best-effort — files we can't read are silently
 * skipped, which is the right call: a missing tunable can't be
 * restored to a value we never observed):
 *   - For every CPU policy: scaling_min_freq, scaling_max_freq, scaling_governor
 *   - For the GPU: devfreq/min_freq, devfreq/max_freq, devfreq/governor
 *   - For the matched device adapter's Settings.System keys
 *     (performance_mode, fan_mode, etc.) — values flipped by AYN's
 *     own UI persist across reboots, so capturing the as-shipped
 *     state matters even on devices we never wrote to.
 *
 * We deliberately don't capture thermal trip points or hwmon PWM —
 * those aren't user-tunable in v1, so reverting them would be
 * meaningless.
 */
@Singleton
class FactoryBaselineRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val writerRegistry: WriterRegistry,
) {
    private val file: File by lazy { File(context.filesDir, FILE_NAME) }

    /** Returns the existing baseline if already captured, otherwise
     *  records a new one. Idempotent — safe to call from every
     *  Application.onCreate without risk of overwriting. */
    suspend fun ensureCaptured(
        report: CapabilityReport,
        adapter: DeviceAdapter?,
    ): FactoryBaseline = withContext(Dispatchers.IO) {
        existing()?.let { return@withContext it }

        val now = System.currentTimeMillis()
        val tunables = mutableListOf<TunableSnapshot>()

        // CPU policies: min, max, governor per discovered policy.
        for (policy in report.cpuPolicies) {
            snapshot(report, Tunables.cpuMinFreq(policy.policyId), now, "factory baseline")?.let(tunables::add)
            snapshot(report, Tunables.cpuMaxFreq(policy.policyId), now, "factory baseline")?.let(tunables::add)
            snapshot(report, Tunables.cpuGovernor(policy.policyId), now, "factory baseline")?.let(tunables::add)
        }

        // GPU: best-effort. The probe gives us a usable root path on
        // Adreno; we skip Mali / MediaTek where the probe couldn't
        // resolve, because writing back to a missing path would just
        // surface as a deny.
        report.gpu?.rootPath?.let { gpuRoot ->
            snapshot(report, Tunables.gpuMinFreq(gpuRoot), now, "factory baseline")?.let(tunables::add)
            snapshot(report, Tunables.gpuMaxFreq(gpuRoot), now, "factory baseline")?.let(tunables::add)
            snapshot(report, Tunables.gpuGovernor(gpuRoot), now, "factory baseline")?.let(tunables::add)
        }

        // Vendor Settings.System keys, when the device adapter exposes
        // them. These are factory-default on first launch (before the
        // user has had a chance to flip anything in our UI) so what
        // we read IS the stock value.
        adapter?.fanAdapter?.takeIf { it.kind.name == "SETTINGS_KEY" }?.let {
            snapshot(report, Tunables.settingsSystemKey(it.target), now, "factory baseline")?.let(tunables::add)
        }
        adapter?.perfPresetAdapter?.takeIf { it.kind == PerfAdapterKind.SETTINGS_KEY }?.let {
            snapshot(report, Tunables.settingsSystemKey(it.target), now, "factory baseline")?.let(tunables::add)
        }

        val baseline = FactoryBaseline(
            capturedAtMs = now,
            appVersionAtCapture = BuildConfig.VERSION_NAME,
            deviceModel = "${report.device.manufacturer} ${report.device.model}".trim(),
            socModel = "${report.soc.socManufacturer} ${report.soc.socModel}".trim(),
            tunables = tunables,
        )

        writeAtomic(baseline)
        baseline
    }

    fun existing(): FactoryBaseline? = runCatching {
        if (!file.exists()) return@runCatching null
        json.decodeFromString<FactoryBaseline>(file.readText())
    }.getOrNull()

    /** User-initiated factory reset clears the baseline so the NEXT
     *  launch recaptures a fresh one (because the restore wrote back
     *  the stock values, the recapture will see the same numbers —
     *  but timestamps will refresh). */
    suspend fun delete() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
    }

    private suspend fun snapshot(
        report: CapabilityReport,
        id: TunableId,
        now: Long,
        reason: String,
    ): TunableSnapshot? {
        val writer: SysfsWriter = writerRegistry.writerFor(id, report)
        val value = writer.read(id) ?: return null
        return TunableSnapshot(
            id = id,
            previousValue = value,
            writtenAtMs = now,
            reason = reason,
        )
    }

    private fun writeAtomic(baseline: FactoryBaseline) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(baseline))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val FILE_NAME = "factory_baseline.json"
    }
}
