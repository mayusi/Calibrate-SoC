package io.github.mayusi.calibratesoc.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.DecisionRecord
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.WorkloadContext
import io.github.mayusi.calibratesoc.ui.autotdp.GoalProfileUi
import io.github.mayusi.calibratesoc.ui.components.CutCorner
import io.github.mayusi.calibratesoc.ui.components.CutCornerShape
import io.github.mayusi.calibratesoc.ui.components.StatusPill

// ── Approved HUD palette ──────────────────────────────────────────────────────
// The user-approved gaming-HUD tokens. Solid near-black surfaces, a bold red
// Arsenal accent edge, and category-keyed metric colours (CPU blue / GPU purple /
// BAT emerald / power amber) so the dense bar reads at a glance over busy
// gameplay. Numbers render in monospace so the HUD reads as a precise tech tool.

// Solid panel/bar backing — near-black so the surface always wins against bright
// game content behind it (high opacity floor + this dark base = no bleed-through).
private val HudBgSolid   = Color(0xFF0D1320)
// Raised surface for the FPS hero cell, header bar, trailing controls and the
// AutoTDP footer — one step lighter than the base so those zones read as framed.
private val HudRaised    = Color(0xFF11192A)
// Per-cell fill for the COMPACT bar's CPU/GPU/BAT metric cells. Same family as
// [HudRaised] but a hair deeper so each metric reads as its own recessed boxed
// chip (distinct tiles, never flat text on the bar). 0xFF0E1626 sits between the
// bar base (0xFF0D1320) and the raised hero (0xFF11192A).
private val HudCellBg    = Color(0xFF0E1626)
// Hairline divider between cells / sections.
private val HudBorder    = Color(0xFF1C2638)
// Sparkline / per-core track backing.
private val HudBarBg     = Color(0xFF18233A)

// Effective-alpha floor for the COMPACT bar only. The user's opacity slider goes
// as low as 0.10, which left the dense bar nearly invisible. The bar floats over
// fast game content and must read as a SOLID intentional strip, so we clamp its
// effective alpha to a high readable minimum. The user can still dial it *down*
// from fully opaque, just never below glanceability.
private const val COMPACT_BAR_MIN_ALPHA = 0.88f

// Text tones.
private val HudValue     = Color(0xFFDFE6F2) // primary value text
private val HudMuted     = Color(0xFF7D8AA0) // muted sub-text / labels
private val HudLabel     = HudMuted          // alias: section/cell label tone
private val HudDim       = Color(0xFF4A586E) // dimmest (disabled / parked / "—")
// Parked-core dim fill (per the approved per-core spec).
private val HudParked    = Color(0xFF384358)

// Accents.
private val HudRed       = Color(0xFFE0455E) // accent edge + throttle + record
private val HudBlue      = Color(0xFF5B8DEF) // CPU
private val HudPurple    = Color(0xFFA78BFA) // GPU
private val HudAmber     = Color(0xFFF0A93E) // power / warm temp
private val HudEmerald   = Color(0xFF5FD0A8) // FPS / battery / good / live

// AutoTDP footer surface (slightly green-tinted near-black + emerald-tinted edge).
private val HudAutoTdpBg     = Color(0xFF0A1A14)
private val HudAutoTdpBorder = Color(0xFF14352A)

// Thickness of the bold red Arsenal accent edge on the leading side of both the
// compact bar and the verbose panel.
private val ACCENT_EDGE = 3.dp

// ── cool → hot temperature colour mapping ─────────────────────────────────────
// Maps the pure [HudDisplayUtils.TempTier] (thresholds 70 / 90 °C) onto the
// Arsenal palette so every temperature on the HUD is coloured by magnitude:
//   COOL → emerald   WARM → amber   HOT → red   NONE (null) → muted "—" colour
// The tier decision is pure + unit-tested; this is only the visual binding.
private fun tempColor(tempC: Float?): Color = when (HudDisplayUtils.tempTier(tempC)) {
    HudDisplayUtils.TempTier.COOL -> HudEmerald
    HudDisplayUtils.TempTier.WARM -> HudAmber
    HudDisplayUtils.TempTier.HOT  -> HudRed
    HudDisplayUtils.TempTier.NONE -> HudDim
}

// ─────────────────────────────────────────────────────────────────────────────
//  Root composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Floating performance HUD overlay — rebuilt to the approved design.
 *
 * COMPACT (always-visible): a horizontal strip of LABELED CELLS on a solid
 * near-black bg with a bold red Arsenal accent edge. Left→right:
 *   [FPS hero | CPU | GPU | BAT | controls].
 * The FPS hero is a big emerald number ONLY when a REAL game framerate exists;
 * otherwise a small muted "60Hz" tag. Whole cells DROP when their data is
 * unavailable (never a dead "—" cell). Tap the expand icon → VERBOSE.
 *
 * VERBOSE (tap to expand): a ~330dp structured card, solid bg, accent edge:
 *   Header → FPS hero (+ frame-ms) → 2×2 metric grid → THERMAL row →
 *   PER-CORE bars → AutoTDP proof footer → opacity + display-Hz controls.
 * The card scrolls so every control stays reachable on short screens.
 *
 * All callbacks are wired in [OverlayService.attachOverlay]. Only the existing
 * [onToggleAutoTdp] and [onSetAutoTdpProfile] callbacks touch AutoTDP -- no new
 * controller calls are introduced here. The COMPACT↔VERBOSE swap is driven by
 * [onCycleLayout], which flips the persisted [HudProfile] (COMPACT⇄VERBOSE).
 */
@Composable
fun HudOverlayContent(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onApplyProfile: (String) -> Unit,
    onCycleNextProfile: () -> Unit,
    onToggleRecord: () -> Unit,
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
    onSetRefreshHz: (Float?) -> Unit,
    onCycleHudSize: () -> Unit,
    onSetOpacity: (Float) -> Unit,
    onClose: () -> Unit,
) {
    when (state.profile) {
        HudProfile.COMPACT -> HudCompactBar(
            state = state,
            onCycleLayout = onCycleLayout,
            onClose = onClose,
        )
        HudProfile.VERBOSE -> HudFullPanel(
            state = state,
            onCycleLayout = onCycleLayout,
            onToggleRecord = onToggleRecord,
            onApplyProfile = onApplyProfile,
            onCycleNextProfile = onCycleNextProfile,
            onToggleAutoTdp = onToggleAutoTdp,
            onSetAutoTdpProfile = onSetAutoTdpProfile,
            onSetRefreshHz = onSetRefreshHz,
            onCycleHudSize = onCycleHudSize,
            onSetOpacity = onSetOpacity,
            onClose = onClose,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared live-clock helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A remembered "now" timestamp that advances every second while composed.
 *
 * The HUD's AutoTDP heartbeat verdict ("live" vs "stalled") compares the last
 * applied epoch against *now*. If `now` were captured once at composition it would
 * freeze whenever no other field recomposed, so a daemon that quietly stalls would
 * keep showing a fake "live" — exactly the dishonesty we must avoid. This ticker
 * keeps the verdict truthful. Mirrors the pattern in
 * [io.github.mayusi.calibratesoc.ui.autotdp] AutoTdpProofOfEffectPanels.
 */
@Composable
private fun rememberHudNowMs(): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    return nowMs
}

// ─────────────────────────────────────────────────────────────────────────────
//  COMPACT mode -- horizontal strip of labeled cells
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The always-visible compact bar — a horizontal strip of LABELED CELLS on a
 * solid near-black bg with a bold red Arsenal accent edge, sized to wrap its
 * content. Left → right:
 *
 *   REAL game FPS:  ‖ [58 fps] │ CPU 2.81G·68° / 87% │ GPU 540M·62° / 74% │ BAT 87%·31° / 6.4W │ AUTO·TDP  ⤢ ✕
 *   No game (Hz):   ‖ [60Hz]   │ CPU … │ GPU … │ BAT … │ …
 *
 * FPS-hero rule: a BIG emerald number ONLY when [HudUiState.gameFpsIsReal] &&
 * gameFps != null; otherwise a small muted "60Hz" tag so a meaningless
 * panel-refresh value never dominates.
 *
 * Cell-drop rules (honesty + density — never a dead "—" cell):
 *  - GPU cell dropped whole when clock AND temp AND load are all unavailable;
 *    if load is unavailable but clock is, the load sub-line is dropped.
 *  - BAT power sub-line ("6.4 W") dropped when power is null/0 (keeps %+temp).
 *
 * Trailing controls cell (raised bg): an AUTO·TDP pill (only while running),
 * a THROT pill (only while throttling now), then expand + close icons.
 */
@Composable
private fun HudCompactBar(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onClose: () -> Unit,
) {
    // Live 1 s clock so the heartbeat "live/stalled" verdict keeps ticking even
    // when no other field recomposes. (Previously captured once at composition,
    // which froze the verdict.) Mirrors AutoTdpScreen.AutoTdpProofOfEffectPanels.
    val nowMs = rememberHudNowMs()
    val throttling = HudDisplayUtils.isThrottlingNow(state.coolingDeviceMaxState)
    val a11y = buildString {
        append(if (state.gameFps != null) "${state.gameFps} ${if (state.gameFpsIsReal) "FPS" else "Hz"}" else "FPS unavailable")
        append(", CPU ${HudDisplayUtils.formatGhzFromMhz(state.cpuMaxMhz.takeIf { it > 0 })} ${state.cpuLoadPct}% ${HudDisplayUtils.formatTemp(state.cpuTempC)}")
        append(", GPU ${state.gpuMhz?.let { "${it}M" } ?: "N/A"} ${HudDisplayUtils.formatTemp(state.gpuTempC)}")
        append(", battery ${HudDisplayUtils.formatBatteryPct(state.batteryPct)} ${HudDisplayUtils.formatTemp(state.batteryTempC)}")
        // Mirror the visible bar: power is only announced when it has a real value
        // (it's hidden, not shown as "--", when the device can't read current).
        if (state.batteryW != null) append(", ${HudDisplayUtils.formatWatts(state.batteryW)}")
        if (throttling) append(", THROTTLING")
        if (state.autoTdpRunning) {
            val capWord = if (state.autoTdpBigCapMhz != null) {
                "cap ${HudDisplayUtils.formatProofChipCap(state.autoTdpBigCapMhz)}"
            } else "stock"
            val live = HudDisplayUtils.heartbeatIsLive(state.autoTdpLastAppliedEpochMs, nowMs)
            append(", AutoTDP $capWord, ${if (live) "live" else "stalled"}")
        }
    }

    // Readability floor (RP6 fix): the dense compact bar sits over busy game
    // content, so an over-transparent backing makes it read as broken/empty.
    // We still honour the user's opacity preference, but clamp the EFFECTIVE
    // bar alpha to a high readable minimum so the populated metrics always stay
    // crisp and the bar reads as a solid strip. The verbose panel keeps the raw
    // user opacity (it's larger and not edge-hugging over gameplay), so this
    // floor is scoped to the compact bar.
    val barAlpha = state.hudOpacity.coerceAtLeast(COMPACT_BAR_MIN_ALPHA)

    Row(
        modifier = Modifier
            .wrapContentWidth()
            .alpha(barAlpha)
            .clip(RoundedCornerShape(13.dp))
            .background(HudBgSolid)
            // Bold red Arsenal accent edge on the leading side so the bar reads as
            // an intentional Calibrate surface, not stray text floating on the game.
            .drawBehind { drawRect(HudRed, size = size.copy(width = ACCENT_EDGE.toPx())) }
            .border(0.8.dp, HudBorder, RoundedCornerShape(13.dp))
            // Generous outer padding so the boxed cells breathe inside the bar —
            // the row of polished tiles reads as a premium HUD, not a cramped strip.
            .padding(start = ACCENT_EDGE + 7.dp, end = 7.dp, top = 7.dp, bottom = 7.dp)
            .semantics { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
        // Each cell now carries its OWN recessed background, so they read as
        // separate chips with a comfortable gap between them — no hairline rules
        // needed (the gap + per-cell fill IS the separation, like the mockup).
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── FPS / Hz hero cell (raised bg) ────────────────────────────────────
        // HERO only when a REAL game framerate exists; otherwise a tiny tag so a
        // meaningless panel-refresh "60" never dominates the bar. The hero is the
        // visual anchor — the biggest, brightest element on the bar.
        CompactHeroCell {
            if (state.gameFpsIsReal && state.gameFps != null) {
                CompactFpsHero(fps = state.gameFps)
            } else {
                CompactHzTag(gameFps = state.gameFps)
            }
        }

        // ── CPU cell — always shown (cpuMaxMhz is the core signal) ────────────
        CompactMetricCell(
            label = "CPU",
            labelColor = HudBlue,
            value = HudDisplayUtils.formatClockWithBoost(
                state.cpuMaxMhz.takeIf { it > 0 }, state.autoTdpBigCapMhz,
            ),
            tempC = state.cpuTempC,
            sub = "${HudDisplayUtils.formatLoadPct(state.cpuLoadPct, state.loadIsProxy)} load",
        )

        // ── GPU cell — dropped whole when clock AND temp AND load all absent ──
        val gpuLoad = HudDisplayUtils.formatLoadPctOrNull(state.gpuLoadPct, isProxy = false)
        val showGpu = (state.gpuMhz != null && state.gpuMhz > 0) ||
            state.gpuTempC != null || gpuLoad != null
        if (showGpu) {
            CompactMetricCell(
                label = "GPU",
                labelColor = HudPurple,
                value = HudDisplayUtils.formatGpuClock(state.gpuMhz),
                tempC = state.gpuTempC,
                // If load is unavailable but clock is, drop just the load sub-line.
                sub = gpuLoad?.let { "$it load" },
            )
        }

        // ── BAT cell — % + inline coloured temp; power sub-line dropped at 0 ───
        CompactMetricCell(
            label = "BAT",
            labelColor = HudEmerald,
            value = HudDisplayUtils.formatBatteryPct(state.batteryPct),
            tempC = state.batteryTempC,
            // Power null/0 → drop just the W sub-line, keep %+temp.
            sub = state.batteryW?.takeIf { it > 0.0 }?.let { HudDisplayUtils.formatWatts(it) },
        )

        // ── Trailing controls cell (raised bg) ────────────────────────────────
        CompactControlsCell(
            state = state,
            throttling = throttling,
            onCycleLayout = onCycleLayout,
            onClose = onClose,
        )
    }
}

/**
 * Raised hero-cell frame for the FPS/Hz lead — the VISUAL ANCHOR of the bar. One
 * shade lighter than the bar (HudRaised) with a faint emerald hairline so the box
 * frames the big FPS number, and generous padding to match the metric cells so it
 * sits as a peer tile (just brighter + bigger). This is the most prominent cell
 * whenever a real game framerate exists.
 */
@Composable
private fun CompactHeroCell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(HudRaised)
            .border(0.8.dp, HudEmerald.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The FPS HERO — big emerald mono number + small "fps" tag. Rendered ONLY when a
 * real in-game framerate exists, so the most useful number during play is the
 * most prominent. (When there's no game, [CompactHzTag] is shown instead.)
 */
@Composable
private fun CompactFpsHero(fps: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = fps.toString(),
            color = HudEmerald,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            lineHeight = 20.sp,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = "fps",
            color = HudEmerald.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

/**
 * The tiny no-game Hz tag — a muted "60Hz", NOT a hero. Shown when the FPS value
 * is just the panel refresh rate so it stays small and secondary; the actual
 * metrics get the visual weight instead.
 */
@Composable
private fun CompactHzTag(gameFps: Int?) {
    Text(
        text = "${gameFps ?: "—"}Hz",
        color = HudMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.3.sp,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * A labeled compact metric cell — a DISTINCT BOXED CHIP (its own recessed
 * [HudCellBg] fill, 7 dp corners, hairline border, generous padding) so CPU / GPU
 * / BAT each read as their own polished tile, never flat text on the bar. Three
 * stacked lines, matching the approved mockup:
 *   top:  LABEL                         (coloured, letter-spaced)
 *   main: VALUE (clock/%/...)  temp°    (mono value + inline cool→hot temp)
 *   sub:  optional muted line           (load / power)
 *
 * The coloured [label] makes the metric instantly identifiable. [value] is the
 * hero number (boost `+` included by the caller). [tempC], when present, sits on
 * the main line coloured by tier. [sub] is dropped when null so the cell stays
 * honest + dense (e.g. GPU with no load reading, BAT with no power reading).
 */
@Composable
private fun CompactMetricCell(
    label: String,
    labelColor: Color,
    value: String,
    tempC: Float?,
    sub: String?,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(HudCellBg)
            .border(0.8.dp, HudBorder, RoundedCornerShape(7.dp))
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Top line: the coloured category label, on its own row above the value.
        Text(
            text = label,
            color = labelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            softWrap = false,
        )
        // Main line: big mono value + inline temp coloured by tier.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = HudValue,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                lineHeight = 15.sp,
                maxLines = 1,
                softWrap = false,
            )
            if (tempC != null) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = HudDisplayUtils.formatTempBare(tempC),
                    color = tempColor(tempC),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
        }
        // Sub line: muted load / power, dropped entirely when null.
        if (sub != null) {
            Text(
                text = sub,
                color = HudMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 11.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Trailing controls cell (raised bg): AUTO·TDP pill (only while running) +
 * THROT pill (only while throttling now) + expand + close icons.
 */
@Composable
private fun CompactControlsCell(
    state: HudUiState,
    throttling: Boolean,
    onCycleLayout: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(HudRaised)
            .border(0.8.dp, HudBorder, RoundedCornerShape(7.dp))
            .padding(start = 7.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (state.autoTdpRunning) {
            CompactAutoTdpPill(state = state)
        }
        if (throttling) {
            CompactThrottleChip()
        }
        // Expand → VERBOSE
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onCycleLayout),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.UnfoldMore,
                contentDescription = "Expand HUD to detailed panel",
                tint = HudValue,
                modifier = Modifier.size(16.dp),
            )
        }
        // Close
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Hide overlay",
                tint = HudMuted,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * "THROT" flag pill — shown only when the kernel cooling-device state proves a
 * mitigation is engaged right now. Red, the honest answer to "am I throttled?".
 */
@Composable
private fun CompactThrottleChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(HudRed.copy(alpha = 0.20f))
            .border(0.8.dp, HudRed.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = "Thermal throttling active" },
    ) {
        Text(
            text = "THROT",
            color = HudRed,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * COMPACT-bar AUTO·TDP pill (only rendered while running):
 *   [pulse-dot] AUTO·TDP
 *
 * Green-bordered. The pulse dot proves the daemon is alive: emerald + animated
 * when a write landed within the live window, muted + static when stalled
 * (age > ~3s). Matches the approved "AUTO·TDP pill" spec.
 */
@Composable
private fun CompactAutoTdpPill(state: HudUiState) {
    val nowMs = rememberHudNowMs()
    val live = HudDisplayUtils.heartbeatIsLive(state.autoTdpLastAppliedEpochMs, nowMs)
    val accent = if (live) HudEmerald else HudDim
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(accent.copy(alpha = if (live) 0.16f else 0.08f))
            .border(0.8.dp, accent.copy(alpha = if (live) 0.6f else 0.3f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics {
                contentDescription = "AutoTDP ${if (live) "live" else "stalled"}"
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HeartbeatDot(live = live, size = 5.dp)
            Text(
                text = "AUTO·TDP",
                color = accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Heartbeat pulse dot. When [live] it slowly pulses its alpha (emerald) to show
 * the daemon is actively writing; when stalled it renders a static muted dot.
 * The animation is purely cosmetic — colour/liveness is decided by the caller
 * from the real heartbeat age, so a stalled daemon can never *look* alive.
 */
@Composable
private fun HeartbeatDot(live: Boolean, size: Dp) {
    val color = if (live) HudEmerald else HudDim
    val alpha = if (live) {
        val transition = rememberInfiniteTransition(label = "heartbeat")
        val pulse by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "heartbeatAlpha",
        )
        pulse
    } else {
        0.6f
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = alpha)),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  FULL mode -- horizontal panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HudFullPanel(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onToggleRecord: () -> Unit,
    onApplyProfile: (String) -> Unit,
    onCycleNextProfile: () -> Unit,
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
    onSetRefreshHz: (Float?) -> Unit,
    onCycleHudSize: () -> Unit,
    onSetOpacity: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val widthDp: Dp = HudDisplayUtils.hudWidthDp(state.hudSizeIndex).dp
    val scroll = rememberScrollState()

    // The verbose card: solid bg + bold red accent edge. The BODY scrolls (capped
    // height) so every section — incl. the opacity + display-Hz controls at the
    // bottom — stays reachable even on short screens, while the header stays put.
    Column(
        modifier = Modifier
            .widthIn(min = widthDp, max = widthDp)
            .alpha(state.hudOpacity)
            .clip(RoundedCornerShape(12.dp))
            .background(HudBgSolid)
            .border(0.8.dp, HudBorder, RoundedCornerShape(12.dp))
            .drawBehind { drawRect(HudRed, size = size.copy(width = ACCENT_EDGE.toPx())) },
    ) {
        // ── Header bar (raised, bottom border) ──────────────────────────────
        FullPanelHeader(
            state = state,
            onCycleLayout = onCycleLayout,
            onToggleRecord = onToggleRecord,
            onCycleHudSize = onCycleHudSize,
            onClose = onClose,
        )

        // ── Scrolling body ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(scroll)
                .padding(start = ACCENT_EDGE + 7.dp, end = 9.dp, top = 8.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // FPS hero (+ frame-ms when real).
            FullFpsHero(state)

            // THROTTLE banner — only when the kernel is mitigating NOW.
            if (HudDisplayUtils.isThrottlingNow(state.coolingDeviceMaxState)) {
                ThrottleBanner()
            }

            // 2×2 metric grid: CPU / GPU / POWER / BATTERY.
            FullMetricGrid(state)

            // THERMAL breakdown: CPU PEAK / GPU / BATT, each coloured. Uses
            // cpuPeakTempC — the HOTTEST cpu zone, never the average.
            HudDivider()
            FullThermalRow(
                cpuTempC = state.cpuPeakTempC ?: state.cpuTempC,
                gpuTempC = state.gpuTempC,
                batteryTempC = state.batteryTempC,
            )

            // PER-CORE load bars (parked cores dimmed). Hidden until data exists.
            if (state.perCoreMhz.isNotEmpty()) {
                HudDivider()
                FullPerCoreSection(state)
            }

            // AutoTDP proof footer (KEEP — strongest part). Reused verbatim,
            // styled to match by its own emerald-edged surface.
            HudDivider()
            AutoTdpProofSection(
                state = state,
                onToggleAutoTdp = onToggleAutoTdp,
                onSetAutoTdpProfile = onSetAutoTdpProfile,
            )

            // Quick profiles (optional).
            if (state.quickProfiles.isNotEmpty()) {
                HudDivider()
                FullProfileChips(
                    chips = state.quickProfiles,
                    onApplyProfile = onApplyProfile,
                    onCycleNextProfile = onCycleNextProfile,
                )
            }

            // Display-Hz picker + opacity controls — always reachable (scrolls).
            HudDivider()
            if (state.availableHzOptions.isNotEmpty()) {
                FullRefreshRateSection(
                    availableHz = state.availableHzOptions,
                    pinnedHz = state.pinnedHz,
                    onSetRefreshHz = onSetRefreshHz,
                )
            }
            FullOpacityRow(
                hudOpacity = state.hudOpacity,
                onSetOpacity = onSetOpacity,
            )

            // Flash message.
            state.lastActionMessage?.let { msg ->
                Text(
                    text = msg,
                    color = HudAmber,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Full-width red banner that surfaces active thermal throttling — the single
 * most useful gaming-HUD signal ("am I being throttled?"). Only rendered when the
 * kernel cooling-device state proves a mitigation is engaged; never speculative.
 */
@Composable
private fun ThrottleBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(HudRed.copy(alpha = 0.12f))
            .border(0.5.dp, HudRed.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .semantics { contentDescription = "Thermal throttling active — clocks limited by the kernel" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Outlined.Bolt, null, tint = HudRed, modifier = Modifier.size(11.dp))
        Text(
            text = "THROTTLING — clocks limited by thermal mitigation",
            color = HudRed,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.3.sp,
        )
    }
}

// ── Full panel: Header ───────────────────────────────────────────────────────

@Composable
private fun FullPanelHeader(
    state: HudUiState,
    onCycleLayout: () -> Unit,
    onToggleRecord: () -> Unit,
    onCycleHudSize: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudRaised)
            .drawBehind {
                // Bottom hairline border separating the header from the body.
                drawRect(
                    HudBorder,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()),
                    size = size.copy(height = 1.dp.toPx()),
                )
            }
            .padding(start = ACCENT_EDGE + 7.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Brand label
        Text(
            text = "CALIBRATE",
            color = HudRed,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
        )
        Spacer(Modifier.width(7.dp))

        // AutoTDP goal/mode pill — shows the GOAL the engine actually runs, so the
        // header matches the picker + main AutoTDP screen.
        val tdpAccent = when (state.autoTdpStatus) {
            AutoTdpStatus.RUNNING -> HudEmerald
            AutoTdpStatus.KILLED_BY_SAFETY, AutoTdpStatus.WRITE_DENIED -> HudRed
            else -> HudDim
        }
        val tdpLabel = if (state.autoTdpRunning) {
            GoalProfileUi.goalShortLabel(GoalProfile.fromLegacyProfile(state.autoTdpActiveProfile))
        } else "TDP OFF"
        StatusPill(text = tdpLabel, accent = tdpAccent)

        Spacer(Modifier.weight(1f))

        // Recording dot (+ elapsed m:ss when recording).
        if (state.isRecording) {
            val m = state.recordingElapsedSeconds / 60
            val s = state.recordingElapsedSeconds % 60
            Text(
                text = "%d:%02d".format(m, s),
                color = HudRed,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(2.dp))
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onToggleRecord)
                .semantics {
                    contentDescription = if (state.isRecording) "Stop recording" else "Start recording"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.FiberManualRecord,
                contentDescription = null,
                tint = if (state.isRecording) HudRed else HudDim,
                modifier = Modifier.size(12.dp),
            )
        }

        // Size toggle (SM/MD/LG)
        HudIconChip(label = HudDisplayUtils.hudSizeLabel(state.hudSizeIndex), onClick = onCycleHudSize)
        Spacer(Modifier.width(3.dp))

        // Collapse → COMPACT
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onCycleLayout),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.UnfoldLess,
                contentDescription = "Collapse to compact bar",
                tint = HudValue,
                modifier = Modifier.size(16.dp),
            )
        }

        // Close
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Hide overlay",
                tint = HudMuted,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

// ── Full panel: FPS hero ────────────────────────────────────────────────────

/**
 * The verbose FPS hero. A big emerald number + "fps · 17.2 ms" frame-time ONLY
 * when a REAL game framerate exists; otherwise a dim "REFRESH 60Hz" line and NO
 * frame-ms (a panel-refresh value has no meaningful frame time).
 */
@Composable
private fun FullFpsHero(state: HudUiState) {
    val isReal = state.gameFpsIsReal && state.gameFps != null
    if (isReal) {
        val fps = state.gameFps!!
        val frameMs = HudDisplayUtils.formatFrameMs(fps)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "$fps frames per second${frameMs?.let { ", $it frame time" } ?: ""}"
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = fps.toString(),
                color = HudEmerald,
                fontSize = 38.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                lineHeight = 38.sp,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (frameMs != null) "fps · $frameMs" else "fps",
                color = HudMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    } else {
        Text(
            text = HudDisplayUtils.formatRefreshTag(state.gameFps),
            color = HudDim,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = state.gameFps?.let { "Display refresh $it Hz" }
                        ?: "Refresh rate unavailable"
                },
        )
    }
}

// ── Full panel: 2×2 metric grid ─────────────────────────────────────────────

/**
 * The 2×2 metric grid: CPU / GPU on the top row, POWER / BATTERY on the bottom,
 * separated by hairline dividers. Each tile pairs its big value with a coloured
 * temp (cool→hot) and a muted sub-line. Honest "—"/omit when unavailable.
 */
@Composable
private fun FullMetricGrid(state: HudUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FullMetricTile(
                label = "CPU",
                accent = HudBlue,
                value = HudDisplayUtils.formatClockWithBoost(
                    state.cpuMaxMhz.takeIf { it > 0 }, state.autoTdpBigCapMhz,
                ),
                tempC = state.cpuTempC,
                sub = "${HudDisplayUtils.formatLoadPct(state.cpuLoadPct, state.loadIsProxy)} load",
                modifier = Modifier.weight(1f),
            )
            FullMetricTile(
                label = "GPU",
                accent = HudPurple,
                value = HudDisplayUtils.formatGpuClock(state.gpuMhz),
                tempC = state.gpuTempC,
                sub = HudDisplayUtils.formatLoadPctOrNull(state.gpuLoadPct, isProxy = false)
                    ?.let { "$it load" },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FullMetricTile(
                label = "POWER",
                accent = HudAmber,
                // Power null/0 → honest "—" (device can't read current_now).
                value = state.batteryW?.takeIf { it > 0.0 }
                    ?.let { HudDisplayUtils.formatWatts(it) } ?: "—",
                tempC = null,
                sub = state.ramUsedPct?.let { "RAM ${it}%" },
                modifier = Modifier.weight(1f),
            )
            FullMetricTile(
                label = "BATTERY",
                accent = HudEmerald,
                value = HudDisplayUtils.formatBatteryPct(state.batteryPct),
                tempC = state.batteryTempC,
                sub = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One grid tile: left accent bar + label, big mono value, then a sub-line that
 * pairs an optional muted [sub] (load / RAM) with an optional coloured temp.
 * The temp is coloured by its [HudDisplayUtils.tempTier] (cool→hot); a null temp
 * is simply omitted (POWER has none) — never a fabricated band.
 */
@Composable
private fun FullMetricTile(
    label: String,
    accent: Color,
    value: String,
    tempC: Float?,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    val showTemp = label != "POWER"
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            // Same recessed chip fill as the COMPACT bar's metric cells so the
            // verbose 2×2 grid reads as the same family of polished boxed tiles,
            // not a flatter/darker variant.
            .background(HudCellBg)
            .border(0.8.dp, HudBorder, RoundedCornerShape(7.dp))
            .semantics {
                contentDescription =
                    "$label $value" + (if (showTemp) " ${HudDisplayUtils.formatTemp(tempC)}" else "") +
                        (sub?.let { ", $it" } ?: "")
            },
    ) {
        // Bold left accent bar — the category key, matching the bar's red edge.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(52.dp)
                .background(accent),
        )
        Column(
            modifier = Modifier.padding(start = 10.dp, end = 9.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = HudValue,
                    fontSize = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    softWrap = false,
                )
                if (showTemp) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = HudDisplayUtils.formatTempBare(tempC),
                        color = tempColor(tempC),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            }
            Text(
                text = sub ?: "",
                color = HudMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 11.sp,
            )
        }
    }
}

// ── AutoTDP proof-of-effect section ──────────────────────────────────────────

/**
 * The "is AutoTDP REALLY working" proof section.
 *
 * STOPPED → Start button + 3-segment profile picker (unchanged controls).
 *
 * RUNNING → a stacked proof block, every row HONESTY-GATED (a row only renders
 * when its backing field is non-null; nulls hide, never fake):
 *   1. HEADER   — "AUTOTDP PROOF" label + heartbeat pulse dot/age + STOP.
 *   2. CHANGED  — cap+delta / parked cores / GPU floor, each hidden if absent;
 *                 "Holding at stock" when nothing is applied.
 *   3. WHY      — clean hold-reason label, tap to expand the raw engine reason
 *                 (carries live %s). LOAD_BLIND reads "load unreadable", never idle.
 *   4. EFFECT   — measured session Wh saved; if null, an honest "measuring…" hint.
 *   5. TICKER   — last-N decisions + a flat-ok cap sparkline (flat = holding steady).
 *   6. CONTROL  — profile picker (EFF/BAL/TGT) so the user can switch while running.
 */
@Composable
private fun AutoTdpProofSection(
    state: HudUiState,
    onToggleAutoTdp: () -> Unit,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
) {
    if (!state.autoTdpRunning) {
        // ── STOPPED: Start + profile picker ──────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HudChipBtn(
                label = "Start AutoTDP",
                enabled = state.autoTdpStatus != AutoTdpStatus.LIVE_UNAVAILABLE,
                accent = HudEmerald,
                onClick = onToggleAutoTdp,
            )
            Spacer(Modifier.weight(1f))
            AutoTdpProfilePicker(
                active = state.autoTdpActiveProfile,
                onSetAutoTdpProfile = onSetAutoTdpProfile,
            )
        }
        // LIVE_UNAVAILABLE is an honest, non-faked state worth surfacing.
        if (state.autoTdpStatus == AutoTdpStatus.LIVE_UNAVAILABLE) {
            Text(
                text = "live tuning unavailable on this device",
                color = HudDim,
                fontSize = 8.sp,
                lineHeight = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        return
    }

    // ── RUNNING: proof block ─────────────────────────────────────────────────
    // Live 1 s clock so the heartbeat keeps ticking without an external recompose
    // (previously captured once at composition → froze "live/stalled").
    val nowMs = rememberHudNowMs()
    val live = HudDisplayUtils.heartbeatIsLive(state.autoTdpLastAppliedEpochMs, nowMs)
    val heartbeatText = HudDisplayUtils.heartbeatLabel(state.autoTdpLastAppliedEpochMs, nowMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(HudAutoTdpBg)
            .border(0.5.dp, HudAutoTdpBorder, RoundedCornerShape(5.dp))
            .drawBehind {
                // Emerald left-edge accent bar — "this section is live".
                drawRect(if (live) HudEmerald else HudDim, size = size.copy(width = 2.dp.toPx()))
            }
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 1. HEADER — label + heartbeat + STOP ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Outlined.Bolt, null, tint = HudEmerald, modifier = Modifier.size(11.dp))
            Text(
                // Goal-mapped label (matches the picker + main screen), not legacy EFF/BAL/TGT.
                text = "AUTOTDP - ${GoalProfileUi.goalShortLabel(GoalProfile.fromLegacyProfile(state.autoTdpActiveProfile))}",
                color = HudEmerald,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
            Spacer(Modifier.weight(1f))
            // 4. HEARTBEAT — pulse dot + "adjusted Xs ago" (hidden if no tick yet)
            HeartbeatDot(live = live, size = 6.dp)
            if (heartbeatText != null) {
                Text(
                    text = heartbeatText,
                    color = if (live) HudEmerald else HudAmber,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "AutoTDP ${if (live) "live" else "stalled"}, $heartbeatText"
                    },
                )
            }
            Spacer(Modifier.width(2.dp))
            HudChipBtn(label = "STOP", enabled = true, accent = HudRed, onClick = onToggleAutoTdp)
        }

        // 2. WHAT IT CHANGED NOW ──────────────────────────────────────────────
        AutoTdpChangedRows(state)

        // 3. WHY (hold-reason, tap to expand raw) ─────────────────────────────
        AutoTdpWhyRow(state)

        // 3b. GOAL / DETECTED row (Wave 4b) ───────────────────────────────────
        if (state.autoTdpGoal != null) {
            AutoTdpGoalRow(goal = state.autoTdpGoal, detectedContext = state.autoTdpDetectedContext)
        }

        // 5. EFFECT (measured only) ───────────────────────────────────────────
        AutoTdpEffectRow(state)

        // 6. DECISION TICKER + sparkline ──────────────────────────────────────
        if (state.autoTdpDecisions.isNotEmpty()) {
            AutoTdpDecisionTicker(state.autoTdpDecisions)
        }

        // 7. CONTROL — profile picker (switch while running) ──────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PROFILE",
                color = HudLabel,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.weight(1f))
            AutoTdpProfilePicker(
                active = state.autoTdpActiveProfile,
                onSetAutoTdpProfile = onSetAutoTdpProfile,
            )
        }
    }
}

/**
 * 3-segment AutoTDP mode picker, shared by running + stopped states.
 *
 * HONESTY (HUD/main-screen alignment fix): the chips still drive the legacy
 * [AutoTdpProfile] (the HUD start path in OverlayService takes that type), but the
 * engine actually runs each legacy profile as a [GoalProfile] via
 * [GoalProfile.fromLegacyProfile]. The labels therefore show the GOAL the engine
 * runs — COOL / BAL / BATT — so the mode set in the HUD reads the same as on the
 * main AutoTDP screen (whose goal picker uses COOL_QUIET / BALANCED_SMART /
 * BATTERY_SAVER), mapping 1:1 instead of advertising a divergent EFF/BAL/TGT set.
 */
@Composable
private fun AutoTdpProfilePicker(
    active: AutoTdpProfile,
    onSetAutoTdpProfile: (AutoTdpProfile) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            AutoTdpProfile.EFFICIENCY,
            AutoTdpProfile.BALANCED,
            AutoTdpProfile.BATTERY_TARGET,
        ).forEach { profile ->
            AutoTdpProfileChip(
                label = GoalProfileUi.goalShortLabel(GoalProfile.fromLegacyProfile(profile)),
                selected = active == profile,
                onClick = { onSetAutoTdpProfile(profile) },
            )
        }
    }
}

/**
 * "WHAT IT CHANGED NOW" — DERIVED facts (live immediately). Each line is hidden
 * when its field is absent. When NOTHING is applied, a single honest
 * "Holding at stock" line is shown instead.
 */
@Composable
private fun AutoTdpChangedRows(state: HudUiState) {
    val capLine = HudDisplayUtils.formatCapLine(state.autoTdpBigCapMhz, state.autoTdpCapDeltaMhz)
    val parkedLine = HudDisplayUtils.formatParkedCoresLine(state.autoTdpParkedCores)
    val gpuLine = HudDisplayUtils.formatGpuLevelLine(state.autoTdpGpuLevel)
    val holdingAtStock = HudDisplayUtils.isHoldingAtStock(
        state.autoTdpBigCapMhz, state.autoTdpParkedCores, state.autoTdpGpuLevel,
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (holdingAtStock) {
            ProofLine(dot = HudDim, text = "Holding at stock", color = HudValue)
        } else {
            if (capLine != null) ProofLine(dot = HudEmerald, text = capLine, color = HudValue)
            if (parkedLine != null) ProofLine(dot = HudBlue, text = parkedLine, color = HudValue)
            if (gpuLine != null) ProofLine(dot = HudPurple, text = gpuLine, color = HudValue)
        }
    }
}

/** One DERIVED-fact line: colored dot + mono text. */
@Composable
private fun ProofLine(dot: Color, text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.semantics { contentDescription = text },
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(dot),
        )
        Text(
            text = text,
            color = color,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * "WHY" — clean hold-reason label; tap to reveal the raw engine reason (which
 * carries the live %s like "GPU 92%, big/prime 18%"). Honesty: LOAD_BLIND reads
 * "CPU load unreadable - holding", never "idle".
 */
@Composable
private fun AutoTdpWhyRow(state: HudUiState) {
    var expanded by remember { mutableStateOf(false) }
    val label = HudDisplayUtils.holdReasonLabel(state.autoTdpHoldReason)
    val hasRaw = state.autoTdpReason.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasRaw) { expanded = !expanded }
                .semantics {
                    contentDescription =
                        "Reason: $label." + if (hasRaw) " Tap to ${if (expanded) "hide" else "show"} detail." else ""
                },
        ) {
            Text(
                text = "WHY",
                color = HudLabel,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            Text(
                text = label,
                color = HudAmber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (hasRaw) {
                Text(
                    text = if (expanded) "[hide]" else "[detail]",
                    color = HudDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (expanded && hasRaw) {
            Text(
                text = state.autoTdpReason,
                color = HudDim,
                fontSize = 8.sp,
                lineHeight = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/**
 * Wave 4b: GOAL row — shows the active GoalProfile and, when AUTO is running,
 * the classifier DETECTED context (blue "DETECTED" pill, not Emerald "MEASURED").
 *
 * HONESTY: the detected context is a classifier BELIEF (GPU-busy% + foreground
 * heuristic after hysteresis). It is NOT a measurement. Always rendered with
 * HudBlue (not HudEmerald) to preserve the DETECTED vs MEASURED distinction.
 */
@Composable
private fun AutoTdpGoalRow(
    goal: GoalProfile,
    detectedContext: WorkloadContext?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "GOAL",
            color = HudLabel,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        Text(
            text = GoalProfileUi.goalShortLabel(goal),
            color = HudBlue,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        // DETECTED context only when AUTO is live — blue pill, not emerald.
        if (detectedContext != null) {
            Text(
                text = "→ ${GoalProfileUi.detectedContextShort(detectedContext)}",
                color = HudBlue,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            // Small "DETECTED" label — distinguishes from MEASURED honesty tier.
            Text(
                text = "DETECTED",
                color = HudBlue,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

/**
 * "EFFECT" — MEASURED-only. Shows session Wh saved (+ power-saved when the probe
 * is ready). When no measured number exists yet, shows an honest one-line
 * "measuring…" hint — NEVER a fabricated value.
 */
@Composable
private fun AutoTdpEffectRow(state: HudUiState) {
    val whLine = HudDisplayUtils.formatSessionWhLine(state.autoTdpSessionWh)
    val powerReady = state.autoTdpSavingsReady && state.autoTdpSavingsMw != null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "EFFECT",
            color = HudLabel,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        if (whLine != null || powerReady) {
            // At least one MEASURED number is available — show it.
            val parts = buildList {
                if (powerReady) {
                    add(
                        HudDisplayUtils.formatAutoTdpSavings(
                            state.autoTdpSavingsMw,
                            state.autoTdpSavingsPct,
                            state.autoTdpSavingsReady,
                        ),
                    )
                }
                if (whLine != null) add(whLine)
            }
            Text(
                text = parts.joinToString("  -  "),
                color = HudEmerald,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Measured effect: ${parts.joinToString(", ")}" },
            )
        } else {
            // No measured probe yet — honest hint, not a number.
            Text(
                text = "measuring... (first ~40s)",
                color = HudDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Effect measuring, first reading in about 40 seconds" },
            )
        }
    }
}

/**
 * "DECISION TICKER" — the last few engine decisions (newest-first) plus a tiny
 * cap-vs-time sparkline. A flat sparkline honestly reads as "holding steady".
 */
@Composable
private fun AutoTdpDecisionTicker(decisions: List<DecisionRecord>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "RECENT",
                color = HudLabel,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
            CapSparkline(
                decisions = decisions,
                modifier = Modifier
                    .weight(1f)
                    .height(14.dp),
            )
        }
        // Last 3 decisions, newest-first: reason + cap.
        decisions.takeLast(3).reversed().forEach { d ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(holdReasonDot(d.holdReason)),
                )
                Text(
                    text = HudDisplayUtils.holdReasonLabel(d.holdReason),
                    color = HudDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = HudDisplayUtils.formatDecisionCap(d.bigCapKhz),
                    color = HudDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Tiny cap-vs-time sparkline from the decision history. Uncapped (null) decisions
 * sit at the top of the track (= stock ceiling). A flat line is honest: the
 * engine has been holding the cap steady. Needs >= 2 points to draw a line.
 */
@Composable
private fun CapSparkline(decisions: List<DecisionRecord>, modifier: Modifier = Modifier) {
    val caps = decisions.map { it.bigCapKhz }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(HudBarBg)
            .semantics { contentDescription = "AutoTDP cap history sparkline" },
    ) {
        if (caps.size >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .drawBehind {
                        val known = caps.filterNotNull()
                        // Range for normalisation; uncapped -> top (stock ceiling).
                        val maxCap = (known.maxOrNull() ?: 0)
                        val minCap = (known.minOrNull() ?: 0)
                        val span = (maxCap - minCap).coerceAtLeast(1)
                        val n = caps.size
                        val stepX = if (n > 1) size.width / (n - 1) else size.width
                        val pad = 2f
                        val usableH = size.height - pad * 2
                        fun yFor(cap: Int?): Float {
                            // null cap = uncapped = at the ceiling (top, small y).
                            val c = cap ?: maxCap
                            val frac = (c - minCap).toFloat() / span
                            // Higher cap -> higher on screen (smaller y).
                            return pad + (1f - frac) * usableH
                        }
                        val path = Path()
                        caps.forEachIndexed { i, cap ->
                            val x = i * stepX
                            val y = yFor(cap)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = HudEmerald,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                        )
                    },
            )
        }
    }
}

/** Dot colour for a decision's hold-reason in the ticker. */
private fun holdReasonDot(reason: io.github.mayusi.calibratesoc.data.autotdp.HoldReason): Color =
    when (reason) {
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.CPU_BOUND_RELAXING -> HudBlue
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.GPU_BOUND_CAPPING -> HudPurple
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.BATTERY_TARGET_HOLDING -> HudAmber
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.LOAD_BLIND_HOLDING -> HudAmber
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.IDLE_HOLDING -> HudDim
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.NO_TELEMETRY -> HudDim
    }

// ── Full panel: Quick profiles ───────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullProfileChips(
    chips: List<Pair<String, String>>,
    onApplyProfile: (String) -> Unit,
    onCycleNextProfile: () -> Unit,
) {
    VerboseSectionLabel("PROFILES", HudBlue)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        chips.forEach { (id, name) ->
            HudSelectChip(
                label = name.take(12),
                selected = false,
                accent = HudBlue,
                onClick = { onApplyProfile(id) },
            )
        }
        HudChipBtn(label = "NEXT", enabled = chips.size > 1, accent = HudBlue, onClick = onCycleNextProfile)
    }
    Text(
        text = "tap writes script - open vendor settings to run",
        color = HudDim,
        fontSize = 8.sp,
        lineHeight = 11.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

// ═════════════════════════════════════════════════════════════════════════════
//  VERBOSE-panel detail sections — all MOUNTED by HudFullPanel.
//
//  FullRefreshRateSection, FullOpacityRow, FullPerCoreSection and FullThermalRow
//  were detached/dead in 0.1.33 (the data they show — display Hz, opacity,
//  per-core freq/load, and the thermal breakdown incl. battery temp — rendered
//  nowhere). They are now wired back into the redesigned verbose panel so every
//  field is reachable again.
// ═════════════════════════════════════════════════════════════════════════════

// ── Full panel: Refresh rate section ────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullRefreshRateSection(
    availableHz: List<Float>,
    pinnedHz: Float?,
    onSetRefreshHz: (Float?) -> Unit,
) {
    VerboseSectionLabel("DISPLAY HZ", HudPurple)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HudSelectChip(
            label = "AUTO",
            selected = pinnedHz == null,
            accent = HudPurple,
            onClick = { onSetRefreshHz(null) },
        )
        availableHz.forEach { hz ->
            HudSelectChip(
                label = HudDisplayUtils.formatHz(hz),
                selected = pinnedHz != null && kotlin.math.abs(hz - pinnedHz) < 0.5f,
                accent = HudPurple,
                onClick = { onSetRefreshHz(hz) },
            )
        }
    }
}

// ── Full panel: Opacity row ──────────────────────────────────────────────────

@Composable
private fun FullOpacityRow(
    hudOpacity: Float,
    onSetOpacity: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        VerboseSectionLabel("OPACITY", HudLabel)
        Spacer(Modifier.weight(1f))
        HudChipBtn(label = "-", enabled = hudOpacity > 0.2f, accent = HudLabel) {
            onSetOpacity((hudOpacity - 0.1f).coerceAtLeast(0.1f))
        }
        Text(
            text = HudDisplayUtils.formatOpacityPct(hudOpacity),
            color = HudValue,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        HudChipBtn(label = "+", enabled = hudOpacity < 1f, accent = HudLabel) {
            onSetOpacity((hudOpacity + 0.1f).coerceAtMost(1f))
        }
    }
}

// ── Full panel: Per-core bars (vertical load columns) ────────────────────────

/**
 * PER-CORE detail — a horizontal row of VERTICAL load columns (one per core),
 * each column's filled height = the core's load%. Cores AutoTDP has parked render
 * dimmed (a flat [HudParked] stub), the power-user signal that those cores are
 * offline. The fill is coloured by load tier (cool blue → busy amber → pegged
 * red) so a hot core jumps out. The core index sits under each column.
 */
@Composable
private fun FullPerCoreSection(state: HudUiState) {
    VerboseSectionLabel("PER-CORE", HudBlue)
    Spacer(Modifier.height(5.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        state.perCoreMhz.forEachIndexed { idx, mhz ->
            val loadPct = state.perCoreLoadPct.getOrNull(idx) ?: 0
            val isParked = idx != 0 && idx in state.autoTdpParkedCores
            PerCoreColumn(
                idx = idx,
                mhz = mhz,
                loadPct = loadPct,
                isParked = isParked,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Per-core load tier colour: cool → busy → pegged. */
private fun loadColor(loadPct: Int): Color = when {
    loadPct >= 85 -> HudRed
    loadPct >= 55 -> HudAmber
    else          -> HudBlue
}

@Composable
private fun PerCoreColumn(
    idx: Int,
    mhz: Int,
    loadPct: Int,
    isParked: Boolean,
    modifier: Modifier = Modifier,
) {
    val loadFrac = (loadPct / 100f).coerceIn(0f, 1f)
    Column(
        modifier = modifier.semantics {
            contentDescription = if (isParked) "Core $idx parked"
            else "Core $idx $mhz megahertz $loadPct percent load"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Vertical track (28dp tall); fill grows up from the bottom = load%.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isParked) HudParked.copy(alpha = 0.25f) else HudBarBg),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (isParked) {
                // A small dim stub marks the core as parked (offline), not loaded.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(HudParked),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp * loadFrac)
                        .clip(RoundedCornerShape(2.dp))
                        .background(loadColor(loadPct)),
                )
            }
        }
        Text(
            text = "$idx",
            color = if (isParked) HudParked else HudMuted,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Full panel: Thermal row ────────────────────────────────────────────────────

@Composable
private fun FullThermalRow(
    cpuTempC: Float?,
    gpuTempC: Float?,
    batteryTempC: Float?,
) {
    VerboseSectionLabel("THERMAL", HudAmber)
    Spacer(Modifier.height(3.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // CPU shows the PEAK zone (hottest core), not the average — the caller
        // passes cpuPeakTempC. Labelled "CPU▲" to make the peak explicit.
        ThermalMini(label = "CPU▲", tempC = cpuTempC, modifier = Modifier.weight(1f))
        ThermalMini(label = "GPU", tempC = gpuTempC, modifier = Modifier.weight(1f))
        ThermalMini(label = "BATT", tempC = batteryTempC, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ThermalMini(label: String, tempC: Float?, modifier: Modifier = Modifier) {
    // Border + value colour both follow the cool→hot tier so a hot reading glows
    // amber/red and a cool one stays calm — the value is the honest source.
    val accent = tempColor(tempC)
    val tier = HudDisplayUtils.tempTier(tempC)
    val borderColor = if (tier == HudDisplayUtils.TempTier.NONE) HudBorder
        else accent.copy(alpha = 0.4f)
    Column(
        modifier = modifier
            .background(HudBarBg, RoundedCornerShape(4.dp))
            .border(0.5.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp)
            .semantics { contentDescription = "$label ${HudDisplayUtils.formatTemp(tempC)}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = HudLabel, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(
            text = HudDisplayUtils.formatTemp(tempC),
            color = if (tier == HudDisplayUtils.TempTier.NONE) HudValue else accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Shared micro-components
// ─────────────────────────────────────────────────────────────────────────────

/** Section label: colored tick + uppercase title. */
@Composable
private fun VerboseSectionLabel(title: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(12.dp)
                .background(accent, RoundedCornerShape(1.dp)),
        )
        Text(
            text = title,
            color = HudValue,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
    }
}

/** Thin horizontal rule. */
@Composable
private fun HudDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = HudBorder)
}

/**
 * Arsenal-style small angular action button.
 * Uses [CutCornerShape] (TopEnd cut) for the angular look.
 */
@Composable
private fun HudChipBtn(
    label: String,
    enabled: Boolean,
    accent: Color = HudBlue,
    onClick: () -> Unit,
) {
    val shape = CutCornerShape(corner = CutCorner.TopEnd, cutSize = 7.dp, cornerRadius = 3.dp)
    val bg = if (enabled) accent.copy(alpha = 0.18f) else Color.Transparent
    val border = if (enabled) accent.copy(alpha = 0.6f) else HudBorder
    val fg = if (enabled) accent else HudDim

    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(0.5.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = fg,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Generic selectable chip: profiles, Hz options, etc. */
@Composable
private fun HudSelectChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color(0xFF0D1825))
            .border(0.5.dp, if (selected) accent else HudBorder, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = if (selected) accent else HudValue,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** AutoTDP profile segment chip. */
@Composable
private fun AutoTdpProfileChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = HudEmerald
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color(0xFF0D1825))
            .border(0.5.dp, if (selected) accent else HudBorder, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = if (selected) accent else HudLabel,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Tiny header chip for size label. */
@Composable
private fun HudIconChip(
    label: String,
    onClick: () -> Unit,
    accent: Color = HudLabel,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(0.5.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
        )
    }
}
