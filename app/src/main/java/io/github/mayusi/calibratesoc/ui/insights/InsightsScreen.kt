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
 * The "BEST PROFILE PER GAME" panel is rendered from the package-keyed
 * [InsightsAggregator.InsightsSummary.bestProfilePerPackage] map. It shows the
 * evidence (avg FPS, throttle rate, session count) and an Apply button. Tapping
 * Apply calls [InsightsViewModel.applyBestProfileByPackage], which binds the
 * bundle using the stable packageName directly (no fragile label→package scan),
 * so the right app is always targeted. The "APPLIED" badge is computed by
 * matching THIS package's own bundle (perAppBundles[packageName]) against the
 * recommended profile — never "any bundle with this profileId" — so it can't
 * mislabel a sibling app. [ForegroundAppWatcher] picks up the bound bundle
 * automatically on the next foreground switch.
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Best profile per game ─────────────────────────────────────────
        // Rendered from bestProfilePerPackage (package-keyed) — the honest,
        // stable source. packageName is the real bundle key, so the APPLIED
        // badge and the apply action both bind the EXACT app, never a sibling
        // that happens to share a display label.
        if (summary.bestProfilePerPackage.isNotEmpty()) {
            item {
                SectionHeader(title = "BEST PROFILE PER GAME", accent = AccentBar.Blue)
            }
            items(summary.bestProfilePerPackage.entries.toList(), key = { it.key }) { (packageName, entry) ->
                // APPLIED is true ONLY when THIS package's own bundle is bound to
                // THIS recommended profile — matched by packageName (the bundle
                // key), not "any bundle in the store with this profileId". That
                // package-precise check is what makes the badge honest.
                val boundProfileName: String? = run {
                    val profileId = store.profiles.firstOrNull { it.name == entry.profileName }?.id
                    val boundId = store.perAppBundles[packageName]?.profileId
                    if (profileId != null && boundId == profileId) entry.profileName else null
                }
                // appLabel may be null when the package never recorded a label —
                // fall back to the packageName so the panel still identifies the app.
                val displayLabel = entry.appLabel ?: packageName
                BestProfilePanel(
                    appLabel = displayLabel,
                    profileName = entry.profileName,
                    avgFps = entry.avgFps,
                    avgThrottleEventsPerSession = entry.avgThrottleEventsPerSession,
                    sessionCount = entry.sessionCount,
                    boundProfileName = boundProfileName,
                    onApply = {
                        viewModel.applyBestProfileByPackage(
                            packageName = packageName,
                            appLabel = displayLabel,
                            profileName = entry.profileName,
                        )
                    },
                )
            }
        } else if (summary.insufficientDataReason == null) {
            item {
                ArsenalPanel(accent = AccentBar.Neutral) {
                    Text(
                        "No per-game profile data yet — play a few sessions with different profiles to see which works best.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            color = MaterialTheme.colorScheme.onSurface,
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
 * - "APPLIED" pill when THIS app's own bundle is bound to this profile (matched
 *   by packageName at the call site — precise, never a sibling app)
 * - Apply button (AccentBar.Emerald, Secondary style) that calls [onApply]
 *
 * [boundProfileName] is non-null when this app's bundle is bound to the
 * recommended profile; used to show the "APPLIED" pill and disable the button.
 */
@Composable
private fun BestProfilePanel(
    appLabel: String,
    profileName: String,
    avgFps: Float?,
    avgThrottleEventsPerSession: Double,
    sessionCount: Int,
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
                StatusPill(text = profileName, accent = AccentBar.Blue)
                if (isApplied) {
                    StatusPill(text = "APPLIED", accent = AccentBar.Emerald)
                }
            }
        }

        Spacer(Modifier.height(Spacing.dense))

        // ── Evidence — the "why" ─────────────────────────────────────────
        avgFps?.let { fps ->
            KvRow(label = "Avg FPS", value = "%.0f fps".format(fps))
        }
        KvRow(
            label = "Throttle/session",
            value = "%.1f".format(avgThrottleEventsPerSession),
            explainer = "Lower = more stable. Based on $sessionCount sessions.",
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
