package io.github.mayusi.calibratesoc.ui.session

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.session.GameSession
import io.github.mayusi.calibratesoc.data.session.SessionSummary
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.MetricLineChartCard
import io.github.mayusi.calibratesoc.ui.components.MetricLineChartOverlay
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.components.StatTile
import io.github.mayusi.calibratesoc.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session detail / timeline screen. The payoff screen — answers "why did
 * my FPS drop?" / "did thermals throttle me?". Four timeline charts:
 *   (a) FPS over time
 *   (b) CPU + GPU temperature overlay
 *   (c) CPU + GPU MHz clock overlay
 *   (d) Power (W) over time
 *
 * Headline summary tiles mirror the Benchmark result screen style.
 * Honesty captions note when FPS data was unavailable (no HUD active).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    viewModel: SessionDetailViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    val session by viewModel.session.collectAsStateWithLifecycle()
    val fullSummary by viewModel.fullSummary.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session timeline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val s = session
        if (s == null) {
            EmptyState(
                icon = Icons.Outlined.SportsEsports,
                title = "Loading…",
                body = "Reading session from storage.",
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
            item { SessionHeaderCard(s) }
            item { SessionSummaryCard(s, fullSummary ?: s.summary) }
            // Chart (a): FPS over time — only when FPS was available
            if (s.fpsAvailableDuringSampling) {
                item {
                    MetricLineChartCard(
                        title = "FPS over time",
                        caption = "Real game FPS captured via SurfaceFlinger (HUD was active). " +
                            "Dips indicate frame-rate drops; look for simultaneous thermal spikes below.",
                        points = s.samples.map { it.fps ?: 0f },
                    )
                }
            } else {
                item {
                    SectionCard("FPS over time") {
                        Text(
                            "FPS not captured — the HUD was not running during this session. " +
                                "Start a session with the HUD active to record real game FPS.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Chart (b): Temperature overlay (CPU + GPU)
            item {
                SectionCard("Temperatures over time") {
                    Text(
                        "CPU (avg across cores) and GPU zones. Sustained temperatures above 75 °C " +
                            "commonly trigger kernel thermal throttling.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.dense))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                    ) {
                        TempLegend("CPU", MaterialTheme.colorScheme.primary)
                        TempLegend("GPU", MaterialTheme.colorScheme.tertiary)
                    }
                    MetricLineChartOverlay(
                        seriesA = s.samples.map { it.cpuTempC ?: 0f },
                        seriesB = s.samples.map { it.gpuTempC ?: 0f },
                    )
                }
            }
            // Chart (c): Clock frequency overlay (CPU max + GPU MHz)
            item {
                SectionCard("Clock frequencies over time") {
                    Text(
                        "CPU max-core MHz and GPU MHz. A sudden drop (especially when temps are " +
                            "high) is a signature of thermal frequency throttling.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.dense))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                    ) {
                        TempLegend("CPU MHz", MaterialTheme.colorScheme.primary)
                        TempLegend("GPU MHz", MaterialTheme.colorScheme.tertiary)
                    }
                    MetricLineChartOverlay(
                        seriesA = s.samples.map { it.cpuMaxMhz.toFloat() },
                        seriesB = s.samples.map { it.gpuMhz?.toFloat() ?: 0f },
                    )
                }
            }
            // Chart (d): Power draw over time
            item {
                MetricLineChartCard(
                    title = "Power draw (W) over time",
                    caption = "Battery draw in watts — derived from current × voltage. " +
                        "Spikes during loading screens are normal; sustained high draw with " +
                        "rising temps is worth investigating.",
                    points = s.samples.map { it.batteryW?.toFloat() ?: 0f },
                )
            }
        }
    }
}

@Composable
private fun SessionHeaderCard(session: GameSession) {
    val dateFmt = SimpleDateFormat("MMMM d yyyy, HH:mm", Locale.getDefault())
    SectionCard("Session info") {
        Text(
            dateFmt.format(Date(session.startedAtMs)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val duration = session.durationMs.let { ms ->
            val h = ms / 3_600_000
            val m = (ms % 3_600_000) / 60_000
            val s = (ms % 60_000) / 1_000
            if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
        }
        Text(duration, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        session.appLabel?.let {
            Text("App: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        session.profileName?.let {
            Text("Profile: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "${session.samples.size} samples at ~1 Hz",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionSummaryCard(session: GameSession, summary: SessionSummary) {
    SectionCard("Summary") {
        // FPS row
        if (session.fpsAvailableDuringSampling) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                StatTile(
                    label = "Avg FPS",
                    value = summary.avgFps?.let { "%.0f".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Min FPS",
                    value = summary.minFps?.let { "%.0f".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "1% low FPS",
                    value = summary.p1LowFps?.let { "%.0f".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Dip events",
                    value = "${summary.fpsDipEvents}",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Spacing.group))
        } else {
            Text(
                "FPS: not recorded — HUD was not active during this session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.group))
        }
        // Thermal + power row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            StatTile(
                label = "Peak CPU temp",
                value = summary.peakCpuTempC?.let { "%.0f°C".format(it) } ?: "—",
                valueColor = summary.peakCpuTempC?.let { t ->
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
                value = summary.peakGpuTempC?.let { "%.0f°C".format(it) } ?: "—",
                valueColor = summary.peakGpuTempC?.let { t ->
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
                value = summary.avgWatts?.let { "%.1fW".format(it) } ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "All data is real telemetry. 1 Hz sample rate means short spikes may not appear in the chart.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small legend pill for the overlay temperature chart. */
@Composable
private fun TempLegend(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "—",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
