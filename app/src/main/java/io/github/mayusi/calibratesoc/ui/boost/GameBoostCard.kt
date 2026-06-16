package io.github.mayusi.calibratesoc.ui.boost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.boost.GameBoostState
import io.github.mayusi.calibratesoc.data.boost.GameBoostStatus
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.PanelAccentEdge
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Game Boost card — one-tap "max performance" session.
 *
 * Honesty contract:
 *  - Always shows the "plug in recommended" and "auto-reverts" notes.
 *  - Shows time-box countdown while boosting.
 *  - Shows [GameBoostState.skippedNodes] honestly ("couldn't pin: N nodes").
 *  - When AutoTDP is running, disables the Start button and explains why.
 *  - Thermal-trip and time-box-expiry states show the reason clearly.
 *
 * This card is embedded in both the Dashboard and the Tune hub (Advanced tab).
 *
 * @param viewModel  Injected [GameBoostViewModel].
 */
@Composable
fun GameBoostCard(
    modifier: Modifier = Modifier,
    viewModel: GameBoostViewModel = hiltViewModel(),
) {
    val boostState by viewModel.boostState.collectAsStateWithLifecycle()
    val canStart by viewModel.canStart.collectAsStateWithLifecycle()
    val autoTdpRunning by viewModel.autoTdpRunning.collectAsStateWithLifecycle()

    GameBoostCardContent(
        boostState = boostState,
        canStart = canStart,
        autoTdpRunning = autoTdpRunning,
        onStart = { viewModel.startBoost() },
        onStop = { viewModel.stopBoost() },
        modifier = modifier,
    )
}

@Composable
internal fun GameBoostCardContent(
    boostState: GameBoostState,
    canStart: Boolean,
    autoTdpRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBoosting = boostState.status == GameBoostStatus.BOOSTING
    val panelAccent = when (boostState.status) {
        GameBoostStatus.BOOSTING -> AccentBar.Red
        GameBoostStatus.THERMAL_TRIPPED -> AccentBar.Amber
        GameBoostStatus.TIME_BOX_EXPIRED -> AccentBar.Neutral
        GameBoostStatus.LIVE_UNAVAILABLE -> AccentBar.Amber
        GameBoostStatus.WRITE_DENIED -> AccentBar.Red
        else -> AccentBar.Red
    }

    ArsenalPanel(
        accent = panelAccent,
        title = "Game boost",
        accentEdge = PanelAccentEdge.Bottom,
        modifier = modifier,
    ) {
        // Status pill row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(
                text = when (boostState.status) {
                    GameBoostStatus.IDLE -> "READY"
                    GameBoostStatus.BOOSTING -> "BOOSTING"
                    GameBoostStatus.TIME_BOX_EXPIRED -> "EXPIRED"
                    GameBoostStatus.THERMAL_TRIPPED -> "THERMAL TRIP"
                    GameBoostStatus.WRITE_DENIED -> "WRITE DENIED"
                    GameBoostStatus.LIVE_UNAVAILABLE -> "UNAVAILABLE"
                    GameBoostStatus.STOPPED -> "STOPPED"
                },
                accent = panelAccent,
            )
            if (isBoosting) {
                TimeBoxCountdown(expiresEpochMs = boostState.timeBoxExpiresEpochMs)
            }
        }

        Spacer(Modifier.height(Spacing.dense))

        // Description — always honest about what this is.
        Text(
            text = boostState.autoRevertsNote,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )

        // Mutual exclusion warning
        if (autoTdpRunning && !isBoosting) {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                text = "AutoTDP is managing clocks. Stop AutoTDP first to start Game Boost.",
                style = MaterialTheme.typography.bodySmall,
                color = AccentBar.Amber,
            )
        }

        // BOOSTING details
        if (isBoosting) {
            Spacer(Modifier.height(Spacing.dense))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(Spacing.dense))

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                KvRow(
                    label = "PINNED NODES",
                    value = "${boostState.pinnedNodeCount}",
                    explainer = "Kernel nodes locked to max performance.",
                )
                if (boostState.skippedNodes.isNotEmpty()) {
                    KvRow(
                        label = "COULDN'T PIN",
                        value = "${boostState.skippedNodes.size} nodes",
                        explainer = "Not writable on this firmware: ${boostState.skippedNodes.joinToString(", ")}",
                    )
                }
                boostState.lastHottestTempC?.let { temp ->
                    KvRow(
                        label = "HOTTEST ZONE",
                        value = "${"%.1f".format(temp)} C",
                        explainer = "Live temperature — boost stops if this exceeds the thermal limit.",
                    )
                }
                if (boostState.plugInRecommended) {
                    Text(
                        text = "Plug in recommended — brute-max drains battery fast.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBar.Amber,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Terminal state details
        when (boostState.status) {
            GameBoostStatus.THERMAL_TRIPPED -> {
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    text = "Stopped: ${boostState.thermalTripReason ?: "Thermal limit reached — all writes reverted."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBar.Amber,
                )
            }
            GameBoostStatus.TIME_BOX_EXPIRED -> {
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    text = "Time box expired — all writes reverted automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            }
            GameBoostStatus.WRITE_DENIED -> {
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    text = "Write denied: ${boostState.writeFailure ?: "a kernel write was rejected — all writes reverted."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBar.Red,
                )
            }
            GameBoostStatus.LIVE_UNAVAILABLE -> {
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    text = "Live writes unavailable: ${boostState.liveUnavailableReason ?: "sysfs not writable on this device."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentBar.Amber,
                )
            }
            else -> Unit
        }

        Spacer(Modifier.height(Spacing.group))

        // Action button — large, cut-corner, Red accent (max-aggression mode)
        if (isBoosting) {
            ArsenalButton(
                label = "Stop boost",
                onClick = onStop,
                accent = AccentBar.Neutral,
                style = ArsenalButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ArsenalButton(
                label = "Game boost",
                onClick = onStart,
                accent = AccentBar.Red,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Live countdown to the time-box expiry. Ticks every second.
 * Shows "MM:SS remaining" or an empty text when [expiresEpochMs] is null.
 */
@Composable
private fun TimeBoxCountdown(expiresEpochMs: Long?) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val remaining = expiresEpochMs?.let { exp ->
        val diff = (exp - nowMs).coerceAtLeast(0L)
        val totalSec = diff / 1_000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        "%02d:%02d".format(min, sec)
    } ?: "--:--"

    Text(
        text = "$remaining remaining",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = AccentBar.Red,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.04.sp,
    )
}
