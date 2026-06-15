package io.github.mayusi.calibratesoc.ui.overlay

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
import io.github.mayusi.calibratesoc.data.script.ScriptGenerateResult
import io.github.mayusi.calibratesoc.data.tunables.ApplyPathway
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import io.github.mayusi.calibratesoc.data.vendor.OdinIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Handles profile apply and flashActionMessage for the HUD.
 * The manual clock stepper (stepBigCoreMhz) has been removed; AutoTDP is
 * the sole clock-management path from the overlay.
 *
 * Kept responsibilities:
 *  - [cycleNextProfile]: cycle through saved quick-profile chips.
 *  - [applyProfileViaScript]: write a profile script on chip tap.
 *  - [flashActionMessage]: atomic-token timed action feedback.
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
    private val hudEventLog: HudEventLog,
    private val profileRepository: ProfileRepository,
) {
    // Injected by OverlayService after construction (avoids circular dep).
    lateinit var assembler: HudStateAssembler
    private var serviceScope: CoroutineScope? = null

    private val flashToken = AtomicLong(0L)

    fun bind(scope: CoroutineScope) {
        serviceScope = scope
    }

    // ── Public entry points called from OverlayService ────────────────────────

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
                // cluster). Tell the user why instead of silently doing nothing.
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
                "Wrote ${profile.name} -> /sdcard/CalibrateSoC. Open $vsName -> Run as Root."
            } else {
                "Wrote ${profile.name} to app-private storage. Move it to /sdcard to run it."
            }
            // Use the same atomic-token flash path so rapid taps cannot leave a stale message.
            flashActionMessage(deployedMsg)
        }
    }

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
