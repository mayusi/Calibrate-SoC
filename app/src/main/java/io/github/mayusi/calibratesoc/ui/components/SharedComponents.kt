package io.github.mayusi.calibratesoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Shared UI components used across Benchmark, Hardware, Dashboard, and
 * other screens. Extracted here so each screen doesn't re-define the
 * same card/row patterns.
 *
 * All public composable signatures are UNCHANGED from the original
 * SharedComponents — existing screens compile without modification.
 * The visual implementation has been updated to the Direction-C
 * ("Arsenal") aesthetic: angular dark surfaces, accent-edged panels,
 * monospace values, tight letter-spacing, and high contrast.
 */

// ── Internal palette ─────────────────────────────────────────────────────
// These aliases now resolve to the single public token source in
// ArsenalComponents.kt (AccentBar / ArsenalText) instead of re-declaring
// their own hex literals. This was the root cause of the hardcoded-color
// drift: two parallel hand-synced palettes (this one + ArsenalComponents)
// that callers couldn't reuse because these constants were private — so
// screens re-typed Color(0xFF999999) etc. instead of referencing a shared
// token. Values are unchanged (verbatim same hex via ArsenalText /
// AccentBar) — this is a pure de-duplication, not a restyle.
private val CArsenalSurface  = ArsenalText.Surface
private val CArsenalTile     = ArsenalText.Tile
private val CAccentNeutral   = AccentBar.Neutral
private val CLabelGray       = ArsenalText.Label
private val CDimGray         = ArsenalText.Dim
private val CBorderSubtle    = Color.White.copy(alpha = 0.06f)
private val CAccentBarWidth  = 3.dp
private val CCornerRadius    = 4.dp

// ─────────────────────────────────────────────────────────────────────────
//  SectionCard — Direction-C restyle, signature unchanged
// ─────────────────────────────────────────────────────────────────────────

/**
 * Section card with a title and arbitrary content. Used across all
 * screens for consistent dark-theme card styling.
 *
 * Direction-C restyle: angular dark surface (#141419), 4 dp radius,
 * a 3 dp neutral left-accent bar, uppercase tracked title with a
 * color tick. Visual behavior mirrors [ArsenalPanel] with a neutral
 * accent so callers that haven't migrated yet immediately pick up the
 * C look without any code change.
 *
 * @param icon Optional leading icon rendered before the title (20 dp,
 *   onSurfaceVariant). Pass null (default) for the original title-only
 *   header — all existing call sites continue to compile unchanged.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    // Determine accent: when an icon is present we use the theme primary
    // (closest to the old icon tint) so the tick color matches the icon.
    // When there is no icon we fall back to the neutral accent.
    val accent = if (icon != null) MaterialTheme.colorScheme.primary else CAccentNeutral

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CArsenalSurface, RoundedCornerShape(CCornerRadius))
            .border(0.5.dp, CBorderSubtle, RoundedCornerShape(CCornerRadius))
            .height(IntrinsicSize.Min),
    ) {
        // Left accent bar
        Box(
            modifier = Modifier
                .width(CAccentBarWidth)
                .fillMaxHeight()
                .background(
                    accent,
                    RoundedCornerShape(topStart = CCornerRadius, bottomStart = CCornerRadius),
                ),
        )
        // Content column
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            // Header row — matches SectionHeader look from ArsenalComponents
            if (icon != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = accent,
                    )
                    Text(
                        title.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.08.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.08.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  StatTile — Direction-C restyle, signature unchanged
// ─────────────────────────────────────────────────────────────────────────

/**
 * Compact stat tile: large monospace SemiBold value (titleMedium) with an
 * optional small unit suffix, and a smaller label below (labelMedium,
 * onSurfaceVariant). Unifies Dashboard's private GlanceStat and any
 * benchmark category mini-cards.
 *
 * Direction-C restyle: dark tile (#0C0C10), uppercase letter-spaced gray
 * label, big mono value, 2 dp neutral bottom accent bar. Visually mirrors
 * [MetricTile] from ArsenalComponents with a neutral accent bar so all
 * existing screens get the C look without any caller changes.
 *
 * @param valueColor Override color for the value text (e.g. temperature
 *   severity). Defaults to white (#FFFFFF).
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color? = null,
) {
    Column(
        modifier = modifier
            .background(CArsenalTile, RoundedCornerShape(CCornerRadius))
            .border(0.5.dp, CBorderSubtle, RoundedCornerShape(CCornerRadius)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.group, vertical = Spacing.dense),
        ) {
            // Uppercase tracked gray label
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = CLabelGray,
                letterSpacing = 0.07.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.dense))
            // Value + unit, baseline-aligned
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = valueColor ?: Color.White,
                    maxLines = 1,
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = CDimGray,
                        modifier = Modifier.padding(bottom = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        }
        // 2 dp bottom accent bar (neutral)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    CAccentNeutral,
                    RoundedCornerShape(bottomStart = CCornerRadius, bottomEnd = CCornerRadius),
                ),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  KvRow — Direction-C restyle, signature unchanged
// ─────────────────────────────────────────────────────────────────────────

/**
 * A labeled key-value row. [label] is body-small / muted gray;
 * [value] is monospace body-small in white. Optional [explainer] shown
 * below the row in labelSmall/muted for per-metric context.
 *
 * Direction-C restyle: label is gray (#999), value is white monospace,
 * explainer is #777. No container changes — KvRow lives inside panels.
 */
@Composable
fun KvRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    explainer: String? = null,
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = CLabelGray,
            )
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
        }
        if (explainer != null) {
            Text(
                text = explainer,
                style = MaterialTheme.typography.labelSmall,
                color = CDimGray,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  EmptyState — Direction-C restyle, signature unchanged
// ─────────────────────────────────────────────────────────────────────────

/**
 * Standardised empty-state widget: centered column with a muted icon,
 * title, body message, and an optional action slot (e.g. a retry button).
 *
 * Direction-C restyle: icon tinted with neutral accent at 45 % alpha,
 * title is white, body is CLabelGray.
 *
 * @param icon Vector icon drawn at 44 dp.
 * @param action Optional trailing composable (e.g. a Button) placed below
 *   the body text.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = CAccentNeutral.copy(alpha = 0.45f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = CLabelGray,
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.dense))
            action()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
//  AlertCard / AlertType — Direction-C restyle, signature unchanged
// ─────────────────────────────────────────────────────────────────────────

/**
 * Alert severity level for [AlertCard].
 */
enum class AlertType { ERROR, WARNING, INFO }

/**
 * Inline alert / status card for errors, warnings, and informational
 * messages. Renders a tinted angular container (icon color at ~15 % alpha
 * blended over the Arsenal surface) with an icon, title + message, and an
 * optional trailing action slot.
 *
 * Direction-C restyle: angular (#141419 base), 4 dp radius, 1 dp border in
 * the alert color at 25 % alpha, tight typography. The layout and public
 * API are identical to the previous implementation.
 */
@Composable
fun AlertCard(
    type: AlertType,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val (icon, accentColor) = when (type) {
        AlertType.ERROR   -> Icons.Outlined.ErrorOutline to AccentBar.Red
        AlertType.WARNING -> Icons.Outlined.WarningAmber to AccentBar.Amber
        AlertType.INFO    -> Icons.Outlined.Info          to AccentBar.Blue
    }

    // Tinted background: blend 15 % accent over Arsenal surface
    val base = CArsenalSurface
    val tintedBg = Color(
        red   = accentColor.red   * 0.15f + base.red   * 0.85f,
        green = accentColor.green * 0.15f + base.green * 0.85f,
        blue  = accentColor.blue  * 0.15f + base.blue  * 0.85f,
        alpha = 1f,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tintedBg, RoundedCornerShape(CCornerRadius))
            .border(0.5.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(CCornerRadius))
            .padding(Spacing.item),
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = type.name,
            tint = accentColor,
            modifier = Modifier.size(18.dp).padding(top = 1.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                letterSpacing = 0.04.sp,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = CLabelGray,
            )
        }
        if (action != null) {
            action()
        }
    }
}
