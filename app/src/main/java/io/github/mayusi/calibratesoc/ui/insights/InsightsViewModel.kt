package io.github.mayusi.calibratesoc.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.insights.InsightsAggregator
import io.github.mayusi.calibratesoc.data.insights.SessionReport
import io.github.mayusi.calibratesoc.data.insights.db.SessionReportDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

/**
 * ViewModel for InsightsScreen.
 *
 * Wires [SessionReportDao] → [InsightsAggregator] → UI state.
 * All aggregation is pure and synchronous; the ViewModel only handles
 * the Flow wiring and the week-window calculation.
 */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val dao: SessionReportDao,
) : ViewModel() {

    /** All recent session reports, newest first, from the DAO. */
    val reports: StateFlow<List<SessionReport>> = dao.observeAll()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Rolled-up insights summary, recomputed whenever [reports] changes.
     * Week window = last 7 days (inclusive of today).
     */
    val summary: StateFlow<InsightsAggregator.InsightsSummary> = reports
        .map { all ->
            val weekStart = weekStartMs()
            val weekEnd = System.currentTimeMillis()
            val weekReports = all.filter { it.startedAtMs in weekStart..weekEnd }
            InsightsAggregator.compute(all, weekReports)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            InsightsAggregator.InsightsSummary(
                batterySavedThisWeekMwh = null,
                tempTrendCPerSession = null,
                bestProfilePerApp = emptyMap(),
                insufficientDataReason = "Loading…",
            ),
        )

    /**
     * Returns the most recent [SessionReport] — surfaced as a "post-session
     * report" after a play session ends. Null when no sessions exist.
     */
    val latestReport: StateFlow<SessionReport?> = reports
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    companion object {
        /** Start of the current calendar week (Monday 00:00:00.000 local). */
        fun weekStartMs(): Long {
            val cal = Calendar.getInstance()
            // Pin the week to start on Monday regardless of the device locale's
            // first-day-of-week. Without this, on a Sunday-first locale (e.g. en-US)
            // setting DAY_OF_WEEK=MONDAY while today is Sunday rolls FORWARD to next
            // Monday — putting the week start in the future. Forcing firstDayOfWeek
            // makes "this week's Monday" always resolve to the past Monday.
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
