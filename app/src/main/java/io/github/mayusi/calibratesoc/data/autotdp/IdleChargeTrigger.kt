package io.github.mayusi.calibratesoc.data.autotdp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Component 7: Idle / Charge auto-downclock trigger.
 *
 * When the user has opted in ([UserPrefs.idleChargeTriggerEnabled]) this
 * emits an [AutoTdpProfileConfig] (EFFICIENCY floor) whenever the device is
 * screen-off OR charging. On screen-on + unplugged it emits null ("restore
 * whatever was running before"). When the toggle is off it always emits null
 * so no silent downclock ever happens.
 *
 * The actual application of the profile is NOT done here. The output
 * [requestedProfile] Flow is consumed by the AutoTdpController integration
 * layer (built separately) which calls [AutoTdpTrigger.onProfileRequested].
 *
 * ## Screen-off detection
 * ACTION_SCREEN_OFF and ACTION_SCREEN_ON must be registered dynamically —
 * they are not delivered to manifest receivers on Android 8+. We register
 * and unregister the receiver in [start] / [stop], mirroring the pattern
 * used by MonitorService's battery sampler.
 *
 * ## Charging detection
 * We read [BatteryManager.EXTRA_STATUS] from the sticky ACTION_BATTERY_CHANGED
 * broadcast on every POWER_CONNECTED / POWER_DISCONNECTED event, which IS
 * deliverable to manifest receivers on modern Android. For robustness we also
 * read the initial charging state when [start] is called so the trigger fires
 * immediately if the device is already charging.
 *
 * ## Honesty invariant
 * This class never writes any kernel tunable. It only produces a signal.
 */
@Singleton
class IdleChargeTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPrefs,
) {
    // ── Internal state atoms ──────────────────────────────────────────────────

    private val _screenOff = MutableStateFlow(false)
    private val _charging = MutableStateFlow(false)

    // ── Receiver ──────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null

    /**
     * The deep-efficiency floor profile emitted on idle/charge.
     * EFFICIENCY with no targetMilliWatts — pure core-parking mode.
     */
    private val efficiencyFloor = AutoTdpProfileConfig(AutoTdpProfile.EFFICIENCY)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Emits the [AutoTdpProfileConfig] the trigger wants the daemon to apply,
     * or null when the trigger is inactive (toggle off, or screen-on + unplugged).
     *
     * The integration layer collects this flow and calls its controller
     * accordingly. The flow is [distinctUntilChanged] so the controller is
     * only invoked when the desired profile actually changes.
     */
    val requestedProfile: Flow<AutoTdpProfileConfig?> =
        combine(userPrefs.idleChargeTriggerEnabled, _screenOff, _charging) { enabled, off, charging ->
            decide(enabled = enabled, screenOff = off, charging = charging)
        }.distinctUntilChanged()

    /**
     * Start listening for screen and power events.
     *
     * Safe to call multiple times — re-registration is a no-op if already
     * registered. Call [stop] symmetrically when the hosting service is
     * destroyed.
     */
    fun start() {
        if (receiver != null) return

        // Snapshot initial charging state before the receiver fires.
        scope.launch {
            val initiallyCharging = isCurrentlyCharging()
            _charging.value = initiallyCharging
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> _screenOff.value = true
                    Intent.ACTION_SCREEN_ON -> _screenOff.value = false
                    Intent.ACTION_POWER_CONNECTED -> _charging.value = true
                    Intent.ACTION_POWER_DISCONNECTED -> _charging.value = false
                }
            }
        }

        context.registerReceiver(receiver, filter)
    }

    /**
     * Stop listening and unregister the receiver.
     *
     * Always call this when the hosting service is destroyed to avoid leaking
     * the system-registered BroadcastReceiver.
     */
    fun stop() {
        receiver?.let {
            runCatching { context.unregisterReceiver(it) }
            receiver = null
        }
        scope.cancel()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Sample the current charging state from the sticky battery intent.
     * Returns false on any error (safe default — won't silently downclock).
     */
    private fun isCurrentlyCharging(): Boolean = runCatching {
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return@runCatching false
        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    companion object {
        /**
         * Pure decision function — no Android, fully testable.
         *
         * Rule: emit an efficiency floor iff the toggle is on AND
         * (screen is off OR device is charging). Otherwise null (no change).
         */
        fun decide(enabled: Boolean, screenOff: Boolean, charging: Boolean): AutoTdpProfileConfig? {
            if (!enabled) return null
            return if (screenOff || charging) AutoTdpProfileConfig(AutoTdpProfile.EFFICIENCY) else null
        }
    }
}

/**
 * Minimal interface the integration layer uses to wire trigger output into
 * the AutoTdpController without a hard dependency on the in-flight service.
 *
 * Components 7 and 8 both produce [AutoTdpProfileConfig]? signals; the
 * integration layer picks the highest-priority non-null one and calls this.
 */
fun interface AutoTdpTrigger {
    fun onProfileRequested(profile: AutoTdpProfileConfig?)
}
