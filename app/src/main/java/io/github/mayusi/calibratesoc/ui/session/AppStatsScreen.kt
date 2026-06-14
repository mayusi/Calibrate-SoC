package io.github.mayusi.calibratesoc.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.session.SessionStatsAggregator
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.components.StatTile
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Per-App Performance Dashboard — Feature 2.
 *
 * Shows each app (identified by its foreground label) aggregated across all
 * saved sessions. Sorted by total playtime, most-played first.
 *
 * Honesty rules surfaced in the UI:
 *   - "1 session" label — never implies a trend with a single data point.
 *   - Metrics that were not recorded (e.g. watts unavailable) show "—" not 0.
 *   - The "Unknown app" bucket is shown transparently rather than hidden.
 *   - FPS row is omitted when no session for the app had FPS available.
 *
 * Navigation: reachable from [SessionsScreen] via an "App dashboard" button.
 * Not in the bottom bar (keeps nav clean; this is a power-user view).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppStatsScreen(
    viewModel: AppStatsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val appStats by viewModel.appStats.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per-app performance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (appStats.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.SportsEsports,
                title = "No sessions yet",
                body = "Record a session from the Dashboard or HUD, then return here to " +
                    "see per-app performance breakdowns.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = Spacing.screen,
                end = Spacing.screen,
                top = padding.calculateTopPadding() + Spacing.item,
                bottom = padding.calculateBottomPadding() + Spacing.item,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            // Top summary: apps ranked by playtime
            item {
                TopAppsSummaryCard(appStats)
            }
            // One card per app
            itemsIndexed(appStats, key = { _, s -> s.appLabel }) { _, stats ->
                AppStatsCard(stats)
            }
        }
    }
}

/**
 * Summary header: lists the top apps by playtime in a compact ranked list.
 * Gives the user an at-a-glance "most played" overview before scrolling
 * into the detailed per-app cards.
 */
@Composable
private fun TopAppsSummaryCard(stats: List<SessionStatsAggregator.AppSessionStats>) {
    SectionCard("Top apps by playtime", icon = Icons.Outlined.BarChart) {
        if (stats.isEmpty()) {
            Text(
                "No recorded sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Text(
            "Sorted by total recorded play time across all sessions.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.dense))
        stats.take(5).forEachIndexed { index, s ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                Text(
                    "#${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Spacing.dense / 2),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        s.appLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${formatPlaytime(s.totalPlaytimeMs)}  ·  ${sessionCountLabel(s.sessionCount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (stats.size > 5) {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "…and ${stats.size - 5} more app${if (stats.size - 5 == 1) "" else "s"} below.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Detailed stats card for a single app.
 * Shows: session count, total playtime, avg FPS, peak temps, avg watts,
 * and dip/throttle event count.
 */
@Composable
private fun AppStatsCard(stats: SessionStatsAggregator.AppSessionStats) {
    SectionCard(stats.appLabel, icon = Icons.Outlined.SportsEsports) {
        // Session count + playtime header
        Text(
            "${sessionCountLabel(stats.sessionCount)}  ·  ${formatPlaytime(stats.totalPlaytimeMs)} total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        if (stats.sessionCount == 1) {
            Text(
                "Only 1 session — these numbers reflect that single recording; " +
                    "trends require multiple sessions.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.group))

        // FPS row
        if (stats.avgFps != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                StatTile(
                    label = "Avg FPS",
                    value = "%.0f".format(stats.avgFps),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            KvRow(
                label = "Avg FPS",
                value = "—",
                explainer = "No sessions for this app had FPS available (HUD not active).",
            )
        }

        Spacer(Modifier.height(Spacing.group))

        // Temp + power row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            StatTile(
                label = "Peak CPU temp",
                value = stats.peakCpuTempC?.let { "%.0f°C".format(it) } ?: "—",
                valueColor = stats.peakCpuTempC?.let { t ->
                    when {
                        t >= 80f -> MaterialTheme.colorScheme.error
                        t >= 65f -> MaterialTheme.colorScheme.secondary
                        else -> null
                    }
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Peak GPU temp",
                value = stats.peakGpuTempC?.let { "%.0f°C".format(it) } ?: "—",
                valueColor = stats.peakGpuTempC?.let { t ->
                    when {
                        t >= 80f -> MaterialTheme.colorScheme.error
                        t >= 65f -> MaterialTheme.colorScheme.secondary
                        else -> null
                    }
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Avg power",
                value = stats.avgWatts?.let { "%.1fW".format(it) } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Spacing.group))

        // Throttle / dip events
        KvRow(
            label = "FPS dip events (across all sessions)",
            value = "${stats.totalThrottleEvents}",
            explainer = if (stats.totalThrottleEvents > 0)
                "Approx. ${formatPlaytime(stats.throttleTimeTotalMs)} in dip states (estimated at 1 event ≈ 1 s)."
            else null,
        )

        Spacer(Modifier.height(Spacing.dense))
        Text(
            "Data from ${sessionCountLabel(stats.sessionCount)}. " +
                "\"—\" means the metric was not recorded in any session.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────

private fun formatPlaytime(ms: Long): String {
    val totalSecs = ms / 1_000L
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

private fun sessionCountLabel(count: Int) =
    "$count session${if (count == 1) "" else "s"}"
