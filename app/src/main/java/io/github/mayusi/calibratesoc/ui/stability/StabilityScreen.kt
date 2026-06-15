package io.github.mayusi.calibratesoc.ui.stability

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.benchmark.BenchOutcome
import io.github.mayusi.calibratesoc.data.benchmark.StabilityResult
import io.github.mayusi.calibratesoc.data.benchmark.StabilityRun
import io.github.mayusi.calibratesoc.data.benchmark.StabilityTestRunner
import io.github.mayusi.calibratesoc.data.benchmark.ThrottleAnalysis
import io.github.mayusi.calibratesoc.data.benchmark.StabilityVerdict
import io.github.mayusi.calibratesoc.data.benchmark.makeThrottleVerdict
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
import io.github.mayusi.calibratesoc.ui.components.MetricLineChartCard
import io.github.mayusi.calibratesoc.ui.components.MetricLineChartOverlay
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import kotlinx.coroutines.launch
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

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StabilityHeader()
            RunControls(state = state, onStart = viewModel::start, onCancel = viewModel::cancelStability)
            result?.let { StabilityResultCard(it) }
            if (history.isEmpty() && result == null && state == StabilityTestRunner.State.Idle) {
                StabilityEmptyState()
            } else if (history.isNotEmpty()) {
                PastRunsCard(
                    history = history,
                    onDelete = { run ->
                        viewModel.deleteRun(run.id)
                        coroutineScope.launch {
                            val snackResult = snackbarHostState.showSnackbar(
                                message = "Deleted",
                                actionLabel = "Undo",
                                withDismissAction = false,
                            )
                            if (snackResult == SnackbarResult.ActionPerformed) {
                                viewModel.reinsertRun(run)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StabilityEmptyState() {
    EmptyState(
        icon = Icons.Outlined.Speed,
        title = "No stability runs yet",
        body = "Run a stability test to see how your device holds up under sustained load. Results appear here.",
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

private val historyDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun PastRunsCard(history: List<StabilityRun>, onDelete: (StabilityRun) -> Unit) {
    SectionCard("Past runs") {
        history.forEachIndexed { index, run ->
            if (index > 0) HorizontalDivider()
            PastRunRow(run = run, onDelete = { onDelete(run) })
        }
    }
}

@Composable
private fun PastRunRow(run: StabilityRun, onDelete: () -> Unit) {
    val color = stabilityVerdictColor(run.stabilityPct)
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (run.stabilityPct != null) "${run.stabilityPct}%" else "N/A",
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
        IconButton(onClick = {
            val text = stabilityRunShareText(run)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, "Share stability result"))
        }) {
            Icon(
                Icons.Outlined.Share,
                contentDescription = "Share run",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            "Runs the CPU and GPU flat-out together and checks how well your device " +
                "holds peak under sustained load. 100% = no throttling. Expect it to " +
                "get hot and the fans to spin.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunControls(
    state: StabilityTestRunner.State,
    onStart: (loopCount: Int, loopMs: Long) -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard("Run") {
        when (state) {
            StabilityTestRunner.State.Idle -> {
                Text(
                    "Runs the CPU and GPU flat-out together to find real sustained performance — expect it to get hot and the fans to spin. Keep the screen on and the device unplugged for honest numbers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RunButton(
                    title = "Quick  (6 loops · ~2 min)",
                    subtitle = "Shorter sustained stress — catches early throttling.",
                    onClick = { onStart(6, 20_000L) },
                )
                RunButton(
                    title = "Full  (9 loops · ~3 min)",
                    subtitle = "Full sustained stress with CPU+GPU hammering — surfaces the thermal throttle floor.",
                    onClick = { onStart(9, 20_000L) },
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
                    "Keep screen on + device unplugged. CPU and GPU are pegged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop")
                }
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
    val analysis = ThrottleAnalysis.from(result.samples, killTempC = 95f)
    val verdict = makeThrottleVerdict(analysis, result.peakTempC, killTempC = 95f, sustainedPct = result.stabilityPct)
    val verdictColor = when (verdict.colorHint) {
        "tertiary" -> MaterialTheme.colorScheme.tertiary
        "secondary" -> MaterialTheme.colorScheme.secondary
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val context = LocalContext.current

    SectionCard("Result") {
        // Headline sustained/peak % + verdict.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (result.stabilityPct != null) "${result.stabilityPct}%" else "N/A (aborted)",
                fontFamily = FontFamily.Monospace,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = verdictColor,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Sustained/Peak",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    verdict.word,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = verdictColor,
                )
            }
            IconButton(onClick = {
                val text = stabilityShareText(result, analysis, verdict)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(intent, "Share stability result"))
            }) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share stability result",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            verdict.explanation,
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

        // Summary row: sustained vs peak FPS, CPU MHz, temps, power.
        SectionCard("Summary") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KvRow(
                    label = "Sustained FPS",
                    value = "%.1f".format(result.avgSustainedFps),
                    explainer = "Average of last 25% of loops"
                )
                KvRow(
                    label = "Peak FPS",
                    value = "%.1f".format(result.maxFps),
                    explainer = "Highest single loop"
                )
                if (analysis != null) {
                    KvRow(
                        label = "Sustained MHz",
                        value = "${analysis.sustainedMhz}",
                        explainer = "CPU clock in last 25%"
                    )
                    KvRow(
                        label = "Peak MHz",
                        value = "${analysis.peakMhz}",
                    )
                }
                KvRow(
                    label = "Peak CPU temp",
                    value = "%.1f°C".format(result.peakTempC),
                )
                result.peakGpuTempC?.let {
                    KvRow(
                        label = "Peak GPU temp",
                        value = "%.1f°C".format(it),
                    )
                }
                if (analysis != null) {
                    KvRow(
                        label = "Thermal headroom",
                        value = "%.1f°C".format(analysis.thermalHeadroomC ?: 0f),
                        explainer = "Buffer before 95°C kill"
                    )
                    analysis.avgPowerMw?.let {
                        KvRow(
                            label = "Avg power",
                            value = "%.0f W".format(it / 1000.0),
                        )
                    }
                    analysis.timeToThrottleMs?.let {
                        KvRow(
                            label = "Time to throttle",
                            value = "${it / 1000}s",
                        )
                    }
                }
            }
        }

        // Four charts: FPS, Temperature, Clocks, Power.
        MetricLineChartCard(
            title = "FPS",
            caption = "Per-loop FPS — flat = stable, dropping = throttling",
            points = result.loopFps.map { it.toFloat() },
        )

        if (result.samples.size >= 2) {
            val cpuTempSeries = result.samples.map { it.cpuMaxTempC }
            val gpuTempSeries = result.samples.mapNotNull { it.gpuTempC }
            SectionCard("Temperature") {
                Text(
                    if (gpuTempSeries.isNotEmpty())
                        "CPU and GPU temperature over time — hotter = closer to throttling"
                    else
                        "CPU temperature over time — hotter = closer to throttling",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (gpuTempSeries.isNotEmpty()) {
                    MetricLineChartOverlay(cpuTempSeries, gpuTempSeries)
                } else {
                    MetricLineChart(cpuTempSeries)
                }
            }
        }

        if (result.samples.size >= 2) {
            val cpuMhzSeries = result.samples.map { it.cpuMaxMhz.toFloat() }
            val gpuMhzSeries = result.samples.mapNotNull { it.gpuMaxMhz?.toFloat() }
            SectionCard("Clocks") {
                Text(
                    if (gpuMhzSeries.isNotEmpty())
                        "CPU and GPU clock frequencies — flat = stable, dropping = throttling"
                    else
                        "CPU clock frequency — flat = stable, dropping = throttling",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (gpuMhzSeries.isNotEmpty()) {
                    MetricLineChartOverlay(cpuMhzSeries, gpuMhzSeries)
                } else {
                    MetricLineChart(cpuMhzSeries)
                }
            }
        }

        if (result.samples.size >= 2) {
            val powerSeries = result.samples.mapNotNull { (it.batteryDrawMw?.toFloat() ?: 0f).takeIf { p -> p > 0f } }
            if (powerSeries.size >= 2) {
                MetricLineChartCard(
                    title = "Power",
                    caption = "Battery draw in watts over time",
                    points = powerSeries.map { it / 1000f },  // mW to W
                )
            }
        }

        Text(
            "FPS, clocks, and temps are from this app's own tests — compare YOUR device's runs, " +
                "not against other phones.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun stabilityVerdictColor(pct: Int?): Color = when {
    pct == null -> MaterialTheme.colorScheme.onSurfaceVariant
    pct >= 95 -> MaterialTheme.colorScheme.tertiary
    pct in 85..94 -> MaterialTheme.colorScheme.primary
    pct in 75..84 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.error
}

// ─── Share helpers ────────────────────────────────────────────────────

/**
 * Build a plain-text share summary for a live [StabilityResult] + its
 * analysis and verdict.
 */
internal fun stabilityShareText(
    result: StabilityResult,
    analysis: ThrottleAnalysis?,
    verdict: StabilityVerdict,
): String = buildString {
    appendLine("Calibrate SoC — Stability Result")
    appendLine()
    appendLine("Stability: ${if (result.stabilityPct != null) "${result.stabilityPct}%" else "N/A"}  •  ${verdict.word}")
    appendLine(verdict.explanation)
    appendLine()
    appendLine("Sustained FPS:  %.1f".format(result.avgSustainedFps))
    appendLine("Peak FPS:       %.1f".format(result.maxFps))
    if (analysis != null) {
        appendLine("Sustained MHz:  ${analysis.sustainedMhz}")
        appendLine("Peak MHz:       ${analysis.peakMhz}")
    }
    appendLine("Peak CPU temp:  %.1f°C".format(result.peakTempC))
    result.peakGpuTempC?.let { appendLine("Peak GPU temp:  %.1f°C".format(it)) }
    if (analysis != null) {
        analysis.thermalHeadroomC?.let { appendLine("Thermal headroom: %.1f°C".format(it)) }
        analysis.avgPowerMw?.let { appendLine("Avg power:      %.2f W".format(it / 1000.0)) }
    }
    appendLine("Duration:       %.0f s".format(result.durationMs / 1000.0))
    appendLine()
    append("— via Calibrate SoC")
}

/**
 * Build a plain-text share summary from a persisted [StabilityRun]
 * (history list rows).
 */
internal fun stabilityRunShareText(run: StabilityRun): String {
    val analysis = ThrottleAnalysis.from(run.samples, killTempC = 95f)
    val verdict = makeThrottleVerdict(
        analysis,
        peakTempC = run.peakTempC,
        killTempC = 95f,
        sustainedPct = run.stabilityPct,
    )
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return buildString {
        appendLine("Calibrate SoC — Stability Result")
        appendLine("Date: ${dateFmt.format(Date(run.startedAtMs))}")
        appendLine()
        appendLine("Stability: ${if (run.stabilityPct != null) "${run.stabilityPct}%" else "N/A"}  •  ${verdict.word}")
        appendLine(verdict.explanation)
        appendLine()
        appendLine("Min FPS: %.1f   Max FPS: %.1f".format(run.minFps, run.maxFps))
        appendLine("Peak temp: %.1f°C".format(run.peakTempC))
        if (analysis != null) {
            appendLine("Sustained MHz: ${analysis.sustainedMhz}  Peak MHz: ${analysis.peakMhz}")
            analysis.thermalHeadroomC?.let { appendLine("Thermal headroom: %.1f°C".format(it)) }
            analysis.avgPowerMw?.let { appendLine("Avg power: %.2f W".format(it / 1000.0)) }
        }
        appendLine()
        append("— via Calibrate SoC")
    }
}

