package io.github.mayusi.calibratesoc.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Force-dark Material 3 theme. Handhelds live in every lighting condition
 * and a bright dashboard scorches the eyes in low light — we pick the look
 * that works everywhere instead of toggling with the OS.
 *
 * No dynamicColor: Material You pulls from wallpaper which makes chart
 * contrast unpredictable across devices. Calibrate SoC should look the
 * same on every Odin, Retroid, Anbernic, and Pixel.
 *
 * Palette intentionally mirrors EmuTran so the two apps look like siblings
 * to a user who has both installed.
 */

/**
 * User-selectable accent colors for the dark theme. The SETTINGS agent
 * persists the chosen value in DataStore and passes it to
 * [CalibrateSocTheme]; the theme recolors [primary] (and [primaryContainer])
 * accordingly. Default is [BLUE] which matches the original hand-tuned
 * palette — nothing changes visually until the user picks a different accent.
 */
enum class AccentColor(
    val color: Color,
    /** Tinted container (≈ deep saturated dark of the hue). */
    val containerColor: Color,
) {
    BLUE(Color(0xFF60A5FA), Color(0xFF1E3A8A)),
    PURPLE(Color(0xFFA78BFA), Color(0xFF2E1B6B)),
    EMERALD(Color(0xFF34D399), Color(0xFF064E3B)),
    AMBER(Color(0xFFF59E0B), Color(0xFF451A03)),
    ROSE(Color(0xFFFB7185), Color(0xFF4C0519)),
}

private fun buildColorScheme(accent: AccentColor) = darkColorScheme(
    primary = accent.color,
    // onPrimary should remain legible on any accent — deep near-black works for all.
    onPrimary = Color(0xFF0A1424),
    primaryContainer = accent.containerColor,
    onPrimaryContainer = Color(0xFFDBEAFE),

    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1E1B4B),

    // Emerald is used for the "Root" capability badge — positive signal.
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF064E3B),

    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),

    surface = Color(0xFF111827),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFFCBD5E1),

    outline = Color(0xFF475569),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
)

private val CalibrateSocTypography = Typography()

/**
 * Root theme composable for the app. Pass an [accent] to recolor
 * [primary] / [primaryContainer] across the entire screen hierarchy.
 *
 * **SETTINGS agent TODO:** read the persisted accent from DataStore
 * (add a `stringPreferencesKey("accent_color")` in [UserPrefs], map
 * the string to [AccentColor] via `AccentColor.valueOf(...)`, expose it
 * as a Flow, then collect it in [MainActivity] via
 * `collectAsStateWithLifecycle()` and pass it here):
 *
 * ```kotlin
 * // In MainActivity.setContent { … }
 * val accent by userPrefs.accentColor.collectAsStateWithLifecycle(AccentColor.BLUE)
 * CalibrateSocTheme(accent = accent) { … }
 * ```
 *
 * Until the SETTINGS agent wires this, the default [AccentColor.BLUE]
 * keeps the app looking exactly as before.
 */
@Composable
fun CalibrateSocTheme(
    accent: AccentColor = AccentColor.BLUE,
    content: @Composable () -> Unit,
) {
    val colorScheme = buildColorScheme(accent)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Theme is also hosted inside the OverlayService's
            // WindowManager view, where the context is a Service (not
            // an Activity) and there's no window to tint. A hard cast
            // here used to crash the app the moment the user tapped
            // Show HUD. Skip the status-bar tint in that case.
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CalibrateSocTypography,
        content = content,
    )
}
