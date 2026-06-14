package io.github.mayusi.calibratesoc.data.remote

import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.presets.Preset

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
    const val MANIFEST_URL = "$BASE/manifest.json"
    const val ADAPTERS_URL = "$BASE/adapters.json"
    const val PRESETS_URL  = "$BASE/presets.json"

    // ── Validation regexes ────────────────────────────────────────────────────

    /** Shell metacharacters and ASCII control chars. Matches BackupManager.validateProfile.
     *  Applied to fields that can reach a generated root SCRIPT (governor, sysfs
     *  targets/values, daemon names) where an unescaped metacharacter would be a real
     *  injection risk. */
    val SHELL_META: Regex       = Regex("""['"`${'$'};&|<>(){}]|\p{Cntrl}""")
    /** DISPLAY-only fields (preset/adapter name + description + notes) are never fed to a
     *  shell — the script generator [commentSafe]/[shellSingleQuote]-escapes everything it
     *  emits. So these only need protection against control chars + line breaks (which
     *  could break a single-line UI label or comment). Punctuation like `/ ( ) & —` is
     *  legitimate in a human-readable name ("RP6 — PS2 / GameCube (Sustained)") and must
     *  be allowed, or our own honest preset names would be rejected. */
    val DISPLAY_UNSAFE: Regex   = Regex("""[\p{Cntrl}\n\r]""")
    /** Governor names additionally reject whitespace and '/'. */
    val GOVERNOR_INVALID: Regex = Regex("""['"`${'$'};&|<>(){}]|\p{Cntrl}|[\s/]""")
    /** Adapter keys are restricted to lowercase identifier chars. */
    val KEY_PATTERN: Regex      = Regex("""^[a-z0-9_]+$""")
    /** Daemon names must not contain shell-dangerous chars or whitespace. */
    val DAEMON_INVALID: Regex   = Regex("""['"`${'$'};&|<>(){}]|\p{Cntrl}|[\s]""")

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
        return null
    }
}
