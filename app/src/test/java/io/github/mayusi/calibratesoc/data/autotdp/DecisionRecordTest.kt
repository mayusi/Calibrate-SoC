package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the [DecisionRecord] ring bound ([List.appendBounded] /
 * [MAX_DECISION_HISTORY]). The daemon relies on this to keep decision history
 * bounded; the UI relies on oldest-first ordering.
 */
class DecisionRecordTest {

    private fun record(epoch: Long) = DecisionRecord(
        epochMs = epoch,
        holdReason = HoldReason.IDLE_HOLDING,
        bigCapKhz = null,
        parkedCount = 0,
        rawReason = "holding #$epoch",
    )

    @Test
    fun `appending below the cap keeps every record oldest-first`() {
        var history = emptyList<DecisionRecord>()
        for (i in 1..5) history = history.appendBounded(record(i.toLong()))

        assertThat(history).hasSize(5)
        assertThat(history.first().epochMs).isEqualTo(1L)
        assertThat(history.last().epochMs).isEqualTo(5L)
    }

    @Test
    fun `history never exceeds MAX_DECISION_HISTORY`() {
        var history = emptyList<DecisionRecord>()
        for (i in 1..(MAX_DECISION_HISTORY + 50)) {
            history = history.appendBounded(record(i.toLong()))
        }
        assertThat(history).hasSize(MAX_DECISION_HISTORY)
    }

    @Test
    fun `oldest entries are dropped when over the cap`() {
        var history = emptyList<DecisionRecord>()
        val total = MAX_DECISION_HISTORY + 5
        for (i in 1..total) history = history.appendBounded(record(i.toLong()))

        // The oldest 5 (epochs 1..5) must be gone; newest is `total`.
        assertThat(history.first().epochMs).isEqualTo((total - MAX_DECISION_HISTORY + 1).toLong())
        assertThat(history.last().epochMs).isEqualTo(total.toLong())
        // None of the dropped epochs remain.
        assertThat(history.map { it.epochMs }).doesNotContain(1L)
        assertThat(history.map { it.epochMs }).doesNotContain(5L)
    }

    @Test
    fun `exactly at the cap retains all and drops none`() {
        var history = emptyList<DecisionRecord>()
        for (i in 1..MAX_DECISION_HISTORY) history = history.appendBounded(record(i.toLong()))
        assertThat(history).hasSize(MAX_DECISION_HISTORY)
        assertThat(history.first().epochMs).isEqualTo(1L)

        // One more push drops exactly the oldest.
        history = history.appendBounded(record(999L))
        assertThat(history).hasSize(MAX_DECISION_HISTORY)
        assertThat(history.first().epochMs).isEqualTo(2L)
        assertThat(history.last().epochMs).isEqualTo(999L)
    }
}
