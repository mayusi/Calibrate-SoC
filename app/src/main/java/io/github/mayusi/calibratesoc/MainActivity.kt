package io.github.mayusi.calibratesoc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.ui.theme.AccentColor
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.data.autotdp.AyaneoStockReconciler
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.display.RefreshRateController
import io.github.mayusi.calibratesoc.data.fancurve.FanCurveController
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.ui.CalibrateSocApp
import io.github.mayusi.calibratesoc.ui.setup.OnboardingScreen
import io.github.mayusi.calibratesoc.ui.theme.CalibrateSocTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single host activity. All screens are Compose destinations inside
 * [CalibrateSocApp]. Also applies the user's saved preferred refresh
 * rate on every resume — without this, the Window snaps back to the
 * panel's default Hz between launches.
 *
 * onResume also invalidates the PServer transactable cache so that returning
 * to the app after running the unlock script lights up PServer-LIVE without
 * requiring a full kill-and-relaunch (FIX 2).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var refreshRateController: RefreshRateController
    @Inject lateinit var userPrefs: UserPrefs
    @Inject lateinit var capabilityProbe: CapabilityProbe
    @Inject lateinit var fanCurveController: FanCurveController

    /** 0.3.6: AYANEO parked-mode self-heal — see [AyaneoStockReconciler]. Re-checked on
     *  every resume (in addition to the process-start check in CalibrateSocApplication) so
     *  returning to the app after backgrounding also self-heals, not just cold launches. */
    @Inject lateinit var ayaneoStockReconciler: AyaneoStockReconciler

    @OptIn(ExperimentalComposeUiApi::class) // testTagsAsResourceId (uiautomator a11y fix)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Collect the user's saved accent so the entire app recolors
            // immediately when changed in Settings. Default = BLUE which
            // is visually identical to the original palette (no visible
            // change until the user picks a different accent).
            val accent by userPrefs.accentColor.collectAsStateWithLifecycle(AccentColor.BLUE)
            CalibrateSocTheme(accent = accent) {
                // ACCESSIBILITY / TESTABILITY: wrap the whole app in a root Box
                // that enables `testTagsAsResourceId`. Whenever the accessibility
                // framework actually queries this window (TalkBack on, or a
                // cooperating uiautomator/UiAutomation connection), Compose exports
                // its full semantics tree AND surfaces every `Modifier.testTag(...)`
                // as a dumpable `resource-id`, so screens are addressable for
                // automated UI verification (verified on Odin 3 / AYANEO Pocket DS:
                // calibrate_root, dashboard_screen, bottom_nav, nav_*, tier_badge all
                // appear). This is the standard, side-effect-free way to make a
                // Compose app uiautomator-addressable.
                //
                // NOTE: it does NOT, by itself, resurrect a NULL accessibility root
                // on a firmware whose UiAutomation never queries Compose windows at
                // all (observed on the Retroid Pocket 6 firmware RP6_V1.0.0.406 —
                // a device-side defect; see the task report). Forcing Compose's
                // internal a11y flag on to work around that CRASHES the app
                // (AccessibilityManager.sendAccessibilityEvent throws when the
                // framework's a11y is off), so we deliberately do NOT do that.
                //
                // The Box is fillMaxSize + no padding/background, so layout and
                // appearance are byte-for-byte identical to before.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .testTag("calibrate_root"),
                ) {
                    // Gate: show onboarding wizard until user finishes or
                    // explicitly skips it. State persisted in DataStore so
                    // it appears exactly once per install (or after a wipe).
                    val done by userPrefs.onboardingComplete.collectAsState(initial = null)
                    when (done) {
                        null -> { /* DataStore still loading — render nothing for ~1 frame */ }
                        false -> OnboardingScreen(onFinished = { /* state flip triggers recompose */ })
                        true -> CalibrateSocApp()
                    }
                }
            }
        }
        lifecycleScope.launch { applyPreferredRefreshRate() }
        // H2: honour the user's "Re-apply when the app opens" fan-curve toggle.
        // Runs ONCE per cold launch (the literal "app opens" moment, not every
        // foreground resume — re-asserting on each resume would needlessly bounce
        // the live fan). The controller self-gates: it no-ops unless the toggle is
        // on AND a curve is saved AND the device is an Odin with a privileged
        // write path, and it inherits applyCurve's verification + honesty (the
        // outcome is logged; it never claims a re-apply it didn't make).
        lifecycleScope.launch { reapplyFanCurveOnOpen() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { applyPreferredRefreshRate() }
        // FIX 2: on every resume, bust BOTH live-write caches and re-probe so that
        // returning from Odin/vendor Settings (where the user ran the unlock script
        // or `pm grant`ed WRITE_SECURE_SETTINGS) re-probes and lights up the live
        // tier automatically — no kill/relaunch. Routed through
        // [CapabilityProbe.fullRefresh] so the PServer + AYANEO cache invalidations
        // live in one place and no caller can forget one. Cheap: a binder null-check
        // + one transact at most per writer, package-presence short-circuit (no IPC)
        // on devices lacking that vendor surface.
        lifecycleScope.launch {
            val report = capabilityProbe.fullRefresh()
            // 0.3.6: self-heal a AYANEO device left parked at a non-stock vendor perf mode
            // by a prior session that was force-killed/crashed before its revert could run.
            // No-op when AutoTDP is actively running or the device is already at stock.
            ayaneoStockReconciler.reconcile(report)
        }
    }

    /**
     * H2: re-assert the saved Odin fan curve on app open when the user enabled
     * the "Re-apply when the app opens" toggle. Delegates to
     * [FanCurveController.maybeReapplyOnOpen], which returns null when nothing
     * was attempted (toggle off / no saved curve / feature unavailable) and an
     * honest [io.github.mayusi.calibratesoc.data.fancurve.ApplyResult] otherwise.
     * We log the outcome so a failed re-apply is never silently treated as
     * success.
     */
    private suspend fun reapplyFanCurveOnOpen() {
        val result = runCatching { fanCurveController.maybeReapplyOnOpen() }.getOrNull()
        if (result != null) {
            android.util.Log.i("CalibrateSoC-FanCurve", "on-open re-apply result: $result")
        }
    }

    private suspend fun applyPreferredRefreshRate() {
        val hz = refreshRateController.preferredHz.first() ?: return
        val modeId = refreshRateController.resolveModeIdForHz(hz) ?: return
        val attrs = window.attributes
        attrs.preferredDisplayModeId = modeId
        attrs.preferredRefreshRate = hz
        window.attributes = attrs
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            runCatching { window.decorView.requestedFrameRate = hz }
        }
    }
}
