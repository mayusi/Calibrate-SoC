package io.github.mayusi.calibratesoc.data.remote

/**
 * Canonical regex definitions shared by [RemoteContentValidator] and
 * [io.github.mayusi.calibratesoc.data.backup.BackupManager].
 *
 * Single source of truth — update here when the threat model changes. Both the
 * OTA validator and the backup import validator reference these values so they
 * stay in sync automatically.
 */
internal object ValidationRegexes {

    /** Shell metacharacters and ASCII control chars. Applied to fields that can
     *  reach a generated root script (governor, sysfs targets/values, daemon
     *  names) where an unescaped metacharacter would be a real injection risk. */
    val SHELL_META: Regex = Regex("""['"`${'$'};&|<>(){}]|\p{Cntrl}""")

    /** DISPLAY-only fields (preset/adapter name + description + notes) are never
     *  fed to a shell — the script generator commentSafe/shellSingleQuote-escapes
     *  everything it emits. So these only need protection against control chars +
     *  line breaks (which could break a single-line UI label or comment).
     *  Punctuation like `/ ( ) & —` is legitimate in a human-readable name
     *  ("RP6 — PS2 / GameCube (Sustained)") and must be allowed, or our own
     *  honest preset names would be rejected. */
    val DISPLAY_UNSAFE: Regex = Regex("""[\p{Cntrl}\n\r]""")

    /** Governor names additionally reject whitespace and '/'. */
    val GOVERNOR_INVALID: Regex = Regex("""['"`${'$'};&|<>(){}]|\p{Cntrl}|[\s/]""")

    /** Adapter keys are restricted to lowercase identifier chars. */
    val KEY_PATTERN: Regex = Regex("""^[a-z0-9_]+$""")

    /** Daemon names must not contain shell-dangerous chars or whitespace. */
    val DAEMON_INVALID: Regex = Regex("""['"`${'$'};&|<>(){}]|\p{Cntrl}|[\s]""")
}
