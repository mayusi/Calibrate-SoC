package io.github.mayusi.calibratesoc.data.advisory

import android.app.GameManager
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standard Android no-root power hints — the ONLY real power levers available
 * to a fully-stock app with no root, no Shizuku, no vendor unlock.
 *
 * HONESTY FRAMEWORK (non-negotiable):
 *  - Every hint is advisory. The OS may or may not honor it.
 *  - [isSupported] gates each hint: the UI MUST hide any toggle whose hint
 *    is not supported on this device/API level.
 *  - [apply] returns [HintResult] which distinguishes APPLIED (hint sent),
 *    UNSUPPORTED (API not available), and REJECTED (OS returned false/threw).
 *  - We NEVER claim a hint "set the clock" or "enforced a cap". Strings say
 *    "hint sent — the OS may or may not honor this".
 *  - [clear] reverts each hint to its neutral/default state.
 *
 * ── What each API actually does (research summary) ────────────────────────
 *
 * 1. PerformanceHintManager (ADPF) — API 31+ (Android 12+)
 *    Creates a "hint session" bound to a set of thread TIDs. The app reports
 *    actual work durations (targetDurationNs + reportActualWorkDuration).
 *    The OS uses these reports to bias the CPU governor toward efficiency when
 *    the app consistently finishes early (headroom available) or toward
 *    performance when it is consistently late. This is a cooperative signal —
 *    the kernel may ignore it or override it for thermal reasons.
 *    Usage: create a session at startup, call reportActualWorkDuration after
 *    each frame or work unit. We use a background-telemetry work period of
 *    ~1000 ms (1 Hz) and report the actual sample tick duration.
 *    Constraint: session is tied to the CALLING app's threads only. We cannot
 *    send ADPF hints for another process (e.g. the game the user is playing).
 *    Effect: biases OUR process's scheduler priority — modest effect in practice
 *    since we are a background monitoring service, not the foreground game.
 *
 * 2. GameManager.setGameMode (API 33+ / Android 13+)
 *    Constraint (CRITICAL — do not fake this):
 *    GameManager.setGameMode() can ONLY be called by the game itself (from
 *    within the game's own process), OR by the platform game dashboard service
 *    (android.service.games.GameService). A third-party app calling
 *    setGameMode for ANOTHER package throws SecurityException. There is no
 *    "request game mode on behalf of the foreground game" API for non-system
 *    apps. We CANNOT call setGameMode for games the user is playing.
 *    What we CAN do: call setGameMode for our OWN package — but since we are
 *    not a game (no <category android:name="android.intent.category.GAME"/>
 *    in our manifest), calling setGameMode on ourselves has no meaningful
 *    effect on the foreground game's power budget.
 *    THEREFORE: isGameModeSupported() returns false and we surface the true
 *    constraint to the user as an explanation, not a toggle. We DO surface
 *    the user-facing guidance: "tell the game to use Battery mode in its own
 *    settings, or use your device's Game Dashboard if it supports it."
 *
 * 3. PowerManager.isSustainedPerformanceModeSupported + Window.setSustainedPerformanceMode
 *    (API 24+)
 *    IMPORTANT CONSTRAINT: setSustainedPerformanceMode() is a method on
 *    android.view.Window, NOT on PowerManager. It can only be called from an
 *    Activity's Window — not from a Service or background context. A monitoring
 *    service such as this one cannot directly toggle it.
 *    PowerManager.isSustainedPerformanceModeSupported() CAN be called from any
 *    context to query support (it is a read-only check).
 *    THEREFORE: isSustainedPerformanceModeSupported() is exposed for the UI to
 *    check and display informational guidance, but the actual toggle must be
 *    driven from an Activity context in the UI layer. We expose isSupported and
 *    a "call from Activity" instruction rather than a fake toggle that does nothing.
 *    On most stock devices isSustainedPerformanceModeSupported() returns false.
 *
 * 4. ADPF setMode (API 35+)
 *    PowerManager.setMode(PowerManager.MODE_DEVICE_IDLE, true) is NOT a public
 *    no-root API. The only public ADPF setMode is the HintSession work
 *    duration reporting already covered above.
 */
@Singleton
class PowerHintController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val TAG = "PowerHintController"

    // ── PerformanceHintManager (ADPF) ─────────────────────────────────────────

    private var hintSession: PerformanceHintManager.Session? = null
    private var hintManager: PerformanceHintManager? = null

    /** True when PerformanceHintManager is available on this device (API 31+). */
    val isAdpfSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.getSystemService(PerformanceHintManager::class.java) != null

    /**
     * Opens an ADPF hint session for background telemetry work.
     *
     * The session reports our monitoring tick as a "work unit". This biases
     * the scheduler toward efficiency when we have headroom (sampling finishes
     * early) or toward responsiveness when we are late. The OS may or may not
     * honor this hint.
     *
     * Note: this session covers OUR process threads, not a foreground game.
     * The effect on game performance/battery is indirect at best.
     *
     * @param targetWorkDurationNs  Target per-tick duration in nanoseconds.
     *        For a 1 Hz monitor tick this would be ~1_000_000_000L (1 s).
     * @return [HintResult.APPLIED] if session created, [HintResult.UNSUPPORTED]
     *         if API is not available, [HintResult.REJECTED] on error.
     */
    fun openAdpfSession(
        threadIds: IntArray = intArrayOf(android.os.Process.myTid()),
        targetWorkDurationNs: Long = 1_000_000_000L,
    ): HintResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return HintResult.UNSUPPORTED("PerformanceHintManager requires API 31+ (Android 12)")
        }
        return try {
            val mgr = context.getSystemService(PerformanceHintManager::class.java)
                ?: return HintResult.UNSUPPORTED("PerformanceHintManager system service not found")
            hintManager = mgr
            hintSession?.close()
            hintSession = mgr.createHintSession(threadIds, targetWorkDurationNs)
            if (hintSession != null) {
                Log.i(TAG, "ADPF hint session opened (target=${targetWorkDurationNs}ns) — advisory only")
                HintResult.APPLIED("ADPF hint session opened. The OS may use this to bias " +
                    "our scheduler priority toward efficiency — it is advisory and may be ignored.")
            } else {
                HintResult.REJECTED("PerformanceHintManager.createHintSession returned null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ADPF session open failed: ${e.message}")
            HintResult.REJECTED("ADPF hint session failed: ${e.message}")
        }
    }

    /**
     * Reports the actual work duration for the most recent monitoring tick.
     * This is the key ADPF feedback loop: if we consistently finish early,
     * the OS may reduce our scheduler priority (saving power); if late, it
     * may raise it. The OS is free to ignore this entirely.
     *
     * Must only be called after [openAdpfSession] returns [HintResult.APPLIED].
     *
     * @param actualWorkDurationNs  How long the tick actually took, in nanoseconds.
     */
    fun reportAdpfWorkDuration(actualWorkDurationNs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            hintSession?.reportActualWorkDuration(actualWorkDurationNs)
        } catch (e: Exception) {
            Log.w(TAG, "ADPF reportActualWorkDuration failed: ${e.message}")
        }
    }

    /** Close the ADPF hint session when advisory mode is stopped. */
    fun closeAdpfSession() {
        try {
            hintSession?.close()
        } catch (_: Exception) {}
        hintSession = null
        hintManager = null
        Log.i(TAG, "ADPF hint session closed")
    }

    // ── GameManager.setGameMode ────────────────────────────────────────────────

    /**
     * Whether the GameManager battery-mode hint is legitimately callable by
     * this app for a meaningful effect.
     *
     * ALWAYS FALSE — see class-level kdoc. GameManager.setGameMode() for
     * another package requires a system/game-service signature. We cannot
     * set game mode on the foreground game the user is playing. Calling it
     * on our own package (which is not declared as a game) has no meaningful
     * effect on the game's power budget.
     *
     * This property exists to gate the UI toggle so we never surface a
     * "Set Battery Game Mode" button that does nothing.
     */
    val isGameModeSupported: Boolean
        get() = false // See class kdoc — not legitimately callable for a third-party game.

    /**
     * Human-readable explanation of why game mode is not directly available.
     * The UI should surface this as an informational note, not a disabled toggle.
     */
    val gameModeConstraintExplanation: String
        get() = "Battery Game Mode (GameManager) can only be set by the game itself or " +
            "the system's Game Dashboard service — not by a third-party app for another process. " +
            "To use it: open your device's Game Dashboard or the game's own settings and select " +
            "Battery / Quality mode. Some OEM game assistants (AYN, Retroid) expose this " +
            "as a vendor Settings key — Calibrate SoC can write those where supported."

    // ── Window.setSustainedPerformanceMode (query only from service context) ──────

    /**
     * True when this device's PowerManager reports sustained performance mode
     * as supported. Read-only check safe to call from any context.
     *
     * CONSTRAINT: The actual toggle (Window.setSustainedPerformanceMode) must
     * be called from an Activity's Window, not from a service. This controller
     * only exposes the support check and an explanation. The UI layer's
     * Activity must drive the toggle. See [sustainedPerfConstraintExplanation].
     *
     * Most stock OEM devices return false here.
     */
    val isSustainedPerformanceModeSupported: Boolean
        get() {
            val pm = context.getSystemService(PowerManager::class.java) ?: return false
            return pm.isSustainedPerformanceModeSupported
        }

    /**
     * Human-readable explanation of the sustained performance mode constraint.
     *
     * The toggle is android.view.Window.setSustainedPerformanceMode(boolean),
     * not a PowerManager method. It must be called from an Activity's Window
     * reference — background services cannot access it. The UI layer must:
     *   1. Check isSustainedPerformanceModeSupported (this property).
     *   2. If true, call window.setSustainedPerformanceMode(true) from the
     *      Activity while the user is actively viewing the advisory screen.
     *
     * We expose this explanation so the UI can show the user what to do rather
     * than presenting a broken or no-op toggle.
     */
    val sustainedPerfConstraintExplanation: String
        get() = if (isSustainedPerformanceModeSupported) {
            "Sustained performance mode is supported on this device. The app's Activity " +
            "can request it via Window.setSustainedPerformanceMode(true) while the advisory " +
            "screen is open — this reduces thermal bursts at the cost of lower peak clocks."
        } else {
            "This device does not support Sustained Performance Mode " +
            "(PowerManager.isSustainedPerformanceModeSupported returned false). " +
            "No action is available."
        }

    /**
     * Returns a [HintResult] describing what the UI layer should do to apply
     * sustained performance mode. Does NOT call the API (cannot from a Service).
     * The actual call is: activity.window.setSustainedPerformanceMode(enable).
     *
     * Returns [HintResult.UNSUPPORTED] when the device does not support it.
     * Returns [HintResult.APPLIED] when supported, as a signal to the UI to
     * make the Window call — the message describes what the UI must do.
     */
    fun sustainedPerformanceModeInstruction(enable: Boolean): HintResult {
        return if (!isSustainedPerformanceModeSupported) {
            HintResult.UNSUPPORTED(
                "This device does not report isSustainedPerformanceModeSupported=true. " +
                "Sustained performance mode is unavailable."
            )
        } else {
            // We cannot call Window.setSustainedPerformanceMode from a Service context.
            // Return APPLIED so the UI knows it CAN make the call from its Window.
            HintResult.APPLIED(
                if (enable) {
                    "Device supports sustained performance mode. Call " +
                    "window.setSustainedPerformanceMode(true) from your Activity's Window — " +
                    "this is advisory: the OS may hold a lower, stable clock envelope to " +
                    "prevent thermal throttle bursts. Not guaranteed."
                } else {
                    "Call window.setSustainedPerformanceMode(false) from your Activity's Window " +
                    "to release sustained performance mode."
                }
            )
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Clear all active hints and return the device to default behavior.
     * Call when the ADVISORY controller is stopped or the app goes to background.
     */
    fun clearAll() {
        closeAdpfSession()
        // Sustained performance mode must be cleared from the Activity's Window.
        // Log a reminder for the UI layer to call window.setSustainedPerformanceMode(false).
        Log.i(TAG, "Advisory stopped — UI layer should clear Window.setSustainedPerformanceMode if active")
    }
}

/**
 * Result of attempting to apply a power hint.
 *
 * NEVER conflate APPLIED with "the OS honored this". APPLIED means the hint
 * was sent; the OS response is opaque. Surface [message] to the user.
 */
sealed class HintResult(open val message: String) {
    /** Hint was sent to the OS. It may or may not be honored. */
    data class APPLIED(override val message: String) : HintResult(message)

    /** The underlying API is not available on this device/API level. */
    data class UNSUPPORTED(override val message: String) : HintResult(message)

    /** The API is available but returned false or threw. */
    data class REJECTED(override val message: String) : HintResult(message)

    /** Whether the hint was sent (not necessarily honored). */
    val wasSent: Boolean get() = this is APPLIED
}
