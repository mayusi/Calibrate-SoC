package io.github.mayusi.calibratesoc.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.session.GameSession
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * List of the ≤10 saved game sessions, newest first. Tap a row to open
 * the timeline detail. Swipe / tap the delete icon to remove a session.
 *
 * Navigation: reachable from the Dashboard "View sessions" button, NOT
 * from the bottom bar (keeping 6 tabs on the handheld).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel = hiltViewModel(),
    onOpenDetail: (Long) -> Unit,
    onOpenAppStats: () -> Unit,
    onBack: () -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.SportsEsports, contentDescription = "Back")
                    }
                },
                actions = {
                    // App dashboard button — always available so users can navigate
                    // even before any sessions exist (the dashboard shows an empty state).
                    IconButton(onClick = onOpenAppStats) {
                        Icon(
                            Icons.Outlined.BarChart,
                            contentDescription = "Per-app performance dashboard",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.SportsEsports,
                title = "No sessions yet",
                body = "Start a session from the Dashboard or the HUD Record button, play for a while, then stop. Your session timeline will appear here.",
                modifier = Modifier.padding(padding),
                action = {
                    FilledTonalButton(onClick = onOpenAppStats) {
                        Icon(Icons.Outlined.BarChart, contentDescription = null)
                        Spacer(Modifier.width(Spacing.dense))
                        Text("App dashboard")
                    }
                },
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    top = padding.calculateTopPadding() + Spacing.item,
                    bottom = padding.calculateBottomPadding() + Spacing.item,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                item {
                    AppDashboardBanner(onOpenAppStats = onOpenAppStats)
                }
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onClick = { onOpenDetail(session.id) },
                        onDelete = { viewModel.delete(session.id) },
                    )
                }
            }
        }
    }
}

/**
 * Small inline banner above the session list pointing users to the app stats screen.
 * Keeps it discoverable without cluttering the TopAppBar label.
 */
@Composable
private fun AppDashboardBanner(onOpenAppStats: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenAppStats)
            .padding(vertical = Spacing.dense),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        Icon(
            Icons.Outlined.BarChart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Per-app performance dashboard",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Avg FPS, peak temps and power across all your recorded apps.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: GameSession,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val durationLabel = formatDuration(session.durationMs)
    val summary = session.summary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.card, vertical = Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateFmt.format(Date(session.startedAtMs)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                session.appLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
                    val fpsLabel = summary.avgFps?.let { "%.0f FPS avg".format(it) } ?: "FPS unavailable"
                    Text(
                        text = fpsLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    summary.peakCpuTempC?.let {
                        Text(
                            text = "%.0f°C peak".format(it),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                it >= 80f -> MaterialTheme.colorScheme.error
                                it >= 65f -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                session.profileName?.let {
                    Text(
                        text = "Profile: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.dense))
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSecs = ms / 1_000
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}
