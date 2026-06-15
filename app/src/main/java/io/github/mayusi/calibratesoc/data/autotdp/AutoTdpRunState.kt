package io.github.mayusi.calibratesoc.data.autotdp

/**
 * Snapshot of the live daemon's runtime status, emitted by
 * [AutoTdpController.state] every tick.
 *
 * UI consumers render this directly — they do NOT query the service.
 */
data class AutoTdpRunState(
    /** What the daemon is doing right now. */
    val status: AutoTdpStatus = AutoTdpStatus.IDLE,
    /** Last decision reason from [AutoTdpEngine.decide]. Human-readable. */
    val lastReason: String = "",
    /** The kernel state the daemon last wrote (null when not yet applied). */
    val appliedState: TdpState? = null,
    /**
     * Whether the LIVE rung is available on this device. Set once at daemon
     * startup from the [CapabilityReport]. False = UI should route to
     * SCRIPT or ADVISORY rung. Never changes after the first [AutoTdpStatus.RUNNING]
     * observation.
     */
    val liveAvailable: Boolean = false,
    /**
     * When [liveAvailable] is false, a human-readable explanation of WHY
     * (e.g. "sysfs cpu online not writable — needs root or unlock script").
     * Null when live is available.
     */
    val liveUnavailableReason: String? = null,
    /** The most recent measured savings result. Null before the first sampling cycle. */
    val savings: SavingsResult? = null,
    /**
     * When the daemon stopped due to a safety kill, the reason is recorded here.
     * Null during normal operation or clean stop.
     */
    val killReason: String? = null,
    /**
     * When a mid-run write fails (denied by kernel), the failure is recorded here
     * so the UI can surface it honestly instead of hiding it.
     */
    val writeFailure: String? = null,
)

/** Lifecycle states for the AutoTDP daemon. */
enum class AutoTdpStatus {
    /** Service not started. */
    IDLE,
    /**
     * Service started but LIVE writes are not available on this device.
     * The service stopped itself and exposed [AutoTdpRunState.liveUnavailableReason].
     * UI should route to SCRIPT or ADVISORY.
     */
    LIVE_UNAVAILABLE,
    /**
     * Daemon is running, collecting telemetry, and writing TDP deltas.
     */
    RUNNING,
    /**
     * Daemon stopped itself due to a safety kill (too hot / battery too low).
     * All writes have been reverted. [AutoTdpRunState.killReason] has the detail.
     */
    KILLED_BY_SAFETY,
    /**
     * A mid-run kernel write was denied. Daemon stopped + reverted everything.
     * [AutoTdpRunState.writeFailure] has the detail.
     */
    WRITE_DENIED,
    /** Normal stop requested. All writes reverted. */
    STOPPED,
}
