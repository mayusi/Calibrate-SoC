package io.github.mayusi.calibratesoc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.ui.theme.AccentColor
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.display.RefreshRateController
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
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
    @Inject lateinit var pServerWriter: PServerWriter
    @Inject lateinit var capabilityProbe: CapabilityProbe

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
        lifecycleScope.launch { applyPreferredRefreshRate() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { applyPreferredRefreshRate() }
        // FIX 2: invalidate the PServer transactable cache on every resume so
        // that returning from Odin Settings (where the user ran the unlock
        // script) re-probes PServer and lights up PServer-LIVE automatically.
        // The invalidation + re-probe is cheap: one binder null-check + one
        // transact("true") at most; no-op on non-AYN devices (binder() returns null).
        lifecycleScope.launch {
            pServerWriter.invalidateTransactableCache()
            capabilityProbe.refresh()
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
