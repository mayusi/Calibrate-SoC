package io.github.mayusi.calibratesoc.data.tunables.writer

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.shizuku.ShizukuNodeCache
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a tunable to the right writer, strongest tier first.
 *
 * TIER PRECEDENCE (per node-family, strongest first):
 *   1. PServer-LIVE (report.pserverSysfsLive) — full ROOT sysfs/settings/governor/
 *      cpufreq/GPU/DDR on ANY device that has it (cross-vendor: AYN Odin + Retroid
 *      Pocket 6 confirmed). STRONGEST. Gated by SELinux mode, not app_whiteList.
 *   2. Vendor fan binder (AYANEO fan / Retroid FanProvider) — FAN nodes ONLY.
 *   3. Vendor general binder (AYANEO AyaAidlService) — only when NOT pserverSysfsLive.
 *   4. ROOT (Magisk + opt-in) → RootWriter.
 *   5. SHIZUKU → ShizukuWriter (probe-confirmed nodes only).
 *   6. chmod-direct (sysfsDirectlyWritable) → UnlockedFileWriter.
 *   7. NONE → NoopWriter.
 *
 * Routing rules:
 *   * SETTINGS_SYSTEM when PServer is TRANSACTABLE (any device) → PServerWriter:
 *     a `settings put` run by PServer's root shell beats an app-UID putString.
 *     Cold-path fallback: a vendor handheld with the binder published also
 *     prefers PServer; everything else → SettingsKeyWriter.
 *   * VENDOR_INTENT → SettingsKeyWriter (legacy path, never observed
 *     to be exercised in v1 but kept for AYANEO AYASpace integration).
 *   * SYSFS when report.pserverSysfsLive AND NOT a vendor fan node → PServerWriter
 *     FIRST. This lights up (for free) DDR/bus devfreq, uclamp, IO scheduler,
 *     input-boost, governor tunables — all already discovered by CapabilityProbe —
 *     on any PServer-live device. Wins over the AYANEO binder and the chmod/
 *     vendor-settings ladder. The vendor FAN node is carved out
 *     ([isFanOnlyBinderNode]) so it stays on the vendor binder.
 *   * SYSFS on AYANEO binder (when NOT pserverSysfsLive) → AyaneoVendorWriter
 *     for the bindable node families.
 *   * SYSFS on Root tier → RootWriter (libsu).
 *   * SYSFS on Shizuku tier AND node passed the per-node write probe →
 *     ShizukuWriter (shell-UID binder write).
 *   * SYSFS on Shizuku tier AND node NOT probe-confirmed →
 *     NoopWriter with CapabilityDenied (honest: shell can't write this node).
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
    private val ayaneo: AyaneoVendorWriter,
) {
    fun writerFor(id: TunableId, report: CapabilityReport): SysfsWriter =
        when (id.kind) {
            TunableKind.SETTINGS_SYSTEM -> {
                // STRONGEST first: when PServer is confirmed transactable, route the
                // settings write through it on ANY device. A `settings put system`
                // executed by PServer's ROOT shell is strictly better than an app-UID
                // ContentResolver putString — it lands regardless of WRITE_SECURE_SETTINGS
                // and survives vendor services that ignore third-party Settings writes.
                // transactableNow() reads the memoised real-transact probe (warmed in
                // CapabilityProbe.refresh) — honest, never a binder()!=null false positive.
                //
                // COLD-PATH fallback (probe not warmed yet, or non-PServer device):
                // keep the brand-gated path — a vendor handheld with the binder published
                // still prefers PServer; everything else uses SettingsKeyWriter, which
                // works on stock kernels that wire perf controls through public Settings keys.
                when {
                    pserver.transactableNow() -> pserver
                    report.vendorApps.anyVendorPerfApp && pserver.binder() != null -> pserver
                    else -> settings
                }
            }
            TunableKind.VENDOR_INTENT -> settings
            TunableKind.SYSFS -> {
                // PSERVER-LIVE is the STRONGEST sysfs tier on ANY device that has it
                // (cross-vendor: AYN Odin + Retroid Pocket 6 both confirmed). PServer runs
                // as ROOT, so it writes cpufreq / GPU / DDR-bus devfreq / governor / uclamp /
                // IO-scheduler / input-boost nodes directly — no per-boot chmod, no vendor
                // overlay. It therefore wins over the AYANEO vendor binder and over the
                // chmod-direct / vendor-settings ladder for sysfs.
                //
                // FAN CARVE-OUT: the ONE family PServer must NOT steal is the vendor fan
                // node. On a device that is BOTH PServer-live and AYANEO-binder-live, the
                // hwmon pwm-fan node stays on the AYANEO binder (which drives the fan via
                // presets/curve — the binder owns fan actuation). isFanOnlyBinderNode()
                // matches the hwmon pwm-fan path families so the fan stays vendor-routed.
                // (The Retroid fan is a SEPARATE FanCurveController, not in this registry —
                // its MAX31760 I2C has no writable sysfs node — so there is no conflict
                // there: RP6 tunes CPU/GPU via PServer here AND keeps its fan binder.)
                //
                // HONESTY: report.pserverSysfsLive is only true when a REAL transact
                // round-trip confirmed PServer ran our command (warmed in CapabilityProbe).
                if (report.pserverSysfsLive && !isFanOnlyBinderNode(id.target)) {
                    pserver
                }
                // AYANEO VENDOR-BINDER LIVE tier (ZERO-SETUP, no root/Shizuku/script):
                // when the gamewindow AyaAidlService is bindable, route the BINDABLE node
                // families (CPU cluster scaling_max_freq / scaling_governor, GPU devfreq
                // max_freq, fan pwm) to AyaneoVendorWriter. The overlay (uid=system)
                // actuates the privileged write; AyaneoVendorWriter reads the node back to
                // verify. Reached only when NOT pserverSysfsLive (PServer-root is stronger),
                // OR for the fan node which the binder owns even alongside PServer.
                //
                // HONESTY: only BINDABLE nodes route here. Non-bindable SYSFS (cpu/online
                // core-parking, min-freq floor, GPU pwrlevel, devfreq min, uclamp, /proc)
                // fall through to the tier ladder → NoopWriter, so isLiveWritable honestly
                // reports them as not live-writable on AYANEO. ayaneoBinderLive is only
                // true when a REAL bind round-trip confirmed the service is bindable.
                else if (report.ayaneoBinderLive && AyaneoVendorWriter.isBindableNode(id.target)) {
                    ayaneo
                } else when (report.privilege) {
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
                        // NOTE: the PServer-LIVE non-fan case is already handled at the TOP
                        // of this SYSFS branch (it is the strongest tier on any device).
                        // We do NOT re-route pserverSysfsLive here, because the only way to
                        // reach this point with pserverSysfsLive == true is a FAN node that
                        // was deliberately carved out for the vendor binder — routing it to
                        // PServer would defeat that carve-out. So the fan node correctly
                        // continues down to the chmod/noop ladder if the vendor binder
                        // wasn't live.

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

    /**
     * True when [path] is a vendor FAN node that PServer-LIVE must NOT steal from
     * the vendor binder. The fan is owned by the vendor's overlay/binder (which
     * actuates it via presets/curves), not by a raw sysfs duty write — so even on
     * a PServer-live device the fan node stays on the vendor binder.
     *
     * Matches the generic hwmon pwm-fan path families (covers the AYANEO
     * `soc:pwm-fan` hwmon pwm node and any analogous hwmon-PWM fan path). This is
     * the ONLY carve-out from the "PServer wins sysfs" rule.
     *
     * (The Retroid Pocket 6 fan has NO writable sysfs node — MAX31760 over I2C —
     * and is driven by a separate FanCurveController that is not part of this
     * registry, so there is no fan node here for PServer to even consider on RP6.)
     */
    internal fun isFanOnlyBinderNode(path: String): Boolean =
        FAN_PWM_NODE.containsMatchIn(path)

    private companion object {
        /**
         * hwmon PWM fan node families. Matches the AYANEO `soc:pwm-fan` hwmon pwm
         * node and any hwmon path ending in a `pwmN` duty file — the vendor-binder
         * fan surface. Deliberately broad over hwmon/pwm so a future device's fan
         * node is also carved out of PServer routing.
         */
        private val FAN_PWM_NODE = Regex("""(?:pwm-fan/.*|hwmon\d+/)pwm\d+$""")
    }
}
