package io.github.mayusi.calibratesoc.data.boost

/**
 * Snapshot of the Game Boost daemon's runtime status, pushed by
 * [GameBoostController.updateState] every meaningful transition. The UI (Wave 4)
 * renders this directly and never queries the service.
 *
 * HONESTY: the [plugInRecommended] + [autoRevertsNote] fields exist so the UI can
 * surface the truth — this is max heat for max FPS, time-boxed and auto-reverting.
 * We never let the UI claim it is free.
 */
data class GameBoostState(
    /** What the daemon is doing right now. */
    val status: GameBoostStatus = GameBoostStatus.IDLE,
    /**
     * Whether the LIVE write path is available on this device. False = the service
     * self-stopped and [liveUnavailableReason] explains why. Mirrors AutoTDP.
     */
    val liveAvailable: Boolean = false,
    /** Human-readable reason LIVE writes are unavailable (null when available). */
    val liveUnavailableReason: String? = null,
    /** Wall-clock epoch (ms) when the boost reached [GameBoostStatus.BOOSTING]. */
    val sessionStartEpochMs: Long? = null,
    /**
     * Wall-clock epoch (ms) at which the time box expires and the session auto-reverts.
     * Null until boosting. The UI renders the countdown from this.
     */
    val timeBoxExpiresEpochMs: Long? = null,
    /** Number of pin writes that actually landed (Success), for honest "N nodes pinned". */
    val pinnedNodeCount: Int = 0,
    /**
     * Nodes that were requested but NOT writable on this firmware (honest skip list).
     * Empty when everything landed. The UI may show "couldn't pin: …" so the user
     * knows the boost is partial rather than pretending it pinned everything.
     */
    val skippedNodes: List<String> = emptyList(),
    /**
     * When the daemon stopped due to a thermal trip, the reason is recorded here.
     * Null during normal operation / clean stop / time-box expiry.
     */
    val thermalTripReason: String? = null,
    /** When a write was denied (no live tier), the failure is recorded here. */
    val writeFailure: String? = null,
    /**
     * Last hottest-zone temperature observed (°C), for the live HUD. Null before the
     * first telemetry sample.
     */
    val lastHottestTempC: Float? = null,
    /**
     * HONEST framing for the UI: true means "plug in recommended" because brute-max
     * drains the battery fast. Always true while boosting — boost is never free.
     */
    val plugInRecommended: Boolean = true,
    /** HONEST framing copy: the boost is time-boxed and auto-reverts. */
    val autoRevertsNote: String =
        "Max heat for max FPS. Time-boxed and auto-reverts — plug in recommended.",
)

/** Lifecycle states for the Game Boost daemon. */
enum class GameBoostStatus {
    /** Not started. */
    IDLE,
    /**
     * Started but the LIVE write path is unavailable on this device; the service
     * self-stopped. [GameBoostState.liveUnavailableReason] has the detail.
     */
    LIVE_UNAVAILABLE,
    /** Brute-max pins are applied and the session is running (time-boxed + guarded). */
    BOOSTING,
    /**
     * The time box expired — the daemon reverted everything and stopped. This is the
     * EXPECTED end state for an un-interrupted boost.
     */
    TIME_BOX_EXPIRED,
    /**
     * A thermal trip fired (hottest zone crossed the safety threshold) — the daemon
     * reverted everything and stopped. [GameBoostState.thermalTripReason] has detail.
     */
    THERMAL_TRIPPED,
    /** A mid-run write was denied (no live tier). Reverted + stopped. */
    WRITE_DENIED,
    /** Normal user stop. All writes reverted. */
    STOPPED,
}
