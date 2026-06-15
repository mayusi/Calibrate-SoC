package io.github.mayusi.calibratesoc.data.profiles

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
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
 * per-app override map and auto-apply the mapped profile.
 *
 * This is the langerhans OdinTools pattern — Accessibility is the
 * only public path to "tell me when the foreground app changes" on
 * Android 10+. The user grants this once in Settings > Accessibility.
 *
 * Performance: the actual switch work is offloaded to a coroutine on
 * Dispatchers.IO. Inside onAccessibilityEvent we do nothing but a
 * package-name string compare + a flag flip — must stay cheap because
 * Android invokes us on every focus change.
 *
 * De-duplication: we record the last-applied profile id per package
 * and skip re-application when the foreground app hasn't actually
 * changed (some apps emit WINDOW_STATE_CHANGED on every dialog).
 */
@AndroidEntryPoint
class ForegroundAppWatcher : AccessibilityService() {

    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var profileApplier: ProfileApplier
    @Inject lateinit var capabilityProbe: CapabilityProbe

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastForegroundPackage: String? = null
    private var inFlight: Job? = null
    // True once the in-flight job has entered the write phase (after snapshot,
    // before write completes). Cancelling mid-write would leave an orphaned
    // snapshot journal entry with no matching write, corrupting boot-revert.
    private val inFlightWriting = AtomicBoolean(false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastForegroundPackage) return
        // Filter out our own UI + the system UI — auto-switching when
        // the user opens Calibrate SoC itself would be infinite loop.
        if (pkg == applicationContext.packageName || pkg.startsWith("com.android.systemui")) return
        lastForegroundPackage = pkg
        scheduleApply(pkg)
    }

    private fun scheduleApply(pkg: String) {
        // BUG FIX (BUG 5): only cancel a pending job if it has NOT yet entered
        // the write phase. Cancelling mid-write (between snapshot and write in
        // TunableWriter) orphans a snapshot journal entry — the snapshot is
        // recorded but the write is never attempted, so boot-revert will try
        // to revert a value we never actually changed. Let a writing job finish.
        if (inFlightWriting.get()) {
            // The previous job is actively writing; let it complete and drop
            // the new apply request. The app will reapply on the next genuine
            // foreground switch.
            Log.d(TAG, "scheduleApply($pkg): previous job is writing — skipping cancel")
            return
        }
        inFlight?.cancel()
        inFlightWriting.set(false)
        inFlight = scope.launch {
            val store = profileRepository.snapshot()
            val profileId = store.perAppOverrides[pkg] ?: return@launch
            val profile = store.profiles.firstOrNull { it.id == profileId } ?: return@launch
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            // Mark write phase BEFORE calling apply so any concurrent
            // foreground switch sees the flag and doesn't cancel us.
            inFlightWriting.set(true)
            val results = try {
                profileApplier.apply(profile.toPreset(), report, reason = "per-app: $pkg")
            } finally {
                inFlightWriting.set(false)
            }
            // BUG FIX (BUG 5): log/surface total write failures rather than
            // silently swallowing them.
            val failures = results.filterNot { it is WriteResult.Success || it is WriteResult.CapabilityDenied }
            if (failures.isNotEmpty()) {
                Log.w(TAG, "scheduleApply($pkg): ${failures.size}/${results.size} writes failed — " +
                    failures.joinToString { r -> "${r.id.target}: ${r::class.simpleName}" })
            }
        }
    }

    override fun onInterrupt() {
        // Required override; nothing to interrupt — our work is fire-and-forget.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
