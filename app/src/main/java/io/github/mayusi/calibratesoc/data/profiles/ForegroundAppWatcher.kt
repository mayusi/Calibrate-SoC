package io.github.mayusi.calibratesoc.data.profiles

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.devicedb.FanAdapterKind
import io.github.mayusi.calibratesoc.data.display.RefreshRateController
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "ForegroundAppWatcher"

/**
 * AccessibilityService that fires TYPE_WINDOW_STATE_CHANGED whenever
 * the user switches apps. We compare the new top package against the
 * per-app bundle map (or the legacy override map for back-compat) and
 * auto-apply the full tune bundle.
 *
 * ## Bundle lookup order (back-compat)
 * 1. [ProfileStore.perAppBundles] — the new full-bundle map.
 * 2. [ProfileStore.perAppOverrides] — legacy profileId string, wrapped into
 *    a minimal [PerAppBundle(profileId=...)] so the apply logic is uniform.
 *
 * ## Bundle apply order (on game launch)
 * 1. Profile / preset via [ProfileApplier] + [PresetSafetyGate].
 * 2. AutoTDP goal via [AutoTdpController].
 * 3. Refresh-rate intent stored to [RefreshRateController].
 * 4. Fan mode via the existing SETTINGS_SYSTEM writer path.
 * 5. Game Boost via [GameBoostLauncher].
 * 6. Background-app reaper via [AppReaper].
 *
 * ## Revert on exit
 * When the user leaves a mapped game (foreground switches to a package that
 * is NOT in the bundle map), the watcher reverts:
 *   - Stops AutoTDP (if we started it).
 *   - Stops GameBoost (if we started it).
 *   - Clears the refresh-rate preference (null = system default).
 *   - Reverts fan mode to the snapshot taken at apply time.
 *   - Profile writes are NOT reverted (persistent user intent; same as before).
 *   - Reaped apps are NOT re-launched — Android relaunches them lazily.
 *
 * ## Performance
 * All work is offloaded to Dispatchers.IO. onAccessibilityEvent does nothing
 * but a string compare and a flag check — it must stay cheap because Android
 * calls us on every focus change.
 *
 * ## Write-phase safety (BUG 5 fix preserved)
 * A write-phase flag prevents cancelling a job mid-journal. If a write is
 * in flight the new apply request is dropped; the next genuine foreground
 * switch re-triggers.
 */
@AndroidEntryPoint
class ForegroundAppWatcher : AccessibilityService() {

    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var profileApplier: ProfileApplier
    @Inject lateinit var capabilityProbe: CapabilityProbe
    @Inject lateinit var autoTdpController: AutoTdpController
    @Inject lateinit var refreshRateController: RefreshRateController
    @Inject lateinit var tunableWriter: TunableWriter
    @Inject lateinit var deviceAdapterRegistry: DeviceAdapterRegistry
    @Inject lateinit var gameBoostLauncher: GameBoostLauncher
    @Inject lateinit var appReaper: AppReaper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastForegroundPackage: String? = null
    private var inFlight: Job? = null

    // True once the in-flight job has entered the write phase. Cancelling mid-write
    // orphans a snapshot journal entry — let the write complete instead.
    private val inFlightWriting = AtomicBoolean(false)

    // Track the active bundle so we can revert it when the game exits.
    @Volatile private var activeBundlePackage: String? = null
    @Volatile private var activeBundle: PerAppBundle? = null
    @Volatile private var fanModeBeforeBundle: String? = null
    @Volatile private var activeFanModeKey: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastForegroundPackage) return
        // Filter out our own UI + the system UI — switching when the user opens
        // Calibrate SoC itself would trigger infinite loops.
        if (pkg == applicationContext.packageName || pkg.startsWith("com.android.systemui")) return
        lastForegroundPackage = pkg
        scheduleApply(pkg)
    }

    private fun scheduleApply(pkg: String) {
        // BUG FIX (BUG 5): only cancel a pending job if it has NOT yet entered
        // the write phase. Cancelling mid-write orphans a snapshot journal entry.
        if (inFlightWriting.get()) {
            Log.d(TAG, "scheduleApply($pkg): previous job is writing — skipping cancel")
            return
        }
        inFlight?.cancel()
        inFlightWriting.set(false)
        inFlight = scope.launch {
            val store = profileRepository.snapshot()

            // ── Resolve bundle for incoming package (back-compat fallback) ─────────
            val bundle: PerAppBundle? = store.perAppBundles[pkg]
                ?: store.perAppOverrides[pkg]?.let { profileId ->
                    // Wrap the legacy single-profile mapping into a minimal bundle so
                    // the rest of the logic is uniform. Behavior is IDENTICAL to the
                    // old single-profile path.
                    PerAppBundle(profileId = profileId)
                }

            // ── Revert the previous bundle (if we have one and the package changed) ─
            val prevBundle = activeBundle
            val prevPkg = activeBundlePackage
            if (prevBundle != null && prevPkg != null && prevPkg != pkg) {
                revertBundle(prevPkg, prevBundle)
            }

            if (bundle == null) {
                // No mapping for this package — clear active bundle tracking.
                activeBundlePackage = null
                activeBundle = null
                return@launch
            }

            // ── Resolve the capability report once for the whole bundle apply ──────
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()

            // ── 1. Profile / preset ────────────────────────────────────────────────
            bundle.profileId?.let { profileId ->
                val profile = store.profiles.firstOrNull { it.id == profileId }
                if (profile == null) {
                    Log.w(TAG, "scheduleApply($pkg): profileId='$profileId' not found in store")
                } else {
                    inFlightWriting.set(true)
                    val results = try {
                        profileApplier.apply(
                            profile.toPreset(),
                            report,
                            reason = "per-app bundle: $pkg",
                        )
                    } finally {
                        inFlightWriting.set(false)
                    }
                    val failures = results.filterNot {
                        it is WriteResult.Success || it is WriteResult.CapabilityDenied
                    }
                    if (failures.isNotEmpty()) {
                        Log.w(TAG, "scheduleApply($pkg): profile apply — " +
                            "${failures.size}/${results.size} writes failed: " +
                            failures.joinToString { r -> "${r.id.target}: ${r::class.simpleName}" })
                    }
                }
            }

            // ── 2. AutoTDP goal ────────────────────────────────────────────────────
            bundle.autoTdpGoal?.let { goal ->
                // WAVE 4a: start the daemon on the NATIVE goal path. The goal (incl.
                // AUTO and the full 5-mode band spec) survives end-to-end to the
                // engine's goalOverride — no lossy goal→legacy-3-profile map. AUTO now
                // engages the REAL context classifier instead of collapsing to BALANCED.
                Log.i(TAG, "scheduleApply($pkg): starting AutoTDP goal=$goal (native)")
                autoTdpController.start(goal)
            }

            // ── 3. Refresh rate ────────────────────────────────────────────────────
            bundle.refreshRateHz?.let { hz ->
                Log.i(TAG, "scheduleApply($pkg): setting preferred refreshRate=$hz Hz")
                refreshRateController.setPreferredHz(hz)
            }

            // ── 4. Fan mode ────────────────────────────────────────────────────────
            bundle.fanMode?.let { mode ->
                val key = resolveFanModeKey(report)
                if (key != null) {
                    // Snapshot current value before writing so we can revert on exit.
                    val before = readFanMode(key)
                    fanModeBeforeBundle = before
                    activeFanModeKey = key
                    Log.i(TAG, "scheduleApply($pkg): setting fan_mode=$mode (was=$before)")
                    writeFanMode(key, mode.toString(), report, reason = "per-app bundle: $pkg")
                } else {
                    Log.d(TAG, "scheduleApply($pkg): fanMode requested but device has no SETTINGS_KEY fan adapter")
                }
            }

            // ── 5. Game Boost ──────────────────────────────────────────────────────
            if (bundle.gameBoostOnLaunch) {
                Log.i(TAG, "scheduleApply($pkg): calling GameBoostLauncher.startBoost")
                gameBoostLauncher.startBoost(pkg)
            }

            // ── 6. Background-app reaper ───────────────────────────────────────────
            appReaper.reapForGame(pkg)

            // Record the active bundle so revertBundle knows what to undo on exit.
            activeBundlePackage = pkg
            activeBundle = bundle
        }
    }

    /**
     * Revert dynamic effects of a bundle when the mapped game loses foreground.
     *
     * WHAT IS REVERTED:
     *   - AutoTDP (if we started it): stop() cleans up via its own revert journal.
     *   - GameBoost (if we started it): stopBoost() → GameBoostService.stop().
     *   - Refresh-rate: set to null (system default).
     *   - Fan mode: restore the snapshot value captured at apply time.
     *
     * WHAT IS NOT REVERTED:
     *   - Profile / preset writes: these are persistent user-intent tuning choices
     *     (same as the old single-profile behavior).
     *   - Reaped apps: Android relaunches them on next user interaction. This is the
     *     correct and expected behavior — reaped apps are *stopped*, not deleted.
     */
    private suspend fun revertBundle(pkg: String, bundle: PerAppBundle) {
        Log.i(TAG, "revertBundle($pkg): reverting bundle effects")

        if (bundle.autoTdpGoal != null) {
            Log.i(TAG, "revertBundle($pkg): stopping AutoTDP")
            autoTdpController.stop()
        }

        if (bundle.gameBoostOnLaunch) {
            Log.i(TAG, "revertBundle($pkg): stopping GameBoost")
            gameBoostLauncher.stopBoost(pkg)
        }

        if (bundle.refreshRateHz != null) {
            Log.i(TAG, "revertBundle($pkg): clearing refresh-rate preference")
            refreshRateController.setPreferredHz(null)
        }

        if (bundle.fanMode != null) {
            val key = activeFanModeKey
            val before = fanModeBeforeBundle
            if (key != null && before != null) {
                Log.i(TAG, "revertBundle($pkg): reverting fan_mode to '$before'")
                val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
                writeFanMode(key, before, report, reason = "per-app bundle revert: $pkg")
            }
            fanModeBeforeBundle = null
            activeFanModeKey = null
        }
    }

    // ── Fan-mode helpers ──────────────────────────────────────────────────────

    /**
     * Resolve the AYN/Retroid fan_mode Settings.System key from the DeviceAdapter
     * (same logic as AutoTdpService and GameBoostService). Returns null on devices
     * without a controllable fan key.
     */
    private fun resolveFanModeKey(
        report: io.github.mayusi.calibratesoc.data.capability.CapabilityReport,
    ): String? = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        ?.fanAdapter
        ?.takeIf { it.kind == FanAdapterKind.SETTINGS_KEY }
        ?.target

    /**
     * Read the current fan_mode Settings.System value via ContentResolver.
     * Returns null on any error.
     */
    private fun readFanMode(key: String): String? = runCatching {
        android.provider.Settings.System.getString(contentResolver, key)
    }.getOrNull()

    /**
     * Write a fan_mode value through [TunableWriter]. SETTINGS_SYSTEM kind routes
     * to PServerWriter which uses the PServer root shell — the same path the rest
     * of the app uses for vendor Settings.System keys.
     */
    private suspend fun writeFanMode(
        key: String,
        value: String,
        report: io.github.mayusi.calibratesoc.data.capability.CapabilityReport,
        reason: String,
    ) {
        val id = TunableId(kind = TunableKind.SETTINGS_SYSTEM, target = key)
        val result = tunableWriter.write(id = id, value = value, report = report, reason = reason)
        if (result !is WriteResult.Success) {
            Log.w(TAG, "writeFanMode($key=$value): ${result::class.simpleName}")
        }
    }

    override fun onInterrupt() {
        // Required override; nothing to interrupt — our work is fire-and-forget coroutines.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
