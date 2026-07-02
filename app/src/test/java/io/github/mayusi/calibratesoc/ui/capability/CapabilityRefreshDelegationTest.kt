package io.github.mayusi.calibratesoc.ui.capability

import android.content.Context
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.baseline.FactoryBaselineRecorder
import io.github.mayusi.calibratesoc.data.baseline.FactoryRestorer
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.prefs.ClockUnit
import io.github.mayusi.calibratesoc.data.prefs.TempUnit
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.ui.settings.SettingsViewModel
import io.github.mayusi.calibratesoc.ui.theme.AccentColor
import io.github.mayusi.calibratesoc.ui.tune.AdvancedUnlockViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 0.3.7 — the tier-showing ViewModels must route their auto/on-demand re-checks
 * through [CapabilityProbe.fullRefresh] (both-cache bust) rather than the cheap
 * pull-only [CapabilityProbe.refresh]. If they used refresh(), an out-of-app
 * `pm grant` would re-read the stale live-write caches and the tier would stay
 * frozen — the exact bug this release fixes.
 *
 * These tests pin the delegation contract:
 *   - SettingsViewModel.fullRefresh() (was missing entirely) -> fullRefresh()
 *   - AdvancedUnlockViewModel.refresh() -> fullRefresh() (was PServer-cache only)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityRefreshDelegationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // SettingsViewModel.checkAccessibilityGranted() reads Settings.Secure and,
        // only if the result is non-null, builds a ComponentName + flattenToString()
        // (which NPEs under the project's isReturnDefaultValues test config). Return
        // null so the check short-circuits to false BEFORE touching ComponentName —
        // the accessibility state is irrelevant to this delegation test.
        mockkStatic(Settings.Secure::class)
        every { Settings.Secure.getString(any(), any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── SettingsViewModel.fullRefresh() ──────────────────────────────────────

    @Test
    fun `SettingsViewModel fullRefresh delegates to capabilityProbe fullRefresh`() = runTest {
        val probe: CapabilityProbe = mockk(relaxed = true)
        every { probe.report } returns MutableStateFlow<CapabilityReport?>(null)

        val vm = SettingsViewModel(
            context = mockk<Context>(relaxed = true),
            capabilityProbe = probe,
            userPrefs = fakeUserPrefs(),
            baselineRecorder = mockk<FactoryBaselineRecorder>(relaxed = true).also {
                every { it.existing() } returns null
            },
            factoryRestorer = mockk<FactoryRestorer>(relaxed = true),
            profileRepository = mockk<ProfileRepository>(relaxed = true).also {
                every { it.store } returns flowOf(ProfileStore())
            },
        )

        vm.fullRefresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { probe.fullRefresh() }
    }

    // ── AdvancedUnlockViewModel.refresh() ────────────────────────────────────

    @Test
    fun `AdvancedUnlockViewModel refresh delegates to capabilityProbe fullRefresh (busts BOTH caches)`() =
        runTest {
            val probe: CapabilityProbe = mockk(relaxed = true)
            every { probe.report } returns MutableStateFlow<CapabilityReport?>(null)
            val script: AdvancedPermissionsScript = mockk(relaxed = true)
            every { script.grantsCurrentlyHeld() } returns AdvancedPermissionsScript.Grants(
                dump = false,
                usageStats = false,
                writeSecureSettings = false,
                sysfsWritable = false,
            )

            val vm = AdvancedUnlockViewModel(
                script = script,
                pServerWriter = mockk<PServerWriter>(relaxed = true),
                capabilityProbe = probe,
            )

            vm.refresh()
            advanceUntilIdle()

            // The whole point of the 0.3.7 change: refresh() now goes through
            // fullRefresh (which busts PServer AND AYANEO caches), not the bare
            // refresh() that would re-read a stale AYANEO availability cache.
            coVerify(exactly = 1) { probe.fullRefresh() }
        }

    // ── Poll interval const ──────────────────────────────────────────────────

    @Test
    fun `capability poll interval is 2 seconds`() {
        // The foreground poll cadence that makes an out-of-app grant reflect
        // in ~2 s. Pinned so a stray edit can't silently make it 20 s (too slow
        // to feel instant) or 200 ms (needless wakeups).
        assertThat(CAPABILITY_POLL_MS).isEqualTo(2000L)
    }

    private fun fakeUserPrefs(): UserPrefs = mockk<UserPrefs>(relaxed = true).also { prefs ->
        every { prefs.rootModeEnabled } returns flowOf(false)
        every { prefs.experimentalEnabled } returns flowOf(false)
        every { prefs.accentColor } returns flowOf(AccentColor.BLUE)
        every { prefs.clockUnit } returns flowOf(ClockUnit.MHZ)
        every { prefs.tempUnit } returns flowOf(TempUnit.CELSIUS)
        every { prefs.lastSeenVersion } returns flowOf(0)
        every { prefs.autoUpdateCheckEnabled } returns flowOf(true)
        every { prefs.autoConfigKnownGamesEnabled } returns flowOf(true)
        every { prefs.tempAlertsEnabled } returns flowOf(false)
        every { prefs.tempAlertThresholdC } returns flowOf(80)
        every { prefs.tempAlertAutoProfileId } returns flowOf(null)
    }
}
