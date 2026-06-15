package io.github.mayusi.calibratesoc.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.insights.InsightsAggregator
import io.github.mayusi.calibratesoc.data.insights.SessionReport
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.MetricTile
import io.github.mayusi.calibratesoc.ui.components.SectionHeader
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Insights Screen — Direction C.
 *
 * Surfaces cross-session data derived by [InsightsAggregator] via [InsightsViewModel]:
 *   - Battery saved this week (MetricTile)
 *   - Temperature trend direction (MetricTile)
 *   - Best profile per game (ArsenalPanel per app)
 *   - Recent session reports (ArsenalPanel per session)
 *
 * "Not enough data" states are always shown honestly — no fabricated numbers.
 */
@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val latestReport by viewModel.latestReport.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item {
            Column {
                Text(
                    "INSIGHTS",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.08.sp,
                )
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    "Cross-session performance rollup — built from real session data only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            }
        }

        // ── Post-session report (latest session, if exists) ──────────────
        latestReport?.let { report ->
            item {
                SectionHeader(title = "LATEST SESSION", accent = AccentBar.Emerald)
                Spacer(Modifier.height(Spacing.dense))
                SessionReportPanel(report = report, isLatest = true)
            }
        }

        // ── This week summary tiles ───────────────────────────────────────
        item {
            SectionHeader(title = "THIS WEEK", accent = AccentBar.Amber)
            Spacer(Modifier.height(Spacing.dense))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                val savedMwh = summary.batterySavedThisWeekMwh
                MetricTile(
                    label = "BATTERY SAVED",
                    value = if (savedMwh != null) "%.0f".format(savedMwh) else "—",
                    unit = if (savedMwh != null) "mWh" else null,
                    accent = AccentBar.Amber,
                    modifier = Modifier.weight(1f),
                )
                val trend = summary.tempTrendCPerSession
                val trendStr = when {
                    trend == null -> "—"
                    trend > 0.5 -> "+%.1f".format(trend)
                    trend < -0.5 -> "%.1f".format(trend)
                    else -> "stable"
                }
                val trendAccent = when {
                    trend == null -> AccentBar.Neutral
                    trend > 0.5 -> AccentBar.Red
                    trend < -0.5 -> AccentBar.Emerald
                    else -> AccentBar.Emerald
                }
                MetricTile(
                    label = "TEMP TREND",
                    value = trendStr,
                    unit = if (trend != null && trendStr != "stable") "°C/sess" else null,
                    accent = trendAccent,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── Insufficient data notice ──────────────────────────────────────
        summary.insufficientDataReason?.let { reason ->
            item {
                ArsenalPanel(accent = AccentBar.Neutral) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF999999),
                    )
                }
            }
        }

        // ── Best profile per game ─────────────────────────────────────────
        if (summary.bestProfilePerApp.isNotEmpty()) {
            item {
                SectionHeader(title = "BEST PROFILE PER GAME", accent = AccentBar.Blue)
            }
            items(summary.bestProfilePerApp.entries.toList(), key = { it.key }) { (appLabel, entry) ->
                BestProfilePanel(appLabel = appLabel, entry = entry)
            }
        } else if (summary.insufficientDataReason == null) {
            item {
                ArsenalPanel(accent = AccentBar.Neutral) {
                    Text(
                        "No per-game profile data yet — play a few sessions with different profiles to see which works best.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF999999),
                    )
                }
            }
        }

        // ── Recent session reports ────────────────────────────────────────
        if (reports.isNotEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.dense))
                SectionHeader(title = "RECENT SESSIONS", accent = AccentBar.Purple)
            }
            items(
                items = if (latestReport != null) reports.drop(1) else reports,
                key = { it.sessionId },
            ) { report ->
                SessionReportPanel(report = report, isLatest = false)
            }
        } else if (summary.insufficientDataReason == null) {
            item {
                EmptyState(
                    icon = Icons.Outlined.BarChart,
                    title = "No sessions recorded yet",
                    body = "Start the HUD overlay and play a game to record your first session.",
                )
            }
        }

        item { Spacer(Modifier.height(Spacing.screen)) }
    }
}

// ── Session report panel ───────────────────────────────────────────────────────

@Composable
private fun SessionReportPanel(report: SessionReport, isLatest: Boolean) {
    val accent = if (isLatest) AccentBar.Emerald else AccentBar.Purple
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val dateStr = fmt.format(Date(report.startedAtMs))
    val durationMin = report.durationMs / 60_000

    ArsenalPanel(accent = accent) {
        // Header row: app label + date + optional "LATEST" pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    (report.appLabel ?: "Unknown app").uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.06.sp,
                )
                Text(
                    "$dateStr · ${durationMin}min",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF777777),
                )
            }
            if (isLatest) {
                StatusPill(text = "LATEST", accent = AccentBar.Emerald)
            }
            report.profileName?.let { prof ->
                StatusPill(text = prof, accent = AccentBar.Blue)
            }
        }

        Spacer(Modifier.height(Spacing.dense))

        // Verdict — plain English from SessionReportBuilder
        Text(
            report.verdict,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCCCCCC),
        )

        Spacer(Modifier.height(Spacing.dense))

        // Key metrics as KvRows
        report.avgFps?.let { fps ->
            KvRow(label = "Avg FPS", value = "%.0f fps".format(fps))
        }
        report.peakFps?.let { fps ->
            KvRow(label = "Peak FPS", value = "%.0f fps".format(fps))
        }
        report.peakCpuTempC?.let { t ->
            KvRow(label = "Peak CPU Temp", value = "%.0f °C".format(t))
        }
        report.energyMwh?.let { e ->
            KvRow(label = "Energy Used", value = "%.0f mWh".format(e))
        }
        report.autoTdpSavedMwh?.let { s ->
            KvRow(label = "AutoTDP Saved", value = "~%.0f mWh".format(s))
        }
        if (report.throttleEventCount >= 2) {
            KvRow(
                label = "Throttle Events",
                value = "${report.throttleEventCount} (heuristic)",
                explainer = "Hot-temp + FPS-dip coinciding — may not be actual throttling.",
            )
        }
    }
}

// ── Best profile panel ─────────────────────────────────────────────────────────

@Composable
private fun BestProfilePanel(
    appLabel: String,
    entry: InsightsAggregator.BestProfileEntry,
) {
    ArsenalPanel(accent = AccentBar.Blue) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                appLabel.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 0.06.sp,
                modifier = Modifier.weight(1f),
            )
            StatusPill(text = "BEST: ${entry.profileName}", accent = AccentBar.Blue)
        }
        Spacer(Modifier.height(Spacing.dense))
        entry.avgFps?.let { fps ->
            KvRow(label = "Avg FPS", value = "%.0f fps".format(fps))
        }
        KvRow(
            label = "Throttle/session",
            value = "%.1f".format(entry.avgThrottleEventsPerSession),
            explainer = "Lower = more stable. Based on ${entry.sessionCount} sessions.",
        )
    }
}
