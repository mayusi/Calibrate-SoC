package io.github.mayusi.calibratesoc.data.thermal

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.FreqRange
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import org.junit.Test

/**
 * Regression tests for the AYANEO Throttle Guard crash-risk sibling of the AutoTDP
 * writable-ceiling fix ([io.github.mayusi.calibratesoc.data.autotdp.TdpCapsWritableCeilingTest]):
 * [ThrottleGuardService.runDaemon] previously wired [ThrottleGuardActuator] straight from
 * `bigPolicy.availableFreqsKhz` — the RAW full kernel OPP table (top 2 592 000 kHz on the
 * AYANEO Pocket DS) — instead of the vendor-WRITABLE ceiling (stock scaling_max_freq =
 * 1 785 600 kHz). On a constrained vendor write path the `gamewindow` overlay REJECTS any
 * scaling_max_freq above the stock ceiling, so a predicted-throttle cap targeting above it
 * would never actuate AND could repeat the rejected-write storm that crashed
 * `com.ayaneo.gamewindow` via the AutoTDP idle path.
 *
 * These tests exercise the EXACT wiring the service now performs — `TdpCaps.from(report)`
 * feeding `ThrottleGuardActuator(caps.bigPolicyId, caps.bigClusterWritableMaxKhz,
 * caps.bigClusterOppStepsKhz)` — so a regression back to the raw kernel table would fail
 * here without needing an Android `Service` instance.
 */
class ThrottleGuardWritableCeilingTest {

    // ─── Fixture builders (mirrors TdpCapsWritableCeilingTest) ───────────────────

    private fun policy(
        id: Int,
        freqsKhz: List<Int>,
        onlineCores: List<Int> = listOf(id),
        stockMaxKhz: Int = freqsKhz.last(),
    ) = CpuPolicyProbe(
        policyId = id,
        onlineCores = onlineCores,
        availableFreqsKhz = freqsKhz,
        availableGovernors = listOf("schedutil"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = stockMaxKhz,
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

    private fun reportWith(
        policies: List<CpuPolicyProbe>,
        privilege: PrivilegeTier = PrivilegeTier.VENDOR_SETTINGS,
        pserverSysfsLive: Boolean = false,
        sysfsDirectlyWritable: Boolean = false,
        ayaneoBinderLive: Boolean = false,
    ): CapabilityReport = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Test",
            brand = "Test",
            model = "TestDevice",
            device = "test",
            hardware = "test",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = null,
        ),
        soc = SoCIdentity("Qualcomm", "Snapdragon", GpuFamily.ADRENO),
        privilege = privilege,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
        pserverSysfsLive = pserverSysfsLive,
        sysfsDirectlyWritable = sysfsDirectlyWritable,
        ayaneoBinderLive = ayaneoBinderLive,
    )

    // The AYANEO Pocket DS big-cluster OPP table (kHz): kernel top 2 592 000, but the
    // vendor overlay's stock scaling_max_freq is 1 785 600 — anything above is rejected.
    private val ayaneoFullOpp = listOf(
        691_200, 940_800, 1_190_400, 1_440_000, 1_555_200,
        1_632_000, 1_785_600, 1_881_600, 2_035_200, 2_265_600, 2_592_000,
    )
    private val ayaneoStockCeilingKhz = 1_785_600
    private val ayaneoKernelTopKhz = 2_592_000

    /** Builds the actuator using the EXACT wiring ThrottleGuardService.runDaemon performs. */
    private fun actuatorFor(report: CapabilityReport): ThrottleGuardActuator {
        val caps = TdpCaps.from(report)
        val stockCeilingKhz = caps.bigClusterWritableMaxKhz.takeIf { it > 0 }
            ?: (report.cpuPolicies.first { it.policyId == caps.bigPolicyId }.availableFreqsKhz.maxOrNull() ?: 0)
        return ThrottleGuardActuator(
            bigPolicyId = caps.bigPolicyId,
            stockCeilingKhz = stockCeilingKhz,
            availableFreqsKhz = caps.bigClusterOppStepsKhz,
        )
    }

    // ─── The crash fix: AYANEO guard never targets above the writable ceiling ────

    @Test
    fun `AYANEO throttle guard cap never targets above the writable ceiling`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            ayaneoBinderLive = true,
        )
        val actuator = actuatorFor(report)

        // A forecast recommending a cap AT the raw kernel top (the pre-fix bug: this used
        // to be accepted verbatim and written straight to scaling_max_freq — rejected by
        // the AYANEO overlay). The actuator must snap it DOWN to the writable ceiling.
        val forecast = ThrottleForecast(
            willThrottleInSec = 4,
            recommendedCapKhz = ayaneoKernelTopKhz,
            reason = "imminent",
        )
        val action = actuator.decide(forecast, suppressed = false)

        // Recommended cap == the writable ceiling is "no cap" (>= stockCeiling), so no
        // write should be issued at all — never a write targeting the kernel top.
        assertThat(action.write).isNull()
        assertThat(action.activeCapKhz).isNull()

        // DEAD-CPU-CAP COLLAPSE (SHIP-BLOCKER fix in TdpCaps.from): on the AYANEO
        // vendor-binder path the CPU scaling_max_freq write is a stock-only no-op — ANY
        // sub-stock value is REJECTED by the `gamewindow` overlay (live-proven). The guard
        // actuates the SAME node via the SAME dead lever as AutoTDP, so a sub-stock guard
        // cap would be rejected identically. TdpCaps.from now collapses
        // bigClusterOppStepsKhz to a SINGLE stock step on this path, which the guard reads
        // (ThrottleGuardService wires availableFreqsKhz = caps.bigClusterOppStepsKhz). With
        // only the stock OPP available, a sub-stock recommendation snaps to stock →
        // clamped >= stockCeiling → "no cap" → NO write. This is CORRECT: the guard now
        // declines the physically-doomed CPU cap on AYANEO instead of emitting a rejected
        // write (mirrors the AutoTDP fall-through to the working GPU lever). On Odin/RP6/
        // ROOT (fullKernelWritable → no collapse) the guard keeps its full multi-step table
        // and still caps sub-stock — see the unregressed tests below.
        val tighter = ThrottleForecast(
            willThrottleInSec = 2,
            recommendedCapKhz = 1_700_000, // below the writable ceiling (1_785_600)
            reason = "imminent",
        )
        val tighterAction = actuator.decide(tighter, suppressed = false)
        // The dead CPU-cap lever is declined: no rejected sub-stock write is emitted, and
        // never a write targeting a raw above-ceiling kernel OPP.
        assertThat(tighterAction.write).isNull()
        assertThat(tighterAction.activeCapKhz).isNull()
    }

    @Test
    fun `AYANEO throttle guard revert-to-stock never writes above the writable ceiling`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            ayaneoBinderLive = true,
        )
        val actuator = actuatorFor(report)

        // DEAD-CPU-CAP COLLAPSE (SHIP-BLOCKER fix): on the AYANEO vendor-binder path the CPU
        // cap is a dead lever, so TdpCaps.from collapses bigClusterOppStepsKhz to a single
        // stock step. A sub-stock recommendation (1_600_000) therefore snaps to stock → NO
        // cap is ever applied (activeCapKhz stays null). When the forecast clears there is
        // nothing to revert, so no write is emitted. This is CORRECT: the guard never made a
        // (doomed) sub-stock write on AYANEO, so there is nothing to undo. TunableWriter's
        // journal + revertAll remain the guaranteed stock-restore backstop on stop
        // regardless. (Odin/RP6/ROOT keep the multi-step table and the real apply→revert
        // path — see the unregressed test below.)
        actuator.decide(
            ThrottleForecast(willThrottleInSec = 2, recommendedCapKhz = 1_600_000, reason = "imminent"),
            suppressed = false,
        )
        val cleared = actuator.decide(ThrottleForecast.noAction("stable"), suppressed = false)

        // No cap was applied (dead lever declined) → no revert write, and definitely never
        // a revert targeting the raw above-ceiling kernel top.
        assertThat(cleared.write).isNull()
        assertThat(cleared.activeCapKhz).isNull()
    }

    // ─── No regression: fully-kernel-writable devices still reach the kernel top ──

    @Test
    fun `PServer-live device throttle guard still reaches the kernel top (Odin 3 RP6 unregressed)`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            privilege = PrivilegeTier.VENDOR_SETTINGS,
            pserverSysfsLive = true,
        )
        val actuator = actuatorFor(report)

        // Revert-to-stock on a fully-writable device targets the real kernel top.
        actuator.decide(
            ThrottleForecast(willThrottleInSec = 2, recommendedCapKhz = 1_600_000, reason = "imminent"),
            suppressed = false,
        )
        val cleared = actuator.decide(ThrottleForecast.noAction("stable"), suppressed = false)

        assertThat(cleared.write).isNotNull()
        assertThat(cleared.write!!.value).isEqualTo(ayaneoKernelTopKhz.toString())

        // A recommendation right at the kernel top is treated as "no cap" (>= stock ceiling).
        val atTop = actuator.decide(
            ThrottleForecast(willThrottleInSec = 2, recommendedCapKhz = ayaneoKernelTopKhz, reason = "imminent"),
            suppressed = false,
        )
        assertThat(atTop.write).isNull()
    }

    @Test
    fun `ROOT device throttle guard OPP table includes the kernel top`() {
        val report = reportWith(
            policies = listOf(
                policy(0, listOf(384_000, 1_555_200), onlineCores = listOf(0, 1, 2, 3)),
                policy(4, ayaneoFullOpp, onlineCores = listOf(4, 5, 6, 7), stockMaxKhz = ayaneoStockCeilingKhz),
            ),
            privilege = PrivilegeTier.ROOT,
        )
        val caps = TdpCaps.from(report)

        assertThat(caps.bigClusterOppStepsKhz).contains(ayaneoKernelTopKhz)
        assertThat(caps.bigClusterWritableMaxKhz).isEqualTo(ayaneoKernelTopKhz)
    }
}
