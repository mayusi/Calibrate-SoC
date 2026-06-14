package io.github.mayusi.calibratesoc.ui.benchmark

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.benchmark.ApplyStatus
import io.github.mayusi.calibratesoc.data.benchmark.BenchFlavor
import io.github.mayusi.calibratesoc.data.benchmark.BenchOutcome
import io.github.mayusi.calibratesoc.data.benchmark.BenchRun
import io.github.mayusi.calibratesoc.data.benchmark.BenchmarkRunner
import io.github.mayusi.calibratesoc.data.benchmark.CategoryDelta
import io.github.mayusi.calibratesoc.data.benchmark.CategoryWinner
import io.github.mayusi.calibratesoc.data.benchmark.ComparativeResult
import io.github.mayusi.calibratesoc.data.benchmark.ComparativeSlot
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.MetricLineChartOverlay
import io.github.mayusi.calibratesoc.ui.components.SectionCard

/**
 * A/B comparative benchmark screen.
 *
 * Lets the user pick two saved profiles and a flavor, runs the same
 * benchmark under each in sequence, then shows a side-by-side delta
 * table and per-category winners. Reuses [ScoreDeltaCard] patterns and
 * the [MetricLineChartOverlay] throttle overlay from [BenchmarkScreen].
 *
 * Honesty:
 *   - Clearly badges each run with its [ApplyStatus] (CONFIRMED / UNVERIFIABLE).
 *   - If UNVERIFIABLE, shows a prominent warning that the profile may not
 *     have taken effect (no-root device / SELinux denial).
 *   - If FAILED (empty profile or IO crash), aborts the sequence and shows
 *     an error — never runs a benchmark under an unapplied profile silently.
 *   - Labels the comparison "same-device-relative" consistently with the
 *     app's other benchmark honesty guardrails.
 */
@Composable
fun ComparativeABScreen(viewModel: ComparativeABViewModel = hiltViewModel()) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val abState by viewModel.abState.collectAsStateWithLifecycle()
    val runnerState by viewModel.runnerState.collectAsStateWithLifecycle()
    val profileA by viewModel.profileA.collectAsStateWithLifecycle()
    val profileB by viewModel.profileB.collectAsStateWithLifecycle()
    val flavor by viewModel.flavor.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ABHeader() }

        when (val state = abState) {
            is ComparativeABViewModel.ABState.Idle,
            is ComparativeABViewModel.ABState.Error -> {
                item {
                    ABConfigCard(
                        profiles = profiles,
                        profileA = profileA,
                        profileB = profileB,
                        flavor = flavor,
                        onSelectA = viewModel::selectProfileA,
                        onSelectB = viewModel::selectProfileB,
                        onSelectFlavor = viewModel::selectFlavor,
                        onStart = viewModel::start,
                        canStart = profileA != null && profileB != null && profileA?.id != profileB?.id,
                    )
                }
                if (state is ComparativeABViewModel.ABState.Error) {
                    item { ABErrorCard(state.message) }
                }
            }

            is ComparativeABViewModel.ABState.ApplyingA -> {
                item { ABProgressCard(phase = "Applying Profile A…", runnerState = null, onCancel = viewModel::cancel) }
            }
            is ComparativeABViewModel.ABState.RunningA -> {
                item { ABProgressCard(phase = "Running benchmark under Profile A…", runnerState = state.runnerState, onCancel = viewModel::cancel) }
            }
            is ComparativeABViewModel.ABState.ApplyingB -> {
                item { ABProgressCard(phase = "Profile A done. Applying Profile B…", runnerState = null, onCancel = viewModel::cancel) }
            }
            is ComparativeABViewModel.ABState.RunningB -> {
                item { ABProgressCard(phase = "Running benchmark under Profile B…", runnerState = state.runnerState, onCancel = viewModel::cancel) }
            }

            is ComparativeABViewModel.ABState.Done -> {
                val result = state.result
                item { ABResultActions(onRunAgain = viewModel::reset) }
                if (result.partial) {
                    item { ABPartialBanner(result) }
                }
                item { ABSlotSummaryRow(result) }
                if (result.deltas.isNotEmpty()) {
                    item { ABDeltaTable(result) }
                    if (result.slotA.run != null && result.slotB.run != null &&
                        result.slotA.run.throttleSamples.isNotEmpty() &&
                        result.slotB.run.throttleSamples.isNotEmpty()
                    ) {
                        item { ABThrottleOverlay(result.slotA.run, result.slotB.run) }
                    }
                } else {
                    item {
                        Text(
                            "No delta data — at least one run produced no scores.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { ABHonestyFooter() }
            }
        }
    }
}

// ─── Header ──────────────────────────────────────────────────────────────

@Composable
private fun ABHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "A/B Profile Comparison",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Pick two profiles and a flavor. The app applies profile A, runs the benchmark, " +
                "then applies profile B and runs again — same device, same load, objective delta.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Config card ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ABConfigCard(
    profiles: List<UserProfile>,
    profileA: UserProfile?,
    profileB: UserProfile?,
    flavor: BenchFlavor,
    onSelectA: (UserProfile) -> Unit,
    onSelectB: (UserProfile) -> Unit,
    onSelectFlavor: (BenchFlavor) -> Unit,
    onStart: () -> Unit,
    canStart: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Configure Comparison", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (profiles.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "No saved profiles yet. Create profiles in the Tune tab first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // Profile A picker
            ProfileDropdown(
                label = "Profile A",
                selected = profileA,
                options = profiles,
                onSelect = onSelectA,
            )

            // Profile B picker
            ProfileDropdown(
                label = "Profile B",
                selected = profileB,
                options = profiles,
                onSelect = onSelectB,
            )

            if (profileA != null && profileB != null && profileA.id == profileB.id) {
                Text(
                    "Select two different profiles to compare.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Flavor picker
            FlavorDropdown(selected = flavor, onSelect = onSelectFlavor)

            // ETA hint
            val etaHint = when (flavor) {
                BenchFlavor.QUICK    -> "~40 s total (2 × Quick)"
                BenchFlavor.STANDARD -> "~2–3 min total (2 × Standard)"
                BenchFlavor.FULL     -> "~8–10 min total (2 × Full — includes throttle test)"
                BenchFlavor.SCENE_3D -> "~6 min total (2 × GPU 3D)"
            }
            Text(
                "ETA: $etaHint. Keep screen on and device unplugged.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Run A/B Benchmark")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDropdown(
    label: String,
    selected: UserProfile?,
    options: List<UserProfile>,
    onSelect: (UserProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        androidx.compose.material3.OutlinedTextField(
            value = selected?.name ?: "Select…",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                            if (profile.description.isNotBlank()) {
                                Text(
                                    profile.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(profile)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlavorDropdown(
    selected: BenchFlavor,
    onSelect: (BenchFlavor) -> Unit,
) {
    val options = listOf(BenchFlavor.QUICK, BenchFlavor.STANDARD, BenchFlavor.FULL, BenchFlavor.SCENE_3D)
    val labels = mapOf(
        BenchFlavor.QUICK to "Quick (~20 s each)",
        BenchFlavor.STANDARD to "Standard (~1 min each)",
        BenchFlavor.FULL to "Full (~5 min each — includes throttle test)",
        BenchFlavor.SCENE_3D to "GPU 3D (~3 min each)",
    )
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        androidx.compose.material3.OutlinedTextField(
            value = labels[selected] ?: selected.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Flavor") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { f ->
                DropdownMenuItem(
                    text = { Text(labels[f] ?: f.name) },
                    onClick = { onSelect(f); expanded = false },
                )
            }
        }
    }
}

// ─── Progress card ────────────────────────────────────────────────────────

@Composable
private fun ABProgressCard(
    phase: String,
    runnerState: BenchmarkRunner.State.Running?,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(phase, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (runnerState != null) {
                LinearProgressIndicator(
                    progress = { runnerState.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "ETA ~${runnerState.etaMs / 1000}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel (shows partial result for A if available)")
            }
        }
    }
}

// ─── Error card ───────────────────────────────────────────────────────────

@Composable
private fun ABErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    "A/B comparison could not start",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

// ─── Result actions ───────────────────────────────────────────────────────

@Composable
private fun ABResultActions(onRunAgain: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onRunAgain) {
            Text("Configure new comparison")
        }
    }
}

// ─── Partial result banner ────────────────────────────────────────────────

@Composable
private fun ABPartialBanner(result: ComparativeResult) {
    val msg = when {
        result.slotB.cancelledMidRun ->
            "Cancelled during Profile B. Only Profile A's results are available."
        result.slotB.run == null ->
            "Profile B could not be applied: ${result.slotB.applyDetails}. " +
                "Showing Profile A result only."
        else ->
            "Partial result — one slot may be incomplete."
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

// ─── Slot summary row (A vs B badges) ────────────────────────────────────

@Composable
private fun ABSlotSummaryRow(result: ComparativeResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SlotSummaryCard(
            label = "A",
            slot = result.slotA,
            accentColor = Color(0xFF60A5FA),
            modifier = Modifier.weight(1f),
        )
        SlotSummaryCard(
            label = "B",
            slot = result.slotB,
            accentColor = Color(0xFFA78BFA),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SlotSummaryCard(
    label: String,
    slot: ComparativeSlot,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val run = slot.run
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(accentColor, shape = MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Text(
                    slot.profile.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Apply status badge
            ApplyStatusBadge(slot.applyStatus)

            if (slot.applyStatus == ApplyStatus.UNVERIFIABLE) {
                Text(
                    slot.applyDetails,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (run != null) {
                run.overallScore?.let { score ->
                    Text(
                        score.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = accentColor,
                    )
                    Text(
                        "Overall score",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (run.outcome != BenchOutcome.COMPLETED) {
                    Text(
                        outcomeLabel(run.outcome),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Text(
                    "No result",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    slot.applyDetails,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApplyStatusBadge(status: ApplyStatus) {
    val (text, color, icon) = when (status) {
        ApplyStatus.CONFIRMED -> Triple(
            "Profile applied",
            MaterialTheme.colorScheme.tertiary,
            Icons.Outlined.CheckCircle,
        )
        ApplyStatus.UNVERIFIABLE -> Triple(
            "May not have applied",
            MaterialTheme.colorScheme.error,
            Icons.Outlined.Warning,
        )
        ApplyStatus.FAILED -> Triple(
            "Not applied",
            MaterialTheme.colorScheme.error,
            Icons.Outlined.Close,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun outcomeLabel(outcome: BenchOutcome) = when (outcome) {
    BenchOutcome.COMPLETED         -> "Completed"
    BenchOutcome.ABORTED_TEMP      -> "Aborted — thermal limit"
    BenchOutcome.ABORTED_BATTERY_TEMP -> "Aborted — battery too hot"
    BenchOutcome.ABORTED_BATTERY   -> "Aborted — battery too hot"
    BenchOutcome.ABORTED_BATTERY_LOW -> "Aborted — battery too low"
    BenchOutcome.ABORTED_DURATION  -> "Aborted — time limit"
    BenchOutcome.ABORTED_USER      -> "Aborted by user"
    BenchOutcome.FAILED_NATIVE     -> "Native crash"
}

// ─── Delta table ──────────────────────────────────────────────────────────

@Composable
private fun ABDeltaTable(result: ComparativeResult) {
    SectionCard("Score Deltas — A vs B") {
        Text(
            "Green/blue tint = winning slot. Same-device-relative — compare YOUR OWN profiles, not other chips.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        ABDeltaHeader()
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
        result.deltas.forEach { delta ->
            ABDeltaRow(delta)
        }
    }
}

@Composable
private fun ABDeltaHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Metric",
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "A",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF60A5FA),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "B",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFA78BFA),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Winner",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ABDeltaRow(delta: CategoryDelta) {
    val aColor = when (delta.winner) {
        CategoryWinner.A -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bColor = when (delta.winner) {
        CategoryWinner.B -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val winnerText = when (delta.winner) {
        CategoryWinner.A -> "A"
        CategoryWinner.B -> "B"
        CategoryWinner.TIE -> "Tie"
        CategoryWinner.NO_DATA -> "—"
    }
    val winnerColor = when (delta.winner) {
        CategoryWinner.A, CategoryWinner.B -> MaterialTheme.colorScheme.tertiary
        CategoryWinner.TIE -> MaterialTheme.colorScheme.onSurfaceVariant
        CategoryWinner.NO_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            delta.label,
            modifier = Modifier.weight(2f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            delta.valueA?.let { delta.valFmt.format(it) + delta.unit } ?: "—",
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = aColor,
            fontWeight = if (delta.winner == CategoryWinner.A) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            delta.valueB?.let { delta.valFmt.format(it) + delta.unit } ?: "—",
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = bColor,
            fontWeight = if (delta.winner == CategoryWinner.B) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            winnerText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = winnerColor,
        )
    }
}

// ─── Throttle overlay ─────────────────────────────────────────────────────

@Composable
private fun ABThrottleOverlay(runA: BenchRun, runB: BenchRun) {
    SectionCard("Sustained CPU MHz — A (blue) vs B (purple)") {
        Text(
            "Both throttle curves on the same chart. Lower = more throttle under sustained load.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val seriesA = runA.throttleSamples.map { it.cpuMaxMhz.toFloat() }
        val seriesB = runB.throttleSamples.map { it.cpuMaxMhz.toFloat() }
        MetricLineChartOverlay(seriesA, seriesB)
    }
}

// ─── Honesty footer ───────────────────────────────────────────────────────

@Composable
private fun ABHonestyFooter() {
    Text(
        "Results are same-device-relative. Never compare these numbers against another chip or " +
            "a benchmark run on a different device. The A/B sequence runs on the same hardware " +
            "back-to-back — temperature may differ between runs (run Full for the fairest comparison).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
