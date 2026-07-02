package io.github.mayusi.calibratesoc.data.capability

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import org.junit.Test

/**
 * 0.3.7 — anti-regression coverage for [CapabilityProbe.fullRefresh].
 *
 * The bug it guards: an out-of-app `pm grant … WRITE_SECURE_SETTINGS` (or a
 * Shizuku/root/vendor unlock) performed while the app stays foregrounded fires no
 * lifecycle transition and no permission broadcast. Both live-write probes
 * ([PServerWriter.isTransactable], [AyaneoBinderClient.isAvailable]) memoise their
 * result for the session, so a plain [CapabilityProbe.refresh] re-reads the STALE
 * caches and the privilege tier stays frozen until process restart.
 *
 * [CapabilityProbe.fullRefresh] is the single, forget-proof fix: it MUST bust BOTH
 * caches BEFORE re-probing. If a future edit drops one of the two invalidations
 * (the "forgot a cache" class of bug), the ordering/verify assertions below fail.
 *
 * All probe collaborators are relaxed mocks — this test is about the cache-bust +
 * re-emit contract, not the detection logic (covered elsewhere).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityProbeFullRefreshTest {

    private val pServerWriter: PServerWriter = mockk(relaxed = true)
    private val ayaneoBinderClient: AyaneoBinderClient = mockk(relaxed = true)

    private fun newProbe(): CapabilityProbe {
        val socDetector: SoCDetector = mockk(relaxed = true)
        // Return a concrete, non-UNKNOWN GPU family so upgradeFamilyByPathPresence
        // short-circuits without touching the (fake) filesystem.
        every { socDetector.detect() } returns Pair(
            DeviceIdentity(
                manufacturer = "AYN",
                brand = "AYN",
                model = "Odin3",
                device = "odin3",
                hardware = "qcom",
                androidVersion = "14",
                sdkInt = 34,
                knownHandheldKey = "odin3",
            ),
            SoCIdentity(
                socManufacturer = "Qualcomm",
                socModel = "SM8650",
                gpuFamily = GpuFamily.ADRENO,
            ),
        )

        val rootProbe: RootProbe = mockk(relaxed = true)
        every { rootProbe.probe() } returns Pair(false, RootKind.NONE)

        val shizukuProbe: ShizukuProbe = mockk(relaxed = true)
        every { shizukuProbe.probe() } returns ShizukuStatus(
            installed = false,
            running = false,
            permissionGranted = false,
            sysfsWriteAllowed = null,
        )

        val settingsWriteProbe: SettingsWriteProbe = mockk(relaxed = true)
        every { settingsWriteProbe.hasWriteSecureSettings() } returns false

        val vendorAppDetector: VendorAppDetector = mockk(relaxed = true)
        every { vendorAppDetector.detect() } returns VendorAppPresence(
            aynGameAssistant = false,
            langerhansOdinTools = false,
            ayaSpace = false,
            retroidGameAssistant = false,
        )

        val userPrefs: UserPrefs = mockk(relaxed = true)
        every { userPrefs.rootModeEnabledBlocking() } returns false

        val advancedPermissionsScript: AdvancedPermissionsScript = mockk(relaxed = true)
        every { advancedPermissionsScript.grantsCurrentlyHeld() } returns
            AdvancedPermissionsScript.Grants(
                dump = false,
                usageStats = false,
                writeSecureSettings = false,
                sysfsWritable = false,
            )

        return CapabilityProbe(
            socDetector = socDetector,
            sysfsProber = mockk(relaxed = true),
            rootProbe = rootProbe,
            shizukuProbe = shizukuProbe,
            settingsWriteProbe = settingsWriteProbe,
            vendorAppDetector = vendorAppDetector,
            fileSystem = FakeFileSystem(),
            userPrefs = userPrefs,
            advancedPermissionsScript = advancedPermissionsScript,
            pServerWriter = pServerWriter,
            ayaneoBinderClient = ayaneoBinderClient,
            selinuxProbe = mockk(relaxed = true),
        )
    }

    @Test
    fun `fullRefresh busts BOTH writer caches`() = runTest {
        val probe = newProbe()

        probe.fullRefresh()

        verify(exactly = 1) { pServerWriter.invalidateTransactableCache() }
        verify(exactly = 1) { ayaneoBinderClient.invalidateAvailabilityCache() }
    }

    @Test
    fun `fullRefresh busts both caches BEFORE re-probing`() = runTest {
        val probe = newProbe()

        probe.fullRefresh()

        // The two invalidations must both precede the transactability re-probe
        // (isTransactable / isAvailable — both suspend) that refresh() performs.
        // If a future edit re-orders so the probe reads before the bust, the stale
        // cache wins — this fails. coVerifyOrder handles the suspend probe calls.
        coVerifyOrder {
            pServerWriter.invalidateTransactableCache()
            pServerWriter.isTransactable()
        }
        coVerifyOrder {
            ayaneoBinderClient.invalidateAvailabilityCache()
            ayaneoBinderClient.isAvailable()
        }
    }

    @Test
    fun `fullRefresh re-emits a report into the shared flow`() = runTest {
        val probe = newProbe()
        assertThat(probe.report.value).isNull()

        val returned = probe.fullRefresh()

        assertThat(returned).isNotNull()
        assertThat(probe.report.value).isEqualTo(returned)
    }

    @Test
    fun `plain refresh does NOT bust the caches (only fullRefresh does)`() = runTest {
        // Documents the contract: refresh() is the cheap re-read; fullRefresh() is
        // the one that busts caches. Callers reacting to an out-of-app grant must
        // use fullRefresh — this pins that distinction.
        val probe = newProbe()

        probe.refresh()

        verify(exactly = 0) { pServerWriter.invalidateTransactableCache() }
        verify(exactly = 0) { ayaneoBinderClient.invalidateAvailabilityCache() }
    }

    @Test
    fun `invalidate stubs are callable (relaxed) - sanity`() {
        // Guards against the mock signatures drifting from the real API.
        every { pServerWriter.invalidateTransactableCache() } just Runs
        every { ayaneoBinderClient.invalidateAvailabilityCache() } just Runs
        pServerWriter.invalidateTransactableCache()
        ayaneoBinderClient.invalidateAvailabilityCache()
        coVerify(exactly = 0) { pServerWriter.executeShell(any()) }
    }
}
