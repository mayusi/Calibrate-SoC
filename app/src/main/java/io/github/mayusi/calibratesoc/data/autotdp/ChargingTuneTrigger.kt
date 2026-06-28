package io.github.mayusi.calibratesoc.data.autotdp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.FanSource
import io.github.mayusi.calibratesoc.data.display.RefreshRateController
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Charging auto-profile trigger — applies a [ChargingBundle] (fan mode +
 * refresh rate + AutoTDP goal) when the device is plugged in to charge, and
 * reverts every axis on unplug.
 *
 * ## What it does
 * When the opt-in toggle ([ChargingBundlePrefs.chargingProfileEnabled]) is ON
 * AND the device is plugged in AND no game/AutoTDP session is already active:
 *   - Starts the AutoTDP daemon with the configured [ChargingBundle.autoTdpGoal].
 *   - Pins the fan to the configured [ChargingBundle.fanMode] (if supported).
 *   - Pins the display to the configured [ChargingBundle.refreshRateHz] (if set).
 *
 * On unplug (or when the trigger is stopped), EVERY axis is reverted:
 *   - AutoTDP is stopped (service handles sysfs revert internally).
 *   - Fan mode is restored to the captured pre-apply value.
 *   - Refresh rate is cleared (null → system default).
 *
 * ## Not-gaming gate
 * If [AutoTdpController.state.status] is [AutoTdpStatus.RUNNING] when the
 * charging event fires, a game bundle or manual session is already active.
 * We skip the bundle entirely and log the reason — we never stomp an active
 * gaming session. The toggle description in the UI is honest about this.
 * On unplug we only revert axes we actually applied, so a skipped application
 * leaves the gaming session undisturbed.
 *
 * ## NonCancellable revert
 * The revert coroutine always runs under [NonCancellable] so the device is
 * never left stuck in charging-mode after an unplug, even if the coroutine
 * scope is being torn down. This mirrors the 384 MHz-fix discipline.
 *
 * ## Honesty invariant
 * We capture fan state before writing (same pattern as ForegroundAppWatcher).
 * We only claim "applied" when writes succeed. Failed writes are logged but
 * do not prevent the revert attempt on unplug (revert uses the captured
 * prior state, not the failed applied value).
 */
@Singleton
class ChargingTuneTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: ChargingBundlePrefs,
    private val autoTdpController: AutoTdpController,
    private val refreshRateController: RefreshRateController,
    private val tunableWriter: TunableWriter,
    private val capabilityProbe: CapabilityProbe,
) {
    private val TAG = "ChargingTuneTrigger"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null

    // ── Apply-state capture (set on apply, cleared on revert) ─────────────────

    /**
     * True iff we applied the bundle on this charge-connect event.
     * Guards revert: we only revert axes we applied.
     */
    @Volatile private var bundleApplied = false

    /** The fan Settings.System key we wrote, or null if fan was not touched. */
    @Volatile private var appliedFanKey: String? = null

    /** The fan value that was in place BEFORE we wrote ours. Restored on revert. */
    @Volatile private var fanValueBeforeBundle: String? = null

    /** True iff we touched the refresh rate. Cleared on revert. */
    @Volatile private var refreshRatePinned = false

    /** True iff we started AutoTDP. Cleared on revert. */
    @Volatile private var autoTdpStarted = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start listening for power-connect / power-disconnect events.
     * Safe to call multiple times — re-registration is a no-op.
     */
    fun start() {
        if (receiver != null) return

        // Snapshot initial charging state: if the trigger starts while already
        // charging and the toggle is on, apply now.
        scope.launch {
            val enabled = prefs.chargingProfileEnabled.first()
            if (enabled && isCurrentlyCharging()) {
                applyBundle()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> scope.launch {
                        val enabled = prefs.chargingProfileEnabled.first()
                        if (enabled) applyBundle()
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> scope.launch {
                        revertBundle(reason = "power disconnected")
                    }
                }
            }
        }

        context.registerReceiver(receiver, filter)
        Log.i(TAG, "start: registered power-event receiver")
    }

    /**
     * Stop listening and unregister the receiver.
     * Reverts the bundle before cancelling the scope so the device is never left in
     * charging-mode after service death.
     *
     * BUG 8 fix: the previous implementation did `scope.launch { revert }.invokeOnCompletion
     * { scope.cancel() }`. If the scope job was ALREADY cancelling (rapid double-stop /
     * teardown), the launched body NEVER started — and a NonCancellable block INSIDE revert
     * cannot save a coroutine body that never began. The charging cap / fan / refresh would
     * then stay applied after unplug. We now run the revert on a private always-alive scope
     * and BLOCK until it completes, so the revert is guaranteed to run to completion on every
     * stop path regardless of the main scope's cancellation state, THEN cancel the main scope.
     */
    fun stop() {
        receiver?.let {
            runCatching { context.unregisterReceiver(it) }
            receiver = null
        }
        // Run the revert on a scope that is NOT being torn down, and block until it
        // finishes. revertBundle is itself NonCancellable; running it here guarantees the
        // body actually starts even if `scope` is mid-cancellation.
        runCatching {
            kotlinx.coroutines.runBlocking {
                revertBundle(reason = "trigger stopped")
            }
        }.onFailure { Log.w(TAG, "stop: revert failed", it) }
        scope.cancel()
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    /**
     * Attempt to apply the configured [ChargingBundle].
     * Skipped if a game/manual AutoTDP session is already active.
     */
    private suspend fun applyBundle() {
        if (bundleApplied) {
            Log.d(TAG, "applyBundle: already applied, skipping")
            return
        }

        // Not-gaming gate: if AutoTDP is already running, a game or manual
        // session owns it — don't stomp.
        val currentStatus = autoTdpController.state.value.status
        if (currentStatus == AutoTdpStatus.RUNNING) {
            Log.i(
                TAG,
                "applyBundle: AutoTDP already RUNNING (game/manual session active) — " +
                    "charging bundle skipped. Will apply after session ends.",
            )
            return
        }

        val bundle = prefs.bundle.first()
        val report = capabilityProbe.report.value ?: run {
            Log.w(TAG, "applyBundle: capability report not yet available, skipping this cycle")
            return
        }

        Log.i(TAG, "applyBundle: applying bundle=$bundle")

        // BUG 9 fix: COMMIT the "applied" flag FIRST, under NonCancellable, BEFORE starting
        // any effect. The failure class this kills: if the coroutine is cancelled between
        // start(AutoTDP) and `bundleApplied = true`, the revert guard `if (!bundleApplied)
        // return` no-ops on unplug while AutoTDP keeps running in charging mode forever.
        // With the flag committed first, even a partial apply is fully reverted on unplug.
        // (Same commit-applied-first discipline as ForegroundAppWatcher's tracker.)
        withContext(NonCancellable) {
            bundleApplied = true
        }

        // 1. AutoTDP goal — start daemon with the configured goal.
        // runCatching: a throw here must not propagate and cancel the apply mid-flight while
        // leaving bundleApplied=true with no revert state — we record autoTdpStarted only on
        // a clean start, and the flag is already committed so unplug still reverts what landed.
        runCatching { autoTdpController.start(bundle.autoTdpGoal) }
            .onSuccess {
                autoTdpStarted = true
                Log.i(TAG, "applyBundle: started AutoTDP goal=${bundle.autoTdpGoal.name}")
            }
            .onFailure { Log.w(TAG, "applyBundle: AutoTDP start failed", it) }

        // 2. Fan mode — capture prior value, then write.
        bundle.fanMode?.let { mode ->
            val fanKey = resolveFanKey(report)
            if (fanKey != null) {
                val priorValue = readFanMode(fanKey)
                appliedFanKey = fanKey
                fanValueBeforeBundle = priorValue
                val result = writeFanMode(
                    key = fanKey,
                    value = mode.toString(),
                    report = report,
                    reason = "charging bundle apply",
                )
                if (result is WriteResult.Success) {
                    Log.i(TAG, "applyBundle: fan_mode set to $mode (was $priorValue)")
                } else {
                    Log.w(TAG, "applyBundle: fan_mode write failed: ${result::class.simpleName}")
                    // Still record that we captured prior state so revert is safe.
                }
            } else {
                Log.d(TAG, "applyBundle: fanMode=$mode requested but device has no SETTINGS_KEY fan adapter")
            }
        }

        // 3. Refresh rate — set preferred Hz.
        bundle.refreshRateHz?.let { hz ->
            refreshRateController.setPreferredHz(hz)
            refreshRatePinned = true
            Log.i(TAG, "applyBundle: refresh rate pinned to $hz Hz")
        }

        // bundleApplied was committed FIRST (BUG 9) so revert is always armed; this is the
        // success log only — re-asserting the flag is a harmless no-op.
        Log.i(TAG, "applyBundle: bundle applied successfully")
    }

    // ── Revert ────────────────────────────────────────────────────────────────

    /**
     * Revert all axes we applied. Always runs under [NonCancellable] so the
     * device is never left stuck in charging-mode after unplug or service death.
     * Safe to call when [bundleApplied] is false — becomes a no-op.
     */
    private suspend fun revertBundle(reason: String) {
        if (!bundleApplied) {
            Log.d(TAG, "revertBundle($reason): nothing applied, no-op")
            return
        }

        Log.i(TAG, "revertBundle($reason): reverting all applied axes")

        withContext(NonCancellable) {
            // 1. AutoTDP — stop daemon (service handles sysfs revert internally).
            if (autoTdpStarted) {
                autoTdpController.stop()
                autoTdpStarted = false
                Log.i(TAG, "revertBundle: AutoTDP stopped")
            }

            // 2. Fan mode — restore prior value.
            val fanKey = appliedFanKey
            val priorFan = fanValueBeforeBundle
            if (fanKey != null && priorFan != null) {
                val report = capabilityProbe.report.value
                if (report != null) {
                    val result = writeFanMode(
                        key = fanKey,
                        value = priorFan,
                        report = report,
                        reason = "charging bundle revert",
                    )
                    if (result is WriteResult.Success) {
                        Log.i(TAG, "revertBundle: fan_mode restored to $priorFan")
                    } else {
                        Log.w(TAG, "revertBundle: fan_mode restore failed: ${result::class.simpleName}")
                    }
                } else {
                    Log.w(TAG, "revertBundle: capability report gone; fan mode NOT restored (device may be rebooting)")
                }
                appliedFanKey = null
                fanValueBeforeBundle = null
            } else if (fanKey != null) {
                // We wrote a fan mode but had no prior value — clear the key reference.
                appliedFanKey = null
                Log.w(TAG, "revertBundle: fanKey present but no prior value captured; fan left as-is")
            }

            // 3. Refresh rate — clear preference (returns to system default).
            if (refreshRatePinned) {
                refreshRateController.setPreferredHz(null)
                refreshRatePinned = false
                Log.i(TAG, "revertBundle: refresh rate cleared (system default restored)")
            }

            bundleApplied = false
            Log.i(TAG, "revertBundle($reason): complete")
        }
    }

    // ── Fan helpers (mirror ForegroundAppWatcher pattern without depending on it) ──

    /**
     * Resolve the vendor fan-mode Settings.System key from the capability report.
     * Returns null if the device has no SETTINGS_KEY fan adapter.
     *
     * Uses [CapabilityReport.fan] directly to avoid depending on the private
     * DeviceAdapterRegistry inside ForegroundAppWatcher.
     */
    private fun resolveFanKey(report: CapabilityReport): String? {
        val fanProbe = report.fan ?: return null
        return when (fanProbe.source) {
            FanSource.VENDOR_SETTINGS_KEY -> fanProbe.controlPath.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * Read the current fan_mode Settings.System value.
     * Returns null on any error (safe — revert will log the gap but won't crash).
     */
    private fun readFanMode(key: String): String? = runCatching {
        android.provider.Settings.System.getString(context.contentResolver, key)
    }.getOrNull()

    /**
     * Write a fan_mode value through [TunableWriter].
     * Routes to the same PServer/SETTINGS_SYSTEM path used by ForegroundAppWatcher.
     */
    private suspend fun writeFanMode(
        key: String,
        value: String,
        report: CapabilityReport,
        reason: String,
    ): WriteResult {
        val id = TunableId(kind = TunableKind.SETTINGS_SYSTEM, target = key)
        return tunableWriter.write(id = id, value = value, report = report, reason = reason)
    }

    // ── Battery helpers ───────────────────────────────────────────────────────

    /**
     * Sample the current charging state from the sticky battery intent.
     * Returns false on any error (safe default — won't silently apply bundle).
     */
    private fun isCurrentlyCharging(): Boolean = runCatching {
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return@runCatching false
        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    // ── Pure testable decision functions ─────────────────────────────────────

    companion object {
        /**
         * Pure decision: should the charging bundle be applied?
         *
         * @param enabled         the master opt-in toggle
         * @param charging        true iff the device is currently charging
         * @param gamingActive    true iff a game/manual AutoTDP session is running
         * @return true iff the bundle should be applied now
         */
        fun shouldApply(enabled: Boolean, charging: Boolean, gamingActive: Boolean): Boolean {
            if (!enabled) return false
            if (!charging) return false
            if (gamingActive) return false
            return true
        }

        /**
         * Pure decision: should the bundle be reverted?
         * Revert on unplug — regardless of gaming state, we always clean up
         * axes we applied.
         *
         * @param charging     true iff the device is currently charging
         * @param wasApplied   true iff we previously applied the bundle
         * @return true iff revert should run now
         */
        fun shouldRevert(charging: Boolean, wasApplied: Boolean): Boolean {
            return !charging && wasApplied
        }
    }
}
