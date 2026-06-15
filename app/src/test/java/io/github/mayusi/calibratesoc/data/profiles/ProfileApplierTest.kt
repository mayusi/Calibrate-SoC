package io.github.mayusi.calibratesoc.data.profiles

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
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [ProfileApplier]'s hard safety gates:
 *   1. Device-targeting gate: rejects a preset whose targetHandheldKeys
 *      does not include the current device.
 *   2. Policy-existence gate: rejects a preset that references policyIds
 *      the current device does not have.
 */
class ProfileApplierTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A TunableWriter that never does real I/O — returns Success for everything. */
    private fun noopWriter(): TunableWriter {
        val writer = mockk<TunableWriter>()
        coEvery { writer.write(any(), any(), any(), any()) } answers {
            WriteResult.Success(
                id = firstArg(),
                previousValue = null,
                newValue = secondArg(),
            )
        }
        return writer
    }

    private fun makeApplier(writer: TunableWriter = noopWriter()) = ProfileApplier(writer)

    private fun policy(id: Int, freqsKhz: List<Int>) = CpuPolicyProbe(
        policyId = id,
        onlineCores = listOf(id),
        availableFreqsKhz = freqsKhz,
        availableGovernors = listOf("schedutil", "walt"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = freqsKhz.last(),
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

    private fun report(
        knownHandheldKey: String?,
        policies: List<CpuPolicyProbe>,
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Test",
            brand = "Test",
            model = "Test",
            device = "test",
            hardware = "test",
            androidVersion = "14",
            sdkInt = 34,
            knownHandheldKey = knownHandheldKey,
        ),
        soc = SoCIdentity(socManufacturer = "", socModel = "", gpuFamily = GpuFamily.ADRENO),
        privilege = PrivilegeTier.ROOT,
        rootKind = RootKind.MAGISK,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
    )

    // ── Safety gate 1: device-targeting ──────────────────────────────────────

    @Test
    fun `device-targeted preset is rejected on wrong device`() = runTest {
        // RP6 preset targeting only retroid_pocket6.
        val rp6Preset = Preset(
            id = "rp6_ps2_gc",
            name = "RP6 PS2 GC",
            description = "RP6 only",
            verification = VerificationTier.COMMUNITY_TUNED,
            cpuPolicyMaxKhz = mapOf(0 to 1555000, 3 to 1920000, 7 to 2227000),
            targetHandheldKeys = listOf("retroid_pocket6"),
        )
        // Odin 3 report — policy0 + policy6 only, device key = ayn_odin3.
        val odin3Report = report(
            knownHandheldKey = "ayn_odin3",
            policies = listOf(
                policy(0, listOf(384000, 2745600, 3532800)),
                policy(6, listOf(1017600, 3072000, 4320000)),
            ),
        )

        val results = makeApplier().apply(rp6Preset, odin3Report, "test")

        // Must be a single Rejected result with a clear error message.
        assertThat(results).hasSize(1)
        val rejected = results.single() as WriteResult.Rejected
        assertThat(rejected.message).contains("retroid_pocket6")
        assertThat(rejected.message).contains("ayn_odin3")
    }

    @Test
    fun `device-targeted preset is accepted on matching device`() = runTest {
        val rp6Preset = Preset(
            id = "rp6_ps2_gc",
            name = "RP6 PS2 GC",
            description = "RP6 only",
            verification = VerificationTier.COMMUNITY_TUNED,
            cpuPolicyMaxKhz = mapOf(0 to 1555000, 3 to 1920000, 7 to 2227000),
            targetHandheldKeys = listOf("retroid_pocket6"),
        )
        val rp6Report = report(
            knownHandheldKey = "retroid_pocket6",
            policies = listOf(
                policy(0, listOf(307000, 1555000, 2016000)),
                policy(3, listOf(499000, 1920000, 2803000)),
                policy(7, listOf(595000, 2227000, 3187000)),
            ),
        )

        val results = makeApplier().apply(rp6Preset, rp6Report, "test")

        // All results must be Success (noop writer).
        assertThat(results).isNotEmpty()
        assertThat(results.all { it is WriteResult.Success }).isTrue()
    }

    @Test
    fun `preset with null targetHandheldKeys is accepted on any device`() = runTest {
        val universalPreset = Preset(
            id = "universal",
            name = "Universal",
            description = "for all",
            verification = VerificationTier.GENERIC_KNOWN_FAMILY,
            cpuPolicyMaxKhz = mapOf(0 to 1000000),
            targetHandheldKeys = null,
        )
        // Apply on an entirely unrecognised device (key = null).
        val unknownReport = report(
            knownHandheldKey = null,
            policies = listOf(policy(0, listOf(300000, 1000000, 2000000))),
        )

        val results = makeApplier().apply(universalPreset, unknownReport, "test")

        assertThat(results).isNotEmpty()
        assertThat(results.all { it is WriteResult.Success }).isTrue()
    }

    // ── Safety gate 2: policy-existence ──────────────────────────────────────

    @Test
    fun `preset with non-existent policyId is blocked`() = runTest {
        // Preset declares policy3 and policy7 — but the device only has policy0.
        val rp6PresetsOnSingleCluster = Preset(
            id = "wrong_topology",
            name = "Wrong topology",
            description = "references non-existent policies",
            verification = VerificationTier.GENERIC_UNKNOWN_FAMILY,
            cpuPolicyMaxKhz = mapOf(0 to 1000000, 3 to 1920000, 7 to 2227000),
        )
        val singleClusterReport = report(
            knownHandheldKey = "some_device",
            policies = listOf(policy(0, listOf(300000, 1000000, 2000000))),
        )

        val results = makeApplier().apply(rp6PresetsOnSingleCluster, singleClusterReport, "test")

        assertThat(results).hasSize(1)
        val rejected = results.single() as WriteResult.Rejected
        assertThat(rejected.message).contains("policy3")
        assertThat(rejected.message).contains("policy7")
    }

    @Test
    fun `odin3 preset is blocked when applied on rp6-topology device`() = runTest {
        // Odin 3 preset uses policy6 — RP6 only has policy0 / policy3 / policy7.
        val odin3Preset = Preset(
            id = "odin3_anti_throttle_small",
            name = "Odin3 Anti-Throttle",
            description = "Odin 3 only",
            verification = VerificationTier.COMMUNITY_TUNED,
            cpuPolicyMaxKhz = mapOf(0 to 2745600, 6 to 3072000),
            // No targetHandheldKeys — relies solely on the policy-existence gate.
        )
        val rp6Report = report(
            knownHandheldKey = "retroid_pocket6",
            policies = listOf(
                policy(0, listOf(307000, 1555000, 2016000)),
                policy(3, listOf(499000, 1920000, 2803000)),
                policy(7, listOf(595000, 2227000, 3187000)),
            ),
        )

        val results = makeApplier().apply(odin3Preset, rp6Report, "test")

        // policy6 is unknown on the RP6; the gate must fire.
        assertThat(results).hasSize(1)
        val rejected = results.single() as WriteResult.Rejected
        assertThat(rejected.message).contains("policy6")
    }

    @Test
    fun `preset with all valid policy IDs passes the policy-existence gate`() = runTest {
        val validPreset = Preset(
            id = "valid",
            name = "Valid",
            description = "correct policies",
            verification = VerificationTier.GENERIC_KNOWN_FAMILY,
            cpuPolicyMaxKhz = mapOf(0 to 1000000, 3 to 1500000),
        )
        val report = report(
            knownHandheldKey = "some_device",
            policies = listOf(
                policy(0, listOf(300000, 1000000, 2000000)),
                policy(3, listOf(400000, 1500000, 3000000)),
            ),
        )

        val results = makeApplier().apply(validPreset, report, "test")

        assertThat(results).isNotEmpty()
        assertThat(results.all { it is WriteResult.Success }).isTrue()
    }
}
