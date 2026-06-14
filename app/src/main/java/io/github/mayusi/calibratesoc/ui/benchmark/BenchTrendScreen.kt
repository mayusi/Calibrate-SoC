package io.github.mayusi.calibratesoc.ui.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.benchmark.BenchFlavor
import io.github.mayusi.calibratesoc.data.benchmark.BenchScores
import io.github.mayusi.calibratesoc.data.benchmark.BenchTrend
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trends tab for the Benchmark screen.
 *
 * Shows how the user's benchmark scores evolve over time, per flavor.
 * Only COMPLETED runs are plotted. Scores are same-device-relative ONLY;
 * the [BenchScores.HONESTY] caption is always visible.
 */
@Composable
fun BenchTrendScreen(
    viewModel: BenchTrendViewModel = hiltViewModel(),
) {
    val result     by viewModel.trendResult.collectAsStateWithLifecycle()
    val metric     by viewModel.selectedMetric.collectAsStateWithLifecycle()
    val flavor     by viewModel.selectedFlavor.collectAsStateWithLifecycle()

    // Loading / truly empty (no completed runs at all)
    if (result == null) {
        TrendEmptyState()
        return
    }

    val trend = result!!
    val activeSeries = trend.seriesFor(metric)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item { TrendHeader() }

        // ── Flavor selector ───────────────────────────────────────────────
        item {
            FlavorSelector(
                selected = trend.flavor,
                onSelect = viewModel::selectFlavor,
            )
        }

        // ── Metric selector ───────────────────────────────────────────────
        item {
            MetricSelector(
                selected = metric,
                flavor   = trend.flavor,
                onSelect = viewModel::selectMetric,
            )
        }

        // ── Empty-series state: < 2 completed runs ────────────────────────
        if (activeSeries.size < 2) {
            item { TrendEmptyState() }
        } else {
            // ── Summary row ───────────────────────────────────────────────
            val delta = trend.deltaFor(metric)
            item {
                SectionCard("${metric.label} trend · ${trend.flavor.name.lowercase().replaceFirstChar { it.uppercase() }}") {
                    // Honesty caption — always first, always visible.
                    Text(
                        BenchScores.HONESTY,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (delta != null) {
                        SummaryRow(delta = delta, runCount = trend.totalRuns)
                    }

                    // ── Chart ─────────────────────────────────────────────
                    Text(
                        "Score over time (oldest → newest) · ${trend.totalRuns} completed ${trend.flavor.name.lowercase()} runs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TrendChartWithDates(
                        series   = activeSeries,
                        heightDp = 180,
                    )

                    // ── Run index legend ──────────────────────────────────
                    RunLegend(activeSeries)
                }
            }
        }
    }
}

// ─── Helpers to pick series/delta by selected metric ─────────────────────────

private fun BenchTrend.TrendResult.seriesFor(metric: TrendMetric): List<BenchTrend.Point> =
    when (metric) {
        TrendMetric.OVERALL -> overallSeries
        TrendMetric.CPU     -> cpuSeries
        TrendMetric.GPU     -> gpuSeries
        TrendMetric.MEMORY  -> memorySeries
    }

private fun BenchTrend.TrendResult.deltaFor(metric: TrendMetric): BenchTrend.DeltaSummary? =
    when (metric) {
        TrendMetric.OVERALL -> overallDelta
        TrendMetric.CPU     -> cpuDelta
        TrendMetric.GPU     -> gpuDelta
        TrendMetric.MEMORY  -> memoryDelta
    }

// ─── Composables ─────────────────────────────────────────────────────────────

@Composable
private fun TrendHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Text(
            "Trends",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Track how your scores change over time — before and after each tune. " +
                "Only completed runs are shown. Scores are meaningful only when comparing YOUR OWN runs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlavorSelector(
    selected: BenchFlavor,
    onSelect: (BenchFlavor) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Text(
            "Benchmark type",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            BenchFlavor.values().forEach { flavor ->
                FilterChip(
                    selected = selected == flavor,
                    onClick  = { onSelect(flavor) },
                    label    = {
                        Text(
                            flavor.name.lowercase().replaceFirstChar { it.uppercase() },
                        )
                    },
                )
            }
        }
        Text(
            "Scores are not comparable across types — a Quick run is CPU-only.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricSelector(
    selected: TrendMetric,
    flavor: BenchFlavor,
    onSelect: (TrendMetric) -> Unit,
) {
    // GPU and Memory aren't available on Quick flavor — grey them out visually
    // by still showing them but noting they have no data (the series will be
    // empty and the chart will show the empty state).
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
        TrendMetric.values().forEach { metric ->
            FilterChip(
                selected = selected == metric,
                onClick  = { onSelect(metric) },
                label    = { Text(metric.label) },
            )
        }
    }
}

@Composable
private fun SummaryRow(
    delta: BenchTrend.DeltaSummary,
    runCount: Int,
) {
    val changeSign   = if (delta.changePercent >= 0) "+" else ""
    val changeColor  = if (delta.improved) Color(0xFF34D399) else MaterialTheme.colorScheme.error
    val changeFmt    = "%s%.1f%%".format(changeSign, delta.changePercent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.dense),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.Bottom,
    ) {
        SummaryTile(
            label = "Latest",
            value = delta.latest.toString(),
            modifier = Modifier.weight(1f),
        )
        SummaryTile(
            label = "Best ever",
            value = delta.best.toString(),
            modifier = Modifier.weight(1f),
        )
        SummaryTile(
            label = "vs. first run",
            value = changeFmt,
            valueColor = changeColor,
            modifier = Modifier.weight(1f),
        )
        SummaryTile(
            label = "Runs",
            value = runCount.toString(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunLegend(series: List<BenchTrend.Point>) {
    val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Run legend",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        series.takeLast(10).forEach { point ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                Text(
                    "#${point.runIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 2.dp),
                )
                Text(
                    point.runName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    dateFmt.format(Date(point.startedAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    point.score.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (series.size > 10) {
            Text(
                "…and ${series.size - 10} older run(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrendEmptyState() {
    EmptyState(
        icon  = Icons.Outlined.ShowChart,
        title = "Not enough data yet",
        body  = "Run at least 2 benchmarks of the same type to see trends. " +
                "Try running a Standard benchmark before and after a tune.",
        modifier = Modifier.padding(top = 32.dp),
    )
}

// ─── Trend chart with date labels ────────────────────────────────────
// Reuses MetricLineChart (the shared chart component) and adds an x-axis
// date legend below it, since Vico's ValueFormatter API varies across
// versions and we want to stay compatible with the project's pinned version.

@Composable
private fun TrendChartWithDates(
    series: List<BenchTrend.Point>,
    heightDp: Int = 180,
) {
    if (series.size < 2) return

    val dateFmt = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

    // The chart itself (score values as floats)
    MetricLineChart(
        points   = series.map { it.score.toFloat() },
        heightDp = heightDp,
    )

    // Date legend below the chart — show first, middle, and last dates as axis labels
    if (series.size >= 2) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                dateFmt.format(Date(series.first().startedAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (series.size >= 3) {
                Text(
                    dateFmt.format(Date(series[series.size / 2].startedAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                dateFmt.format(Date(series.last().startedAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
