package io.github.mayusi.calibratesoc.ui.capability

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * How often the foreground poll re-probes capability while a tier-showing screen
 * is STARTED. 2 s is a good balance: fast enough that an out-of-app `pm grant`
 * (or Shizuku/root/vendor-binder unlock) reflects in the tier badge within ~2 s
 * without the user reopening the app, cheap enough to be invisible on battery
 * (the probe is a binder null-check + one transact at most; a package-presence
 * short-circuit on devices lacking the vendor surface).
 */
const val CAPABILITY_POLL_MS = 2000L

/**
 * Keeps the [io.github.mayusi.calibratesoc.data.capability.CapabilityReport]
 * fresh WHILE a tier-showing screen (Dashboard / Tune / Settings) is on screen,
 * WITHOUT requiring a kill/relaunch after the user grants live-tuning access out
 * of band.
 *
 * The problem it solves: `adb shell pm grant … WRITE_SECURE_SETTINGS` (or a
 * Shizuku/root/vendor grant) performed WHILE the app stays foregrounded fires no
 * lifecycle transition and Android emits no permission-change broadcast, so a
 * pull-only [CapabilityProbe.refresh] never re-runs and the tier stays stale.
 *
 * Two triggers, both routed through [onRefresh] (wire it to
 * `vm.fullRefresh()` so both writer caches are busted before re-probing):
 *
 *  1. **ON_RESUME hook** — the instant we return to the app (e.g. back from the
 *     adb terminal or the vendor settings screen), re-probe immediately instead
 *     of waiting for the next poll tick. Mirrors the OnboardingScreen pattern.
 *
 *  2. **2 s foreground poll** — a [repeatOnLifecycle] loop scoped to
 *     [Lifecycle.State.STARTED]. This is THE fix for "granted via adb while the
 *     app never lost focus": the poll re-probes every [CAPABILITY_POLL_MS] and,
 *     crucially, `repeatOnLifecycle(STARTED)` SUSPENDS the loop the moment the
 *     screen is backgrounded and resumes it on return — so it NEVER runs in the
 *     background and costs nothing on battery when the user isn't looking.
 *
 * [onRefresh] must be cheap + idempotent (fullRefresh is) so overlapping ticks
 * are harmless; no in-flight guard is needed at this cadence.
 */
@Composable
fun CapabilityAutoRefresh(onRefresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keep the latest lambda without restarting the effects each recomposition.
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    // Trigger 1: immediate re-probe on every ON_RESUME (return-to-app).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentOnRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Trigger 2: 2 s foreground poll, auto-suspended when backgrounded.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                currentOnRefresh()
                delay(CAPABILITY_POLL_MS)
            }
        }
    }
}
