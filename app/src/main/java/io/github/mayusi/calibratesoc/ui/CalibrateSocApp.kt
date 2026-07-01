package io.github.mayusi.calibratesoc.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.mayusi.calibratesoc.data.update.UpdateInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.ui.capability.CapabilityReportScreen
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.dashboard.DashboardScreen
import io.github.mayusi.calibratesoc.ui.hardware.HardwareScreen
import io.github.mayusi.calibratesoc.ui.performance.PerformanceHubScreen
import io.github.mayusi.calibratesoc.ui.session.AppStatsScreen
import io.github.mayusi.calibratesoc.ui.session.SessionDetailScreen
import io.github.mayusi.calibratesoc.ui.settings.SettingsScreen
import io.github.mayusi.calibratesoc.ui.tune.TuneHubScreen
import io.github.mayusi.calibratesoc.ui.update.UpdateBannerViewModel
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Top-level Compose root — Direction C v2 IA.
 *
 * Five-tab bottom nav (Arsenal dark bar, accent underline on active item):
 *   Dashboard   — live telemetry home
 *   Tune        — hub [Presets | Advanced | AutoTDP | Profiles]
 *   Performance — hub [Benchmark | Stability | Sessions]
 *   Hardware    — SoC/RAM/storage identify + speed-test
 *   Settings    — privilege badge + grant flows + About
 *
 * AutoTDP and Advanced Tuning are now FIRST-CLASS SURFACES inside the
 * Tune hub. They are no longer sub-screens reachable only by scrolling
 * past sliders — they have dedicated tabs in the Arsenal segmented
 * control at the top of the Tune hub.
 *
 * Deep-links (e.g. Dashboard "AutoTDP" strip) navigate to the Tune
 * route with an initialTab argument so the correct tab is pre-selected.
 *
 * Off-nav screens (DeviceInfo, SessionDetail, AppStats) remain reachable
 * via the NavHost; the bottom bar just stays hidden on those routes.
 */
@Composable
fun CalibrateSocApp(
    bannerVm: UpdateBannerViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Destination.Dashboard.route

    val pendingUpdate by bannerVm.pendingUpdate.collectAsStateWithLifecycle()

    // Whether the bottom bar should be visible.
    // Uses prefix-based matching so deep-link routes like "tune?tab=2" are
    // correctly treated as bar-visible (see Destination.isBottomBarVisible).
    val showBottomBar = Destination.isBottomBarVisible(current)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                ArsenalBottomBar(
                    current = current,
                    onNavigate = { dest ->
                        if (current != dest.route) {
                            nav.navigate(dest.route) {
                                popUpTo(Destination.Dashboard.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(
                navController = nav,
                startDestination = Destination.Dashboard.route,
            ) {
                // ── 1. Dashboard ──────────────────────────────────────────
                composable(Destination.Dashboard.route) {
                    DashboardScreen(
                        onOpenSessions = {
                            nav.navigate(Destination.Performance.route) {
                                popUpTo(Destination.Dashboard.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenAutoTdp = {
                            // Deep-link: Tune hub pre-selected to AutoTDP tab (index 2)
                            nav.navigate("${Destination.Tune.route}?tab=2") {
                                popUpTo(Destination.Dashboard.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }

                // ── 2. Tune Hub ───────────────────────────────────────────
                // Default entry (tab=0 Presets)
                composable(Destination.Tune.route) {
                    TuneHubScreen(
                        initialTab = 0,
                        onOpenHistory = {
                            nav.navigate(Destination.TuneHistory.route)
                        },
                    )
                }
                // Deep-link variant with tab argument
                composable("${Destination.Tune.route}?tab={tab}") { backStackEntry ->
                    val tab = backStackEntry.arguments?.getString("tab")?.toIntOrNull() ?: 0
                    TuneHubScreen(
                        initialTab = tab,
                        onOpenHistory = {
                            nav.navigate(Destination.TuneHistory.route)
                        },
                    )
                }

                // ── 3. Tune Hub sub-screens (off-nav, pushable) ───────────
                // These routes exist so external deep-links (e.g. from the
                // Dashboard strip) can also land on a specific surface directly.
                composable(Destination.AdvancedTuning.route) {
                    TuneHubScreen(
                        initialTab = 1,
                        onOpenHistory = { nav.navigate(Destination.TuneHistory.route) },
                    )
                }
                composable(Destination.AutoTdp.route) {
                    TuneHubScreen(
                        initialTab = 2,
                        onOpenHistory = { nav.navigate(Destination.TuneHistory.route) },
                    )
                }
                composable(Destination.Profiles.route) {
                    // Profiles moved to tab index 4 when Fan Curve was inserted at 3.
                    TuneHubScreen(
                        initialTab = 4,
                        onOpenHistory = { nav.navigate(Destination.TuneHistory.route) },
                    )
                }
                composable(Destination.TuneHistory.route) {
                    io.github.mayusi.calibratesoc.ui.tune.TuneHistoryScreen()
                }

                // ── 4. Performance Hub ────────────────────────────────────
                composable(Destination.Performance.route) {
                    PerformanceHubScreen(
                        initialTab = 0,
                        onOpenSessionDetail = { id -> nav.navigate(Destination.SessionDetail.route(id)) },
                        onOpenAppStats = { nav.navigate(Destination.AppStats.route) },
                    )
                }
                // Stability deep-link route (tab 1)
                composable(Destination.Stability.route) {
                    PerformanceHubScreen(
                        initialTab = 1,
                        onOpenSessionDetail = { id -> nav.navigate(Destination.SessionDetail.route(id)) },
                        onOpenAppStats = { nav.navigate(Destination.AppStats.route) },
                    )
                }
                // Sessions deep-link route (tab 2)
                composable(Destination.Sessions.route) {
                    PerformanceHubScreen(
                        initialTab = 2,
                        onOpenSessionDetail = { id -> nav.navigate(Destination.SessionDetail.route(id)) },
                        onOpenAppStats = { nav.navigate(Destination.AppStats.route) },
                    )
                }
                // Benchmark deep-link route (tab 0 = same as Performance default)
                composable(Destination.Benchmark.route) {
                    PerformanceHubScreen(
                        initialTab = 0,
                        onOpenSessionDetail = { id -> nav.navigate(Destination.SessionDetail.route(id)) },
                        onOpenAppStats = { nav.navigate(Destination.AppStats.route) },
                    )
                }
                // Insights deep-link route (tab 3)
                composable(Destination.Insights.route) {
                    PerformanceHubScreen(
                        initialTab = 3,
                        onOpenSessionDetail = { id -> nav.navigate(Destination.SessionDetail.route(id)) },
                        onOpenAppStats = { nav.navigate(Destination.AppStats.route) },
                    )
                }
                // Game-Aware deep-link route (tab 4)
                composable(Destination.GameAware.route) {
                    PerformanceHubScreen(
                        initialTab = 4,
                        onOpenSessionDetail = { id -> nav.navigate(Destination.SessionDetail.route(id)) },
                        onOpenAppStats = { nav.navigate(Destination.AppStats.route) },
                    )
                }

                // NOTE: The Game Tunes hub has NO nav-graph entry. It is opened
                // inline from ProfilesScreen via local state (gameTuneApp = pkg),
                // which is the single live path. A composable(Destination.GameTunes)
                // entry used to live here but had ZERO navigate() call sites — it
                // was unreachable dead routing and has been removed (along with the
                // Destination.GameTunes object). See ProfilesScreen for the real path.

                // ── 6. Off-nav deep-link screens ──────────────────────────
                composable(Destination.SessionDetail.route) { backStackEntry ->
                    val sessionId = backStackEntry.arguments
                        ?.getString("sessionId")?.toLongOrNull()
                        ?: return@composable
                    SessionDetailScreen(
                        sessionId = sessionId,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Destination.AppStats.route) {
                    AppStatsScreen(onBack = { nav.popBackStack() })
                }

                // ── 6. Hardware ───────────────────────────────────────────
                composable(Destination.Hardware.route) { HardwareScreen() }

                // ── 7. Settings + DeviceInfo ──────────────────────────────
                composable(Destination.Settings.route) {
                    SettingsScreen(
                        onOpenDeviceInfo = { nav.navigate(Destination.DeviceInfo.route) },
                    )
                }
                composable(Destination.DeviceInfo.route) { CapabilityReportScreen() }
            }

            // ── App-level "update available" banner ───────────────────────
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

// ─────────────────────────────────────────────────────────────────────────────
//  Arsenal Bottom Navigation Bar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Direction-C bottom navigation bar.
 *
 * Design:
 *   - Near-black background (#0C0C10), 1 dp top border in white/6 %.
 *   - Active item: [AccentBar.Red] indicator bar at the top (3 dp),
 *     white icon + label, bold weight.
 *   - Inactive items: [0xFF6B7280] muted gray, lighter weight.
 *   - Labels: uppercase, tight letter-spacing.
 *   - No Material [NavigationBar] — custom to enforce the angular C aesthetic.
 *
 * Hub routes (Tune sub-tabs, Performance sub-tabs) are resolved back to their
 * parent hub bottom-bar item so the active indicator stays correct when the
 * user is on a sub-surface inside a hub.
 *
 * @param current    The current route string from [NavBackStackEntry].
 * @param onNavigate Called with the destination when the user taps a tab.
 */
@Composable
private fun ArsenalBottomBar(
    current: String,
    onNavigate: (Destination) -> Unit,
) {
    val bgColor      = Color(0xFF0C0C10)
    val borderColor  = Color.White.copy(alpha = 0.06f)
    val activeAccent = AccentBar.Red
    val activeText   = Color.White
    val inactiveText = Color(0xFF6B7280)
    val indicatorH   = 3.dp
    val barHeight    = 60.dp

    // Resolve which bottom-bar item is "active" given the current route.
    // Delegates to the shared pure helper in Destination so the logic is
    // testable and consistent with isBottomBarVisible.
    val activeDest = Destination.activeDestFor(current)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bottom_nav")
            .background(bgColor)
            .border(
                width = 0.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(0.dp),
            ),
    ) {
        // Active indicator — accent stripe at the very top of the bar,
        // spanning only the active tab's column.
        Row(
            modifier = Modifier.fillMaxWidth().height(indicatorH),
        ) {
            Destination.bottomBar.forEach { dest ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(indicatorH)
                        .background(
                            if (dest == activeDest) activeAccent else Color.Transparent,
                        ),
                )
            }
        }

        // Tab items
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Destination.bottomBar.forEach { dest ->
                val isActive = dest == activeDest
                val itemColor = if (isActive) activeText else inactiveText
                val icon: ImageVector = when (dest) {
                    Destination.Dashboard   -> Icons.Outlined.Dashboard
                    Destination.Tune        -> Icons.Outlined.Tune
                    Destination.Performance -> Icons.Outlined.BarChart
                    Destination.Hardware    -> Icons.Outlined.Memory
                    Destination.Settings    -> Icons.Outlined.Settings
                    // Remaining destinations are not in the bottom bar;
                    // these arms keep the `when` exhaustive.
                    else -> Icons.Outlined.Speed
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(barHeight)
                        .testTag("nav_${dest.route}")
                        .clickable { onNavigate(dest) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = dest.label,
                        tint = itemColor,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = dest.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = itemColor,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 0.04.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  UpdateAvailableBanner — unchanged from v1; kept in this file for locality
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Non-modal "update available" banner shown at the top of the main scaffold.
 * Three actions: "Update" (routes to Settings), "Later" (snooze 7d), "×" (dismiss tag).
 */
@Composable
private fun UpdateAvailableBanner(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.dense),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 4.dp),
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
