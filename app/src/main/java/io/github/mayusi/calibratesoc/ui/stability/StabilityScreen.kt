package io.github.mayusi.calibratesoc.ui.stability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import io.github.mayusi.calibratesoc.data.benchmark.BenchOutcome
import io.github.mayusi.calibratesoc.data.benchmark.StabilityResult
import io.github.mayusi.calibratesoc.data.benchmark.StabilityRun
import io.github.mayusi.calibratesoc.data.benchmark.StabilityTestRunner
import io.github.mayusi.calibratesoc.data.benchmark.ThrottleSample
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stability Test sub-screen — sustained GPU stress (Wild Life Extreme
 * Stress style). Lives inside the Benchmark screen behind a segmented
 * toggle (see BenchmarkScreen). Not a standalone bottom-nav destination.
 */
@Composable
fun StabilityScreen(viewModel: StabilityViewModel = hiltViewModel()) {
    val state by viewModel.runnerState.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StabilityHeader()
        RunControls(state = state, onStart = viewModel::start)
        result?.let { StabilityResultCard(it) }
        if (history.isNotEmpty()) {
            PastRunsCard(history = history, onDelete = viewModel::deleteRun)
        }
    }
}

private val historyDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun PastRunsCard(history: List<StabilityRun>, onDelete: (Long) -> Unit) {
    SectionCard("Past runs") {
        history.forEachIndexed { index, run ->
            if (index > 0) HorizontalDivider()
            PastRunRow(run = run, onDelete = { onDelete(run.id) })
        }
    }
}

@Composable
private fun PastRunRow(run: StabilityRun, onDelete: () -> Unit) {
    val (color, _) = stabilityVerdict(run.stabilityPct)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${run.stabilityPct}%",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                historyDateFormat.format(Date(run.startedAtMs)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Min %.1f · Max %.1f FPS · Peak %.1f°C".format(
                    run.minFps, run.maxFps, run.peakTempC,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete run",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StabilityHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Stability Test",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Runs the GPU flat-out, back-to-back, and checks if performance holds. " +
                "100% = no throttling under sustained load.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunControls(
    state: StabilityTestRunner.State,
    onStart: (loopCount: Int, loopMs: Long) -> Unit,
) {
    SectionCard("Run") {
        when (state) {
            StabilityTestRunner.State.Idle -> {
                Text(
                    "Pick a length. Keep the screen on and the device unplugged for honest numbers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RunButton(
                    title = "Quick  (10 loops · ~5 min)",
                    subtitle = "Shorter sustained stress — catches early throttling.",
                    onClick = { onStart(10, 30_000L) },
                )
                RunButton(
                    title = "Full  (20 loops · ~10 min)",
                    subtitle = "Full sustained stress — surfaces the deep thermal throttle floor.",
                    onClick = { onStart(20, 30_000L) },
                )
            }
            is StabilityTestRunner.State.Running -> {
                Text(
                    "Running loop ${state.loopIndex + 1} / ${state.loopCount}…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Keep screen on + device unplugged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunButton(title: String, subtitle: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StabilityResultCard(result: StabilityResult) {
    val (color, word) = stabilityVerdict(result.stabilityPct)

    SectionCard("Result") {
        // Headline stability %.
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${result.stabilityPct}%",
                fontFamily = FontFamily.Monospace,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Column {
                Text(
                    "Stability",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    word,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
            }
        }

        Text(
            "Min loop: %.1f FPS   ·   Max loop: %.1f FPS".format(result.minFps, result.maxFps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Peak CPU temp: %.1f°C".format(result.peakTempC),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (result.outcome == BenchOutcome.ABORTED_TEMP) {
            Text(
                "Stopped early — thermal limit reached.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Per-loop FPS curve.
        Text(
            "Per-loop FPS — flat = stable, sloping down = throttling",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FpsChart(result.loopFps)

        // Thermal curve from telemetry samples.
        if (result.samples.size >= 2) {
            Text(
                "CPU temperature over time",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThermalChart(result.samples)
        }

        Text(
            "FPS here is from this app's own GPU test — compare YOUR device's runs, " +
                "not against other phones.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun stabilityVerdict(pct: Int): Pair<Color, String> = when {
    pct >= 97 -> MaterialTheme.colorScheme.tertiary to "Rock solid"
    pct in 90..96 -> MaterialTheme.colorScheme.primary to "Stable"
    pct in 80..89 -> MaterialTheme.colorScheme.secondary to "Some throttling"
    else -> MaterialTheme.colorScheme.error to "Heavy throttling"
}

// ─── Charts (local equivalents — BenchmarkScreen's are private) ───────

@Composable
private fun FpsChart(loopFps: List<Double>) {
    if (loopFps.size < 2) return
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(loopFps) {
        producer.runTransaction {
            lineSeries { series(loopFps.map { it.toFloat() }) }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = producer,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
    )
}

@Composable
private fun ThermalChart(samples: List<ThrottleSample>) {
    if (samples.size < 2) return
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(samples) {
        producer.runTransaction {
            lineSeries { series(samples.map { it.cpuMaxTempC }) }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = producer,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
    )
}
