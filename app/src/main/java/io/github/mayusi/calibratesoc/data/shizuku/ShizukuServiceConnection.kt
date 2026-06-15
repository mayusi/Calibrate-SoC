package io.github.mayusi.calibratesoc.data.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of the [SysfsUserService] Shizuku UserService binding.
 *
 * Shizuku's UserService model (v13): Shizuku forks a process at shell UID,
 * loads our [SysfsUserService] class into it, and delivers the IBinder via a
 * standard [android.content.ServiceConnection]. We cast it to
 * [ISysfsUserService] via [ISysfsUserService.Stub.asInterface] and use it for
 * shell-UID sysfs writes.
 *
 * The connection is lazy — [ensureConnected] only binds when first needed.
 * [serviceFlow] exposes the live stub (null when unbound) so callers can
 * observe the connection state reactively.
 *
 * Thread safety: [ServiceConnection] callbacks arrive on the main thread;
 * [MutableStateFlow] is thread-safe. [ensureConnected] and [disconnect] are
 * safe to call from any thread.
 */
@Singleton
class ShizukuServiceConnection @Inject constructor() {

    private val _serviceFlow = MutableStateFlow<ISysfsUserService?>(null)

    /** Emits the live stub when the UserService is bound, null otherwise. */
    val serviceFlow: StateFlow<ISysfsUserService?> = _serviceFlow.asStateFlow()

    /** Synchronous accessor for the current stub — null if not yet bound. */
    val service: ISysfsUserService? get() = _serviceFlow.value

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "io.github.mayusi.calibratesoc",
            SysfsUserService::class.java.name,
        )
    )
        .daemon(false)
        .processNameSuffix("sysfs_shell")
        .debuggable(false)
        .version(SERVICE_VERSION)

    /** Standard Android ServiceConnection — Shizuku 13 uses this interface. */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.i(TAG, "SysfsUserService connected")
            _serviceFlow.value = ISysfsUserService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "SysfsUserService disconnected")
            _serviceFlow.value = null
        }
    }

    private var bound = false

    /**
     * Bind the UserService if not already bound. No-op when already connected.
     * Must only be called when Shizuku permission is granted.
     */
    fun ensureConnected() {
        if (bound) return
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
            bound = true
            Log.i(TAG, "bindUserService called")
        } catch (e: Throwable) {
            Log.e(TAG, "bindUserService failed: ${e.message}")
        }
    }

    /**
     * Unbind and release the UserService. The shell-UID process is reaped by
     * Shizuku once all clients have unbound.
     */
    fun disconnect() {
        if (!bound) return
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (e: Throwable) {
            Log.w(TAG, "unbindUserService error: ${e.message}")
        } finally {
            bound = false
            _serviceFlow.value = null
        }
    }

    private companion object {
        const val TAG = "ShizukuServiceConn"
        // Bump when the AIDL interface changes — Shizuku will restart the
        // service process to pick up the new class.
        const val SERVICE_VERSION = 1
    }
}
