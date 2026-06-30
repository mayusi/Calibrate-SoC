package io.github.mayusi.calibratesoc.ui.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
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
import androidx.compose.ui.draw.scale
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
 * (the unlock SCRIPT — pm grants + PServer whitelist) lives in
 * Tune → Advanced unlock and is surfaced as an optional next-step at
 * the end of this wizard.
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
 *   None        → still in the universal steps / all-done decision
 *   Ask         → "do you want advanced unlock?" explainer
 *   AutoSetup   → the ONE-TRUST path on PServer-live devices: a single
 *                 trust prompt that self-grants DUMP / usage-stats /
 *                 secure-settings via the device's root bridge — no
 *                 script, no file picker. Honest fallback to ScriptGuide
 *                 if PServer turns out not to be transactable.
 *   ScriptGuide → the MAIN advanced step (FALLBACK for non-PServer
 *                 devices): generate + run the unlock script (pm grants).
 *   Done        → celebrate; live grants checklist; enter app
 */
private enum class AdvPhase {
    None, Ask, AutoSetup, ScriptGuide, Done,
    // ── Universal device paths (each routes a device to ITS real grant flow) ──
    /** Root present but not enabled: offer the root-mode opt-in so the device
     *  reaches the ROOT tier instead of falling to NONE. */
    RootEnable,
    /** Shizuku installed (no PServer/root): surface the EXISTING ShizukuOnboarding
     *  state machine (install → pair → grant → per-node probe). */
    ShizukuSetup,
    /** Plain phone/tablet (no privilege path): the honest terminal — universal
     *  HUD/monitoring perms + "live tuning needs a privilege path" note. */
    PlainTerminal,
}

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    advancedUnlockVm: AdvancedUnlockViewModel = hiltViewModel(),
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Resolved once; cheap (package-manager check).
    val advancedApplicable = remember { UnlockScriptSetupItem.isApplicable(context) }

    // Terminal advanced sub-wizard phase. Starts at None — only enters
    // the advanced flow once the user opts in from the all-done step.
    var advancedPhase by remember { mutableStateOf(AdvPhase.None) }
    val grants by advancedUnlockVm.grants.collectAsState()
    val capability by advancedUnlockVm.capability.collectAsState()
    // FULL one-click setup state (setupEverything → grant the 3 pm-perms PLUS the
    // four special-access toggles in one shot). Drives the live checklist on the
    // PServer-live happy path. Honest by construction — only ever reflects the
    // engine's post-grant per-item readback.
    val fullSetupState by advancedUnlockVm.fullSetupState.collectAsState()

    val cap = capability

    // On PServer-live / root / AYANEO-binder devices (the common Odin + RP6 case)
    // live tuning is ALREADY active with zero setup. The unlock script and the whole
    // "advanced unlock" chore are UNNECESSARY for tuning — the app auto-detected the
    // device and set itself up. We surface that as a clean "all set, nothing to do"
    // success state and offer the HUD/FPS-permissions script only as an OPTIONAL
    // enhancement. Conservative on an unsettled probe (cap == null → not yet "live").
    val liveAlreadyActive = cap != null && (
        grants.sysfsWritable ||
        cap.pserverSysfsLive ||
        cap.ayaneoBinderLive ||
        cap.privilege == io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.ROOT
    )

    // ── UNIVERSAL routing signals (restores per-device grant paths) ─────────────
    // Each of these is a REAL, already-built grant path; the universal router
    // sends the device to the one that matches what the probe found instead of
    // dumping every non-PServer device into the old manual ladder.
    //   rootAvailable: a root binary (Magisk/KernelSU/other) is present. The
    //     device CAN reach the ROOT tier — but only if the user opts in (root is
    //     opt-in by design). Detected via rootKind, independent of the opt-in.
    //   rootEnabled:   root present AND the user opted into root mode → the probe
    //     classified the device as the ROOT tier (a live one-tap-capable path).
    //   shizukuInstalled: the Shizuku app is on the device (running or not) — the
    //     universal, vendor-agnostic privilege path.
    val rootAvailable = cap != null &&
        cap.rootKind != io.github.mayusi.calibratesoc.data.capability.RootKind.NONE
    val rootEnabled = cap != null &&
        cap.privilege == io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.ROOT
    val shizukuInstalled = cap?.shizuku?.installed == true

    // ── First-run route: PServer-live → one-click hero, not the old ladder ──
    //
    // THE FIX. The wizard used to ALWAYS fall into the per-permission stepIdx
    // ladder on first run (advancedPhase starts at None), so a PServer-live
    // device — confirmed live by the isTransactable() probe — was walked through
    // the OLD manual overlay/usage/battery steps. The one-click "Set up
    // everything" hero (AdvPhase.AutoSetup) was only reachable from the END of
    // that ladder. Nothing consumed the reactive pserverSysfsLive signal to skip
    // straight to it.
    //
    // [decideOnboardingRoute] is the single pure source of truth for that
    // decision (unit-tested in OnboardingRouteTest). It reacts to the probe
    // landing: cap is null on the first frame → Checking transient (NOT the
    // ladder); when the probe resolves pserverSysfsLive true → AutoSetupHero.
    //
    // [userTookManualControl] makes the decision sticky to user intent: once
    // they enter an advanced sub-phase by hand OR advance the manual ladder on
    // a non-live device, we never yank them back onto the auto route.
    var userTookManualControl by remember { mutableStateOf(false) }
    val onboardingRoute = decideOnboardingRoute(
        capResolved = cap != null,
        pserverSysfsLive = cap?.pserverSysfsLive == true,
        userTookManualControl = userTookManualControl,
    )

    // ── UNIVERSAL route: each device to ITS correct grant path ──────────────────
    // [decideUniversalRoute] is the pure source of truth (unit-tested in
    // OnboardingUniversalRouteTest). It reacts to the probe landing exactly like
    // the legacy route: cap null → Checking; then PServer/AYANEO → AutoSetupHero,
    // enabled-root → AutoSetupHero, root-not-enabled → RootEnable, Shizuku →
    // ShizukuSetup, vendor-script → ScriptLadder, plain device → PlainTerminal.
    val universalRoute = decideUniversalRoute(
        UniversalSignals(
            capResolved = cap != null,
            userTookManualControl = userTookManualControl,
            pserverSysfsLive = cap?.pserverSysfsLive == true,
            ayaneoBinderLive = cap?.ayaneoBinderLive == true,
            rootAvailable = rootAvailable,
            rootEnabled = rootEnabled,
            shizukuInstalled = shizukuInstalled,
            advancedApplicable = advancedApplicable,
        )
    )

    // FIX 1 (the soft-trap fix) — the "a privilege/permission path actually
    // landed" signal, hoisted ABOVE the gate so grantHeld can include it. The
    // unlock SCRIPT only ever sets dump / writeSecureSettings / direct-sysfs
    // (the universal usage-stats perm is excluded — the user already granted it
    // in step 2, so it isn't evidence the script ran). Without folding this into
    // grantHeld, a non-PServer device that successfully ran the script auto-
    // advanced to AdvPhase.Done but canComplete stayed false forever → "Enter
    // app" silently no-op'd → the device was TRAPPED. Now a landed script (or a
    // Shizuku grant, or enabled root) satisfies the gate.
    val scriptUnlocked = grants.dump || grants.writeSecureSettings || grants.sysfsWritable
    val shizukuGranted = cap?.shizuku?.permissionGranted == true
    // privilegePathGranted: a NON-PServer privilege path actually landed.
    val privilegePathGranted = scriptUnlocked || shizukuGranted || rootEnabled

    // ── ROOT-OPTIONAL completion gate (never hard-locks ANY device) ─────────────
    // Calibrate is ROOT-OPTIONAL: the app FULLY WORKS on every device with NO grant
    // (HUD overlay, FPS, monitoring, benchmarks, profiles, insights all run on the
    // NONE tier). Root / PServer / Shizuku / script only unlock the EXTRA live
    // CPU/GPU tuning — "better with", never "required". So onboarding NEVER walls a
    // device — including a PServer handheld. Once the probe RESOLVES, every device
    // can ALWAYS enter; the grant is the strongly-OFFERED primary action per route
    // (big inviting CTA) but is always skippable and stays re-reachable later from
    // Settings / Tune. The only non-completable state is the momentary Checking
    // (probe in flight) — a transient placeholder, not a lock.
    //
    // These signals no longer GATE entry; they drive UI EMPHASIS (whether the
    // one-tap already landed so the hero collapses to a quiet "all set", whether
    // the bridge was unavailable, whether a non-PServer path granted). FIX 1 is
    // subsumed: a script/Shizuku/root device — like every device — always completes
    // once resolved, so the old soft-trap is impossible.
    val oneTapGrantHeld = liveAlreadyActive ||
        fullSetupState is AdvancedUnlockViewModel.FullSetupState.FullCompleted
    val bridgeUnavailable =
        fullSetupState is AdvancedUnlockViewModel.FullSetupState.NotAvailable
    val canComplete = canCompleteUniversal(
        route = universalRoute,
        oneTapGrantHeld = oneTapGrantHeld,
        bridgeUnavailable = bridgeUnavailable,
        privilegePathGranted = privilegePathGranted,
        universalPermsSatisfied = true,
    )

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
    // Warm the capability report once on mount so the live-path signals
    // (PServer / AYANEO binder / root) are settled before the user reaches
    // the advanced Done screen. Without this the probe stays null.
    LaunchedEffect(Unit) { advancedUnlockVm.refresh() }
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

    // Re-probe capability whenever we enter the Script phase so the live-path
    // signals are fresh (PServer cache may be stale from cold start before the
    // user ran anything).
    LaunchedEffect(advancedPhase) {
        if (advancedPhase == AdvPhase.ScriptGuide) {
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

    // Reactive auto-route (THE FIX): the moment the probe confirms a one-tap-live
    // device, jump straight to the one-click hero (AdvPhase.AutoSetup),
    // collapsing the old per-permission ladder. Keyed on the universal route so it
    // fires when the live signal flips true a frame after first composition. Only
    // acts from the pristine first-run state (still at welcome, no advanced phase
    // yet) so it never fights a user who has already navigated somewhere on
    // purpose. Covers PServer-live, AYANEO-binder-live, AND enabled-root — all of
    // which decideUniversalRoute maps to AutoSetupHero (a frictionless one-tap
    // path). Non-live routes (RootEnable / ShizukuSetup / ScriptLadder /
    // PlainTerminal) are NOT auto-jumped here: those devices walk the universal
    // perm ladder first (the HUD/monitoring perms matter on every device), then
    // the AllDone step offers their device-specific privilege path.
    LaunchedEffect(universalRoute) {
        if (universalRoute == UniversalRoute.AutoSetupHero &&
            advancedPhase == AdvPhase.None &&
            stepIdx == 0
        ) {
            advancedPhase = AdvPhase.AutoSetup
        }
    }

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
    // (scriptUnlocked is defined once above the gate so grantHeld can include it.)
    LaunchedEffect(advancedPhase, scriptUnlocked) {
        if (advancedPhase == AdvPhase.ScriptGuide && scriptUnlocked) {
            // The unlock script ran — pm grants + (on AYN) the PServer whitelist
            // landed. That's the whole job for PServer/binder/most devices, so
            // clear the skipped flag and celebrate.
            viewModel.setAdvancedSetupSkipped(false)
            advancedPhase = AdvPhase.Done
        }
    }

    // ── Scary-skip confirmation dialog ────────────────────────────────────────
    // Only shown when the user tries to leave the unlock-SCRIPT advanced step
    // (or the AllDone "Skip" path) on an applicable device without completing
    // it. This is about the SCRIPT (which actually unlocks live tuning).
    if (showScarySkipDialog) {
        ScarySkipDialog(
            onGoBack = { showScarySkipDialog = false },
            onSkipAnyway = {
                showScarySkipDialog = false
                // Record the skip so gated features can re-surface the prompt.
                viewModel.setAdvancedSetupSkipped(true)
                // HARD-REQUIRE: still routed through the mandatory-grant gate. On a
                // capable device this is a no-op until the grant is held — the dialog
                // path is not offered there anyway (requestSkip == enterApp on capable
                // devices below), so this only ever completes where skipping is honest.
                if (canComplete) {
                    viewModel.markComplete()
                    onFinished()
                }
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

        // enterApp is the SINGLE chokepoint to finish onboarding. It is gated by
        // [canComplete]: on a PServer-capable device the mandatory grant must be
        // held first (or the bridge must be genuinely unavailable). On a
        // non-capable device, or once the grant is held, it completes normally.
        // The dedicated "Skip / Not now" buttons are also HIDDEN on capable
        // devices below — this guard is defense-in-depth so no path can sneak a
        // user in without the grant while the probe says they CAN grant.
        val enterApp: () -> Unit = {
            if (canComplete) {
                viewModel.markComplete()
                onFinished()
            }
        }

        // Skip-exit routing under the HARD-REQUIRE policy:
        //   • CAPABLE device, grant NOT held  → there is NO skip. requestSkip routes
        //     to the gated [enterApp], which no-ops until the grant lands. The
        //     dedicated skip buttons are HIDDEN on these devices (below), so the
        //     user's only way forward is the one-tap grant.
        //   • CAPABLE device, grant HELD (e.g. live-already-active: tuning is on
        //     with zero setup) → nothing is actually being skipped; enterApp
        //     completes cleanly (canComplete == true).
        //   • NON-capable device → the door is open; enterApp completes.
        // The ScarySkipDialog is retained only for the legacy non-capable warning
        // surface and is never the path that lets a capable user skip the grant.
        val requestSkip: () -> Unit = enterApp

        // Once the user is in the advanced sub-wizard, it owns the body
        // and the universal-step dispatch is bypassed entirely.
        if (advancedPhase != AdvPhase.None) {
            val vendorName = OdinIntents.vendorSettingsName(context)
            when (advancedPhase) {
                AdvPhase.Ask -> AdvancedAskStep(
                    vendorName = vendorName,
                    advancedApplicable = advancedApplicable,
                    // When a live path already exists (PServer-root, AYANEO binder, or
                    // root), live tuning is ALREADY active — there is nothing to set up.
                    // Show the positive affirmation + let the user skip the whole advanced
                    // flow instead of walking the script guide.
                    liveAlreadyActive = liveAlreadyActive,
                    pserverSysfsLive = cap?.pserverSysfsLive == true,
                    // ONE-TRUST: on a PServer-live device the app can grant itself
                    // everything via the root bridge — route to the single trust
                    // prompt (AutoSetup), no script. On non-PServer devices fall back
                    // to the unlock SCRIPT.
                    onYes = {
                        advancedPhase = if (cap?.pserverSysfsLive == true) {
                            AdvPhase.AutoSetup
                        } else {
                            AdvPhase.ScriptGuide
                        }
                    },
                    // ONE-TRUST primary on a PServer-live device: self-grant the
                    // optional HUD/FPS/per-app perms via the root bridge in one tap.
                    onAutoSetup = { advancedPhase = AdvPhase.AutoSetup },
                    // "Already live → nothing to do" goes straight to the celebrate/enter
                    // screen (no scary-skip dialog — there is nothing being skipped).
                    onAlreadyLiveContinue = { advancedPhase = AdvPhase.Done },
                    onNo = requestSkip,
                )
                AdvPhase.AutoSetup -> AdvancedFullSetupStep(
                    fullSetupState = fullSetupState,
                    // Honest "already done on re-open" signal off the live readbacks
                    // (NOT what we sent): every SetupItem currently held now.
                    liveTuningActive = liveAlreadyActive,
                    // Set up everything → one PServer-root shot that grants the 3
                    // pm-perms + Usage Access + Overlay + Battery + Notifications.
                    // The engine reports the HONEST per-item readback we render.
                    onSetUp = { advancedUnlockVm.setupEverything() },
                    // Retry → re-fire the same one-click setup (e.g. after a partial).
                    onRetry = { advancedUnlockVm.setupEverything() },
                    // Enter app → straight into the app. The one-click screen IS the
                    // terminal celebration on this path, so we don't bounce through the
                    // old "advanced unlock complete" Done screen. Any ungranted extras
                    // stay re-runnable later from Tune → HUD & FPS permissions.
                    onEnterApp = enterApp,
                    // Honest fallback: if PServer wasn't transactable, offer the script
                    // ladder for whatever's still missing.
                    onUseScript = { advancedPhase = AdvPhase.ScriptGuide },
                    // ROOT-OPTIONAL: the IDLE "Not now" escape is ALWAYS available.
                    // The one-tap "Set up everything" is the big inviting primary CTA
                    // (clearly the recommended action for live tuning) but is never a
                    // wall — a user can skip into the fully-working app and grant later
                    // from Settings / Tune.
                    allowIdleSkip = true,
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
                    onContinue = { advancedPhase = AdvPhase.Done },
                    onEnterApp = requestSkip,
                )
                AdvPhase.Done -> AdvancedDoneStep(
                    grants = grants,
                    onEnterApp = enterApp,
                )
                // ── ROOT route: a Magisk/KernelSU device that hasn't opted in ──
                // Offer the EXISTING root-mode opt-in (UserPrefs.setRootModeEnabled
                // via the VM) so the device reaches the ROOT tier instead of NONE.
                // Once enabled, the capability probe re-classifies it as ROOT and
                // the auto-route promotes it to the one-tap hero on the next frame.
                AdvPhase.RootEnable -> RootEnableStep(
                    rootKind = cap?.rootKind,
                    rootModeEnabled = rootEnabled,
                    onEnableRoot = {
                        viewModel.setRootModeEnabled(true)
                        // Re-probe so privilege flips to ROOT immediately; the
                        // auto-route then moves to AutoSetup on the next frame.
                        advancedUnlockVm.refresh()
                    },
                    // The universal HUD/monitoring perms still matter — let the user
                    // walk them (or, if root is declined, this is their entry).
                    onEnterApp = enterApp,
                )
                // ── SHIZUKU route: surface the EXISTING ShizukuOnboarding flow ──
                AdvPhase.ShizukuSetup -> ShizukuSetupStep(
                    state = viewModel.shizukuState,
                    supportsOnDeviceWirelessPairing =
                        viewModel.shizukuSupportsOnDeviceWirelessPairing,
                    wirelessPairingSteps = viewModel.shizukuWirelessPairingSteps,
                    onGrantPermission = { viewModel.requestShizukuPermission() },
                    onRefresh = { viewModel.refreshShizuku() },
                    onEnterApp = enterApp,
                )
                // ── PLAIN route: the honest universal terminal ──────────────────
                AdvPhase.PlainTerminal -> PlainDeviceTerminalStep(
                    statuses = statuses,
                    onGrant = { item ->
                        lastActionAtMs = System.currentTimeMillis()
                        item.launch(context)
                    },
                    onSeeOptions = { advancedPhase = AdvPhase.Ask },
                    onEnterApp = enterApp,
                )
                AdvPhase.None -> Unit
            }
            return@Column
        }
        // ── Probe-in-flight guard ─────────────────────────────────────────────
        // While the capability probe hasn't landed yet (cap == null) AND the user
        // hasn't already committed to a flow, show a brief "Checking your device…"
        // transient instead of the welcome/ladder. This is what prevents a
        // PServer-live first-run user from being stranded on the OLD manual ladder
        // because of a first-frame race: the instant the probe resolves live, the
        // auto-route LaunchedEffect above flips us to the one-click hero; if it
        // resolves non-live we fall through to the genuine manual ladder below.
        // We only gate the pristine first-run state (welcome) — once the user has
        // moved into the ladder on a confirmed non-live device we never bounce
        // them back to a spinner.
        if (onboardingRoute == OnboardingRoute.Checking && stepIdx == 0 && !userTookManualControl) {
            CheckingDeviceStep()
            return@Column
        }
        when (stepIdx) {
            0 -> WelcomeStep(
                // Advancing off welcome commits to the manual ladder — but only
                // honestly: if the probe still says "live", the auto-route has
                // already taken over and this lambda won't run for a live device.
                // Marking manual control keeps the decision sticky so a late probe
                // can't yank a user who deliberately chose the manual path.
                onNext = {
                    userTookManualControl = true
                    stepIdx = 1
                },
                onSkipAll = enterApp,
                // ROOT-OPTIONAL: "Skip setup" is shown on EVERY device. The app
                // fully works with no grant (HUD / monitoring / benchmarks); the
                // grant is offered prominently later (AllDone / one-tap hero) but is
                // never a wall. No device is hard-locked at the welcome step.
                showSkip = true,
            )
            allDoneStepIdx -> AllDoneStep(
                advancedApplicable = advancedApplicable,
                liveAlreadyActive = liveAlreadyActive,
                pserverSysfsLive = cap?.pserverSysfsLive == true,
                // The device's UNIVERSAL route — drives which privilege path the
                // AllDone step offers (Shizuku / root opt-in / vendor script /
                // plain terminal) so every device sees ITS real path, not just the
                // script for everyone.
                universalRoute = universalRoute,
                // Honest "everything already granted" off the live readbacks — when
                // true (e.g. a re-open after a prior one-click setup) the full-setup
                // hero collapses to a quiet "all set" with no button to re-run.
                fullSetupAllHeld = grants.dump && grants.usageStats &&
                    grants.writeSecureSettings,
                // PServer-live HERO: one tap that grants EVERYTHING (the full
                // setupEverything path) via the live checklist screen — collapses the
                // old separate overlay/usage/battery/script steps into one click.
                onSetupEverything = { advancedPhase = AdvPhase.AutoSetup },
                // Route the privilege offer to the device's REAL path:
                //   Shizuku-installed  → the EXISTING ShizukuOnboarding flow
                //   root-available     → the root-mode opt-in step
                //   vendor-script/else → the Ask explainer → script ladder (unchanged)
                onSetupAdvanced = {
                    advancedPhase = when (universalRoute) {
                        UniversalRoute.ShizukuSetup -> AdvPhase.ShizukuSetup
                        UniversalRoute.RootEnable -> AdvPhase.RootEnable
                        else -> AdvPhase.Ask
                    }
                },
                onEnterApp = enterApp,
                onBack = { stepIdx-- },
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

        // ROOT-OPTIONAL escape hatch on the permission steps — shown on EVERY
        // device. The app fully works with no grant, so the user can always get
        // into it; the privilege grant is offered prominently at the AllDone step
        // (and re-reachable from Settings / Tune), never forced here. Not shown on
        // welcome (has its own Skip) or all-done (has its own Enter / offer).
        val allDoneStep = items.size + 1
        if (stepIdx in 1 until allDoneStep) {
            TextButton(
                onClick = enterApp,
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
                        "You can always complete this setup later from Tune → HUD & FPS permissions.",
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

/**
 * Brief "Checking your device…" transient shown on first run while the
 * capability probe is still in flight (cap == null). Prevents committing a
 * PServer-live user to the OLD manual ladder because of a first-frame race:
 * the instant the probe resolves, the route flips to either the one-click hero
 * (PServer-live) or the genuine manual ladder (non-live). Lightweight by design
 * — the probe lands within a few hundred ms of mount.
 */
@Composable
private fun CheckingDeviceStep() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
            )
            Text(
                "Checking your device…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "One moment — we're detecting how your handheld can tune so we can set " +
                    "everything up the fastest way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WelcomeStep(
    onNext: () -> Unit,
    onSkipAll: () -> Unit,
    // When false (PServer-capable device under the hard-require), the "Skip setup"
    // button is hidden and "Get started" takes the full width — the only way
    // forward is into the mandatory grant.
    showSkip: Boolean = true,
) {
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
                    "CPU/GPU tunes, in-game HUD, hardware inspector.",
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
                if (showSkip) {
                    OutlinedButton(
                        onClick = onSkipAll,
                        modifier = Modifier.weight(1f),
                    ) { Text("Skip setup") }
                }
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                ) { Text("Get started") }
            }
        }
    }
}

/**
 * Final universal step — all 3 permissions done. Branches the terminal flow on
 * what the capability probe found, and OFFERS each device its real grant path as
 * the prominent primary action (ROOT-OPTIONAL — the offer is never a wall):
 *
 *  - [liveAlreadyActive] (PServer-live / root / AYANEO-binder): the app
 *    auto-detected the device and live tuning is already on with ZERO setup —
 *    a clean "all set" state, with the optional one-tap extras / script offered.
 *  - not live, [universalRoute] gives the device's path: ShizukuSetup → the
 *    Shizuku flow; RootEnable → the root opt-in; ScriptLadder → the unlock
 *    script. Each is the strongly-offered primary CTA with an honest skip.
 *  - PlainTerminal: the honest "HUD/monitoring work now; live tuning needs a
 *    privilege path" terminal — never trapped.
 *
 * [onSetupAdvanced] routes the offer to the device's path; the "Skip — enter app"
 * always enters the fully-working app directly (no scary wall).
 */
@Composable
private fun AllDoneStep(
    advancedApplicable: Boolean,
    liveAlreadyActive: Boolean,
    pserverSysfsLive: Boolean,
    universalRoute: UniversalRoute,
    fullSetupAllHeld: Boolean,
    onSetupEverything: () -> Unit,
    onSetupAdvanced: () -> Unit,
    onEnterApp: () -> Unit,
    onBack: () -> Unit,
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

            // ── PServer-live / root / AYANEO-binder → "all set, nothing to do" ──
            // The app detected the device and set itself up. Tuning is LIVE now —
            // custom MHz/GPU/governor Apply instantly, no script, no reboot.
            if (liveAlreadyActive) {
                Text(
                    "You're all set!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
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
                        if (pserverSysfsLive) "PServer live — full root tuning" else "Live tuning active",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text(
                    "We detected your device and set it up automatically — live tuning is active. " +
                        "Custom CPU/GPU clocks, governors, and AutoTDP Apply instantly from inside the " +
                        "app: no script, no reboot, nothing to configure.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (pserverSysfsLive && !fullSetupAllHeld) {
                    // ── PServer-live HERO: ONE TAP grants EVERYTHING ──────────────
                    // Calibrate uses the device's built-in root bridge to grant
                    // itself everything it needs — the in-game FPS / DUMP perms, the
                    // overlay HUD, foreground detection for per-app profiles, reliable
                    // background running, and notifications — in a single tap. No
                    // Settings trips, no script. This collapses the old separate
                    // overlay / usage / battery / script steps into one click.
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Finish in one tap",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Calibrate can grant itself everything else it needs — in-game FPS, the " +
                                    "overlay HUD, per-app profiles, reliable background running, and " +
                                    "notifications — through your device's root bridge. No Settings trips, " +
                                    "no script.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = onSetupEverything,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Set up everything",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(
                        onClick = onEnterApp,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Skip — enter app") }
                } else {
                    // Either everything is already granted (re-open after a prior
                    // one-click setup), or this is a non-PServer live path (root /
                    // AYANEO binder) where the one-tap self-grant mechanism doesn't
                    // apply. Enter the app; the (optional) extras stay reachable from
                    // Tune → HUD & FPS permissions.
                    Button(
                        onClick = onEnterApp,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Enter app") }

                    if (!fullSetupAllHeld) {
                        // Non-PServer live path: keep the optional script as the add-on
                        // route to the FPS / per-app extras (honest — those aren't
                        // self-grantable here without a script).
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Optional: in-game FPS + per-app auto-profiles",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Want a real in-game FPS counter and per-app auto-switching? Grant 3 extra " +
                                        "permissions with a one-tap script. Totally optional — tuning already " +
                                        "works without it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = onSetupAdvanced,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Grant HUD & FPS permissions (optional)") }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                return@Column
            }

            // ── Not live: route the privilege OFFER to the device's REAL path ────
            // Every device sees ITS path here — Shizuku, root opt-in, or the vendor
            // script — instead of the script for everyone. The plain device gets the
            // honest terminal (no privilege path; HUD/monitoring still work).
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

            // Route-specific offer copy + CTA label. Each is the device's genuine
            // path, surfaced honestly.
            val offer: Triple<String, String, String>? = when (universalRoute) {
                UniversalRoute.ShizukuSetup -> Triple(
                    "Unlock live tuning with Shizuku",
                    "Your device has Shizuku. Pair it once (on-device, no PC needed) to unlock " +
                        "live CPU/GPU tuning, AutoTDP, and the HUD ± buttons.",
                    "Set up Shizuku",
                )
                UniversalRoute.RootEnable -> Triple(
                    "Root detected — enable root tuning",
                    "We detected root (Magisk/KernelSU) on your device. Turn on root mode to unlock " +
                        "full live CPU/GPU/governor tuning and AutoTDP.",
                    "Enable root tuning",
                )
                UniversalRoute.ScriptLadder -> Triple(
                    "One more thing — live in-app tuning (this device needs setup)",
                    "Your device supports instant ± CPU/GPU clock changes straight from the HUD, " +
                        "AutoTDP, and vendor tuning. This one-time ~2-minute setup is needed to use " +
                        "those features.",
                    "Set up live tuning",
                )
                // PlainTerminal / AutoSetupHero / Checking → no per-device privilege
                // offer here (plain device gets the honest note below; the others are
                // handled by their own branches/auto-route).
                else -> null
            }

            if (offer != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            offer.first,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            offer.second,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onSetupAdvanced,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(offer.third) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    // ROOT-OPTIONAL: every route's "Skip" enters the fully-working
                    // app directly (HUD / monitoring / benchmarks need no grant). The
                    // grant is the prominent primary CTA above and stays re-reachable
                    // from Settings / Tune — skipping is honest, never a wall. (The
                    // ScarySkipDialog's "read-only" framing is obsolete under this
                    // philosophy, so we no longer route Skip through it.)
                    OutlinedButton(
                        onClick = onEnterApp,
                        modifier = Modifier.weight(1f),
                    ) { Text("Skip — enter app") }
                }
            } else {
                // ── PLAIN device — the honest terminal ──────────────────────────
                // No privilege path exists. The universal perms already gave the HUD
                // + monitoring; be honest that live tuning needs root / Shizuku / a
                // supported handheld, and point the user at the options. NEVER trapped.
                AdvNoteCard(
                    "Live CPU/GPU tuning needs a privilege path — root, Shizuku, or a supported " +
                        "handheld. The HUD overlay, FPS & performance monitoring, benchmarks, " +
                        "profiles, and insights all work on your device right now.",
                )
                Button(
                    onClick = onEnterApp,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enter app") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    // Honest "how to unlock more" — opens the same options explainer.
                    OutlinedButton(
                        onClick = onSetupAdvanced,
                        modifier = Modifier.weight(1f),
                    ) { Text("How to unlock tuning") }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GrantRow(if (grants.dump) GrantState.HELD else GrantState.PENDING, "FPS / gfxinfo (DUMP)")
            GrantRow(if (grants.usageStats) GrantState.HELD else GrantState.PENDING, "Foreground app (Usage access)")
            GrantRow(if (grants.writeSecureSettings) GrantState.HELD else GrantState.PENDING, "Vendor tune keys (Secure settings)")
            // Direct sysfs writes: if it's live, great. If it's NOT live but the
            // script clearly ran (DUMP came through), this device's kernel keeps
            // the CPU scaling_max_freq nodes read-only (verified on stock
            // Snapdragon handhelds like the RP6 — chmod 666 is silently rejected
            // even with root). That's a kernel lock, not a failed setup — show it
            // as N/A, not a sad empty circle.
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
    liveAlreadyActive: Boolean,
    pserverSysfsLive: Boolean,
    onYes: () -> Unit,
    onAutoSetup: () -> Unit,
    onAlreadyLiveContinue: () -> Unit,
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
            // ── "Already unlocked" branch ──────────────────────────────────────
            // A live path (PServer-root, AYANEO/Retroid binder, or root) is already
            // active. Live tuning works zero-setup — celebrate and let the user skip
            // the whole advanced flow.
            if (liveAlreadyActive) {
                AdvCardHeader(
                    Icons.Outlined.Check,
                    "Live tuning active — nothing to do",
                    "Your device tunes live with no setup",
                )
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
                        if (pserverSysfsLive) "PServer root — LIVE" else "Vendor / root path — LIVE",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text(
                    "AutoTDP, live ± clock tuning, and the HUD controls all work right now — no " +
                        "script, no extra setup, no root needed. In one tap Calibrate can grant " +
                        "itself everything else — in-game FPS, the overlay HUD, per-app profiles, " +
                        "reliable background running, and notifications — or you can skip it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pserverSysfsLive) {
                    // ONE-CLICK: PServer root is live, so the app can grant itself
                    // EVERYTHING in a single tap — no script, no file picker.
                    Button(
                        onClick = onAutoSetup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Set up everything",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(onClick = onAlreadyLiveContinue, modifier = Modifier.fillMaxWidth()) {
                        Text("Skip — I'm good")
                    }
                } else {
                    // Non-PServer live path (root / AYANEO binder): the one-tap self-grant
                    // route isn't the mechanism here, so keep the script as the optional add-on.
                    Button(onClick = onAlreadyLiveContinue, modifier = Modifier.fillMaxWidth()) {
                        Text("Great — continue")
                    }
                    OutlinedButton(onClick = onYes, modifier = Modifier.fillMaxWidth()) {
                        Text("Run the script anyway (optional extras)")
                    }
                }
                return@Column
            }

            AdvCardHeader(
                Icons.Outlined.Bolt,
                if (advancedApplicable) "HUD & FPS permissions" else "HUD & FPS permissions (optional)",
            )
            Text(
                "Grant these and you get a real in-game FPS counter, per-app auto-profiles, vendor " +
                    "tuning without Shizuku, and — on devices with a custom kernel — instant ± clock " +
                    "changes right from the HUD. Core clock tuning already works without this on " +
                    "supported handhelds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AdvNoteCard(
                "The cost: a one-time ~2-minute setup — generate a small script and run it once via " +
                    "$vendorName's \"Run script as Root\". That's it; the permissions persist afterward.",
            )
            if (advancedApplicable) {
                Text(
                    "Your device supports this setup. The script grants HUD / FPS / vendor-key " +
                        "permissions; live tuning runs via the vendor root path.",
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
 * The user-facing label for each [AdvancedPermissionsScript.SetupItem] row in the
 * one-click checklist. STABLE ordered list — the order mirrors the engine's
 * [AdvancedPermissionsScript.fullSetupReadback] linkedMap and the brief's copy.
 * Lives next to the UI (not the engine) so wording changes never touch the
 * contract.
 */
private data class SetupRowSpec(
    val item: AdvancedPermissionsScript.SetupItem,
    val label: String,
    val icon: ImageVector,
)

private val SetupItemLabels: List<SetupRowSpec> = listOf(
    SetupRowSpec(
        AdvancedPermissionsScript.SetupItem.ROOT_PERMS,
        "In-game FPS & diagnostics (DUMP)",
        Icons.Outlined.Speed,
    ),
    SetupRowSpec(
        AdvancedPermissionsScript.SetupItem.USAGE_ACCESS,
        "Foreground detection — per-app profiles (Usage access)",
        Icons.Outlined.Apps,
    ),
    SetupRowSpec(
        AdvancedPermissionsScript.SetupItem.OVERLAY,
        "Overlay HUD (Display over other apps)",
        Icons.Outlined.Layers,
    ),
    SetupRowSpec(
        AdvancedPermissionsScript.SetupItem.BATTERY,
        "Reliable background running (Battery unrestricted)",
        Icons.Outlined.BatteryChargingFull,
    ),
    SetupRowSpec(
        AdvancedPermissionsScript.SetupItem.NOTIFICATIONS,
        "Notifications",
        Icons.Outlined.Notifications,
    ),
)

/**
 * ONE-CLICK FULL setup step (PServer-live devices). A single screen, a single
 * tap: [AdvancedUnlockViewModel.setupEverything] grants the app EVERYTHING it
 * needs through the device's built-in root bridge (PServer) — the 3 pm-perms
 * (DUMP / usage-stats / secure-settings) PLUS Usage Access, the overlay HUD,
 * battery-unrestricted, and notifications — in one shot. No Settings trips, no
 * script, no file picker.
 *
 * HONESTY is enforced end-to-end. The checklist NEVER shows ✓ for something the
 * engine's post-grant readback says is false — every row reads the real
 * [AdvancedUnlockViewModel.FullSetupState.FullCompleted.held] map:
 *   - Idle               → "Set up everything" hero + the trust line + a preview
 *                           of the items that will be granted (all unchecked).
 *   - Running            → live checklist with a progress bar ("Setting everything up…").
 *   - FullCompleted (all)→ "You're all set 🎉 — Calibrate is fully configured" + Enter app.
 *   - FullCompleted (some)→ honest partial: ✓ for what landed, ✗ for what didn't,
 *                           + a Retry and a "Grant the rest via the script" fallback.
 *   - NotAvailable       → honest "couldn't set up automatically" + the script ladder
 *                           ([AdvancedScriptStep]) which stays the FALLBACK for
 *                           non-PServer devices.
 *   - already all held   → if [liveTuningActive] AND nothing left to grant, this
 *                           screen isn't shown (the caller skips straight to "all
 *                           set"); but if reached, the FullCompleted-all branch
 *                           renders the all-set state.
 */
@Composable
private fun AdvancedFullSetupStep(
    fullSetupState: AdvancedUnlockViewModel.FullSetupState,
    liveTuningActive: Boolean,
    onSetUp: () -> Unit,
    onRetry: () -> Unit,
    onEnterApp: () -> Unit,
    onUseScript: () -> Unit,
    // When false (PServer-capable device under the hard-require), the IDLE-state
    // "Not now" escape is hidden — the one-tap "Set up everything" is the only way
    // forward. The NotAvailable/FullCompleted enter-app paths are honesty escapes
    // and are NOT affected by this flag.
    allowIdleSkip: Boolean = true,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = fullSetupState) {
                // ── ALL SET (every item landed per the live readback) ──────────
                is AdvancedUnlockViewModel.FullSetupState.FullCompleted -> if (s.allGranted) {
                    // Genuine win — a classy celebratory banner (Emerald), not childish.
                    SetupCelebration(
                        title = "You're all set",
                        body = "Calibrate is fully configured — tuning, in-game FPS, the overlay HUD, " +
                            "per-app profiles, and reliable background running are all enabled.",
                    )
                    SetupChecklist(held = s.held)
                    Button(onClick = onEnterApp, modifier = Modifier.fillMaxWidth()) {
                        Text("Enter app")
                    }
                } else {
                    // ── HONEST PARTIAL — some item didn't take ─────────────────
                    AdvCardHeader(Icons.Outlined.Warning, "Almost there")
                    Text(
                        "Calibrate enabled what it could through the root bridge, but not everything " +
                            "came through. Here's exactly what's on and what's still missing — you can " +
                            "retry, or grant the rest with the one-time script.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SetupChecklist(held = s.held)
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry setup")
                    }
                    OutlinedButton(onClick = onUseScript, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant the rest via the script")
                    }
                    OutlinedButton(onClick = onEnterApp, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue anyway")
                    }
                }

                // ── SETTING EVERYTHING UP… (live checklist, none ticked yet) ───
                is AdvancedUnlockViewModel.FullSetupState.Running -> {
                    AdvCardHeader(Icons.Outlined.Bolt, "Setting everything up…")
                    Text(
                        "Granting Calibrate everything it needs through your device's root bridge.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                    // No held map yet — show the items pending so the user sees what's
                    // being set up. Honest: every row is unchecked until the readback lands.
                    SetupChecklist(held = emptyMap())
                }

                // ── PServer NOT TRANSACTABLE (honest fallback to the script) ───
                is AdvancedUnlockViewModel.FullSetupState.NotAvailable -> {
                    AdvCardHeader(Icons.Outlined.Info, "Couldn't set up automatically")
                    Text(
                        "We couldn't reach your device's root bridge to grant the permissions " +
                            "automatically. No changes were made. You can still enable everything by " +
                            "running the one-time unlock script.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onUseScript, modifier = Modifier.fillMaxWidth()) {
                        Text("Set up with the script instead")
                    }
                    OutlinedButton(onClick = onEnterApp, modifier = Modifier.fillMaxWidth()) {
                        Text("Not now — enter app")
                    }
                }

                // ── IDLE — the HERO "set up everything" moment ─────────────────
                is AdvancedUnlockViewModel.FullSetupState.Idle -> {
                    // Big, confident badge + headline + value line — the "tap this and
                    // you're done" moment. Bold but cohesive with the Arsenal idiom.
                    HeroBadge(Icons.Outlined.AutoAwesome)
                    Text(
                        "Set up Calibrate in one tap",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Calibrate uses your device's built-in root bridge to grant itself everything " +
                            "it needs. No Settings trips, no script, no reboot — just one tap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Preview of what one tap grants — honest, all unchecked until the user taps.
                    SetupChecklist(
                        held = emptyMap(),
                        liveTuningActive = liveTuningActive,
                        title = "What you'll get",
                    )
                    AdvNoteCard(
                        "Safe by design: the app only grants its OWN known permissions. Every root " +
                            "command passes the built-in safety gate, which permits nothing else.",
                    )
                    Button(
                        onClick = onSetUp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Set up everything",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    // HARD-REQUIRE: "Not now" is shown ONLY on non-capable devices.
                    // On a PServer-capable device the one-tap grant above is the only
                    // way forward — there is no "not now."
                    if (allowIdleSkip) {
                        OutlinedButton(onClick = onEnterApp, modifier = Modifier.fillMaxWidth()) {
                            Text("Not now")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The HERO icon badge for the one-click setup screen — a large accent-tinted
 * circle with the app's primary accent. Two concentric tints give it depth
 * without a heavy gradient (stays cheap to draw on a handheld).
 */
@Composable
private fun HeroBadge(icon: ImageVector) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * A classy "you're all set" celebration banner — an Emerald (tertiary) badge,
 * a bold title, and a body line. Genuine-win energy without confetti/childish
 * flair. Only ever shown when the engine's readback confirms everything landed.
 */
@Composable
private fun SetupCelebration(title: String, body: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(34.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The honest live checklist for one-click setup. Each row reflects the REAL
 * per-[AdvancedPermissionsScript.SetupItem] [held] map produced by the engine's
 * post-grant readback — a row is ✓ only when [held] says true for it, otherwise
 * it shows a pending ○. Never fabricates a ✓.
 *
 * The leading "Live tuning (already active on this device)" row is shown only on
 * a PServer-live device ([liveTuningActive]); it reflects the genuinely-active
 * live-tuning path, not a setup item, so it's ✓ from the start there.
 */
@Composable
private fun SetupChecklist(
    held: Map<AdvancedPermissionsScript.SetupItem, Boolean>,
    liveTuningActive: Boolean = false,
    title: String? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                ChecklistHeader(title)
            }
            if (liveTuningActive) {
                SetupChecklistRow(
                    done = true,
                    label = "Live tuning (already active on this device)",
                    icon = Icons.Outlined.Bolt,
                )
            }
            for (spec in SetupItemLabels) {
                // HONEST: ✓ only when the readback says this item is held. Absent key
                // (Idle preview / Running before readback) → pending, never ✓.
                SetupChecklistRow(
                    done = held[spec.item] == true,
                    label = spec.label,
                    icon = spec.icon,
                )
            }
        }
    }
}

/** A small accent-keyed header for the checklist card (Arsenal section idiom). */
@Composable
private fun ChecklistHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One honest checklist row — pending shows the item's own icon in an outlined
 * circle; granted flips to an Emerald (tertiary) check with a subtle pop. The
 * tint, text colour, and a small scale animate so each ✓ ticking in feels alive.
 * HONESTY: [done] mirrors the real held map; nothing here can fake a ✓.
 */
@Composable
private fun SetupChecklistRow(done: Boolean, label: String, icon: ImageVector) {
    val iconTint by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.outline,
        animationSpec = tween(durationMillis = 280),
        label = "checklistIconTint",
    )
    val textColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 280),
        label = "checklistTextColor",
    )
    // A gentle pop the moment a row flips to granted — tasteful, not gaudy.
    val pop by animateFloatAsState(
        targetValue = if (done) 1f else 0.9f,
        animationSpec = tween(durationMillis = 220),
        label = "checklistPop",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Granted",
                    tint = iconTint,
                    modifier = Modifier
                        .size(24.dp)
                        .scale(pop),
                )
            } else {
                // Pending: the item's own icon, muted, inside a hollow ring.
                Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "Pending",
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (done) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
        )
    }
}

/**
 * The MAIN advanced step — generate + run the unlock script, with live
 * grant detection. This is the part that actually unlocks live tuning on
 * PServer/binder/most devices (pm grants + PServer whitelist).
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
    onContinue: () -> Unit,
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
            AdvCardHeader(Icons.Outlined.Terminal, "Run the unlock script", "One-time setup")

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
            // Manual forward path to the Done screen for when live grants
            // didn't auto-detect (some firmwares delay the pm-grant probe).
            // The live-success effect normally advances automatically; this
            // is the always-available "I ran it — continue" escape.
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("I ran the script — continue") }
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

/**
 * Phase 5 — celebrate. Honest if only some grants landed.
 */
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
            //    failure. Instant in-app ± clocks need a custom kernel.
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
                    "You're unlocked. Real in-game FPS and vendor tuning are on. " +
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

// ─────────────────────────────────────────────────────────────────────
// UNIVERSAL device steps (wire the orphaned root / Shizuku / plain paths)
// ─────────────────────────────────────────────────────────────────────

/**
 * ROOT route — a device with su/Magisk/KernelSU present but root mode NOT yet
 * enabled. We surface the EXISTING root-mode opt-in (UserPrefs.setRootModeEnabled
 * via the VM) so the device reaches the ROOT tier instead of falling to NONE.
 *
 * Root is opt-in by design, so declining is honest (not a trap): "Not now" enters
 * the app with the universal HUD/monitoring features still working.
 */
@Composable
private fun RootEnableStep(
    rootKind: io.github.mayusi.calibratesoc.data.capability.RootKind?,
    rootModeEnabled: Boolean,
    onEnableRoot: () -> Unit,
    onEnterApp: () -> Unit,
) {
    val rootName = when (rootKind) {
        io.github.mayusi.calibratesoc.data.capability.RootKind.MAGISK -> "Magisk"
        io.github.mayusi.calibratesoc.data.capability.RootKind.KERNELSU -> "KernelSU"
        else -> "root"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AdvCardHeader(Icons.Outlined.Bolt, "Root detected — enable root tuning", "Full live tuning")
            Text(
                "We detected $rootName on your device. Turn on root mode and Calibrate gets the " +
                    "strongest tuning path: live CPU/GPU clocks and governors, AutoTDP, and the HUD ± " +
                    "buttons — applied instantly, no script, no reboot.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AdvNoteCard(
                "Root mode is off by default and stays off until you enable it here. You can turn it " +
                    "back off any time in Settings. Every root command passes the built-in safety gate.",
            )
            if (rootModeEnabled) {
                // Already flipped — the probe will promote the device to ROOT and the
                // auto-route moves it to the one-tap hero on the next frame. Show a
                // brief confirmation so the screen isn't blank during that beat.
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
                        "Root mode enabled — finishing setup…",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            } else {
                Button(
                    onClick = onEnableRoot,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Enable root tuning",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Root is opt-in: declining is honest, never a trap.
            OutlinedButton(onClick = onEnterApp, modifier = Modifier.fillMaxWidth()) {
                Text("Not now — enter app")
            }
        }
    }
}

/**
 * SHIZUKU route — surfaces the EXISTING
 * [io.github.mayusi.calibratesoc.data.shizuku.ShizukuOnboarding] state machine as
 * an onboarding step. This composable is pure presentation over that machine's
 * [OnboardingState]; it does NOT reimplement Shizuku detection or permission
 * logic — every state/button delegates back to the VM, which delegates to the
 * existing singleton.
 *
 * States surfaced honestly:
 *   NOT_INSTALLED        → explain Shizuku + an install pointer
 *   INSTALLED_STOPPED    → the on-device wireless-debugging pairing guide
 *   RUNNING_NO_PERMISSION→ the "Grant Shizuku permission" button (real requestPermission)
 *   GRANTED              → success; live tuning enabled
 *   GRANTED_NO_WRITES    → the HONEST "this kernel blocks shell writes" disclosure
 */
@Composable
private fun ShizukuSetupStep(
    state: kotlinx.coroutines.flow.StateFlow<io.github.mayusi.calibratesoc.data.shizuku.OnboardingState>,
    supportsOnDeviceWirelessPairing: Boolean,
    wirelessPairingSteps: List<io.github.mayusi.calibratesoc.data.shizuku.GuideStep>,
    onGrantPermission: () -> Unit,
    onRefresh: () -> Unit,
    onEnterApp: () -> Unit,
) {
    val shizukuState by state.collectAsState()
    // Re-evaluate on resume so returning from Shizuku's pairing/permission UI
    // updates the state without a manual tap.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AdvCardHeader(Icons.Outlined.Bolt, "Set up Shizuku", "Live tuning without root")
            when (shizukuState) {
                io.github.mayusi.calibratesoc.data.shizuku.OnboardingState.NOT_INSTALLED -> {
                    Text(
                        "Shizuku lets Calibrate run privileged tuning commands without root. Install " +
                            "Shizuku (Play Store / F-Droid / GitHub), then come back — we'll walk you " +
                            "through pairing it on-device (no PC needed on Android 11+).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AdvNoteCard(
                        "After installing Shizuku, return here and tap Refresh — this step will advance " +
                            "to the pairing guide automatically.",
                    )
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text("I installed Shizuku — refresh")
                    }
                }
                io.github.mayusi.calibratesoc.data.shizuku.OnboardingState.INSTALLED_STOPPED -> {
                    Text(
                        if (supportsOnDeviceWirelessPairing)
                            "Shizuku is installed but not running. Pair it once using wireless debugging " +
                                "— entirely on-device, no computer required:"
                        else
                            "Shizuku is installed but not running. On this Android version you'll need a " +
                                "one-time PC connection to start it (adb). Follow Shizuku's own guide, then " +
                                "return here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (supportsOnDeviceWirelessPairing) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            wirelessPairingSteps.forEachIndexed { i, step ->
                                NumberedStep(i + 1, "${step.title} — ${step.detail}")
                            }
                        }
                    }
                    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text("I started Shizuku — refresh")
                    }
                }
                io.github.mayusi.calibratesoc.data.shizuku.OnboardingState.RUNNING_NO_PERMISSION -> {
                    Text(
                        "Shizuku is running. Grant Calibrate permission to use it for live tuning.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onGrantPermission,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Grant Shizuku permission",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                io.github.mayusi.calibratesoc.data.shizuku.OnboardingState.GRANTED -> {
                    SetupCelebration(
                        title = "Shizuku connected",
                        body = "Live tuning is enabled through Shizuku — CPU/GPU clocks, governors, and " +
                            "AutoTDP work where your kernel allows it.",
                    )
                }
                io.github.mayusi.calibratesoc.data.shizuku.OnboardingState.GRANTED_NO_WRITES -> {
                    // HONEST disclosure — preserved exactly as the state machine intends.
                    AdvCardHeader(Icons.Outlined.Info, "Shizuku connected — limited on this device")
                    Text(
                        "Shizuku is connected, but this device's kernel (vendor SELinux policy) blocks " +
                            "shell writes to the CPU/GPU tuning nodes. Monitoring, the HUD, benchmarks, " +
                            "and vendor settings still work — but live ± clock tuning isn't available " +
                            "here. That's a kernel lock, not a setup error.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Enter app is always available — Shizuku is offered, never forced.
            OutlinedButton(onClick = onEnterApp, modifier = Modifier.fillMaxWidth()) {
                Text("Enter app")
            }
        }
    }
}

/**
 * PLAIN device terminal — a stock phone/tablet with no PServer / root / Shizuku /
 * vendor runner. Honest by construction: the universal perms (overlay / usage /
 * battery) make the HUD + monitoring work on ANY device, and we tell the user
 * plainly what they get and what unlocking more would take. The door is ALWAYS
 * open (canComplete is true for this route) — a plain device is NEVER trapped.
 */
@Composable
private fun PlainDeviceTerminalStep(
    statuses: Map<String, Boolean>,
    onGrant: (SetupItem) -> Unit,
    onSeeOptions: () -> Unit,
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
            AdvCardHeader(Icons.Outlined.CheckCircle, "You're ready to go")
            Text(
                "Calibrate works on your device for the HUD overlay, FPS & performance monitoring, " +
                    "benchmarks, profiles, and insights. Grant the permissions below so the HUD and " +
                    "monitoring run reliably.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The universal perms — system-dialog grants that work on ANY device.
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (item in AllSetupItems) {
                        val done = statuses[item.id] == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (done) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (!done) {
                                TextButton(onClick = { onGrant(item) }) { Text("Grant") }
                            }
                        }
                    }
                }
            }
            AdvNoteCard(
                "Live CPU/GPU tuning needs a privilege path — root, Shizuku, or a supported handheld. " +
                    "Want to unlock it? Here's how.",
            )
            Button(onClick = onEnterApp, modifier = Modifier.fillMaxWidth()) { Text("Enter app") }
            OutlinedButton(onClick = onSeeOptions, modifier = Modifier.fillMaxWidth()) {
                Text("How to unlock live tuning")
            }
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
