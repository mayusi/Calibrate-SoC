package io.github.mayusi.calibratesoc.ui.thermal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.thermal.ThrottleGuardState
import io.github.mayusi.calibratesoc.data.thermal.ThrottleGuardStatus
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Predictive Throttle Guard toggle panel.
 *
 * Shows:
 *  - Enable/disable toggle.
 *  - Running state: "running", "suppressed" (with explainer), "stopped".
 *  - Last forecast reason when the guard has predicted a throttle.
 *  - [ThrottleGuardState.willThrottleInSec] when a throttle is imminent.
 *  - Active pre-emptive cap when one is applied.
 *
 * When suppressed, shows a clear explainer ("AutoTDP/Boost is handling thermals")
 * so the user understands why the guard is standing down instead of appearing broken.
 *
 * Lives on the Tune -> Advanced tab (or a thermal section within it).
 */
@Composable
fun ThrottleGuardPanel(
    modifier: Modifier = Modifier,
    viewModel: ThrottleGuardViewModel = hiltViewModel(),
) {
    val guardState by viewModel.guardState.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val suppressionReason by viewModel.suppressionReason.collectAsStateWithLifecycle()

    ThrottleGuardPanelContent(
        guardState = guardState,
        isEnabled = isEnabled,
        suppressionReason = suppressionReason,
        onSetEnabled = { viewModel.setEnabled(it) },
        modifier = modifier,
    )
}

@Composable
internal fun ThrottleGuardPanelContent(
    guardState: ThrottleGuardState,
    isEnabled: Boolean,
    suppressionReason: String?,
    onSetEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        guardState.suppressed -> AccentBar.Neutral
        guardState.capApplied -> AccentBar.Amber
        isEnabled -> AccentBar.Emerald
        else -> AccentBar.Neutral
    }

    ArsenalPanel(
        accent = accent,
        title = "Predictive throttle guard",
        modifier = modifier,
    ) {
        // Toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pre-emptive throttle prevention",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = "Caps the big cluster slightly before the kernel trips, so FPS degrades smoothly instead of dropping off a cliff.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF999999),
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onSetEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF0A0A0E),
                    checkedTrackColor = AccentBar.Emerald,
                    uncheckedThumbColor = Color(0xFF888888),
                    uncheckedTrackColor = Color(0xFF2A2A2A),
                ),
            )
        }

        // Status
        if (isEnabled) {
            Spacer(Modifier.height(Spacing.dense))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                StatusPill(
                    text = when {
                        guardState.suppressed -> "SUPPRESSED"
                        guardState.capApplied -> "CAP ACTIVE"
                        guardState.status == ThrottleGuardStatus.RUNNING -> "MONITORING"
                        guardState.status == ThrottleGuardStatus.LIVE_UNAVAILABLE -> "UNAVAILABLE"
                        else -> "STOPPED"
                    },
                    accent = accent,
                )
            }

            // Suppression explainer (priority display)
            if (guardState.suppressed && suppressionReason != null) {
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    text = suppressionReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
                Text(
                    text = "The guard is standing down but will resume automatically when the owner stops.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF666666),
                )
            }

            // Active pre-emptive cap
            if (!guardState.suppressed && guardState.capApplied) {
                Spacer(Modifier.height(Spacing.dense))
                val capMhz = guardState.activeCapKhz?.let { it / 1000 }
                Text(
                    text = "Pre-emptive cap: ${capMhz ?: "??"} MHz",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentBar.Amber,
                )
            }

            // Throttle forecast
            guardState.willThrottleInSec?.let { sec ->
                if (!guardState.suppressed) {
                    Spacer(Modifier.height(Spacing.dense))
                    Text(
                        text = "Throttle predicted in ~${sec}s",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentBar.Red,
                    )
                }
            }

            // Last forecast reason
            if (guardState.lastForecastReason.isNotBlank() && !guardState.suppressed) {
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    text = "Forecast: ${guardState.lastForecastReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888),
                )
            }

            // LIVE unavailable
            if (guardState.status == ThrottleGuardStatus.LIVE_UNAVAILABLE) {
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    text = "Live writes unavailable: ${guardState.liveUnavailableReason ?: "sysfs not writable."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBar.Amber,
                )
            }
        }
    }
}
