package io.github.mayusi.calibratesoc.data.tunables.writer

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuNodeCache
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a tunable to the right writer for the current privilege tier.
 *
 * Routing rules:
 *   * SETTINGS_SYSTEM on AYN devices where PServer is bindable →
 *     PServerWriter (langerhans-style root proxy). AYN's vendor
 *     daemon ignores plain Settings.System writes from third-party
 *     apps — only the PServer-proxied path actually flips the kernel.
 *   * SETTINGS_SYSTEM elsewhere → SettingsKeyWriter (works on devices
 *     where the vendor service subscribes to ContentObserver events
 *     normally, OR where the user just wants the value persisted).
 *   * VENDOR_INTENT → SettingsKeyWriter (legacy path, never observed
 *     to be exercised in v1 but kept for AYANEO AYASpace integration).
 *   * SYSFS on Root tier → RootWriter (libsu).
 *   * SYSFS on Shizuku tier AND node passed the per-node write probe →
 *     ShizukuWriter (shell-UID binder write).
 *   * SYSFS on Shizuku tier AND node NOT probe-confirmed →
 *     NoopWriter with CapabilityDenied (honest: shell can't write this node).
 *   * SYSFS on VENDOR_SETTINGS / NONE when PServer is TRANSACTABLE (whitelist
 *     step has been run) → PServerWriter. PServer runs as root and can write
 *     any sysfs node without per-boot chmod. This is the "PServer-LIVE" tier
 *     for AutoTDP on AYN Odin 2/3/Thor.
 *     HONESTY: [PServerWriter.transactableNow] is only true when the probe
 *     confirmed PServer ran our command (whitelist add succeeded). If the step
 *     hasn't been done, it returns false and we fall through honestly.
 *   * SYSFS with unlock-script chmod 666 → UnlockedFileWriter.
 *   * SYSFS else → NoopWriter (the Generate script path covers SYSFS
 *     without a live SysfsWriter).
 *
 * HONESTY INVARIANT: A node is only routed to ShizukuWriter when
 * [ShizukuNodeCache.isCachedWritable] returns true — meaning the no-op
 * probe proved shell can write this exact path on this device. Nodes that
 * failed the probe (vendor SELinux denial) stay at NoopWriter regardless of
 * the privilege tier, so the UI never offers a live control that cannot write.
 *
 * Returning a writer that will always deny is intentional — every
 * caller can rely on a non-null writer and the deny surfaces as a
 * WriteResult so the UI explains why.
 */
@Singleton
class WriterRegistry @Inject constructor(
    private val root: RootWriter,
    private val shizuku: ShizukuWriter,
    private val settings: SettingsKeyWriter,
    private val pserver: PServerWriter,
    private val noop: NoopWriter,
    private val unlockedFile: UnlockedFileWriter,
    private val nodeCache: ShizukuNodeCache,
) {
    fun writerFor(id: TunableId, report: CapabilityReport): SysfsWriter =
        when (id.kind) {
            TunableKind.SETTINGS_SYSTEM -> {
                // Pick PServer when this is a vendor handheld (AYN/Odin,
                // AYANEO, Retroid) AND the service is actually published
                // on the binder. The binder() call is cheap (one
                // reflection invocation). On devices without a vendor
                // perf app, SettingsKeyWriter is the right choice — it
                // works on stock kernels that wire perf controls through
                // public Settings keys.
                if (report.vendorApps.anyVendorPerfApp && pserver.binder() != null) pserver else settings
            }
            TunableKind.VENDOR_INTENT -> settings
            TunableKind.SYSFS -> when (report.privilege) {
                PrivilegeTier.ROOT -> root
                PrivilegeTier.SHIZUKU -> {
                    // Only route to ShizukuWriter when the per-node probe confirmed
                    // shell can actually write this path on this device. If the node
                    // hasn't passed the probe (vendor SELinux denial or not yet probed),
                    // fall through to NoopWriter so the UI honestly reports denial.
                    if (nodeCache.isCachedWritable(id.target)) shizuku else noop
                }
                PrivilegeTier.VENDOR_SETTINGS,
                PrivilegeTier.NONE -> {
                    when {
                        // PServer-LIVE tier: AYN devices where the one-time whitelist
                        // step has been run. PServer executes our shell commands as root,
                        // so no per-boot chmod is needed. This is the best live path for
                        // AutoTDP on Odin 2/3/Thor (faster + no chmod-resets-on-boot).
                        // HONESTY: report.pserverSysfsLive is only true when CapabilityProbe
                        // confirmed a real transact round-trip during refresh(). If it's false
                        // (whitelist step not done, or non-AYN device), we fall through to
                        // UnlockedFileWriter or NoopWriter — never pretend PServer works.
                        report.pserverSysfsLive -> pserver

                        // Unlock-script tier: the chmod 666 the script applied made
                        // certain nodes app-UID-writable without root. Route those to
                        // UnlockedFileWriter; everything else stays at NoopWriter
                        // (will produce a CapabilityDenied that the UI shows as
                        // "script-only" rather than a live write attempt).
                        report.sysfsDirectlyWritable && Tunables.isUnlockCoveredNode(id.target) ->
                            unlockedFile

                        else -> noop
                    }
                }
            }
        }

    /**
     * Returns true when [id] would be routed to a live-write-capable writer
     * (RootWriter, ShizukuWriter for probed nodes, or UnlockedFileWriter).
     *
     * Used by [AutoTdpService.liveUnavailableReason] and the UI to decide
     * whether to show LIVE controls vs SCRIPT/ADVISORY mode. This is the
     * single source of truth — callers do NOT need to replicate tier logic.
     */
    fun isLiveWritable(id: TunableId, report: CapabilityReport): Boolean =
        writerFor(id, report) !is NoopWriter
}
