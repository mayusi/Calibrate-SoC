package io.github.mayusi.calibratesoc.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.shizuku.OnboardingState
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuOnboarding
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin VM for the onboarding wizard. Flips the onboarding-complete flag in
 * UserPrefs when the user finishes or explicitly skips, and surfaces the two
 * orphaned grant paths the universal onboarding needs to wire WITHOUT
 * reimplementing them:
 *   - the ROOT opt-in ([UserPrefs.setRootModeEnabled]) so a Magisk/KernelSU user
 *     can enable root mode from inside onboarding and reach the ROOT tier; and
 *   - the EXISTING [ShizukuOnboarding] state machine + permission request, so the
 *     Shizuku step drives the real flow (install → pair → grant → per-node probe).
 *
 * Everything else (live perm probes, system launches) is done synchronously on
 * the UI thread inside the composable because they're cheap and don't need a
 * state holder.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userPrefs: UserPrefs,
    private val shizukuOnboarding: ShizukuOnboarding,
) : ViewModel() {
    fun markComplete() {
        viewModelScope.launch { userPrefs.setOnboardingComplete(true) }
    }

    /**
     * Record whether the user confirmed the "Skip — read-only" scary dialog
     * on an applicable (AYN/vendor-runner) device.
     *
     * Pass true when the user confirms the full-screen ScarySkipDialog.
     * Pass false when they complete the advanced setup (so gated-feature
     * re-surface logic knows they did it properly).
     */
    fun setAdvancedSetupSkipped(skipped: Boolean) {
        viewModelScope.launch { userPrefs.setAdvancedSetupSkipped(skipped) }
    }

    // ── ROOT opt-in (surfaced into onboarding so a rooted device reaches ROOT) ──

    /** Live mirror of the root-mode opt-in. The ROOT step shows whether the user
     *  has flipped it; once true (and root is present) the capability probe
     *  re-classifies the device as the ROOT tier on its next refresh. */
    val rootModeEnabled: StateFlow<Boolean> = userPrefs.rootModeEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Enable (or disable) root mode from onboarding. Reuses the SAME
     * [UserPrefs.setRootModeEnabled] opt-in the Settings screen uses — we only
     * SURFACE it here, we don't add a new mechanism. After this flips true on a
     * device with root present, the capability probe promotes the device to
     * [io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.ROOT].
     */
    fun setRootModeEnabled(enabled: Boolean) {
        viewModelScope.launch { userPrefs.setRootModeEnabled(enabled) }
    }

    // ── Shizuku onboarding (surfacing the EXISTING state machine, not rebuilt) ──

    /** The EXISTING [ShizukuOnboarding] state machine, observed by the Shizuku
     *  onboarding step. Drives the wireless-debugging guide / "Grant permission"
     *  button / per-node probe result incl. the honest GRANTED_NO_WRITES state. */
    val shizukuState: StateFlow<OnboardingState> = shizukuOnboarding.state

    /** True on Android 11+ — the on-device wireless-debugging pairing flow works
     *  with no PC cable. Surfaced so the step shows the right guide. */
    val shizukuSupportsOnDeviceWirelessPairing: Boolean
        get() = shizukuOnboarding.supportsOnDeviceWirelessPairing

    /** The step-by-step wireless-debugging guide from the existing machine. */
    val shizukuWirelessPairingSteps get() = shizukuOnboarding.wirelessPairingSteps

    /** Re-evaluate the Shizuku onboarding state (call on resume / after a grant).
     *  Pass the probed-writable node count to surface GRANTED_NO_WRITES honestly. */
    fun refreshShizuku(probedWritableCount: Int = -1) {
        shizukuOnboarding.refresh(probedWritableCount)
    }

    /** Fire the EXISTING Shizuku permission dialog. Reuses
     *  [ShizukuOnboarding.requestPermission] — no new permission mechanism. */
    fun requestShizukuPermission() {
        shizukuOnboarding.requestPermission(ShizukuOnboarding.PERMISSION_REQUEST_CODE)
    }
}
