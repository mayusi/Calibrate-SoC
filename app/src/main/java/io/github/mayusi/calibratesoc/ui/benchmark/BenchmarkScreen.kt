package io.github.mayusi.calibratesoc.ui.benchmark

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.benchmark.BenchBottleneck
import io.github.mayusi.calibratesoc.data.benchmark.BenchFlavor
import io.github.mayusi.calibratesoc.data.benchmark.BenchRating
import io.github.mayusi.calibratesoc.data.benchmark.BenchRun
import io.github.mayusi.calibratesoc.data.benchmark.BenchScores
import io.github.mayusi.calibratesoc.data.benchmark.BenchmarkRunner
import io.github.mayusi.calibratesoc.data.benchmark.GpuSceneResult
import io.github.mayusi.calibratesoc.data.benchmark.SceneLoopResult
import io.github.mayusi.calibratesoc.data.benchmark.BenchConfig
import io.github.mayusi.calibratesoc.data.benchmark.SUSTAINED_WINDOW_RATIO
import io.github.mayusi.calibratesoc.data.benchmark.ThrottleAnalysis
import io.github.mayusi.calibratesoc.data.benchmark.ThrottleSample
import io.github.mayusi.calibratesoc.data.hardware.StorageClassNames
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import kotlinx.serialization.json.Json
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
import io.github.mayusi.calibratesoc.ui.components.MetricLineChartOverlay
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.stability.StabilityScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benchmark screen — run / history / compare / trends.
 *
 * Redesigned for clarity:
 *   - Headline result card: composite score + BenchRating word + one sentence.
 *   - Category cards (CPU / Memory / GPU / Thermal) with explainers.
 *   - Per-metric labeled rows with plain-English description.
 *   - Throttle curve annotation (Full runs).
 *   - Compare view: card-based + color-coded deltas.
 *   - Trends tab: score history over time, per flavor + metric.
 */
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel = hiltViewModel()) {
    // Segmented toggle: 0 = Benchmark, 1 = Stability, 2 = Trends.
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
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 5),
            ) { Text("Benchmark") }
            SegmentedButton(
                selected = tab == 1,
                onClick = { tab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 5),
            ) { Text("Stability") }
            SegmentedButton(
                selected = tab == 2,
                onClick = { tab = 2 },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 5),
            ) { Text("Trends") }
            SegmentedButton(
                selected = tab == 3,
                onClick = { tab = 3 },
                shape = SegmentedButtonDefaults.itemShape(index = 3, count = 5),
            ) { Text("Hub") }
            SegmentedButton(
                selected = tab == 4,
                onClick = { tab = 4 },
                shape = SegmentedButtonDefaults.itemShape(index = 4, count = 5),
            ) { Text("A/B") }
        }
        when (tab) {
            0 -> BenchmarkContent(viewModel)
            1 -> StabilityScreen()
            2 -> BenchTrendScreen()
            3 -> BenchmarkHubScreen()
            else -> ComparativeABScreen()
        }
    }
}

@Composable
private fun BenchmarkContent(viewModel: BenchmarkViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val runnerState by viewModel.runnerState.collectAsStateWithLifecycle()
    val selection by viewModel.compareSelection.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()

    val sortedHistory = remember(history, sortOrder) {
        when (sortOrder) {
            BenchSortOrder.NEWEST -> history // already newest-first from DB
            BenchSortOrder.HIGHEST_SCORE -> history.sortedByDescending { it.overallScore }
        }
    }

    // Build a map: runId → previous same-flavor completed run's overallScore.
    // Used for the auto-delta headline in each RunCard.
    val prevScoreByRun = remember(history) {
        val byFlavor = history
            .filter { it.outcome == io.github.mayusi.calibratesoc.data.benchmark.BenchOutcome.COMPLETED }
            .sortedBy { it.startedAtMs }
            .groupBy { it.flavor }
        byFlavor.values.flatMap { runs ->
            runs.mapIndexedNotNull { idx, run ->
                if (idx == 0) null
                else run.id to (runs[idx - 1].overallScore)
            }
        }.toMap()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BenchHeader() }
        item {
            RunControls(
                state = runnerState,
                onRun = { flavor, name -> viewModel.runBenchmark(flavor, name) },
                onCancel = viewModel::cancelBenchmark,
            )
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
            item { SortChipRow(sortOrder, onSort = viewModel::setSortOrder) }
            items(sortedHistory, key = { it.id }) { run ->
                RunCard(
                    run = run,
                    report = capability,
                    selected = run.id in selection,
                    onToggleSelection = { viewModel.toggleSelection(run.id) },
                    onDelete = { viewModel.delete(run.id) },
                    prevSameFlavorScore = prevScoreByRun[run.id],
                )
            }
        }
    }
}

@Composable
private fun SortChipRow(
    current: BenchSortOrder,
    onSort: (BenchSortOrder) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = current == BenchSortOrder.NEWEST,
            onClick = { onSort(BenchSortOrder.NEWEST) },
            label = { Text("Newest") },
        )
        FilterChip(
            selected = current == BenchSortOrder.HIGHEST_SCORE,
            onClick = { onSort(BenchSortOrder.HIGHEST_SCORE) },
            label = { Text("Highest score") },
        )
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
    onCancel: () -> Unit,
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
                        title = "Quick  (~20 s)",
                        subtitle = "Single-thread CPU integer — lightweight before/after check when iterating a tune.",
                        learnBullets = listOf(
                            "Peak per-core integer speed",
                            "Fastest way to spot CPU clock regressions",
                            "No GPU, memory, or multi-thread data",
                        ),
                        onClick = { onRun(BenchFlavor.QUICK, name) },
                    )
                    FlavorButton(
                        title = "Standard  (~2 min)",
                        subtitle = "Full suite: CPU int + float + AES + RAM + GPU triangle storm + 3D scene + storage probe.",
                        learnBullets = listOf(
                            "Single- and multi-thread CPU throughput",
                            "GPU fill rate + frame pacing (triangle storm)",
                            "Bottleneck diagnosis — GPU-bound vs CPU-limited",
                            "3D scene: 3-loop sustained FPS + stability%",
                            "Storage read speed (UFS class indication)",
                        ),
                        onClick = { onRun(BenchFlavor.STANDARD, name) },
                    )
                    FlavorButton(
                        title = "Full  (~5 min)",
                        subtitle = "Standard + 2-min sustained throttle curve. The only mode that shows your under-heat clock floor.",
                        learnBullets = listOf(
                            "Everything Standard gives you",
                            "Peak vs sustained CPU clock (throttle floor)",
                            "Time-to-throttle: how long the chip holds peak clock",
                            "Thermal headroom: margin below the 85°C kill threshold",
                            "Power draw and energy used under sustained load",
                        ),
                        onClick = { onRun(BenchFlavor.FULL, name) },
                    )
                    FlavorButton(
                        title = "GPU 3D  (~3 min)",
                        subtitle = "Heavy sustained 1440p scene — 10 loops × 20 s. GPU-only: FPS stability across loops.",
                        learnBullets = listOf(
                            "Sustained GPU FPS over 10 loops (real gaming load)",
                            "Stability%: how much FPS drops as thermals rise",
                            "Per-loop bar chart — spot exactly when throttling starts",
                            "Frame pacing: 1% low, p99, consistency%",
                        ),
                        onClick = { onRun(BenchFlavor.SCENE_3D, name) },
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
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun FlavorButton(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    learnBullets: List<String> = emptyList(),
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (learnBullets.isNotEmpty()) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Text(
                    if (expanded) "Hide details" else "What will this measure?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    learnBullets.forEach { bullet ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                bullet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
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
    prevSameFlavorScore: Long? = null,
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val rating = remember(run, report) {
        if (report != null) BenchRating.rate(run, report) else null
    }
    val throttle = remember(run) { ThrottleAnalysis.from(run.throttleSamples, killTempC = 85f) }
    val bottleneck = remember(run, throttle) {
        if (run.outcome == io.github.mayusi.calibratesoc.data.benchmark.BenchOutcome.COMPLETED) {
            BenchBottleneck.diagnose(run.kernels, throttle)
        } else null
    }
    val sceneResult = remember(run.kernels.sceneJson) {
        run.kernels.sceneJson?.let { json ->
            runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString<GpuSceneResult>(json)
            }.getOrNull()
        }
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

            // ── Bottleneck verdict (P1 — THE key card, shown first) ──
            bottleneck?.let { BottleneckCard(it) }

            // ── Headline score + rating + auto-delta vs prev run ─────
            run.overallScore?.let { score ->
                HeadlineScoreCard(score, rating, prevSameFlavorScore)
            }

            // ── Category sub-scores (CPU / GPU / Memory) ─────────
            CategoryScoreRow(remember(run) { BenchScores.from(run) })

            // ── Category cards ───────────────────────────────────
            CpuCard(run)
            if (run.kernels.memoryBandwidthMBps != null || run.kernels.storageReadMBps != null) MemoryCard(run)
            if (run.kernels.gpuFps != null || run.kernels.cpuDrawCallFps != null) GpuDetailCard(run)
            if (run.throttleSamples.isNotEmpty()) PowerThermalCard(run)

            // ── Scene result card (SCENE_3D standalone OR embedded in STANDARD/FULL)
            if (sceneResult != null) {
                Scene3DResultCard(sceneResult)
            }

            // ── Snapshot context ─────────────────────────────────
            SnapshotContext(run)

            // ── Actions ──────────────────────────────────────────
            val context = LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleSelection) {
                    Text(if (selected) "Unselect" else "Select to compare")
                }
                TextButton(onClick = onDelete) { Text("Delete") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    val text = benchShareText(run, rating)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share benchmark"))
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share benchmark result",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ─── Bottleneck verdict card ──────────────────────────────────────────

@Composable
private fun BottleneckCard(verdict: BenchBottleneck.BottleneckVerdict) {
    val accentColor = when (verdict.type) {
        BenchBottleneck.BottleneckType.GPU_BOUND         -> Color(0xFFA78BFA) // purple
        BenchBottleneck.BottleneckType.CPU_DRAW_CALL     -> Color(0xFF60A5FA) // blue
        BenchBottleneck.BottleneckType.THERMAL_THROTTLE  -> MaterialTheme.colorScheme.error
        BenchBottleneck.BottleneckType.MEMORY_BOUND      -> Color(0xFF34D399) // green
        BenchBottleneck.BottleneckType.BALANCED          -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val typeLabel = when (verdict.type) {
        BenchBottleneck.BottleneckType.GPU_BOUND         -> "GPU-bound"
        BenchBottleneck.BottleneckType.CPU_DRAW_CALL     -> "CPU-limited"
        BenchBottleneck.BottleneckType.THERMAL_THROTTLE  -> "Thermal throttle"
        BenchBottleneck.BottleneckType.MEMORY_BOUND      -> "Memory-bound"
        BenchBottleneck.BottleneckType.BALANCED          -> "Balanced"
    }
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accentColor,
                )
                Text(
                    "Bottleneck: $typeLabel",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                )
                val confLabel = when (verdict.confidence) {
                    BenchBottleneck.BottleneckVerdict.Confidence.HIGH   -> ""
                    BenchBottleneck.BottleneckVerdict.Confidence.MEDIUM -> " (medium confidence)"
                    BenchBottleneck.BottleneckVerdict.Confidence.LOW    -> " (low confidence)"
                }
                if (confLabel.isNotEmpty()) {
                    Text(
                        confLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                verdict.headline,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(0.dp),
            ) {
                Text(
                    if (expanded) "Hide detail" else "Why? What to change?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                Text(
                    verdict.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Knob: ${verdict.knob}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// ─── Headline score card ──────────────────────────────────────────────

@Composable
private fun HeadlineScoreCard(
    score: Long,
    rating: BenchRating.Rating?,
    prevScore: Long? = null,
) {
    val ratingColor = rating?.let { r ->
        when (r.color) {
            BenchRating.RatingColor.TERTIARY -> MaterialTheme.colorScheme.tertiary
            BenchRating.RatingColor.PRIMARY -> MaterialTheme.colorScheme.primary
            BenchRating.RatingColor.SECONDARY -> MaterialTheme.colorScheme.secondary
            BenchRating.RatingColor.OUTLINE -> MaterialTheme.colorScheme.outline
        }
    }

    val clipboardManager = LocalClipboardManager.current
    // Auto-delta vs previous same-flavor run
    val deltaStr = if (prevScore != null && prevScore > 0) {
        val pct = (score - prevScore) * 100.0 / prevScore
        "%+.1f%% vs last".format(pct)
    } else null
    val deltaColor = if (prevScore != null && score > prevScore)
        Color(0xFF34D399) else MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(score.toString()))
                    },
                ) {
                    Text(
                        score.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy score",
                        modifier = Modifier.size(16.dp).padding(bottom = 4.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    deltaStr?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = deltaColor,
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
    val bw = run.kernels.memoryBandwidthMBps
    val storageMBps = run.kernels.storageReadMBps
    if (bw == null && storageMBps == null) return
    SectionCard("Memory & Storage") {
        Text(
            "How quickly the CPU can read/write RAM and internal flash. " +
                "Higher bandwidth = smoother texture streaming and large-data workloads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        bw?.let {
            KvRow(
                label = "RAM bandwidth",
                value = "%.1f MB/s".format(it),
                explainer = "Sequential read/write throughput (STREAM Triad). Affects game asset loading.",
            )
        }
        storageMBps?.let { mbps ->
            val storageClass = when {
                mbps >= 3000 -> "UFS 3.1+ class"
                mbps >= 1500 -> "UFS 3.0 class"
                mbps >= 800  -> "UFS 2.1 class"
                mbps >= 300  -> "UFS 2.0 / eMMC 5.1 class"
                else         -> "slow / eMMC class"
            }
            KvRow(
                label = "Storage read speed",
                value = "%.0f MB/s — $storageClass".format(mbps),
                explainer = "Sequential read from internal flash (32 MB probe, kernel cache included). " +
                    "Compare your own runs — stock firmware may serve from page cache.",
            )
        }
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
        k.cpuDrawCallFps?.let { dcFps ->
            val gpuFps = k.gpuFps
            val headroom = if (gpuFps != null && gpuFps > 0) {
                " (${(dcFps / gpuFps).let { "%.1fx".format(it) }} above GPU FPS)"
            } else ""
            KvRow(
                label = "CPU draw-call ceiling",
                value = "%.0f calls/s$headroom".format(dcFps),
                explainer = "How many draw commands/s the CPU can submit to the GPU. " +
                    "Divide by typical draw-calls-per-frame (~100–500) to estimate whether " +
                    "the CPU can keep the GPU fed. If this is close to GPU FPS, the CPU is " +
                    "the bottleneck — raising CPU clock / governor helps more than GPU clock.",
            )
        }
        k.cpuUsageDuringGpuPct?.let { pct ->
            val hint = when {
                pct >= 70 -> "CPU was $pct% busy — the GPU bench was likely CPU-limited, not GPU-bound. " +
                    "Raising CPU clock will improve this score."
                pct <= 30 -> "CPU was only $pct% busy — pure GPU-bound. " +
                    "Raising GPU clock directly improves this score."
                else -> "CPU was $pct% busy during the GPU test — mixed CPU/GPU load."
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
    // BUG 8: use the same killTempC for both ThrottleAnalysis and the bar denominator.
    // BenchConfig.killTempC is the run default (85°C); the run doesn't store it separately,
    // so we derive from BenchConfig() rather than hardcoding 85f in two places.
    val killTempC = BenchConfig().killTempC
    val a = remember(run) { ThrottleAnalysis.from(samples, killTempC = killTempC) } ?: return

    val throttleAnnotation = if (a.dropPct < 5.0) {
        "Held ${a.sustainedMhz} MHz — no throttling detected."
    } else {
        "Dropped %.0f%% under sustained load (peak ${a.peakMhz} → sustained ${a.sustainedMhz} MHz).".format(a.dropPct)
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
            value = "${a.sustainedMhz} / ${a.peakMhz} MHz",
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
                // BUG 8: use killTempC as denominator (not hardcoded 85f) so the bar
                // fraction matches the analysis boundary used to compute headroom.
                explainer = "Margin below the %.0f°C kill threshold. More = more sustained clock available.".format(killTempC),
                fraction = (headroom / killTempC).coerceIn(0f, 1f),
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

        // ── Combined MHz + temperature chart (P2) ───────────────
        Text(
            "CPU MHz over time — flat = no throttle, dropping = throttling under heat.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThermalMhzCombinedChart(
            samples = samples,
            timeToThrottleMs = a.timeToThrottleMs,
        )
    }
}

// ─── Per-loop FPS bar chart ────────────────────────────────────────────
// 3DMark-style: each loop = a bar, green within 5% of peak, amber/red below,
// dashed stability-threshold line, peak+sustained annotation.

@Composable
fun PerLoopFpsBarChart(
    loopResults: List<SceneLoopResult>,
    modifier: Modifier = Modifier,
    heightDp: Int = 150,
) {
    if (loopResults.size < 2) return
    val sorted = loopResults.sortedBy { it.loopIndex }
    val peakFps = sorted.maxOf { it.avgFps }
    // BUG 7: use SUSTAINED_WINDOW_RATIO (0.75 → last 25%) so the chart's
    // sustain line matches the headline stability% computed by StabilityResult.
    val tailFrom = (sorted.size * SUSTAINED_WINDOW_RATIO).toInt().coerceAtMost(sorted.size - 1)
    val sustainedFps = sorted.drop(tailFrom).map { it.avgFps }.average()
    val stabilityThreshold = peakFps * 0.90 // 90% of peak = dashed line

    // Colors
    val green  = Color(0xFF34D399)
    val amber  = Color(0xFFFBBF24)
    val red    = Color(0xFFF87171)
    val dashColor  = Color(0xFF94A3B8)

    Canvas(modifier = modifier.fillMaxWidth().height(heightDp.dp)) {
        if (peakFps <= 0) return@Canvas
        val barCount = sorted.size
        val gapFraction = 0.15f
        val barWidth = size.width / barCount * (1f - gapFraction)
        val gap = size.width / barCount * gapFraction

        sorted.forEachIndexed { i, loop ->
            val frac = (loop.avgFps / peakFps).toFloat().coerceIn(0f, 1f)
            val barH = size.height * frac
            val x = i * (barWidth + gap)
            val color = when {
                loop.avgFps >= peakFps * 0.95 -> green
                loop.avgFps >= peakFps * 0.80 -> amber
                else -> red
            }
            drawRect(
                color = color,
                topLeft = Offset(x, size.height - barH),
                size = Size(barWidth, barH),
            )
        }

        // Dashed stability threshold line at 90% of peak
        val lineY = size.height * (1f - (stabilityThreshold / peakFps).toFloat().coerceIn(0f, 1f))
        drawLine(
            color = dashColor,
            start = Offset(0f, lineY),
            end = Offset(size.width, lineY),
            strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f)),
        )
    }
}

// ─── Combined MHz + temperature chart ─────────────────────────────────
// Two overlay lines on one chart (MHz primary axis scaled, temp secondary).
// Dashed vertical marker at timeToThrottleMs; amber region post-throttle.

@Composable
private fun ThermalMhzCombinedChart(
    samples: List<ThrottleSample>,
    timeToThrottleMs: Long?,
    modifier: Modifier = Modifier,
    heightDp: Int = 180,
) {
    if (samples.size < 2) return
    val peakMhz = samples.maxOf { it.cpuMaxMhz }.toFloat().coerceAtLeast(1f)
    val peakTemp = samples.maxOf { it.cpuMaxTempC }.coerceAtLeast(1f)
    val totalMs = (samples.last().elapsedMs - samples.first().elapsedMs).toFloat().coerceAtLeast(1f)
    Column(modifier = modifier) {

    val mhzColor   = Color(0xFF60A5FA) // blue
    val tempColor  = Color(0xFFF97316) // orange
    val throttleLineColor = Color(0xFFFBBF24) // amber dashed
    val amberFill  = Color(0xFFFBBF24).copy(alpha = 0.12f)

    Canvas(modifier = modifier.fillMaxWidth().height(heightDp.dp)) {
        val w = size.width
        val h = size.height

        // Determine throttle x position
        val throttleX: Float? = timeToThrottleMs?.let { ttMs ->
            val firstMs = samples.first().elapsedMs
            ((ttMs - firstMs).toFloat() / totalMs * w).coerceIn(0f, w)
        }

        // Amber shaded post-throttle region
        if (throttleX != null) {
            drawRect(
                color = amberFill,
                topLeft = Offset(throttleX, 0f),
                size = Size(w - throttleX, h),
            )
        }

        // Draw MHz line
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val cur  = samples[i]
            val x1 = ((prev.elapsedMs - samples.first().elapsedMs).toFloat() / totalMs * w)
            val x2 = ((cur.elapsedMs  - samples.first().elapsedMs).toFloat() / totalMs * w)
            val y1 = h - (prev.cpuMaxMhz.toFloat() / peakMhz * h)
            val y2 = h - (cur.cpuMaxMhz.toFloat()  / peakMhz * h)
            drawLine(mhzColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
        }

        // Draw temperature line
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val cur  = samples[i]
            val x1 = ((prev.elapsedMs - samples.first().elapsedMs).toFloat() / totalMs * w)
            val x2 = ((cur.elapsedMs  - samples.first().elapsedMs).toFloat() / totalMs * w)
            val y1 = h - (prev.cpuMaxTempC / peakTemp * h)
            val y2 = h - (cur.cpuMaxTempC  / peakTemp * h)
            drawLine(tempColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
        }

        // Dashed vertical throttle marker
        if (throttleX != null) {
            drawLine(
                color = throttleLineColor,
                start = Offset(throttleX, 0f),
                end = Offset(throttleX, h),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f)),
            )
        }
    }
    // Legend
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(10.dp).height(2.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF60A5FA)))
            }
            Text("CPU MHz", style = MaterialTheme.typography.labelSmall, color = Color(0xFF60A5FA))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(10.dp).height(2.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFFF97316)))
            }
            Text("CPU Temp", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF97316))
        }
        if (timeToThrottleMs != null) {
            Text(
                "-- throttle point",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFBBF24),
            )
        }
    }
    } // end Column
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
    val clipboardManager = LocalClipboardManager.current
    val capsLine = run.snapshot.cpuPolicies.joinToString("  ") { p ->
        "p${p.policyId}=${p.maxKhz / 1000}MHz/${p.governor}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${run.snapshot.deviceModel} · $tierLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (capsLine.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable {
                    clipboardManager.setText(AnnotatedString(capsLine))
                },
            ) {
                Text(
                    capsLine,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy policy line",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
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

// ─── Share helpers ────────────────────────────────────────────────────

/**
 * Build a plain-text share summary for a BenchRun + its optional rating.
 */
private fun benchShareText(run: BenchRun, rating: BenchRating.Rating?): String {
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val scores = BenchScores.from(run)
    val k = run.kernels
    val a = ThrottleAnalysis.from(run.throttleSamples)

    return buildString {
        appendLine("Calibrate SoC — Benchmark Result")
        appendLine()

        appendLine("Run: ${run.name}")
        appendLine("Flavor: ${run.flavor.name.lowercase().replaceFirstChar { it.uppercase() }}")
        appendLine("Date: ${dateFmt.format(Date(run.startedAtMs))}")
        appendLine()

        run.overallScore?.let { score ->
            appendLine("Composite score: $score")
            rating?.word?.let { appendLine("Rating: $it") }
            rating?.let { r ->
                val sentence = r.abortReason ?: r.oneSentence
                appendLine(sentence)
            }
            appendLine()
        }

        // Category scores
        if (scores.cpu != null || scores.gpu != null || scores.memory != null) {
            appendLine("Category scores:")
            scores.cpu?.let { appendLine("  CPU:    $it") }
            scores.gpu?.let { appendLine("  GPU:    $it") }
            scores.memory?.let { appendLine("  Memory: $it") }
            appendLine()
        }

        // Key kernel numbers
        appendLine("Key numbers:")
        k.cpuIntegerSingle?.let { appendLine("  CPU 1T int:   $it") }
        k.cpuIntegerMulti?.let { appendLine("  CPU MT int:   $it") }
        k.cpuFloat?.let { appendLine("  CPU float:    $it") }
        k.cpuAes?.let { appendLine("  AES-128:      $it") }
        k.memoryBandwidthMBps?.let { appendLine("  RAM BW:       %.1f MB/s".format(it)) }
        k.gpuFps?.let { appendLine("  GPU avg FPS:  %.1f".format(it)) }
        k.gpuP1LowFps?.let { appendLine("  GPU 1% low:   %.1f FPS".format(it)) }
        k.gpuFrameConsistencyPct?.let { appendLine("  GPU consist.: %.0f%%".format(it)) }
        appendLine()

        // Device context
        val tierLabel = when (run.snapshot.privilegeTier) {
            "AYN_SETTINGS" -> "Vendor settings"
            "ROOT" -> "Root"
            "SHIZUKU" -> "Shizuku"
            "NONE" -> "No-root"
            else -> run.snapshot.privilegeTier
        }
        appendLine("Device: ${run.snapshot.deviceModel}")
        appendLine("SoC: ${run.snapshot.socModel}")
        appendLine("Privilege: $tierLabel")
        appendLine()

        // Throttle summary (FULL runs only)
        if (a != null) {
            appendLine("Throttle summary:")
            appendLine("  Sustained clock: ${a.sustainedMhz} MHz")
            appendLine("  Peak clock:      ${a.peakMhz} MHz")
            appendLine("  Drop:            %.0f%%".format(a.dropPct))
            appendLine("  Peak CPU temp:   %.1f°C".format(a.peakCpuTempC))
            a.peakGpuTempC?.let { appendLine("  Peak GPU temp:   %.1f°C".format(it)) }
            a.avgPowerMw?.let { appendLine("  Avg power:       %.2f W".format(it / 1000.0)) }
            appendLine()
        }

        append("— via Calibrate SoC")
    }
}
