package io.github.mayusi.calibratesoc.data.share

import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.remote.ValidationRegexes
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─── Prefix constant ──────────────────────────────────────────────────────────

/** Versioned prefix for CSOC2 game-tune share codes. */
const val GAME_TUNE_PREFIX = "CSOC2:"

// ─── Wire format ──────────────────────────────────────────────────────────────

/**
 * The JSON shape that travels in a CSOC2 game-tune share code.
 *
 * Carries a FULL game tune: the clock-profile fields from [ShareablePreset]
 * (CPU/GPU clocks, extraSysfs, targetHandheldKeys) PLUS per-app bundle fields
 * ([autoTdpGoal], [refreshRateHz], [fanMode], [gameBoostOnLaunch]) PLUS the
 * target game identity ([packageName], [gameDisplayName]).
 *
 * The [fmtVersion] is CSOC2-specific and independent of CSOC1's
 * [PresetShareCodec.CURRENT_FMT_VERSION]. It is checked by
 * [GameTuneShareCodec.decode] before the result is returned.
 *
 * Device-specific identifiers (bundle [PerAppBundle.profileId],
 * [PerAppBundle.autoCreated]) are intentionally omitted — the profile that
 * backs the clock fields travels as inline data, not by reference, and the
 * auto-created flag is always false for hand-exported tunes.
 */
@Serializable
data class ShareableGameTune(

    // ── Metadata ──────────────────────────────────────────────────────────────

    /**
     * CSOC2 payload format version. Checked by [GameTuneShareCodec.decode]
     * against [GameTuneShareCodec.CURRENT_GAME_TUNE_FMT_VERSION].
     * History:
     *   v1 — initial release (all fields listed below).
     */
    val fmtVersion: Int = 1,

    /** Human-readable preset/profile name (shown in the import sheet). */
    val name: String,

    /** Optional description of the tune (shown below the name). */
    val description: String,

    // ── Clock-profile fields (mirrors ShareablePreset) ────────────────────────

    /** CPU policy maximum frequency (kHz), keyed by cpufreq policy index. */
    val cpuPolicyMaxKhz: Map<Int, Int> = emptyMap(),

    /** CPU policy minimum frequency (kHz), keyed by cpufreq policy index. */
    val cpuPolicyMinKhz: Map<Int, Int> = emptyMap(),

    /**
     * CPU policy governor name, keyed by cpufreq policy index.
     * JSON object keys are Strings; values must pass the governor safety check.
     */
    val cpuPolicyGovernor: Map<Int, String> = emptyMap(),

    /** GPU maximum frequency (Hz). Null = do not constrain. */
    val gpuMaxHz: Long? = null,

    /** GPU minimum frequency (Hz). Null = do not constrain. */
    val gpuMinHz: Long? = null,

    /** GPU devfreq governor name. Null = do not change. */
    val gpuGovernor: String? = null,

    /**
     * Generic sysfs/procfs knobs beyond the first-class clock fields.
     * Each key is validated with [TunableMetadata.validateCustomSysfsPath];
     * each value is checked against [ValidationRegexes.SHELL_META] on import.
     */
    val extraSysfs: Map<String, String> = emptyMap(),

    /**
     * Device-targeting filter. Null = tune applies on any device.
     * Each entry must match `^[a-z0-9_]+$` and be ≤ 64 chars.
     * Propagated to the bound [UserProfile] on import so [ProfileApplier]
     * Gate 1 still enforces the device restriction after import.
     */
    val targetHandheldKeys: List<String>? = null,

    // ── Bundle fields ─────────────────────────────────────────────────────────

    /**
     * AutoTDP goal to start on launch. Null = do not start AutoTDP.
     * Must be one of the [GoalProfile] enum values.
     */
    val autoTdpGoal: GoalProfile? = null,

    /**
     * Preferred display refresh rate in Hz (e.g. 90f, 120f).
     * Null = do not change. Must be in 1f..500f when present.
     */
    val refreshRateHz: Float? = null,

    /**
     * AYN/Retroid fan_mode preset index (0=Quiet, 4=Smart, 5=Sport).
     * Null = do not change. Must be in 0..10 when present.
     */
    val fanMode: Int? = null,

    /** When true, trigger GameBoost on launch. */
    val gameBoostOnLaunch: Boolean = false,

    // ── Game identity ─────────────────────────────────────────────────────────

    /**
     * Android package name of the target game/app (e.g. "com.example.mygame").
     * Validated with `^[A-Za-z0-9._]+$`, 1..256 chars, not blank.
     */
    val packageName: String,

    /** Human-readable game name shown in the import sheet. Max 256 chars. */
    val gameDisplayName: String,
) {
    companion object {
        /**
         * Build a [ShareableGameTune] from a live [PerAppBundle] and its
         * associated [UserProfile] (the clock-profile backing the bundle).
         *
         * The [profile] may be null when the bundle carries only non-profile
         * fields (goal, refresh-rate, fan, boost) with no clock preset; in that
         * case all clock fields are left at their defaults (empty / null).
         *
         * @param packageName    The target app's package name.
         * @param gameDisplayName Human-readable game name from the library.
         * @param bundle         The [PerAppBundle] to export.
         * @param profile        The [UserProfile] linked via [PerAppBundle.profileId], or null.
         */
        fun fromBundleAndProfile(
            packageName: String,
            gameDisplayName: String,
            bundle: PerAppBundle,
            profile: UserProfile?,
        ): ShareableGameTune = ShareableGameTune(
            fmtVersion = GameTuneShareCodec.CURRENT_GAME_TUNE_FMT_VERSION,
            name = profile?.name ?: gameDisplayName,
            description = profile?.description ?: "",
            cpuPolicyMaxKhz = profile?.cpuPolicyMaxKhz ?: emptyMap(),
            cpuPolicyMinKhz = profile?.cpuPolicyMinKhz ?: emptyMap(),
            cpuPolicyGovernor = profile?.cpuPolicyGovernor ?: emptyMap(),
            gpuMaxHz = profile?.gpuMaxHz,
            gpuMinHz = profile?.gpuMinHz,
            gpuGovernor = profile?.gpuGovernor,
            extraSysfs = profile?.extraSysfs ?: emptyMap(),
            targetHandheldKeys = profile?.targetHandheldKeys,
            autoTdpGoal = bundle.autoTdpGoal,
            refreshRateHz = bundle.refreshRateHz,
            fanMode = bundle.fanMode,
            gameBoostOnLaunch = bundle.gameBoostOnLaunch,
            packageName = packageName,
            gameDisplayName = gameDisplayName,
        )
    }
}

// ─── Sealed result ────────────────────────────────────────────────────────────

/**
 * Result type returned by [GameTuneShareCodec.decode].
 * Always one of [Success] or [Error] — the codec never throws.
 */
sealed class GameTuneDecodeResult {
    /** The code decoded successfully and the tune passed all validation checks. */
    data class Success(val tune: ShareableGameTune) : GameTuneDecodeResult()

    /** The code could not be decoded or failed validation. [reason] is user-facing. */
    data class Error(val reason: String) : GameTuneDecodeResult()

    /**
     * A specific field in the decoded tune failed schema/range validation.
     * [path] is a dot-path like "cpuPolicyMaxKhz.policy0"; [reason] is user-facing.
     */
    data class ValidationError(val path: String, val reason: String) : GameTuneDecodeResult()
}

// ─── Codec ───────────────────────────────────────────────────────────────────

/**
 * Encodes/decodes a full per-game tune as a copy-paste-safe share code.
 *
 * Format: `CSOC2:<url-safe-base64-of-deflate-compressed-json>`
 *
 *  - `CSOC2` is the versioned prefix. Bumping the digit signals a breaking
 *    payload change (unreadable by older app versions).
 *  - The payload is the JSON of [ShareableGameTune] compressed with Deflate
 *    (via [java.util.zip.Deflater] / [java.util.zip.Inflater]) then encoded
 *    with URL-safe Base64 NO_WRAP.
 *  - The same security guards as [PresetShareCodec] apply:
 *      • 64 KiB Base64 length cap before any allocation.
 *      • 256 KiB decompressed-output cap (decompression-bomb guard).
 *      • Full [validateImport] of every imported field before [GameTuneDecodeResult.Success].
 *
 * **Android / JVM isolation:** Base64 is injected via [Base64Encoder] so the
 * same codec runs in pure-JVM unit tests without needing the Android runtime.
 *
 * @param json   A [Json] instance configured with `ignoreUnknownKeys = true`
 *               and `isLenient = false` (the app-wide production instance).
 * @param base64 An [AndroidBase64Encoder] in production; a [JavaBase64Encoder]
 *               in unit tests.
 */
class GameTuneShareCodec(
    private val json: Json,
    private val base64: Base64Encoder,
) {

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Encode a game tune into a shareable one-line CSOC2 code string.
     *
     * Builds a [ShareableGameTune] from the supplied parameters, serialises it
     * to JSON, compresses with Deflate, and Base64-encodes the result.
     *
     * @return `"CSOC2:<base64>"` — a single line safe for Reddit/Discord/clipboard.
     */
    fun encode(
        packageName: String,
        gameDisplayName: String,
        bundle: PerAppBundle,
        profile: UserProfile?,
    ): String {
        val tune = ShareableGameTune.fromBundleAndProfile(packageName, gameDisplayName, bundle, profile)
        val jsonText = json.encodeToString(tune)
        val compressed = deflate(jsonText.toByteArray(Charsets.UTF_8))
        val b64 = base64.encode(compressed)
        return "$GAME_TUNE_PREFIX$b64"
    }

    /**
     * Decode a CSOC2 share code into a [GameTuneDecodeResult].
     *
     * Performs, in order:
     *  1. Trim and prefix check — distinguishes CSOC1 codes, unknown codes,
     *     and valid CSOC2 codes; returns [GameTuneDecodeResult.Error] for
     *     anything that isn't CSOC2.
     *  2. Base64 length cap (64 KiB) before any heap allocation.
     *  3. Base64 decode → Deflate inflate (256 KiB cap) → UTF-8 → JSON.
     *  4. Format-version guard (rejects codes from newer app versions).
     *  5. [validateImport] — full field-level security check before returning
     *     [GameTuneDecodeResult.Success].
     *
     * Never throws — all errors produce [GameTuneDecodeResult.Error].
     */
    fun decode(code: String): GameTuneDecodeResult {
        val trimmed = code.trim()

        // Prefix / version check.
        if (!trimmed.startsWith(GAME_TUNE_PREFIX)) {
            return when {
                trimmed.startsWith("CSOC1:") -> GameTuneDecodeResult.Error(
                    "Unsupported version. This is a clock-only CSOC1 preset — import it via the Profiles screen instead.",
                )
                trimmed.startsWith("CSOC") -> GameTuneDecodeResult.Error(
                    "Unsupported share code version. Update the app to import this game tune.",
                )
                else -> GameTuneDecodeResult.Error(
                    "That doesn't look like a Calibrate SoC game tune code. Codes start with \"$GAME_TUNE_PREFIX\".",
                )
            }
        }

        val b64Part = trimmed.removePrefix(GAME_TUNE_PREFIX)
        if (b64Part.isBlank()) {
            return GameTuneDecodeResult.Error("Game tune code is empty after the version prefix.")
        }

        // Base64 size cap — reject before allocating memory for decoding.
        if (b64Part.length > MAX_BASE64_LENGTH) {
            return GameTuneDecodeResult.Error("Game tune code is too large.")
        }

        // Base64 decode.
        val compressed = runCatching { base64.decode(b64Part) }.getOrElse { e ->
            return GameTuneDecodeResult.Error(
                "Game tune code contains invalid characters: ${e.message}",
            )
        }

        // Inflate.
        val jsonBytes = runCatching { inflate(compressed) }.getOrElse { e ->
            return GameTuneDecodeResult.Error(
                "Game tune code is corrupted (decompression failed): ${e.message}",
            )
        }

        // UTF-8 decode.
        val jsonText = runCatching { jsonBytes.toString(Charsets.UTF_8) }.getOrElse { e ->
            return GameTuneDecodeResult.Error(
                "Game tune code payload is not valid UTF-8: ${e.message}",
            )
        }

        // JSON decode.
        val tune = runCatching {
            json.decodeFromString<ShareableGameTune>(jsonText)
        }.getOrElse { e ->
            return GameTuneDecodeResult.Error(
                "Game tune code contains invalid data: ${e.message}",
            )
        }

        // Format version guard — must know this payload version.
        if (tune.fmtVersion > CURRENT_GAME_TUNE_FMT_VERSION) {
            return GameTuneDecodeResult.Error(
                "Game tune was created with a newer app (format v${tune.fmtVersion}, " +
                    "this app supports up to v$CURRENT_GAME_TUNE_FMT_VERSION). Update the app to import it.",
            )
        }

        // Security: validate all imported fields before returning success.
        val validationError = validateImport(tune)
        if (validationError != null) {
            return GameTuneDecodeResult.Error("Game tune import failed validation: $validationError")
        }

        return GameTuneDecodeResult.Success(tune)
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    /**
     * Validate all fields of an imported [ShareableGameTune].
     *
     * Called by [decode] before returning [GameTuneDecodeResult.Success].
     * Checks are ordered cheapest-first; returns the first error message found,
     * or null when the tune is safe to import.
     *
     * Checks performed:
     *  1. [ShareableGameTune.packageName] — `^[A-Za-z0-9._]+$`, 1..256 chars, not blank.
     *  2. [ShareableGameTune.gameDisplayName] — not blank, ≤ 256 chars, no control chars.
     *  3. [ShareableGameTune.name] — not blank, ≤ 256 chars, no control chars.
     *  4. [ShareableGameTune.description] — ≤ 1024 chars, no control chars.
     *  5. [ShareableGameTune.autoTdpGoal] — must be a known [GoalProfile] enum entry (if not null).
     *  6. [ShareableGameTune.refreshRateHz] — must be in 1f..500f (if not null).
     *  7. [ShareableGameTune.fanMode] — must be in 0..10 (if not null).
     *  8. [ShareableGameTune.cpuPolicyGovernor] values — not blank, no [ValidationRegexes.GOVERNOR_INVALID] chars.
     *  9. [ShareableGameTune.gpuGovernor] — same check as CPU governors (if not null).
     * 10. [ShareableGameTune.extraSysfs] keys — validated via [TunableMetadata.validateCustomSysfsPath].
     * 11. [ShareableGameTune.extraSysfs] values — must not match [ValidationRegexes.SHELL_META].
     * 12. [ShareableGameTune.targetHandheldKeys] entries — `^[a-z0-9_]+$`, ≤ 64 chars each (if not null).
     *
     * @return Null on success, a user-facing error message on the first failure.
     */
    internal fun validateImport(tune: ShareableGameTune): String? {
        val packageNamePattern = Regex("^[A-Za-z0-9._]+$")

        // 1. packageName
        if (tune.packageName.isBlank()) {
            return "packageName must not be blank."
        }
        if (tune.packageName.length > 256) {
            return "packageName is too long (max 256 chars)."
        }
        if (!packageNamePattern.matches(tune.packageName)) {
            return "packageName '${tune.packageName}' contains invalid characters. Only A-Za-z0-9._ are allowed."
        }

        // 2. gameDisplayName
        if (tune.gameDisplayName.isBlank()) {
            return "gameDisplayName must not be blank."
        }
        if (tune.gameDisplayName.length > 256) {
            return "gameDisplayName is too long (max 256 chars)."
        }
        if (ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn(tune.gameDisplayName)) {
            return "gameDisplayName contains invalid control characters."
        }

        // 3. name
        if (tune.name.isBlank()) {
            return "name must not be blank."
        }
        if (tune.name.length > 256) {
            return "name is too long (max 256 chars)."
        }
        if (ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn(tune.name)) {
            return "name contains invalid control characters."
        }

        // 4. description
        if (tune.description.length > 1024) {
            return "description is too long (max 1024 chars)."
        }
        if (ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn(tune.description)) {
            return "description contains invalid control characters."
        }

        // 5. autoTdpGoal — explicit enum membership check
        if (tune.autoTdpGoal != null && !GoalProfile.entries.contains(tune.autoTdpGoal)) {
            return "autoTdpGoal '${tune.autoTdpGoal}' is not a recognised GoalProfile value."
        }

        // 6. refreshRateHz
        if (tune.refreshRateHz != null && tune.refreshRateHz !in 1f..500f) {
            return "refreshRateHz ${tune.refreshRateHz} is out of range (must be 1–500 Hz)."
        }

        // 7. fanMode
        if (tune.fanMode != null && tune.fanMode !in 0..10) {
            return "fanMode ${tune.fanMode} is out of range (must be 0–10)."
        }

        // 8. cpuPolicyGovernor values
        for ((policy, governor) in tune.cpuPolicyGovernor) {
            if (governor.isBlank()) {
                return "cpuPolicyGovernor for policy $policy must not be blank."
            }
            if (ValidationRegexes.GOVERNOR_INVALID.containsMatchIn(governor)) {
                return "cpuPolicyGovernor for policy $policy ('$governor') contains invalid characters."
            }
        }

        // 9. gpuGovernor
        if (tune.gpuGovernor != null) {
            if (tune.gpuGovernor.isBlank()) {
                return "gpuGovernor must not be blank."
            }
            if (ValidationRegexes.GOVERNOR_INVALID.containsMatchIn(tune.gpuGovernor)) {
                return "gpuGovernor '${tune.gpuGovernor}' contains invalid characters."
            }
        }

        // 10. extraSysfs keys — path safety
        for (path in tune.extraSysfs.keys) {
            val pathError = TunableMetadata.validateCustomSysfsPath(path)
            if (pathError != null) {
                return "extraSysfs path '$path' is invalid: $pathError"
            }
        }

        // 11. extraSysfs values — no shell metacharacters
        for ((path, value) in tune.extraSysfs) {
            if (ValidationRegexes.SHELL_META.containsMatchIn(value)) {
                return "extraSysfs value for path '$path' contains unsafe shell characters."
            }
        }

        // 12. targetHandheldKeys
        val handheldKeyPattern = Regex("^[a-z0-9_]+$")
        if (tune.targetHandheldKeys != null) {
            for (key in tune.targetHandheldKeys) {
                if (key.length > 64) {
                    return "targetHandheldKeys entry '$key' is too long (max 64 chars)."
                }
                if (!handheldKeyPattern.matches(key)) {
                    return "targetHandheldKeys entry '$key' contains invalid characters. Only a-z0-9_ are allowed."
                }
            }
        }

        return null
    }

    // ─── Compression helpers (same as PresetShareCodec) ───────────────────────

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
            // Decompression-bomb guard: cap total decompressed output.
            // A real game tune is well under 256 KiB; exceeding this cap indicates
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
        /**
         * Current CSOC2 payload format version embedded in [ShareableGameTune.fmtVersion].
         * Independent of [PresetShareCodec.CURRENT_FMT_VERSION].
         * History:
         *   v1 — initial release (clock profile + bundle fields + game identity).
         */
        const val CURRENT_GAME_TUNE_FMT_VERSION = 1

        /**
         * Maximum allowed length (in chars) of the Base64 portion of a share code.
         * Matches [PresetShareCodec.MAX_BASE64_LENGTH].
         * 64 KiB is far larger than any real game-tune code.
         */
        const val MAX_BASE64_LENGTH = 64 * 1024

        /**
         * Maximum number of bytes the decompressed payload may occupy.
         * Matches [PresetShareCodec.MAX_INFLATED_BYTES].
         * 256 KiB is far larger than any real game-tune JSON.
         */
        const val MAX_INFLATED_BYTES = 256 * 1024
    }
}
