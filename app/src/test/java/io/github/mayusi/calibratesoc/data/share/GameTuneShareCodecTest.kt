package io.github.mayusi.calibratesoc.data.share

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GameTuneShareCodec].
 *
 * Android isolation: the production codec uses [AndroidBase64Encoder] which
 * calls [android.util.Base64]. Here we inject [JavaBase64Encoder] — a
 * pure-JVM implementation backed by [java.util.Base64] — so these tests run
 * on the JVM without an Android runtime. The same deflate/inflate and
 * JSON-serialize/deserialize logic is exercised; only the Base64 provider
 * differs.
 */
class GameTuneShareCodecTest {

    /**
     * Pure-JVM Base64 encoder for tests. Uses [java.util.Base64] URL_SAFE
     * encoding with NO_PADDING so the output is equivalent to Android's
     * NO_WRAP | URL_SAFE flags (same alphabet, no newlines, no padding).
     */
    private class JavaBase64Encoder : Base64Encoder {
        private val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        private val decoder = java.util.Base64.getUrlDecoder()
        override fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
        override fun decode(text: String): ByteArray = decoder.decode(text)
    }

    private lateinit var codec: GameTuneShareCodec

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        codec = GameTuneShareCodec(json, JavaBase64Encoder())
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun minimalTune(
        packageName: String = "com.example.game",
        gameDisplayName: String = "My Game",
        name: String = "Balanced",
        description: String = "A nice tune",
        autoTdpGoal: GoalProfile? = GoalProfile.BALANCED_SMART,
        refreshRateHz: Float? = 90f,
        fanMode: Int? = 4,
        gameBoostOnLaunch: Boolean = false,
        cpuPolicyMaxKhz: Map<Int, Int> = mapOf(0 to 1800000, 4 to 2400000),
        cpuPolicyMinKhz: Map<Int, Int> = emptyMap(),
        cpuPolicyGovernor: Map<Int, String> = mapOf(0 to "schedutil"),
        gpuMaxHz: Long? = null,
        gpuMinHz: Long? = null,
        gpuGovernor: String? = null,
        extraSysfs: Map<String, String> = emptyMap(),
        targetHandheldKeys: List<String>? = null,
    ) = ShareableGameTune(
        fmtVersion = GameTuneShareCodec.CURRENT_GAME_TUNE_FMT_VERSION,
        name = name,
        description = description,
        cpuPolicyMaxKhz = cpuPolicyMaxKhz,
        cpuPolicyMinKhz = cpuPolicyMinKhz,
        cpuPolicyGovernor = cpuPolicyGovernor,
        gpuMaxHz = gpuMaxHz,
        gpuMinHz = gpuMinHz,
        gpuGovernor = gpuGovernor,
        extraSysfs = extraSysfs,
        targetHandheldKeys = targetHandheldKeys,
        autoTdpGoal = autoTdpGoal,
        refreshRateHz = refreshRateHz,
        fanMode = fanMode,
        gameBoostOnLaunch = gameBoostOnLaunch,
        packageName = packageName,
        gameDisplayName = gameDisplayName,
    )

    private fun minimalBundle() = PerAppBundle(
        autoTdpGoal = GoalProfile.BALANCED_SMART,
        gameBoostOnLaunch = false,
    )

    private fun minimalProfile() = UserProfile(
        id = "test",
        name = "Balanced",
        description = "",
        cpuPolicyMaxKhz = mapOf(0 to 1800000),
        cpuPolicyMinKhz = emptyMap(),
        cpuPolicyGovernor = mapOf(0 to "schedutil"),
        applyOnBoot = false,
        createdAtMs = 0L,
    )

    /** Compress bytes with Deflate (mirrors codec internals). */
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

    /** Build a valid CSOC2 code from a raw JSON string. */
    private fun buildCsoc2Code(jsonText: String): String {
        val compressed = deflate(jsonText.toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return "$GAME_TUNE_PREFIX$b64"
    }

    // ─── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `encode-decode round-trip preserves packageName`() {
        val code = codec.encode("com.specific.package", "My Game", minimalBundle(), minimalProfile())
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.packageName).isEqualTo("com.specific.package")
    }

    @Test
    fun `encode-decode round-trip preserves gameDisplayName`() {
        val code = codec.encode("com.example.game", "Devil May Cry 3", minimalBundle(), minimalProfile())
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.gameDisplayName).isEqualTo("Devil May Cry 3")
    }

    @Test
    fun `encode-decode round-trip preserves name and description`() {
        val profile = minimalProfile().copy(name = "Performance Plus", description = "High clocks")
        val code = codec.encode("com.example.game", "My Game", minimalBundle(), profile)
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.name).isEqualTo("Performance Plus")
        assertThat(result.tune.description).isEqualTo("High clocks")
    }

    @Test
    fun `encode-decode round-trip preserves cpuPolicyMaxKhz`() {
        val caps = mapOf(0 to 1800000, 4 to 2400000, 6 to 3000000)
        val profile = minimalProfile().copy(cpuPolicyMaxKhz = caps)
        val code = codec.encode("com.example.game", "My Game", minimalBundle(), profile)
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.cpuPolicyMaxKhz).isEqualTo(caps)
    }

    @Test
    fun `encode-decode round-trip preserves autoTdpGoal BALANCED_SMART`() {
        val bundle = minimalBundle().copy(autoTdpGoal = GoalProfile.BALANCED_SMART)
        val code = codec.encode("com.example.game", "My Game", bundle, minimalProfile())
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.autoTdpGoal).isEqualTo(GoalProfile.BALANCED_SMART)
    }

    @Test
    fun `encode-decode round-trip preserves refreshRateHz`() {
        val bundle = minimalBundle().copy(refreshRateHz = 120f)
        val code = codec.encode("com.example.game", "My Game", bundle, minimalProfile())
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.refreshRateHz).isEqualTo(120f)
    }

    @Test
    fun `encode-decode round-trip preserves fanMode`() {
        val bundle = minimalBundle().copy(fanMode = 5)
        val code = codec.encode("com.example.game", "My Game", bundle, minimalProfile())
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.fanMode).isEqualTo(5)
    }

    @Test
    fun `encode-decode round-trip preserves gameBoostOnLaunch true`() {
        val bundle = minimalBundle().copy(gameBoostOnLaunch = true)
        val code = codec.encode("com.example.game", "My Game", bundle, minimalProfile())
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.gameBoostOnLaunch).isTrue()
    }

    @Test
    fun `encode-decode round-trip preserves extraSysfs`() {
        val sysfsMap = mapOf(
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor" to "schedutil",
            "/sys/class/thermal/thermal_zone0/mode" to "enabled",
        )
        val profile = minimalProfile().copy(extraSysfs = sysfsMap)
        val code = codec.encode("com.example.game", "My Game", minimalBundle(), profile)
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.extraSysfs).isEqualTo(sysfsMap)
    }

    @Test
    fun `encode-decode round-trip preserves targetHandheldKeys`() {
        val keys = listOf("ayn_odin3", "retroid_pocket6")
        val profile = minimalProfile().copy(targetHandheldKeys = keys)
        val code = codec.encode("com.example.game", "My Game", minimalBundle(), profile)
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.targetHandheldKeys).isEqualTo(keys)
    }

    @Test
    fun `encode-decode round-trip with null profile uses gameDisplayName as name`() {
        val code = codec.encode("com.example.game", "Bayonetta", minimalBundle(), null)
        val result = codec.decode(code) as GameTuneDecodeResult.Success
        assertThat(result.tune.name).isEqualTo("Bayonetta")
    }

    @Test
    fun `encoded code starts with CSOC2 prefix`() {
        val code = codec.encode("com.example.game", "My Game", minimalBundle(), minimalProfile())
        assertThat(code).startsWith(GAME_TUNE_PREFIX)
    }

    @Test
    fun `encoded code is single line with no whitespace`() {
        val code = codec.encode("com.example.game", "My Game", minimalBundle(), minimalProfile())
        assertThat(code).doesNotContain(" ")
        assertThat(code).doesNotContain("\n")
        assertThat(code).doesNotContain("\r")
    }

    // ─── Backward-compat: CSOC1 codes give specific guidance ──────────────────

    @Test
    fun `CSOC1 code returns Error with guidance to use Profiles screen`() {
        // Craft a valid CSOC1 payload (same format PresetShareCodec produces).
        val csoc1Json = """{"fmtVersion":1,"name":"Legacy","description":"Old preset","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{}}"""
        val compressed = deflate(csoc1Json.toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        val csoc1Code = "CSOC1:$b64"

        val result = codec.decode(csoc1Code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
        val error = result as GameTuneDecodeResult.Error
        // The error must guide the user toward the Profiles screen or mention CSOC1 / clock-only.
        val lower = error.reason.lowercase()
        val hasGuidance = lower.contains("csoc1") || lower.contains("profiles screen") || lower.contains("clock-only")
        assertThat(hasGuidance).isTrue()
    }

    // ─── Error cases ───────────────────────────────────────────────────────────

    @Test
    fun `blank code returns Error`() {
        val result = codec.decode("   ")
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `empty code returns Error`() {
        val result = codec.decode("")
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `unknown prefix returns Error`() {
        val result = codec.decode("TOTALLY_NOT_A_CODE:abc123")
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `CSOC2 prefix with empty payload returns Error`() {
        val result = codec.decode("CSOC2:")
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `malformed base64 returns Error`() {
        val result = codec.decode("CSOC2:!!not-valid-base64!!")
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
        val error = result as GameTuneDecodeResult.Error
        assertThat(error.reason).isNotEmpty()
    }

    @Test
    fun `base64 payload over MAX_BASE64_LENGTH returns Error`() {
        // Build a string just above the cap — content doesn't matter, only its size.
        val oversized = "A".repeat(GameTuneShareCodec.MAX_BASE64_LENGTH + 1)
        val code = "$GAME_TUNE_PREFIX$oversized"
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
        val error = result as GameTuneDecodeResult.Error
        assertThat(error.reason).isNotEmpty()
    }

    @Test
    fun `inflated payload over MAX_INFLATED_BYTES returns Error`() {
        // Build repetitive JSON that compresses small but inflates beyond 256 KiB.
        val bigJson = """{"fmtVersion":1,"name":"Bomb","description":"${"x".repeat(300_000)}","packageName":"com.bomb","gameDisplayName":"Bomb","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{}}"""
        val code = buildCsoc2Code(bigJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
        val error = result as GameTuneDecodeResult.Error
        assertThat(error.reason).contains("decompression failed")
    }

    @Test
    fun `future fmtVersion returns Error with version number in reason`() {
        val futureJson = """{"fmtVersion":999,"name":"Future","description":"","packageName":"com.example.game","gameDisplayName":"Game","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{}}"""
        val code = buildCsoc2Code(futureJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
        val error = result as GameTuneDecodeResult.Error
        assertThat(error.reason).contains("999")
    }

    @Test
    fun `garbage after valid prefix never throws`() {
        repeat(10) {
            val garbage = "$GAME_TUNE_PREFIX" + (0..20).map { ('a'..'z').random() }.joinToString("")
            val result = runCatching { codec.decode(garbage) }
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isInstanceOf(GameTuneDecodeResult.Error::class.java)
        }
    }

    // ─── Security: malicious import REFUSED at decode boundary ─────────────────

    @Test
    fun `dangerous sysfs path is refused at decode`() {
        // Path traversal: /sys/kernel/debug/../../../proc/sysrq-trigger
        val maliciousJson = """{"fmtVersion":1,"name":"Evil","description":"","packageName":"com.example.game","gameDisplayName":"Game","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{},"extraSysfs":{"/sys/kernel/debug/../../../proc/sysrq-trigger":"b"}}"""
        val code = buildCsoc2Code(maliciousJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `shell metachar in extraSysfs value is refused at decode`() {
        // Valid path, but value contains shell injection: "schedutil; rm -rf /"
        val maliciousJson = """{"fmtVersion":1,"name":"Evil","description":"","packageName":"com.example.game","gameDisplayName":"Game","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{},"extraSysfs":{"/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor":"schedutil; rm -rf /"}}"""
        val code = buildCsoc2Code(maliciousJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `bad packageName is refused at decode`() {
        val maliciousJson = """{"fmtVersion":1,"name":"Evil","description":"","packageName":"com.evil; rm -rf /","gameDisplayName":"Game","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{}}"""
        val code = buildCsoc2Code(maliciousJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `refreshRateHz out of range is refused at decode`() {
        val badJson = """{"fmtVersion":1,"name":"Bad","description":"","packageName":"com.example.game","gameDisplayName":"Game","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{},"refreshRateHz":9999.0}"""
        val code = buildCsoc2Code(badJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `fanMode out of range is refused at decode`() {
        val badJson = """{"fmtVersion":1,"name":"Bad","description":"","packageName":"com.example.game","gameDisplayName":"Game","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{},"fanMode":99}"""
        val code = buildCsoc2Code(badJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
    }

    @Test
    fun `zip-bomb caps hold`() {
        // Same as inflated payload test — verify the inflate cap fires before OOM.
        val bigJson = """{"fmtVersion":1,"name":"Bomb","description":"${"x".repeat(300_000)}","packageName":"com.bomb","gameDisplayName":"Bomb","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{}}"""
        val code = buildCsoc2Code(bigJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Error::class.java)
        val error = result as GameTuneDecodeResult.Error
        assertThat(error.reason).contains("decompression failed")
    }

    // ─── Backward-compat with fmtVersion=1 (explicit) ─────────────────────────

    @Test
    fun `format v1 code without optional fields decodes successfully`() {
        // Only required/non-nullable fields; all optional fields omitted.
        val minJson = """{"fmtVersion":1,"name":"Minimal","description":"","packageName":"com.example.game","gameDisplayName":"My Game"}"""
        val code = buildCsoc2Code(minJson)
        val result = codec.decode(code)
        assertThat(result).isInstanceOf(GameTuneDecodeResult.Success::class.java)
        val success = result as GameTuneDecodeResult.Success
        assertThat(success.tune.name).isEqualTo("Minimal")
        assertThat(success.tune.packageName).isEqualTo("com.example.game")
    }

    // ─── validateImport tests (call directly on codec) ─────────────────────────

    @Test
    fun `validateImport returns null for a valid tune`() {
        val tune = minimalTune()
        assertThat(codec.validateImport(tune)).isNull()
    }

    @Test
    fun `validateImport rejects blank packageName`() {
        val tune = minimalTune(packageName = "   ")
        assertThat(codec.validateImport(tune)).isNotNull()
    }

    @Test
    fun `validateImport rejects packageName with shell chars`() {
        val tune = minimalTune(packageName = "com.evil; rm -rf /")
        assertThat(codec.validateImport(tune)).isNotNull()
    }

    @Test
    fun `validateImport rejects blank name`() {
        val tune = minimalTune(name = "   ")
        assertThat(codec.validateImport(tune)).isNotNull()
    }

    @Test
    fun `validateImport rejects refreshRateHz 0f`() {
        val tune = minimalTune(refreshRateHz = 0f)
        assertThat(codec.validateImport(tune)).isNotNull()
    }

    @Test
    fun `validateImport rejects fanMode 11`() {
        val tune = minimalTune(fanMode = 11)
        assertThat(codec.validateImport(tune)).isNotNull()
    }

    @Test
    fun `validateImport rejects dangerous sysfs path in extraSysfs`() {
        val tune = minimalTune(
            extraSysfs = mapOf("/sys/kernel/debug/../../../proc/sysrq-trigger" to "b"),
        )
        assertThat(codec.validateImport(tune)).isNotNull()
    }

    @Test
    fun `validateImport rejects shell metachar in extraSysfs value`() {
        val tune = minimalTune(
            extraSysfs = mapOf(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor" to "schedutil; rm -rf /",
            ),
        )
        assertThat(codec.validateImport(tune)).isNotNull()
    }

    @Test
    fun `validateImport rejects targetHandheldKeys with uppercase`() {
        val tune = minimalTune(targetHandheldKeys = listOf("AYN_ODIN3"))
        assertThat(codec.validateImport(tune)).isNotNull()
    }
}
