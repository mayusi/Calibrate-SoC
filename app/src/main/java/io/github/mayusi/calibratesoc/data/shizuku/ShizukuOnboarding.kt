package io.github.mayusi.calibratesoc.data.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects and exposes the current Shizuku onboarding state as a [StateFlow].
 *
 * Why no-PC wireless debugging:
 *   On Android 11+, the "Wireless debugging" developer option allows pairing a
 *   device to itself using an on-screen QR code / pairing code — NO computer
 *   required. Shizuku 12.3+ can self-start via this mechanism. The user enables
 *   Developer Options, enables Wireless debugging, opens the "Pair device with
 *   pairing code" dialog, then taps Start in Shizuku. The entire flow happens
 *   on-device with no PC cable.
 *
 * States — emitted in progression order:
 *   NOT_INSTALLED      → app is not installed; link to Play / F-Droid / GitHub
 *   INSTALLED_STOPPED  → app is installed but service is not running;
 *                        guide user through wireless debugging pair + Start
 *   RUNNING_NO_PERMISSION → Shizuku is running but we haven't asked for permission;
 *                        prompt the in-app permission request dialog
 *   GRANTED            → fully operational; probes can proceed
 *   GRANTED_NO_WRITES  → granted, but ALL probed nodes returned denied;
 *                        vendor SELinux blocks shell writes on this device
 *
 * Android 13+ auto-start on trusted Wi-Fi: Shizuku exposes a "Start on boot
 * via wireless debugging" setting. We detect the API-level and surface a
 * guideline step when available (API 33+). The actual setting is in Shizuku's
 * own UI; we just tell the user it exists and point them there.
 *
 * The UI agent observes [state] and renders the appropriate guide card.
 * No UI code lives here — this class is pure state + detection.
 */
@Singleton
class ShizukuOnboarding @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(computeState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /**
     * Re-evaluate the onboarding state. Call this:
     *   - On app resume (Activity.onResume)
     *   - After the user grants/denies the Shizuku permission dialog
     *   - After the probe cache updates (to detect GRANTED_NO_WRITES)
     */
    fun refresh(probedWritableCount: Int = -1) {
        _state.value = computeState(probedWritableCount)
    }

    // ── State computation ─────────────────────────────────────────────────────

    private fun computeState(probedWritableCount: Int = -1): OnboardingState {
        if (!isShizukuInstalled()) return OnboardingState.NOT_INSTALLED

        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!running) return OnboardingState.INSTALLED_STOPPED

        val granted = try {
            !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (!granted) return OnboardingState.RUNNING_NO_PERMISSION

        // Shizuku is granted. Did the node probes run yet?
        if (probedWritableCount == 0) return OnboardingState.GRANTED_NO_WRITES
        return OnboardingState.GRANTED
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // ── Guidance helpers (consumed by the UI layer) ───────────────────────────

    /**
     * Whether the device supports the on-device wireless debugging pairing
     * flow (Android 11 / API 30+). Below API 30, the user needs a PC cable.
     */
    val supportsOnDeviceWirelessPairing: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Whether the device supports Shizuku's "start on boot via wireless
     * debugging" auto-start feature (Android 13 / API 33+).
     */
    val supportsAutoStartOnTrustedWifi: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Step-by-step guide for the on-device wireless debugging setup.
     * Each [GuideStep] is a user-visible instruction the UI can render.
     *
     * The steps assume the device is on Android 11+ (checked by
     * [supportsOnDeviceWirelessPairing] before showing this guide).
     */
    val wirelessPairingSteps: List<GuideStep> = buildList {
        add(GuideStep(
            title = "Enable Developer Options",
            detail = "Go to Settings → About phone → tap Build number 7 times.",
        ))
        add(GuideStep(
            title = "Enable Wireless debugging",
            detail = "Settings → Developer options → Wireless debugging → toggle ON.",
        ))
        add(GuideStep(
            title = "Open Shizuku",
            detail = "Tap 'Pairing code' inside Shizuku, then tap the 'Start via wireless debugging' button.",
        ))
        add(GuideStep(
            title = "Enter pairing code",
            detail = "In Developer options → Wireless debugging → 'Pair device with pairing code' — enter the code shown in Shizuku.",
        ))
        add(GuideStep(
            title = "Grant permission",
            detail = "Return to Calibrate SoC and tap 'Grant Shizuku permission'.",
        ))
        if (supportsAutoStartOnTrustedWifi) {
            add(GuideStep(
                title = "Optional: auto-start on trusted Wi-Fi",
                detail = "In Shizuku settings, enable 'Start on boot via wireless debugging' so Shizuku restarts automatically when you're on your home Wi-Fi.",
            ))
        }
    }

    /** Request the Shizuku permission dialog. Call from an Activity context. */
    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ShizukuOnboarding"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST_CODE = 0x5A17 // "SHI7" mnemonic
    }
}

/**
 * The current Shizuku onboarding / capability state.
 *
 * Progression: NOT_INSTALLED → INSTALLED_STOPPED → RUNNING_NO_PERMISSION
 *              → GRANTED (happy path) or GRANTED_NO_WRITES (vendor denial).
 */
enum class OnboardingState {
    /**
     * Shizuku app is not installed on this device.
     * UI: show install link + explain what Shizuku is.
     */
    NOT_INSTALLED,

    /**
     * Shizuku is installed but the background service is not running.
     * UI: show the on-device wireless debugging pairing guide.
     */
    INSTALLED_STOPPED,

    /**
     * Shizuku service is running, but this app has not been granted permission.
     * UI: show "Grant permission" button that calls [ShizukuOnboarding.requestPermission].
     */
    RUNNING_NO_PERMISSION,

    /**
     * Shizuku is granted and at least one sysfs node passed the write probe.
     * UI: show live controls for the probed-writable nodes only.
     */
    GRANTED,

    /**
     * Shizuku is granted BUT all probed sysfs nodes returned DENIED.
     * This device's vendor SELinux policy blocks shell writes to kernel tunables.
     * UI: honest disclosure — "Shizuku connected but this kernel blocks shell writes
     *     to CPU/GPU nodes. Monitoring + vendor settings only."
     */
    GRANTED_NO_WRITES,
}

/** One step in the wireless-debugging pairing guide. */
data class GuideStep(
    val title: String,
    val detail: String,
)
