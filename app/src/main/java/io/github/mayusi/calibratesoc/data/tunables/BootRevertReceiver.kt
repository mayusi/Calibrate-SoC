package io.github.mayusi.calibratesoc.data.tunables

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "BootRevertReceiver"

/**
 * On BOOT_COMPLETED we replay the snapshot journal in reverse to undo
 * everything the app wrote during the last session. This is the safety
 * default: nothing we changed persists across reboot unless the user
 * explicitly opts into "apply at boot" per-profile (Phase 4).
 *
 * The receiver is registered in AndroidManifest.xml with
 * RECEIVE_BOOT_COMPLETED. It uses goAsync() because the revert can take
 * several seconds — long enough to exceed the synchronous broadcast
 * budget.
 *
 * Hilt-injected via @AndroidEntryPoint, but the heavy lifting runs on a
 * detached scope because the BroadcastReceiver lifecycle ends as soon
 * as we return from finish().
 */
@AndroidEntryPoint
class BootRevertReceiver : BroadcastReceiver() {

    @Inject lateinit var capabilityProbe: CapabilityProbe
    @Inject lateinit var tunableWriter: TunableWriter
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var profileApplier: ProfileApplier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val report = capabilityProbe.refresh()
                // Revert first. Even when a profile is marked
                // applyOnBoot, the revert step is harmless — most
                // sysfs values don't survive reboot, so the journal
                // either matches reality (we wrote them, they're
                // still in place) or the revert no-ops.
                val revertSummary = tunableWriter.revertAll(report)
                if (revertSummary.failed == 0) {
                    Log.i(TAG, "Boot revert complete: ${revertSummary.ok}/${revertSummary.totalEntries} entries reverted successfully.")
                } else {
                    Log.w(
                        TAG,
                        "Boot revert partial: ${revertSummary.ok} succeeded, " +
                            "${revertSummary.failed} FAILED of ${revertSummary.totalEntries} total entries. " +
                            "Journal retained for next boot retry.",
                    )
                }
                // Then re-apply any profile the user asked to persist.
                // Last write wins on conflicting policies.
                val applyAtBoot = profileRepository.snapshot().profiles
                    .filter { it.applyOnBoot }
                for (profile in applyAtBoot) {
                    profileApplier.apply(profile.toPreset(), report, reason = "boot re-apply: ${profile.name}")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
