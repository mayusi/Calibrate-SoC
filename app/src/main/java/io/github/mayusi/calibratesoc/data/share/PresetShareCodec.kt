package io.github.mayusi.calibratesoc.data.share

import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encodes/decodes a [UserProfile] as a short, copy-paste-safe "preset share code"
 * for posting on Reddit, Discord, etc.
 *
 * Format:  `CSOC1:<url-safe-base64-of-deflate-compressed-json>`
 *
 *  - `CSOC1` is the versioned prefix (CalibrateSOC format v1). Future breaking
 *    changes bump the digit so old codes aren't silently misinterpreted.
 *  - The payload is the JSON of [ShareablePreset] compressed with Deflate (via
 *    [java.util.zip.Deflater] / [java.util.zip.Inflater]) then Base64-encoded
 *    with NO_WRAP + URL_SAFE flags so the result is a single line safe for
 *    pasting anywhere.
 *
 * **Android / JVM isolation:** Base64 is injected via [Base64Encoder] so the
 * same codec runs in pure-JVM unit tests (using [java.util.Base64]) without
 * needing the Android runtime.
 */
class PresetShareCodec(
    private val json: Json,
    private val base64: Base64Encoder,
) {

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Encode a [UserProfile] into a shareable one-line code string.
     * Returns the code on success, or throws — callers that want a
     * no-throw version should wrap with [runCatching].
     */
    fun encode(profile: UserProfile): String {
        val shareable = ShareablePreset.fromProfile(profile)
        val jsonText = json.encodeToString(shareable)
        val compressed = deflate(jsonText.toByteArray(Charsets.UTF_8))
        val b64 = base64.encode(compressed)
        return "$PREFIX$b64"
    }

    /**
     * Decode a share code string into a [ShareDecodeResult].
     * Never throws — all errors produce [ShareDecodeResult.Error].
     */
    fun decode(code: String): ShareDecodeResult {
        val trimmed = code.trim()

        // Version/prefix check — reject anything without our marker.
        if (!trimmed.startsWith(PREFIX)) {
            val knownPrefixes = listOf("CSOC") // extend as versions land
            val looksLikeOurFormat = knownPrefixes.any { trimmed.startsWith(it) }
            return if (looksLikeOurFormat) {
                ShareDecodeResult.Error("Unsupported share code version. Update the app to import this preset.")
            } else {
                ShareDecodeResult.Error("That doesn't look like a Calibrate SoC preset code. Codes start with \"$PREFIX\".")
            }
        }

        val b64Part = trimmed.removePrefix(PREFIX)
        if (b64Part.isBlank()) {
            return ShareDecodeResult.Error("Preset code is empty after the version prefix.")
        }

        // FIX 2 — Base64 size cap: reject before allocating memory for decoding.
        // A real preset encodes to well under 1 KiB; 64 KiB chars is extremely generous.
        if (b64Part.length > MAX_BASE64_LENGTH) {
            return ShareDecodeResult.Error("Preset code is too large.")
        }

        // Base64 decode
        val compressed = runCatching { base64.decode(b64Part) }.getOrElse { e ->
            return ShareDecodeResult.Error("Preset code contains invalid characters: ${e.message}")
        }

        // Inflate
        val jsonBytes = runCatching { inflate(compressed) }.getOrElse { e ->
            return ShareDecodeResult.Error("Preset code is corrupted (decompression failed): ${e.message}")
        }

        val jsonText = runCatching { jsonBytes.toString(Charsets.UTF_8) }.getOrElse { e ->
            return ShareDecodeResult.Error("Preset code payload is not valid UTF-8: ${e.message}")
        }

        // JSON decode
        val shareable = runCatching { json.decodeFromString<ShareablePreset>(jsonText) }.getOrElse { e ->
            return ShareDecodeResult.Error("Preset code contains invalid data: ${e.message}")
        }

        // Format version guard — must know this payload version
        if (shareable.fmtVersion > CURRENT_FMT_VERSION) {
            return ShareDecodeResult.Error(
                "Preset was created with a newer app (format v${shareable.fmtVersion}, " +
                    "this app supports up to v$CURRENT_FMT_VERSION). Update the app to import it.",
            )
        }

        // Convert to UserProfile, marking as SHARED_UNVERIFIED
        val profile = shareable.toUserProfile()
        return ShareDecodeResult.Success(profile)
    }

    // ─── Compression helpers ───────────────────────────────────────────────────

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

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(input)
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(1024)
        var totalDecompressed = 0
        while (!inflater.finished()) {
            val n = inflater.inflate(tmp)
            if (n == 0 && !inflater.finished()) {
                throw java.util.zip.DataFormatException("Unexpected end of deflate stream")
            }
            // FIX 1 — Decompression bomb guard: cap total decompressed output.
            // A real preset is well under 256 KiB; exceeding this cap indicates
            // a crafted hostile payload attempting to exhaust heap.
            totalDecompressed += n
            if (totalDecompressed > MAX_INFLATED_BYTES) {
                inflater.end()
                throw java.util.zip.DataFormatException("Decompressed payload exceeds size limit")
            }
            buf.write(tmp, 0, n)
        }
        inflater.end()
        return buf.toByteArray()
    }

    companion object {
        /** Versioned prefix. Bump the digit on any breaking payload change. */
        const val PREFIX = "CSOC1:"

        /**
         * Current payload format version embedded in [ShareablePreset.fmtVersion].
         * History:
         *   v1 — initial release (no extraSysfs)
         *   v2 — adds extraSysfs (Map<String,String>); v1 codes still decode fine
         *         because kotlinx.serialization uses the field's default (emptyMap())
         *         when the key is absent and the Json config has ignoreUnknownKeys=true.
         */
        const val CURRENT_FMT_VERSION = 2

        /**
         * Maximum allowed length (in chars) of the Base64 portion of a share code.
         * Rejects oversized input before allocating memory for decoding.
         * 64 KiB characters is far larger than any real preset code.
         */
        const val MAX_BASE64_LENGTH = 64 * 1024

        /**
         * Maximum number of bytes the decompressed payload may occupy.
         * Prevents a crafted "decompression bomb" from exhausting the heap.
         * 256 KiB is far larger than any real preset JSON.
         */
        const val MAX_INFLATED_BYTES = 256 * 1024
    }
}

// ─── Sealed result ─────────────────────────────────────────────────────────────

sealed class ShareDecodeResult {
    data class Success(val profile: UserProfile) : ShareDecodeResult()
    data class Error(val reason: String) : ShareDecodeResult()
}

// ─── Wire format ───────────────────────────────────────────────────────────────

/**
 * The JSON shape that travels in the share code. Only tune-relevant fields
 * travel — device-specific metadata (id, applyOnBoot, createdAtMs) are
 * regenerated on import. The [fmtVersion] guards against future breaking
 * field changes.
 *
 * We strip [UserProfile.id], [UserProfile.applyOnBoot], and
 * [UserProfile.createdAtMs] because they are device-specific and meaningless
 * on another device. They are regenerated freshly on [toUserProfile].
 */
@Serializable
data class ShareablePreset(
    /** Payload format version — checked by [PresetShareCodec.decode] before
     *  converting. Must match [PresetShareCodec.CURRENT_FMT_VERSION] or lower. */
    val fmtVersion: Int = 1,
    val name: String,
    val description: String,
    val cpuPolicyMaxKhz: Map<Int, Int> = emptyMap(),
    val cpuPolicyMinKhz: Map<Int, Int> = emptyMap(),
    /** Map key serialized as String because JSON object keys must be strings;
     *  the Int-keyed map in UserProfile round-trips via explicit conversion. */
    val cpuPolicyGovernor: Map<Int, String> = emptyMap(),
    val gpuMaxHz: Long? = null,
    val gpuMinHz: Long? = null,
    val gpuGovernor: String? = null,
    /**
     * Generic sysfs/procfs knobs beyond the first-class fields above.
     * Added in format v2; defaults to emptyMap() so v1 codes (which omit
     * this key entirely) still deserialize correctly — kotlinx.serialization
     * uses the default when the key is absent (requires ignoreUnknownKeys=true
     * on the Json config, which both production and test instances set).
     */
    val extraSysfs: Map<String, String> = emptyMap(),
) {
    fun toUserProfile(): UserProfile = UserProfile(
        // New unique ID on every import so two imports of the same code don't collide.
        id = "shared_${System.currentTimeMillis()}",
        name = name,
        description = description,
        cpuPolicyMaxKhz = cpuPolicyMaxKhz,
        cpuPolicyMinKhz = cpuPolicyMinKhz,
        cpuPolicyGovernor = cpuPolicyGovernor,
        gpuMaxHz = gpuMaxHz,
        gpuMinHz = gpuMinHz,
        gpuGovernor = gpuGovernor,
        // SECURITY: extraSysfs from an untrusted share code is passed through as-is
        // here. It is validated at apply-time via TunableMetadata.validateCustomSysfsPath
        // (per-path) and TunableMetadata.forId().validate (per-value) inside
        // ProfileApplier.apply(). The resulting UserProfile is also run through
        // BackupManager.validateProfile() in ProfilesViewModel.confirmImport() before
        // being persisted. Shared profiles are never auto-applied (applyOnBoot = false).
        extraSysfs = extraSysfs,
        // Shared profiles do NOT auto-apply — user applies manually.
        applyOnBoot = false,
        createdAtMs = System.currentTimeMillis(),
    )

    companion object {
        fun fromProfile(profile: UserProfile) = ShareablePreset(
            fmtVersion = PresetShareCodec.CURRENT_FMT_VERSION,
            name = profile.name,
            description = profile.description,
            cpuPolicyMaxKhz = profile.cpuPolicyMaxKhz,
            cpuPolicyMinKhz = profile.cpuPolicyMinKhz,
            cpuPolicyGovernor = profile.cpuPolicyGovernor,
            gpuMaxHz = profile.gpuMaxHz,
            gpuMinHz = profile.gpuMinHz,
            gpuGovernor = profile.gpuGovernor,
            extraSysfs = profile.extraSysfs,
        )
    }
}
