package io.github.mayusi.calibratesoc.ui.autotdp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpSavings
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.SavingsResult
import io.github.mayusi.calibratesoc.data.efficiency.EstimateSource
import io.github.mayusi.calibratesoc.data.efficiency.EfficiencyPlan
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.data.vendor.OdinIntents
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.MetricTile
import io.github.mayusi.calibratesoc.ui.components.PanelAccentEdge
import io.github.mayusi.calibratesoc.ui.components.SectionHeader
import io.github.mayusi.calibratesoc.ui.components.StatBar
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * AutoTDP screen — Direction C Arsenal rebuild.
 *
 * Layout (top-to-bottom):
 *  1. Hero: big on/off ArsenalButton (emerald when running, red when off).
 *     Live savings MetricTile shown prominently when data is ready.
 *  2. Rung / mode disclosure (ArsenalPanel, honest, always visible).
 *  3. Profile segmented control (Efficiency / Balanced / Battery-target).
 *  4. Live "now" MetricTile mini-grid (CPU MHz, GPU MHz, hottest temp, draw W).
 *  5. Live running state panel (parked cores, cap, GPU level, decision reason).
 *  6. EfficiencyAdvisor panel — knee cap recommendation + expected draw reduction.
 *     CTA to run sweep when no data; bar chart when sweep is done.
 *  7. Undervolt capability tier disclosure (honest, never fake slider).
 *  8. Battery target card (when BATTERY_TARGET profile selected).
 *  9. PServer unlock CTA (AYN/Odin not yet whitelisted).
 * 10. Script deploy result (SCRIPT rung).
 * 11. Companion toggles: idle/charge + per-app.
 */
@Composable
fun AutoTdpScreen(
    onBack: () -> Unit = {},
    viewModel: AutoTdpViewModel = hiltViewModel(),
) {
    val rung by viewModel.rung.collectAsStateWithLifecycle()
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val targetHours by viewModel.targetHours.collectAsStateWithLifecycle()
    val batteryTargetResult by viewModel.batteryTargetResult.collectAsStateWithLifecycle()
    val sweepProgress by viewModel.sweepProgress.collectAsStateWithLifecycle()
    val kneeKhz by viewModel.kneeKhz.collectAsStateWithLifecycle()
    val idleChargeTriggerEnabled by viewModel.idleChargeTriggerEnabled.collectAsStateWithLifecycle()
    val manuallyOn by viewModel.manuallyOn.collectAsStateWithLifecycle()
    val startingUp by viewModel.startingUp.collectAsStateWithLifecycle()
    val startError by viewModel.startError.collectAsStateWithLifecycle()
    val lastScriptDeploy by viewModel.lastScriptDeploy.collectAsStateWithLifecycle()
    val perAppMap by viewModel.perAppMap.collectAsStateWithLifecycle()
    val showPServerUnlockCta by viewModel.showPServerUnlockCta.collectAsStateWithLifecycle()
    val lastUnlockDeploy by viewModel.lastUnlockDeploy.collectAsStateWithLifecycle()
    val liveSnapshot by viewModel.liveSnapshot.collectAsStateWithLifecycle()
    val efficiencyPlan by viewModel.efficiencyPlan.collectAsStateWithLifecycle()
    val undervoltCapability by viewModel.undervoltCapability.collectAsStateWithLifecycle()

    var showPerAppDialog by remember { mutableStateOf(false) }

    val isRunning = runState.status == AutoTdpStatus.RUNNING

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        // ── 1. Page header ──────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "AUTO TDP",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.05.sp,
                        color = Color.White,
                    )
                    Text(
                        "Closed-loop power governor",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF999999),
                        letterSpacing = 0.04.sp,
                    )
                }
                // Live badge when running
                if (isRunning) {
                    StatusPill(text = "LIVE", accent = AccentBar.Emerald)
                }
            }
        }

        // ── 2. Hero on/off ─────────────────────────────────────────────────────
        item {
            HeroControlBlock(
                rung = rung,
                isOn = manuallyOn,
                startingUp = startingUp,
                runState = runState,
                onToggle = { viewModel.setManualEnabled(it) },
                onGenerateScript = { viewModel.generateEfficiencyScript() },
            )
        }

        // ── 2b. Abort / start-error notice (FIX 4) ────────────────────────────
        // Persists after the button returns to STOPPED so the user knows WHY.
        // The notice remains visible until the user taps "Dismiss" or taps START again.
        startError?.let { errorMsg ->
            item {
                AbortNoticeCard(
                    message = errorMsg,
                    onDismiss = { viewModel.clearStartError() },
                )
            }
        }

        // ── 3. Live savings MetricTile (only when data ready) ──────────────────
        if (isRunning && runState.savings?.enoughData == true) {
            item {
                SavingsHeroRow(savings = runState.savings!!)
            }
        }

        // ── 4. Rung disclosure ─────────────────────────────────────────────────
        item { ArsenalRungBanner(rung = rung, runState = runState) }

        // ── 5. Profile segmented control ───────────────────────────────────────
        item {
            ArsenalPanel(
                accent = AccentBar.Blue,
                title = "Profile",
            ) {
                ArsenalProfilePicker(
                    selected = selectedProfile,
                    onSelected = { viewModel.selectProfile(it) },
                    excludeBatteryTarget = rung == AutoTdpRung.SCRIPT,
                )
            }
        }

        // ── 6. Battery target input (when selected) ────────────────────────────
        if (selectedProfile == AutoTdpProfile.BATTERY_TARGET) {
            item {
                BatteryTargetArsenalCard(
                    targetHours = targetHours,
                    result = batteryTargetResult,
                    onTargetHoursChanged = { viewModel.setTargetHours(it) },
                )
            }
        }

        // ── 7. Live "now" MetricTile grid ──────────────────────────────────────
        // Always shown (shows "–" when not running / not available)
        item {
            ArsenalPanel(accent = AccentBar.Emerald, title = "Now") {
                LiveNowArsenalGrid(snapshot = liveSnapshot, isRunning = isRunning)
            }
        }

        // ── 8. Running state panel (parked cores, cap, GPU, decision reason) ───
        if (runState.status != AutoTdpStatus.IDLE) {
            item {
                RunningStatePanels(runState = runState)
            }
        }

        // ── 9. EfficiencyAdvisor surface ───────────────────────────────────────
        item {
            EfficiencyAdvisorPanel(
                plan = efficiencyPlan,
                sweepState = sweepProgress,
                kneeKhz = kneeKhz,
                onStartSweep = { viewModel.startEfficiencySweep() },
                onCancelSweep = { viewModel.cancelSweep() },
            )
        }

        // ── 10. Undervolt capability tier disclosure ──────────────────────────
        undervoltCapability?.let { cap ->
            item { UndervoltTierPanel(capability = cap) }
        }

        // ── 11. PServer unlock CTA ─────────────────────────────────────────────
        if (showPServerUnlockCta) {
            item {
                PServerUnlockArsenalCard(
                    lastDeploy = lastUnlockDeploy,
                    onDeploy = { viewModel.deployUnlockScript() },
                    onRefresh = { viewModel.refreshCapability() },
                    onDismiss = { viewModel.clearUnlockDeploy() },
                )
            }
        }

        // ── 12. Last script deploy (SCRIPT rung) ──────────────────────────────
        lastScriptDeploy?.let { deployed ->
            item {
                LastScriptArsenalCard(
                    deployed = deployed,
                    onDismiss = { viewModel.clearLastScriptDeploy() },
                )
            }
        }

        // ── 13. Companion toggles ─────────────────────────────────────────────
        item {
            CompanionTogglesArsenalPanel(
                idleChargeTriggerEnabled = idleChargeTriggerEnabled,
                perAppMapCount = perAppMap.size,
                onIdleChargeToggle = { viewModel.setIdleChargeTriggerEnabled(it) },
                onOpenPerApp = { showPerAppDialog = true },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // Per-app efficiency dialog (unchanged logic, same dialog)
    if (showPerAppDialog) {
        PerAppEfficiencyDialog(
            currentMap = perAppMap,
            onSetProfile = { pkg, profile -> viewModel.setPerAppProfile(pkg, profile) },
            onDismiss = { showPerAppDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Hero on/off block
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeroControlBlock(
    rung: AutoTdpRung,
    isOn: Boolean,
    startingUp: Boolean,
    runState: AutoTdpRunState,
    onToggle: (Boolean) -> Unit,
    onGenerateScript: () -> Unit,
) {
    val isRunning = runState.status == AutoTdpStatus.RUNNING

    ArsenalPanel(
        accent = if (isRunning) AccentBar.Emerald else AccentBar.Red,
        accentEdge = PanelAccentEdge.Bottom,
    ) {
        when (rung) {
            AutoTdpRung.LIVE -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    Text(
                        when {
                            startingUp -> "STARTING…"
                            isOn && runState.status == AutoTdpStatus.RUNNING -> "DAEMON ACTIVE"
                            isOn -> "STARTING…"
                            else -> "STOPPED"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) AccentBar.Emerald else Color(0xFF999999),
                        letterSpacing = 0.10.sp,
                    )
                    // FIX 1: show a progress bar while the capability probe is in flight.
                    if (startingUp) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentBar.Blue,
                        )
                    }
                    Text(
                        if (isRunning) "Writing kernel clocks every second"
                        else "All kernel writes reverted",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF777777),
                    )
                    Spacer(Modifier.height(4.dp))
                    // FIX 1: disable the button while probing to prevent double-taps.
                    ArsenalButton(
                        label = when {
                            startingUp -> "Starting…"
                            isOn -> "Stop AutoTDP"
                            else -> "Start AutoTDP"
                        },
                        onClick = { if (!startingUp) onToggle(!isOn) },
                        accent = if (isOn || startingUp) AccentBar.Emerald else AccentBar.Red,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AutoTdpRung.SCRIPT -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    Text(
                        "SCRIPT BUILDER",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = AccentBar.Amber,
                        letterSpacing = 0.10.sp,
                    )
                    Text(
                        "No live sysfs access. Generates a one-shot efficiency tune script — run it via Settings → Run script as Root.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF999999),
                    )
                    ArsenalButton(
                        label = "Generate efficiency tune script",
                        onClick = onGenerateScript,
                        accent = AccentBar.Amber,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AutoTdpRung.ADVISORY -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    Text(
                        "ADVISORY MODE",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = AccentBar.Neutral,
                        letterSpacing = 0.10.sp,
                    )
                    Text(
                        "No write access. Showing efficiency advice only — no kernel changes applied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF999999),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Savings hero row (shown only when data is ready)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SavingsHeroRow(savings: SavingsResult) {
    val saving = savings.deltaMw
    val pct = kotlin.math.abs(savings.deltaPct)
    val isSaving = saving > 0
    val accent = if (isSaving) AccentBar.Emerald else AccentBar.Red

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        val deltaLabel = if (isSaving) "-${saving}" else "+${-saving}"
        MetricTile(
            label = "Draw savings",
            value = "$deltaLabel mW",
            unit = null,
            accent = accent,
            valueColor = accent,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "Reduction",
            value = "${"%.1f".format(pct)}",
            unit = "%",
            accent = accent,
            valueColor = if (isSaving) accent else null,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "Samples",
            value = "${savings.sampleCount}",
            unit = null,
            accent = AccentBar.Neutral,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Rung disclosure banner
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ArsenalRungBanner(rung: AutoTdpRung, runState: AutoTdpRunState) {
    val (accent, statusLabel, body) = when (rung) {
        AutoTdpRung.LIVE -> Triple(
            AccentBar.Emerald,
            "RUNG: LIVE",
            if (runState.status == AutoTdpStatus.RUNNING) {
                "Live control confirmed — daemon writing every second. Prime cores park when GPU-bound; big cluster capped at efficiency knee; GPU prioritised. All writes revert on stop."
            } else {
                "Sysfs is live-writable on this device (PServer, direct sysfs, or root). AutoTDP runs a closed-loop daemon — parks prime cores, caps big cluster, reverts everything on stop."
            },
        )
        AutoTdpRung.SCRIPT -> Triple(
            AccentBar.Amber,
            "RUNG: SCRIPT",
            "Live auto-adjust needs the one-time unlock (see below) or root. This rung generates the best static efficiency tune as a shell script — run it once via 'Run script as Root'. No live loop; settings revert on reboot.",
        )
        AutoTdpRung.ADVISORY -> Triple(
            AccentBar.Neutral,
            "RUNG: ADVISORY",
            "No write access. AutoTDP can read live telemetry and show efficiency advice, but cannot apply any changes. Run the unlock script or use root to reach SCRIPT or LIVE.",
        )
    }

    val liveSuffix = if (runState.status == AutoTdpStatus.LIVE_UNAVAILABLE) {
        "\n\nDaemon confirmed: ${runState.liveUnavailableReason ?: "live writes not available — check sysfs permissions."}"
    } else ""

    ArsenalPanel(accent = accent) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            verticalAlignment = Alignment.Top,
        ) {
            StatusPill(text = statusLabel, accent = accent)
            Text(
                body + liveSuffix,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFBBBBBB),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Arsenal profile picker (segmented control look)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ArsenalProfilePicker(
    selected: AutoTdpProfile,
    onSelected: (AutoTdpProfile) -> Unit,
    excludeBatteryTarget: Boolean = false,
) {
    val profiles = if (excludeBatteryTarget) {
        listOf(AutoTdpProfile.EFFICIENCY, AutoTdpProfile.BALANCED)
    } else {
        listOf(AutoTdpProfile.EFFICIENCY, AutoTdpProfile.BALANCED, AutoTdpProfile.BATTERY_TARGET)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
    ) {
        profiles.forEach { profile ->
            val isSelected = selected == profile
            val accent = when (profile) {
                AutoTdpProfile.EFFICIENCY -> AccentBar.Emerald
                AutoTdpProfile.BALANCED -> AccentBar.Blue
                AutoTdpProfile.BATTERY_TARGET -> AccentBar.Amber
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSelected) accent.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                        RoundedCornerShape(4.dp),
                    )
                    .border(
                        1.dp,
                        if (isSelected) accent else Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { onSelected(profile) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (profile) {
                        AutoTdpProfile.EFFICIENCY -> "EFFICIENCY"
                        AutoTdpProfile.BALANCED -> "BALANCED"
                        AutoTdpProfile.BATTERY_TARGET -> "BATTERY"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) accent else Color(0xFF888888),
                    letterSpacing = 0.06.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    Spacer(Modifier.height(Spacing.dense))
    Text(
        when (selected) {
            AutoTdpProfile.EFFICIENCY ->
                "Aggressive: parks prime cores, caps big cluster at ~67% of max. Best for GPU-bound emulation."
            AutoTdpProfile.BALANCED ->
                "Mild: parks prime cores on GPU-bound load, caps big at ~75%. Good for mixed workloads."
            AutoTdpProfile.BATTERY_TARGET ->
                "Target mode: set desired battery life; AutoTDP computes the cap needed to hit it."
        },
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF777777),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Live "now" mini-grid (4 MetricTiles)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LiveNowArsenalGrid(
    snapshot: AutoTdpViewModel.LiveSnapshot?,
    isRunning: Boolean,
) {
    val dimColor = if (isRunning) null else AccentBar.Neutral.copy(alpha = 0.5f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
    ) {
        val cpuMhz = snapshot?.cpuMaxMhz?.toString() ?: "–"
        val gpuMhz = snapshot?.gpuMhz?.toString() ?: "–"
        val tempC = snapshot?.hottestTempC?.toString() ?: "–"
        val drawW = snapshot?.batteryDrawW?.let { "%.1f".format(it) } ?: "–"

        MetricTile(
            label = "CPU MAX",
            value = cpuMhz,
            unit = if (snapshot?.cpuMaxMhz != null) "MHz" else null,
            accent = AccentBar.Blue,
            valueColor = dimColor,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "GPU",
            value = gpuMhz,
            unit = if (snapshot?.gpuMhz != null) "MHz" else null,
            accent = AccentBar.Purple,
            valueColor = dimColor,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "HOTTEST",
            value = tempC,
            unit = if (snapshot?.hottestTempC != null) "°C" else null,
            accent = AccentBar.Amber,
            valueColor = if (snapshot?.hottestTempC?.let { it > 85 } == true) AccentBar.Red else dimColor,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "DRAW",
            value = drawW,
            unit = if (snapshot?.batteryDrawW != null) "W" else null,
            accent = AccentBar.Neutral,
            valueColor = dimColor,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Running state panels
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RunningStatePanels(runState: AutoTdpRunState) {
    when (runState.status) {
        AutoTdpStatus.RUNNING -> {
            val state = runState.appliedState

            ArsenalPanel(accent = AccentBar.Blue, title = "Current kernel writes") {
                if (state != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                        if (state.parkedPrimeCores.isNotEmpty()) {
                            KvRow(
                                label = "PARKED PRIME CORES",
                                value = state.parkedPrimeCores.sorted().joinToString(", ") { "cpu$it" },
                                explainer = "Offlined to shed thermal load and cut power draw.",
                            )
                        } else {
                            KvRow(label = "PRIME CORES", value = "all online", explainer = "No parking — CPU-bound or below threshold.")
                        }
                        state.bigClusterCapKhz?.let { cap ->
                            KvRow(label = "BIG CLUSTER CAP", value = "${cap / 1000} MHz", explainer = "Frequency ceiling. Efficiency knee or target.")
                        }
                        state.gpuFloorLevel?.let { lvl ->
                            KvRow(
                                label = "GPU MAX POWER LEVEL",
                                value = "$lvl${if (lvl == 0) " (max perf)" else ""}",
                                explainer = "0 = fastest. AutoTDP keeps GPU fast when CPU is parked.",
                            )
                        }
                        if (state.governorOverrides.isNotEmpty()) {
                            KvRow(
                                label = "GOVERNOR OVERRIDES",
                                value = state.governorOverrides.entries.joinToString(", ") { (p, g) -> "policy$p→$g" },
                            )
                        }
                    }
                }

                // Decision reason — monospace
                if (runState.lastReason.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.dense))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Spacer(Modifier.height(Spacing.dense))
                    Text(
                        "DECISION",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBar.Neutral,
                        letterSpacing = 0.07.sp,
                    )
                    Spacer(Modifier.height(Spacing.dense))
                    Text(
                        runState.lastReason,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFCCCCCC),
                    )
                }
            }

            // Savings block (shown when measuring OR ready)
            Spacer(Modifier.height(Spacing.item))
            ArsenalPanel(accent = AccentBar.Emerald, title = "Draw savings (measured this session)") {
                DrawSavingsArsenalBlock(savings = runState.savings)
            }
        }

        AutoTdpStatus.KILLED_BY_SAFETY -> {
            ArsenalPanel(accent = AccentBar.Red, title = "Stopped by safety guard") {
                Text(
                    runState.killReason ?: "AutoTDP stopped itself. All kernel writes reverted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBar.Red,
                )
            }
        }

        AutoTdpStatus.WRITE_DENIED -> {
            ArsenalPanel(accent = AccentBar.Red, title = "Kernel write denied") {
                Text(
                    runState.writeFailure
                        ?: "A mid-run write was rejected. All writes reverted. Try the unlock script or root.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBar.Red,
                )
            }
        }

        AutoTdpStatus.STOPPED -> {
            ArsenalPanel(accent = AccentBar.Neutral, title = "Stopped") {
                Text(
                    "Stopped cleanly. All kernel writes reverted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            }
        }

        AutoTdpStatus.LIVE_UNAVAILABLE -> {
            ArsenalPanel(accent = AccentBar.Amber, title = "Live writes unavailable") {
                Text(
                    runState.liveUnavailableReason
                        ?: "This device cannot do live sysfs writes. Use the SCRIPT rung instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBar.Amber,
                )
            }
        }

        AutoTdpStatus.IDLE -> Unit
    }
}

@Composable
private fun DrawSavingsArsenalBlock(savings: SavingsResult?) {
    when {
        savings == null -> {
            Text(
                "Measuring baseline… sampling battery draw before AutoTDP enables (~20 s). Keep the same workload running.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
        }
        !savings.enoughData -> {
            StatBar(
                label = "Measuring",
                value = "${savings.sampleCount} / ${AutoTdpSavings.MIN_SAMPLES_FOR_REPORT}",
                fraction = savings.sampleCount.toFloat() / AutoTdpSavings.MIN_SAMPLES_FOR_REPORT,
                accent = AccentBar.Emerald,
            )
        }
        else -> {
            val saving = savings.deltaMw
            val pct = kotlin.math.abs(savings.deltaPct)
            val isSaving = saving > 0
            val accent = if (isSaving) AccentBar.Emerald else AccentBar.Red

            if (isSaving) {
                Text(
                    "Saving ~${saving} mW / ${"%.1f".format(pct)}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                )
                Spacer(Modifier.height(Spacing.dense))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
            ) {
                MetricTile(
                    label = "BASELINE",
                    value = "${savings.baselineMw}",
                    unit = "mW",
                    accent = AccentBar.Neutral,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "WITH AUTOTDP",
                    value = "${savings.tunedMw}",
                    unit = "mW",
                    accent = AccentBar.Emerald,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "DELTA",
                    value = if (isSaving) "-$saving" else "+${-saving}",
                    unit = "mW",
                    accent = accent,
                    valueColor = accent,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "${savings.sampleCount} samples — measured on this device, this session.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF777777),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  EfficiencyAdvisor surface
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EfficiencyAdvisorPanel(
    plan: EfficiencyPlan?,
    sweepState: SweepUiState,
    kneeKhz: Int?,
    onStartSweep: () -> Unit,
    onCancelSweep: () -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Emerald, title = "Efficiency Advisor") {
        if (plan == null) {
            // No plan yet
            Text(
                "No efficiency data. Run the sweep below to measure your device's perf-per-watt curve.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
        } else {
            // Show the plan summary
            Text(
                plan.summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCCCCCC),
            )

            if (plan.drawEstimateSource == EstimateSource.MEASURED && plan.estimatedDrawReductionPct != null) {
                Spacer(Modifier.height(Spacing.dense))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
                ) {
                    plan.bigClusterKneeCap?.let { cap ->
                        MetricTile(
                            label = "KNEE CAP",
                            value = "${cap / 1000}",
                            unit = "MHz",
                            accent = AccentBar.Emerald,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MetricTile(
                        label = "DRAW REDUCTION",
                        value = "${plan.estimatedDrawReductionPct}",
                        unit = "%",
                        accent = AccentBar.Emerald,
                        valueColor = AccentBar.Emerald,
                        modifier = Modifier.weight(1f),
                    )
                    plan.gpuPowerLevelFloor?.let { lvl ->
                        MetricTile(
                            label = "GPU FLOOR LVL",
                            value = "$lvl",
                            unit = null,
                            accent = AccentBar.Purple,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.dense))
                StatusPill(text = "MEASURED", accent = AccentBar.Emerald)
            } else if (plan.drawEstimateSource == EstimateSource.ESTIMATED) {
                Spacer(Modifier.height(Spacing.dense))
                StatusPill(text = "ESTIMATED — run sweep for real data", accent = AccentBar.Amber)
            }
        }

        Spacer(Modifier.height(Spacing.group))
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(Spacing.group))

        // Efficiency sweep section
        SectionHeader(title = "Efficiency Curve Finder", accent = AccentBar.Emerald)
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "Steps the big-cluster cap through its OPP table under synthetic load; finds the perf-per-watt knee. " +
                "Takes ~${estimateSweepMinutes(sweepState)} min. Run while discharging.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )

        Spacer(Modifier.height(Spacing.group))

        when (val s = sweepState) {
            is SweepUiState.Idle -> {
                ArsenalButton(
                    label = if (kneeKhz != null) "Re-run efficiency sweep" else "Run efficiency sweep",
                    onClick = onStartSweep,
                    accent = AccentBar.Emerald,
                    style = ArsenalButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is SweepUiState.Running -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    StatBar(
                        label = "Step ${s.stepIndex} / ${s.totalSteps} — ${s.currentCapKhz / 1000} MHz cap",
                        value = "${s.stepIndex}/${s.totalSteps}",
                        fraction = if (s.totalSteps > 0) s.stepIndex.toFloat() / s.totalSteps else 0f,
                        accent = AccentBar.Emerald,
                    )
                    ArsenalButton(
                        label = "Cancel sweep",
                        onClick = onCancelSweep,
                        accent = AccentBar.Red,
                        style = ArsenalButtonStyle.Secondary,
                    )
                }
            }
            is SweepUiState.Done -> {
                val ranked = remember(s.result) {
                    AutoTdpViewModel.rankOppPoints(s.result.points, s.result.knee?.capKhz)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    Text(
                        s.result.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCCCCCC),
                    )
                    ranked.forEach { pt ->
                        StatBar(
                            label = "${pt.capMhz} MHz",
                            value = "${"%.1f".format(pt.perfPerWatt)} p/W  ${pt.drawMw}mW",
                            fraction = pt.ppwFraction,
                            accent = if (pt.isKnee) AccentBar.Emerald else AccentBar.Neutral.copy(alpha = 0.5f),
                        )
                        if (pt.isKnee) {
                            StatusPill(text = "KNEE — BEST EFFICIENCY", accent = AccentBar.Emerald)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    Text(
                        "Bar width = perf/W relative to best step. Measured on your device, approximate.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF777777),
                    )
                    ArsenalButton(
                        label = "Re-run sweep",
                        onClick = onStartSweep,
                        accent = AccentBar.Emerald,
                        style = ArsenalButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            is SweepUiState.Failed -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    AlertCard(type = AlertType.WARNING, title = "Sweep failed", message = s.reason)
                    ArsenalButton(
                        label = "Retry sweep",
                        onClick = onStartSweep,
                        accent = AccentBar.Amber,
                        style = ArsenalButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Undervolt capability tier panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UndervoltTierPanel(capability: io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapability) {
    val (accent, tierLabel, tierBody) = when (capability.tier) {
        io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapabilityTier.REAL_VOLTAGE_TABLE -> Triple(
            AccentBar.Emerald,
            "VOLTAGE TABLE PRESENT",
            "A per-OPP voltage table was found and is writable on this device. Real per-frequency voltage control is available (rare on stock firmware).",
        )
        io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapabilityTier.KNEE_EQUIVALENT -> Triple(
            AccentBar.Blue,
            "KNEE-EQUIVALENT EFFICIENCY",
            "No writable voltage table on this device (stock Snapdragon/Qualcomm CPR manages voltages in signed firmware). Efficiency is achieved by frequency-capping clusters at the measured perf-per-watt knee — this achieves most of the battery benefit of undervolting by staying on the flat V/F curve.",
        )
        io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapabilityTier.READ_ONLY -> Triple(
            AccentBar.Neutral,
            "READ ONLY",
            "Neither voltage table nor frequency-cap writes are available. Advisory mode only.",
        )
    }

    ArsenalPanel(accent = accent, title = "Efficiency capability") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            verticalAlignment = Alignment.Top,
        ) {
            StatusPill(text = tierLabel, accent = accent)
        }
        Spacer(Modifier.height(Spacing.dense))
        Text(
            tierBody,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBBBBBB),
        )

        // NEVER show a fake voltage slider — only if real table is present
        if (capability.tier == io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapabilityTier.REAL_VOLTAGE_TABLE) {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "CPU voltage table writable: ${capability.cpuVoltTableWritable} | GPU voltage table writable: ${capability.gpuVoltTableWritable}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = AccentBar.Emerald,
            )
        }
        // On KNEE_EQUIVALENT: explicit note — never a fake slider
        if (capability.tier == io.github.mayusi.calibratesoc.data.efficiency.UndervoltCapabilityTier.KNEE_EQUIVALENT) {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "Freq-cap writes available: ${capability.freqCapWritable}  |  Voltage table: not exposed by stock firmware",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF888888),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Battery target card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BatteryTargetArsenalCard(
    targetHours: Double,
    result: io.github.mayusi.calibratesoc.data.autotdp.BatteryTarget.BatteryTargetResult?,
    onTargetHoursChanged: (Double) -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Amber, title = "Battery target") {
        Text(
            "Set how many hours you want. AutoTDP computes the big-cluster cap that would sustain that life at current draw. Estimate — real life depends on your actual workload.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.dense))
        var hoursText by remember(targetHours) { mutableStateOf("%.1f".format(targetHours)) }
        OutlinedTextField(
            value = hoursText,
            onValueChange = { text ->
                hoursText = text
                text.toDoubleOrNull()?.takeIf { it > 0 }?.let { onTargetHoursChanged(it) }
            },
            label = { Text("Target hours") },
            suffix = { Text("h") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        result?.let { r ->
            Spacer(Modifier.height(Spacing.dense))
            Text(
                r.honestyNote,
                style = MaterialTheme.typography.bodySmall,
                color = if (r.achievable) Color(0xFFBBBBBB) else AccentBar.Amber,
            )
            if (r.achievable && r.mappedCapKhz != null) {
                Spacer(Modifier.height(Spacing.dense))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
                ) {
                    MetricTile(
                        label = "REQUIRED CAP",
                        value = "${r.mappedCapKhz / 1000}",
                        unit = "MHz",
                        accent = AccentBar.Amber,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "BUDGET",
                        value = "%.1f".format(r.budgetW),
                        unit = "W",
                        accent = AccentBar.Amber,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  PServer unlock CTA
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PServerUnlockArsenalCard(
    lastDeploy: AdvancedPermissionsScript.Deployed?,
    onDeploy: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val vendorName = remember {
        OdinIntents.vendorSettingsName(context)
    }

    ArsenalPanel(accent = AccentBar.Amber, title = "One-Time Unlock → LIVE") {
        Text(
            "This device has AYN's PServer vendor service. Run the unlock script ONCE via $vendorName → Run script as Root. " +
                "After that, AutoTDP controls clocks live until the next reboot.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBBBBBB),
        )
        Spacer(Modifier.height(Spacing.dense))
        AlertCard(
            type = AlertType.INFO,
            title = "What the script does",
            message = "• Adds this app to PServer whitelist\n" +
                "• Grants DUMP + PACKAGE_USAGE_STATS + WRITE_SECURE_SETTINGS\n" +
                "• chmod 666 on cpufreq/GPU/governor sysfs nodes (resets at reboot)\n" +
                "Plain text — you can audit it before running.",
        )
        Spacer(Modifier.height(Spacing.group))
        if (lastDeploy == null) {
            ArsenalButton(
                label = "Generate unlock script",
                onClick = onDeploy,
                accent = AccentBar.Amber,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val fileName = remember(lastDeploy.path) { lastDeploy.path.substringAfterLast('/') }
            Text(fileName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            if (lastDeploy.visibleToOdinPicker) {
                Text(
                    "1. Tap 'Open $vendorName'\n2. Tap 'Run script as Root'\n3. Pick the .sh\n4. Tap 'Check again'",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF999999),
                )
            } else {
                Text(
                    "Script saved to app-private folder. Copy to /sdcard/CalibrateSoC/ manually then run.",
                    style = MaterialTheme.typography.bodySmall, color = AccentBar.Amber,
                )
            }
            Spacer(Modifier.height(Spacing.group))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                if (lastDeploy.visibleToOdinPicker) {
                    ArsenalButton(label = "Open $vendorName", onClick = { OdinIntents.openVendorSettings(context) }, accent = AccentBar.Amber)
                }
                ArsenalButton(label = "Check again", onClick = onRefresh, accent = AccentBar.Blue, style = ArsenalButtonStyle.Secondary)
                ArsenalButton(label = "Dismiss", onClick = onDismiss, accent = AccentBar.Neutral, style = ArsenalButtonStyle.Secondary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Last script deploy card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LastScriptArsenalCard(
    deployed: AynScriptDeployer.Deployed,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val vendorName = remember { OdinIntents.vendorSettingsName(context) }

    ArsenalPanel(accent = AccentBar.Amber, title = "Script generated") {
        val fileName = remember(deployed.path) { deployed.path.substringAfterLast('/') }
        Text(fileName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
        if (deployed.visibleToOdinPicker) {
            Text(
                "1. Tap 'Open $vendorName'\n2. Tap 'Run script as Root'\n3. Pick the .sh\nRe-run after each reboot.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF999999),
            )
        } else {
            Text(
                "Script in app-private folder — copy to /sdcard/CalibrateSoC/ manually.",
                style = MaterialTheme.typography.bodySmall, color = AccentBar.Amber,
            )
        }
        Spacer(Modifier.height(Spacing.group))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
            if (deployed.visibleToOdinPicker) {
                ArsenalButton(label = "Open $vendorName", onClick = { OdinIntents.openVendorSettings(context) }, accent = AccentBar.Amber)
            }
            ArsenalButton(label = "Dismiss", onClick = onDismiss, accent = AccentBar.Neutral, style = ArsenalButtonStyle.Secondary)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Companion toggles panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CompanionTogglesArsenalPanel(
    idleChargeTriggerEnabled: Boolean,
    perAppMapCount: Int,
    onIdleChargeToggle: (Boolean) -> Unit,
    onOpenPerApp: () -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Blue, title = "Companion features") {
        // Idle/charge toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("IDLE / CHARGE DOWNCLOCK", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 0.06.sp)
                Text(
                    "Applies EFFICIENCY floor when screen off or charging. Restores on screen-on/unplug.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            }
            Spacer(Modifier.width(Spacing.group))
            Box(
                modifier = Modifier
                    .background(
                        if (idleChargeTriggerEnabled) AccentBar.Emerald.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                        RoundedCornerShape(4.dp),
                    )
                    .border(1.dp, if (idleChargeTriggerEnabled) AccentBar.Emerald else Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .clickable { onIdleChargeToggle(!idleChargeTriggerEnabled) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (idleChargeTriggerEnabled) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (idleChargeTriggerEnabled) AccentBar.Emerald else Color(0xFF888888),
                    letterSpacing = 0.06.sp,
                )
            }
        }

        Spacer(Modifier.height(Spacing.group))
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(Spacing.group))

        // Per-app map
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    Text("PER-APP AUTOTDP PROFILES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 0.06.sp)
                    if (perAppMapCount > 0) StatusPill(text = "$perAppMapCount", accent = AccentBar.Blue)
                }
                Text(
                    "Bind an AutoTDP profile to a specific app. Applies automatically when that app is in the foreground.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            }
            Spacer(Modifier.width(Spacing.group))
            ArsenalButton(
                label = "Edit",
                onClick = onOpenPerApp,
                accent = AccentBar.Blue,
                style = ArsenalButtonStyle.Secondary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Per-app efficiency dialog (unchanged behavior, same logic)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PerAppEfficiencyDialog(
    currentMap: Map<String, AutoTdpProfile>,
    onSetProfile: (String, AutoTdpProfile?) -> Unit,
    onDismiss: () -> Unit,
) {
    var packageInput by remember { mutableStateOf("") }
    var selectedProfile by remember { mutableStateOf(AutoTdpProfile.EFFICIENCY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Per-app AutoTDP profiles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Text(
                    "Map a package name to an AutoTDP profile. When that app is in the foreground and AutoTDP is not manually ON, this profile is automatically applied.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (currentMap.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Current mappings:", style = MaterialTheme.typography.labelMedium)
                    currentMap.forEach { (pkg, profile) ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pkg.substringAfterLast('.'), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text(profile.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { onSetProfile(pkg, null) }) { Text("Remove") }
                        }
                    }
                    HorizontalDivider()
                }
                OutlinedTextField(
                    value = packageInput,
                    onValueChange = { packageInput = it },
                    label = { Text("Package name (e.g. com.example.game)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ArsenalProfilePicker(
                    selected = selectedProfile,
                    onSelected = { selectedProfile = it },
                    excludeBatteryTarget = true,
                )
                Button(
                    onClick = {
                        val pkg = packageInput.trim()
                        if (pkg.isNotEmpty()) {
                            onSetProfile(pkg, selectedProfile)
                            packageInput = ""
                        }
                    },
                    enabled = packageInput.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add mapping") }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Abort / start-error notice (FIX 4)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Persistent notice surfaced whenever AutoTDP stops for a non-user reason.
 *
 * Stays visible after the button returns to STOPPED so the user knows WHY the
 * daemon stopped instead of seeing a silent flip to "STOPPED" with no context.
 * The [message] string is provided by the ViewModel and is always plain-language
 * (no internal codes, no fake values — honesty-first).
 *
 * FIX 4: abort paths (LIVE_UNAVAILABLE, KILLED_BY_SAFETY, WRITE_DENIED) all now
 * route here with a human-readable explanation before clearing _manuallyOn.
 */
@Composable
private fun AbortNoticeCard(
    message: String,
    onDismiss: () -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Amber) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = AccentBar.Amber,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Text(
                    "AutoTDP stopped",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentBar.Amber,
                    letterSpacing = 0.07.sp,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCCCCCC),
                )
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun estimateSweepMinutes(state: SweepUiState): String {
    val steps = when (state) {
        is SweepUiState.Running -> state.totalSteps
        else -> 8
    }
    val totalSeconds = steps * 5
    return if (totalSeconds < 60) "<1" else "${totalSeconds / 60}"
}
