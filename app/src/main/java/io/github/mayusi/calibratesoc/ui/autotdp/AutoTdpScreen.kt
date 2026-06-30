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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpEffect
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.ChargingBundle
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpSavings
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.DecisionRecord
import io.github.mayusi.calibratesoc.data.autotdp.EffectSource
import io.github.mayusi.calibratesoc.data.autotdp.HoldReason
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
import io.github.mayusi.calibratesoc.ui.autotdp.adaptive.AdaptivePanel
import io.github.mayusi.calibratesoc.ui.autotdp.adaptive.AdaptiveViewModel
import io.github.mayusi.calibratesoc.ui.theme.Spacing
import kotlinx.coroutines.delay

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
/** Top-level mode tabs for the AutoTDP screen (Unit 4). */
private enum class AutoTdpScreenMode { ADAPTIVE, GOAL_MODES, MANUAL }

@Composable
fun AutoTdpScreen(
    onBack: () -> Unit = {},
    viewModel: AutoTdpViewModel = hiltViewModel(),
    smartVm: SmartAutoTdpViewModel = hiltViewModel(),
    adaptiveVm: AdaptiveViewModel = hiltViewModel(),
) {
    val rung by viewModel.rung.collectAsStateWithLifecycle()
    val liveMechanism by viewModel.liveMechanism.collectAsStateWithLifecycle()
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    // NOTE: the legacy top-level `selectedProfile` collection was removed with the
    // legacy PROFILE picker (Wave 4b). The Per-App dialog keeps its OWN local profile
    // state; the main screen drives everything off `smartSelectedGoal`.
    val targetHours by viewModel.targetHours.collectAsStateWithLifecycle()
    val batteryTargetResult by viewModel.batteryTargetResult.collectAsStateWithLifecycle()
    val batteryCapacityUnavailable by viewModel.batteryCapacityUnavailable.collectAsStateWithLifecycle()
    val sweepProgress by viewModel.sweepProgress.collectAsStateWithLifecycle()
    val kneeKhz by viewModel.kneeKhz.collectAsStateWithLifecycle()
    val idleChargeTriggerEnabled by viewModel.idleChargeTriggerEnabled.collectAsStateWithLifecycle()
    val chargingProfileEnabled by viewModel.chargingProfileEnabled.collectAsStateWithLifecycle()
    val chargingBundle by viewModel.chargingBundle.collectAsStateWithLifecycle()
    val manuallyOn by viewModel.manuallyOn.collectAsStateWithLifecycle()
    val startingUp by viewModel.startingUp.collectAsStateWithLifecycle()
    val startError by viewModel.startError.collectAsStateWithLifecycle()
    val lastScriptDeploy by viewModel.lastScriptDeploy.collectAsStateWithLifecycle()
    val perAppMap by viewModel.perAppMap.collectAsStateWithLifecycle()
    val showPServerUnlockCta by viewModel.showPServerUnlockCta.collectAsStateWithLifecycle()
    val unlockLadder by viewModel.unlockLadder.collectAsStateWithLifecycle()
    val lastUnlockDeploy by viewModel.lastUnlockDeploy.collectAsStateWithLifecycle()
    val liveSnapshot by viewModel.liveSnapshot.collectAsStateWithLifecycle()
    val efficiencyPlan by viewModel.efficiencyPlan.collectAsStateWithLifecycle()
    val undervoltCapability by viewModel.undervoltCapability.collectAsStateWithLifecycle()

    // Smart goal picker state (Wave 4b)
    val smartSelectedGoal by smartVm.selectedGoal.collectAsStateWithLifecycle()
    val smartActiveGoal by smartVm.activeGoal.collectAsStateWithLifecycle()
    val smartDetectedContext by smartVm.detectedContext.collectAsStateWithLifecycle()
    val smartIsRunning by smartVm.isRunning.collectAsStateWithLifecycle()
    val smartFpsFloor by smartVm.fpsFloor.collectAsStateWithLifecycle()
    val smartTempCeiling by smartVm.tempCeilingC.collectAsStateWithLifecycle()
    val smartRuntimeHours by smartVm.targetRuntimeHours.collectAsStateWithLifecycle()
    val smartFpsDegraded by smartVm.fpsFloorDegraded.collectAsStateWithLifecycle()
    val smartRuntimeNote by smartVm.runtimeProjectionNote.collectAsStateWithLifecycle()

    // Adaptive mode state (Unit 4)
    val adaptiveSelectedPreset by adaptiveVm.selectedPreset.collectAsStateWithLifecycle()
    val adaptiveCustomIntent by adaptiveVm.customIntent.collectAsStateWithLifecycle()
    val adaptiveEffectiveIntent by adaptiveVm.effectiveIntent.collectAsStateWithLifecycle()
    val adaptiveNearestPreset by adaptiveVm.nearestPreset.collectAsStateWithLifecycle()
    val adaptiveGpuOcTier by adaptiveVm.gpuOcTier.collectAsStateWithLifecycle()
    val adaptiveBeyondStockConsent by adaptiveVm.beyondStockConsent.collectAsStateWithLifecycle()
    val adaptiveBeyondStockVerdict by adaptiveVm.beyondStockProbeVerdict.collectAsStateWithLifecycle()
    val adaptiveIsRunning by adaptiveVm.isRunning.collectAsStateWithLifecycle()
    val adaptiveCpuCapLabel by adaptiveVm.liveCpuCapLabel.collectAsStateWithLifecycle()
    val adaptiveGpuLabel by adaptiveVm.liveGpuLabel.collectAsStateWithLifecycle()
    val adaptiveWhyLabel by adaptiveVm.liveWhyLabel.collectAsStateWithLifecycle()
    val adaptiveModeActive by adaptiveVm.adaptiveModeActive.collectAsStateWithLifecycle()

    // Mode segmented control state (Unit 4) — Adaptive is the default-highlighted tab
    var screenMode by remember { mutableStateOf(AutoTdpScreenMode.ADAPTIVE) }

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
                // WAVE 4b WIRING FIX: START now starts with the selected GOAL MODE.
                // Previously this called setManualEnabled(it) with no goal, so the
                // daemon always ran the legacy BALANCED profile regardless of which
                // GOAL chip was picked. We pass smartSelectedGoal through so the goal
                // (incl. AUTO) reaches AutoTdpController.start(goal) → the engine's
                // goalOverride. The watts ceiling (BATTERY_SAVER only) is derived
                // inside the VM from the battery-target preview, so we pass null here.
                onToggle = { turnOn ->
                    viewModel.setManualEnabled(turnOn, smartSelectedGoal, targetMilliWatts = null)
                },
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
        item { ArsenalRungBanner(rung = rung, liveMechanism = liveMechanism, runState = runState) }

        // ── 5. Mode segmented control + control panel (Unit 4) ────────────────
        // Adaptive is the new default tab; Goal Modes preserves the existing
        // GoalPickerPanel; Manual = placeholder for future manual controls.
        // The insert is purely ADDITIVE — GoalPickerPanel is unchanged, just
        // gated behind the Goal Modes tab.
        //
        // TODO: AutoTdpScreen.kt is ~2200 LOC — flagged as split candidate.
        // Suggested split: AdaptiveSection.kt + GoalModesSection.kt extracted
        // as top-level item-slot composables when this file grows further.
        item {
            AutoTdpModeSegmentedControl(
                selected = screenMode,
                onSelect = { screenMode = it },
            )
        }
        item {
            when (screenMode) {
                AutoTdpScreenMode.ADAPTIVE -> AdaptivePanel(
                    selectedPreset       = adaptiveSelectedPreset,
                    customIntent         = adaptiveCustomIntent,
                    effectiveIntent      = adaptiveEffectiveIntent,
                    nearestPreset        = adaptiveNearestPreset,
                    gpuOcTier            = adaptiveGpuOcTier,
                    beyondStockConsent   = adaptiveBeyondStockConsent,
                    beyondStockVerdict   = adaptiveBeyondStockVerdict,
                    isRunning            = adaptiveIsRunning,
                    liveCpuCapLabel      = adaptiveCpuCapLabel,
                    liveGpuLabel         = adaptiveGpuLabel,
                    liveTemp             = runState.appliedState?.let { state ->
                        // temperature is not in TdpState directly; surface from
                        // liveSnapshot when available (Unit 5 wires the live °C)
                        null
                    },
                    liveWhyLabel         = adaptiveWhyLabel,
                    adaptiveModeActive   = adaptiveModeActive,
                    onSelectPreset       = { adaptiveVm.selectPreset(it) },
                    onUpdateWeight       = { axis, v -> adaptiveVm.updateCustomWeight(axis, v) },
                    onEnterCustom        = { adaptiveVm.enterCustom() },
                    onExitToPreset       = { adaptiveVm.exitToPreset() },
                    onSetGpuOcTier       = { adaptiveVm.setGpuOcTier(it) },
                    onGrantConsent       = { adaptiveVm.grantBeyondStockConsent() },
                    onSetAdaptiveActive  = { adaptiveVm.setAdaptiveActive(it) },
                )
                AutoTdpScreenMode.GOAL_MODES -> GoalPickerPanel(
                    selectedGoal         = smartSelectedGoal,
                    activeGoal           = smartActiveGoal,
                    detectedContext      = smartDetectedContext,
                    isRunning            = smartIsRunning,
                    onSelectGoal         = { smartVm.selectGoal(it) },
                    fpsFloor             = smartFpsFloor,
                    tempCeilingC         = smartTempCeiling,
                    targetRuntimeHours   = smartRuntimeHours,
                    onSetFpsFloor        = { smartVm.setFpsFloor(it) },
                    onSetTempCeiling     = { smartVm.setTempCeilingC(it) },
                    onSetRuntimeHours    = { smartVm.setTargetRuntimeHours(it) },
                    fpsFloorDegraded     = smartFpsDegraded,
                    runtimeProjectionNote = smartRuntimeNote,
                )
                AutoTdpScreenMode.MANUAL -> {
                    // Manual controls placeholder — future wave.
                    // No controls have been removed; this slot is additive.
                }
            }
        }

        // ── 6. Battery target input (reachable under the Battery-Saver GOAL) ────
        // The watts/hours ceiling input is kept reachable, now gated on the GOAL MODE
        // (BATTERY_SAVER carries the hard power ceiling) instead of the removed legacy
        // BATTERY_TARGET profile. The VM derives the watts budget from this preview
        // when starting a BATTERY_SAVER goal.
        if (screenMode == AutoTdpScreenMode.GOAL_MODES && smartSelectedGoal == GoalProfile.BATTERY_SAVER) {
            item {
                BatteryTargetArsenalCard(
                    targetHours = targetHours,
                    result = batteryTargetResult,
                    capacityUnavailable = batteryCapacityUnavailable,
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

        // ── 10b. Vendor-neutral "reach LIVE" ladder ───────────────────────────
        // Shown on ANY device when AutoTDP is not yet live. Ordered best-first:
        // Grant Shizuku → Run unlock script → Enable root. The AYN PServer CTA
        // below is the strongest form of the unlock-script rung on AYN/Odin.
        unlockLadder?.let { ladder ->
            item {
                UnlockLadderArsenalCard(ladder = ladder)
            }
        }

        // ── 11. PServer unlock CTA (AYN binder — strongest unlock-script rung) ──
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
                chargingProfileEnabled = chargingProfileEnabled,
                chargingBundle = chargingBundle,
                onChargingProfileToggle = { viewModel.setChargingProfileEnabled(it) },
                onChargingGoalSelected = { viewModel.setChargingAutoTdpGoal(it) },
                onChargingFanModeSelected = { viewModel.setChargingFanMode(it) },
                onChargingRefreshRateSelected = { viewModel.setChargingRefreshRateHz(it) },
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

/**
 * Mechanism-aware body copy for the LIVE rung banner.
 *
 * The LIVE rung is reached by several DISTINCT live-write paths; the banner must name
 * the one THIS device actually uses instead of the old generic "PServer, direct sysfs,
 * or root" line — which is flat-out wrong on a zero-setup AYANEO that is live ONLY via
 * its vendor binder (no PServer, no root, no sysfs). Each branch is honest about what its
 * path can and cannot drive.
 *
 * Copy is kept consistent with `ui.capability.CapabilityUi.explainerText` (the tier-chip
 * explainer) so the AutoTDP screen and the Capability screen never contradict each other.
 *
 * HONESTY: the AYANEO branch is only ever shown when [LiveMechanism.AYANEO_BINDER] — which
 * the VM derives strictly from `ayaneoBinderLive` — and it states accurately that the
 * binder drives the CPU cluster cap, governor, GPU max, and fan, but NOT core parking
 * (the engine rides the cap lever instead, which is why AutoTDP still runs live there).
 */
private fun liveRungBody(mechanism: LiveMechanism, running: Boolean): String {
    val revertTail = "All writes revert on stop."
    return when (mechanism) {
        LiveMechanism.AYANEO_BINDER ->
            if (running) {
                "Live control confirmed via AYANEO's vendor service (AyaAidlService) — CPU cluster capped at the efficiency knee, governor + GPU max + fan applied directly. (Core parking isn't on the binder path, so the engine rides the cap lever.) $revertTail"
            } else {
                "Live tuning is active through AYANEO's vendor service (AyaAidlService) — no root, no script, no reboot, nothing to set up. AutoTDP caps the CPU cluster, sets the governor, and drives GPU max + fan directly. (Core parking isn't on the binder path — the engine caps instead, so the loop still runs live.)"
            }
        LiveMechanism.PSERVER_ROOT ->
            if (running) {
                "Live control confirmed via PServer — daemon writing every second. Prime cores park when GPU-bound; big cluster capped at efficiency knee; GPU prioritised. $revertTail"
            } else {
                "Live via PServer (the vendor root runner) — Apply works for everything directly, no script and no reboot. AutoTDP runs a closed-loop daemon: parks prime cores, caps the big cluster, reverts everything on stop."
            }
        LiveMechanism.GENERIC_ROOT ->
            if (running) {
                "Live control confirmed via root — daemon writing every second. Prime cores park when GPU-bound; big cluster capped at efficiency knee; GPU prioritised. $revertTail"
            } else {
                "Live via root (Magisk/KernelSU) — direct sysfs writes, Apply works for everything. AutoTDP runs a closed-loop daemon: parks prime cores, caps the big cluster, reverts everything on stop."
            }
        LiveMechanism.SHIZUKU ->
            if (running) {
                "Live control confirmed via Shizuku — daemon writing every second. Prime cores park when GPU-bound; big cluster capped at efficiency knee; GPU prioritised. $revertTail"
            } else {
                "Live via Shizuku — the per-node probe confirmed shell can write the cpufreq nodes, so AutoTDP runs its closed-loop daemon with no root and no reboot: parks prime cores, caps the big cluster, reverts everything on stop."
            }
        LiveMechanism.DIRECT_SYSFS, LiveMechanism.NONE ->
            // DIRECT_SYSFS = the unlock script chmod'd the nodes. NONE should not occur for
            // a LIVE rung, but fall back to the accurate vendor-neutral "unlocked" copy
            // rather than naming a path we cannot confirm.
            if (running) {
                "Live control confirmed — daemon writing every second. Prime cores park when GPU-bound; big cluster capped at efficiency knee; GPU prioritised. $revertTail"
            } else {
                "Sysfs is live-writable on this device (the unlock script chmod'd the cpufreq nodes — no root, no reboot). AutoTDP runs a closed-loop daemon: parks prime cores, caps the big cluster, reverts everything on stop."
            }
    }
}

@Composable
private fun ArsenalRungBanner(
    rung: AutoTdpRung,
    liveMechanism: LiveMechanism,
    runState: AutoTdpRunState,
) {
    val (accent, statusLabel, body) = when (rung) {
        AutoTdpRung.LIVE -> Triple(
            AccentBar.Emerald,
            "RUNG: LIVE",
            liveRungBody(liveMechanism, running = runState.status == AutoTdpStatus.RUNNING),
        )
        AutoTdpRung.SCRIPT -> Triple(
            AccentBar.Amber,
            "RUNG: SCRIPT",
            "Live auto-adjust needs the one-time unlock (see below) or root. This rung generates the best static efficiency tune as a shell script — run it once via 'Run script as Root'. No live loop; settings revert on reboot.",
        )
        AutoTdpRung.ADVISORY -> Triple(
            AccentBar.Neutral,
            "RUNG: ADVISORY",
            "No write access. AutoTDP can read live telemetry and show efficiency advice, but cannot apply any changes. On supported devices, grant live tuning from Tune → HUD & FPS permissions. Otherwise, use root to reach SCRIPT or LIVE.",
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
            // Rich "proof it's working" stack. Owns a 1 s live clock so the
            // heartbeat ("Xs ago") and pulse animate even between daemon ticks.
            AutoTdpProofOfEffectPanels(runState = runState)
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

// ─────────────────────────────────────────────────────────────────────────────
//  AutoTDP "proof it's working" panel suite (RUNNING only)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The rich proof-of-effect stack shown while AutoTDP is RUNNING.
 *
 * Honesty-first: every measured row is hidden unless its backing field on
 * [AutoTdpRunState] is non-null. DERIVED facts (what the tuner changed, why it
 * is holding) render immediately; MEASURED facts (power/temp/fps/energy) appear
 * only once the ~40 s A/B probe lands ([EffectSource.MEASURED] / enoughData).
 *
 * Sections, top-to-bottom:
 *  1. WHAT IT CHANGED NOW    — derived applied-config facts.
 *  2. WHY IT'S HOLDING       — clean hold-reason label + raw reason on expand.
 *  3. EFFECT VS STOCK        — measured power/temp/fps proof, or honest "measuring".
 *  4. SESSION SAVINGS        — integrated Wh, hidden until measured.
 *  5. HEARTBEAT              — last-applied time-ago + live pulse.
 *  6. DECISION HISTORY       — compact adapting-over-time timeline.
 *
 * Owns a 1 s clock so HEARTBEAT and the "time-ago" labels stay live between
 * daemon ticks. The clock is read-only display state — no data mutation.
 */
@Composable
private fun AutoTdpProofOfEffectPanels(runState: AutoTdpRunState) {
    // Live 1 s clock for relative-time + pulse. Seeded so the first frame is
    // correct before the first delay elapses.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val effect = runState.effect

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {

        // ── 1. WHAT IT CHANGED NOW ────────────────────────────────────────────
        ArsenalPanel(accent = AccentBar.Blue, title = "What it changed now") {
            WhatItChangedBlock(effect = effect, appliedState = runState.appliedState)
        }

        // ── 2. WHY IT'S HOLDING ───────────────────────────────────────────────
        ArsenalPanel(
            accent = holdReasonAccent(runState.holdReason),
            title = "Why it's holding",
        ) {
            WhyHoldingBlock(holdReason = runState.holdReason, rawReason = runState.lastReason)
        }

        // ── 3. EFFECT VS STOCK (measured) ─────────────────────────────────────
        ArsenalPanel(accent = AccentBar.Emerald, title = "Effect vs stock") {
            EffectVsStockBlock(effect = effect, savings = runState.savings)
        }

        // ── 4. SESSION SAVINGS (hidden until measured) ────────────────────────
        effect?.sessionEnergySavedMilliWh?.let { mwh ->
            ArsenalPanel(accent = AccentBar.Emerald, title = "Session savings") {
                SessionSavingsBlock(milliWh = mwh)
            }
        }

        // ── 5. HEARTBEAT ──────────────────────────────────────────────────────
        runState.lastAppliedEpochMs?.let { lastMs ->
            ArsenalPanel(accent = AccentBar.Neutral) {
                HeartbeatBlock(lastAppliedEpochMs = lastMs, nowMs = nowMs)
            }
        }

        // ── 6. DECISION HISTORY ───────────────────────────────────────────────
        if (runState.decisions.isNotEmpty()) {
            ArsenalPanel(accent = AccentBar.Blue, title = "Decision history") {
                DecisionHistoryBlock(decisions = runState.decisions, nowMs = nowMs)
            }
        }
    }
}

// ── 1. WHAT IT CHANGED NOW ─────────────────────────────────────────────────

@Composable
private fun WhatItChangedBlock(
    effect: AutoTdpEffect?,
    appliedState: io.github.mayusi.calibratesoc.data.autotdp.TdpState?,
) {
    // Source the derived facts from the effect bundle when present (always
    // populated once running); fall back to appliedState for the raw set.
    val parked = effect?.parkedPrimeCores ?: appliedState?.parkedPrimeCores ?: emptySet()
    val bigCapKhz = effect?.bigCapKhz ?: appliedState?.bigClusterCapKhz
    val gpuFloor = effect?.gpuFloorLevel ?: appliedState?.gpuFloorLevel
    val capDeltaKhz = effect?.capDeltaKhz
    val stockCeilingKhz = effect?.stockBigCeilingKhz

    val nothingApplied = parked.isEmpty() && bigCapKhz == null && gpuFloor == null

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        if (nothingApplied) {
            // Honest: derived facts show no change in effect right now.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
                StatusPill(text = "HOLDING AT STOCK", accent = AccentBar.Neutral)
                Text(
                    "No cap, no parked cores, GPU unconstrained — running at the device's stock ceiling this tick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // BIG CLUSTER CAP — derived, with delta vs stock ceiling when known.
            if (bigCapKhz != null) {
                val deltaText = if (capDeltaKhz != null && stockCeilingKhz != null) {
                    "  (-${capDeltaKhz / 1000} MHz vs ${stockCeilingKhz / 1000} stock)"
                } else ""
                KvRow(
                    label = "BIG CLUSTER CAP",
                    value = "${bigCapKhz / 1000} MHz$deltaText",
                    explainer = "Frequency ceiling held below stock to stay on the efficient V/F curve.",
                )
            }
            // PARKED PRIME CORES — derived (count + which).
            if (parked.isNotEmpty()) {
                val cores = parked.sorted()
                KvRow(
                    label = "PARKED PRIME CORES",
                    value = "${cores.size} (${cores.joinToString(", ") { "cpu$it" }})",
                    explainer = "Offlined to shed power; budget redirected to GPU / cooler cores.",
                )
            }
            // GPU LEVEL HELD — derived. 0 = max perf.
            if (gpuFloor != null) {
                KvRow(
                    label = "GPU LEVEL HELD",
                    value = "$gpuFloor${if (gpuFloor == 0) " (max perf)" else ""}",
                    explainer = "0 = fastest. Kept low so the GPU stays fast while CPU is trimmed.",
                )
            }
        }
    }
}

// ── 2. WHY IT'S HOLDING ────────────────────────────────────────────────────

@Composable
private fun WhyHoldingBlock(holdReason: HoldReason, rawReason: String) {
    var expanded by remember { mutableStateOf(false) }
    val accent = holdReasonAccent(holdReason)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            StatusPill(text = holdReasonLabel(holdReason), accent = accent)
            if (rawReason.isNotBlank()) {
                Text(
                    if (expanded) "Hide detail" else "Show detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBar.Blue,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
        Text(
            holdReasonExplainer(holdReason),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBBBBBB),
        )
        // Raw engine reason (with live %s) shown on expand — the unfiltered truth.
        if (expanded && rawReason.isNotBlank()) {
            Spacer(Modifier.height(Spacing.dense))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "RAW ENGINE REASON",
                style = MaterialTheme.typography.labelSmall,
                color = AccentBar.Neutral,
                letterSpacing = 0.07.sp,
            )
            Spacer(Modifier.height(Spacing.dense))
            Text(
                rawReason,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFCCCCCC),
            )
        }
    }
}

// ── 3. EFFECT VS STOCK (measured proof) ────────────────────────────────────

@Composable
private fun EffectVsStockBlock(effect: AutoTdpEffect?, savings: SavingsResult?) {
    val measured = effect?.effectSource == EffectSource.MEASURED &&
        savings != null && savings.enoughData

    if (!measured) {
        // HONEST measuring state — show progress, NEVER a fabricated number.
        val haveSamples = savings != null && savings.sampleCount > 0
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
            Text(
                "Measuring effect… first ~40 s of this run (20 s stock baseline, then 20 s tuned). " +
                    "Keep the same workload running for an honest comparison.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
            if (haveSamples) {
                StatBar(
                    label = "Collecting samples",
                    value = "${savings!!.sampleCount} / ${AutoTdpSavings.MIN_SAMPLES_FOR_REPORT}",
                    fraction = savings.sampleCount.toFloat() / AutoTdpSavings.MIN_SAMPLES_FOR_REPORT,
                    accent = AccentBar.Amber,
                )
            } else {
                StatusPill(text = "PROBE IN PROGRESS", accent = AccentBar.Amber)
            }
        }
        return
    }

    // Measured — render the proof. savings is guaranteed non-null here.
    val s = savings!!
    val saving = s.deltaMw
    val pct = kotlin.math.abs(s.deltaPct)
    val isSaving = saving > 0
    val accent = if (isSaving) AccentBar.Emerald else AccentBar.Red

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        // Headline power saving (W + %).
        val savingW = saving.toDouble() / 1000.0
        Text(
            if (isSaving) "Saving ${"%.2f".format(savingW)} W  /  ${"%.1f".format(pct)}%"
            else "Using ${"%.2f".format(kotlin.math.abs(savingW))} W more  /  ${"%.1f".format(pct)}%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = accent,
        )

        // Baseline vs tuned mW pair + delta.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
        ) {
            MetricTile(
                label = "STOCK BASELINE",
                value = "${s.baselineMw}",
                unit = "mW",
                accent = AccentBar.Neutral,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "TUNED",
                value = "${s.tunedMw}",
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

        // Temp + FPS deltas — each hidden when its measured field is null.
        val tempDelta = effect?.tempDeltaC
        val fpsDelta = effect?.fpsDelta
        if (tempDelta != null || fpsDelta != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
            ) {
                if (tempDelta != null) {
                    val cooler = tempDelta > 0f
                    MetricTile(
                        label = "TEMP DELTA",
                        value = (if (cooler) "-" else "+") + "%.1f".format(kotlin.math.abs(tempDelta)),
                        unit = "°C",
                        accent = AccentBar.Amber,
                        valueColor = if (cooler) AccentBar.Emerald else AccentBar.Red,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (fpsDelta != null) {
                    MetricTile(
                        label = "FPS DELTA",
                        value = if (fpsDelta >= 0) "+$fpsDelta" else "$fpsDelta",
                        unit = "fps",
                        accent = AccentBar.Purple,
                        valueColor = if (fpsDelta >= 0) AccentBar.Emerald else AccentBar.Red,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the row balanced when only one delta is present.
                if (tempDelta == null || fpsDelta == null) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // Confidence + provenance label — explicit about what this is.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            StatusPill(text = "MEASURED", accent = AccentBar.Emerald)
            Text(
                "${s.sampleCount}/${AutoTdpSavings.MIN_SAMPLES_FOR_REPORT}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF999999),
            )
        }
        Text(
            "Measured on your device this session — stock baseline (first 20 s) vs tuned.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
        )
    }
}

// ── 4. SESSION SAVINGS ─────────────────────────────────────────────────────

@Composable
private fun SessionSavingsBlock(milliWh: Double) {
    val wh = milliWh / 1000.0
    val display = if (wh < 0.1) "%.3f".format(wh) else "%.2f".format(wh)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Text(
            "≈ $display Wh saved this session",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = AccentBar.Emerald,
        )
        Text(
            "Based on your measured baseline, integrated over the time AutoTDP has been running.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
        )
    }
}

// ── 5. HEARTBEAT ───────────────────────────────────────────────────────────

@Composable
private fun HeartbeatBlock(lastAppliedEpochMs: Long, nowMs: Long) {
    val stale = isHeartbeatStale(lastAppliedEpochMs, nowMs)
    val accent = if (stale) AccentBar.Amber else AccentBar.Emerald
    val agoText = relativeTimeAgo(lastAppliedEpochMs, nowMs)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        // Live pulse dot — emerald when fresh, muted amber when stalled.
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(accent.copy(alpha = if (stale) 0.5f else 1f), RoundedCornerShape(4.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (stale) "AutoTDP heartbeat stalled" else "AutoTDP adjusted $agoText",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (stale) AccentBar.Amber else Color.White,
            )
            Text(
                if (stale) "Last applied $agoText — no fresh tick recently. It may be holding steady, or the loop is stuck."
                else "The daemon is applying state on its tick — proof it's alive, not silently stuck.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF777777),
            )
        }
        StatusPill(text = if (stale) "STALLED" else "LIVE", accent = accent)
    }
}

// ── 6. DECISION HISTORY ────────────────────────────────────────────────────

@Composable
private fun DecisionHistoryBlock(decisions: List<DecisionRecord>, nowMs: Long) {
    // Newest-first for the timeline; cap the rendered rows so a long ring
    // doesn't dominate the screen (the data layer already bounds to ~20).
    val ordered = remember(decisions) { decisions.asReversed() }
    val shown = ordered.take(8)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Text(
            "Recent decisions (newest first) — proof AutoTDP is adapting, not frozen.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
        )

        // Cap-vs-time sparkline. Flat = holding steady (honest), not faked.
        DecisionCapSparkline(decisions = decisions)

        shown.forEachIndexed { index, d ->
            if (index > 0) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                // Color tick keyed to the hold reason.
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .width(3.dp)
                        .height(14.dp)
                        .background(holdReasonAccent(d.holdReason), RoundedCornerShape(1.dp)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        holdReasonLabel(d.holdReason),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = holdReasonAccent(d.holdReason),
                        letterSpacing = 0.05.sp,
                    )
                    Text(
                        buildString {
                            append(if (d.bigCapKhz != null) "${d.bigCapKhz / 1000} MHz cap" else "uncapped")
                            append(" · ")
                            append(if (d.parkedCount > 0) "${d.parkedCount} parked" else "0 parked")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF999999),
                    )
                }
                Text(
                    relativeTimeAgo(d.epochMs, nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF777777),
                )
            }
        }
    }
}

/**
 * A compact cap-vs-time sparkline drawn from the decision ring. Each bar is a
 * decision's big-cluster cap as a fraction of the max cap seen in the window;
 * uncapped decisions render as a full-height muted bar. A flat line is an
 * HONEST signal that the tuner is holding steady — we never smooth or invent
 * intermediate points.
 */
@Composable
private fun DecisionCapSparkline(decisions: List<DecisionRecord>) {
    // Need at least two points and at least one real cap to be meaningful.
    val caps = decisions.map { it.bigCapKhz }
    val maxCap = caps.filterNotNull().maxOrNull()
    if (decisions.size < 2 || maxCap == null || maxCap <= 0) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        decisions.forEach { d ->
            val cap = d.bigCapKhz
            // Uncapped → full height muted; capped → proportional emerald.
            val fraction = if (cap == null) 1f else (cap.toFloat() / maxCap.toFloat()).coerceIn(0.06f, 1f)
            val barColor = if (cap == null) AccentBar.Neutral.copy(alpha = 0.4f) else AccentBar.Emerald
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .background(barColor, RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp)),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Pure helpers for the proof-of-effect panel (no Android — unit-testable)
// ─────────────────────────────────────────────────────────────────────────────

/** Clean, honest UI label for a [HoldReason]. */
internal fun holdReasonLabel(reason: HoldReason): String = when (reason) {
    HoldReason.CPU_BOUND_RELAXING -> "CPU-bound — relaxing"
    HoldReason.GPU_BOUND_CAPPING -> "GPU-bound — capping"
    HoldReason.BATTERY_TARGET_HOLDING -> "Battery target — holding"
    // Mandatory honesty distinction: load-blind is NOT idle.
    HoldReason.LOAD_BLIND_HOLDING -> "CPU load unreadable — holding"
    HoldReason.IDLE_HOLDING -> "Lightly loaded — holding"
    HoldReason.NO_TELEMETRY -> "No telemetry — holding"
}

/** One-line plain-language explainer for a [HoldReason]. */
internal fun holdReasonExplainer(reason: HoldReason): String = when (reason) {
    HoldReason.CPU_BOUND_RELAXING ->
        "A big/prime core was saturated, so AutoTDP relaxed — unparked a core or stepped the cap up. The CPU is the bottleneck."
    HoldReason.GPU_BOUND_CAPPING ->
        "The workload was GPU-bound across the window, so AutoTDP tightened — parked a prime core or stepped the cap down to redirect power to the GPU."
    HoldReason.BATTERY_TARGET_HOLDING ->
        "Holding the proportional cap derived from your battery-life target, even though neither saturation nor GPU-bound fired."
    HoldReason.LOAD_BLIND_HOLDING ->
        "CPU load is unreadable on this device (no /proc/stat or freq proxy this sample). The CPU could be busy — we can't measure it, so we hold rather than guess. This is NOT idle."
    HoldReason.IDLE_HOLDING ->
        "Load IS readable and is below the saturation threshold, and the workload is not GPU-bound. Genuinely lightly loaded, so nothing to do."
    HoldReason.NO_TELEMETRY ->
        "No telemetry was available this tick (empty window). AutoTDP made no real decision."
}

/** Accent color keyed to a [HoldReason] — emerald=active relax, amber=caution/blind. */
internal fun holdReasonAccent(reason: HoldReason): Color = when (reason) {
    HoldReason.CPU_BOUND_RELAXING -> AccentBar.Emerald
    HoldReason.GPU_BOUND_CAPPING -> AccentBar.Emerald
    HoldReason.BATTERY_TARGET_HOLDING -> AccentBar.Amber
    HoldReason.LOAD_BLIND_HOLDING -> AccentBar.Amber
    HoldReason.IDLE_HOLDING -> AccentBar.Neutral
    HoldReason.NO_TELEMETRY -> AccentBar.Neutral
}

/** Heartbeat staleness threshold — daemon ticks ~1 Hz, so >3 s is stalled. */
internal const val HEARTBEAT_STALE_MS = 3_000L

/** True when the last-applied heartbeat is older than [HEARTBEAT_STALE_MS]. */
internal fun isHeartbeatStale(
    lastAppliedEpochMs: Long,
    nowMs: Long,
    thresholdMs: Long = HEARTBEAT_STALE_MS,
): Boolean = (nowMs - lastAppliedEpochMs) > thresholdMs

/**
 * Compact relative-time label: "just now" (<1 s), "Ns ago" (<60 s),
 * "Nm ago" (<60 min), else "Nh ago". A future/negative delta clamps to
 * "just now" so a clock skew never prints a nonsense value.
 */
internal fun relativeTimeAgo(epochMs: Long, nowMs: Long): String {
    val deltaMs = nowMs - epochMs
    if (deltaMs < 1_000L) return "just now"
    val seconds = deltaMs / 1_000L
    if (seconds < 60L) return "${seconds}s ago"
    val minutes = seconds / 60L
    if (minutes < 60L) return "${minutes}m ago"
    val hours = minutes / 60L
    return "${hours}h ago"
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
    capacityUnavailable: Boolean,
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
        // HONEST fallback (FIX 2): when the device exposes no readable battery charge
        // counter we cannot compute a real required-cap — say so instead of showing a
        // figure backed by a fabricated capacity constant.
        if (capacityUnavailable) {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "Battery capacity unreadable on this device — required-cap estimate unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = AccentBar.Amber,
            )
        }
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
//  Vendor-neutral "reach LIVE" ladder
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Shows the ordered, vendor-neutral path ladder to reach LIVE AutoTDP when the
 * device is not yet live. Honest per-device: each rung is labelled with what it
 * can actually do on THIS device. No vendor-specific assumptions — works for
 * AYANEO / GPD / Anbernic / generic Android exactly as for AYN/Retroid.
 */
@Composable
private fun UnlockLadderArsenalCard(ladder: UnlockLadder) {
    ArsenalPanel(accent = AccentBar.Amber, title = "Reach LIVE — pick a path") {
        Text(
            "AutoTDP isn't doing live kernel writes yet. Any ONE of these unlocks it — " +
                "they're ordered easiest-first.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBBBBBB),
        )
        Spacer(Modifier.height(Spacing.group))
        ladder.steps.forEachIndexed { index, step ->
            UnlockLadderRow(stepNumber = index + 1, step = step)
            if (index < ladder.steps.lastIndex) {
                Spacer(Modifier.height(Spacing.dense))
            }
        }
        if (ladder.vendorBinderPathAvailable) {
            Spacer(Modifier.height(Spacing.group))
            AlertCard(
                type = AlertType.INFO,
                title = "Vendor fast-path detected",
                message = "This device has a vendor service (AYN PServer). The one-time " +
                    "unlock below uses it for the cleanest no-root LIVE — see the card under this one.",
            )
        }
    }
}

@Composable
private fun UnlockLadderRow(stepNumber: Int, step: UnlockStep) {
    val title = when (step.kind) {
        UnlockStepKind.SHIZUKU -> "Grant Shizuku"
        UnlockStepKind.UNLOCK_SCRIPT -> "Run the unlock script once"
        UnlockStepKind.ROOT -> "Enable root mode"
    }
    val detail = when (step.kind) {
        UnlockStepKind.SHIZUKU ->
            "No root, no reboot — the best default. Bind Shizuku and grant this app permission; " +
                "AutoTDP then probes whether the kernel allows shell writes on this device."
        UnlockStepKind.UNLOCK_SCRIPT ->
            "Run it once via your device's \"Run script as Root\" runner. Grants the app's " +
                "permissions and (on AYN/Odin) whitelists it with PServer — works on Enforcing " +
                "SELinux, no Permissive needed. Resets on reboot — re-run after each boot."
        UnlockStepKind.ROOT ->
            "Magisk / KernelSU — full, vendor-independent live writes that survive reboot."
    }
    val (pillText, pillAccent) = when (step.state) {
        UnlockStepState.DONE -> "DONE" to AccentBar.Emerald
        UnlockStepState.DONE_BUT_INSUFFICIENT -> "GRANTED — KERNEL STILL BLOCKS" to AccentBar.Amber
        UnlockStepState.AVAILABLE -> "AVAILABLE" to AccentBar.Blue
        UnlockStepState.AVAILABLE_NEEDS_EXTERNAL_RUNNER -> "NEEDS A ROOT-SCRIPT RUNNER" to AccentBar.Amber
        UnlockStepState.AVAILABLE_NEEDS_INSTALL -> "NEEDS MAGISK / KERNELSU" to AccentBar.Amber
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Text(
            "$stepNumber.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                StatusPill(text = pillText, accent = pillAccent)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
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
    // ── Charging auto-profile ─────────────────────────────────────────────────
    chargingProfileEnabled: Boolean,
    chargingBundle: ChargingBundle,
    onChargingProfileToggle: (Boolean) -> Unit,
    onChargingGoalSelected: (GoalProfile) -> Unit,
    onChargingFanModeSelected: (Int?) -> Unit,
    onChargingRefreshRateSelected: (Float?) -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Blue, title = "Companion features") {
        // ── Idle/charge downclock toggle (existing) ───────────────────────────
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

        // ── Charging auto-profile ─────────────────────────────────────────────
        // Master toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "CHARGING PROFILE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.06.sp,
                )
                Text(
                    "When plugged in and not gaming: applies the cool/quiet bundle below " +
                        "(fan + refresh rate + AutoTDP goal). Reverts all axes on unplug.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            }
            Spacer(Modifier.width(Spacing.group))
            Box(
                modifier = Modifier
                    .background(
                        if (chargingProfileEnabled) AccentBar.Emerald.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                        RoundedCornerShape(4.dp),
                    )
                    .border(
                        1.dp,
                        if (chargingProfileEnabled) AccentBar.Emerald else Color.White.copy(alpha = 0.15f),
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { onChargingProfileToggle(!chargingProfileEnabled) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (chargingProfileEnabled) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (chargingProfileEnabled) AccentBar.Emerald else Color(0xFF888888),
                    letterSpacing = 0.06.sp,
                )
            }
        }

        // Bundle config — shown collapsed when toggle is OFF so the screen stays clean
        if (chargingProfileEnabled) {
            Spacer(Modifier.height(Spacing.group))

            // AutoTDP goal picker (COOL_QUIET / EFFICIENCY / BALANCED_SMART)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "AUTOTDP GOAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF777777),
                    letterSpacing = 0.05.sp,
                )
                val chargingGoals = listOf<Pair<GoalProfile, String>>(
                    GoalProfile.COOL_QUIET to "Cool/Quiet",
                    GoalProfile.BATTERY_SAVER to "Battery Saver",
                    GoalProfile.BALANCED_SMART to "Balanced",
                    GoalProfile.MAX_FPS to "Max FPS",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chargingGoals.forEach { entry ->
                        val goal = entry.first
                        val label = entry.second
                        val selected = chargingBundle.autoTdpGoal == goal
                        Box(
                            modifier = Modifier
                                .background(
                                    if (selected) AccentBar.Blue.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                                    RoundedCornerShape(4.dp),
                                )
                                .border(
                                    1.dp,
                                    if (selected) AccentBar.Blue else Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { onChargingGoalSelected(goal) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) AccentBar.Blue else Color(0xFF888888),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.group))

            // Fan mode picker
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "FAN MODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF777777),
                    letterSpacing = 0.05.sp,
                )
                val fanOptions = listOf<Pair<Int?, String>>(
                    GoalProfile.FanPresets.QUIET to "Quiet",
                    GoalProfile.FanPresets.SMART to "Smart",
                    GoalProfile.FanPresets.SPORT to "Sport",
                    null to "Off (don't touch)",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fanOptions.forEach { entry ->
                        val mode = entry.first
                        val label = entry.second
                        val selected = chargingBundle.fanMode == mode
                        Box(
                            modifier = Modifier
                                .background(
                                    if (selected) AccentBar.Blue.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                                    RoundedCornerShape(4.dp),
                                )
                                .border(
                                    1.dp,
                                    if (selected) AccentBar.Blue else Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { onChargingFanModeSelected(mode) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) AccentBar.Blue else Color(0xFF888888),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.group))

            // Refresh rate picker
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "DISPLAY REFRESH RATE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF777777),
                    letterSpacing = 0.05.sp,
                )
                val rateOptions = listOf<Pair<Float?, String>>(
                    60f to "60 Hz",
                    90f to "90 Hz",
                    120f to "120 Hz",
                    null to "Off (don't touch)",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rateOptions.forEach { entry ->
                        val hz = entry.first
                        val label = entry.second
                        val selected = chargingBundle.refreshRateHz == hz
                        Box(
                            modifier = Modifier
                                .background(
                                    if (selected) AccentBar.Blue.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                                    RoundedCornerShape(4.dp),
                                )
                                .border(
                                    1.dp,
                                    if (selected) AccentBar.Blue else Color.White.copy(alpha = 0.12f),
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { onChargingRefreshRateSelected(hz) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) AccentBar.Blue else Color(0xFF888888),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.group))
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(Spacing.group))

        // ── Per-app map (existing) ────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────────────
//  Unit 4: Mode segmented control [ Adaptive | Goal Modes | Manual ]
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Three-tab mode selector sitting above the control panel area (Unit 4).
 *
 * Adaptive is highlighted by default (it is the new recommended entry point).
 * Goal Modes preserves the existing GoalPickerPanel unchanged.
 * Manual is a placeholder for future direct-override controls.
 *
 * Uses the existing Arsenal chip pattern (accent border + tinted background
 * when selected, neutral border when idle) — no new design primitives.
 */
@Composable
private fun AutoTdpModeSegmentedControl(
    selected: AutoTdpScreenMode,
    onSelect: (AutoTdpScreenMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AutoTdpScreenMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val accent = AccentBar.Emerald
            val bg     = if (isSelected) accent.copy(alpha = 0.14f) else Color(0xFF0C0C10)
            val border = if (isSelected) 1.dp else 0.5.dp
            val borderColor = if (isSelected) accent else Color(0xFF6B7280).copy(alpha = 0.4f)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(bg)
                    .border(border, borderColor, RoundedCornerShape(4.dp))
                    .clickable { onSelect(mode) }
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (mode) {
                        AutoTdpScreenMode.ADAPTIVE   -> "Adaptive"
                        AutoTdpScreenMode.GOAL_MODES -> "Goal Modes"
                        AutoTdpScreenMode.MANUAL     -> "Manual"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) accent else Color(0xFF9999AA),
                    letterSpacing = 0.02.sp,
                )
            }
        }
    }
}
