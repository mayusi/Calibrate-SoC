package io.github.mayusi.calibratesoc.data.remote

import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.share.PresetShareCodec
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import kotlinx.serialization.Serializable

/**
 * Pure-Kotlin (no Android runtime) validator for remote OTA content.
 * Extracted from [RemoteContentRepository] so it can be unit-tested on the JVM
 * without an Android context — the companion object of [RemoteContentRepository]
 * cannot be loaded in pure-JVM tests because the class itself depends on
 * [android.content.Context].
 *
 * Both [RemoteContentRepository] and the unit tests share this object.
 */
internal object RemoteContentValidator {

    // ── OTA content URLs ──────────────────────────────────────────────────────

    /** Hardcoded GitHub raw URLs — the base URL is never derived from user input. */
    private const val BASE = "https://raw.githubusercontent.com/mayusi/Calibrate-SoC/main/content"
    const val MANIFEST_URL   = "$BASE/manifest.json"
    const val ADAPTERS_URL   = "$BASE/adapters.json"
    const val PRESETS_URL    = "$BASE/presets.json"
    const val GAME_TUNES_URL = "$BASE/game_tunes.json"

    // ── Community game tunes data class ──────────────────────────────────────

    /**
     * A community-contributed tune entry fetched from [GAME_TUNES_URL].
     *
     * [tuneCode] is a CSOC2 share code (prefix "CSOC2:") — it is validated for
     * syntactic plausibility here but NOT decoded; decoding is the responsibility
     * of GameTuneShareCodec at import time.
     */
    @Serializable
    data class CommunityGameTune(
        val tuneCode: String,
        val gameDisplayName: String,
        val packageName: String,
        val authorHandle: String = "",
        val targetDeviceKeys: List<String> = emptyList(),
        val notes: String = "",
    )

    // ── Validation regexes ────────────────────────────────────────────────────
    //
    // Canonical definitions live in [ValidationRegexes]; these aliases keep the
    // public API surface stable so any existing call-sites outside this file
    // continue to compile without change.

    /** @see ValidationRegexes.SHELL_META */
    val SHELL_META: Regex       get() = ValidationRegexes.SHELL_META
    /** @see ValidationRegexes.DISPLAY_UNSAFE */
    val DISPLAY_UNSAFE: Regex   get() = ValidationRegexes.DISPLAY_UNSAFE
    /** @see ValidationRegexes.GOVERNOR_INVALID */
    val GOVERNOR_INVALID: Regex get() = ValidationRegexes.GOVERNOR_INVALID
    /** @see ValidationRegexes.KEY_PATTERN */
    val KEY_PATTERN: Regex      get() = ValidationRegexes.KEY_PATTERN
    /** @see ValidationRegexes.DAEMON_INVALID */
    val DAEMON_INVALID: Regex   get() = ValidationRegexes.DAEMON_INVALID

    /**
     * Validates a remote [DeviceAdapter]. Returns null if valid, or a human-readable
     * error string on the first failing constraint.
     */
    fun validateAdapter(adapter: DeviceAdapter): String? {
        if (!KEY_PATTERN.matches(adapter.key)) {
            return "key '${adapter.key}' contains disallowed characters (must be [a-z0-9_])"
        }
        if (adapter.key.isBlank()) return "key must not be blank"
        if (adapter.displayName.isBlank()) return "displayName must not be blank"
        // Display-only — punctuation allowed, control chars/newlines not.
        if (DISPLAY_UNSAFE.containsMatchIn(adapter.displayName)) return "displayName contains control characters"
        adapter.notes?.let { notes ->
            if (DISPLAY_UNSAFE.containsMatchIn(notes)) return "notes contains control characters"
        }
        adapter.fanAdapter?.let { fan ->
            if (SHELL_META.containsMatchIn(fan.target)) return "fanAdapter.target contains disallowed characters"
        }
        adapter.perfPresetAdapter?.let { perf ->
            if (SHELL_META.containsMatchIn(perf.target)) return "perfPresetAdapter.target contains disallowed characters"
            for (mapping in perf.presets) {
                if (SHELL_META.containsMatchIn(mapping.value))
                    return "perfPresetAdapter.presets value '${mapping.value}' contains disallowed characters"
            }
        }
        for (key in adapter.perfDaemonsToStopOnWrite) {
            if (DAEMON_INVALID.containsMatchIn(key))
                return "perfDaemonsToStopOnWrite '$key' contains disallowed characters"
        }
        return null
    }

    /**
     * Validates a remote [Preset]. Returns null if valid, or a human-readable error string.
     * Mirrors [io.github.mayusi.calibratesoc.data.backup.BackupManager.validateProfile].
     */
    fun validatePreset(preset: Preset): String? {
        if (preset.id.isBlank()) return "id must not be blank"
        if (SHELL_META.containsMatchIn(preset.id)) return "id contains disallowed characters"
        if (preset.name.isBlank()) return "name must not be blank"
        // name + description are DISPLAY-only (the script generator escapes anything it
        // emits) — allow human punctuation like / ( ) & — , just not control chars.
        if (DISPLAY_UNSAFE.containsMatchIn(preset.name)) return "name contains control characters"
        if (DISPLAY_UNSAFE.containsMatchIn(preset.description)) return "description contains control characters"
        // sourceUrl legitimately contains '/' and ':' — only block control chars + quotes
        // (it's shown in a UI link + a '# Source:' script comment, which is comment-safe).
        preset.sourceUrl?.let { url ->
            if (DISPLAY_UNSAFE.containsMatchIn(url) || url.contains('\'') || url.contains('"')) {
                return "sourceUrl contains disallowed characters"
            }
        }
        for ((policyId, gov) in preset.cpuPolicyGovernor) {
            if (GOVERNOR_INVALID.containsMatchIn(gov))
                return "cpuPolicyGovernor[$policyId] '$gov' contains disallowed characters"
            if (gov.isBlank()) return "cpuPolicyGovernor[$policyId] must not be blank"
        }
        preset.gpuGovernor?.let { gov ->
            if (GOVERNOR_INVALID.containsMatchIn(gov)) return "gpuGovernor '$gov' contains disallowed characters"
            if (gov.isBlank()) return "gpuGovernor must not be blank"
        }
        // Validate extraSysfs entries — both the path key and the value.
        //
        // Path: delegated to TunableMetadata.validateCustomSysfsPath() which enforces the
        // /sys/ or /proc/ prefix, rejects traversal and dangerous nodes, and (after S0(a))
        // rejects all shell metacharacters.
        //
        // Value: control chars and SHELL_META characters are rejected as defence-in-depth.
        // The script generator (emitSysfsWrite) wraps values in shellSingleQuote() already,
        // but a control char (e.g. a NUL or ESC) in the value has no legitimate use and
        // could confuse kernel parsers or audit log consumers even within a quoted context.
        for ((key, value) in preset.extraSysfs) {
            val pathErr = TunableMetadata.validateCustomSysfsPath(key)
            if (pathErr != null) return "extraSysfs path '$key' is invalid: $pathErr"
            if (SHELL_META.containsMatchIn(value)) {
                return "extraSysfs value for '$key' contains disallowed characters"
            }
        }
        return null
    }

    /**
     * Validates a [CommunityGameTune] fetched from [GAME_TUNES_URL].
     * Returns null if valid, or a human-readable error string on the first failing constraint.
     *
     * NOTE: [CommunityGameTune.tuneCode] is only checked for syntactic plausibility
     * (non-blank, correct prefix, length within cap). Actual decoding and semantic
     * validation are deferred to GameTuneShareCodec at import time.
     */
    fun validateCommunityTune(tune: CommunityGameTune): String? {
        // packageName: non-blank, ≤ 256 chars, alphanumeric + dots + underscores only
        if (tune.packageName.isBlank()) return "packageName must not be blank"
        if (tune.packageName.length > 256) return "packageName exceeds 256 characters"
        if (!Regex("^[A-Za-z0-9._]+$").matches(tune.packageName))
            return "packageName '${tune.packageName}' contains disallowed characters"

        // gameDisplayName: non-blank, ≤ 256 chars, no control characters
        if (tune.gameDisplayName.isBlank()) return "gameDisplayName must not be blank"
        if (tune.gameDisplayName.length > 256) return "gameDisplayName exceeds 256 characters"
        if (DISPLAY_UNSAFE.containsMatchIn(tune.gameDisplayName))
            return "gameDisplayName contains control characters"

        // tuneCode: non-blank, must start with "CSOC2:", length ≤ MAX_BASE64_LENGTH
        if (tune.tuneCode.isBlank()) return "tuneCode must not be blank"
        if (!tune.tuneCode.startsWith("CSOC2:")) return "tuneCode must start with 'CSOC2:'"
        if (tune.tuneCode.length > PresetShareCodec.MAX_BASE64_LENGTH)
            return "tuneCode exceeds maximum allowed length"

        // authorHandle: optional, ≤ 64 chars, no control characters
        if (tune.authorHandle.length > 64) return "authorHandle exceeds 64 characters"
        if (DISPLAY_UNSAFE.containsMatchIn(tune.authorHandle))
            return "authorHandle contains control characters"

        // notes: optional, ≤ 512 chars, no control characters
        if (tune.notes.length > 512) return "notes exceeds 512 characters"
        if (DISPLAY_UNSAFE.containsMatchIn(tune.notes))
            return "notes contains control characters"

        // targetDeviceKeys: each entry must match [a-z0-9_], ≤ 64 chars
        for (deviceKey in tune.targetDeviceKeys) {
            if (deviceKey.length > 64) return "targetDeviceKeys entry '$deviceKey' exceeds 64 characters"
            if (!Regex("^[a-z0-9_]+$").matches(deviceKey))
                return "targetDeviceKeys entry '$deviceKey' contains disallowed characters"
        }

        return null
    }
}
