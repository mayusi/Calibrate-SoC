package io.github.mayusi.calibratesoc.data.tunables.writer

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
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
 *   * SYSFS on Shizuku tier → ShizukuWriter (CapabilityDenied stub
 *     until UserService lands).
 *   * SYSFS on AYN_SETTINGS / NONE tier → NoopWriter (the Generate
 *     script path covers SYSFS without a SysfsWriter).
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
                PrivilegeTier.SHIZUKU -> shizuku
                PrivilegeTier.AYN_SETTINGS,
                PrivilegeTier.NONE -> noop
            }
        }
}
