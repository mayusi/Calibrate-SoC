package io.github.mayusi.calibratesoc.data.backup

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.mockk.mockk
import org.junit.Test

/**
 * Unit tests for [BackupManager.validateProfile] — the import-time guard that
 * prevents malformed or adversarially crafted backup files from feeding shell
 * metacharacters into the script generator.
 *
 * [BackupManager] requires several injected dependencies (ProfileRepository,
 * BenchRepository, etc.) that are heavy to construct. We use mockk to satisfy
 * the constructor and then call the pure [BackupManager.validateProfile] method
 * directly.
 */
class BackupManagerValidationTest {

    // validateProfile is pure logic — build a minimal BackupManager with mocked
    // dependencies; the coroutine-backed methods are never invoked here.
    private val manager = BackupManager(
        profileRepo = mockk(),
        benchRepo = mockk(),
        tuneHistoryStore = mockk(),
        json = mockk(),
    )

    private fun profile(
        name: String = "Balanced",
        description: String = "Nice tune",
        cpuGov: Map<Int, String> = emptyMap(),
        gpuGov: String? = null,
        extraSysfs: Map<String, String> = emptyMap(),
    ) = UserProfile(
        id = "test_id",
        name = name,
        description = description,
        cpuPolicyGovernor = cpuGov,
        gpuGovernor = gpuGov,
        extraSysfs = extraSysfs,
        createdAtMs = 0L,
    )

    // ── Happy-path ───────────────────────────────────────────────────────────

    @Test
    fun `normal profile with plain name passes validation`() {
        assertThat(manager.validateProfile(profile(name = "Performance"))).isNull()
    }

    @Test
    fun `profile with alphanumeric name and governor passes`() {
        assertThat(
            manager.validateProfile(
                profile(
                    name = "Battery Saver 2024",
                    cpuGov = mapOf(0 to "schedutil", 4 to "performance"),
                    gpuGov = "simple_ondemand",
                ),
            ),
        ).isNull()
    }

    // ── Name / description: DISPLAY-only fields ──────────────────────────────
    //
    // name + description never reach a shell un-escaped — the script generator
    // commentSafe/shellSingleQuote-escapes everything it emits. So human
    // punctuation (including shell metacharacters) is LEGITIMATE in a display
    // name and must pass; only ASCII control characters + line breaks (which
    // could corrupt a single-line UI label or a script comment) are rejected.
    // The real injection guard lives at the script-generation layer (tested in
    // AynScriptGeneratorTest) and on the governor fields (below), not here.

    @Test
    fun `name with apostrophe passes (display field, escaped downstream)`() {
        assertThat(manager.validateProfile(profile(name = "Mike's Tune"))).isNull()
    }

    @Test
    fun `name with human punctuation passes`() {
        // Real preset names contain — / ( ) & — these are display-only and safe.
        assertThat(
            manager.validateProfile(profile(name = "RP6 — PS2 / GameCube (Sustained) & Cool")),
        ).isNull()
    }

    @Test
    fun `name with shell metacharacters passes (escaped at script layer)`() {
        // A name like "Tune; reboot" or "foo' ; rm -rf /data" is harmless as a
        // display label and is single-quote-escaped if it ever reaches a script.
        // Defence is at the emit layer, not import-time, for display fields.
        assertThat(manager.validateProfile(profile(name = "foo' ; rm -rf /data ; echo '"))).isNull()
        assertThat(manager.validateProfile(profile(name = "Tune \$HOME"))).isNull()
        assertThat(manager.validateProfile(profile(name = "Tune; reboot"))).isNull()
        assertThat(manager.validateProfile(profile(name = "Tune`id`"))).isNull()
    }

    @Test
    fun `name with newline is rejected`() {
        val err = manager.validateProfile(profile(name = "Tune\nreboot"))
        assertThat(err).isNotNull()
        assertThat(err).contains("name")
    }

    @Test
    fun `blank name is rejected`() {
        val err = manager.validateProfile(profile(name = "   "))
        assertThat(err).isNotNull()
        assertThat(err).contains("blank")
    }

    @Test
    fun `description with shell metacharacter passes (display field)`() {
        // Same reasoning as name — display-only, escaped downstream.
        assertThat(manager.validateProfile(profile(description = "nice tune; echo bad"))).isNull()
    }

    @Test
    fun `description with control character is rejected`() {
        val err = manager.validateProfile(profile(description = "tunebell"))
        assertThat(err).isNotNull()
        assertThat(err).contains("description")
    }

    // ── Governor injection ───────────────────────────────────────────────────

    @Test
    fun `cpu governor with single quote is rejected`() {
        val err = manager.validateProfile(profile(cpuGov = mapOf(0 to "schedutil'")))
        assertThat(err).isNotNull()
        assertThat(err).contains("cpuPolicyGovernor")
    }

    @Test
    fun `cpu governor with space is rejected`() {
        val err = manager.validateProfile(profile(cpuGov = mapOf(0 to "sched util")))
        assertThat(err).isNotNull()
        assertThat(err).contains("cpuPolicyGovernor")
    }

    @Test
    fun `cpu governor with slash is rejected`() {
        val err = manager.validateProfile(profile(cpuGov = mapOf(0 to "sched/util")))
        assertThat(err).isNotNull()
        assertThat(err).contains("cpuPolicyGovernor")
    }

    @Test
    fun `cpu governor with injection payload is rejected`() {
        val err = manager.validateProfile(
            profile(cpuGov = mapOf(0 to "schedutil'; cat /data/data/io.github.mayusi; echo '")),
        )
        assertThat(err).isNotNull()
        assertThat(err).contains("cpuPolicyGovernor")
    }

    @Test
    fun `gpu governor with single quote is rejected`() {
        val err = manager.validateProfile(profile(gpuGov = "simple_ondemand'"))
        assertThat(err).isNotNull()
        assertThat(err).contains("gpuGovernor")
    }

    @Test
    fun `null gpu governor is always valid`() {
        assertThat(manager.validateProfile(profile(gpuGov = null))).isNull()
    }

    // ── extraSysfs validation (defence-in-depth at the import boundary) ───────

    @Test
    fun `profile with valid extraSysfs path passes`() {
        val err = manager.validateProfile(
            profile(extraSysfs = mapOf("/sys/class/devfreq/gpu/min_freq" to "300000000")),
        )
        assertThat(err).isNull()
    }

    @Test
    fun `extraSysfs path with shell metacharacter is rejected`() {
        val err = manager.validateProfile(
            profile(extraSysfs = mapOf("/sys/x`reboot`" to "1")),
        )
        assertThat(err).isNotNull()
        assertThat(err).contains("extraSysfs")
    }

    @Test
    fun `extraSysfs path outside sys or proc is rejected`() {
        val err = manager.validateProfile(
            profile(extraSysfs = mapOf("/data/local/tmp/evil" to "1")),
        )
        assertThat(err).isNotNull()
        assertThat(err).contains("extraSysfs")
    }

    // ── SEC-3: extraSysfs VALUE shell-meta rejection (mirror the OTA path) ─────
    //
    // For an UNKNOWN path, TunableMetadata.forId returns RAW_STRING whose
    // validate() returns null — NO value check. Before SEC-3 a crafted backup
    // could smuggle "0; reboot" into a value. validateProfile now rejects shell
    // metacharacters + control chars in the value, exactly like the OTA
    // RemoteContentValidator.validatePreset path.

    @Test
    fun `extraSysfs value with shell metacharacter on unknown path is rejected`() {
        // The path is a valid, UNKNOWN sysfs node (RAW_STRING metadata → no value
        // kind constraint), so only the new SHELL_META check can catch this.
        val err = manager.validateProfile(
            profile(extraSysfs = mapOf("/sys/devices/platform/vendor_knob/level" to "0; reboot")),
        )
        assertThat(err).isNotNull()
        assertThat(err).contains("extraSysfs")
        assertThat(err).contains("disallowed characters")
    }

    @Test
    fun `extraSysfs value with backtick on unknown path is rejected`() {
        val err = manager.validateProfile(
            profile(extraSysfs = mapOf("/sys/devices/platform/vendor_knob/level" to "1`id`")),
        )
        assertThat(err).isNotNull()
        assertThat(err).contains("disallowed characters")
    }

    @Test
    fun `extraSysfs value with control char on unknown path is rejected`() {
        val err = manager.validateProfile(
            profile(extraSysfs = mapOf("/sys/devices/platform/vendor_knob/level" to "1\u0007")),
        )
        assertThat(err).isNotNull()
        assertThat(err).contains("disallowed characters")
    }

    @Test
    fun `extraSysfs plain numeric value on unknown path still passes`() {
        // A clean value on an unknown path must NOT be rejected by the new check.
        val err = manager.validateProfile(
            profile(extraSysfs = mapOf("/sys/devices/platform/vendor_knob/level" to "42")),
        )
        assertThat(err).isNull()
    }

    // ── Schema version constant ──────────────────────────────────────────────

    @Test
    fun `SUPPORTED_SCHEMA_VERSION is 1`() {
        assertThat(BackupManager.SUPPORTED_SCHEMA_VERSION).isEqualTo(1)
    }
}
