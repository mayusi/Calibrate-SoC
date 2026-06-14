package io.github.mayusi.calibratesoc.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.mayusi.calibratesoc.data.update.UpdateInfo
import io.github.mayusi.calibratesoc.ui.benchmark.BenchmarkScreen
import io.github.mayusi.calibratesoc.ui.capability.CapabilityReportScreen
import io.github.mayusi.calibratesoc.ui.dashboard.DashboardScreen
import io.github.mayusi.calibratesoc.ui.hardware.HardwareScreen
import io.github.mayusi.calibratesoc.ui.profiles.ProfilesScreen
import io.github.mayusi.calibratesoc.ui.session.SessionDetailScreen
import io.github.mayusi.calibratesoc.ui.session.SessionsScreen
import io.github.mayusi.calibratesoc.ui.settings.SettingsScreen
import io.github.mayusi.calibratesoc.ui.tune.TuneHistoryScreen
import io.github.mayusi.calibratesoc.ui.tune.TuneScreen
import io.github.mayusi.calibratesoc.ui.update.UpdateBannerViewModel
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Top-level Compose root. Six-tab bottom nav:
 *   Dashboard — live telemetry (home)
 *   Tune      — sliders + universal preset list
 *   Profiles  — saved tunes + per-app override map
 *   Benchmark — Quick/Full bench + history + compare drawer
 *   Hardware  — identify SoC/RAM/storage + speed-test them
 *   Settings  — privilege badge + Accessibility grant + About
 *
 * Device Info (the Phase 1 capability dump) lives off-nav as a deep
 * link from Settings. It's a power-user diagnostic, not part of the
 * main user journey.
 */
@Composable
fun CalibrateSocApp(
    bannerVm: UpdateBannerViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Destination.Dashboard.route

    val pendingUpdate by bannerVm.pendingUpdate.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Destination.bottomBar.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest.route,
                        onClick = {
                            if (current != dest.route) {
                                nav.navigate(dest.route) {
                                    popUpTo(Destination.Dashboard.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (dest) {
                                    Destination.Dashboard -> Icons.Outlined.Speed
                                    Destination.Tune -> Icons.Outlined.Tune
                                    Destination.Profiles -> Icons.Outlined.Bookmarks
                                    Destination.Benchmark -> Icons.Outlined.BarChart
                                    Destination.Hardware -> Icons.Outlined.Memory
                                    Destination.Settings -> Icons.Outlined.Settings
                                    Destination.DeviceInfo -> Icons.Outlined.Info
                                    Destination.TuneHistory -> Icons.Outlined.Bookmarks
                                    // Sessions/SessionDetail are not in the bottom bar;
                                    // these arms keep the sealed-class when exhaustive.
                                    Destination.Sessions -> Icons.Outlined.BarChart
                                    Destination.SessionDetail -> Icons.Outlined.BarChart
                                },
                                contentDescription = dest.label,
                            )
                        },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(
                navController = nav,
                startDestination = Destination.Dashboard.route,
            ) {
                composable(Destination.Dashboard.route) {
                    DashboardScreen(
                        onOpenSessions = { nav.navigate(Destination.Sessions.route) },
                    )
                }
                composable(Destination.Tune.route) {
                    TuneScreen(onOpenHistory = { nav.navigate(Destination.TuneHistory.route) })
                }
                composable(Destination.TuneHistory.route) { TuneHistoryScreen() }
                composable(Destination.Profiles.route) { ProfilesScreen() }
                composable(Destination.Benchmark.route) { BenchmarkScreen() }
                composable(Destination.Hardware.route) { HardwareScreen() }
                composable(Destination.Settings.route) {
                    SettingsScreen(onOpenDeviceInfo = { nav.navigate(Destination.DeviceInfo.route) })
                }
                composable(Destination.DeviceInfo.route) { CapabilityReportScreen() }
                composable(Destination.Sessions.route) {
                    SessionsScreen(
                        onOpenDetail = { id -> nav.navigate(Destination.SessionDetail.route(id)) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Destination.SessionDetail.route) { backStack ->
                    val sessionId = backStack.arguments?.getString("sessionId")?.toLongOrNull() ?: return@composable
                    SessionDetailScreen(
                        sessionId = sessionId,
                        onBack = { nav.popBackStack() },
                    )
                }
            }

            // ── App-level "update available" banner ───────────────────────────
            // Slides in from the top; non-modal; dismissible via three actions:
            //   "Update" → navigates to Settings (existing download/install path)
            //   "Later"  → snoozes for 7 days
            //   "×"      → dismisses this tag permanently (re-shows on newer tag)
            AnimatedVisibility(
                visible = pendingUpdate != null,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
            ) {
                pendingUpdate?.let { info ->
                    UpdateAvailableBanner(
                        info = info,
                        onUpdate = {
                            bannerVm.consume()
                            // Navigate to Settings → the existing Updates card handles download
                            if (current != Destination.Settings.route) {
                                nav.navigate(Destination.Settings.route) {
                                    popUpTo(Destination.Dashboard.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        onLater = { bannerVm.snooze() },
                        onDismiss = { bannerVm.dismiss() },
                    )
                }
            }
        }
    }
}

/**
 * Non-modal "update available" banner shown at the top of the main scaffold.
 * Matches the style of the existing [WhatsNewBanner] in SettingsScreen
 * (primaryContainer, rounded card, same action-button idiom).
 *
 * Three actions:
 *   "Update" (FilledTonalButton) → routes to Settings updater card.
 *   "Later"  (TextButton)        → snoozes for 7 days.
 *   "×"      (IconButton)        → dismisses this tag permanently.
 */
@Composable
private fun UpdateAvailableBanner(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.dense),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.card, vertical = Spacing.group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.group))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Update available — ${info.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Never installs automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            FilledTonalButton(
                onClick = onUpdate,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text("Update") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onLater) {
                Text("Later", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Dismiss update banner",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
