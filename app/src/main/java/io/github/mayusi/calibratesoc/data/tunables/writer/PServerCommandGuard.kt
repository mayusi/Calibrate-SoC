package io.github.mayusi.calibratesoc.data.tunables.writer

import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata

/**
 * PROVABLY-SAFE allow-list guard for EVERY command that reaches AYN/Retroid's
 * `PServerBinder` root daemon.
 *
 * ## Why this exists
 *
 * `PServerWriter.transact()` is the ONE chokepoint through which every root
 * command in the app flows (writeSysfs chmod-sandwich, writeSettingsSystem, and
 * every `executeShell` caller — HardwareScanner, CpuLoadSource, AppReaper,
 * GameFpsSampler, FanCurveController, StorageSpeedTester, PrivilegedSysfsReader,
 * TunableWriter daemon hooks). There is exactly one `binder.transact()` in the
 * app. If we deny a command here, it CANNOT reach root — unbypassable by
 * construction.
 *
 * A user recently had a device wiped by an unrelated `rm` from another app. The
 * design goal here is the strongest possible: *provably cannot run a destructive
 * command*. We therefore DEFAULT-DENY: a command is allowed ONLY when every one
 * of its segments matches an explicit, narrow allow rule built directly from the
 * real call-site inventory. Anything not on the list — including any future new
 * call shape — is denied until someone deliberately widens the allow-list (and
 * adds a regression test for it).
 *
 * ## How it works
 *
 *  1. Fast tripwires: a real NUL byte, or a raw newline outside the one
 *     structured fan-script form, is an instant DENY.
 *  2. Split the command into segments on the shell operators `&&`, `||`, `;`,
 *     `|` — but ONLY when those operators appear OUTSIDE single quotes. POSIX
 *     single-quote is literal and is the ONLY quoting style the codebase emits
 *     ([String.shellQuote] everywhere), so a `;` or `|` inside `'...'` (a
 *     SurfaceFlinger layer name, the fan-script base64 blob) is data, not a
 *     separator, and must not split.
 *  3. The fan-curve apply script is a single structured `if … ; then … ; else …
 *     ; fi` blob. It is recognised and validated as a WHOLE (every path it
 *     touches must be the fixed Odin config-prefix) before per-segment parsing,
 *     because it legitimately uses `mv -f`/`rm -f`/`chown`/`kill` that the
 *     generic rules forbid. The carve-out is path-pinned: it can only ever touch
 *     `/data/user_de/0/com.odin.settings/shared_prefs/config.xml[.calibrate.tmp]`.
 *  4. Every remaining segment must independently pass [inspectSegment]. The
 *     first failing segment is named in the [Verdict.Deny] reason.
 *
 * ## One blocklist, not two
 *
 * Path safety reuses the SAME validators the rest of the app uses:
 * [TunableMetadata.isDangerousPath] for the dangerous-node block list and a
 * local sysfs-path check identical in contract to
 * [PServerWriter.validateSysfsPath]. There is no second blocklist to drift.
 *
 * ## setenforce is ALWAYS denied here
 *
 * `setenforce` (toggling SELinux enforcement) is HARD-DENIED in [inspect]. A
 * future user-initiated "Force SELinux permissive" action will use a SEPARATE
 * dedicated method carrying an explicit one-shot token — NOT [PServerWriter
 * .executeShell] / the generic write path — so the general guard can stay
 * absolute about it. Never relax this.
 */
object PServerCommandGuard {

    /** Result of inspecting a command. */
    sealed interface Verdict {
        /** The command is safe to send to PServer. */
        object Allow : Verdict

        /** The command is blocked. [reason] names the offending segment/cause. */
        data class Deny(val reason: String) : Verdict
    }

    // ── Fixed, pinned paths for the Odin fan-curve carve-out ──────────────────
    //
    // The ONLY non-sysfs paths the guard will ever allow a write/move/chown/rm on.
    // Hardcoded to mirror FanCurveScript.CONFIG_XML_PATH so the carve-out cannot
    // be aimed anywhere else (not /data elsewhere, not /sdcard, not /, no glob).

    /** Exact Odin stock-settings shared_prefs directory. Trailing slash is load-bearing. */
    private const val ODIN_PREFS_DIR = "/data/user_de/0/com.odin.settings/shared_prefs/"

    /** Exact config.xml the fan curve overwrites. */
    private const val ODIN_CONFIG_XML = ODIN_PREFS_DIR + "config.xml"

    /** Exact temp file the fan curve decodes into before the atomic mv. */
    private const val ODIN_CONFIG_TMP = ODIN_CONFIG_XML + ".calibrate.tmp"

    /** Odin stock-settings package whose process the fan script kills to drop its cache. */
    private const val ODIN_SETTINGS_PKG = "com.odin.settings"

    // ── Known-good fixed argument sets ────────────────────────────────────────

    /** Our own package names — the only `pm grant` targets. */
    private val GRANTABLE_PKGS = setOf(
        "io.github.mayusi.calibratesoc",
        "io.github.mayusi.calibratesoc.debug",
    )

    /** The only permissions we ever `pm grant` (see AdvancedPermissionsScript). */
    private val GRANTABLE_PERMS = setOf(
        "android.permission.DUMP",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.WRITE_SECURE_SETTINGS",
    )

    /**
     * The only services `stop`/`start` may target — the perf daemons declared in
     * the device DB ([adapter.perfDaemonsToStopOnWrite]) that race the cpufreq
     * write. A `start`/`stop` of anything else (e.g. `stop netd`) is denied.
     * Pattern (not a fixed list) because the device DB ships daemon names per
     * adapter; this matches the validator contract `[A-Za-z0-9._-]+` AND requires
     * a known perf-daemon shape so we never start/stop an arbitrary init service.
     */
    private val PERF_DAEMON = Regex("""(?:vendor\.)?perf[A-Za-z0-9._-]*""")

    /** The single allowed `dumpsys` subjects (read/query only). */
    private val DUMPSYS_SURFACEFLINGER_FLAGS = setOf("--list", "--version")

    /**
     * Allowed sub-commands for `dumpsys activity`. The activity manager DUMP
     * permission (granted by the one-time unlock script) makes these world-safe
     * read queries. We allow ONLY the two sub-forms the foreground-reader uses:
     *   - `activities` → mResumedActivity grep (foreground package, root path)
     *   - `top-activity` → alternate single-activity form (same info, less output)
     * Anything else (e.g. `dumpsys activity services`) is denied.
     */
    private val DUMPSYS_ACTIVITY_ALLOWED_SUBCOMMANDS = setOf("activities", "top-activity")

    /** Package-name shape — Android packages are `[A-Za-z0-9_.]+` with at least one dot. */
    private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")

    /** Settings.System key shape (matches PServerWriter.writeSettingsSystem). */
    private val SETTINGS_KEY = Regex("""[A-Za-z0-9_.]+""")

    /** getprop/setprop-style property key shape (read only — getprop). */
    private val PROP_KEY = Regex("""[A-Za-z0-9_.\-]+""")

    /**
     * Command verbs that are ALWAYS destructive in the general path and can never
     * appear except inside the path-pinned fan-script carve-out. Listed for clarity
     * and for the fast tripwire; default-deny already blocks them, but naming them
     * makes the intent auditable and the deny reason precise.
     */
    private val HARD_DENY_VERBS = setOf(
        "rm", "dd", "mkfs", "mke2fs", "make_ext4fs", "format", "mknod", "mv", "cp",
        "ln", "unlink", "truncate", "shred", "wipe", "fastboot", "reboot", "poweroff",
        "svc", "content", "setenforce", "init", "mount", "umount", "insmod", "rmmod",
        "modprobe", "busybox", "toybox", "sh", "bash", "su", "chroot", "nsenter",
        "blockdev", "sgdisk", "parted", "tune2fs", "resize2fs", "e2fsck", "fsck",
    )

    /**
     * Inspect a full command string. Returns [Verdict.Allow] only when every
     * segment matches an allow rule; otherwise [Verdict.Deny] naming the cause.
     */
    fun inspect(command: String): Verdict {
        // ── Fast tripwires ────────────────────────────────────────────────────
        if (command.isBlank()) return Verdict.Deny("empty command")
        if (command.any { it.code == 0 }) return Verdict.Deny("command contains a NUL byte")
        // Carriage returns never legitimately appear.
        if (command.contains('\r')) return Verdict.Deny("command contains a carriage return")

        // `setenforce` is absolute-denied everywhere (see class doc). Check before
        // anything else so no parsing quirk can ever let it through.
        if (containsBareToken(command, "setenforce")) {
            return Verdict.Deny("setenforce is never permitted via the general guard (use the dedicated SELinux action)")
        }

        // ── Fan-curve apply script: validated as a whole, path-pinned ──────────
        // It is the ONLY command that legitimately contains a raw newline-free
        // `if … fi` structure with mv/rm/chown/kill. If it LOOKS like the fan
        // script (starts with `if printf ... | base64 -d`), it must match the
        // strict whole-script validator — partial matches are denied.
        if (looksLikeFanScript(command)) {
            return validateFanScript(command)
        }

        // No raw newlines outside the fan script.
        if (command.contains('\n')) return Verdict.Deny("command contains a raw newline")

        // ── Split into segments (quote-aware) and validate each ───────────────
        val segments = splitSegments(command)
        if (segments.isEmpty()) return Verdict.Deny("no executable segment found")
        for (seg in segments) {
            val trimmed = seg.trim()
            if (trimmed.isEmpty()) continue // empty segment between operators is harmless
            val verdict = inspectSegment(trimmed)
            if (verdict is Verdict.Deny) return verdict
        }
        return Verdict.Allow
    }

    // ── Per-segment allow rules ───────────────────────────────────────────────

    /**
     * Validate ONE shell segment (already split off the operators). Default-deny:
     * the first token selects an allow rule; the rule validates the arguments.
     */
    private fun inspectSegment(segment: String): Verdict {
        // Strip leading grouping/structure tokens that the splitter may leave on a
        // segment boundary in non-fan commands (defensive; the real inputs don't
        // use these outside the fan script, which is handled separately).
        val seg = segment
            .removePrefix("(").removePrefix("{").trim()
        if (seg.isEmpty()) return Verdict.Allow

        // A redirect can appear in a generic segment (e.g. drop_caches flush, or a
        // benign `2>/dev/null` on the fan metadata read). The redirect operator + its
        // target are STRIPPED before tokenizing so the verb/arg-count rules see only
        // the real arguments; redirect targets are policed separately by hasRedirect /
        // redirectTarget on the raw segment.
        val redirectFree = stripRedirects(seg)
        val tokens = tokenize(redirectFree) ?: return Verdict.Deny("unparseable segment: '$segment'")
        if (tokens.isEmpty()) return Verdict.Deny("empty segment after tokenizing: '$segment'")

        val verb = tokens[0]

        // Hard-deny verbs can never reach the generic path. (Fan-script carve-out
        // is handled earlier and never lands here.)
        if (verb in HARD_DENY_VERBS) {
            return Verdict.Deny("destructive/forbidden verb '$verb' in segment: '$segment'")
        }

        return when (verb) {
            "cat" -> allowCat(tokens, segment)
            "sync" -> if (tokens.size == 1) Verdict.Allow
                else Verdict.Deny("sync takes no arguments: '$segment'")
            "echo" -> allowEcho(tokens, segment)
            "printf" -> allowPrintf(tokens, segment)
            "chmod" -> allowChmodGeneric(tokens, segment)
            "settings" -> allowSettings(tokens, segment)
            "pm" -> allowPm(tokens, segment)
            "am" -> allowAm(tokens, segment)
            "dumpsys" -> allowDumpsys(tokens, segment)
            "getprop" -> allowGetprop(tokens, segment)
            "getenforce" -> if (tokens.size == 1) Verdict.Allow
                else Verdict.Deny("getenforce takes no arguments: '$segment'")
            "service" -> allowService(tokens, segment)
            "stat" -> allowStat(tokens, segment)
            "grep" -> allowGrep(tokens, segment)
            "true" -> if (tokens.size == 1) Verdict.Allow
                else Verdict.Deny("'true' takes no arguments: '$segment'")
            "stop", "start" -> allowDaemonControl(tokens, segment)
            else -> Verdict.Deny("verb '$verb' is not on the allow-list: '$segment'")
        }
    }

    /**
     * `cat <path>` / `cat /proc/stat`. The path must be a validated sysfs/proc
     * node, OR the pinned Odin config/tmp file (FanCurveController reads config.xml
     * back for the read-modify-write). Read-only: no redirect permitted.
     */
    private fun allowCat(tokens: List<String>, segment: String): Verdict {
        // Any redirect in a cat segment is forbidden (cat never writes).
        if (hasRedirect(segment)) return Verdict.Deny("cat must not redirect: '$segment'")
        if (tokens.size != 2) return Verdict.Deny("cat takes exactly one path: '$segment'")
        if (isOdinConfigPath(tokens[1])) return Verdict.Allow
        return requireReadablePath(tokens[1], segment)
    }

    /**
     * `echo 3 > /proc/sys/vm/drop_caches` — the ONE allowed /proc/sys write.
     * Also a bare `echo <text>` (no redirect) is harmless. Any redirect target
     * MUST be a validated sysfs node, and `drop_caches` is on the dangerous list
     * EXCEPT this single, exact, sanctioned shape.
     */
    private fun allowEcho(tokens: List<String>, segment: String): Verdict {
        if (!hasRedirect(segment)) {
            // Plain echo of literal text — no filesystem effect. Allow.
            return Verdict.Allow
        }
        // The only sanctioned echo-redirect is the drop_caches cache flush.
        val normalized = segment.trim().replace(Regex("\\s+"), " ")
        if (normalized == "echo 3 > /proc/sys/vm/drop_caches") return Verdict.Allow
        return Verdict.Deny("the only permitted echo-redirect is the drop_caches flush: '$segment'")
    }

    /**
     * `printf %s '<val>' > '<path>'` — the write half of the chmod-sandwich.
     * The redirect target MUST be a validated sysfs node. A `printf` WITHOUT a
     * redirect is harmless (no FS effect) and allowed.
     */
    private fun allowPrintf(tokens: List<String>, segment: String): Verdict {
        if (!hasRedirect(segment)) return Verdict.Allow
        val target = redirectTarget(segment)
            ?: return Verdict.Deny("could not parse printf redirect target: '$segment'")
        return requireWritableSysfsPath(target, segment)
    }

    /**
     * Generic `chmod <mode> '<path>'` (the sandwich's 666/444/644). The path MUST
     * be a validated sysfs node. (The fan-script chmod on the Odin config path is
     * handled inside the fan carve-out, never here.)
     */
    private fun allowChmodGeneric(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("chmod must not redirect: '$segment'")
        if (tokens.size != 3) return Verdict.Deny("chmod takes <mode> <path>: '$segment'")
        val mode = tokens[1]
        if (!mode.matches(Regex("[0-7]{3,4}"))) {
            return Verdict.Deny("chmod mode must be octal 3-4 digits: '$segment'")
        }
        return requireWritableSysfsPath(tokens[2], segment)
    }

    /** `settings put system <key> <val>` / `settings get system <key>`. */
    private fun allowSettings(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("settings must not redirect: '$segment'")
        // settings <get|put> system <key> [value]
        if (tokens.size < 4) return Verdict.Deny("settings command too short: '$segment'")
        val op = tokens[1]
        val namespace = tokens[2]
        if (op != "get" && op != "put") return Verdict.Deny("settings op must be get/put: '$segment'")
        if (namespace != "system") return Verdict.Deny("settings namespace must be 'system': '$segment'")
        if (!SETTINGS_KEY.matches(tokens[3])) return Verdict.Deny("settings key has disallowed chars: '$segment'")
        if (op == "get") {
            if (tokens.size != 4) return Verdict.Deny("settings get takes only a key: '$segment'")
        } else {
            // put: key + exactly one value token (value is shell-quoted at the
            // call-site; the tokenizer returns it as one token). The value is data —
            // it ran through `settings put` which never interprets it as a command —
            // so any value content is fine as long as it's a single token.
            if (tokens.size != 5) return Verdict.Deny("settings put takes key + one value: '$segment'")
        }
        return Verdict.Allow
    }

    /** `pm grant <pkg> <perm>` — only our packages + the three known perms. */
    private fun allowPm(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("pm must not redirect: '$segment'")
        if (tokens.size != 4 || tokens[1] != "grant") {
            return Verdict.Deny("only 'pm grant <pkg> <perm>' is permitted: '$segment'")
        }
        if (tokens[2] !in GRANTABLE_PKGS) {
            return Verdict.Deny("pm grant target must be our own package: '$segment'")
        }
        if (tokens[3] !in GRANTABLE_PERMS) {
            return Verdict.Deny("pm grant permission not on allow-list: '$segment'")
        }
        return Verdict.Allow
    }

    /**
     * `am force-stop <pkg>` / `am set-inactive <pkg> true|false` (AppReaper).
     * Explicitly NOT `am start` / `am broadcast` / anything else.
     */
    private fun allowAm(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("am must not redirect: '$segment'")
        if (tokens.size < 3) return Verdict.Deny("am command too short: '$segment'")
        return when (tokens[1]) {
            "force-stop" -> {
                if (tokens.size != 3) return Verdict.Deny("am force-stop takes one pkg: '$segment'")
                if (!PACKAGE_NAME.matches(tokens[2])) return Verdict.Deny("am force-stop pkg invalid: '$segment'")
                Verdict.Allow
            }
            "set-inactive" -> {
                if (tokens.size != 4) return Verdict.Deny("am set-inactive takes pkg + bool: '$segment'")
                if (!PACKAGE_NAME.matches(tokens[2])) return Verdict.Deny("am set-inactive pkg invalid: '$segment'")
                if (tokens[3] != "true" && tokens[3] != "false") {
                    return Verdict.Deny("am set-inactive flag must be true/false: '$segment'")
                }
                Verdict.Allow
            }
            else -> Verdict.Deny("only am force-stop / set-inactive are permitted: '$segment'")
        }
    }

    /**
     * `dumpsys SurfaceFlinger --list|--version`,
     * `dumpsys SurfaceFlinger --latency '<layer>'`,
     * `dumpsys gfxinfo <pkg>`,
     * `dumpsys activity activities` (foreground-package root read — mResumedActivity grep),
     * `dumpsys window` (foreground-package root read — mCurrentFocus grep).
     * All are read/query only — no redirect permitted.
     *
     * The two new subjects (`activity` and `window`) are narrowly gated:
     *   - `activity`: only the sub-commands in [DUMPSYS_ACTIVITY_ALLOWED_SUBCOMMANDS].
     *     These enumerate the activity stack; they do NOT start, stop, or modify anything.
     *   - `window`: bare `dumpsys window` only (no extra args). The WindowManager dump
     *     includes `mCurrentFocus` which identifies the foreground window package.
     * Both are read-only system queries that require no special permission beyond DUMP
     * (granted by the unlock script) and are root-safe with no FS effect.
     */
    private fun allowDumpsys(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("dumpsys must not redirect: '$segment'")
        if (tokens.size < 2) return Verdict.Deny("dumpsys needs a service: '$segment'")
        return when (tokens[1]) {
            "SurfaceFlinger" -> {
                when (tokens.size) {
                    2 -> Verdict.Allow // bare dumpsys SurfaceFlinger (read)
                    3 -> if (tokens[2] in DUMPSYS_SURFACEFLINGER_FLAGS) Verdict.Allow
                        else Verdict.Deny("unknown SurfaceFlinger flag: '$segment'")
                    4 -> if (tokens[2] == "--latency") {
                        // The layer name is a single quoted token — arbitrary text is
                        // safe data here (dumpsys never executes it). No path check.
                        Verdict.Allow
                    } else Verdict.Deny("unknown SurfaceFlinger 3-arg form: '$segment'")
                    else -> Verdict.Deny("dumpsys SurfaceFlinger too many args: '$segment'")
                }
            }
            "gfxinfo" -> {
                if (tokens.size != 3) return Verdict.Deny("dumpsys gfxinfo takes one pkg: '$segment'")
                if (!PACKAGE_NAME.matches(tokens[2])) return Verdict.Deny("dumpsys gfxinfo pkg invalid: '$segment'")
                Verdict.Allow
            }
            "activity" -> {
                // `dumpsys activity <sub-command>` — only the allowed read-only sub-commands.
                // These query the activity stack without starting/stopping anything.
                if (tokens.size != 3) {
                    return Verdict.Deny("dumpsys activity takes exactly one sub-command: '$segment'")
                }
                if (tokens[2] !in DUMPSYS_ACTIVITY_ALLOWED_SUBCOMMANDS) {
                    return Verdict.Deny(
                        "dumpsys activity sub-command '${tokens[2]}' not on allow-list: '$segment'"
                    )
                }
                Verdict.Allow
            }
            "window" -> {
                // `dumpsys window` — bare form only. Contains mCurrentFocus for the
                // foreground-package root read. No extra args are accepted (the bare
                // dump is already scoped; extra args risk other sub-systems).
                if (tokens.size != 2) {
                    return Verdict.Deny("dumpsys window takes no arguments: '$segment'")
                }
                Verdict.Allow
            }
            else -> Verdict.Deny("dumpsys service not on allow-list: '$segment'")
        }
    }

    /** `getprop <key>` (read). */
    private fun allowGetprop(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("getprop must not redirect: '$segment'")
        return when (tokens.size) {
            1 -> Verdict.Allow // dump all props (read)
            2 -> if (PROP_KEY.matches(tokens[1])) Verdict.Allow
                else Verdict.Deny("getprop key invalid: '$segment'")
            else -> Verdict.Deny("getprop takes at most one key: '$segment'")
        }
    }

    /** `service list` (read — used by the unlock script's PServer presence check). */
    private fun allowService(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("service must not redirect: '$segment'")
        if (tokens.size == 2 && tokens[1] == "list") return Verdict.Allow
        return Verdict.Deny("only 'service list' is permitted: '$segment'")
    }

    /**
     * `stat -c '<fmt>' <path>` (read metadata). Used by the fan controller to read
     * the Odin config's owner/mode/context. The path must be the Odin config OR a
     * validated sysfs node. (Inside the fan carve-out, the stat lives in its own
     * read command, not the apply script — handled here generically.)
     */
    private fun allowStat(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("stat must not redirect: '$segment'")
        // stat -c <fmt> <path>
        if (tokens.size != 4 || tokens[1] != "-c") {
            return Verdict.Deny("only 'stat -c <fmt> <path>' is permitted: '$segment'")
        }
        val path = tokens[3]
        if (isOdinConfigPath(path)) return Verdict.Allow
        return requireReadablePath(path, segment)
    }

    /**
     * `grep -q '<pat>'` / `grep <pat>` as the RIGHT side of a `cat ... | grep` or
     * `service list | grep`. grep reads stdin only and never touches the FS here;
     * we still forbid any redirect and any file argument that isn't a read path.
     */
    private fun allowGrep(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("grep must not redirect: '$segment'")
        // grep [-q] <pattern>  — pattern is a single quoted token (data, not a path).
        // We only allow the stdin-filter forms (no file operands) to avoid grep
        // being used to read arbitrary files.
        val operands = tokens.drop(1).filter { !it.startsWith("-") }
        if (operands.size > 1) return Verdict.Deny("grep with file operands not permitted: '$segment'")
        return Verdict.Allow
    }

    /** `stop <perf-daemon>` / `start <perf-daemon>` — perf daemons only. */
    private fun allowDaemonControl(tokens: List<String>, segment: String): Verdict {
        if (hasRedirect(segment)) return Verdict.Deny("stop/start must not redirect: '$segment'")
        if (tokens.size != 2) return Verdict.Deny("stop/start takes one daemon: '$segment'")
        if (!PERF_DAEMON.matches(tokens[1])) {
            return Verdict.Deny("stop/start only permitted for perf daemons: '$segment'")
        }
        return Verdict.Allow
    }

    // ── Fan-curve apply script carve-out ──────────────────────────────────────

    /**
     * True when [command] begins with the fan-curve apply script's fingerprint.
     * We then require the FULL strict shape in [validateFanScript]; a partial or
     * mutated match is denied there.
     */
    private fun looksLikeFanScript(command: String): Boolean {
        val c = command.trimStart()
        return c.startsWith("if printf %s ") && c.contains("| base64 -d")
    }

    /**
     * Validate the fan-curve apply script as a single pinned unit.
     *
     * The ONLY accepted shape is the exact structure emitted by
     * [io.github.mayusi.calibratesoc.data.fancurve.FanCurveScript.buildApplyScript]:
     *
     *   if printf %s '<b64>' | base64 -d > '<tmp>' && mv -f '<tmp>' '<config>'
     *      && chmod '<mode>' '<config>' && chown <ref> '<config>'
     *      && { chcon '<ctx>' '<config>' || restorecon '<config>'; }  (or restorecon '<config>')
     *   ; then kill -9 $(pidof com.odin.settings)
     *   ; settings put system fan_mode 3 ; settings put system fan_mode 4
     *   ; else rm -f '<tmp>' ; echo '<marker>' ; exit 1 ; fi
     *
     * The proof of safety: EVERY path the script touches (the redirect target,
     * the mv source+dest, every chmod/chown/chcon/restorecon/rm-f operand) is
     * extracted and must equal the fixed Odin config / tmp path. The only process
     * killed is `$(pidof com.odin.settings)`. The only settings writes are
     * fan_mode 3/4. If ANY token deviates, we deny — there is no general
     * rm/mv/chown hole.
     */
    private fun validateFanScript(command: String): Verdict {
        // Structural guards: no NUL/CR already checked. Disallow path traversal and
        // any reference to a path outside the Odin prefix or /sys anywhere in the blob.
        if (command.contains("..")) return Verdict.Deny("fan script contains path-traversal '..'")

        // Every single-quoted path-like token must be the Odin config or tmp (the
        // b64 blob, mode, context, marker are quoted data — see allow-set below).
        // Extract all single-quoted tokens.
        val quoted = extractSingleQuoted(command)

        // The set of quoted tokens we expect: the base64 blob (opaque), the tmp
        // path, the config path, an octal mode, an owner:group OR --reference target,
        // an SELinux context (optional), and the failure marker. We assert that every
        // quoted token that LOOKS like a filesystem path (starts with '/') is exactly
        // the Odin config or tmp — nothing else.
        for (q in quoted) {
            if (q.startsWith("/")) {
                if (q != ODIN_CONFIG_XML && q != ODIN_CONFIG_TMP && q != ODIN_PREFS_DIR.trimEnd('/') && q != ODIN_PREFS_DIR) {
                    return Verdict.Deny("fan script references a non-Odin path '$q'")
                }
            }
        }

        // The chown may use --reference=<prefsDir>; that reference must be the Odin
        // prefs dir. Validate any --reference target.
        Regex("""--reference=('?)([^'\s]+)\1""").findAll(command).forEach { m ->
            val ref = m.groupValues[2]
            if (ref != ODIN_PREFS_DIR && ref != ODIN_PREFS_DIR.trimEnd('/')) {
                return Verdict.Deny("fan script chown --reference points outside Odin prefs: '$ref'")
            }
        }

        // The ONLY redirect targets allowed are the tmp file (base64 decode) — pinned.
        // Find every `> <target>` and assert it is the tmp path.
        Regex(""">\s*'([^']*)'""").findAll(command).forEach { m ->
            val target = m.groupValues[1]
            if (target != ODIN_CONFIG_TMP) {
                return Verdict.Deny("fan script redirect target is not the Odin tmp file: '$target'")
            }
        }
        // No unquoted redirect targets permitted in the fan script.
        Regex(""">\s*([^'\s]+)""").findAll(command).forEach { m ->
            // Skip the `>` that is part of a quoted target (already handled). An
            // unquoted target here is a deviation.
            return Verdict.Deny("fan script has an unquoted redirect target: '${m.groupValues[1]}'")
        }

        // The only mv is `mv -f '<tmp>' '<config>'`.
        if (!Regex("""mv -f '${Regex.escape(ODIN_CONFIG_TMP)}' '${Regex.escape(ODIN_CONFIG_XML)}'""").containsMatchIn(command)) {
            return Verdict.Deny("fan script mv is not the pinned tmp→config move")
        }

        // The only kill is `kill -9 $(pidof com.odin.settings)`.
        Regex("""kill\s+-9\s+\$\(pidof\s+([^)]+)\)""").findAll(command).forEach { m ->
            if (m.groupValues[1].trim() != ODIN_SETTINGS_PKG) {
                return Verdict.Deny("fan script kills a non-Odin process: '${m.groupValues[1].trim()}'")
            }
        }
        // Any `kill` NOT in the pidof-Odin form is a deviation.
        Regex("""\bkill\b""").findAll(command).forEach {
            val after = command.substring(it.range.first)
            if (!after.startsWith("kill -9 \$(pidof $ODIN_SETTINGS_PKG)")) {
                return Verdict.Deny("fan script contains a kill that is not 'kill -9 \$(pidof $ODIN_SETTINGS_PKG)'")
            }
        }

        // The only rm is `rm -f '<tmp>'`.
        Regex("""\brm\b""").findAll(command).forEach {
            val after = command.substring(it.range.first)
            if (!after.startsWith("rm -f '$ODIN_CONFIG_TMP'")) {
                return Verdict.Deny("fan script contains an rm that is not 'rm -f <odin-tmp>'")
            }
        }

        // The only settings writes are fan_mode 3 and fan_mode 4. Key/value are
        // delimited by whitespace OR a trailing `;` (the script chains with `;`),
        // so the value group must exclude both whitespace and `;`.
        Regex("""settings put system ([^\s;]+) ([^\s;]+)""").findAll(command).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[2]
            if (key != "fan_mode" || (value != "3" && value != "4")) {
                return Verdict.Deny("fan script settings write is not fan_mode 3/4: '$key $value'")
            }
        }

        // No forbidden verbs sneaking in (dd, mkfs, etc.) outside the sanctioned ones.
        for (bad in HARD_DENY_VERBS) {
            if (bad == "rm" || bad == "mv") continue // sanctioned + path-pinned above
            if (containsBareToken(command, bad)) {
                return Verdict.Deny("fan script contains forbidden verb '$bad'")
            }
        }

        return Verdict.Allow
    }

    // ── Path validation (reuses the app's single blocklist) ───────────────────

    /**
     * A path is readable when it is a clean /sys/ or /proc/ node that is NOT on the
     * dangerous-node block list. Mirrors [PServerWriter.validateSysfsPath] but is
     * the read contract (no write). The dangerous-node list is the SAME one
     * [TunableMetadata.isDangerousPath] enforces — one blocklist, no drift.
     */
    private fun requireReadablePath(path: String, segment: String): Verdict {
        val err = validatePath(path, allowProcStat = true)
        return if (err == null) Verdict.Allow else Verdict.Deny("$err (segment: '$segment')")
    }

    /**
     * A writable sysfs target must be a clean /sys/ or /proc/ node NOT on the
     * dangerous block list. Identical contract to [PServerWriter.validateSysfsPath].
     */
    private fun requireWritableSysfsPath(path: String, segment: String): Verdict {
        val err = validatePath(path, allowProcStat = false)
        return if (err == null) Verdict.Allow else Verdict.Deny("$err (segment: '$segment')")
    }

    /**
     * The shared path validator — same rules as [PServerWriter.validateSysfsPath],
     * intentionally re-expressed here so the guard is self-contained and can be
     * unit-tested with no Android deps. Returns null when safe, else an error string.
     *
     * @param allowProcStat when true, the literal /proc/stat read is permitted
     *   (CpuStatSampler reads it). It is otherwise a /proc path like any other.
     */
    private fun validatePath(path: String, allowProcStat: Boolean): String? {
        if (path.isEmpty()) return "empty path"
        // /proc/stat is a sanctioned read.
        if (allowProcStat && path == "/proc/stat") return null
        // Control-character / traversal checks apply to EVERY path family below.
        if (path.contains("..")) return "path contains traversal '..' (got '$path')"
        if (path.any { it.code == 0 }) return "path contains a NUL byte"
        if (path.contains('\n') || path.contains('\r')) return "path contains control characters (got '$path')"
        // Narrow cgroup-boost carve-out: the uclamp/schedtune perf nodes live under
        // /dev/cpuctl and /dev/stune, NOT /sys or /proc. They are legitimate perf
        // levers (sched boost) that PServer-LIVE now writes. We permit ONLY the exact
        // modeled node shapes — never a blanket /dev/ allow (no device files, no /dev/block).
        if (isCgroupBoostNode(path)) return null
        if (!path.startsWith("/sys/") && !path.startsWith("/proc/")) {
            return "path must start with /sys/ or /proc/ (or a modeled cgroup-boost node) (got '$path')"
        }
        // Reuse the ONE dangerous-node block list.
        if (TunableMetadata.isDangerousPath(path)) {
            return "path '$path' is on the dangerous-node block list"
        }
        return null
    }

    /**
     * Exact-shape allow for the cgroup-boost perf nodes (uclamp on cpuctl, schedtune
     * on stune). These are the ONLY non-/sys, non-/proc paths the guard permits a
     * write to — pinned to the exact modeled families so a `/dev/...` write to
     * anything else (block devices, ptmx, etc.) is still denied. Slice names are
     * restricted to `[A-Za-z0-9_-]+` so no traversal/metachar can hide in the slice.
     */
    private val CGROUP_BOOST_NODE = Regex(
        """/dev/cpuctl/[A-Za-z0-9_-]+/cpu\.uclamp\.(min|max)""" +
            """|/dev/stune/[A-Za-z0-9_-]+/schedtune\.(boost|prefer_idle)""",
    )

    internal fun isCgroupBoostNode(path: String): Boolean = CGROUP_BOOST_NODE.matches(path)

    private fun isOdinConfigPath(path: String): Boolean =
        path == ODIN_CONFIG_XML || path == ODIN_CONFIG_TMP

    // ── Lexing helpers ────────────────────────────────────────────────────────

    /**
     * Split a command into top-level segments on `&&`, `||`, `;`, `|`, ignoring
     * any operator that appears inside a single-quoted span (the codebase's only
     * quoting style). Returns the raw segment strings (callers trim).
     */
    internal fun splitSegments(command: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var inSingle = false
        while (i < command.length) {
            val c = command[i]
            if (c == '\'') {
                inSingle = !inSingle
                sb.append(c)
                i++
                continue
            }
            if (!inSingle) {
                // && and ||
                if (c == '&' && i + 1 < command.length && command[i + 1] == '&') {
                    out.add(sb.toString()); sb.setLength(0); i += 2; continue
                }
                if (c == '|' && i + 1 < command.length && command[i + 1] == '|') {
                    out.add(sb.toString()); sb.setLength(0); i += 2; continue
                }
                if (c == '|') { // single pipe
                    out.add(sb.toString()); sb.setLength(0); i += 1; continue
                }
                if (c == ';') {
                    out.add(sb.toString()); sb.setLength(0); i += 1; continue
                }
                // A lone & (background) is never legitimate in our commands.
                if (c == '&') {
                    out.add(sb.toString()); sb.setLength(0); i += 1; continue
                }
            }
            sb.append(c)
            i++
        }
        out.add(sb.toString())
        return out
    }

    /**
     * Tokenize a single segment into whitespace-separated tokens, treating a
     * single-quoted span as ONE token with the quotes stripped. Returns null if a
     * single quote is left unterminated (malformed → caller denies).
     *
     * NOTE: a redirect operator `>`/`>>` and its target are kept as tokens too,
     * but redirect handling is done separately via [redirectTarget]/[hasRedirect]
     * on the raw segment; tokenize is used for the leading-verb + arg checks.
     */
    internal fun tokenize(segment: String): List<String>? {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var inSingle = false
        var started = false
        fun flush() { if (started) { out.add(sb.toString()); sb.setLength(0); started = false } }
        while (i < segment.length) {
            val c = segment[i]
            if (c == '\'') {
                inSingle = !inSingle
                started = true // an empty '' is a real (empty) token
                i++
                continue
            }
            if (!inSingle && c.isWhitespace()) {
                flush()
                i++
                continue
            }
            sb.append(c)
            started = true
            i++
        }
        if (inSingle) return null // unterminated quote
        flush()
        return out
    }

    /**
     * Remove every output-redirect clause (`>tgt`, `>>tgt`, and fd-qualified forms
     * like `2>tgt`, `1>>tgt`) — including any leading fd digit and the target token —
     * from a segment, OUTSIDE single quotes. Used so the verb/arg-count rules see
     * only real arguments. Redirect targets are policed separately via [redirects].
     */
    private fun stripRedirects(segment: String): String {
        val sb = StringBuilder()
        var inSingle = false
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '\'') { inSingle = !inSingle; sb.append(c); i++; continue }
            if (!inSingle && c == '>') {
                // Drop a leading fd digit that we already appended (e.g. the '2' in 2>).
                if (sb.isNotEmpty() && sb.last().isDigit()) {
                    // only if that digit is a standalone fd (preceded by space/start)
                    val k = sb.length - 1
                    val beforeDigit = if (k == 0) ' ' else sb[k - 1]
                    if (beforeDigit.isWhitespace()) sb.deleteCharAt(k)
                }
                var j = i + 1
                if (j < segment.length && segment[j] == '>') j++ // >>
                while (j < segment.length && segment[j].isWhitespace()) j++
                // skip the target token (single-quoted span or bare word)
                if (j < segment.length && segment[j] == '\'') {
                    j++
                    while (j < segment.length && segment[j] != '\'') j++
                    if (j < segment.length) j++ // closing quote
                } else {
                    while (j < segment.length && !segment[j].isWhitespace()) j++
                }
                i = j
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** A single output redirection: which fd, and the (quote-stripped) target. */
    private data class Redirect(val target: String)

    /** The benign sink — a redirect here has no filesystem effect we must police. */
    private const val DEV_NULL = "/dev/null"

    /**
     * Enumerate ALL output redirects (`>`, `>>`, and fd-qualified forms like
     * `2>`, `1>>`) that appear OUTSIDE single quotes. Targets are quote-stripped.
     * A `<` input redirect is not enumerated (it reads, it does not write); an
     * input redirect from a non-sysfs path is still caught because the verb rules
     * never need `<` and any unexpected operator shows up as a stray token. We only
     * police WRITE targets here, which is where the danger is.
     */
    private fun redirects(segment: String): List<Redirect> {
        val out = ArrayList<Redirect>()
        var inSingle = false
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '\'') { inSingle = !inSingle; i++; continue }
            if (!inSingle && c == '>') {
                var j = i + 1
                if (j < segment.length && segment[j] == '>') j++ // >>
                while (j < segment.length && segment[j].isWhitespace()) j++
                if (j < segment.length) {
                    val rest = segment.substring(j)
                    val toks = tokenize(rest)
                    val target = toks?.firstOrNull()
                    if (target != null) out.add(Redirect(target))
                }
                i = j
                continue
            }
            i++
        }
        return out
    }

    /**
     * Output redirects that ACTUALLY write to a filesystem location we must police —
     * i.e. excluding the benign `/dev/null` sink (used by `2>/dev/null` stderr
     * suppression in the fan metadata read command).
     */
    private fun effectiveRedirects(segment: String): List<Redirect> =
        redirects(segment).filter { it.target != DEV_NULL }

    /**
     * True when the segment has a redirect that writes somewhere OTHER than
     * /dev/null. A `2>/dev/null` alone is NOT a redirect we police.
     */
    private fun hasRedirect(segment: String): Boolean = effectiveRedirects(segment).isNotEmpty()

    /**
     * The single effective (non-/dev/null) redirect target, or null when there is
     * none. Used by printf/echo where exactly one sysfs write target is expected.
     */
    private fun redirectTarget(segment: String): String? =
        effectiveRedirects(segment).map { it.target }.let {
            if (it.size == 1) it.first() else null
        }

    /** All single-quoted spans (quotes stripped) found in [command]. */
    private fun extractSingleQuoted(command: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inSingle = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\'') {
                if (inSingle) { out.add(sb.toString()); sb.setLength(0) }
                inSingle = !inSingle
                i++
                continue
            }
            if (inSingle) sb.append(c)
            i++
        }
        return out
    }

    /**
     * True when [token] appears as a standalone word in [command] (surrounded by
     * start/end or whitespace/operators), outside single quotes. Used for the
     * absolute setenforce check and the fan-script forbidden-verb scan, so a
     * substring (e.g. "setenforced" or a path containing "rm") never false-trips
     * and, conversely, a real bare token never slips by.
     */
    private fun containsBareToken(command: String, token: String): Boolean {
        var inSingle = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\'') { inSingle = !inSingle; i++; continue }
            if (!inSingle && command.startsWith(token, i)) {
                val before = if (i == 0) ' ' else command[i - 1]
                val afterIdx = i + token.length
                val after = if (afterIdx >= command.length) ' ' else command[afterIdx]
                if (!before.isLetterOrDigit() && before != '_' && before != '.' && before != '-' &&
                    !after.isLetterOrDigit() && after != '_' && after != '.' && after != '-'
                ) {
                    return true
                }
            }
            i++
        }
        return false
    }
}
