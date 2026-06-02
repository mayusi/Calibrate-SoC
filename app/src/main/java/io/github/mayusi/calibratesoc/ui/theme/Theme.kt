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
private val CalibrateSocColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0A1424),
    primaryContainer = Color(0xFF1E3A8A),
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

@Composable
fun CalibrateSocTheme(content: @Composable () -> Unit) {
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
        colorScheme = CalibrateSocColors,
        typography = CalibrateSocTypography,
        content = content,
    )
}
