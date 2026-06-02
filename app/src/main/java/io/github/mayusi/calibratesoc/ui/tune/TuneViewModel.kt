package io.github.mayusi.calibratesoc.ui.tune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.PresetGenerator
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
import io.github.mayusi.calibratesoc.data.script.BootScriptReminder
import io.github.mayusi.calibratesoc.data.tunables.ApplyPathway
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 5 Tune state. Reads the capability report (for the freq tables
 * the sliders are bounded by) and the device adapter (for community
 * presets). Holds pending slider positions before the user taps Apply —
 * scrubbing a slider does NOT hit the kernel, only Apply does. This
 * keeps the "snapshot once per intentional change" property intact for
 * boot-revert.
 *
 * Apply gates on the [oneTimeOcAcknowledged] flag; the screen shows a
 * typed-confirm dialog the first time and stores the ack in DataStore.
 */
@HiltViewModel
class TuneViewModel @Inject constructor(
    private val capabilityProbe: CapabilityProbe,
    private val presetGenerator: PresetGenerator,
    private val tunableWriter: TunableWriter,
    private val profileApplier: ProfileApplier,
    private val profileRepository: ProfileRepository,
    private val userPrefs: UserPrefs,
    private val deviceAdapterRegistry: DeviceAdapterRegistry,
    private val scriptGenerator: AynScriptGenerator,
    private val scriptDeployer: AynScriptDeployer,
    private val bootReminder: BootScriptReminder,
    private val tuneHistoryStore: TuneHistoryStore,
) : ViewModel() {

    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    /** Matched device adapter for the current report. Null on generic /
     *  unknown devices. Drives the AYN_SETTINGS vendor card. Tied to
     *  the capability report's StateFlow rather than a one-shot init
     *  launch — that way the adapter updates if the report ever
     *  re-resolves to a different device key (rare, but happens on
     *  external-display dock events). */
    val adapter: StateFlow<DeviceAdapter?> = capabilityProbe.report
        .map { it?.device?.knownHandheldKey?.let(deviceAdapterRegistry::lookup) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Last one-shot script deploy result for UI confirmation. */
    private val _lastDeploy = MutableStateFlow<AynScriptDeployer.Deployed?>(null)
    val lastDeploy: StateFlow<AynScriptDeployer.Deployed?> = _lastDeploy.asStateFlow()

    /** The preset behind the last deploy — so the deploy dialog can
     *  read back the kernel and confirm it actually applied. */
    private val _lastDeployPreset = MutableStateFlow<Preset?>(null)
    val lastDeployPreset: StateFlow<Preset?> = _lastDeployPreset.asStateFlow()

    /** Last boot-service install result. Same UX as lastDeploy but
     *  rendered with a different copy explaining persistence. */
    private val _lastBootDeploy = MutableStateFlow<AynScriptDeployer.BootDeployed?>(null)
    val lastBootDeploy: StateFlow<AynScriptDeployer.BootDeployed?> = _lastBootDeploy.asStateFlow()

    val oneTimeOcAcknowledged: StateFlow<Boolean> = userPrefs.ocAcknowledged
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Pending edits keyed by policyId → (minKhz, maxKhz, governor). */
    private val _pending = MutableStateFlow<Map<Int, PolicyEdit>>(emptyMap())
    val pending: StateFlow<Map<Int, PolicyEdit>> = _pending.asStateFlow()

    private val _lastResults = MutableStateFlow<List<WriteResult>>(emptyList())
    val lastResults: StateFlow<List<WriteResult>> = _lastResults.asStateFlow()

    /** Full preset list — community + generic algorithmic — as produced
     *  by [PresetGenerator]. Re-derived on each refresh because the OPP
     *  table can change if the user enables core-online tuning (Phase 4
     *  follow-up). */
    val presets: StateFlow<List<Preset>> = MutableStateFlow(emptyList<Preset>())
        .also { sink ->
            viewModelScope.launch {
                val report = capabilityProbe.refresh()
                sink.value = presetGenerator.presetsFor(report)
            }
        }.asStateFlow()

    fun setEdit(policyId: Int, edit: PolicyEdit) {
        _pending.value = _pending.value.toMutableMap().apply { put(policyId, edit) }
    }

    fun clearPending() {
        _pending.value = emptyMap()
    }

    fun acknowledgeOc() {
        viewModelScope.launch { userPrefs.setOcAcknowledged(true) }
    }

    /** Walk pending edits and write each. Returns when all writes resolve. */
    fun apply() {
        val report = capability.value ?: return
        val edits = _pending.value
        viewModelScope.launch {
            val results = mutableListOf<WriteResult>()
            for ((policyId, edit) in edits) {
                if (edit.minKhz != null) {
                    results += tunableWriter.write(
                        id = Tunables.cpuMinFreq(policyId),
                        value = edit.minKhz.toString(),
                        report = report,
                        reason = "Tune UI",
                    )
                }
                if (edit.maxKhz != null) {
                    results += tunableWriter.write(
                        id = Tunables.cpuMaxFreq(policyId),
                        value = edit.maxKhz.toString(),
                        report = report,
                        reason = "Tune UI",
                    )
                }
                if (edit.governor != null) {
                    results += tunableWriter.write(
                        id = Tunables.cpuGovernor(policyId),
                        value = edit.governor,
                        report = report,
                        reason = "Tune UI",
                    )
                }
            }
            _lastResults.value = results
            _pending.value = emptyMap()
            // Refresh probe so the slider min/max move to reflect what
            // the kernel actually accepted (some writes clamp).
            capabilityProbe.refresh()
        }
    }

    fun applyPreset(preset: Preset) {
        val report = capability.value ?: return
        viewModelScope.launch {
            _lastResults.value = profileApplier.apply(preset, report, "Preset: ${preset.name}")
            capabilityProbe.refresh()
            recordHistory(preset, ApplyPathway.DIRECT_ROOT)
        }
    }

    private suspend fun recordHistory(preset: Preset, pathway: ApplyPathway, notes: String = "") {
        tuneHistoryStore.append(
            TuneHistoryEntry(
                appliedAtMs = System.currentTimeMillis(),
                presetName = preset.name,
                presetDescription = preset.description,
                pathway = pathway,
                notes = notes,
                cpuPolicyMaxKhz = preset.cpuPolicyMaxKhz,
                cpuPolicyMinKhz = preset.cpuPolicyMinKhz,
                cpuPolicyGovernor = preset.cpuPolicyGovernor,
                gpuMaxHz = preset.gpuMaxHz,
                gpuMinHz = preset.gpuMinHz,
                gpuGovernor = preset.gpuGovernor,
            ),
        )
    }

    /**
     * Apply a vendor preset (AYN performance_mode / fan_mode int flip).
     * Routes through TunableWriter so the snapshot+revert invariant
     * stays intact for these writes too. Returns the WriteResult so the
     * UI can render success / EACCES errors.
     */
    fun applyVendorSetting(key: String, value: String, reasonDisplay: String) {
        val report = capability.value ?: return
        val id = TunableId(kind = TunableKind.SETTINGS_SYSTEM, target = key)
        viewModelScope.launch {
            val result = tunableWriter.write(
                id = id,
                value = value,
                report = report,
                reason = "Vendor: $reasonDisplay",
            )
            _lastResults.value = listOf(result)
            tuneHistoryStore.append(
                TuneHistoryEntry(
                    appliedAtMs = System.currentTimeMillis(),
                    presetName = reasonDisplay,
                    presetDescription = "Vendor key $key = $value",
                    pathway = ApplyPathway.AYN_SETTINGS_KEY,
                ),
            )
        }
    }

    /**
     * Generate the AYN/Odin script for [preset] and drop it to disk
     * where Odin Settings' "Run script as Root" picker can find it.
     * The user invokes the script via Odin's own UI — we never elevate
     * ourselves. Result reported via [lastDeploy].
     */
    fun generateAynScript(preset: Preset) {
        val report = capability.value ?: return
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        viewModelScope.launch {
            val body = scriptGenerator.generate(preset, report, adapter)
            _lastDeploy.value = scriptDeployer.deploy(preset, body)
            _lastDeployPreset.value = preset
            recordHistory(preset, ApplyPathway.GENERATED_SCRIPT)
        }
    }

    /** Boot-install variant: same script, dropped into Magisk /
     *  KernelSU service.d so it runs at every boot. The deploy
     *  itself is root-required — failure surfaces via [lastBootDeploy]
     *  with a human-readable error string. */
    fun installScriptAsBootService(preset: Preset) {
        val report = capability.value ?: return
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        viewModelScope.launch {
            val body = scriptGenerator.generate(preset, report, adapter)
            val res = scriptDeployer.deployForBoot(preset, body)
            _lastBootDeploy.value = res
            if (res.success) recordHistory(preset, ApplyPathway.BOOT_SCRIPT_INSTALL, "manager: ${res.manager}")
        }
    }

    /** Generate the script + register a post-boot reminder so the
     *  user is nudged to re-fire it after each reboot. This is the
     *  no-root "persistent" path. Truly automatic execution still
     *  needs Magisk/KernelSU (use Install at boot for that). */
    fun generateScriptWithReminder(preset: Preset) {
        val report = capability.value ?: return
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        viewModelScope.launch {
            val body = scriptGenerator.generate(preset, report, adapter)
            _lastDeploy.value = scriptDeployer.deploy(preset, body)
            _lastDeployPreset.value = preset
            bootReminder.register(preset)
            recordHistory(preset, ApplyPathway.BOOT_REMINDER_REGISTERED)
        }
    }

    fun clearLastDeploy() { _lastDeploy.value = null }
    fun clearLastBootDeploy() { _lastBootDeploy.value = null }

    /** Result of reading back the kernel after the user runs a script. */
    private val _verifyResult = MutableStateFlow<VerifyResult?>(null)
    val verifyResult: StateFlow<VerifyResult?> = _verifyResult.asStateFlow()
    fun clearVerifyResult() { _verifyResult.value = null }

    data class PolicyVerify(
        val policyId: Int,
        val wantMaxKhz: Int?,
        val gotMaxKhz: Int?,
        val ok: Boolean,
        val readable: Boolean,
    )

    data class VerifyResult(
        val presetName: String,
        val policies: List<PolicyVerify>,
    ) {
        val allOk: Boolean get() = policies.isNotEmpty() && policies.all { it.readable && it.ok }
        val anyChecked: Boolean get() = policies.any { it.wantMaxKhz != null }
        val anyMismatch: Boolean get() = policies.any { it.readable && !it.ok }
        val anyUnreadable: Boolean get() = policies.any { !it.readable }
        val readableOk: Boolean get() = policies.any { it.readable && it.ok }
        val allUnreadable: Boolean get() = policies.isNotEmpty() && policies.all { !it.readable }
    }

    /**
     * After the user runs the generated script via their vendor "Run
     * script as Root" runner, read back each policy's actual
     * scaling_max_freq and compare it to what the preset asked for.
     * This is the definitive "did it actually work" check — the app's
     * UID can READ these files even when it can't write them, so we get
     * ground truth straight from the kernel. A mismatch means perfd
     * clamped it back or the script didn't run.
     *
     * Tolerance: kernels snap to the nearest OPP, so we accept the
     * readback if it's within 1 OPP step (~5%) of the requested value.
     */
    fun verifyApplied(preset: Preset) {
        viewModelScope.launch {
            val checks = preset.cpuPolicyMaxKhz.toSortedMap().map { (policyId, wantKhz) ->
                val path = java.io.File(
                    "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq",
                )
                val gotKhz = runCatching { path.readText().trim().toInt() }.getOrNull()
                // Within 5% counts as a match (OPP snapping).
                val ok = gotKhz != null &&
                    kotlin.math.abs(gotKhz - wantKhz) <= (wantKhz * 0.05).toInt()
                PolicyVerify(policyId, wantKhz, gotKhz, ok, readable = gotKhz != null)
            }
            _verifyResult.value = VerifyResult(preset.name, checks)
        }
    }

    /**
     * Snapshot the pending edits AND the current kernel state for any
     * policy NOT pending-edited into a UserProfile. This is the
     * "Save as profile" affordance — captures everything the user can
     * see on the Tune screen RIGHT NOW so the saved profile reproduces
     * the visible state, not a partial slider position.
     */
    fun saveAsProfile(name: String, description: String, applyOnBoot: Boolean) {
        val report = capability.value ?: return
        val edits = _pending.value
        val maxes = mutableMapOf<Int, Int>()
        val mins = mutableMapOf<Int, Int>()
        val govs = mutableMapOf<Int, String>()
        for (policy in report.cpuPolicies) {
            val edit = edits[policy.policyId]
            maxes[policy.policyId] = edit?.maxKhz ?: policy.currentMaxKhz
            mins[policy.policyId] = edit?.minKhz ?: policy.currentMinKhz
            (edit?.governor ?: policy.currentGovernor).takeIf { it.isNotBlank() }?.let {
                govs[policy.policyId] = it
            }
        }
        val asPreset = Preset(
            id = "user_${System.currentTimeMillis()}",
            name = name,
            description = description,
            verification = VerificationTier.USER_CUSTOM,
            cpuPolicyMaxKhz = maxes,
            cpuPolicyMinKhz = mins,
            cpuPolicyGovernor = govs,
        )
        viewModelScope.launch {
            profileRepository.saveProfile(UserProfile.fromPreset(asPreset, applyOnBoot))
        }
    }

    data class PolicyEdit(
        val minKhz: Int? = null,
        val maxKhz: Int? = null,
        val governor: String? = null,
    )
}
