package io.github.mayusi.calibratesoc.ui.benchmark

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.benchmarkhub.BenchmarkAppRegistry
import io.github.mayusi.calibratesoc.data.benchmarkhub.KnownBenchmarkApp
import io.github.mayusi.calibratesoc.data.scorelog.ExternalScoreEntity
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benchmark Hub — discover, launch, and personally log scores from popular
 * third-party benchmark apps.
 *
 * LEGAL framing (enforced throughout):
 *   - We ONLY detect install status + launch apps via standard Android intents.
 *   - We do NOT scrape scores, read result files, or use accessibility services
 *     on third-party apps.
 *   - All scores shown are user-entered ("self-reported") and labeled as such.
 *   - No equivalence claims between our scores and theirs.
 */
@Composable
fun BenchmarkHubScreen(
    viewModel: BenchmarkHubViewModel = hiltViewModel(),
) {
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val scores by viewModel.scores.collectAsStateWithLifecycle()
    val scoredBenchmarks by viewModel.scoredBenchmarks.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item { HubHeader() }

        // ── Legal / honesty notice ──────────────────────────────────
        item {
            AlertCard(
                type = AlertType.INFO,
                title = "Third-party apps — what this hub does",
                message = BenchmarkAppRegistry.HONESTY_NOTE,
            )
        }

        // ── Known apps grid ─────────────────────────────────────────
        item {
            SectionCard(
                title = "Benchmark apps",
                icon = Icons.Outlined.Apps,
            ) {
                Text(
                    "Tap \"Open\" to launch an installed benchmark, or \"Get\" to find it on Play Store.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.group))
                installedApps.forEach { state ->
                    AppRow(
                        state = state,
                        context = context,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }

        // ── External score log header ────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "My benchmark log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Scores YOU entered after running a benchmark app. " +
                            "Self-reported — not verified by Calibrate SoC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add score", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        // ── Trend curves per benchmark (only when ≥2 entries exist) ─
        if (scoredBenchmarks.isNotEmpty()) {
            scoredBenchmarks.forEach { name ->
                val benchScores = scores.filter { it.benchmarkName == name }
                if (benchScores.size >= 2) {
                    item(key = "trend_$name") {
                        BenchmarkTrendCard(name = name, entries = benchScores)
                    }
                }
            }
        }

        // ── Score list ───────────────────────────────────────────────
        if (scores.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Edit,
                    title = "No scores logged yet",
                    body = "After running a benchmark app, come back here and tap \"Add score\" to track your results over time.",
                )
            }
        } else {
            items(scores, key = { it.id }) { score ->
                ScoreCard(score = score, onDelete = { viewModel.deleteScore(score.id) })
            }
        }
    }

    if (showAddDialog) {
        AddScoreDialog(
            knownApps = BenchmarkAppRegistry.ALL,
            onDismiss = { showAddDialog = false },
            onSave = { benchName, pkg, scoreVal, label, device, ts, note ->
                viewModel.saveScore(benchName, pkg, scoreVal, label, device, ts, note)
                showAddDialog = false
            },
        )
    }
}

// ─── Header ──────────────────────────────────────────────────────────

@Composable
private fun HubHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Benchmark Hub",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "One place to launch popular benchmarks and keep a personal log of your scores.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── App row (installed / not installed) ─────────────────────────────

@Composable
private fun AppRow(
    state: BenchmarkHubViewModel.AppInstallState,
    context: Context,
) {
    val app = state.app
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                app.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                app.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.installed) {
            FilledTonalButton(
                onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                },
            ) {
                Icon(
                    Icons.Outlined.Launch,
                    contentDescription = "Open ${app.displayName}",
                    modifier = Modifier.size(16.dp),
                )
                Text("Open", modifier = Modifier.padding(start = 4.dp))
            }
        } else {
            OutlinedButton(
                onClick = {
                    val uri = Uri.parse(app.playStoreUri)
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        // Fallback to https if market:// not available (no Play Store)
                        val fallback = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}"),
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        runCatching { context.startActivity(fallback) }
                    }
                },
            ) {
                Text("Get")
            }
        }
    }
}

// ─── Score trend card ─────────────────────────────────────────────────

@Composable
private fun BenchmarkTrendCard(
    name: String,
    entries: List<ExternalScoreEntity>,
) {
    val sorted = remember(entries) { entries.sortedBy { it.notedAtMs } }
    val points = remember(sorted) { sorted.map { it.scoreValue.toFloat() } }
    val label = sorted.firstOrNull()?.scoreLabel ?: "score"

    SectionCard(
        title = "$name — trend",
        icon = Icons.Outlined.ShowChart,
    ) {
        Text(
            "Your self-reported ${label}s over time. Scores are NOT comparable to other chips.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricLineChart(points = points, heightDp = 130)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val dateFmt = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
            Text(
                dateFmt.format(Date(sorted.first().notedAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                dateFmt.format(Date(sorted.last().notedAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Score card ───────────────────────────────────────────────────────

@Composable
private fun ScoreCard(
    score: ExternalScoreEntity,
    onDelete: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        score.benchmarkName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        dateFmt.format(Date(score.notedAtMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "%.0f".format(score.scoreValue),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFA78BFA), // purple — GPU accent, same as category mini-card
                )
            }
            Text(
                score.scoreLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            score.deviceName?.let {
                Text(
                    "Device: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            score.note?.let {
                Text(
                    "Note: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Self-reported badge — always visible
            Text(
                "Self-reported — you entered this score",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                fontWeight = FontWeight.Medium,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete score",
                        modifier = Modifier.size(16.dp),
                    )
                    Text("Delete", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

// ─── Add score dialog ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddScoreDialog(
    knownApps: List<KnownBenchmarkApp>,
    onDismiss: () -> Unit,
    onSave: (
        benchmarkName: String,
        packageName: String?,
        scoreValue: Double,
        scoreLabel: String,
        deviceName: String?,
        notedAtMs: Long,
        note: String?,
    ) -> Unit,
) {
    // Dropdown state
    var dropdownExpanded by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<KnownBenchmarkApp?>(null) }
    var customBenchmarkName by rememberSaveable { mutableStateOf("") }
    var scoreText by rememberSaveable { mutableStateOf("") }
    var scoreLabel by rememberSaveable { mutableStateOf("Overall") }
    var deviceName by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var notedAtMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val benchmarkName = selectedApp?.displayName ?: customBenchmarkName
    val scoreDouble = scoreText.toDoubleOrNull()
    val canSave = benchmarkName.isNotBlank() && scoreDouble != null && scoreDouble > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a benchmark score") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Text(
                    "Enter a score you got from a third-party benchmark app. " +
                        "This is stored locally and labeled self-reported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── Benchmark picker ──────────────────────────────
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                ) {
                    OutlinedTextField(
                        value = selectedApp?.displayName ?: "Custom / other",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Benchmark app") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Custom / other") },
                            onClick = {
                                selectedApp = null
                                scoreLabel = "score"
                                dropdownExpanded = false
                            },
                        )
                        knownApps.forEach { app ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(app.displayName)
                                        Text(
                                            app.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedApp = app
                                    scoreLabel = app.defaultScoreLabel
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // ── Custom name (only when no known app selected) ──
                if (selectedApp == null) {
                    OutlinedTextField(
                        value = customBenchmarkName,
                        onValueChange = { customBenchmarkName = it.take(40) },
                        label = { Text("Benchmark name") },
                        placeholder = { Text("e.g. Speedometer 3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ── Score + label ─────────────────────────────────
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    OutlinedTextField(
                        value = scoreText,
                        onValueChange = { scoreText = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                        label = { Text("Score") },
                        placeholder = { Text("e.g. 12345") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = scoreLabel,
                        onValueChange = { scoreLabel = it.take(24) },
                        label = { Text("Label / unit") },
                        placeholder = { Text("Overall") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ── Optional fields ───────────────────────────────
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it.take(40) },
                    label = { Text("Device (optional)") },
                    placeholder = { Text("e.g. Odin 2 Max") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("e.g. After tuning governor") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSave) {
                        onSave(
                            benchmarkName,
                            selectedApp?.packageName,
                            scoreDouble!!,
                            scoreLabel,
                            deviceName.trim().ifEmpty { null },
                            notedAtMs,
                            note.trim().ifEmpty { null },
                        )
                    }
                },
                enabled = canSave,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
