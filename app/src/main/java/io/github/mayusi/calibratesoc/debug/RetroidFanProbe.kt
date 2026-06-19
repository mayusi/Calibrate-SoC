package io.github.mayusi.calibratesoc.debug

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.io.File

/**
 * DE-RISK PROBE — debug-only, isolated, removable.
 *
 * Single question this answers: can a NORMAL (non-system, non-root) app on the
 * Retroid Pocket 6 bind `SettingsController` → `FanProvider` and set a custom
 * fan speed through the vendor binder chain?
 *
 * ## Wire protocol — decompiled from `com.rp.gameassistant` (uid=system)
 *
 * ### Step 1 — acquire SettingsController
 *   ServiceManager.getService("SettingsController")
 *   (reached via reflection: ServiceManager is @hide; same technique used in
 *   AyaneoBinderClient and PServerWriter for hidden-API access.)
 *
 * ### Step 2 — acquire FanProvider IBinder from SettingsController (txn 1)
 *   data.writeInterfaceToken("com.ro.settings.IExternalControlManager")
 *   data.writeString("FanProvider")
 *   settingsController.transact(1, data, reply, 0)
 *   reply.readException()
 *   IBinder fanProvider = reply.readStrongBinder()
 *
 * ### Step 3 — set custom fan speed (txn 7 = r(int) in decompiled class)
 *   data.writeInterfaceToken("com.ro.settings.IFanControlProvider")
 *   data.writeInt(speed)
 *   fanProvider.transact(7, data, reply, 0)
 *   reply.readException()
 *
 * ### Step 4 — read back current fan value (txn 2 = b() in decompiled class)
 *   data.writeInterfaceToken("com.ro.settings.IFanControlProvider")
 *   fanProvider.transact(2, data, reply, 0)
 *   reply.readException()
 *   int current = reply.readInt()
 *
 * ### Step 5 — restore auto mode (txn 5 = c(int) in decompiled class)
 *   data.writeInterfaceToken("com.ro.settings.IFanControlProvider")
 *   data.writeInt(mode)          // 2 = Smart / auto per gameassistant prefs
 *   fanProvider.transact(5, data, reply, 0)
 *   reply.readException()
 *
 * ## Speed unit uncertainty
 * The RP6 stores the fan preference "fan_speed" = 25000 and "display_pwm_value"
 * exists as a separate key. The txn-7 unit is unknown — it may be a raw 0-255
 * PWM or a scaled value (~0-25500 or similar). The probe deliberately accepts
 * the speed value as an intent extra so the caller can experiment with different
 * magnitudes without rebuilding. Start with small values (e.g. 50, 128, 200)
 * to stay safe; nothing permanent is written (a reboot or restore-mode call
 * returns control to the auto governor).
 *
 * ## Safety
 * - Fan is the safest test target: worst case the fan spins at a fixed speed
 *   temporarily. Restore by calling with `restore=true` (txn 5, mode=2) or
 *   by rebooting the device.
 * - NEVER crashes or ANRs: every failure path is caught and returns a
 *   [ProbeResult]. All binder work is called from Dispatchers.IO.
 *
 * ## Physical cross-check
 * `/sys/class/hwmon/hwmonN/pwm1` (0-255) is the raw fan PWM node, readable by
 * any app. Before/after comparison confirms whether the hardware actually moved.
 *
 * ## Physical node for cross-check:
 *   `adb shell "cat /sys/class/hwmon/hwmon[*]/pwm1"`
 */
object RetroidFanProbe {

    const val TAG = "RP_FAN_PROBE"

    // ── Interface descriptors ───────────────────────────────────────────────────

    /** Descriptor used on the SettingsController binder. */
    private const val EXTERNAL_CONTROL_DESCRIPTOR = "com.ro.settings.IExternalControlManager"

    /** Descriptor used on the FanProvider binder. */
    private const val FAN_PROVIDER_DESCRIPTOR = "com.ro.settings.IFanControlProvider"

    /** Name passed to SettingsController.transact(1) to acquire FanProvider. */
    private const val PROVIDER_NAME = "FanProvider"

    /** ServiceManager service name registered by the RP6 vendor daemon. */
    private const val SERVICE_NAME = "SettingsController"

    // ── Transaction codes ───────────────────────────────────────────────────────

    /** txn 1 on SettingsController: getProvider(String) -> IBinder. */
    private const val TXN_GET_PROVIDER = 1

    /** txn 2 on FanProvider: b() -> Int — read current fan value. */
    private const val TXN_READ_FAN = 2

    /** txn 5 on FanProvider: c(int mode) — set fan mode (2 = Smart/auto). */
    private const val TXN_SET_MODE = 5

    /** txn 6 on FanProvider: j(boolean) — ENABLE custom fan mode. The decompiled
     *  ScreenStateReceiver custom path is c(true)->e(speed), i.e. j(true) THEN r(speed). */
    private const val TXN_ENABLE_CUSTOM = 6

    /** txn 7 on FanProvider: r(int speed) — set custom fan speed. */
    private const val TXN_SET_SPEED = 7

    // ── Sealed result ───────────────────────────────────────────────────────────

    /**
     * Exhaustive result of one probe call. Every failure maps here — the probe
     * never throws.
     */
    sealed interface ProbeResult {

        /**
         * ServiceManager.getService("SettingsController") returned null.
         * The RP6 vendor daemon is absent or the app lacks access to the hidden
         * ServiceManager API entirely.
         */
        data object ServiceNull : ProbeResult

        /**
         * SettingsController binder was retrieved, but the FanProvider returned
         * by txn 1 was null. The SettingsController is not exposing a FanProvider
         * on this firmware version, or the interface token / txn code is wrong.
         */
        data object ProviderNull : ProbeResult

        /**
         * A [SecurityException] was thrown during a transact() call. The app is
         * blocked at the binder layer — the RP6 vendor restricts FanProvider
         * access to system-UID callers.
         */
        data class Denied(val reason: String) : ProbeResult

        /**
         * txn 7 (set speed) completed without a security exception.
         * [readBackValue] is the result of the immediate txn 2 (read-back) call —
         * null if that secondary read itself failed (the set is still considered
         * attempted; check `/sys/class/hwmon/hwmonN/pwm1` for physical confirmation).
         */
        data class SentOk(val readBackValue: Int?) : ProbeResult

        /**
         * A transact() call failed for a non-security reason (dead object,
         * remote exception, unexpected exception, bad reply). [reason] describes
         * the failure.
         */
        data class TransactFailed(val reason: String) : ProbeResult
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Attempt to set the RP6 fan speed to [speed] via the SettingsController
     * → FanProvider binder chain. Reads the current fan value back immediately
     * after setting.
     *
     * When [restore] is true, [speed] is ignored and txn 5 (c(int mode)) is
     * called instead with [restoreMode] (default 2 = Smart/auto) to hand fan
     * control back to the stock governor.
     *
     * Call only from a background thread / Dispatchers.IO.
     *
     * @param speed      Fan speed value to send via txn 7.  Unit is unknown —
     *                   start with small values (50, 128, 200) to experiment.
     * @param restore    When true: call txn 5 (set mode) instead of txn 7.
     * @param restoreMode Mode value for txn 5. 2 = Smart/auto per RP6 prefs;
     *                   the caller can override with 1 or 4 to try alternatives.
     * @return           A [ProbeResult] describing the outcome.  Never throws.
     */
    fun tryBind(speed: Int, restore: Boolean = false, restoreMode: Int = 2): ProbeResult {
        Log.i(TAG, "=== RP FAN PROBE START  speed=$speed  restore=$restore  restoreMode=$restoreMode ===")
        logPwm1("before")

        val settingsController = getSettingsController()
            ?: return ProbeResult.ServiceNull.also {
                Log.w(TAG, "RESULT=SERVICE_NULL — ServiceManager.getService(\"$SERVICE_NAME\") returned null")
                Log.w(TAG, "  Either the RP6 vendor daemon is not running or reflection was blocked.")
            }

        Log.i(TAG, "SettingsController binder acquired: $settingsController")

        val fanProvider = getFanProvider(settingsController)
            ?: return ProbeResult.ProviderNull.also {
                Log.w(TAG, "RESULT=PROVIDER_NULL — txn 1 on SettingsController returned null FanProvider")
                Log.w(TAG, "  Interface token or txn code may be wrong for this firmware build.")
            }

        Log.i(TAG, "FanProvider binder acquired: $fanProvider")

        return if (restore) {
            setFanMode(fanProvider, restoreMode)
        } else {
            setFanSpeed(fanProvider, speed)
        }
    }

    // ── Step 1: ServiceManager.getService() via reflection ─────────────────────

    /**
     * Calls the hidden [android.os.ServiceManager.getService] via reflection.
     * Returns null on any failure (class not found, method not found,
     * invocation error, result null).
     */
    private fun getSettingsController(): IBinder? = try {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val result = getService.invoke(null, SERVICE_NAME)
        if (result == null) {
            Log.w(TAG, "ServiceManager.getService(\"$SERVICE_NAME\") returned null")
        }
        result as? IBinder
    } catch (e: ClassNotFoundException) {
        Log.w(TAG, "getSettingsController: ServiceManager class not found: ${e.message}")
        null
    } catch (e: NoSuchMethodException) {
        Log.w(TAG, "getSettingsController: getService method not found: ${e.message}")
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "getSettingsController: reflection blocked by SecurityException: ${e.message}")
        null
    } catch (t: Throwable) {
        Log.w(TAG, "getSettingsController: unexpected ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    // ── Step 2: SettingsController.transact(1) → FanProvider ───────────────────

    /**
     * Calls txn 1 on [settingsController] with token [EXTERNAL_CONTROL_DESCRIPTOR]
     * and string payload [PROVIDER_NAME], then reads the reply's exception and
     * extracts the returned [IBinder] (FanProvider).
     *
     * Returns null if the transact fails or the reply binder is null.
     */
    private fun getFanProvider(settingsController: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(EXTERNAL_CONTROL_DESCRIPTOR)
            data.writeString(PROVIDER_NAME)
            settingsController.transact(TXN_GET_PROVIDER, data, reply, 0)
            reply.readException()
            val provider = reply.readStrongBinder()
            if (provider == null) {
                Log.w(TAG, "getFanProvider: reply.readStrongBinder() returned null")
            }
            provider
        } catch (e: SecurityException) {
            Log.w(TAG, "getFanProvider: SecurityException on txn $TXN_GET_PROVIDER: ${e.message}")
            null
        } catch (e: RemoteException) {
            Log.w(TAG, "getFanProvider: RemoteException on txn $TXN_GET_PROVIDER: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "getFanProvider: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ── Step 3: FanProvider.transact(7) — set custom speed ─────────────────────

    /**
     * Calls txn 7 (r(int)) on [fanProvider] to set a custom fan speed, then
     * immediately calls txn 2 (b()) to read back the current value.
     */
    private fun setFanSpeed(fanProvider: IBinder, speed: Int): ProbeResult {
        // The decompiled UI custom-fan path is j(true) THEN r(speed) — enable custom
        // mode first via txn 6, otherwise the auto governor ignores the manual speed.
        run {
            val en = Parcel.obtain()
            val enReply = Parcel.obtain()
            try {
                en.writeInterfaceToken(FAN_PROVIDER_DESCRIPTOR)
                en.writeInt(1) // j(true)
                fanProvider.transact(TXN_ENABLE_CUSTOM, en, enReply, 0)
                enReply.readException()
                Log.i(TAG, "txn $TXN_ENABLE_CUSTOM j(true) — custom mode enabled before setting speed")
            } catch (t: Throwable) {
                Log.w(TAG, "txn $TXN_ENABLE_CUSTOM j(true) failed (continuing): ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                enReply.recycle()
                en.recycle()
            }
        }

        Log.i(TAG, "setFanSpeed: calling txn $TXN_SET_SPEED with speed=$speed")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FAN_PROVIDER_DESCRIPTOR)
            data.writeInt(speed)
            fanProvider.transact(TXN_SET_SPEED, data, reply, 0)
            reply.readException()
        } catch (e: SecurityException) {
            return ProbeResult.Denied("txn $TXN_SET_SPEED: SecurityException: ${e.message}").also {
                Log.w(TAG, "RESULT=DENIED — ${e.message}")
                Log.w(TAG, "  RP6 fan control requires system UID. Root or SettingsController proxy needed.")
            }
        } catch (e: RemoteException) {
            return ProbeResult.TransactFailed("txn $TXN_SET_SPEED: ${e.javaClass.simpleName}: ${e.message}").also {
                Log.w(TAG, "RESULT=TRANSACT_FAILED — ${e.javaClass.simpleName}: ${e.message}")
            }
        } catch (t: Throwable) {
            return ProbeResult.TransactFailed("txn $TXN_SET_SPEED: ${t.javaClass.simpleName}: ${t.message}").also {
                Log.w(TAG, "RESULT=TRANSACT_FAILED — ${t.javaClass.simpleName}: ${t.message}")
            }
        } finally {
            reply.recycle()
            data.recycle()
        }

        Log.i(TAG, "txn $TXN_SET_SPEED completed without exception — fan speed command accepted at binder layer")

        // Read back immediately.
        val readBack = readFanValue(fanProvider)
        logPwm1("after")

        return ProbeResult.SentOk(readBack).also {
            Log.i(TAG, "RESULT=SENT_OK  speed_sent=$speed  read_back=$readBack")
            Log.i(TAG, "  Verify: adb shell \"cat /sys/class/hwmon/hwmon*/pwm1\"")
            Log.i(TAG, "  If pwm1 changed → the RP6 fan binder path is OPEN (no root needed).")
            Log.i(TAG, "  If pwm1 did NOT change → txn accepted but unit is wrong; try different speed magnitudes.")
            Log.i(TAG, "  Restore: fire the receiver with extras restore=true (or reboot).")
        }
    }

    // ── Step 5: FanProvider.transact(5) — restore auto mode ────────────────────

    /**
     * Calls txn 5 (c(int mode)) on [fanProvider] to return fan control to the
     * stock governor.
     */
    private fun setFanMode(fanProvider: IBinder, mode: Int): ProbeResult {
        Log.i(TAG, "setFanMode: calling txn $TXN_SET_MODE with mode=$mode (2=Smart/auto)")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FAN_PROVIDER_DESCRIPTOR)
            data.writeInt(mode)
            fanProvider.transact(TXN_SET_MODE, data, reply, 0)
            reply.readException()
        } catch (e: SecurityException) {
            return ProbeResult.Denied("txn $TXN_SET_MODE: SecurityException: ${e.message}").also {
                Log.w(TAG, "RESULT=DENIED on restore — ${e.message}")
            }
        } catch (e: RemoteException) {
            return ProbeResult.TransactFailed("txn $TXN_SET_MODE: ${e.javaClass.simpleName}: ${e.message}").also {
                Log.w(TAG, "RESULT=TRANSACT_FAILED on restore — ${e.javaClass.simpleName}: ${e.message}")
            }
        } catch (t: Throwable) {
            return ProbeResult.TransactFailed("txn $TXN_SET_MODE: ${t.javaClass.simpleName}: ${t.message}").also {
                Log.w(TAG, "RESULT=TRANSACT_FAILED on restore — ${t.javaClass.simpleName}: ${t.message}")
            }
        } finally {
            reply.recycle()
            data.recycle()
        }

        val readBack = readFanValue(fanProvider)
        logPwm1("after restore")

        return ProbeResult.SentOk(readBack).also {
            Log.i(TAG, "RESULT=SENT_OK (RESTORE)  mode=$mode  read_back=$readBack")
            Log.i(TAG, "  Fan control returned to auto governor (mode $mode).")
        }
    }

    // ── Step 4: FanProvider.transact(2) — read current value ───────────────────

    /**
     * Calls txn 2 (b()) on [fanProvider] to read the current fan value.
     * Returns null on any failure (secondary read — the set result is already
     * determined by this point).
     */
    private fun readFanValue(fanProvider: IBinder): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(FAN_PROVIDER_DESCRIPTOR)
            fanProvider.transact(TXN_READ_FAN, data, reply, 0)
            reply.readException()
            val value = reply.readInt()
            Log.i(TAG, "readFanValue: txn $TXN_READ_FAN returned $value")
            value
        } catch (e: SecurityException) {
            Log.w(TAG, "readFanValue: SecurityException: ${e.message}")
            null
        } catch (e: RemoteException) {
            Log.w(TAG, "readFanValue: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "readFanValue: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ── Physical node cross-check ───────────────────────────────────────────────

    /**
     * Read `/sys/class/hwmon/hwmonN/pwm1` and log the result. Not all firmwares
     * expose the glob path from the app process — log the raw file discovery
     * so we know which hwmon index the RP6 uses.
     */
    private fun logPwm1(label: String) {
        try {
            val hwmonRoot = File("/sys/class/hwmon")
            if (!hwmonRoot.exists()) {
                Log.i(TAG, "pwm1 [$label]: /sys/class/hwmon not accessible from app process")
                return
            }
            val dirs = hwmonRoot.listFiles() ?: emptyArray()
            if (dirs.isEmpty()) {
                Log.i(TAG, "pwm1 [$label]: /sys/class/hwmon is empty or unreadable")
                return
            }
            for (dir in dirs.sortedBy { it.name }) {
                val pwm1 = File(dir, "pwm1")
                if (pwm1.exists()) {
                    val raw = pwm1.readText().trim()
                    Log.i(TAG, "pwm1 [$label]: ${pwm1.absolutePath} = $raw")
                } else {
                    Log.i(TAG, "pwm1 [$label]: ${dir.absolutePath}/pwm1 does not exist")
                }
            }
        } catch (t: Throwable) {
            // App processes typically cannot read hwmon — that's fine; the adb
            // command below is the authoritative cross-check.
            Log.i(TAG, "pwm1 [$label]: not readable from app process (${t.javaClass.simpleName})")
            Log.i(TAG, "  Cross-check via: adb shell \"cat /sys/class/hwmon/hwmon*/pwm1\"")
        }
    }
}
