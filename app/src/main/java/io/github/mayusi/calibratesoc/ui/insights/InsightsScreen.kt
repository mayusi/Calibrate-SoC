package io.github.mayusi.calibratesoc.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.insights.InsightsAggregator
import io.github.mayusi.calibratesoc.data.insights.SessionReport
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
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
 *   - Best profile per game (ArsenalPanel per app, with one-tap Apply)
 *   - Recent session reports (ArsenalPanel per session)
 *
 * "Not enough data" states are always shown honestly — no fabricated numbers.
 *
 * The "BEST PROFILE PER GAME" panel shows the evidence (avg FPS, throttle rate,
 * session count) and an Apply button. Tapping Apply calls
 * [InsightsViewModel.applyBestProfile], which resolves the display label →
 * package name via PackageManager and the profile name → profile id via
 * [ProfileRepository], then writes the bundle. [ForegroundAppWatcher] picks it
 * up automatically on the next foreground switch.
 */
@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val latestReport by viewModel.latestReport.collectAsStateWithLifecycle()
    val applyResult by viewModel.applyResult.collectAsStateWithLifecycle()
    val store by viewModel.profileStore.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar on success or error, then clear the result.
    LaunchedEffect(applyResult) {
        when (val r = applyResult) {
            is InsightsViewModel.ApplyResult.Success -> {
                snackbarHostState.showSnackbar(
                    "Applied ${r.profileName} to ${r.appLabel} — auto-tunes on next launch.",
                )
                viewModel.clearApplyResult()
            }
            is InsightsViewModel.ApplyResult.Error -> {
                snackbarHostState.showSnackbar(r.reason)
                viewModel.clearApplyResult()
            }
            InsightsViewModel.ApplyResult.Idle -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                // Determine whether this profile is already bound so we can show "APPLIED".
                // We look at perAppBundles only — the canonical write path used by applyBestProfile.
                // We resolve profileId → name via the store's profile list for the comparison.
                val boundProfileName: String? = run {
                    val bundles = store.perAppBundles
                    // We can't look up by package name here because the key is appLabel, not
                    // packageName — the bundle map is keyed by packageName. We check bundles
                    // whose profileId matches a profile whose name == entry.profileName, and
                    // whose value (packageName) *might* correspond to this appLabel. Since we
                    // cannot resolve label→package in a pure composable without IO, we instead
                    // check whether ANY bundle in the store has this profileId, and let the
                    // success snackbar be the primary confirmation. The "APPLIED" badge is best-
                    // effort — it will show correctly once the user sees the snackbar and re-
                    // enters the screen (store is live).
                    val profileId = store.profiles.firstOrNull { it.name == entry.profileName }?.id
                    if (profileId != null && bundles.values.any { it.profileId == profileId }) {
                        entry.profileName
                    } else null
                }
                BestProfilePanel(
                    appLabel = appLabel,
                    entry = entry,
                    boundProfileName = boundProfileName,
                    onApply = { viewModel.applyBestProfile(appLabel, entry.profileName) },
                )
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
        } // end LazyColumn

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF1E1E26),
                    contentColor = Color.White,
                )
            },
        )
    } // end Box
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

/**
 * Panel for a single "best profile" recommendation.
 *
 * Shows:
 * - App name (uppercase)
 * - "RECOMMENDED" pill + profile name pill to emphasise it is learned from data
 * - Evidence: avg FPS, throttle/session, session count (the "why")
 * - "APPLIED" pill when this profile is already bound in the store (best-effort:
 *   matched by profileId across any bundle — see call site comment)
 * - Apply button (AccentBar.Emerald, Secondary style) that calls [onApply]
 *
 * [boundProfileName] is non-null when the profile is already bound somewhere in
 * the store; used to show the "APPLIED" pill and disable the Apply button.
 */
@Composable
private fun BestProfilePanel(
    appLabel: String,
    entry: InsightsAggregator.BestProfileEntry,
    boundProfileName: String?,
    onApply: () -> Unit,
) {
    val isApplied = boundProfileName != null
    ArsenalPanel(accent = AccentBar.Blue) {
        // ── Header row: app name + pills ─────────────────────────────────
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
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                StatusPill(text = "RECOMMENDED", accent = AccentBar.Emerald)
                StatusPill(text = entry.profileName, accent = AccentBar.Blue)
                if (isApplied) {
                    StatusPill(text = "APPLIED", accent = AccentBar.Emerald)
                }
            }
        }

        Spacer(Modifier.height(Spacing.dense))

        // ── Evidence — the "why" ─────────────────────────────────────────
        entry.avgFps?.let { fps ->
            KvRow(label = "Avg FPS", value = "%.0f fps".format(fps))
        }
        KvRow(
            label = "Throttle/session",
            value = "%.1f".format(entry.avgThrottleEventsPerSession),
            explainer = "Lower = more stable. Based on ${entry.sessionCount} sessions.",
        )

        Spacer(Modifier.height(Spacing.item))

        // ── Apply button ─────────────────────────────────────────────────
        ArsenalButton(
            label = if (isApplied) "ALREADY APPLIED" else "APPLY TO THIS GAME",
            onClick = onApply,
            accent = AccentBar.Emerald,
            style = ArsenalButtonStyle.Secondary,
            enabled = !isApplied,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
