package io.github.mayusi.calibratesoc.data.thermal

/**
 * Snapshot of the live Predictive Throttle Guard, pushed by
 * [ThrottleGuardController.updateState]. The UI (Wave 4) renders this directly.
 *
 * The guard is a SAFETY ASSIST: a standalone toggle that pre-emptively caps the
 * big cluster a little BEFORE the kernel's own throttle cliff, so FPS degrades
 * smoothly instead of dropping off a cliff. It is AUTO-SUPPRESSED while AutoTDP or
 * Game Boost is running (they do their own thermal management) — see [suppressed].
 */
data class ThrottleGuardState(
    val status: ThrottleGuardStatus = ThrottleGuardStatus.IDLE,
    /** Whether the LIVE write path is available. False = self-stopped; see reason. */
    val liveAvailable: Boolean = false,
    /** Human-readable reason LIVE writes are unavailable (null when available). */
    val liveUnavailableReason: String? = null,
    /**
     * True while the guard is standing down because AutoTDP / Game Boost owns the
     * clocks. The guard reverts its own cap and idles while this is true so it never
     * fights the active owner. The service keeps running (so it resumes automatically
     * when the owner stops) but applies nothing.
     */
    val suppressed: Boolean = false,
    /**
     * The most recent forecast reason (always human-readable; suitable for an event
     * log). Empty before the first telemetry window completes.
     */
    val lastForecastReason: String = "",
    /** Estimated seconds until the kernel trip, when a throttle is predicted; else null. */
    val willThrottleInSec: Int? = null,
    /**
     * The big-cluster cap (kHz) the guard has pre-emptively applied RIGHT NOW, or null
     * when no cap is active (forecast clear / suppressed). The UI shows this as the
     * live "pre-emptive cap" value.
     */
    val activeCapKhz: Int? = null,
    /** When a write was denied (no live tier), the failure is recorded here. */
    val writeFailure: String? = null,
) {
    /** Whether a pre-emptive cap is currently applied (derived convenience). */
    val capApplied: Boolean get() = activeCapKhz != null
}

/** Lifecycle states for the Predictive Throttle Guard. */
enum class ThrottleGuardStatus {
    /** Not started. */
    IDLE,
    /** Started but LIVE writes are unavailable; the service self-stopped. */
    LIVE_UNAVAILABLE,
    /**
     * Running: feeding telemetry into the forecaster. May be actively capping, holding
     * (forecast clear), or [ThrottleGuardState.suppressed] (AutoTDP/Boost owns clocks).
     */
    RUNNING,
    /** Normal user stop. Any pre-emptive cap reverted. */
    STOPPED,
}
