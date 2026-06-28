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
    /**
     * Machine-readable classification of the most recent engine decision (the
     * clean label the UI shows by default; [lastReason] is the raw string shown
     * on expand). Defaults to [HoldReason.NO_TELEMETRY] before the first tick.
     */
    val holdReason: HoldReason = HoldReason.NO_TELEMETRY,
    /**
     * HEARTBEAT: wall-clock epoch (ms) of the last tick where the daemon
     * applied/updated state. Null before the first applied tick. The UI uses this
     * to prove the daemon is alive (e.g. "last update 2 s ago") vs. silently stuck.
     */
    val lastAppliedEpochMs: Long? = null,
    /**
     * Wall-clock epoch (ms) when the daemon reached [AutoTdpStatus.RUNNING].
     * Null until running. Used to compute session-elapsed for energy integration.
     */
    val sessionStartEpochMs: Long? = null,
    /**
     * The current proof-of-effect bundle (what AutoTDP is doing + measured impact).
     * Null before the first applied tick. DERIVED fields are always trustworthy;
     * MEASURED fields are gated on a completed probe (see [AutoTdpEffect]).
     */
    val effect: AutoTdpEffect? = null,
    /**
     * Rolling history of recent decisions (oldest-first, bounded by
     * [MAX_DECISION_HISTORY]). Empty before the first applied tick.
     */
    val decisions: List<DecisionRecord> = emptyList(),
    /**
     * WAVE 4a: the Smart [GoalProfile] the daemon is currently running. When the
     * user picked AUTO this is the CONCRETE goal the context classifier resolved AUTO
     * to this tick (e.g. AUTO → BALANCED_SMART); otherwise it is the picked goal.
     * Null until the first decision, or when the daemon is running the pure legacy
     * profile path with no goal. This is CONFIG/INTENT — the goal in effect — not a
     * measurement. The UI (Wave 4b) renders "… → Balanced".
     */
    val activeGoal: GoalProfile? = null,
    /**
     * WAVE 4a: the workload context the classifier currently BELIEVES it is in
     * (`classifier.stable`). This is the DETECTED honesty tier — the classifier's
     * committed belief after hysteresis, NOT a sensor reading. Null until the first
     * decision. The UI (Wave 4b) renders "Detected: Heavy 3D → Balanced". Keep this
     * honest: it is a classification (belief), never a measured quantity.
     */
    val detectedContext: WorkloadContext? = null,
    /**
     * UNIT 4 — true when TARGET_FPS_FLOOR is the picked goal but no REAL frame-rate
     * source is available, so the daemon degraded to BALANCED_SMART. The UI shows a
     * banner explaining the degrade. Honesty flag; false otherwise.
     */
    val fpsFloorDegraded: Boolean = false,
    /**
     * UNIT 4 — for TARGET_RUNTIME, the MODELLED live projection line for the HUD/picker,
     * e.g. "Projected: 3h 10m (modelled, estimated)". Null when the daemon is not running
     * TARGET_RUNTIME or no projection could be modelled. This is a MODEL output, always
     * labelled as such — never a guarantee.
     */
    val runtimeProjectionNote: String? = null,
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
