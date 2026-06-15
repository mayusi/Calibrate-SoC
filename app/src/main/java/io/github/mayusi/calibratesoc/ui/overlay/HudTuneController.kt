package io.github.mayusi.calibratesoc.ui.overlay

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
import io.github.mayusi.calibratesoc.data.script.ScriptGenerateResult
import io.github.mayusi.calibratesoc.data.tunables.ApplyPathway
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.vendor.OdinIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Handles all live-tune write operations for the HUD stepper buttons and
 * profile chip apply. Extracted from [OverlayService] to keep the service
 * as a thin lifecycle host.
 *
 * Write priority order (same as the original OverlayService):
 *  1. Direct sysfs FileWriter (chmod 666 applied by unlock script)
 *  2. PServer binder (Odin vendor daemon, permissive SELinux)
 *  3. libsu root shell (Magisk / KernelSU)
 *  4. Script-per-tap fallback (generates + deploys a one-shot script)
 *
 * All state changes (lastActionMessage, bigCoreCurrentMhz, history) are
 * reported back via [HudStateAssembler] so the HUD recomposes correctly.
 *
 * Not a Hilt [Singleton]: created by [OverlayService] and bound to its
 * lifecycle scope.
 */
class HudTuneController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val capabilityProbe: CapabilityProbe,
    private val deviceAdapterRegistry: DeviceAdapterRegistry,
    private val scriptGenerator: AynScriptGenerator,
    private val scriptDeployer: AynScriptDeployer,
    private val tuneHistoryStore: TuneHistoryStore,
    private val pServerWriter: PServerWriter,
    private val hudEventLog: HudEventLog,
    private val profileRepository: ProfileRepository,
) {
    // Injected by OverlayService after construction (avoids circular dep).
    lateinit var assembler: HudStateAssembler
    private var serviceScope: CoroutineScope? = null

    private val flashToken = AtomicLong(0L)
    private var lastWriteFailureReason: String? = null

    fun bind(scope: CoroutineScope) {
        serviceScope = scope
    }

    // ── Public entry points called from OverlayService ────────────────────────

    /**
     * Step the big-core cluster max-frequency by [deltaMhz] using the best
     * available write path (direct sysfs → PServer → libsu → script).
     *
     * **AutoTDP gate**: the first thing this function checks is
     * [HudUiState.autoTdpRunning]. This write-side check is the *authoritative*
     * barrier — do not remove it. The UI-side stepper hide ([HudOverlayContent]
     * via [HudDisplayUtils.shouldGateSteppers]) is a UX convenience only; a
     * race, a notification shortcut, or a future feature could still call this
     * function while AutoTDP is running. The code guard here is what prevents
     * conflicting clock writes.
     */
    fun stepBigCoreMhz(deltaMhz: Int) {
        val scope = serviceScope ?: return
        scope.launch {
            val state = assembler.state.value

            // Gate: AutoTDP owns the clocks when running.
            if (state.autoTdpRunning) {
                flashActionMessage("AutoTDP is managing clocks — stop it first to tune manually.")
                return@launch
            }

            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            if (report.cpuPolicies.isEmpty()) {
                hudEventLog.add(HudEventLog.Level.WARN, "No CPU policy found for stepping")
                flashActionMessage("No CPU policy detected.")
                return@launch
            }
            val enabled = state.enabledPolicies.ifEmpty {
                report.cpuPolicies.map { it.policyId }.toSet()
            }
            val targets = report.cpuPolicies.filter { it.policyId in enabled }
            if (targets.isEmpty()) {
                flashActionMessage("Toggle at least one cluster chip.")
                return@launch
            }

            // OPP-snap: step to the nearest available OPP frequency.
            val perPolicyNewKhz = mutableMapOf<Int, Int>()
            for (p in targets) {
                val targetKhz = (p.currentMaxKhz / 1000 + deltaMhz) * 1000
                val snapped = p.availableFreqsKhz.minByOrNull { kotlin.math.abs(it - targetKhz) }
                    ?: targetKhz
                perPolicyNewKhz[p.policyId] = snapped
            }

            val rootAvailable = isRootAvailable()
            val summary = perPolicyNewKhz.toSortedMap().entries.joinToString(" ") {
                "p${it.key}=${it.value / 1000}"
            }

            // Multi-path write dispatch.
            lastWriteFailureReason = null
            val result = executeStepWrite(perPolicyNewKhz, report, summary, deltaMhz, rootAvailable)
            lastWriteFailureReason = result.failureReason

            // UI feedback.
            when {
                result.applied && result.via == "direct" -> {
                    hudEventLog.add(HudEventLog.Level.ACTION, "direct write OK ($summary)")
                    flashActionMessage("Applied: $summary MHz")
                }
                result.applied && result.via == "pserver" -> {
                    hudEventLog.add(HudEventLog.Level.ACTION, "PServer write OK ($summary)")
                    flashActionMessage("Applied: $summary MHz")
                }
                result.applied && result.via == "libsu" -> {
                    hudEventLog.add(HudEventLog.Level.ACTION, "root write OK ($summary)")
                    flashActionMessage("Applied: $summary MHz")
                }
                !result.applied && result.via == "libsu" -> {
                    val err = result.failureReason ?: "unknown"
                    hudEventLog.add(HudEventLog.Level.ERROR, "root write failed: $err")
                    flashActionMessage("Root write failed: $err")
                }
                result.via == "script" -> {
                    hudEventLog.add(HudEventLog.Level.WARN, "no root — script written ($summary)")
                    val reason = result.failureReason
                    flashActionMessage(
                        if (reason != null) {
                            "Write failed — $reason. Need root or run setup script (Settings → Unlock HUD)."
                        } else {
                            "No root — need to run setup script. See Settings → Unlock HUD."
                        }
                    )
                }
            }

            // Update big-core MHz in the assembler.
            val bigPolicyId = targets.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }?.policyId
            val bigNewKhz = bigPolicyId?.let { perPolicyNewKhz[it] }
            assembler.feedBigCoreMhz(bigPolicyId, bigNewKhz?.let { it / 1000 })

            tuneHistoryStore.append(
                TuneHistoryEntry(
                    appliedAtMs = System.currentTimeMillis(),
                    presetName = "HUD step ${if (deltaMhz >= 0) "+" else ""}$deltaMhz MHz",
                    presetDescription = summary,
                    pathway = when {
                        result.applied && result.via != "script" -> ApplyPathway.DIRECT_ROOT
                        else -> ApplyPathway.GENERATED_SCRIPT
                    },
                    notes = when (result.via) {
                        "direct" -> "HUD stepper (chmod direct)"
                        "pserver" -> "HUD stepper (PServer binder)"
                        "libsu" -> "HUD stepper (libsu root)"
                        else -> "HUD stepper (script fallback)"
                    },
                    cpuPolicyMaxKhz = perPolicyNewKhz,
                ),
            )
        }
    }

    fun cycleNextProfile() {
        val chips = assembler.state.value.quickProfiles
        if (chips.isEmpty()) {
            flashActionMessage("No saved profiles to cycle through.")
            hudEventLog.add(HudEventLog.Level.WARN, "Profile cycle: nothing to cycle")
            return
        }
        val lastAppliedId = hudEventLog.entries.value
            .firstNotNullOfOrNull { entry ->
                Regex("^apply ([^ ]+) ").find(entry.message)?.groupValues?.getOrNull(1)
            }
        val idx = chips.indexOfFirst { it.first == lastAppliedId }.takeIf { it >= 0 } ?: -1
        val next = chips[(idx + 1).coerceAtLeast(0) % chips.size]
        hudEventLog.add(HudEventLog.Level.ACTION, "apply ${next.first} (${next.second})")
        applyProfileViaScript(next.first)
    }

    fun applyProfileViaScript(profileId: String) {
        val scope = serviceScope ?: return
        scope.launch {
            val profile = profileRepository.snapshot().profiles.firstOrNull { it.id == profileId }
                ?: return@launch
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
            val preset = profile.toPreset()
            val generateResult = scriptGenerator.generate(preset, report, adapter)
            if (generateResult is ScriptGenerateResult.Rejected) {
                // The device-safety gate blocked this profile (wrong device / foreign
                // cluster). Tell the user why instead of silently doing nothing — they
                // tapped a chip and deserve to know it was refused, not just see a no-op.
                flashActionMessage("Can't apply ${profile.name}: ${generateResult.reason}")
                return@launch
            }
            val deployed = scriptDeployer.deploy(preset, (generateResult as ScriptGenerateResult.Ok).script)
            tuneHistoryStore.append(
                TuneHistoryEntry(
                    appliedAtMs = System.currentTimeMillis(),
                    presetName = profile.name,
                    presetDescription = profile.description,
                    pathway = ApplyPathway.GENERATED_SCRIPT,
                    notes = "from HUD chip",
                    cpuPolicyMaxKhz = profile.cpuPolicyMaxKhz,
                    cpuPolicyMinKhz = profile.cpuPolicyMinKhz,
                    cpuPolicyGovernor = profile.cpuPolicyGovernor,
                    gpuMaxHz = profile.gpuMaxHz,
                    gpuMinHz = profile.gpuMinHz,
                    gpuGovernor = profile.gpuGovernor,
                ),
            )
            val vsName = OdinIntents.vendorSettingsName(appContext)

            val deployedMsg = if (deployed.visibleToOdinPicker) {
                "Wrote ${profile.name} → /sdcard/CalibrateSoC. Open $vsName → Run as Root."
            } else {
                "Wrote ${profile.name} to app-private storage. Move it to /sdcard to run it."
            }
            // Use the same atomic-token flash path as flashActionMessage() so rapid
            // profile-chip taps cannot leave a stale message or clear the wrong one.
            flashActionMessage(deployedMsg)
        }
    }

    // ── Private write-path helpers ─────────────────────────────────────────────

    private data class StepWriteResult(
        val applied: Boolean,
        val via: String,
        val failureReason: String?,
    )

    private suspend fun executeStepWrite(
        perPolicyNewKhz: Map<Int, Int>,
        report: CapabilityReport,
        summary: String,
        deltaMhz: Int,
        rootAvailable: Boolean,
    ): StepWriteResult {
        // Path 1: direct sysfs.
        var failureReason: String? = null
        val directOk = runCatching { tryDirectSysfsWrite(perPolicyNewKhz) }.getOrElse {
            hudEventLog.add(HudEventLog.Level.ERROR, "sysfs write threw: ${it.message}")
            failureReason = it.message ?: "sysfs write threw an exception"
            false
        }
        if (directOk) return StepWriteResult(applied = true, via = "direct", failureReason = null)

        // Path 2: PServer binder.
        val pserverOk = runCatching {
            tryPServerWrite(perPolicyNewKhz, report, summary)
        }.getOrElse {
            hudEventLog.add(HudEventLog.Level.ERROR, "PServer write threw: ${it.message}")
            failureReason = it.message ?: "PServer write threw an exception"
            false
        }
        if (pserverOk) return StepWriteResult(applied = true, via = "pserver", failureReason = null)

        // Path 3: libsu root.
        if (rootAvailable) {
            val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
            val lock = adapter?.chmodLockCpuFreqWrites ?: true
            val cmds = mutableListOf<String>()
            adapter?.perfDaemonsToStopOnWrite?.forEach { cmds += "stop $it" }
            for ((pid, khz) in perPolicyNewKhz) {
                val path = cpuFreqMaxPath(pid)
                cmds += buildChmodSandwich(path, khz.toString(), lock, suppressErrors = false)
            }
            val res = com.topjohnwu.superuser.Shell.cmd(*cmds.toTypedArray()).exec()
            return if (res.isSuccess) {
                StepWriteResult(applied = true, via = "libsu", failureReason = null)
            } else {
                val err = res.err.joinToString("; ").ifBlank { "exit ${res.code}" }
                StepWriteResult(applied = false, via = "libsu", failureReason = err)
            }
        }

        // Path 4: script-per-tap fallback.
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        val preset = Preset(
            id = "hud_step_${System.currentTimeMillis()}",
            name = "HUD step (${if (deltaMhz >= 0) "+" else ""}$deltaMhz)",
            description = "Stepped: $summary MHz",
            verification = VerificationTier.USER_CUSTOM,
            cpuPolicyMaxKhz = perPolicyNewKhz,
        )
        when (val result = scriptGenerator.generate(preset, report, adapter)) {
            is ScriptGenerateResult.Ok -> scriptDeployer.deploy(preset, result.script)
            is ScriptGenerateResult.Rejected -> { /* step preset is device-derived; rejection is a no-op */ }
        }
        return StepWriteResult(applied = false, via = "script", failureReason = failureReason)
    }

    private fun tryDirectSysfsWrite(perPolicyKhz: Map<Int, Int>): Boolean {
        if (perPolicyKhz.isEmpty()) return false
        for ((policyId, khz) in perPolicyKhz) {
            val path = java.io.File(cpuFreqMaxPath(policyId))
            val outcome = runCatching {
                path.bufferedWriter().use { it.write(khz.toString()) }
            }
            if (outcome.isFailure) {
                val cause = outcome.exceptionOrNull()
                val reason = cause?.message ?: "permission denied"
                hudEventLog.add(HudEventLog.Level.WARN, "sysfs write policy$policyId failed: $reason")
                lastWriteFailureReason = "sysfs write denied on policy$policyId ($reason)"
                return false
            }
        }
        return true
    }

    private suspend fun tryPServerWrite(
        perPolicyKhz: Map<Int, Int>,
        report: CapabilityReport,
        summary: String,
    ): Boolean {
        if (perPolicyKhz.isEmpty()) return false
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        val daemons = adapter?.perfDaemonsToStopOnWrite.orEmpty()
        val lock = adapter?.chmodLockCpuFreqWrites ?: true
        val parts = mutableListOf<String>()
        daemons.forEach { parts += "stop $it 2>/dev/null" }
        for ((policyId, khz) in perPolicyKhz) {
            val path = cpuFreqMaxPath(policyId)
            parts += buildChmodSandwich(path, khz.toString(), lock, suppressErrors = true)
        }
        val command = parts.joinToString(" ; ")
        val result = pServerWriter.executeShell(command) ?: run {
            hudEventLog.add(HudEventLog.Level.WARN, "PServer binder not reachable")
            lastWriteFailureReason = "PServer unreachable (need root or Force SELinux)"
            return false
        }
        val (status, stdout) = result
        return if (status == 0) {
            true
        } else {
            hudEventLog.add(HudEventLog.Level.WARN, "PServer status=$status stdout=$stdout")
            lastWriteFailureReason = "PServer rejected write (status=$status)"
            false
        }
    }

    private fun cpuFreqMaxPath(policyId: Int): String =
        "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq"

    private fun buildChmodSandwich(
        path: String,
        value: String,
        lock: Boolean,
        suppressErrors: Boolean,
    ): List<String> {
        val redir = if (suppressErrors) " 2>/dev/null" else ""
        return buildList {
            if (lock) add("chmod 666 $path$redir")
            add("printf %s '$value' > $path")
            if (lock) add("chmod 444 $path$redir")
        }
    }

    private fun isRootAvailable(): Boolean =
        runCatching { com.topjohnwu.superuser.Shell.getCachedShell()?.isRoot == true }
            .getOrElse { false }

    fun flashActionMessage(msg: String) {
        val scope = serviceScope ?: return
        val token = flashToken.incrementAndGet()
        assembler.setActionMessage(msg)
        scope.launch(Dispatchers.Main.immediate) {
            kotlinx.coroutines.delay(6_000)
            if (flashToken.get() == token) {
                assembler.setActionMessage(null)
            }
        }
    }
}
