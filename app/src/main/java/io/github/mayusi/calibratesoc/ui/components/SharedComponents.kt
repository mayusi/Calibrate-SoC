package io.github.mayusi.calibratesoc.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Shared UI components used across Benchmark, Hardware, Dashboard, and
 * other screens. Extracted here so each screen doesn't re-define the
 * same card/row patterns.
 */

/**
 * Section card with a title and arbitrary content. Used across all
 * screens for consistent dark-theme card styling.
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
            if (icon != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

/**
 * A labeled key-value row. [label] is body-small / onSurfaceVariant;
 * [value] is monospace body-small. Optional [explainer] shown below the
 * row in labelSmall/onSurfaceVariant for per-metric context.
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
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (explainer != null) {
            Text(
                explainer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Compact stat tile: large monospace SemiBold value (titleMedium) with an
 * optional small unit suffix, and a smaller label below (labelMedium,
 * onSurfaceVariant). Unifies Dashboard's private GlanceStat and any
 * benchmark category mini-cards.
 *
 * @param valueColor Override color for the value text (e.g. temperature
 *   severity). Defaults to onSurface.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color? = null,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            )
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Standardised empty-state widget: centered column with a muted icon,
 * title, body message, and an optional action slot (e.g. a retry button).
 *
 * @param icon Vector icon drawn at 44 dp with 50 % alpha (onSurfaceVariant).
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.dense))
            action()
        }
    }
}

/**
 * Inline alert / status card for errors, warnings, and informational
 * messages. Renders a tinted container (icon color at ~12 % alpha over
 * the surface) with an icon, title + message, and an optional trailing
 * action slot.
 */
enum class AlertType { ERROR, WARNING, INFO }

@Composable
fun AlertCard(
    type: AlertType,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val (icon, color) = when (type) {
        AlertType.ERROR -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        AlertType.WARNING -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.secondary
        AlertType.INFO -> Icons.Outlined.Info to MaterialTheme.colorScheme.primary
    }
    val tint = color.copy(alpha = 0.12f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 1f).let { surface ->
                    // Blend tint over surface by hand: result = tint * alpha + surface * (1 - alpha)
                    Color(
                        red = tint.red * tint.alpha + surface.red * (1f - tint.alpha),
                        green = tint.green * tint.alpha + surface.green * (1f - tint.alpha),
                        blue = tint.blue * tint.alpha + surface.blue * (1f - tint.alpha),
                        alpha = 1f,
                    )
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .padding(Spacing.item),
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = type.name,
            tint = color,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            action()
        }
    }
}
