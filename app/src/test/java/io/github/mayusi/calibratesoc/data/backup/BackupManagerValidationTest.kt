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
    ) = UserProfile(
        id = "test_id",
        name = name,
        description = description,
        cpuPolicyGovernor = cpuGov,
        gpuGovernor = gpuGov,
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

    // ── Name / description injection ─────────────────────────────────────────

    @Test
    fun `name with single quote is rejected`() {
        val err = manager.validateProfile(profile(name = "Mike's Tune"))
        assertThat(err).isNotNull()
        assertThat(err).contains("name")
    }

    @Test
    fun `name with shell injection payload is rejected`() {
        val err = manager.validateProfile(profile(name = "foo' ; rm -rf /data ; echo '"))
        assertThat(err).isNotNull()
        assertThat(err).contains("name")
    }

    @Test
    fun `name with dollar sign is rejected`() {
        val err = manager.validateProfile(profile(name = "Tune \$HOME"))
        assertThat(err).isNotNull()
    }

    @Test
    fun `name with semicolon is rejected`() {
        val err = manager.validateProfile(profile(name = "Tune; reboot"))
        assertThat(err).isNotNull()
    }

    @Test
    fun `name with backtick is rejected`() {
        val err = manager.validateProfile(profile(name = "Tune`id`"))
        assertThat(err).isNotNull()
    }

    @Test
    fun `blank name is rejected`() {
        val err = manager.validateProfile(profile(name = "   "))
        assertThat(err).isNotNull()
        assertThat(err).contains("blank")
    }

    @Test
    fun `description with shell metacharacter is rejected`() {
        val err = manager.validateProfile(profile(description = "nice tune; echo bad"))
        assertThat(err).isNotNull()
        assertThat(err).contains("description")
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

    // ── Schema version constant ──────────────────────────────────────────────

    @Test
    fun `SUPPORTED_SCHEMA_VERSION is 1`() {
        assertThat(BackupManager.SUPPORTED_SCHEMA_VERSION).isEqualTo(1)
    }
}
