package io.github.mayusi.calibratesoc.data.autotdp

/**
 * One entry in the AutoTDP decision history ring. Captured each tick the daemon
 * applies/updates state, so the UI can render a short "what the tuner has been
 * doing" timeline without the daemon retaining unbounded history.
 *
 * @property epochMs     Wall-clock time the decision was recorded ([System.currentTimeMillis]).
 *                       Captured by the daemon (which runs on Dispatchers.IO) and passed in —
 *                       never read inside any pure/testable function.
 * @property holdReason  Machine-readable classification of the decision (clean UI label).
 * @property bigCapKhz   Big-cluster cap applied at this decision (kHz). Null = uncapped.
 * @property parkedCount How many prime cores were parked at this decision.
 * @property rawReason   The engine's free-form reason string (shown on expand).
 */
data class DecisionRecord(
    val epochMs: Long,
    val holdReason: HoldReason,
    val bigCapKhz: Int?,
    val parkedCount: Int,
    val rawReason: String,
)

/**
 * Maximum number of [DecisionRecord]s retained in [AutoTdpRunState.decisions].
 * The daemon drops the oldest entry once this bound is exceeded (FIFO ring).
 */
const val MAX_DECISION_HISTORY = 20

/**
 * Append [record] to a decision history list, dropping the oldest entries so the
 * result never exceeds [MAX_DECISION_HISTORY]. Pure — no time calls, no I/O — so
 * the ring-bounding behaviour is directly unit-testable.
 *
 * Order: oldest-first, newest-last (append semantics). When the list is at the
 * cap, the head (oldest) is dropped to make room for [record].
 */
fun List<DecisionRecord>.appendBounded(record: DecisionRecord): List<DecisionRecord> {
    val appended = this + record
    return if (appended.size > MAX_DECISION_HISTORY) {
        appended.takeLast(MAX_DECISION_HISTORY)
    } else {
        appended
    }
}
