package io.github.mayusi.calibratesoc.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.BuildConfig
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.prefs.ClockUnit
import io.github.mayusi.calibratesoc.data.prefs.TempUnit
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.update.UpdateInfo
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.AccentColor
import io.github.mayusi.calibratesoc.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Settings screen. Surfaces:
 *   - Post-update "What's New" banner (dismissible)
 *   - Appearance (accent colour picker)
 *   - Units (clock: MHz/GHz, temperature: °C/°F)
 *   - What's New / in-app changelog link
 *   - Check for updates + GitHub links
 *   - Setup checklist (re-grant permissions)
 *   - Experimental features gate
 *   - Privilege tier badge with explainer
 *   - Root mode toggle
 *   - Per-app auto-switch (Accessibility grant)
 *   - Factory restore
 *   - Device Info deep-link
 *   - Data & backup placeholder (BackupCard)
 *   - About (version, license, repo links)
 */
@Composable
fun SettingsScreen(
    onOpenDeviceInfo: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val accessibilityGranted by viewModel.accessibilityGranted.collectAsStateWithLifecycle()
    val rootModeEnabled by viewModel.rootModeEnabled.collectAsStateWithLifecycle()
    val rootDetected by viewModel.rootDetected.collectAsStateWithLifecycle()
    val baseline by viewModel.baseline.collectAsStateWithLifecycle()
    val restoreSummary by viewModel.restoreSummary.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val clockUnit by viewModel.clockUnit.collectAsStateWithLifecycle()
    val tempUnit by viewModel.tempUnit.collectAsStateWithLifecycle()
    val shouldShowWhatsNew by viewModel.shouldShowWhatsNew.collectAsStateWithLifecycle()
    val showWhatsNewScreen by viewModel.showWhatsNewScreen.collectAsStateWithLifecycle()
    val tempAlertsEnabled by viewModel.tempAlertsEnabled.collectAsStateWithLifecycle()
    val tempAlertThresholdC by viewModel.tempAlertThresholdC.collectAsStateWithLifecycle()
    val tempAlertAutoProfileId by viewModel.tempAlertAutoProfileId.collectAsStateWithLifecycle()
    val savedProfiles by viewModel.savedProfiles.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var pendingFactoryConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Full-screen What's New overlay — shown when the user taps the
    // What's New row OR the post-update banner's "See what's new" button.
    //
    // M-2 fix: this overlay is a boolean flip inside Settings, NOT a real nav
    // destination, so a system Back press would otherwise fall through to the
    // NavHost and pop the whole Settings stack. Intercept Back here so it just
    // closes the overlay and returns to Settings (matching the in-screen Back
    // button's behaviour).
    if (showWhatsNewScreen) {
        BackHandler(enabled = true) { viewModel.closeWhatsNew() }
        WhatsNewScreen(onBack = { viewModel.closeWhatsNew() })
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item {
            Text(
                "SETTINGS",
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
                letterSpacing = 0.04.sp,
            )
        }

        // ── Post-update "What's New" banner ───────────────────────────────
        if (shouldShowWhatsNew) {
            item {
                WhatsNewBanner(
                    versionName = viewModel.appVersion,
                    onSeeWhatsNew = {
                        viewModel.markWhatsNewSeen()
                        viewModel.openWhatsNew()
                    },
                    onDismiss = { viewModel.markWhatsNewSeen() },
                )
            }
        }

        // ── Appearance ────────────────────────────────────────────────────
        item {
            SectionCard(title = "APPEARANCE", icon = Icons.Outlined.ColorLens) {
                Text(
                    "Accent colour",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.dense))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    AccentColor.entries.forEach { accent ->
                        val selected = accent == accentColor
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(accent.color)
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = Color.White,
                                            shape = CircleShape,
                                        )
                                    } else {
                                        Modifier.border(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.20f),
                                            shape = CircleShape,
                                        )
                                    },
                                )
                                .clickable { viewModel.setAccent(accent) },
                        )
                    }
                }
                Text(
                    "Current: ${accentColor.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Units ─────────────────────────────────────────────────────────
        item {
            SectionCard(title = "UNITS", icon = Icons.Outlined.Straighten) {
                // Clock unit toggle
                Text(
                    "Clock speed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.dense))
                SegmentedToggle(
                    options = listOf("MHz", "GHz"),
                    selectedIndex = if (clockUnit == ClockUnit.MHZ) 0 else 1,
                    onSelect = { idx ->
                        viewModel.setClockUnit(if (idx == 0) ClockUnit.MHZ else ClockUnit.GHZ)
                    },
                )

                Spacer(Modifier.height(Spacing.group))

                // Temperature unit toggle
                Text(
                    "Temperature",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.dense))
                SegmentedToggle(
                    options = listOf("°C", "°F"),
                    selectedIndex = if (tempUnit == TempUnit.CELSIUS) 0 else 1,
                    onSelect = { idx ->
                        viewModel.setTempUnit(if (idx == 0) TempUnit.CELSIUS else TempUnit.FAHRENHEIT)
                    },
                )
            }
        }

        // ── Temperature alerts ─────────────────────────────────────────────
        item {
            TempAlertsCard(
                enabled = tempAlertsEnabled,
                thresholdC = tempAlertThresholdC,
                tempUnit = tempUnit,
                autoProfileId = tempAlertAutoProfileId,
                savedProfiles = savedProfiles,
                onToggle = viewModel::setTempAlertsEnabled,
                onThresholdChange = viewModel::setTempAlertThresholdC,
                onAutoProfileChange = viewModel::setTempAlertAutoProfileId,
            )
        }

        // ── What's New row ────────────────────────────────────────────────
        item {
            SectionCard(title = "WHAT'S NEW", icon = Icons.Outlined.NewReleases) {
                Text(
                    "See what changed in this version and review the full update history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.dense))
                ArsenalButton(
                    label = "View changelog",
                    onClick = { viewModel.openWhatsNew() },
                    style = ArsenalButtonStyle.Secondary,
                    accent = AccentBar.Neutral,
                )
            }
        }

        // ── Check for updates + GitHub links ──────────────────────────────
        item {
            UpdatesAndFeedbackCard(context = context)
        }

        // ── Setup checklist ────────────────────────────────────────────────
        item { SetupChecklistCard() }

        // ── Experimental features ──────────────────────────────────────────
        item {
            ExperimentalCard(
                enabled = viewModel.experimentalEnabled.collectAsStateWithLifecycle().value,
                onToggle = viewModel::setExperimentalEnabled,
            )
        }

        // ── Privilege tier ─────────────────────────────────────────────────
        item {
            val tier = capability?.privilege ?: PrivilegeTier.NONE
            val vb = io.github.mayusi.calibratesoc.data.vendor.VendorBranding.of(capability)
            // PServer-live is the strongest tier on Odin / Retroid: the vendor root
            // runner writes sysfs as root even when PrivilegeTier reads
            // VENDOR_SETTINGS. Surface it as full root tuning, NOT the old
            // "vendor keys only, custom MHz via script" story.
            val pserverLive = capability?.pserverSysfsLive == true
            val tierAccent = when {
                tier == PrivilegeTier.ROOT -> AccentBar.Emerald
                pserverLive -> AccentBar.Emerald
                tier == PrivilegeTier.VENDOR_SETTINGS -> AccentBar.Emerald
                tier == PrivilegeTier.SHIZUKU -> AccentBar.Blue
                else -> AccentBar.Neutral
            }
            val tierChip = when {
                pserverLive -> "PSERVER LIVE"
                tier == PrivilegeTier.VENDOR_SETTINGS -> vb.tierLabel
                else -> tier.name
            }
            val tierExplainer = when {
                pserverLive ->
                    "PServer live — full root tuning. ${vb.brand}'s vendor root runner writes CPU/GPU " +
                        "clocks, governors, and DDR directly as root. Custom MHz caps, AutoTDP, and the " +
                        "HUD ± controls Apply instantly — no script, no reboot, nothing to set up."
                tier == PrivilegeTier.ROOT ->
                    "Full kernel-level CPU/GPU clocking + fan control + custom tunes. Magisk / KernelSU detected and root mode enabled."
                tier == PrivilegeTier.VENDOR_SETTINGS ->
                    "${vb.brand} tier active. Vendor performance + fan modes apply instantly via the same Settings.System keys ${vb.brand}'s own Quick Settings tile uses. Custom MHz caps via Generate script."
                tier == PrivilegeTier.SHIZUKU ->
                    "Monitoring + vendor tuning. Full sysfs writes pending a Shizuku UserService update."
                else ->
                    "Read-only monitoring + benchmark. On supported handhelds, grant WRITE_SECURE_SETTINGS via adb to enable vendor tuning. Magisk / KernelSU unlocks the ROOT tier (opt in below). Custom MHz caps work on ANY device via Generate script."
            }
            ArsenalPanel(accent = tierAccent, title = "PRIVILEGE TIER") {
                StatusPill(text = tierChip, accent = tierAccent)
                Spacer(Modifier.height(Spacing.dense))
                Text(tierExplainer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── Root mode toggle ───────────────────────────────────────────────
        item {
            ArsenalPanel(accent = if (rootModeEnabled) AccentBar.Emerald else AccentBar.Neutral, title = "ROOT MODE (ADVANCED)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (rootDetected) {
                            "Magisk / KernelSU detected. Enable to let Calibrate SoC use it for direct kernel writes (custom MHz caps without the script step)."
                        } else {
                            "No su binary found. Install Magisk or KernelSU first if you want this tier."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rootModeEnabled,
                        enabled = rootDetected,
                        onCheckedChange = { viewModel.setRootModeEnabled(it) },
                    )
                }
            }
        }

        // ── Per-app auto-switch ────────────────────────────────────────────
        item {
            ArsenalPanel(
                accent = if (accessibilityGranted) AccentBar.Emerald else AccentBar.Amber,
                title = "PER-APP AUTO-SWITCH",
            ) {
                StatusPill(
                    text = if (accessibilityGranted) "ENABLED" else "DISABLED",
                    accent = if (accessibilityGranted) AccentBar.Emerald else AccentBar.Amber,
                )
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    if (accessibilityGranted) {
                        "Per-app overrides will fire on app switch. Configure them on the Profiles tab."
                    } else {
                        "Grant Accessibility access so Calibrate SoC can detect app switches and auto-apply your per-app profiles."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.dense))
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(if (accessibilityGranted) "Manage in Settings" else "Open Accessibility settings") }
            }
        }

        // ── Auto-configure known games ─────────────────────────────────────
        item {
            AutoConfigKnownGamesCard(
                enabled = viewModel.autoConfigKnownGamesEnabled.collectAsStateWithLifecycle().value,
                onToggle = viewModel::setAutoConfigKnownGamesEnabled,
            )
        }

        // ── Factory restore ────────────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Amber, title = "RESTORE TO FACTORY STATE") {
                val b = baseline
                if (b == null) {
                    Text(
                        "Capturing baseline... if this persists, restart the app once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentBar.Red,
                    )
                } else {
                    val date = java.text.SimpleDateFormat("MMM d yyyy, HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(b.capturedAtMs))
                    Text(
                        "Captured $date • ${b.tunables.size} tunables recorded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.dense))
                    Text(
                        "Replays every CPU/GPU cap, governor, and vendor key that was set when the app first launched. The actual surfaces restored depend on your current privilege tier — read-only tier will skip kernel writes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.dense))
                    OutlinedButton(onClick = { pendingFactoryConfirm = true }) {
                        Text("Restore to first-launch state")
                    }
                }
            }
        }

        // ── Device Info ────────────────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Blue, title = "DEVICE") {
                Text(
                    "Open the full capability report — what the probe found on this device. Includes the Report unknown device share button.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.dense))
                OutlinedButton(onClick = onOpenDeviceInfo) { Text("Open Device Info") }
            }
        }

        // ── Data & backup ──────────────────────────────────────────────────
        item { BackupCard() }

        // ── About ──────────────────────────────────────────────────────────
        item {
            SectionCard(title = "ABOUT", icon = Icons.Outlined.Info) {
                Text(
                    "Calibrate SoC ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Universal Handheld Tuner — Apache-2.0", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Tuned for AYN Thor · Odin 3 · Odin 2, Retroid Pocket 6 · 5, and high-end Android phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/TheOldTaylor/Odin3-CPU-Underclock"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Credits: TheOldTaylor (Odin 3 underclock seed)") }
            }
        }
    }

    if (pendingFactoryConfirm) {
        FactoryConfirmDialog(
            onConfirm = {
                viewModel.restoreToFactory()
                pendingFactoryConfirm = false
            },
            onDismiss = { pendingFactoryConfirm = false },
        )
    }

    restoreSummary?.let { summary ->
        RestoreSummaryDialog(
            summary = summary,
            onDismiss = viewModel::clearRestoreSummary,
        )
    }
}

// ── Updates & Feedback card ───────────────────────────────────────────────────

/**
 * Full in-app updater section. Uses [UpdateViewModel] for state and
 * [ChangelogText] for rendering GitHub release notes.
 *
 * States:
 *   Idle / UpToDate → shows "Check for updates" button + version label.
 *   Checking        → spinner + "Checking…" text.
 *   Available       → version card with expandable release notes + "Update now".
 *   Downloading     → linear progress bar with percentage.
 *   ReadyToInstall  → auto-launched installer + "Reopen installer" button.
 *   Error           → AlertCard + "Open Releases page" fallback.
 *
 * Report-issue and View-source buttons are always visible below the updater.
 */
@Composable
private fun UpdatesAndFeedbackCard(
    context: android.content.Context,
    updateVm: UpdateViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val updateState by updateVm.state.collectAsStateWithLifecycle()
    val autoUpdateEnabled by settingsVm.autoUpdateCheckEnabled.collectAsStateWithLifecycle()
    var notesExpanded by remember { mutableStateOf(false) }

    SectionCard(title = "Updates & Feedback", icon = Icons.Outlined.SystemUpdate) {
        // ── Version label (always visible) ────────────────────────────────
        Text(
            "Calibrate SoC ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.group))

        // ── Auto-check toggle ─────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Check for updates automatically",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Checks GitHub about once a day and shows a banner when a new version is out. Updates never install on their own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoUpdateEnabled,
                onCheckedChange = { settingsVm.setAutoUpdateCheckEnabled(it) },
            )
        }
        Spacer(Modifier.height(Spacing.dense))

        // ── Updater state machine ─────────────────────────────────────────
        when (val s = updateState) {
            is UpdateUiState.Idle -> {
                OutlinedButton(
                    onClick = { updateVm.check() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.dense))
                    Text("Check for updates")
                }
            }

            is UpdateUiState.Checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Checking…", style = MaterialTheme.typography.bodySmall)
                }
            }

            is UpdateUiState.UpToDate -> {
                Text(
                    "You're on the latest version (${s.currentVersion}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBar.Emerald,
                )
                Spacer(Modifier.height(Spacing.dense))
                OutlinedButton(
                    onClick = { updateVm.check() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check again") }
            }

            is UpdateUiState.Available -> {
                UpdateAvailableCard(
                    info = s.info,
                    notesExpanded = notesExpanded,
                    onToggleNotes = { notesExpanded = !notesExpanded },
                    onDownload = { updateVm.download(s.info) },
                    onOpenGitHub = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/mayusi/Calibrate-SoC/releases/latest"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }

            is UpdateUiState.Downloading -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    Text(
                        if (s.pct < 0) "Downloading…" else "Downloading… ${s.pct}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.pct < 0) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { s.pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            is UpdateUiState.ReadyToInstall -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    Text(
                        "Opening installer…",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentBar.Emerald,
                    )
                    OutlinedButton(
                        onClick = { updateVm.install(s.file) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.dense))
                        Text("Reopen installer")
                    }
                }
            }

            is UpdateUiState.Error -> {
                AlertCard(
                    type = AlertType.ERROR,
                    title = "Update check failed",
                    message = s.message,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.dense))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    OutlinedButton(
                        onClick = { updateVm.reset() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Dismiss") }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/mayusi/Calibrate-SoC/releases/latest"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            updateVm.reset()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.dense))
                        Text("Releases page")
                    }
                }
            }
        }

        // ── Always-visible GitHub links ───────────────────────────────────
        Spacer(Modifier.height(Spacing.dense))
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/mayusi/Calibrate-SoC/issues"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.material3.Icon(
                Icons.Outlined.BugReport,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.dense))
            Text("Report an issue")
        }
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/mayusi/Calibrate-SoC"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.material3.Icon(
                Icons.Outlined.Code,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.dense))
            Text("View source on GitHub")
        }
    }
}

/**
 * Card shown when a newer version is available. Shows the version name,
 * an expandable release notes section (rendered via [ChangelogText]), and
 * an "Update now" / "Open on GitHub" button depending on whether an APK
 * asset exists.
 */
@Composable
private fun UpdateAvailableCard(
    info: UpdateInfo,
    notesExpanded: Boolean,
    onToggleNotes: () -> Unit,
    onDownload: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Emerald, title = "UPDATE AVAILABLE") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            io.github.mayusi.calibratesoc.ui.components.StatusPill(
                text = "v${info.versionName}",
                accent = AccentBar.Emerald,
            )
            if (info.apkSize > 0) {
                Text(
                    formatBytes(info.apkSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Expandable release notes
        if (info.notes.isNotBlank()) {
            TextButton(
                onClick = onToggleNotes,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(
                    if (notesExpanded) "Hide release notes" else "Show release notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentBar.Emerald,
                    letterSpacing = 0.06.sp,
                )
            }
            AnimatedVisibility(visible = notesExpanded) {
                ChangelogText(
                    markdown = info.notes,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Action button
        if (info.apkUrl != null) {
            ArsenalButton(
                label = "Update now",
                onClick = onDownload,
                accent = AccentBar.Emerald,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "This release has no APK download yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArsenalButton(
                label = "View on GitHub",
                onClick = onOpenGitHub,
                accent = AccentBar.Neutral,
                style = ArsenalButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}

// ── What's New banner ─────────────────────────────────────────────────────────

@Composable
private fun WhatsNewBanner(
    versionName: String,
    onSeeWhatsNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Emerald, title = "UPDATED TO $versionName") {
        Text(
            "See what changed in this release.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.group))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            ArsenalButton(
                label = "See what's new",
                onClick = onSeeWhatsNew,
                accent = AccentBar.Emerald,
            )
            ArsenalButton(
                label = "Dismiss",
                onClick = onDismiss,
                accent = AccentBar.Neutral,
                style = ArsenalButtonStyle.Secondary,
            )
        }
    }
}

// ── Temperature alerts card ────────────────────────────────────────────────────

/**
 * Settings card for the temperature alert feature. Shows:
 *   - A switch to enable/disable alerts.
 *   - When enabled: a threshold slider (60–95 °C) displayed in the user's
 *     preferred temperature unit; stored internally in °C.
 *   - An auto-profile picker ("None" or any saved profile).
 *
 * Honest helper text: alerts reflect real sensor readings. If no temp is
 * readable, no alert fires.
 */
@Composable
private fun TempAlertsCard(
    enabled: Boolean,
    thresholdC: Int,
    tempUnit: TempUnit,
    autoProfileId: String?,
    savedProfiles: List<UserProfile>,
    onToggle: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onAutoProfileChange: (String?) -> Unit,
) {
    SectionCard(title = "Temperature alerts", icon = Icons.Outlined.Thermostat) {
        Text(
            "Notifies you when your device gets hot so you can take a break or switch to a cooler profile. " +
                "Alerts run while monitoring is active — keep the floating HUD on while gaming to be warned in-game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.group))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Temperature alerts", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (enabled) "ON — fires when threshold is crossed" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) AccentBar.Emerald
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        AnimatedVisibility(visible = enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Spacer(Modifier.height(Spacing.dense))

                // Threshold slider
                val displayThreshold = when (tempUnit) {
                    TempUnit.CELSIUS    -> thresholdC.toFloat()
                    TempUnit.FAHRENHEIT -> thresholdC * 9f / 5f + 32f
                }
                val minDisplay = when (tempUnit) {
                    TempUnit.CELSIUS    -> 60f
                    TempUnit.FAHRENHEIT -> 60f * 9f / 5f + 32f  // 140°F
                }
                val maxDisplay = when (tempUnit) {
                    TempUnit.CELSIUS    -> 95f
                    TempUnit.FAHRENHEIT -> 95f * 9f / 5f + 32f  // 203°F
                }
                val unitLabel = when (tempUnit) {
                    TempUnit.CELSIUS    -> "°C"
                    TempUnit.FAHRENHEIT -> "°F"
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Alert threshold",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "%.0f%s".format(displayThreshold, unitLabel),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
                Slider(
                    value = displayThreshold,
                    onValueChange = { newDisplay ->
                        // Convert back to °C for storage
                        val newC = when (tempUnit) {
                            TempUnit.CELSIUS    -> newDisplay.roundToInt()
                            TempUnit.FAHRENHEIT -> ((newDisplay - 32f) * 5f / 9f).roundToInt()
                        }.coerceIn(60, 95)
                        onThresholdChange(newC)
                    },
                    valueRange = minDisplay..maxDisplay,
                    steps = when (tempUnit) {
                        TempUnit.CELSIUS    -> 34  // 60–95 °C in 1°C steps = 35 positions → 34 steps
                        TempUnit.FAHRENHEIT -> 62  // ~1°F per step across ~63 F range
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Alerts fire when any CPU, GPU, or battery sensor reaches this value.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(Spacing.dense))

                // Auto-profile picker
                Text(
                    "Switch to profile when hot",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.dense))
                ProfilePickerDropdown(
                    selectedId = autoProfileId,
                    profiles = savedProfiles,
                    onSelect = onAutoProfileChange,
                )
                Text(
                    if (autoProfileId == null) {
                        "Notify only — no profile will be auto-applied."
                    } else {
                        "The selected profile will be applied when the alert fires."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProfilePickerDropdown(
    selectedId: String?,
    profiles: List<UserProfile>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = profiles.firstOrNull { it.id == selectedId }?.name ?: "None"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selectedName, modifier = Modifier.weight(1f))
            Text("▾", style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None — notify only") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        onSelect(profile.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ── Segmented toggle (Arsenal-styled) ─────────────────────────────────────────

@Composable
private fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = androidx.compose.ui.graphics.Color(0xFF0C0C10)
    val accentColor = AccentBar.Blue
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .background(bgColor, RoundedCornerShape(4.dp)),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) accentColor.copy(alpha = 0.18f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) accentColor
                    else AccentBar.Neutral,
                    letterSpacing = 0.06.sp,
                )
            }
        }
    }
}

// ── Dialogs ────────────────────────────────────────────────────────────────────

@Composable
private fun FactoryConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val required = "RESTORE"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore to first-launch state?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Text(
                    "Every CPU/GPU cap, governor, and vendor key that was recorded at first launch will be written back. Anything not in the baseline (manual sysfs writes, new vendor keys, profiles created after first launch) is unaffected.",
                )
                Spacer(Modifier.height(Spacing.group))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type \"$required\" to confirm") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = typed.trim() == required) { Text("Restore") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RestoreSummaryDialog(
    summary: io.github.mayusi.calibratesoc.data.baseline.FactoryRestorer.RestoreSummary,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore complete") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Text("${summary.ok} of ${summary.total} tunables restored.", fontFamily = FontFamily.Monospace)
                if (summary.denied > 0) {
                    Text("${summary.denied} skipped (current privilege tier can't write them).", style = MaterialTheme.typography.bodySmall)
                }
                if (summary.failed > 0) {
                    Text("${summary.failed} failed.", color = AccentBar.Red, style = MaterialTheme.typography.bodySmall)
                    summary.errors.take(3).forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
    )
}

/**
 * Experimental features toggle. Off by default. ON requires the
 * user to type "ENABLE" — same idiom as the first-OC ack — so the
 * user definitively opts in. Unlocks the HUD ± steppers and any
 * future "this might brick your day" features.
 */
@Composable
private fun ExperimentalCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    var pendingConfirm by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf("") }
    ArsenalPanel(
        accent = if (enabled) AccentBar.Red else AccentBar.Neutral,
        title = "EXPERIMENTAL FEATURES",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (enabled) "ON — risky features unlocked" else "OFF — safe defaults only",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) AccentBar.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        confirmText = ""
                        pendingConfirm = true
                    } else {
                        onToggle(false)
                    }
                },
            )
        }
        Text(
            "Unlocks the HUD ± clock steppers and other features that " +
                "can put the device in a bad state if used without root. " +
                "Anything that happens with experimental features ON is on you, not the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (pendingConfirm) {
        AlertDialog(
            onDismissRequest = { pendingConfirm = false },
            title = { Text("Enable experimental features?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                    Text(
                        "Experimental features include HUD ± clock steppers, " +
                                "direct sysfs writes, and other tools that need root " +
                                "or a rooted-tier permission to work.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "If you turn them on without root, they generate scripts " +
                                "you have to run through your device's settings. If you " +
                                "DO have root and you do something stupid, it's on you — " +
                                "the app won't stop you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Type ENABLE below to continue:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it.uppercase() },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onToggle(true)
                        pendingConfirm = false
                    },
                    enabled = confirmText.trim() == "ENABLE",
                ) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Master toggle for the zero-tap "auto-configure known games" feature.
 *
 * Default ON (the headline feature). Honest copy: explains exactly what the app
 * does on its own, that it's a starting default (not optimal), and that every
 * auto-config is announced with an Undo. Turning it off is a plain switch — no
 * typed-confirm, because OFF is the SAFE direction (it just stops the app acting
 * on its own).
 */
@Composable
private fun AutoConfigKnownGamesCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ArsenalPanel(
        accent = if (enabled) AccentBar.Emerald else AccentBar.Neutral,
        title = "AUTO-CONFIGURE KNOWN GAMES",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (enabled) "ON — sets up new games for you" else "OFF — never auto-tunes",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) AccentBar.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "When a game Calibrate recognises launches for the first time and you " +
                "haven't set it up yourself, the app picks a sensible starting tune " +
                "for it automatically and tells you with a notification you can undo. " +
                "It's a starting point based on the game's type, not a guaranteed-best " +
                "setup, and it never overrides tunes you set yourself. Turn this off to " +
                "stop the app auto-configuring any game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Quick-glance setup checklist. Mirrors the first-launch wizard
 * items so users can re-grant after a wipe / reboot without
 * restarting the wizard. Each row polls its own status every second
 * so the ✓ appears immediately when the user returns from a system
 * grant page.
 */
@Composable
private fun SetupChecklistCard() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick = System.currentTimeMillis()
        }
    }
    val items = io.github.mayusi.calibratesoc.ui.setup.AllSetupItemsWithAdvanced
    val statuses by remember(tick) {
        androidx.compose.runtime.derivedStateOf {
            items.associate { it.id to it.isDone(context) }
        }
    }
    ArsenalPanel(accent = AccentBar.Blue, title = "SETUP CHECKLIST") {
        Text(
            "These are the perms + toggles the app needs. Tap any row to open the matching system page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.dense))
        items.forEach { item ->
            val applicable = item.isApplicable(context)
            val done = statuses[item.id] ?: false
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!applicable) {
                    Text(
                        "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentBar.Neutral,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Not available on this device",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentBar.Neutral,
                        )
                    }
                } else {
                    Text(
                        if (done) "✓" else "○",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (done) AccentBar.Emerald else AccentBar.Neutral,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White)
                        Text(
                            if (done) "Granted" else "Not yet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { item.launch(context) }) {
                        Text(if (done) "Re-open" else "Grant")
                    }
                }
            }
        }
    }
}
