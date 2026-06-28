package io.github.mayusi.calibratesoc.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Thermostat
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.insights.PerPackageHistory
import io.github.mayusi.calibratesoc.data.session.GameSession
import io.github.mayusi.calibratesoc.data.session.SessionSummary
import io.github.mayusi.calibratesoc.data.session.SessionStatsAggregator
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
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
    val throttleEvents by viewModel.throttleEvents.collectAsStateWithLifecycle()
    val throttleSummary by viewModel.throttleSummary.collectAsStateWithLifecycle()
    val perGameHistory by viewModel.perGameHistory.collectAsStateWithLifecycle()

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
            // ── Feature 1: Thermal Event Timeline ─────────────────────────
            item {
                ThermalEventTimelineCard(
                    session = s,
                    events = throttleEvents,
                    summaryLine = throttleSummary,
                )
            }
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
            // Chart (e): CPU load % over time — the only recorded-but-never-drawn metric.
            item {
                MetricLineChartCard(
                    title = "CPU load over time",
                    caption = "Average CPU load across all cores (%). " +
                        "Sustained 100 % load with rising temps and dropping clocks is the " +
                        "classic throttle signature — cross-reference with the temperature " +
                        "and clock charts above.",
                    points = s.samples.map { it.cpuLoadPct.toFloat() },
                )
            }
            // Feature B2: Per-game aggregate history card.
            // Only shown when packageName is known and ≥2 sessions exist
            // (ViewModel sets perGameHistory=null for <2 sessions).
            // Snapshot to a local val first — smart cast on delegated property is blocked.
            val historySnapshot = perGameHistory
            if (historySnapshot != null) {
                item {
                    PerGameHistoryCard(history = historySnapshot)
                }
            }
        }
    }
}

@Composable
private fun SessionHeaderCard(session: GameSession) {
    val dateFmt = remember { SimpleDateFormat("MMMM d yyyy, HH:mm", Locale.getDefault()) }
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

/**
 * Thermal Event Timeline card — Feature 1.
 *
 * Shows:
 *   - A plain-language summary sentence (or honest "no events" / "not enough data").
 *   - A scrollable list of individual heuristic throttle events with timestamp,
 *     FPS before/after, and CPU temperature.
 *
 * Honesty rules:
 *   - Events are labelled "(heuristic)" — they are not confirmed kernel sysfs throttle
 *     records; they are detected by the temp-90th-percentile + FPS-dip algorithm.
 *   - When FPS was not recorded, we show an explicit "FPS not available" message
 *     rather than pretending we can detect throttle events.
 *   - When the session has too few samples, we say so.
 */
@Composable
private fun ThermalEventTimelineCard(
    session: GameSession,
    events: List<SessionStatsAggregator.ThermalThrottleEvent>,
    summaryLine: String?,
) {
    SectionCard("Thermal event timeline", icon = Icons.Outlined.Thermostat) {
        when {
            // Case 1: FPS was not available — cannot detect throttle events.
            !session.fpsAvailableDuringSampling -> {
                Text(
                    "Throttle event detection requires FPS data. " +
                        "Run a session with the HUD active to enable this analysis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Case 2: Session too short.
            session.samples.size < SessionStatsAggregator.MIN_SAMPLES_FOR_ANALYSIS -> {
                Text(
                    "Session too short for throttle analysis " +
                        "(need ≥ ${SessionStatsAggregator.MIN_SAMPLES_FOR_ANALYSIS} samples, " +
                        "got ${session.samples.size}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Case 3: No events found.
            events.isEmpty() -> {
                Text(
                    "No thermal throttle events detected (heuristic). " +
                        "CPU temperatures stayed below the 90th-percentile threshold " +
                        "without a simultaneous FPS dip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Case 4: Events detected — show summary + list.
            else -> {
                // Summary sentence
                if (summaryLine != null) {
                    AlertCard(
                        type = AlertType.WARNING,
                        title = "Thermal events detected",
                        message = summaryLine,
                    )
                    Spacer(Modifier.height(Spacing.group))
                }
                Text(
                    "Heuristic: events fired when CPU was in the hottest 10 % of the session " +
                        "AND FPS dropped >10 % below the 30-second rolling average. " +
                        "Not a confirmed kernel throttle record.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.group))
                events.forEachIndexed { index, event ->
                    ThermalEventRow(index + 1, event)
                    if (index < events.lastIndex) {
                        Spacer(Modifier.height(Spacing.dense))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThermalEventRow(
    index: Int,
    event: SessionStatsAggregator.ThermalThrottleEvent,
) {
    val elapsedSec = event.elapsedMs / 1_000L
    val m = elapsedSec / 60
    val s = elapsedSec % 60
    val timeStr = if (m > 0) "${m}m ${s}s" else "${s}s"
    val fpsBefore = "%.0f".format(event.fpsBefore)
    val fpsAfter = event.fpsAtEvent?.let { "%.0f".format(it) } ?: "?"
    val temp = "%.0f°C".format(event.cpuTempC)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        Icon(
            imageVector = Icons.Outlined.Thermostat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Event $index  @  $timeStr",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "FPS ${fpsBefore}→${fpsAfter}   CPU $temp",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

// ── Feature B2: Per-game aggregate history card ────────────────────────────

/**
 * Shows cross-session aggregate trends for the current game package.
 *
 * Only rendered when [history.sessionCount] >= 2; for a single session the
 * caller shows an honest "Only 1 session" message instead.
 *
 * Honesty rules:
 *  - Savings % only shown when AutoTDP was active in ≥1 qualifying session.
 *  - Trend charts each require ≥2 points (MetricLineChart enforces this).
 *  - Each chart caption says "each point = one session, oldest→newest."
 *  - savedFps spread only shown when ≥2 distinct profiles with fps exist.
 */
@Composable
private fun PerGameHistoryCard(history: PerPackageHistory) {
    val appLabel = history.appLabel ?: history.packageName
    SectionCard("${appLabel.uppercase()} over time") {
        if (history.sessionCount < 2) {
            Text(
                "Only ${history.sessionCount} session recorded for this game — " +
                    "play more to see trends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        // ── Savings headline ──────────────────────────────────────────────
        val savedPct = history.savedPercent
        if (savedPct != null) {
            Text(
                "AutoTDP saved ~${"%.1f".format(savedPct)}% of battery " +
                    "vs the measured baseline. ${history.savingsBasis}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                history.savingsBasis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        history.savedFps?.let { spread ->
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "+${"%.0f".format(spread)} fps vs your other tune for this game " +
                    "(measured profile spread — MEASURED).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.group))

        // ── Avg FPS trend ────────────────────────────────────────────────
        if (history.avgFpsTrend.size >= 2) {
            Text(
                "Avg FPS per session (oldest→newest)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Each point = one session. Only sessions with FPS data are shown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricLineChart(points = history.avgFpsTrend, heightDp = 100)
            Spacer(Modifier.height(Spacing.group))
        }

        // ── Peak temp trend ──────────────────────────────────────────────
        if (history.peakTempTrend.size >= 2) {
            Text(
                "Peak CPU temp per session (°C, oldest→newest)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Each point = one session. Rising trend with throttle events " +
                    "suggests a thermal build-up problem.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricLineChart(points = history.peakTempTrend, heightDp = 100)
            Spacer(Modifier.height(Spacing.group))
        }

        // ── Battery draw trend ───────────────────────────────────────────
        if (history.batteryPerHourTrend.size >= 2) {
            Text(
                "Avg battery draw per session (mWh/hr, oldest→newest)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Each point = one session (energy ÷ duration). " +
                    "Lower is more efficient.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricLineChart(points = history.batteryPerHourTrend, heightDp = 100)
        }

        Spacer(Modifier.height(Spacing.dense))
        Text(
            "All values are from your real recorded sessions. " +
                "${history.sessionCount} sessions total for this game.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
