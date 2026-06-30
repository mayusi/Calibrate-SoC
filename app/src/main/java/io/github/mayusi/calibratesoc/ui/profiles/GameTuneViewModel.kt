package io.github.mayusi.calibratesoc.ui.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.backup.BackupManager
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.remote.RemoteContentRepository
import io.github.mayusi.calibratesoc.data.share.GameTuneDecodeResult
import io.github.mayusi.calibratesoc.data.share.GameTuneShareCodec
import io.github.mayusi.calibratesoc.data.share.ShareableGameTune
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Game Tune share/import/community screen.
 *
 * Manages three flows:
 *  1. Share — encode a game's full tune config into a CSOC2 code.
 *  2. Import — decode a pasted CSOC2 code, preview, validate, persist.
 *  3. Community — load OTA-fetched community tunes from [RemoteContentRepository].
 *
 * NOTE: AppModule must provide [GameTuneShareCodec] — mirror the existing
 * providePresetShareCodec pattern:
 *
 *   @Provides @Singleton
 *   fun provideGameTuneShareCodec(json: Json, base64: Base64Encoder): GameTuneShareCodec =
 *       GameTuneShareCodec(json, base64)
 */
@HiltViewModel
class GameTuneViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameTuneCodec: GameTuneShareCodec,
    private val profileRepository: ProfileRepository,
    private val remoteContentRepository: RemoteContentRepository,
    private val backupManager: BackupManager,
) : ViewModel() {

    // ─── Share code ────────────────────────────────────────────────────────────

    private val _shareCode = MutableStateFlow<String?>(null)
    val shareCode: StateFlow<String?> = _shareCode.asStateFlow()

    // ─── Import state ──────────────────────────────────────────────────────────

    private val _importState = MutableStateFlow<GameTuneImportState>(GameTuneImportState.Idle)
    val importState: StateFlow<GameTuneImportState> = _importState.asStateFlow()

    // ─── Community tunes ───────────────────────────────────────────────────────
    //
    // RemoteContentValidator is internal to the data layer, so we project it to
    // the public [CommunityTuneUiModel] before crossing the ViewModel boundary.

    private val _communityTunes = MutableStateFlow<List<CommunityTuneUiModel>>(emptyList())
    val communityTunes: StateFlow<List<CommunityTuneUiModel>> = _communityTunes.asStateFlow()

    private val _communityLoading = MutableStateFlow(false)
    val communityLoading: StateFlow<Boolean> = _communityLoading.asStateFlow()

    // ─── Share ─────────────────────────────────────────────────────────────────

    /**
     * Generate a CSOC2 share code for the given game + bundle + profile.
     * Populates [shareCode] on success; clears it if encoding fails.
     */
    fun generateShareCode(
        packageName: String,
        gameDisplayName: String,
        bundle: PerAppBundle,
        profile: UserProfile?,
    ) {
        val code = runCatching {
            gameTuneCodec.encode(
                packageName = packageName,
                gameDisplayName = gameDisplayName,
                bundle = bundle,
                profile = profile,
            )
        }.getOrElse {
            _shareCode.value = null
            return
        }
        _shareCode.value = code
    }

    /** Clear the share code (e.g. when the user dismisses the share sheet). */
    fun clearShareCode() {
        _shareCode.value = null
    }

    // ─── Import ────────────────────────────────────────────────────────────────

    /**
     * Attempt to decode [code] as a CSOC2 game tune share code.
     *
     *  - Blank input → [GameTuneImportState.Idle]
     *  - Decode error → [GameTuneImportState.Error]
     *  - Success → [GameTuneImportState.Preview]
     *
     * Device-mismatch logic (now wired):
     *  [currentDeviceKey] is the running device's
     *  [io.github.mayusi.calibratesoc.data.capability.DeviceIdentity.knownHandheldKey]
     *  (passed in by the screen from the capability report). A tune is a
     *  mismatch when it declares a non-empty device-targeting list
     *  ([ShareableGameTune.targetHandheldKeys]) that does NOT include this
     *  device — i.e. it was built for different silicon. This is the SAME
     *  predicate [io.github.mayusi.calibratesoc.data.profiles.PresetSafetyGate]
     *  Gate 1 enforces at apply time, surfaced early here as a warning so the
     *  user sees it before import (the warning informs, it does not block —
     *  the gate still blocks the actual write).
     *
     *  Honesty: an untargeted tune (targetHandheldKeys null/empty) is NOT a
     *  mismatch — it applies anywhere. A null [currentDeviceKey] (device key
     *  not yet probed / unknown device) is treated as a mismatch ONLY when the
     *  tune is targeted, since we cannot prove the unknown device is in the
     *  target set — matching the gate's "unknown current key fails a targeted
     *  preset" behaviour.
     */
    fun decodeImportCode(code: String, currentDeviceKey: String? = null) {
        if (code.isBlank()) {
            _importState.value = GameTuneImportState.Idle
            return
        }
        when (val result = gameTuneCodec.decode(code)) {
            is GameTuneDecodeResult.Success -> {
                val tune = result.tune
                val deviceMismatch = isDeviceMismatch(tune.targetHandheldKeys, currentDeviceKey)
                _importState.value = GameTuneImportState.Preview(tune, deviceMismatch)
            }
            is GameTuneDecodeResult.Error -> {
                _importState.value = GameTuneImportState.Error(result.reason)
            }
            is GameTuneDecodeResult.ValidationError -> {
                // GameTuneShareCodec.decode() folds sysfs-path validation failures into
                // GameTuneDecodeResult.Error (the validateImport() call at the end of decode()
                // returns a plain error string). GameTuneDecodeResult.ValidationError is
                // retained in the sealed class for future use (e.g. a per-field streaming
                // validator) but is never emitted by the current codec. Surface it as an Error.
                _importState.value = GameTuneImportState.Error(
                    "Validation failed — ${result.path}: ${result.reason}",
                )
            }
        }
    }

    /**
     * Validate then persist the previewed [tune] as a new profile + bundle pair.
     *
     * Steps:
     *  1. Build a [UserProfile] from the tune's clock fields.
     *  2. Run [BackupManager.validateProfile] — defence-in-depth for hostile codes.
     *  3. Build a [PerAppBundle] wiring the new profile to the game's package name.
     *  4. Persist profile + bundle atomically (both repository calls happen in the
     *     same coroutine; if saveProfile throws the bundle write is skipped).
     *
     * [onResult] is called on the main dispatcher with null on success, or a
     * human-readable error string on any failure.
     */
    fun confirmImport(
        tune: ShareableGameTune,
        currentDeviceKey: String?,
        onResult: (error: String?) -> Unit,
    ) {
        viewModelScope.launch {
            // ── 0. Device-targeting guard ────────────────────────────────────
            // Refuse to persist a tune built for a different device family. The
            // preview already WARNED about this (deviceMismatch), but a user can
            // tap Import anyway; this is the honest hard stop. Same predicate as
            // the preview + PresetSafetyGate Gate 1. An untargeted tune passes.
            if (isDeviceMismatch(tune.targetHandheldKeys, currentDeviceKey)) {
                val target = tune.targetHandheldKeys?.joinToString(", ").orEmpty()
                onResult(
                    "This tune targets [$target] and was not built for your device " +
                        "[${currentDeviceKey ?: "unknown"}]. Import a tune made for your device.",
                )
                return@launch
            }

            // ── 1. Reconstruct a UserProfile from tune clock fields ──────────
            val profile = UserProfile(
                id = "shared_game_${tune.packageName}_${System.currentTimeMillis()}",
                name = tune.name,
                description = tune.description,
                cpuPolicyMaxKhz = tune.cpuPolicyMaxKhz,
                cpuPolicyMinKhz = tune.cpuPolicyMinKhz,
                cpuPolicyGovernor = tune.cpuPolicyGovernor,
                gpuMaxHz = tune.gpuMaxHz,
                gpuMinHz = tune.gpuMinHz,
                gpuGovernor = tune.gpuGovernor,
                extraSysfs = tune.extraSysfs,
                // Shared tunes must never auto-apply — user explicitly applies.
                applyOnBoot = false,
                createdAtMs = System.currentTimeMillis(),
                targetHandheldKeys = tune.targetHandheldKeys,
            )

            // ── 2. Validate via BackupManager (same defence used for preset import) ──
            val validationError = backupManager.validateProfile(profile)
            if (validationError != null) {
                onResult("Cannot import: $validationError")
                return@launch
            }

            // ── 3. Build the PerAppBundle for this game ─────────────────────
            val bundle = PerAppBundle(
                profileId = profile.id,
                autoTdpGoal = tune.autoTdpGoal,
                refreshRateHz = tune.refreshRateHz,
                fanMode = tune.fanMode,
                gameBoostOnLaunch = tune.gameBoostOnLaunch,
                autoCreated = false,
            )

            // ── 4. Persist both ─────────────────────────────────────────────
            runCatching {
                profileRepository.saveProfile(profile)
                profileRepository.setBundle(tune.packageName, bundle)
            }
                .onSuccess { onResult(null) }
                .onFailure { onResult("Failed to save game tune: ${it.message}") }
        }
    }

    /** Reset the import state back to Idle (user cancelled the preview). */
    fun dismissImport() {
        _importState.value = GameTuneImportState.Idle
    }

    // ─── Community tunes ───────────────────────────────────────────────────────

    /**
     * Populate [communityTunes] from the local cache (no network).
     * Optionally filters to tunes that match [deviceKey] (or target any device).
     * Call this after [refreshCommunityTunes] settles, or on first composition.
     */
    fun loadCommunityTunes(deviceKey: String?) {
        val all = remoteContentRepository.communityTunes()
        val filtered = if (deviceKey.isNullOrBlank()) {
            all
        } else {
            all.filter { tune ->
                tune.targetDeviceKeys.isEmpty() || tune.targetDeviceKeys.contains(deviceKey)
            }
        }
        _communityTunes.value = filtered.map { tune ->
            CommunityTuneUiModel(
                tuneCode = tune.tuneCode,
                gameDisplayName = tune.gameDisplayName,
                packageName = tune.packageName,
                authorHandle = tune.authorHandle,
                targetDeviceKeys = tune.targetDeviceKeys,
                notes = tune.notes,
            )
        }
    }

    /**
     * Trigger a network refresh then reload the local cache into [communityTunes].
     * Updates [communityLoading] around the fetch. Fail-open: if the network
     * request fails we keep whatever was previously cached.
     */
    fun refreshCommunityTunes(deviceKey: String?) {
        viewModelScope.launch {
            _communityLoading.value = true
            runCatching { remoteContentRepository.refresh() }
            loadCommunityTunes(deviceKey)
            _communityLoading.value = false
        }
    }

    companion object {
        /**
         * Pure device-targeting predicate. Returns true when [targetHandheldKeys]
         * declares a non-empty target list that does NOT include [currentDeviceKey]
         * (the running device is foreign to the tune). Mirrors
         * [io.github.mayusi.calibratesoc.data.profiles.PresetSafetyGate] Gate 1:
         *
         *  - null / empty targets  → false (untargeted tune applies anywhere).
         *  - non-empty targets, currentDeviceKey null  → true (cannot prove the
         *    unknown device is in the target set — fail safe, same as the gate).
         *  - non-empty targets, key present  → !targets.contains(key).
         *
         * Extracted as a pure static helper so it is unit-testable on the JVM
         * without constructing the (Android-dependent) ViewModel.
         */
        @JvmStatic
        fun isDeviceMismatch(targetHandheldKeys: List<String>?, currentDeviceKey: String?): Boolean {
            if (targetHandheldKeys.isNullOrEmpty()) return false
            return currentDeviceKey == null || currentDeviceKey !in targetHandheldKeys
        }
    }
}

// ─── Public UI model for community tunes ──────────────────────────────────────

/**
 * Public projection of [RemoteContentValidator.CommunityGameTune] (which is
 * internal to the data layer) for use in the UI layer. Fields mirror the source
 * type 1-to-1; mapping happens in [GameTuneViewModel.loadCommunityTunes].
 */
data class CommunityTuneUiModel(
    val tuneCode: String,
    val gameDisplayName: String,
    val packageName: String,
    val authorHandle: String = "",
    val targetDeviceKeys: List<String> = emptyList(),
    val notes: String = "",
)

// ─── Import dialog state ───────────────────────────────────────────────────────

/** States of the import flow in [GameTuneViewModel]. */
sealed class GameTuneImportState {
    /** No import in progress — the paste field is empty or has been dismissed. */
    object Idle : GameTuneImportState()

    /**
     * A valid code has been decoded. Show the preview card so the user can
     * inspect the tune before accepting it.
     *
     * [deviceMismatch] is true when [tune.targetHandheldKeys] targets a different
     * device family and should trigger a warning (not a block).
     */
    data class Preview(
        val tune: ShareableGameTune,
        val deviceMismatch: Boolean,
    ) : GameTuneImportState()

    /**
     * The code failed to decode or failed field-level validation inside the codec.
     * [GameTuneShareCodec] folds both decode failures and sysfs-path validation
     * rejections into [GameTuneDecodeResult.Error] with a user-facing [reason].
     */
    data class Error(val reason: String) : GameTuneImportState()

    /**
     * A specific field in the decoded tune failed schema/range validation.
     * [path] is a dot-path like "cpuPolicyMaxKhz.policy0"; [reason] is user-facing.
     * Maps from [GameTuneDecodeResult.ValidationError].
     */
    data class ValidationError(val path: String, val reason: String) : GameTuneImportState()
}
