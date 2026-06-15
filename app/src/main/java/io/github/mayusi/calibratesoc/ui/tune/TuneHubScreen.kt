package io.github.mayusi.calibratesoc.ui.tune

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.github.mayusi.calibratesoc.ui.autotdp.AutoTdpScreen
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.profiles.ProfilesScreen
import io.github.mayusi.calibratesoc.ui.tune.advanced.AdvancedTuningScreen

/**
 * Tune Hub — Direction-C tabbed surface.
 *
 * Hosts four first-class sub-surfaces via an Arsenal segmented control:
 *   0  PRESETS   — CPU/GPU sliders + community presets (existing TuneScreen)
 *   1  ADVANCED  — governor tunables, custom sysfs (existing AdvancedTuningScreen)
 *   2  AUTO TDP  — dynamic power management (existing AutoTdpScreen)
 *   3  PROFILES  — saved tunes + per-app overrides (existing ProfilesScreen)
 *
 * AutoTDP and Advanced Tuning are now THEIR OWN TOP-LEVEL DESTINATIONS
 * accessible from the bottom bar → Tune tab. They are no longer buried
 * below-the-fold buttons in TuneScreen.
 *
 * @param initialTab  0-based index to open directly (for deep-link jumps).
 * @param onOpenHistory  callback forwarded to TuneScreen's History button.
 */
@Composable
fun TuneHubScreen(
    initialTab: Int = 0,
    onOpenHistory: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

    val tabs = listOf("PRESETS", "ADVANCED", "AUTO TDP", "PROFILES")

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Arsenal Segmented Tab Control ──────────────────────────────────
        ArsenalTabRow(
            tabs = tabs,
            selectedIndex = selectedTab,
            accent = AccentBar.Red,
            onTabSelected = { selectedTab = it },
        )

        // ── Tab content ────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> TuneScreen(
                    onOpenHistory = onOpenHistory,
                    // In hub mode the Advanced and AutoTDP buttons inside TuneScreen
                    // switch tabs instead of navigating to separate routes.
                    onOpenAdvancedTuning = { selectedTab = 1 },
                    onOpenAutoTdp = { selectedTab = 2 },
                )
                1 -> AdvancedTuningScreen(onBack = { selectedTab = 0 })
                2 -> AutoTdpScreen(onBack = { selectedTab = 0 })
                3 -> ProfilesScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Arsenal segmented tab row
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Direction-C angular segmented tab bar.
 *
 * - Near-black (#0C0C10) background.
 * - Active tab: accent bottom underline (3 dp), white text, no rounded fill.
 * - Inactive tabs: muted gray text (#6B7280).
 * - Uppercase, tight letter-spacing, monospace-weight numeric labels.
 *
 * The overall container has a subtle 1 dp bottom border in the accent color
 * so the control reads as a hard boundary between nav and content, matching
 * the Direction-C "hardware panel" language.
 *
 * @param tabs          List of tab label strings (rendered uppercase).
 * @param selectedIndex Index of the currently active tab.
 * @param accent        Accent color for the active indicator.
 * @param onTabSelected Callback when the user taps a tab.
 */
@Composable
fun ArsenalTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    accent: Color,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor   = Color(0xFF0C0C10)
    val inactiveText = Color(0xFF6B7280)
    val dividerColor = Color.White.copy(alpha = 0.06f)
    val indicatorH = 3.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .border(
                width = 0.5.dp,
                color = dividerColor,
                shape = RoundedCornerShape(0.dp),
            ),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                val isActive = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(0.dp))
                        .clickable { onTabSelected(index) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) Color.White else inactiveText,
                        letterSpacing = 0.07.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }

        // Active indicator row — accent underline beneath active tab
        Row(modifier = Modifier.fillMaxWidth().height(indicatorH)) {
            tabs.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(indicatorH)
                        .background(
                            if (index == selectedIndex) accent else Color.Transparent,
                        ),
                )
            }
        }
    }
}
