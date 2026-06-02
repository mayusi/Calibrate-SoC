package io.github.mayusi.calibratesoc

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.mayusi.calibratesoc.data.baseline.FactoryBaselineRecorder
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App entry point. Hilt requires a custom [Application] subclass.
 *
 * Shizuku's `provider` library auto-discovers the binder via the
 * <provider> tag in AndroidManifest.xml — no init code needed here.
 *
 * Factory baseline is captured here (not in MainActivity) because we
 * want it to fire ONCE per install, before any UI interaction can
 * have changed device state. Hilt injects directly into Application
 * subclasses via field injection.
 */
@HiltAndroidApp
class CalibrateSocApplication : Application() {

    @Inject lateinit var capabilityProbe: CapabilityProbe
    @Inject lateinit var deviceAdapterRegistry: DeviceAdapterRegistry
    @Inject lateinit var factoryBaselineRecorder: FactoryBaselineRecorder

    /** Detached scope — capture runs in parallel with the rest of
     *  startup. Worst case the user opens Tune before capture
     *  finishes, in which case `ensureCaptured` is idempotent
     *  and the second caller gets the persisted result. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val report = capabilityProbe.refresh()
            val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
            factoryBaselineRecorder.ensureCaptured(report, adapter)
        }
    }
}
