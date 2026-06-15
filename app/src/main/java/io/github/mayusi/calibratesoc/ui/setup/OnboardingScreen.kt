package io.github.mayusi.calibratesoc.ui.setup

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.vendor.OdinIntents
import io.github.mayusi.calibratesoc.ui.tune.AdvancedUnlockViewModel
import kotlinx.coroutines.delay

/**
 * First-launch wizard. Walks the user through the three universal
 * permissions (overlay, usage access, battery opt). Advanced unlock
 * (Force SELinux + unlock script) lives in Tune → Advanced unlock and
 * is surfaced as an optional next-step at the end of this wizard.
 *
 * On applicable devices (AYN/Odin/Retroid/AYANEO — any device with a
 * vendor runner or PServer), the advanced steps are FORCED: the user
 * must either complete them or confirm a full-screen scary warning that
 * the app will be read-only. On non-applicable devices the steps remain
 * optional (they cannot complete them — no vendor runner present).
 */
/**
 * Terminal state machine that runs AFTER the 3 universal permission
 * steps. The whole flow is optional — every phase has a skip/enter-app
 * escape.
 *   None         → still in the universal steps / all-done decision
 *   Ask          → "do you want advanced unlock?" explainer
 *   SelinuxGuide → Step 1 of 2: turn ON Force SELinux (breadcrumb guide)
 *   ScriptGuide  → Step 2 of 2: generate + run the unlock script
 *   Done         → celebrate; live grants checklist; enter app
 */
private enum class AdvPhase { None, Ask, SelinuxGuide, ScriptGuide, Done }

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    advancedUnlockVm: AdvancedUnlockViewModel = hiltViewModel(),
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Resolved once; cheap (package-manager check).
    val advancedApplicable = remember { ForceSelinuxSetupItem.isApplicable(context) }

    // Terminal advanced sub-wizard phase. Starts at None — only enters
    // the advanced flow once the user opts in from the all-done step.
    var advancedPhase by remember { mutableStateOf(AdvPhase.None) }
    val grants by advancedUnlockVm.grants.collectAsState()

    // Full-screen scary-skip dialog: shown when the user tries to bypass
    // the forced advanced step on an applicable device.
    var showScarySkipDialog by remember { mutableStateOf(false) }

    // Last action timestamp — bumped every time the user taps a "Grant"
    // button. For the first 8 seconds after they tap, we poll at 250 ms
    // (fast enough to update the moment they switch back to us). After
    // that we drop to 1 s to save battery if they're idling on the
    // screen.
    var lastActionAtMs by remember { mutableStateOf(0L) }
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val sinceAction = System.currentTimeMillis() - lastActionAtMs
            val interval = if (sinceAction < 8_000L) 250L else 1_000L
            delay(interval)
            tick = System.currentTimeMillis()
            // While the user is running the unlock script (or admiring
            // the Done screen), re-poll live grants each tick so the
            // checklist lights up the moment the script lands.
            if (advancedPhase == AdvPhase.ScriptGuide || advancedPhase == AdvPhase.Done) {
                advancedUnlockVm.refresh()
            }
        }
    }

    // Re-probe capability whenever we enter SELinux or Script phases so
    // the isDone() signals are fresh (PServer cache may be stale from
    // cold start before the user ran anything).
    LaunchedEffect(advancedPhase) {
        if (advancedPhase == AdvPhase.SelinuxGuide || advancedPhase == AdvPhase.ScriptGuide) {
            advancedUnlockVm.refresh()
        }
    }

    // The moment we come back from a system grant screen, force one
    // immediate re-poll instead of waiting for the next tick. Without
    // this the ✓ takes up to a second to appear and users think the
    // grant failed.
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                tick = System.currentTimeMillis()
                // Coming back from the vendor settings app — pull fresh
                // grants immediately so the advanced checklist doesn't
                // wait a full tick to reflect a just-run script.
                advancedUnlockVm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val items = AllSetupItems
    // Recompute on every tick. Don't use remember(tick) + derivedStateOf
    // here — derivedStateOf only re-runs when the state it READS inside
    // its lambda changes, and isDone(context) reads no Compose state.
    // The key on remember() doesn't help: it just rebuilds the holder.
    // Plain recompute is fine — three permission probes are cheap.
    @Suppress("UNUSED_EXPRESSION") tick // keep tick in this scope so we recompute when it changes
    val statuses: Map<String, Boolean> = items.associate { it.id to it.isDone(context) }
    var stepIdx by remember { mutableIntStateOf(0) }
    // Steps: 0=welcome, 1..N=items, N+1=all-done/advanced-unlock nudge
    val totalSteps = items.size + 2

    // Auto-advance: when the current step's item flips to done, move
    // to the next one after a short pulse (so the user sees the ✓ for
    // a beat). Doesn't fire for welcome or all-done steps.
    val currentItemId = (stepIdx - 1).takeIf { it in items.indices }?.let { items[it].id }
    val currentDone = currentItemId?.let { statuses[it] == true } == true
    // Item steps run 1..items.size; the all-done step is items.size+1
    LaunchedEffect(currentItemId, currentDone) {
        if (currentItemId != null && currentDone && stepIdx in 1..items.size) {
            delay(700)
            // Re-check — user may have hit Back, or status flickered.
            if (stepIdx in 1..items.size && items[stepIdx - 1].isDone(context)) {
                stepIdx++
            }
        }
    }

    // Advanced live-success detection: celebrate once a SCRIPT-ONLY grant
    // lands. We deliberately exclude usageStats from the trigger — the user
    // already granted Usage access back in universal step 2, so keying on
    // anyHeld would instantly skip the script guide to Done before they run
    // anything. DUMP / WRITE_SECURE_SETTINGS / direct-sysfs are only ever
    // granted by the unlock script, so they're the true "it ran" signal.
    val scriptUnlocked = grants.dump || grants.writeSecureSettings || grants.sysfsWritable
    LaunchedEffect(advancedPhase, scriptUnlocked) {
        if (advancedPhase == AdvPhase.ScriptGuide && scriptUnlocked) {
            // Advanced setup completed — clear the skipped flag so gated-feature
            // re-surface logic knows the user did it properly.
            viewModel.setAdvancedSetupSkipped(false)
            advancedPhase = AdvPhase.Done
        }
    }

    // ── Scary-skip confirmation dialog ────────────────────────────────────────
    // Only shown when the user tries to leave a forced advanced step on an
    // applicable device without completing it. Covers both the SELinux and
    // Script phases (and the AllDone "Skip" path on applicable devices).
    if (showScarySkipDialog) {
        ScarySkipDialog(
            onGoBack = { showScarySkipDialog = false },
            onSkipAnyway = {
                showScarySkipDialog = false
                // Record the skip so gated features can re-surface the prompt.
                viewModel.setAdvancedSetupSkipped(true)
                viewModel.markComplete()
                onFinished()
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OnboardingHeader(stepIdx, totalSteps, items.size)

        val allDoneStepIdx = items.size + 1 // last step index

        // enterApp used ONLY for non-applicable devices and genuine
        // completion paths — never for the silent TextButton skip on
        // applicable devices.
        val enterApp: () -> Unit = {
            viewModel.markComplete()
            onFinished()
        }

        // On applicable devices, the "skip advanced" exit path must go
        // through ScarySkipDialog instead of silently entering the app.
        val requestSkip: () -> Unit = if (advancedApplicable) {
            { showScarySkipDialog = true }
        } else {
            enterApp
        }

        // Once the user is in the advanced sub-wizard, it owns the body
        // and the universal-step dispatch is bypassed entirely.
        if (advancedPhase != AdvPhase.None) {
            val vendorName = OdinIntents.vendorSettingsName(context)
            when (advancedPhase) {
                AdvPhase.Ask -> AdvancedAskStep(
                    vendorName = vendorName,
                    advancedApplicable = advancedApplicable,
                    onYes = { advancedPhase = AdvPhase.SelinuxGuide },
                    onNo = requestSkip,
                )
                AdvPhase.SelinuxGuide -> AdvancedSelinuxStep(
                    vendorName = vendorName,
                    advancedApplicable = advancedApplicable,
                    selinuxDone = ForceSelinuxSetupItem.isDone(context),
                    onOpenVendor = {
                        lastActionAtMs = System.currentTimeMillis()
                        ForceSelinuxSetupItem.launch(context)
                    },
                    onConfirmed = {
                        ForceSelinuxSetupItem.setManuallyConfirmed(context, true)
                        advancedPhase = AdvPhase.ScriptGuide
                    },
                    onSkip = {
                        // "Skip this step" on an applicable device → scary dialog.
                        // On non-applicable: advance freely (can't do it anyway).
                        if (advancedApplicable) {
                            showScarySkipDialog = true
                        } else {
                            advancedPhase = AdvPhase.ScriptGuide
                        }
                    },
                    onEnterApp = requestSkip,
                )
                AdvPhase.ScriptGuide -> AdvancedScriptStep(
                    vendorName = vendorName,
                    advancedApplicable = advancedApplicable,
                    grants = grants,
                    onGenerate = { advancedUnlockVm.deployScript() },
                    onOpenVendor = {
                        lastActionAtMs = System.currentTimeMillis()
                        OdinIntents.openVendorSettings(context)
                    },
                    onShowFile = { path -> OdinIntents.openScriptDirectory(context, path) },
                    onEnterApp = requestSkip,
                )
                AdvPhase.Done -> AdvancedDoneStep(
                    grants = grants,
                    onEnterApp = enterApp,
                )
                AdvPhase.None -> Unit
            }
            return@Column
        }
        when (stepIdx) {
            0 -> WelcomeStep(
                onNext = { stepIdx = 1 },
                onSkipAll = enterApp,
            )
            allDoneStepIdx -> AllDoneStep(
                advancedApplicable = advancedApplicable,
                onSetupAdvanced = { advancedPhase = AdvPhase.Ask },
                onEnterApp = enterApp,
                onBack = { stepIdx-- },
                onSkipAdvanced = requestSkip,
            )
            else -> {
                val itemIdx = stepIdx - 1
                val item = items[itemIdx]
                StepCard(
                    item = item,
                    done = statuses[item.id] ?: false,
                    onAction = {
                        // Bumping this triggers the 8-second 250ms
                        // fast-poll window so the moment the user
                        // returns from the system screen the ✓
                        // appears within a frame.
                        lastActionAtMs = System.currentTimeMillis()
                        item.launch(context)
                    },
                    onNext = {
                        stepIdx++
                    },
                    onBack = if (stepIdx > 1) ({ stepIdx-- }) else null,
                )
            }
        }

        // Always-visible escape hatch on permission steps only.
        // Not shown on welcome (has its own Skip) or all-done (has Finish).
        val allDoneStep = items.size + 1
        if (stepIdx in 1 until allDoneStep) {
            TextButton(
                onClick = {
                    viewModel.markComplete()
                    onFinished()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Skip setup — let me into the app")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Scary skip dialog — full-screen warning for applicable devices
// ─────────────────────────────────────────────────────────────────────

/**
 * Full-screen warning dialog shown when the user tries to skip the
 * advanced unlock steps on an applicable (AYN/vendor-runner) device.
 *
 * Without the setup, ALL performance features — AutoTDP, live tuning,
 * the in-game HUD ± buttons — are unavailable and the app is
 * effectively read-only. This dialog makes the cost crystal-clear
 * before letting the user proceed.
 *
 * Primary action: "Go back and set it up" (recommended / non-destructive).
 * Secondary action: "Skip — read-only" (destructive — clearly labelled).
 */
@Composable
private fun ScarySkipDialog(
    onGoBack: () -> Unit,
    onSkipAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onGoBack,
        icon = {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp),
            )
        },
        title = {
            Text(
                "Skip advanced setup?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Without this, you will NOT be able to use:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("• AutoTDP (automatic power budgeting)", style = MaterialTheme.typography.bodyMedium)
                    Text("• Live tuning (HUD ± clock buttons)", style = MaterialTheme.typography.bodyMedium)
                    Text("• In-game HUD controls", style = MaterialTheme.typography.bodyMedium)
                    Text("• Any performance feature", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "The app will be read-only — monitoring and benchmarks still work, " +
                        "but you cannot change anything.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        "You can always complete this setup later from Settings → Advanced unlock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onGoBack) {
                Text("Go back and set it up")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSkipAnyway,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Skip — read-only")
            }
        },
    )
}

@Composable
private fun OnboardingHeader(stepIdx: Int, total: Int, itemCount: Int) {
    // Per-step friendly subtitle. stepIdx 0 = welcome, 1..itemCount = the
    // permission steps, itemCount+1 = all-done.
    val subtitle = when (stepIdx) {
        0 -> "A couple of quick permissions and you're ready to play."
        itemCount + 1 -> "All set — welcome aboard."
        else -> "Step ${stepIdx} of $itemCount — tap, allow, and we'll move on automatically."
    }
    Column {
        Text(
            "Quick setup",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (stepIdx + 1).toFloat() / total.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // One dot per real step (welcome → each permission → done).
            for (i in 0 until total) {
                StepDot(
                    completed = i < stepIdx,
                    current = i == stepIdx,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Step ${stepIdx + 1} of $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A single progress dot in the header row. Completed steps are a small
 * filled tertiary dot, upcoming steps are an outlined hollow dot, and the
 * current step is a slightly larger filled primary dot.
 */
@Composable
private fun StepDot(completed: Boolean, current: Boolean) {
    when {
        current -> Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        completed -> Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary),
        )
        else -> Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit, onSkipAll: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Let's get you set up — takes 30 seconds",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Universal handheld tuner. Real-time monitor, benchmark, " +
                    "CPU/GPU presets, in-game HUD, hardware inspector.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Best on: AYN Thor · Odin 3 · Odin 2, Retroid Pocket 6 · 5, " +
                    "and high-end Android phones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "The next 3 steps grant the permissions the monitoring and HUD need. " +
                    "Each one auto-advances once granted. " +
                    "You can revisit this from Settings any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSkipAll,
                    modifier = Modifier.weight(1f),
                ) { Text("Skip setup") }
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                ) { Text("Get started") }
            }
        }
    }
}

/**
 * Final universal step — all 3 permissions done. Branches the terminal
 * flow: if advanced unlock is achievable on this device, offer a clear
 * choice between setting it up now or confirming the scary skip dialog;
 * otherwise just let the user in.
 *
 * On applicable devices, [onSkipAdvanced] routes through ScarySkipDialog.
 * On non-applicable devices, [onSkipAdvanced] == [onEnterApp] (same lambda).
 */
@Composable
private fun AllDoneStep(
    advancedApplicable: Boolean,
    onSetupAdvanced: () -> Unit,
    onEnterApp: () -> Unit,
    onBack: () -> Unit,
    onSkipAdvanced: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "You're set up!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Monitoring, HUD, and benchmarks are ready. The app works on any Android device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (advancedApplicable) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "One more thing — live in-app tuning (required for this device)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Your device supports instant ± CPU/GPU clock changes straight from the HUD, " +
                                "AutoTDP, and vendor preset tuning. This one-time ~2-minute setup is needed " +
                                "to use those features.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onSetupAdvanced,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Set up advanced tuning") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    // On applicable devices this routes through ScarySkipDialog
                    // so the user sees the consequences before skipping.
                    OutlinedButton(
                        onClick = onSkipAdvanced,
                        modifier = Modifier.weight(1f),
                    ) { Text("Skip — enter app") }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Button(
                        onClick = onEnterApp,
                        modifier = Modifier.weight(1f),
                    ) { Text("Enter app") }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Advanced unlock sub-wizard (optional for non-applicable devices,
// forced gate for applicable devices). All steps have a proper exit
// path: either genuine completion or confirmed ScarySkipDialog.
// ─────────────────────────────────────────────────────────────────────

/** Shared card scaffold for the advanced steps — icon badge + title +
 *  scrollable body content. */
@Composable
private fun AdvCardHeader(icon: ImageVector, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A single numbered breadcrumb row with a circular number badge. The
 *  [dimmed] flag greys it out for steps not yet reachable. */
@Composable
private fun NumberedStep(number: Int, text: String, dimmed: Boolean = false) {
    val alpha = if (dimmed) 0.45f else 1f
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f * alpha)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Tinted callout card (used for "why this matters" notes). */
@Composable
private fun AdvNoteCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Tri-state for one grants-checklist row. NA means the capability is
 *  genuinely unavailable on THIS device's kernel (not a failed setup) —
 *  e.g. direct CPU-sysfs writes on a stock Snapdragon handheld whose
 *  scaling_max_freq nodes are kernel-read-only. */
private enum class GrantState { HELD, PENDING, NA }

/** One row of the live grants checklist — ✓ (held), ○ (pending), or a
 *  muted "—" with an N/A tag when the device can't ever provide it. */
@Composable
private fun GrantRow(state: GrantState, label: String, naNote: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val icon = when (state) {
            GrantState.HELD -> Icons.Outlined.CheckCircle
            GrantState.PENDING -> Icons.Outlined.RadioButtonUnchecked
            GrantState.NA -> Icons.Outlined.RemoveCircleOutline
        }
        val tint = when (state) {
            GrantState.HELD -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.outline
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == GrantState.HELD) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == GrantState.NA && naNote != null) {
                Text(
                    naNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GrantsChecklist(grants: AdvancedPermissionsScript.Grants) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GrantRow(if (grants.dump) GrantState.HELD else GrantState.PENDING, "FPS / gfxinfo (DUMP)")
            GrantRow(if (grants.usageStats) GrantState.HELD else GrantState.PENDING, "Foreground app (Usage access)")
            GrantRow(if (grants.writeSecureSettings) GrantState.HELD else GrantState.PENDING, "Vendor preset keys (Secure settings)")
            // Direct sysfs writes: if it's live, great. If it's NOT live but the
            // script clearly ran (DUMP came through), this device's kernel keeps
            // the CPU scaling_max_freq nodes read-only (verified on stock
            // Snapdragon handhelds like the RP6 — chmod 666 is silently rejected
            // even with Force SELinux + root). That's a kernel lock, not a failed
            // setup — show it as N/A, not a sad empty circle, so the user stops
            // chasing Force SELinux.
            val sysfsState = when {
                grants.sysfsWritable -> GrantState.HELD
                grants.dump -> GrantState.NA           // script ran, kernel locked the nodes
                else -> GrantState.PENDING             // script hasn't run yet
            }
            GrantRow(
                sysfsState,
                "Direct sysfs writes (live ± clocks)",
                naNote = "Custom-kernel only on this device — clock tuning still works via the script path.",
            )
        }
    }
}

/**
 * Phase 2 — friendly explainer of what advanced unlock gets/costs.
 *
 * On applicable devices [onNo] routes through ScarySkipDialog (caller
 * passes [requestSkip] which triggers the dialog). On non-applicable
 * devices it goes straight to the app.
 */
@Composable
private fun AdvancedAskStep(
    vendorName: String,
    advancedApplicable: Boolean,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AdvCardHeader(
                Icons.Outlined.Bolt,
                if (advancedApplicable) "Advanced unlock (required for live tuning)" else "Advanced unlock (optional)",
            )
            Text(
                "Turn this on and you get real in-game FPS, vendor preset tuning without Shizuku, " +
                    "and — on devices with a custom kernel — instant ± clock changes right from the HUD. " +
                    "On stock handhelds clock tuning still works through the one-tap script.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AdvNoteCard(
                "The cost: a one-time ~2-minute setup — flip a system toggle in $vendorName and run a " +
                    "one-time script as root. That's it; it persists afterward.",
            )
            if (advancedApplicable) {
                Text(
                    "Your device supports this setup. Without it, AutoTDP, live tuning, and HUD controls " +
                        "are unavailable — the app will be read-only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "It's completely optional. Monitoring, the HUD, and benchmarks already work without it — " +
                        "this just unlocks live tuning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Button(onClick = onYes, modifier = Modifier.fillMaxWidth()) { Text("Yes, set it up") }
            // On applicable devices the label reflects the scary consequence.
            OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth()) {
                Text(if (advancedApplicable) "Skip (read-only mode)" else "No thanks, enter app")
            }
        }
    }
}

/**
 * Phase 3 — Step 1 of 2: turn ON Force SELinux. The app can't deep-link
 * to the exact toggle, so this is a breadcrumb guide + manual confirm.
 *
 * On applicable devices the "Skip this" button is REMOVED — trying to
 * skip goes through [onSkip] which the parent maps to ScarySkipDialog.
 */
@Composable
private fun AdvancedSelinuxStep(
    vendorName: String,
    advancedApplicable: Boolean,
    selinuxDone: Boolean,
    onOpenVendor: () -> Unit,
    onConfirmed: () -> Unit,
    onSkip: () -> Unit,
    onEnterApp: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AdvCardHeader(Icons.Outlined.Security, "Turn on Force SELinux", "Step 1 of 2")

            if (selinuxDone) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Already unlocked",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberedStep(1, "Tap \"Open $vendorName\" below — it opens the settings app.")
                NumberedStep(2, "Scroll to find Force SELinux (it may be under a 'Developer', 'Advanced', or 'System' section depending on your device).")
                NumberedStep(3, "Toggle Force SELinux → ON.")
                NumberedStep(4, "Press back / recent-apps to return to Calibrate SoC.")
            }

            AdvNoteCard(
                "This lets the unlock script's permission changes actually stick. Without it, the script " +
                    "runs but the live-tuning chmods are blocked.",
            )

            Button(onClick = onOpenVendor, modifier = Modifier.fillMaxWidth()) { Text("Open $vendorName") }
            OutlinedButton(onClick = onConfirmed, modifier = Modifier.fillMaxWidth()) {
                Text(if (selinuxDone) "Continue" else "I turned it ON — continue")
            }
            // On applicable devices: no silent "I'll do it later" — show the
            // scary dialog instead so the user understands the consequence.
            // On non-applicable: keep both escape buttons (they can't do this anyway).
            if (advancedApplicable) {
                TextButton(
                    onClick = onSkip, // → ScarySkipDialog on applicable devices
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Skip (read-only mode)") }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip this — I'll do it later") }
                    TextButton(onClick = onEnterApp, modifier = Modifier.weight(1f)) { Text("Enter app") }
                }
            }
        }
    }
}

/**
 * Phase 4 — Step 2 of 2: generate + run the unlock script, with live
 * grant detection.
 *
 * Auto-deploys the script on entry (so the user doesn't have to tap
 * "Generate" manually — the file is ready the moment they see this screen).
 * Also auto-opens the vendor settings runner if applicable.
 */
@Composable
private fun AdvancedScriptStep(
    vendorName: String,
    advancedApplicable: Boolean,
    grants: AdvancedPermissionsScript.Grants,
    onGenerate: () -> AdvancedPermissionsScript.Deployed,
    onOpenVendor: () -> Unit,
    onShowFile: (String) -> Unit,
    onEnterApp: () -> Unit,
) {
    var deployed by remember { mutableStateOf<AdvancedPermissionsScript.Deployed?>(null) }
    val generated = deployed != null

    // Auto-deploy the script the first time this composable is shown.
    // This means the file is ready before the user even reads the instructions.
    LaunchedEffect(Unit) {
        if (deployed == null) {
            deployed = runCatching { onGenerate() }.getOrNull()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AdvCardHeader(Icons.Outlined.Terminal, "Run the unlock script", "Step 2 of 2")

            Button(
                onClick = { deployed = runCatching { onGenerate() }.getOrNull() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (generated) "Re-generate script" else "Generate script") }

            deployed?.let { d ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Saved — look for this file:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "calibratesoc_unlock.sh",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        d.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberedStep(1, "Script was auto-generated — look for calibratesoc_unlock.sh in your CalibrateSoC folder.")
                NumberedStep(2, "Tap \"Open $vendorName\" below.", dimmed = !generated)
                NumberedStep(3, "Find Run script as Root (often under a 'Root', 'Advanced', or 'Tools' menu).", dimmed = !generated)
                NumberedStep(4, "In its file picker, browse to /CalibrateSoC/ and pick calibratesoc_unlock.sh.", dimmed = !generated)
                NumberedStep(5, "Run it. You'll see 'Calibrate SoC unlock complete.' Then come back here.", dimmed = !generated)
            }

            // Live success detection — watch each grant light up.
            Text(
                "Watch these light up as the script runs:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GrantsChecklist(grants)

            Button(
                onClick = onOpenVendor,
                enabled = generated,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open $vendorName") }
            OutlinedButton(
                onClick = { deployed?.let { onShowFile(it.path) } },
                enabled = generated,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show me the script file") }
            // On applicable devices this routes through ScarySkipDialog;
            // on non-applicable it directly enters the app.
            TextButton(
                onClick = onEnterApp,
                modifier = Modifier.fillMaxWidth(),
                colors = if (advancedApplicable) ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ) else ButtonDefaults.textButtonColors(),
            ) { Text(if (advancedApplicable) "Skip (read-only mode)" else "Skip — enter app") }
        }
    }
}

/** Phase 5 — celebrate. Honest if only some grants landed. */
@Composable
private fun AdvancedDoneStep(
    grants: AdvancedPermissionsScript.Grants,
    onEnterApp: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "Advanced unlock complete!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            GrantsChecklist(grants)
            // Three outcomes:
            //  - allHeld: everything, including direct sysfs writes (custom kernel).
            //  - script ran (the three pm-grants landed) but sysfs is kernel-locked
            //    on this device (stock RP6/Snapdragon) — that's expected, NOT a
            //    failure, so DON'T tell them to re-run with Force SELinux (proven
            //    not to help: the cpufreq nodes are read-only at the kernel level).
            //  - something's genuinely still pending (script not fully run yet) —
            //    only then is the re-run hint useful.
            val scriptGrantsLanded = grants.dump && grants.usageStats && grants.writeSecureSettings
            when {
                grants.allHeld -> Text(
                    "You can now do instant ± clock changes directly from the HUD. Everything's unlocked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                scriptGrantsLanded -> Text(
                    "You're unlocked. Real in-game FPS and vendor preset tuning are on. " +
                        "Instant in-app ± clocks need a custom kernel on this device — but clock " +
                        "tuning still works through the one-tap script path. Nothing more to do.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "Some items didn't come through — make sure you ran the script via " +
                        "Run script as Root, then come back. You can still enter the app now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onEnterApp, modifier = Modifier.fillMaxWidth()) { Text("Enter app") }
        }
    }
}

@Composable
private fun StepCard(
    item: SetupItem,
    done: Boolean,
    onAction: () -> Unit,
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
) {
    // Whether the user has tapped "Open" / "Generate" at least once
    // for this step. Drives the post-action "tap back when you're
    // done" hint — Android refuses to return to us automatically from
    // most system permission screens, so the user has to do it.
    var actionTaken by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Friendly per-step icon. Maps the SetupItem id → a Material
            // glyph without touching the SetupItem interface.
            val stepIcon = when (item.id) {
                "overlay" -> Icons.Outlined.Layers
                "usage_stats" -> Icons.Outlined.Insights
                "battery_opt" -> Icons.Outlined.BatteryChargingFull
                else -> Icons.Outlined.Settings
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        stepIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusDot(done = done)
            }

            // Split the rationale into a "why it matters" body and a
            // crisp "what to tap" line (the trailing "Tap …" sentence),
            // rendered as a distinct callout below.
            val (why, tapHint) = splitRationale(item.rationale)
            Text(
                why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tapHint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.TouchApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            "What to do:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            tapHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (done) {
                // Celebratory "done" pill.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Done — granted!",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            } else if (actionTaken) {
                // Honest version: Android doesn't reliably return us
                // to the wizard after a permission grant on every
                // firmware. Style it as a gentle highlighted note.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "After enabling it in the system screen, come back to " +
                                "this app (recent apps or the launcher icon) — the " +
                                "✓ will appear and we'll move to the next step.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // Unlock-script step — show the deployed path so the user
            // knows exactly which file to pick in Odin's runner. Only
            // shown after the user has tapped Generate at least once.
            val deployedPath = (item as? UnlockScriptSetupItem)?.lastDeployedPath
            if (deployedPath != null && actionTaken && !done) {
                Text(
                    "Script written:\n$deployedPath",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }

            // Force SELinux step — we can't reliably auto-detect it on
            // most firmwares, so once the user has gone to Odin Settings
            // we offer a manual "I've turned it on" override so the
            // wizard isn't a dead end.
            val context = LocalContext.current
            if (item.id == "force_selinux" && actionTaken && !done) {
                Text(
                    "Can't auto-detect this one — it's an Odin firmware toggle " +
                        "we have no permission to read. If you flipped it ON in " +
                        "Odin Settings, confirm below.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        ForceSelinuxSetupItem.setManuallyConfirmed(context, true)
                        onNext()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("I've enabled Force SELinux — continue") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
                if (!done) {
                    Button(
                        onClick = {
                            actionTaken = true
                            onAction()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        // Action-specific label per step so it's clear
                        // what tapping does. Unlock-script writes a file
                        // rather than opening settings — kept special.
                        Text(
                            when (item.id) {
                                "unlock_script" -> "Generate script"
                                "overlay" -> "Open & allow overlay"
                                "usage_stats" -> "Open & allow usage access"
                                "battery_opt" -> "Allow background running"
                                else -> "Open system settings"
                            },
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Button(onClick = onNext) {
                    Text("Next")
                }
            }
        }
    }
}

/**
 * Split a SetupItem rationale into ("why it matters", "what to tap").
 * The "what to tap" is the trailing sentence that begins with "Tap"; if
 * there's no such sentence the whole text is the "why" and the tap hint
 * is null.
 */
private fun splitRationale(rationale: String): Pair<String, String?> {
    val trimmed = rationale.trim()
    // Find the last sentence that starts with "Tap".
    val tapIdx = trimmed.indexOf("Tap ")
    if (tapIdx <= 0) return trimmed to null
    val why = trimmed.substring(0, tapIdx).trim().trimEnd('.', ' ')
    val tap = trimmed.substring(tapIdx).trim()
    return (if (why.isEmpty()) trimmed else why + ".") to tap
}

@Composable
private fun StatusDot(done: Boolean) {
    val color = if (done) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
