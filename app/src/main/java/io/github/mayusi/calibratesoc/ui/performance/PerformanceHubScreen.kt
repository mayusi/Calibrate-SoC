package io.github.mayusi.calibratesoc.ui.performance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.mayusi.calibratesoc.ui.benchmark.BenchmarkScreen
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.gameaware.GameAwareScreen
import io.github.mayusi.calibratesoc.ui.insights.InsightsScreen
import io.github.mayusi.calibratesoc.ui.session.SessionsScreen
import io.github.mayusi.calibratesoc.ui.stability.StabilityScreen
import io.github.mayusi.calibratesoc.ui.tune.ArsenalTabRow

/**
 * Performance Hub — Direction-C tabbed surface.
 *
 * Hosts five first-class sub-surfaces via an Arsenal segmented control:
 *   0  BENCHMARK  — Quick/Full bench + score history
 *   1  STABILITY  — Stress test + throttle analysis
 *   2  SESSIONS   — Per-game session log + App Stats
 *   3  INSIGHTS   — Cross-session analytics (battery saved, temp trend, best profiles)
 *   4  GAME-AWARE — Per-game AutoTDP + profile bindings
 *
 * @param initialTab  0-based index to open directly (for deep-link jumps from Dashboard).
 * @param onOpenSessionDetail  Forwarded to SessionsScreen when a session row is tapped.
 * @param onOpenAppStats       Forwarded to SessionsScreen's "App Stats" action.
 */
@Composable
fun PerformanceHubScreen(
    initialTab: Int = 0,
    onOpenSessionDetail: (Long) -> Unit = {},
    onOpenAppStats: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

    val tabs = listOf("BENCHMARK", "STABILITY", "SESSIONS", "INSIGHTS", "GAMES")

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Arsenal Segmented Tab Control ──────────────────────────────────
        ArsenalTabRow(
            tabs = tabs,
            selectedIndex = selectedTab,
            accent = AccentBar.Purple,   // GPU/performance accent
            onTabSelected = { selectedTab = it },
        )

        // ── Tab content ────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> BenchmarkScreen()
                1 -> StabilityScreen()
                2 -> SessionsScreen(
                    onOpenDetail = onOpenSessionDetail,
                    onOpenAppStats = onOpenAppStats,
                    onBack = { /* no-op: Sessions is a hub tab, not a pushed screen */ },
                )
                3 -> InsightsScreen()
                4 -> GameAwareScreen()
            }
        }
    }
}
