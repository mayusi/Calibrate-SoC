package io.github.mayusi.calibratesoc.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.mayusi.calibratesoc.ui.benchmark.BenchmarkScreen
import io.github.mayusi.calibratesoc.ui.capability.CapabilityReportScreen
import io.github.mayusi.calibratesoc.ui.dashboard.DashboardScreen
import io.github.mayusi.calibratesoc.ui.hardware.HardwareScreen
import io.github.mayusi.calibratesoc.ui.profiles.ProfilesScreen
import io.github.mayusi.calibratesoc.ui.settings.SettingsScreen
import io.github.mayusi.calibratesoc.ui.tune.TuneHistoryScreen
import io.github.mayusi.calibratesoc.ui.tune.TuneScreen

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
fun CalibrateSocApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Destination.Dashboard.route

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
        NavHost(
            navController = nav,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Dashboard.route) { DashboardScreen() }
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
        }
    }
}
