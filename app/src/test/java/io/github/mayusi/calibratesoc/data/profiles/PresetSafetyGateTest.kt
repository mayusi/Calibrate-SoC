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
import io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
import io.github.mayusi.calibratesoc.data.script.ScriptGenerateResult
import io.github.mayusi.calibratesoc.data.share.PresetShareCodec
import io.github.mayusi.calibratesoc.data.share.ShareDecodeResult
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Tests for [PresetSafetyGate] (the extracted gate object) AND the
 * production paths that were previously unguarded:
 *
 *   1. UserProfile round-trip: fromPreset(rp6Preset) → toPreset() still
 *      carries targetHandheldKeys so ProfileApplier.apply() rejects on
 *      the wrong device.  This is the test that would have caught the
 *      original bug.
 *   2. Share-code round-trip: encode RP6-targeted profile → decode →
 *      decoded profile/preset still carries the targeting → rejected on
 *      Odin 3.
 *   3. PresetSafetyGate.check() returns Rejected for (a) device mismatch,
 *      (b) foreign policyId; Ok for null targeting and matching device.
 *   4. Script generation refuses for a device-mismatched preset.
 *   5. Back-compat: old UserProfile JSON (no targetHandheldKeys) → null →
 *      not falsely blocked.
 */
class PresetSafetyGateTest {

    // ── Shared fixtures ───────────────────────────────────────────────────────

    private fun policy(id: Int, vararg freqsKhz: Int) = CpuPolicyProbe(
        policyId = id,
        onlineCores = listOf(id),
        availableFreqsKhz = freqsKhz.toList(),
        availableGovernors = listOf("schedutil", "walt"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = freqsKhz.last(),
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

    private fun report(
        knownHandheldKey: String?,
        policies: List<CpuPolicyProbe> = emptyList(),
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

    private val rp6Policies = listOf(
        policy(0, 307000, 1555000, 2016000),
        policy(3, 499000, 1920000, 2803000),
        policy(7, 595000, 2227000, 3187000),
    )

    private val odin3Policies = listOf(
        policy(0, 384000, 2745600, 3532800),
        policy(6, 1017600, 3072000, 4320000),
    )

    private val rp6Preset = Preset(
        id = "rp6_ps2_gc",
        name = "RP6 PS2 GC",
        description = "RP6 only",
        verification = VerificationTier.COMMUNITY_TUNED,
        cpuPolicyMaxKhz = mapOf(0 to 1555000, 3 to 1920000, 7 to 2227000),
        cpuPolicyMinKhz = mapOf(0 to 307000, 3 to 499000, 7 to 595000),
        targetHandheldKeys = listOf("retroid_pocket6"),
    )

    private val rp6Report = report("retroid_pocket6", rp6Policies)
    private val odin3Report = report("ayn_odin3", odin3Policies)

    private fun noopWriter(): TunableWriter {
        val writer = mockk<TunableWriter>()
        coEvery { writer.write(any(), any(), any(), any()) } answers {
            WriteResult.Success(id = firstArg(), previousValue = null, newValue = secondArg())
        }
        return writer
    }

    // ── 3. PresetSafetyGate.check() direct tests ─────────────────────────────

    @Test
    fun `gate returns Rejected for device-targeted preset on wrong device`() {
        val verdict = PresetSafetyGate.check(rp6Preset, odin3Report)

        assertThat(verdict).isInstanceOf(PresetSafetyGate.SafetyVerdict.Rejected::class.java)
        val reason = (verdict as PresetSafetyGate.SafetyVerdict.Rejected).reason
        assertThat(reason).contains("retroid_pocket6")
        assertThat(reason).contains("ayn_odin3")
    }

    @Test
    fun `gate returns Ok for device-targeted preset on matching device`() {
        val verdict = PresetSafetyGate.check(rp6Preset, rp6Report)

        assertThat(verdict).isInstanceOf(PresetSafetyGate.SafetyVerdict.Ok::class.java)
    }

    @Test
    fun `gate returns Ok for null targeting on any device`() {
        // Null targeting disables Gate 1. To isolate that, the preset must only
        // reference policies that DO exist on the device, so Gate 2 stays quiet —
        // otherwise the foreign-policy gate (correctly) fires and masks the intent
        // of this test. Odin 3 has policy0/policy6, so target only policy0.
        val universal = rp6Preset.copy(
            targetHandheldKeys = null,
            cpuPolicyMaxKhz = mapOf(0 to 2400000),
            cpuPolicyMinKhz = emptyMap(),
            cpuPolicyGovernor = emptyMap(),
        )
        val verdict = PresetSafetyGate.check(universal, odin3Report)

        assertThat(verdict).isInstanceOf(PresetSafetyGate.SafetyVerdict.Ok::class.java)
    }

    @Test
    fun `gate returns Rejected for foreign policyId`() {
        // rp6Preset has policy0/3/7; odin3 only has policy0/6
        val presetsWithForeignIds = rp6Preset.copy(
            targetHandheldKeys = null, // disable Gate 1 to isolate Gate 2
        )
        val verdict = PresetSafetyGate.check(presetsWithForeignIds, odin3Report)

        // policy3 and policy7 are foreign on Odin 3
        assertThat(verdict).isInstanceOf(PresetSafetyGate.SafetyVerdict.Rejected::class.java)
        val reason = (verdict as PresetSafetyGate.SafetyVerdict.Rejected).reason
        assertThat(reason).contains("policy3")
        assertThat(reason).contains("policy7")
    }

    // ── 1. Production path: UserProfile round-trip (the bug test) ─────────────

    /**
     * This is the test that would have caught the original bug:
     * fromPreset(rp6Preset) → toPreset() was dropping targetHandheldKeys,
     * so ProfileApplier.apply() never saw the restriction.
     *
     * With the fix, toPreset() propagates targetHandheldKeys and the
     * apply() call is correctly rejected on the Odin 3.
     */
    @Test
    fun `UserProfile fromPreset then toPreset preserves targetHandheldKeys and apply is rejected on wrong device`() = runTest {
        // Simulate the Profiles screen apply path:
        // 1. A preset is saved as a UserProfile.
        val profile = UserProfile.fromPreset(
            preset = rp6Preset,
            applyOnBoot = false,
            createdOnDeviceKey = "retroid_pocket6",
        )

        // 2. toPreset() is called before applying (ProfileApplier.apply expects a Preset).
        val roundTrippedPreset = profile.toPreset()

        // 3. The targeting must survive the round-trip.
        assertThat(roundTrippedPreset.targetHandheldKeys).isEqualTo(listOf("retroid_pocket6"))

        // 4. Applying that preset on an Odin 3 MUST be rejected.
        val applier = ProfileApplier(noopWriter())
        val results = applier.apply(roundTrippedPreset, odin3Report, "test")

        assertThat(results).hasSize(1)
        val rejected = results.single() as WriteResult.Rejected
        assertThat(rejected.message).contains("retroid_pocket6")
        assertThat(rejected.message).contains("ayn_odin3")
    }

    @Test
    fun `UserProfile fromPreset then toPreset on the matching device is accepted`() = runTest {
        val profile = UserProfile.fromPreset(
            preset = rp6Preset,
            applyOnBoot = false,
            createdOnDeviceKey = "retroid_pocket6",
        )
        val roundTrippedPreset = profile.toPreset()

        val applier = ProfileApplier(noopWriter())
        val results = applier.apply(roundTrippedPreset, rp6Report, "test")

        assertThat(results).isNotEmpty()
        assertThat(results.all { it is WriteResult.Success }).isTrue()
    }

    // ── 2. Share-code round-trip ──────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class JavaBase64Encoder : io.github.mayusi.calibratesoc.data.share.Base64Encoder {
        private val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        private val decoder = java.util.Base64.getUrlDecoder()
        override fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
        override fun decode(text: String): ByteArray = decoder.decode(text)
    }

    private val codec = PresetShareCodec(json, JavaBase64Encoder())

    @Test
    fun `share-code round-trip preserves targetHandheldKeys`() {
        // Encode an RP6-targeted profile into a share code.
        val profile = UserProfile.fromPreset(
            preset = rp6Preset,
            applyOnBoot = false,
            createdOnDeviceKey = "retroid_pocket6",
        )

        val code = codec.encode(profile)
        val decoded = codec.decode(code) as ShareDecodeResult.Success

        assertThat(decoded.profile.targetHandheldKeys).isEqualTo(listOf("retroid_pocket6"))
    }

    @Test
    fun `decoded share-code profile is rejected on wrong device`() = runTest {
        val profile = UserProfile.fromPreset(
            preset = rp6Preset,
            applyOnBoot = false,
            createdOnDeviceKey = "retroid_pocket6",
        )

        val code = codec.encode(profile)
        val decoded = (codec.decode(code) as ShareDecodeResult.Success).profile

        // The decoded profile → toPreset() → apply on Odin 3 must be rejected.
        val applier = ProfileApplier(noopWriter())
        val results = applier.apply(decoded.toPreset(), odin3Report, "test")

        assertThat(results).hasSize(1)
        val rejected = results.single() as WriteResult.Rejected
        assertThat(rejected.message).contains("retroid_pocket6")
        assertThat(rejected.message).contains("ayn_odin3")
    }

    @Test
    fun `old share code without targetHandheldKeys decodes to null and is not falsely blocked`() = runTest {
        // Craft a share code that predates targetHandheldKeys (no such field in JSON).
        val v2Json = """{
            "fmtVersion":2,
            "name":"Legacy",
            "description":"Old code",
            "cpuPolicyMaxKhz":{"0":1800000},
            "cpuPolicyMinKhz":{},
            "cpuPolicyGovernor":{},
            "extraSysfs":{}
        }""".trimIndent()
        val compressed = deflate(v2Json.toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        val code = "${PresetShareCodec.PREFIX}$b64"

        val decoded = codec.decode(code) as ShareDecodeResult.Success

        // targetHandheldKeys must default to null (back-compat).
        assertThat(decoded.profile.targetHandheldKeys).isNull()

        // Applying on any device must NOT be blocked (null = universal).
        val applier = ProfileApplier(noopWriter())
        val results = applier.apply(
            decoded.profile.toPreset(),
            report("ayn_odin3", listOf(policy(0, 300000, 1800000, 2000000))),
            "test",
        )
        // Must produce Success results (from the noop writer), not Rejected.
        assertThat(results.all { it is WriteResult.Success }).isTrue()
    }

    // ── 4. Script generation refuses for device-mismatched preset ─────────────

    @Test
    fun `generate returns Rejected for device-targeted preset on wrong device`() {
        val result = AynScriptGenerator().generate(rp6Preset, odin3Report, null)

        assertThat(result).isInstanceOf(ScriptGenerateResult.Rejected::class.java)
        val rejected = result as ScriptGenerateResult.Rejected
        assertThat(rejected.reason).contains("retroid_pocket6")
        assertThat(rejected.reason).contains("ayn_odin3")
    }

    @Test
    fun `generate returns Ok for device-targeted preset on matching device`() {
        val result = AynScriptGenerator().generate(rp6Preset, rp6Report, null)

        assertThat(result).isInstanceOf(ScriptGenerateResult.Ok::class.java)
    }

    // ── 5. Back-compat: old UserProfile without targetHandheldKeys ────────────

    @Test
    fun `old UserProfile without targetHandheldKeys applies without false block`() = runTest {
        // Simulate a profile that predates this field — no targetHandheldKeys set.
        val legacyProfile = UserProfile(
            id = "legacy",
            name = "Legacy Profile",
            description = "Old",
            cpuPolicyMaxKhz = mapOf(0 to 1800000),
            createdAtMs = 1_000_000L,
            // targetHandheldKeys intentionally omitted → null default
        )

        assertThat(legacyProfile.targetHandheldKeys).isNull()

        val preset = legacyProfile.toPreset()
        assertThat(preset.targetHandheldKeys).isNull()

        // Null targeting must not block apply on any device.
        val applier = ProfileApplier(noopWriter())
        val results = applier.apply(
            preset,
            report("ayn_odin3", listOf(policy(0, 300000, 1800000, 2000000))),
            "test",
        )
        assertThat(results.all { it is WriteResult.Success }).isTrue()
    }

    // ── Compression helper (same as PresetShareCodecTest) ─────────────────────

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(tmp)
            buf.write(tmp, 0, n)
        }
        deflater.end()
        return buf.toByteArray()
    }
}
