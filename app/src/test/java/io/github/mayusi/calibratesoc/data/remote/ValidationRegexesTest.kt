package io.github.mayusi.calibratesoc.data.remote

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Guard tests for [ValidationRegexes]. Verifies that each regex accepts its
 * intended inputs and rejects the known-bad patterns. This is a smoke-test for
 * the shared constants — the full security validation logic is exercised in
 * [RemoteContentRepositoryTest] and [BackupManagerValidationTest].
 */
class ValidationRegexesTest {

    // --- SHELL_META ---

    @Test
    fun `SHELL_META matches single quote`() {
        assertThat(ValidationRegexes.SHELL_META.containsMatchIn("evil'injection")).isTrue()
    }

    @Test
    fun `SHELL_META matches backtick`() {
        assertThat(ValidationRegexes.SHELL_META.containsMatchIn("foo`id`")).isTrue()
    }

    @Test
    fun `SHELL_META matches dollar sign`() {
        assertThat(ValidationRegexes.SHELL_META.containsMatchIn("\$HOME")).isTrue()
    }

    @Test
    fun `SHELL_META matches semicolon`() {
        assertThat(ValidationRegexes.SHELL_META.containsMatchIn("a;b")).isTrue()
    }

    @Test
    fun `SHELL_META matches control char`() {
        assertThat(ValidationRegexes.SHELL_META.containsMatchIn("abc")).isTrue()
    }

    @Test
    fun `SHELL_META does not match plain alphanumeric`() {
        assertThat(ValidationRegexes.SHELL_META.containsMatchIn("schedutil")).isFalse()
    }

    @Test
    fun `SHELL_META does not match hyphen or underscore`() {
        assertThat(ValidationRegexes.SHELL_META.containsMatchIn("simple-ondemand")).isFalse()
        assertThat(ValidationRegexes.SHELL_META.containsMatchIn("simple_ondemand")).isFalse()
    }

    // --- DISPLAY_UNSAFE ---

    @Test
    fun `DISPLAY_UNSAFE matches newline`() {
        assertThat(ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn("foo\nbar")).isTrue()
    }

    @Test
    fun `DISPLAY_UNSAFE matches carriage return`() {
        assertThat(ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn("foo\rbar")).isTrue()
    }

    @Test
    fun `DISPLAY_UNSAFE matches control char`() {
        assertThat(ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn("bell")).isTrue()
    }

    @Test
    fun `DISPLAY_UNSAFE does not match human punctuation`() {
        // Shell metacharacters are allowed in display-only fields.
        assertThat(ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn("RP6 — PS2 / GameCube (Sustained) & Cool")).isFalse()
        assertThat(ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn("foo; rm -rf /")).isFalse()
        assertThat(ValidationRegexes.DISPLAY_UNSAFE.containsMatchIn("Mike's Tune")).isFalse()
    }

    // --- GOVERNOR_INVALID ---

    @Test
    fun `GOVERNOR_INVALID matches whitespace`() {
        assertThat(ValidationRegexes.GOVERNOR_INVALID.containsMatchIn("sched util")).isTrue()
    }

    @Test
    fun `GOVERNOR_INVALID matches slash`() {
        assertThat(ValidationRegexes.GOVERNOR_INVALID.containsMatchIn("sched/util")).isTrue()
    }

    @Test
    fun `GOVERNOR_INVALID matches semicolon`() {
        assertThat(ValidationRegexes.GOVERNOR_INVALID.containsMatchIn("schedutil;evil")).isTrue()
    }

    @Test
    fun `GOVERNOR_INVALID does not match valid governor names`() {
        for (name in listOf("schedutil", "performance", "powersave", "simple_ondemand", "msm-adreno-tz")) {
            assertWithMessage("Governor '$name' should be valid")
                .that(ValidationRegexes.GOVERNOR_INVALID.containsMatchIn(name))
                .isFalse()
        }
    }

    // --- KEY_PATTERN ---

    @Test
    fun `KEY_PATTERN matches valid lowercase key`() {
        assertThat(ValidationRegexes.KEY_PATTERN.matches("retroid_pocket6")).isTrue()
    }

    @Test
    fun `KEY_PATTERN rejects uppercase`() {
        assertThat(ValidationRegexes.KEY_PATTERN.matches("AYN_ODIN")).isFalse()
    }

    @Test
    fun `KEY_PATTERN rejects space`() {
        assertThat(ValidationRegexes.KEY_PATTERN.matches("my device")).isFalse()
    }

    @Test
    fun `KEY_PATTERN rejects empty string`() {
        assertThat(ValidationRegexes.KEY_PATTERN.matches("")).isFalse()
    }

    // --- DAEMON_INVALID ---

    @Test
    fun `DAEMON_INVALID matches pipe`() {
        assertThat(ValidationRegexes.DAEMON_INVALID.containsMatchIn("perfd|evil")).isTrue()
    }

    @Test
    fun `DAEMON_INVALID matches space`() {
        assertThat(ValidationRegexes.DAEMON_INVALID.containsMatchIn("per fd")).isTrue()
    }

    @Test
    fun `DAEMON_INVALID does not match valid daemon names`() {
        for (name in listOf("perfd", "vendor.perf-hal-1-0", "mpdecision")) {
            assertWithMessage("Daemon '$name' should be valid")
                .that(ValidationRegexes.DAEMON_INVALID.containsMatchIn(name))
                .isFalse()
        }
    }

    // --- aliases in RemoteContentValidator are consistent ---

    @Test
    fun `RemoteContentValidator aliases match ValidationRegexes`() {
        // Spot-check that the public aliases still compile and return the right pattern.
        assertThat(RemoteContentValidator.SHELL_META.pattern)
            .isEqualTo(ValidationRegexes.SHELL_META.pattern)
        assertThat(RemoteContentValidator.DISPLAY_UNSAFE.pattern)
            .isEqualTo(ValidationRegexes.DISPLAY_UNSAFE.pattern)
        assertThat(RemoteContentValidator.GOVERNOR_INVALID.pattern)
            .isEqualTo(ValidationRegexes.GOVERNOR_INVALID.pattern)
        assertThat(RemoteContentValidator.KEY_PATTERN.pattern)
            .isEqualTo(ValidationRegexes.KEY_PATTERN.pattern)
        assertThat(RemoteContentValidator.DAEMON_INVALID.pattern)
            .isEqualTo(ValidationRegexes.DAEMON_INVALID.pattern)
    }
}
