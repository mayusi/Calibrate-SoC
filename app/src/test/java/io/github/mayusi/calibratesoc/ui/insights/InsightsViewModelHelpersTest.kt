package io.github.mayusi.calibratesoc.ui.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for pure helpers in [InsightsViewModel].
 *
 * These are pure-JVM tests — no Android context required.
 */
class InsightsViewModelHelpersTest {

    // ── weekStartMs ───────────────────────────────────────────────────────────

    @Test
    fun `weekStartMs returns a Monday at midnight`() {
        val ms = InsightsViewModel.weekStartMs()
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms

        assertEquals("Hour should be 0", 0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals("Minute should be 0", 0, cal.get(Calendar.MINUTE))
        assertEquals("Second should be 0", 0, cal.get(Calendar.SECOND))
        assertEquals("Millisecond should be 0", 0, cal.get(Calendar.MILLISECOND))
        assertEquals("Day should be Monday", Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `weekStartMs is at most 7 days in the past`() {
        val ms = InsightsViewModel.weekStartMs()
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        assertTrue(
            "weekStartMs ($ms) should be within 7 days of now ($now)",
            now - ms <= sevenDaysMs,
        )
    }

    @Test
    fun `weekStartMs is not in the future`() {
        val ms = InsightsViewModel.weekStartMs()
        val now = System.currentTimeMillis()
        assertTrue("weekStartMs should not be in the future", ms <= now)
    }
}
