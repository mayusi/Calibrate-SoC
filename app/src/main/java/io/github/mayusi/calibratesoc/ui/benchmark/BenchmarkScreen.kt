package io.github.mayusi.calibratesoc.ui.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.benchmark.BenchFlavor
import io.github.mayusi.calibratesoc.data.benchmark.BenchRating
import io.github.mayusi.calibratesoc.data.benchmark.BenchRun
import io.github.mayusi.calibratesoc.data.benchmark.BenchScores
import io.github.mayusi.calibratesoc.data.benchmark.BenchmarkRunner
import io.github.mayusi.calibratesoc.data.benchmark.ThrottleAnalysis
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
import io.github.mayusi.calibratesoc.ui.components.MetricLineChartOverlay
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.stability.StabilityScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benchmark screen — run / history / compare.
 *
 * Redesigned for clarity:
 *   - Headline result card: composite score + BenchRating word + one sentence.
 *   - Category cards (CPU / Memory / GPU / Thermal) with explainers.
 *   - Per-metric labeled rows with plain-English description.
 *   - Throttle curve annotation (Full runs).
 *   - Compare view: card-based + color-coded deltas.
 */
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel = hiltViewModel()) {
    // Segmented toggle: 0 = Benchmark (existing), 1 = Stability (new).
    // Kept inside this screen instead of a 7th bottom-nav tab — the bar
    // already carries 6 and a 7th is cramped on a landscape handheld.
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = tab == 0,
                onClick = { tab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Benchmark") }
            SegmentedButton(
                selected = tab == 1,
                onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Stability") }
        }
        when (tab) {
            0 -> BenchmarkContent(viewModel)
            else -> StabilityScreen()
        }
    }
}

@Composable
private fun BenchmarkContent(viewModel: BenchmarkViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val runnerState by viewModel.runnerState.collectAsStateWithLifecycle()
    val selection by viewModel.compareSelection.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BenchHeader() }
        item {
            RunControls(runnerState) { flavor, name -> viewModel.runBenchmark(flavor, name) }
        }

        if (selection.size == 2) {
            val a = history.firstOrNull { it.id == selection[0] }
            val b = history.firstOrNull { it.id == selection[1] }
            if (a != null && b != null) {
                item { CompareCard(a, b, capability, onClose = viewModel::clearSelection) }
            }
        }

        if (history.isEmpty()) {
            item { BenchEmptyState() }
        } else {
            items(history, key = { it.id }) { run ->
                RunCard(
                    run = run,
                    report = capability,
                    selected = run.id in selection,
                    onToggleSelection = { viewModel.toggleSelection(run.id) },
                    onDelete = { viewModel.delete(run.id) },
                )
            }
        }
    }
}

// ─── Header ──────────────────────────────────────────────────────────

@Composable
private fun BenchHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Benchmark",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Run a benchmark before and after tuning to see the real difference. " +
                "Scores are only meaningful when compared against your OWN earlier runs — " +
                "not across different chips.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Empty state ──────────────────────────────────────────────────────

@Composable
private fun BenchEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Speed,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No benchmarks yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Run your first benchmark to see scores and compare runs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Run controls ─────────────────────────────────────────────────────

@Composable
private fun RunControls(
    state: BenchmarkRunner.State,
    onRun: (BenchFlavor, String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                BenchmarkRunner.State.Idle -> {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Run name (optional)") },
                        placeholder = { Text("e.g. Stock baseline, +200 MHz tune") },
                        singleLine = true,
                        supportingText = {
                            Text(
                                "Helps you find this run later. Leave blank for an auto timestamp.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                    FlavorButton(
                        title = "Quick",
                        subtitle = "~20 s · single-thread CPU integer. Best for quick before/after checks.",
                        onClick = { onRun(BenchFlavor.QUICK, name) },
                    )
                    FlavorButton(
                        title = "Standard",
                        subtitle = "~1 min · full suite: CPU int (single + multi) + float + AES + RAM + GPU.",
                        onClick = { onRun(BenchFlavor.STANDARD, name) },
                    )
                    FlavorButton(
                        title = "Full",
                        subtitle = "~3 min · Standard + 2-min sustained throttle curve. Shows how the chip behaves under heat.",
                        onClick = { onRun(BenchFlavor.FULL, name) },
                    )
                }
                is BenchmarkRunner.State.Running -> {
                    Text(
                        "Running ${state.flavor.name.lowercase().replaceFirstChar { it.uppercase() }}…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Keep screen on + device unplugged. ETA ~${state.etaMs / 1000}s.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlavorButton(title: String, subtitle: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─── Run card (history) ───────────────────────────────────────────────

@Composable
private fun RunCard(
    run: BenchRun,
    report: CapabilityReport?,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val rating = remember(run, report) {
        if (report != null) BenchRating.rate(run, report) else null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── Title row ───────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        run.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        dateFmt.format(Date(run.startedAtMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlavorChip(run.flavor)
            }

            // ── Headline score + rating ──────────────────────────
            run.overallScore?.let { score ->
                HeadlineScoreCard(score, rating)
            }

            // ── Category sub-scores (CPU / GPU / Memory) ─────────
            CategoryScoreRow(remember(run) { BenchScores.from(run) })

            // ── Category cards ───────────────────────────────────
            CpuCard(run)
            if (run.kernels.memoryBandwidthMBps != null) MemoryCard(run)
            if (run.kernels.gpuFps != null || run.kernels.cpuDrawCallFps != null) GpuDetailCard(run)
            if (run.throttleSamples.isNotEmpty()) PowerThermalCard(run)

            // ── Snapshot context ─────────────────────────────────
            SnapshotContext(run)

            // ── Actions ──────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onToggleSelection) {
                    Text(if (selected) "Unselect" else "Select to compare")
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

// ─── Headline score card ──────────────────────────────────────────────

@Composable
private fun HeadlineScoreCard(
    score: Long,
    rating: BenchRating.Rating?,
) {
    val ratingColor = rating?.let { r ->
        when (r.color) {
            BenchRating.RatingColor.TERTIARY -> MaterialTheme.colorScheme.tertiary
            BenchRating.RatingColor.PRIMARY -> MaterialTheme.colorScheme.primary
            BenchRating.RatingColor.SECONDARY -> MaterialTheme.colorScheme.secondary
            BenchRating.RatingColor.OUTLINE -> MaterialTheme.colorScheme.outline
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    score.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column {
                    Text(
                        "Composite score",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    rating?.word?.let { word ->
                        Text(
                            word,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ratingColor ?: MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Text(
                "Higher is faster. Best used to compare YOUR OWN runs before/after a tune — not across different chips.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            rating?.let { r ->
                val displayText = r.abortReason ?: r.oneSentence
                Text(
                    displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = ratingColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─── Category sub-scores row ──────────────────────────────────────────

@Composable
private fun CategoryScoreRow(scores: BenchScores) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryMiniCard(
                "CPU",
                scores.cpu,
                Color(0xFF60A5FA),
                Modifier.weight(1f),
            )
            CategoryMiniCard(
                "GPU",
                scores.gpu,
                Color(0xFFA78BFA),
                Modifier.weight(1f),
            )
            CategoryMiniCard(
                "Memory",
                scores.memory,
                Color(0xFF34D399),
                Modifier.weight(1f),
            )
        }
        Text(
            BenchScores.HONESTY,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryMiniCard(
    label: String,
    value: Long?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                value?.toString() ?: "—",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Category cards ───────────────────────────────────────────────────

@Composable
private fun CpuCard(run: BenchRun) {
    val k = run.kernels
    if (k.cpuIntegerSingle == null && k.cpuIntegerMulti == null &&
        k.cpuFloat == null && k.cpuAes == null) return

    SectionCard("CPU") {
        Text(
            "Raw compute — how fast the processor runs math and crypto. " +
                "Single-thread shows peak per-core speed; multi-thread shows all cores combined.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        k.cpuIntegerSingle?.let {
            KvRow(
                label = "Integer (1 thread)",
                value = it.toString(),
                explainer = "Higher = faster per-core integer math. Drives most game logic.",
            )
        }
        k.cpuIntegerMulti?.let {
            KvRow(
                label = "Integer (all threads)",
                value = it.toString(),
                explainer = "All cores in parallel. Shows multi-tasking headroom.",
            )
        }
        k.cpuFloat?.let {
            KvRow(
                label = "Floating-point",
                value = it.toString(),
                explainer = "Physics, audio, graphics transform work.",
            )
        }
        k.cpuAes?.let {
            KvRow(
                label = "AES-128 encryption",
                value = it.toString(),
                explainer = "Storage & network crypto. High = fast app I/O.",
            )
        }
    }
}

@Composable
private fun MemoryCard(run: BenchRun) {
    val bw = run.kernels.memoryBandwidthMBps ?: return
    SectionCard("Memory") {
        Text(
            "How quickly the CPU can read/write RAM. " +
                "Higher bandwidth = smoother texture streaming and large-data workloads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KvRow(
            label = "RAM bandwidth",
            value = "%.1f MB/s".format(bw),
            explainer = "Sequential read/write throughput. Affects game asset loading.",
        )
    }
}

@Composable
private fun GpuDetailCard(run: BenchRun) {
    val k = run.kernels
    if (k.gpuFps == null && k.cpuDrawCallFps == null) return
    SectionCard("GPU detail") {
        Text(
            "Graphics rendering speed and frame pacing. Averages hide stutter — " +
                "the 1% low and consistency show how even the frames actually are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        k.gpuFps?.let {
            KvRow(
                label = "Avg FPS (triangle storm)",
                value = "%.1f FPS".format(it),
                explainer = "Raw GPU fill rate. Higher = better graphics headroom.",
            )
        }
        k.gpuP50Fps?.let {
            KvRow(
                label = "Median FPS",
                value = "%.1f FPS".format(it),
                explainer = "The typical frame's speed — less skewed by outliers than the average.",
            )
        }
        k.gpuP1LowFps?.let {
            KvRow(
                label = "1% low FPS",
                value = "%.1f FPS".format(it),
                explainer = "FPS you sustain 99% of the time — derived from the 99th-percentile slowest frame.",
            )
        }
        k.gpuP99FrameMs?.let {
            KvRow(
                label = "p99 frame time",
                value = "%.2f ms".format(it),
                explainer = "The slowest 1% of frames take this long — high = visible stutter.",
            )
        }
        k.gpuFrameConsistencyPct?.let { pct ->
            MetricBarRow(
                label = "Consistency",
                value = "%.0f%%".format(pct),
                explainer = "100% = perfectly even frame pacing.",
                fraction = (pct / 100.0).toFloat().coerceIn(0f, 1f),
            )
        }
        k.cpuDrawCallFps?.let {
            KvRow(
                label = "CPU draw-call ceiling",
                value = "%.0f calls/s".format(it),
                explainer = "If lower than GPU FPS, your games may be CPU-bottlenecked.",
            )
        }
        k.cpuUsageDuringGpuPct?.let { pct ->
            val hint = when {
                pct >= 70 -> "CPU was heavily loaded during GPU test — likely CPU-limited."
                pct <= 30 -> "CPU was idle during GPU test — pure GPU-bound workload."
                else -> "Mixed CPU/GPU load during GPU test."
            }
            KvRow(
                label = "CPU usage during GPU test",
                value = "$pct%",
                explainer = hint,
            )
        }
        val frameCurve = k.gpuFrameTimesMs
        if (frameCurve != null && frameCurve.size >= 2) {
            Text(
                "Per-frame time (ms) — flat = smooth, spikes = stutter.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricLineChart(frameCurve)
        }
    }
}

@Composable
private fun PowerThermalCard(run: BenchRun) {
    val samples = run.throttleSamples
    if (samples.isEmpty()) return
    val a = remember(run) { ThrottleAnalysis.from(samples, killTempC = 85f) } ?: return

    val throttleAnnotation = if (a.dropPct < 5.0) {
        "Held ${a.sustainedMhz} MHz — no throttling detected."
    } else {
        "Dropped %.0f%% under sustained load (peak ${a.startMhz} → sustained ${a.sustainedMhz} MHz).".format(a.dropPct)
    }

    SectionCard("Power & thermals") {
        Text(
            "Shows how the chip behaves under continuous load — peak is easy, " +
                "sustained is what matters in long gaming sessions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            throttleAnnotation,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        KvRow(
            label = "Sustained vs peak clock",
            value = "${a.sustainedMhz} / ${a.startMhz} MHz",
            explainer = "Sustained (last 25%) vs peak. Sustained is what you actually get in long sessions.",
        )
        KvRow(
            label = "Time to throttle",
            value = a.timeToThrottleMs?.let { "%.1f s".format(it / 1000.0) } ?: "no throttle",
            explainer = "How long the chip held ≥95% of peak clock before dropping.",
        )
        KvRow(
            label = "Peak CPU temp",
            value = "%.1f°C".format(a.peakCpuTempC),
            explainer = "Hottest CPU reading during the sustained test.",
        )
        a.peakGpuTempC?.let {
            KvRow(
                label = "Peak GPU temp",
                value = "%.1f°C".format(it),
                explainer = "Hottest GPU reading during the sustained test.",
            )
        }
        a.thermalHeadroomC?.let { headroom ->
            MetricBarRow(
                label = "Thermal headroom",
                value = "%.1f°C".format(headroom),
                explainer = "Margin below the 85°C kill threshold. More = more sustained clock available.",
                fraction = (headroom / 85f).coerceIn(0f, 1f),
            )
        }
        a.avgPowerMw?.let {
            KvRow(
                label = "Avg power",
                value = "%.2f W".format(it / 1000.0),
                explainer = "Mean battery draw under sustained load.",
            )
        }
        a.energyMwh?.let {
            KvRow(
                label = "Energy used",
                value = "%.1f mWh".format(it),
                explainer = "Total energy drawn over the throttle test.",
            )
        }

        Text(
            "Sustained CPU MHz over time — flat = no throttle, sloping down = throttling.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricLineChart(samples.map { it.cpuMaxMhz.toFloat() })

        Text(
            "CPU temperature over time.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricLineChart(samples.map { it.cpuMaxTempC })
    }
}

/** KvRow plus a thin progress bar where a true 0..1 fraction is meaningful
 *  (consistency %, thermal headroom). Not used on raw CPU/mem rows to avoid
 *  fake precision. */
@Composable
private fun MetricBarRow(
    label: String,
    value: String,
    explainer: String?,
    fraction: Float?,
) {
    Column {
        KvRow(label = label, value = value, explainer = explainer)
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
            )
        }
    }
}

@Composable
private fun SnapshotContext(run: BenchRun) {
    // Humanize the raw tier enum captured in the snapshot. The snapshot
    // doesn't carry the vendor brand, so AYN_SETTINGS becomes the
    // generic "Vendor settings" rather than misattributing a brand.
    val tierLabel = when (run.snapshot.privilegeTier) {
        "AYN_SETTINGS" -> "Vendor settings"
        "ROOT" -> "Root"
        "SHIZUKU" -> "Shizuku"
        "NONE" -> "No-root"
        else -> run.snapshot.privilegeTier
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${run.snapshot.deviceModel} · $tierLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val capsLine = run.snapshot.cpuPolicies.joinToString("  ") { p ->
            "p${p.policyId}=${p.maxKhz / 1000}MHz/${p.governor}"
        }
        if (capsLine.isNotBlank()) {
            Text(
                capsLine,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Chips ────────────────────────────────────────────────────────────

@Composable
private fun FlavorChip(flavor: BenchFlavor) {
    AssistChip(onClick = {}, label = { Text(flavor.name) })
}

// ─── Compare view ─────────────────────────────────────────────────────

@Composable
private fun CompareCard(
    a: BenchRun,
    b: BenchRun,
    report: CapabilityReport?,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Compare",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            Text(
                "${a.name} → ${b.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ScoreDeltaCard(a, b)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ThrottleOverlay(a, b)
        }
    }
}

@Composable
private fun ScoreDeltaCard(a: BenchRun, b: BenchRun) {
    val sa = remember(a) { BenchScores.from(a) }
    val sb = remember(b) { BenchScores.from(b) }
    val ta = remember(a) { ThrottleAnalysis.from(a.throttleSamples) }
    val tb = remember(b) { ThrottleAnalysis.from(b.throttleSamples) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Score deltas (B vs A) — green = improvement",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        deltaRow("Overall", a.overallScore?.toDouble(), b.overallScore?.toDouble(), "%.0f")
        deltaRow("CPU int 1T", a.kernels.cpuIntegerSingle?.toDouble(), b.kernels.cpuIntegerSingle?.toDouble(), "%.0f")
        deltaRow("CPU int MT", a.kernels.cpuIntegerMulti?.toDouble(), b.kernels.cpuIntegerMulti?.toDouble(), "%.0f")
        deltaRow("CPU float", a.kernels.cpuFloat?.toDouble(), b.kernels.cpuFloat?.toDouble(), "%.0f")
        deltaRow("AES-128", a.kernels.cpuAes?.toDouble(), b.kernels.cpuAes?.toDouble(), "%.0f")
        deltaRow("RAM MB/s", a.kernels.memoryBandwidthMBps, b.kernels.memoryBandwidthMBps, "%.1f")
        deltaRow("GPU FPS", a.kernels.gpuFps, b.kernels.gpuFps, "%.1f")
        deltaRow("Draw calls", a.kernels.cpuDrawCallFps, b.kernels.cpuDrawCallFps, "%.0f")
        deltaRow("CPU% GPU", a.kernels.cpuUsageDuringGpuPct?.toDouble(), b.kernels.cpuUsageDuringGpuPct?.toDouble(), "%.0f")
        // ── Category sub-scores ──────────────────────────────────
        deltaRow("CPU score", sa.cpu?.toDouble(), sb.cpu?.toDouble(), "%.0f")
        deltaRow("GPU score", sa.gpu?.toDouble(), sb.gpu?.toDouble(), "%.0f")
        deltaRow("Memory score", sa.memory?.toDouble(), sb.memory?.toDouble(), "%.0f")
        // ── Frame pacing ─────────────────────────────────────────
        deltaRow("1% low FPS", a.kernels.gpuP1LowFps, b.kernels.gpuP1LowFps, "%.1f")
        deltaRow("Consistency %", a.kernels.gpuFrameConsistencyPct, b.kernels.gpuFrameConsistencyPct, "%.0f")
        // ── Power & energy (FULL vs FULL only) ───────────────────
        deltaRow("Energy mWh", ta?.energyMwh, tb?.energyMwh, "%.1f")
        deltaRow("Avg W", ta?.avgPowerMw?.let { it / 1000.0 }, tb?.avgPowerMw?.let { it / 1000.0 }, "%.2f")
    }
}

@Composable
private fun deltaRow(label: String, av: Double?, bv: Double?, valFmt: String) {
    if (av == null && bv == null) return
    val pct = if (av != null && av > 0 && bv != null) ((bv - av) * 100.0 / av) else 0.0
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            av?.let { valFmt.format(it) } ?: "—",
            modifier = Modifier.padding(end = 12.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            bv?.let { valFmt.format(it) } ?: "—",
            modifier = Modifier.padding(end = 12.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            if (av != null && bv != null) "%+.1f%%".format(pct) else "—",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = if (pct >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ThrottleOverlay(a: BenchRun, b: BenchRun) {
    if (a.throttleSamples.isEmpty() && b.throttleSamples.isEmpty()) {
        Text(
            "Throttle curves only available on Full runs. Run both as Full to see the overlay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        "Sustained CPU MHz over time — lower in B means more throttle under load",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val seriesA = a.throttleSamples.map { it.cpuMaxMhz.toFloat() }
    val seriesB = b.throttleSamples.map { it.cpuMaxMhz.toFloat() }
    MetricLineChartOverlay(seriesA, seriesB)
}
