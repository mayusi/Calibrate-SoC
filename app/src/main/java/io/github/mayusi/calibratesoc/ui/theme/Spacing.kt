package io.github.mayusi.calibratesoc.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing tokens for the CalibrateSoC design system.
 *
 * Usage pattern:
 *   - [screen]  — outer padding for full-screen LazyColumn / Column
 *   - [card]    — inner padding inside a SectionCard
 *   - [item]    — vertical space between list rows / between sibling items
 *   - [group]   — tighter gap between items within a logical group
 *   - [dense]   — minimal gap (icon + text pairs, chip rows)
 *
 * These are the tokens to use in Dashboard, Hardware, and all new
 * components. Do NOT force-migrate every magic number app-wide — adopt
 * in files you touch so there is a clear reference pattern to follow.
 */
object Spacing {
    val screen = 16.dp
    val card = 16.dp
    val item = 12.dp
    val group = 8.dp
    val dense = 4.dp
}
