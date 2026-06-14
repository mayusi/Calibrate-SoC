package io.github.mayusi.calibratesoc

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.mayusi.calibratesoc.data.baseline.FactoryBaselineRecorder
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.remote.RemoteContentRepository
import io.github.mayusi.calibratesoc.data.update.AutoUpdateChecker
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
    @Inject lateinit var autoUpdateChecker: AutoUpdateChecker
    @Inject lateinit var remoteContentRepository: RemoteContentRepository

    /** Detached scope — capture runs in parallel with the rest of
     *  startup. Worst case the user opens Tune before capture
     *  finishes, in which case `ensureCaptured` is idempotent
     *  and the second caller gets the persisted result. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // OTA content refresh — best-effort, non-blocking, throttled to
        // once per 12 hours. Failure leaves the bundled set intact and
        // never crashes. Launched first so that if the fetch completes
        // quickly (e.g. disk-cache hit) the merged adapter + preset sets
        // are ready before the capability probe finishes.
        scope.launch { remoteContentRepository.refresh() }

        scope.launch {
            val report = capabilityProbe.refresh()
            val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
            factoryBaselineRecorder.ensureCaptured(report, adapter)
        }
        // Best-effort auto-update check — fire-and-forget, never blocks startup.
        // Runs on IO, swallows all errors, and only proceeds if the user opted in
        // and 24 hours have passed since the last check.
        scope.launch { autoUpdateChecker.runIfDue() }
    }
}
