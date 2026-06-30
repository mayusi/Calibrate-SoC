package io.github.mayusi.calibratesoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.text.style.TextAlign
import io.github.mayusi.calibratesoc.ui.theme.Spacing

// ───────────────────────────────────────────────────────────────────────────
//  SECTION 1 — Arsenal Color Tokens
// ───────────────────────────────────────────────────────────────────────────

/**
 * Direction-C categorical accent palette.
 *
 * Each entry maps a semantic hardware category to a high-contrast accent
 * color that can be used as a left-border, bottom-accent bar, pill tint,
 * or bar-fill. All colors are chosen for legibility on the near-black
 * (#0C0C10 / #141419) Arsenal backgrounds.
 *
 * Usage:
 * ```kotlin
 * ArsenalPanel(accent = AccentBar.Red) { ... }
 * MetricTile(label = "SoC Temp", value = "72", unit = "°C", accent = AccentBar.Red)
 * ```
 */
object AccentBar {
    /** Primary aggressive accent — danger, high-priority actions. */
    val Red     = Color(0xFFFF4D6D)

    /** CPU cluster / frequency data. */
    val Blue    = Color(0xFF5C93F0)

    /** GPU / graphics data. */
    val Purple  = Color(0xFFA98BF5)

    /** Battery / power / thermal caution. */
    val Amber   = Color(0xFFE0A93D)

    /** Live / healthy / good signal. */
    val Emerald = Color(0xFF2EE6A6)

    /** Neutral fallback — informational / uncategorized. */
    val Neutral = Color(0xFF6B7280)

    /**
     * Returns the arsenal accent that most closely matches an existing
     * [io.github.mayusi.calibratesoc.ui.theme.AccentColor] so callers
     * can bridge the two systems without duplicate color constants.
     */
    fun fromMaterialPrimary(primary: Color): Color {
        // Rough hue-match by comparing dominant channel
        val r = primary.red
        val g = primary.green
        val b = primary.blue
        return when {
            r > 0.7f && g < 0.5f -> Red
            b > 0.7f && r < 0.5f -> Blue
            b > 0.5f && r > 0.5f -> Purple
            r > 0.7f && g > 0.5f -> Amber
            g > 0.7f && b > 0.5f -> Emerald
            else                  -> Neutral
        }
    }
}

/**
 * Shared neutral surface + muted-text tokens for the Direction-C ("Arsenal")
 * look — the single public source for the greys/surfaces that were
 * previously hand-duplicated as private constants in both
 * [ArsenalComponents] (this file, e.g. [ArsenalPanel]'s local
 * `surfaceColor`) and `SharedComponents.kt` (`CArsenalSurface`,
 * `CArsenalTile`, `CLabelGray`, `CDimGray`, ...).
 *
 * That duplication was the root cause of call sites re-typing literals
 * like `Color(0xFF999999)` instead of reusing a shared label-gray token —
 * the "real" token existed but was `private`. Centralizing here lets new
 * (and migrated) call sites reference `ArsenalText.Label` / `ArsenalText.Dim`
 * / `ArsenalText.Surface` / `ArsenalText.Tile` instead.
 *
 * All values are copied verbatim from the existing private constants —
 * this is a pure de-duplication, NOT a restyle. Same hex in, same hex out.
 */
object ArsenalText {
    /** Arsenal panel surface background (#141419). Was `CArsenalSurface` / [ArsenalPanel]'s local `surfaceColor`. */
    val Surface = Color(0xFF141419)

    /** Arsenal tile/inset background, darker than [Surface] (#0C0C10). Was `CArsenalTile`. */
    val Tile = Color(0xFF0C0C10)

    /** Uppercase tracked label / muted body text (#999999). Was `CLabelGray`. */
    val Label = Color(0xFF999999)

    /** Dimmer secondary text — units, explainers (#777777). Was `CDimGray`. */
    val Dim = Color(0xFF777777)
}

// ───────────────────────────────────────────────────────────────────────────
//  SECTION 2 — CutCornerShape
// ───────────────────────────────────────────────────────────────────────────

/**
 * Which corner to bevel in [CutCornerShape].
 */
enum class CutCorner { TopStart, TopEnd, BottomEnd, BottomStart }

/**
 * A [Shape] that cuts a single corner of the rectangle at a 45-degree angle,
 * giving the angular, hardware-tool look of Direction C.  All other corners
 * are optionally rounded at a small radius (default 4 dp).
 *
 * The cut is a straight diagonal slice across the chosen corner — the
 * resulting shape has five points when [cornerRadius] > 0 (curved arc on
 * the three un-cut corners) or exactly five straight edges when radius == 0.
 *
 * @param corner    Which corner to slice (default [CutCorner.TopEnd] for
 *                  buttons — top-right in LTR).
 * @param cutSize   Size of the diagonal cut in Dp (default 12 dp).
 * @param cornerRadius Radius for the remaining three corners (default 4 dp).
 */
class CutCornerShape(
    private val corner: CutCorner = CutCorner.TopEnd,
    private val cutSize: Dp = 12.dp,
    private val cornerRadius: Dp = 4.dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val cut = with(density) { cutSize.toPx() }
        val r   = with(density) { cornerRadius.toPx() }
        val w = size.width
        val h = size.height

        // Resolve the corner taking RTL into account.
        val resolvedCorner = when (layoutDirection) {
            LayoutDirection.Rtl -> when (corner) {
                CutCorner.TopStart  -> CutCorner.TopEnd
                CutCorner.TopEnd    -> CutCorner.TopStart
                CutCorner.BottomEnd -> CutCorner.BottomStart
                CutCorner.BottomStart -> CutCorner.BottomEnd
            }
            else -> corner
        }

        val path = Path().apply {
            when (resolvedCorner) {
                CutCorner.TopStart -> {
                    // Start after the cut on the top edge
                    moveTo(cut, 0f)
                    lineTo(w - r, 0f); quadraticTo(w, 0f, w, r)
                    lineTo(w, h - r); quadraticTo(w, h, w - r, h)
                    lineTo(r, h);     quadraticTo(0f, h, 0f, h - r)
                    // The cut corner itself
                    lineTo(0f, cut)
                    close()
                }
                CutCorner.TopEnd -> {
                    moveTo(r, 0f)
                    // Cut at top-end: skip to cut point on top, then diagonal
                    lineTo(w - cut, 0f)
                    lineTo(w, cut)
                    lineTo(w, h - r); quadraticTo(w, h, w - r, h)
                    lineTo(r, h);     quadraticTo(0f, h, 0f, h - r)
                    lineTo(0f, r);    quadraticTo(0f, 0f, r, 0f)
                    close()
                }
                CutCorner.BottomEnd -> {
                    moveTo(r, 0f)
                    lineTo(w - r, 0f); quadraticTo(w, 0f, w, r)
                    lineTo(w, h - cut)
                    lineTo(w - cut, h)
                    lineTo(r, h);     quadraticTo(0f, h, 0f, h - r)
                    lineTo(0f, r);    quadraticTo(0f, 0f, r, 0f)
                    close()
                }
                CutCorner.BottomStart -> {
                    moveTo(r, 0f)
                    lineTo(w - r, 0f); quadraticTo(w, 0f, w, r)
                    lineTo(w, h - r); quadraticTo(w, h, w - r, h)
                    lineTo(cut, h)
                    lineTo(0f, h - cut)
                    lineTo(0f, r);    quadraticTo(0f, 0f, r, 0f)
                    close()
                }
            }
        }
        return Outline.Generic(path)
    }
}

// ───────────────────────────────────────────────────────────────────────────
//  SECTION 3 — ArsenalPanel
// ───────────────────────────────────────────────────────────────────────────

/**
 * Where to place the colored accent bar on an [ArsenalPanel].
 */
enum class PanelAccentEdge { Start, Bottom }

/**
 * Direction-C surface panel — the primary container primitive.
 *
 * Replaces Material's Card with an angular dark surface (#141419), a small
 * corner radius (4 dp), and a thick (3 dp) colored accent edge (left or
 * bottom). An optional [title] is rendered as an uppercase, letter-spaced
 * label with a thin color tick preceding it.
 *
 * All existing [SectionCard] call sites can be migrated to [ArsenalPanel]
 * without changing content lambdas — only the outer container changes.
 *
 * @param accent     Accent bar color from [AccentBar] (or any [Color]).
 * @param modifier   Standard compose modifier.
 * @param title      Optional section title. When non-null, a [SectionHeader]
 *                   is rendered at the top of the panel before [content].
 * @param accentEdge Which edge receives the accent bar (default [PanelAccentEdge.Start]).
 * @param content    Arbitrary composable content.
 */
@Composable
fun ArsenalPanel(
    accent: Color,
    modifier: Modifier = Modifier,
    title: String? = null,
    accentEdge: PanelAccentEdge = PanelAccentEdge.Start,
    content: @Composable () -> Unit,
) {
    val surfaceColor = ArsenalText.Surface
    val accentBarWidth = 4.dp
    val cornerRadius = 4.dp

    when (accentEdge) {
        PanelAccentEdge.Start -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(surfaceColor, RoundedCornerShape(cornerRadius))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(cornerRadius),
                    )
                    .height(IntrinsicSize.Min),
            ) {
                // Left accent bar
                Box(
                    modifier = Modifier
                        .width(accentBarWidth)
                        .fillMaxHeight()
                        .background(accent, RoundedCornerShape(topStart = cornerRadius, bottomStart = cornerRadius)),
                )
                // Content area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(Spacing.card),
                    verticalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    if (title != null) SectionHeader(title = title, accent = accent)
                    content()
                }
            }
        }
        PanelAccentEdge.Bottom -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .background(surfaceColor, RoundedCornerShape(cornerRadius))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(cornerRadius),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.card),
                    verticalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    if (title != null) SectionHeader(title = title, accent = accent)
                    content()
                }
                // Bottom accent bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(accentBarWidth)
                        .background(accent, RoundedCornerShape(bottomStart = cornerRadius, bottomEnd = cornerRadius)),
                )
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
//  SECTION 4 — ArsenalButton
// ───────────────────────────────────────────────────────────────────────────

/**
 * Style variant for [ArsenalButton].
 */
enum class ArsenalButtonStyle { Primary, Secondary }

/**
 * Direction-C angular button with a single cut corner.
 *
 * - **Primary**: filled with [accent] (default [AccentBar.Red]), near-black text.
 * - **Secondary**: dark (#141419) fill with a 1 dp [accent]-colored outline.
 *
 * The top-end corner is beveled (12 dp diagonal) via [CutCornerShape], giving
 * the aggressive angular look from the Direction C brief. The overall height
 * is fixed at 44 dp to match a comfortable touch target.
 *
 * @param label   Button label text (rendered uppercase, tracked).
 * @param onClick Click callback.
 * @param style   [ArsenalButtonStyle.Primary] or [ArsenalButtonStyle.Secondary].
 * @param accent  Accent color; defaults to [AccentBar.Red].
 * @param enabled Whether interaction is enabled; when false the button is
 *                rendered at 38 % alpha.
 * @param modifier Standard compose modifier.
 */
@Composable
fun ArsenalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ArsenalButtonStyle = ArsenalButtonStyle.Primary,
    accent: Color = AccentBar.Red,
    enabled: Boolean = true,
) {
    val shape = CutCornerShape(corner = CutCorner.TopEnd, cutSize = 12.dp, cornerRadius = 4.dp)
    val alpha = if (enabled) 1f else 0.38f

    val bg = when (style) {
        ArsenalButtonStyle.Primary   -> accent.copy(alpha = alpha)
        ArsenalButtonStyle.Secondary -> ArsenalText.Surface.copy(alpha = alpha)
    }
    val textColor = when (style) {
        ArsenalButtonStyle.Primary   -> Color(0xFF0A0A0E)
        ArsenalButtonStyle.Secondary -> accent.copy(alpha = alpha)
    }

    val borderMod = when (style) {
        ArsenalButtonStyle.Secondary -> Modifier.border(1.dp, accent.copy(alpha = alpha), shape)
        ArsenalButtonStyle.Primary   -> Modifier
    }

    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(bg)
            .then(borderMod)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = textColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ───────────────────────────────────────────────────────────────────────────
//  SECTION 5 — MetricTile
// ───────────────────────────────────────────────────────────────────────────

/**
 * Direction-C metric tile — the primary at-a-glance stat widget.
 *
 * Dark tile (#0C0C10) with:
 * - Uppercase, tightly letter-spaced gray label at the top.
 * - Big bold monospace value + small unit suffix at baseline alignment.
 * - A 2 dp colored bottom accent bar.
 *
 * Replaces [StatTile] as the C-aesthetic primitive. Signatures differ so
 * screens can choose which to use; [StatTile] is restyled in-place for
 * backwards-compat.
 *
 * @param label      Metric name (e.g. "SoC Temp"). Rendered uppercase.
 * @param value      Current reading as a formatted string (e.g. "72.3").
 * @param unit       Optional suffix rendered smaller and muted (e.g. "°C").
 * @param accent     Bottom-bar accent color for this metric's category.
 * @param valueColor Optional override for the value text color (e.g. red
 *                   when temperature is high). Defaults to white.
 * @param modifier   Standard compose modifier.
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color? = null,
) {
    val tileBg = ArsenalText.Tile
    val accentBarHeight = 3.dp
    val cornerRadius = 4.dp

    Column(
        modifier = modifier
            .background(tileBg, RoundedCornerShape(cornerRadius))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(cornerRadius)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.group, vertical = Spacing.dense),
        ) {
            // Label: uppercase, letter-spaced, muted gray
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ArsenalText.Label,
                letterSpacing = 0.07.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.dense))
            // Value + unit row, baseline-aligned
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = valueColor ?: Color.White,
                    maxLines = 1,
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = ArsenalText.Dim,
                        modifier = Modifier.padding(bottom = 3.dp),
                        maxLines = 1,
                    )
                }
            }
        }
        // 2dp bottom accent bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(accentBarHeight)
                .background(accent, RoundedCornerShape(bottomStart = cornerRadius, bottomEnd = cornerRadius)),
        )
    }
}

// ───────────────────────────────────────────────────────────────────────────
//  SECTION 6 — StatBar
// ───────────────────────────────────────────────────────────────────────────

/**
 * Direction-C labeled progress bar — for CPU/GPU loads, frequencies,
 * and any bounded metric displayed as a fraction.
 *
 * Layout: `[LABEL]          [VALUE]`
 *          `[━━━━━━━░░░░░░░]` (flat, slightly slanted via a drawBehind skew)
 *
 * The bar fill has a flat-top / angular clip to reinforce the C aesthetic.
 * If [slanted] is true the right edge of the filled portion is slanted
 * (2 dp skew) instead of vertical — this is the "Direction C bar" look.
 *
 * @param label    Left label text.
 * @param value    Right value text (formatted string).
 * @param fraction Fill fraction [0..1].
 * @param accent   Fill color.
 * @param slanted  When true, the fill's right edge is skewed 2 dp for the
 *                 angular look. Defaults to true.
 * @param modifier Standard compose modifier.
 */
@Composable
fun StatBar(
    label: String,
    value: String,
    fraction: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    slanted: Boolean = true,
) {
    val clampedFraction = fraction.coerceIn(0f, 1f)
    val trackColor = Color(0xFF1E1E26)
    val barHeight = 7.dp

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ArsenalText.Label,
                letterSpacing = 0.04.sp,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(Spacing.dense))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(trackColor, RoundedCornerShape(2.dp))
                .drawBehind {
                    val fillWidth = size.width * clampedFraction
                    if (fillWidth <= 0f) return@drawBehind
                    val skew = if (slanted && fillWidth < size.width) 4f else 0f
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(fillWidth - skew, 0f)
                        lineTo(fillWidth, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, accent)
                },
        )
    }
}

// ───────────────────────────────────────────────────────────────────────────
//  SECTION 7 — StatusPill
// ───────────────────────────────────────────────────────────────────────────

/**
 * Direction-C status badge — small, squared (3 dp radius), tinted.
 *
 * Used for live/running badges ("LIVE", "PSERVER"), tier labels
 * (ROOT, ADB, NONE), and any compact category indicator.
 *
 * @param text   Label text. Rendered uppercase with tight tracking.
 * @param accent Badge fill color (tinted at 18 % alpha over a dark base);
 *               the text itself is rendered in [accent] at full opacity.
 * @param modifier Standard compose modifier.
 */
@Composable
fun StatusPill(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val pillBg = ArsenalText.Tile.copy(alpha = 1f)
    // Tinted background: blend 18 % accent over near-black
    val tinted = Color(
        red   = accent.red   * 0.18f + pillBg.red   * 0.82f,
        green = accent.green * 0.18f + pillBg.green * 0.82f,
        blue  = accent.blue  * 0.18f + pillBg.blue  * 0.82f,
        alpha = 1f,
    )

    Box(
        modifier = modifier
            .background(tinted, RoundedCornerShape(3.dp))
            .border(0.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.sp,
            maxLines = 1,
        )
    }
}

// ───────────────────────────────────────────────────────────────────────────
//  SECTION 8 — SectionHeader
// ───────────────────────────────────────────────────────────────────────────

/**
 * Direction-C section header — thin colored leading bar + uppercase tracked title.
 *
 * Replaces the plain [Text] titles used inside [SectionCard] with an
 * angular, color-keyed header. The 3×16 dp accent tick immediately
 * associates the section with its category color.
 *
 * @param title  Section label. Rendered uppercase with letter-spacing.
 * @param accent Leading tick color.
 * @param modifier Standard compose modifier.
 */
@Composable
fun SectionHeader(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.dense + 2.dp),
    ) {
        // 3 dp wide x 18 dp tall colored tick
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .background(accent, RoundedCornerShape(1.dp)),
        )
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
