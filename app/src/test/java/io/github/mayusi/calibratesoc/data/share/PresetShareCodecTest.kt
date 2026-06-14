package io.github.mayusi.calibratesoc.data.share

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PresetShareCodec].
 *
 * Android isolation: the production codec uses [AndroidBase64Encoder] which
 * calls [android.util.Base64]. Here we inject [JavaBase64Encoder] — a
 * pure-JVM implementation backed by [java.util.Base64] — so these tests run
 * on the JVM without an Android runtime. The same deflate/inflate and
 * JSON-serialize/deserialize logic is exercised; only the Base64 provider
 * differs.
 */
class PresetShareCodecTest {

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

    private lateinit var codec: PresetShareCodec

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        codec = PresetShareCodec(json, JavaBase64Encoder())
    }

    // ─── Helper ────────────────────────────────────────────────────────────────

    private fun profile(
        name: String = "Balanced",
        description: String = "Nice tune",
        cpuPolicyMaxKhz: Map<Int, Int> = mapOf(0 to 1800000, 4 to 2400000),
        cpuPolicyMinKhz: Map<Int, Int> = emptyMap(),
        cpuPolicyGovernor: Map<Int, String> = mapOf(0 to "schedutil"),
        gpuMaxHz: Long? = null,
        gpuMinHz: Long? = null,
        gpuGovernor: String? = null,
    ) = UserProfile(
        id = "test_id",
        name = name,
        description = description,
        cpuPolicyMaxKhz = cpuPolicyMaxKhz,
        cpuPolicyMinKhz = cpuPolicyMinKhz,
        cpuPolicyGovernor = cpuPolicyGovernor,
        gpuMaxHz = gpuMaxHz,
        gpuMinHz = gpuMinHz,
        gpuGovernor = gpuGovernor,
        applyOnBoot = false,
        createdAtMs = 1_000_000L,
    )

    // ─── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `encode-decode round-trip preserves name`() {
        val code = codec.encode(profile(name = "Performance Plus"))
        val result = codec.decode(code) as ShareDecodeResult.Success
        assertThat(result.profile.name).isEqualTo("Performance Plus")
    }

    @Test
    fun `encode-decode round-trip preserves description`() {
        val code = codec.encode(profile(description = "High clocks for gaming"))
        val result = codec.decode(code) as ShareDecodeResult.Success
        assertThat(result.profile.description).isEqualTo("High clocks for gaming")
    }

    @Test
    fun `encode-decode round-trip preserves cpuPolicyMaxKhz`() {
        val caps = mapOf(0 to 1800000, 4 to 2400000, 6 to 3000000)
        val code = codec.encode(profile(cpuPolicyMaxKhz = caps))
        val result = codec.decode(code) as ShareDecodeResult.Success
        assertThat(result.profile.cpuPolicyMaxKhz).isEqualTo(caps)
    }

    @Test
    fun `encode-decode round-trip preserves cpuPolicyMinKhz`() {
        val mins = mapOf(0 to 300000, 4 to 500000)
        val code = codec.encode(profile(cpuPolicyMinKhz = mins))
        val result = codec.decode(code) as ShareDecodeResult.Success
        assertThat(result.profile.cpuPolicyMinKhz).isEqualTo(mins)
    }

    @Test
    fun `encode-decode round-trip preserves cpuPolicyGovernor`() {
        val govs = mapOf(0 to "schedutil", 4 to "performance")
        val code = codec.encode(profile(cpuPolicyGovernor = govs))
        val result = codec.decode(code) as ShareDecodeResult.Success
        assertThat(result.profile.cpuPolicyGovernor).isEqualTo(govs)
    }

    @Test
    fun `encode-decode round-trip preserves GPU fields`() {
        val code = codec.encode(
            profile(gpuMaxHz = 750_000_000L, gpuMinHz = 300_000_000L, gpuGovernor = "simple_ondemand"),
        )
        val result = codec.decode(code) as ShareDecodeResult.Success
        assertThat(result.profile.gpuMaxHz).isEqualTo(750_000_000L)
        assertThat(result.profile.gpuMinHz).isEqualTo(300_000_000L)
        assertThat(result.profile.gpuGovernor).isEqualTo("simple_ondemand")
    }

    @Test
    fun `decoded profile never auto-applies on boot`() {
        val code = codec.encode(profile())
        val result = codec.decode(code) as ShareDecodeResult.Success
        assertThat(result.profile.applyOnBoot).isFalse()
    }

    @Test
    fun `decoded profile has a fresh id different from the source`() {
        val source = profile()
        val code = codec.encode(source)
        val result = codec.decode(code) as ShareDecodeResult.Success
        // Imported profiles get a fresh "shared_…" id, not the original "test_id".
        assertThat(result.profile.id).isNotEqualTo(source.id)
        assertThat(result.profile.id).startsWith("shared_")
    }

    @Test
    fun `encoded code starts with CSOC1 prefix`() {
        val code = codec.encode(profile())
        assertThat(code).startsWith(PresetShareCodec.PREFIX)
    }

    @Test
    fun `encoded code is single line with no whitespace`() {
        val code = codec.encode(profile())
        assertThat(code).doesNotContain(" ")
        assertThat(code).doesNotContain("\n")
        assertThat(code).doesNotContain("\r")
    }

    // ─── Error: unknown / unsupported prefix ───────────────────────────────────

    @Test
    fun `unknown prefix returns Error`() {
        val result = codec.decode("TOTALLY_NOT_A_CODE:abc123")
        assertThat(result).isInstanceOf(ShareDecodeResult.Error::class.java)
    }

    @Test
    fun `empty string returns Error`() {
        val result = codec.decode("")
        assertThat(result).isInstanceOf(ShareDecodeResult.Error::class.java)
    }

    @Test
    fun `blank string returns Error`() {
        val result = codec.decode("   ")
        assertThat(result).isInstanceOf(ShareDecodeResult.Error::class.java)
    }

    @Test
    fun `CSOC1 prefix with empty payload returns Error`() {
        val result = codec.decode("CSOC1:")
        assertThat(result).isInstanceOf(ShareDecodeResult.Error::class.java)
    }

    // ─── Error: malformed base64 / bad JSON ────────────────────────────────────

    @Test
    fun `malformed base64 after prefix returns Error and does not crash`() {
        val result = codec.decode("CSOC1:!!not-valid-base64!!")
        assertThat(result).isInstanceOf(ShareDecodeResult.Error::class.java)
        val error = result as ShareDecodeResult.Error
        assertThat(error.reason).isNotEmpty()
    }

    @Test
    fun `valid base64 but bad compressed payload returns Error and does not crash`() {
        // Valid base64 that is NOT a deflated JSON — inflation will fail.
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("not-deflated".toByteArray())
        val result = codec.decode("CSOC1:$b64")
        assertThat(result).isInstanceOf(ShareDecodeResult.Error::class.java)
    }

    @Test
    fun `garbage after valid prefix never throws`() {
        repeat(10) {
            val garbage = "CSOC1:" + (0..20).map { ('a'..'z').random() }.joinToString("")
            val result = runCatching { codec.decode(garbage) }
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isInstanceOf(ShareDecodeResult.Error::class.java)
        }
    }

    // ─── Error: future format version ─────────────────────────────────────────

    @Test
    fun `format version higher than current returns Error`() {
        // Manually craft a payload with fmtVersion = 999 to simulate a future share code.
        val futurePayload = """{"fmtVersion":999,"name":"Test","description":"","cpuPolicyMaxKhz":{},"cpuPolicyMinKhz":{},"cpuPolicyGovernor":{}}"""
        val compressed = deflate(futurePayload.toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        val fakeCode = "CSOC1:$b64"

        val result = codec.decode(fakeCode)
        assertThat(result).isInstanceOf(ShareDecodeResult.Error::class.java)
        val error = result as ShareDecodeResult.Error
        assertThat(error.reason).contains("999")
    }

    // ─── Validation: validateProfile rules (integration) ──────────────────────
    //
    // The codec itself does NOT call validateProfile — that's the ViewModel's
    // responsibility before save (reusing BackupManager). Here we test that a
    // preset with a shell-metacharacter name CAN still round-trip through the
    // codec (decode succeeds), so validation is correctly deferred to the caller.
    // This confirms the codec doesn't accidentally swallow bad data silently OR
    // accidentally block valid input.

    @Test
    fun `codec decode returns Success for a preset with injection-like name allowing caller to validate`() {
        // We intentionally encode an invalid profile to confirm the codec itself
        // doesn't block it — the ViewModel.confirmImport → backupManager.validateProfile
        // layer is the security gate.
        val evil = profile(name = "nice'; rm -rf /")
        val code = codec.encode(evil)
        val result = codec.decode(code)
        // The codec succeeds — it is up to the ViewModel to validate before saving.
        assertThat(result).isInstanceOf(ShareDecodeResult.Success::class.java)
    }

    // ─── Deflate helper (mirrors codec internals, needed for test setup) ───────

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
