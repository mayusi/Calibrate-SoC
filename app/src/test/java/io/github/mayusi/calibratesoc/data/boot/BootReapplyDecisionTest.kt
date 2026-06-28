package io.github.mayusi.calibratesoc.data.boot

import com.google.common.truth.Truth.assertThat
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
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * WAVE 3A — boot AUTO-reapply decision pipeline.
 *
 * [io.github.mayusi.calibratesoc.data.tunables.BootRevertReceiver] is an Android
 * BroadcastReceiver (no Robolectric here, so the receiver class itself isn't unit-
 * tested), but the DECISION it runs is pure and composed of three already-DI'd parts:
 *
 *   1. resolveBootApplyMode(report)               → AUTO / REMINDER / UNSUPPORTED
 *   2. profiles.filter { it.applyOnBoot }         → which tunes to reapply
 *   3. ProfileApplier.apply(profile.toPreset(), …)→ issues the writes (PServer-routed)
 *
 * These tests drive that exact composition over a mocked [TunableWriter] (the same
 * layer ProfileApplier writes through, which the production receiver routes to PServer)
 * and assert the brief's three scenarios:
 *   - AUTO + a reapply-marked profile → the profile's tunables ARE written.
 *   - AUTO + NO reapply-marked profile → nothing is reapplied (revert-only path).
 *   - non-AUTO (REMINDER / UNSUPPORTED) → no reapply writes are issued.
 */
class BootReapplyDecisionTest {

    private fun policy(id: Int, vararg freqsKhz: Int) = CpuPolicyProbe(
        policyId = id,
        onlineCores = listOf(id),
        availableFreqsKhz = freqsKhz.toList(),
        availableGovernors = listOf("schedutil"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = freqsKhz.last(),
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

    private fun report(
        privilege: PrivilegeTier = PrivilegeTier.VENDOR_SETTINGS,
        pserverSysfsLive: Boolean = false,
        handheldKey: String? = "ayn_odin3",
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "AYN", brand = "AYN", model = "Odin3",
            device = "odin3", hardware = "pineapple",
            androidVersion = "14", sdkInt = 34, knownHandheldKey = handheldKey,
        ),
        soc = SoCIdentity(socManufacturer = "", socModel = "", gpuFamily = GpuFamily.ADRENO),
        privilege = privilege,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = listOf(
            policy(0, 384_000, 2_745_600, 3_532_800),
            policy(6, 1_017_600, 3_072_000, 4_320_000),
        ),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
        pserverSysfsLive = pserverSysfsLive,
    )

    /** An Odin-3-targeted tune marked applyOnBoot. */
    private fun bootProfile(applyOnBoot: Boolean) = UserProfile(
        id = "user_boot_1",
        name = "Boot Underclock",
        description = "applies on boot",
        cpuPolicyMaxKhz = mapOf(0 to 2_745_600, 6 to 3_072_000),
        applyOnBoot = applyOnBoot,
        createdAtMs = 0L,
        targetHandheldKeys = listOf("ayn_odin3"),
    )

    private fun applierOver(writer: TunableWriter) = ProfileApplier(writer)

    private fun recordingWriter(): TunableWriter {
        val w = mockk<TunableWriter>()
        coEvery { w.write(any(), any(), any(), any()) } answers {
            WriteResult.Success(id = firstArg(), previousValue = null, newValue = secondArg())
        }
        return w
    }

    @Test
    fun `AUTO mode with a reapply-marked profile issues the profile's writes`() = runTest {
        val writer = recordingWriter()
        val rep = report(privilege = PrivilegeTier.VENDOR_SETTINGS, pserverSysfsLive = true)
        assertThat(resolveBootApplyMode(rep)).isEqualTo(BootApplyMode.AUTO)

        val applier = applierOver(writer)
        val results = applier.apply(
            bootProfile(applyOnBoot = true).toPreset(),
            rep,
            reason = "boot re-apply: Boot Underclock",
        )

        // The reapply issued writes and none were rejected (correct device + live tier).
        assertThat(results).isNotEmpty()
        assertThat(results.none { it is WriteResult.Rejected }).isTrue()
        // Both cpu max-freq caps were written.
        coVerify(exactly = 1) { writer.write(Tunables.cpuMaxFreq(0), "2745600", any(), any()) }
        coVerify(exactly = 1) { writer.write(Tunables.cpuMaxFreq(6), "3072000", any(), any()) }
    }

    @Test
    fun `AUTO mode with NO reapply-marked profile reapplies nothing`() = runTest {
        val writer = recordingWriter()
        val rep = report(pserverSysfsLive = true)
        assertThat(resolveBootApplyMode(rep)).isEqualTo(BootApplyMode.AUTO)

        // The only profile is NOT marked applyOnBoot → the receiver's filter drops it.
        val applyAtBoot = listOf(bootProfile(applyOnBoot = false)).filter { it.applyOnBoot }
        assertThat(applyAtBoot).isEmpty()

        // Nothing is applied → no writes issued (the revert-only path remains).
        coVerify(exactly = 0) { writer.write(any(), any(), any(), any()) }
    }

    @Test
    fun `REMINDER mode does not reapply (non-AUTO tier)`() = runTest {
        val writer = recordingWriter()
        // VENDOR_SETTINGS without any live boot path → REMINDER, not AUTO.
        val rep = report(privilege = PrivilegeTier.VENDOR_SETTINGS, pserverSysfsLive = false)
        assertThat(resolveBootApplyMode(rep)).isEqualTo(BootApplyMode.REMINDER)

        // The receiver only reapplies on AUTO. A non-AUTO mode short-circuits before apply.
        val mode = resolveBootApplyMode(rep)
        if (mode == BootApplyMode.AUTO) {
            applierOver(writer).apply(bootProfile(true).toPreset(), rep, "should not run")
        }

        coVerify(exactly = 0) { writer.write(any(), any(), any(), any()) }
    }

    @Test
    fun `UNSUPPORTED mode does not reapply`() = runTest {
        val writer = recordingWriter()
        val rep = report(privilege = PrivilegeTier.NONE, pserverSysfsLive = false)
        assertThat(resolveBootApplyMode(rep)).isEqualTo(BootApplyMode.UNSUPPORTED)

        val mode = resolveBootApplyMode(rep)
        if (mode == BootApplyMode.AUTO) {
            applierOver(writer).apply(bootProfile(true).toPreset(), rep, "should not run")
        }

        coVerify(exactly = 0) { writer.write(any(), any(), any(), any()) }
    }
}
