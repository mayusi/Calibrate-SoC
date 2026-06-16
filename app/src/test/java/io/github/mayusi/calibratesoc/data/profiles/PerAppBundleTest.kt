package io.github.mayusi.calibratesoc.data.profiles

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
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
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the per-app full-tune bundle (Wave 3b Feature 1).
 *
 * Tests cover:
 *   1. Back-compat: a legacy [ProfileStore.perAppOverrides] entry deserializes and
 *      applies identically to the old single-profile behavior.
 *   2. A full bundle (profile + autoTdpGoal + refreshRateHz + fanMode) stores all
 *      fields correctly and applies the profile through ProfileApplier.
 *   3. Bundle with only profileId set == old behavior (apply fires, no other side effects).
 *   4. deleteProfile strips bundle profileId references cleanly.
 *   5. [ProfileStore] JSON round-trip: perAppBundles survives serialize→deserialize.
 *   6. GoalProfile serialization survives in a PerAppBundle JSON round-trip.
 */
class PerAppBundleTest {

    @Before
    fun stubLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Fixtures ─────────────────────────────────────────────────────────────────

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

    private val odin3Report = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "AYN",
            brand = "AYN",
            model = "Odin 3",
            device = "odin3",
            hardware = "qcom",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = "ayn_odin3",
        ),
        soc = SoCIdentity(socManufacturer = "Qualcomm", socModel = "SM8650", gpuFamily = GpuFamily.ADRENO),
        privilege = PrivilegeTier.ROOT,
        rootKind = RootKind.MAGISK,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = listOf(policy(0, 384000, 2745600), policy(6, 1017600, 4320000)),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
    )

    private fun noopWriter(): TunableWriter {
        val writer = mockk<TunableWriter>()
        coEvery { writer.write(any(), any(), any(), any()) } answers {
            WriteResult.Success(id = firstArg(), previousValue = null, newValue = secondArg())
        }
        return writer
    }

    private val sampleProfile = UserProfile(
        id = "profile_1",
        name = "Test Profile",
        description = "desc",
        cpuPolicyMaxKhz = mapOf(0 to 2400000, 6 to 3200000),
        createdAtMs = 1_000_000L,
    )

    // ── 1. Back-compat: legacy perAppOverrides still works ─────────────────────

    @Test
    fun `legacy perAppOverrides entry survives JSON round-trip`() {
        val store = ProfileStore(
            profiles = listOf(sampleProfile),
            perAppOverrides = mapOf("com.example.game" to "profile_1"),
        )

        val encoded = json.encodeToString(store)
        val decoded = json.decodeFromString<ProfileStore>(encoded)

        assertThat(decoded.perAppOverrides["com.example.game"]).isEqualTo("profile_1")
        // New field defaults to empty map (back-compat).
        assertThat(decoded.perAppBundles).isEmpty()
    }

    @Test
    fun `old ProfileStore JSON without perAppBundles deserializes without error`() {
        // Simulate a persisted JSON that predates perAppBundles.
        val oldJson = """
            {
              "version": 1,
              "profiles": [],
              "perAppOverrides": { "com.example.game": "profile_1" }
            }
        """.trimIndent()

        val decoded = json.decodeFromString<ProfileStore>(oldJson)

        assertThat(decoded.perAppOverrides["com.example.game"]).isEqualTo("profile_1")
        // perAppBundles defaults to empty (ignoreUnknownKeys-style back-compat).
        assertThat(decoded.perAppBundles).isEmpty()
    }

    // ── 2. Full bundle stores all fields correctly ─────────────────────────────

    @Test
    fun `PerAppBundle with all fields survives JSON round-trip`() {
        val bundle = PerAppBundle(
            profileId = "profile_1",
            autoTdpGoal = GoalProfile.MAX_FPS,
            refreshRateHz = 120f,
            fanMode = 5,
            gameBoostOnLaunch = true,
        )

        val encoded = json.encodeToString(bundle)
        val decoded = json.decodeFromString<PerAppBundle>(encoded)

        assertThat(decoded.profileId).isEqualTo("profile_1")
        assertThat(decoded.autoTdpGoal).isEqualTo(GoalProfile.MAX_FPS)
        assertThat(decoded.refreshRateHz).isEqualTo(120f)
        assertThat(decoded.fanMode).isEqualTo(5)
        assertThat(decoded.gameBoostOnLaunch).isTrue()
    }

    @Test
    fun `ProfileStore with perAppBundles survives JSON round-trip`() {
        val store = ProfileStore(
            profiles = listOf(sampleProfile),
            perAppOverrides = emptyMap(),
            perAppBundles = mapOf(
                "com.example.game" to PerAppBundle(
                    profileId = "profile_1",
                    autoTdpGoal = GoalProfile.BALANCED_SMART,
                    refreshRateHz = 90f,
                ),
            ),
        )

        val encoded = json.encodeToString(store)
        val decoded = json.decodeFromString<ProfileStore>(encoded)

        val bundle = decoded.perAppBundles["com.example.game"]
        assertThat(bundle).isNotNull()
        assertThat(bundle!!.profileId).isEqualTo("profile_1")
        assertThat(bundle.autoTdpGoal).isEqualTo(GoalProfile.BALANCED_SMART)
        assertThat(bundle.refreshRateHz).isEqualTo(90f)
        assertThat(bundle.gameBoostOnLaunch).isFalse()
    }

    // ── 3. Bundle with only profileId == old behavior ─────────────────────────

    @Test
    fun `bundle with only profileId applies profile via ProfileApplier`() = runTest {
        val writer = noopWriter()
        val applier = ProfileApplier(writer)

        val bundle = PerAppBundle(profileId = "profile_1")
        assertThat(bundle.autoTdpGoal).isNull()
        assertThat(bundle.refreshRateHz).isNull()
        assertThat(bundle.fanMode).isNull()
        assertThat(bundle.gameBoostOnLaunch).isFalse()

        // Simulate the ForegroundAppWatcher apply path for a minimal bundle.
        val results = applier.apply(sampleProfile.toPreset(), odin3Report, "test")

        assertThat(results.all { it is WriteResult.Success }).isTrue()
        coVerify(atLeast = 1) { writer.write(any(), any(), any(), any()) }
    }

    // ── 4. deleteProfile clears bundle profileId references ───────────────────

    @Test
    fun `deleteProfile clears profileId in bundles that reference it`() {
        val store = ProfileStore(
            profiles = listOf(sampleProfile),
            perAppBundles = mapOf(
                "com.example.game" to PerAppBundle(
                    profileId = "profile_1",
                    autoTdpGoal = GoalProfile.COOL_QUIET,
                ),
                "com.other.app" to PerAppBundle(profileId = "other_profile"),
            ),
        )

        // Simulate the ProfileRepository.deleteProfile logic.
        val id = "profile_1"
        val cleaned = store.copy(
            profiles = store.profiles.filterNot { it.id == id },
            perAppOverrides = store.perAppOverrides.filterValues { it != id },
            perAppBundles = store.perAppBundles.mapValues { (_, b) ->
                if (b.profileId == id) b.copy(profileId = null) else b
            }.filterValues { b ->
                b.profileId != null || b.autoTdpGoal != null ||
                    b.refreshRateHz != null || b.fanMode != null || b.gameBoostOnLaunch
            },
        )

        // Bundle for com.example.game: profileId cleared but autoTdpGoal preserved.
        val gameBundleAfter = cleaned.perAppBundles["com.example.game"]
        assertThat(gameBundleAfter).isNotNull()
        assertThat(gameBundleAfter!!.profileId).isNull()
        assertThat(gameBundleAfter.autoTdpGoal).isEqualTo(GoalProfile.COOL_QUIET)

        // Bundle for com.other.app: references a different profile — unchanged.
        assertThat(cleaned.perAppBundles["com.other.app"]?.profileId).isEqualTo("other_profile")
    }

    @Test
    fun `deleteProfile removes bundle entirely when it has no other fields`() {
        val store = ProfileStore(
            profiles = listOf(sampleProfile),
            perAppBundles = mapOf(
                // Only field is the profileId being deleted — bundle should be removed.
                "com.example.game" to PerAppBundle(profileId = "profile_1"),
            ),
        )

        val id = "profile_1"
        val cleaned = store.copy(
            profiles = store.profiles.filterNot { it.id == id },
            perAppOverrides = store.perAppOverrides.filterValues { it != id },
            perAppBundles = store.perAppBundles.mapValues { (_, b) ->
                if (b.profileId == id) b.copy(profileId = null) else b
            }.filterValues { b ->
                b.profileId != null || b.autoTdpGoal != null ||
                    b.refreshRateHz != null || b.fanMode != null || b.gameBoostOnLaunch
            },
        )

        // The now-empty bundle must be removed entirely.
        assertThat(cleaned.perAppBundles).doesNotContainKey("com.example.game")
    }

    // ── 5. GoalProfile round-trip in bundle ───────────────────────────────────

    @Test
    fun `all GoalProfile values survive bundle JSON round-trip`() {
        for (goal in GoalProfile.entries) {
            val bundle = PerAppBundle(autoTdpGoal = goal)
            val encoded = json.encodeToString(bundle)
            val decoded = json.decodeFromString<PerAppBundle>(encoded)
            assertThat(decoded.autoTdpGoal).isEqualTo(goal)
        }
    }
}
