package io.github.mayusi.calibratesoc.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileNameUtilsTest {

    // --- happy path ---

    @Test
    fun `plain alphanumeric name is returned unchanged`() {
        assertThat(toSafeFilename("MyPreset", "fallback")).isEqualTo("MyPreset")
    }

    @Test
    fun `dots underscores and hyphens are preserved`() {
        assertThat(toSafeFilename("my_preset-v1.0", "fallback")).isEqualTo("my_preset-v1.0")
    }

    @Test
    fun `space is replaced by underscore`() {
        assertThat(toSafeFilename("My Preset", "fallback")).isEqualTo("My_Preset")
    }

    @Test
    fun `run of hostile chars becomes single underscore`() {
        // "RP6 — PS2 / GameCube (Sustained)" → "RP6___PS2___GameCube__Sustained_" after replace,
        // then trim → "RP6___PS2___GameCube__Sustained" but replace replaces RUNS so:
        // non-[A-Za-z0-9._-] chars: ' ', '—', ' ', '/', ' ', '(', ')', each run → '_'
        val result = toSafeFilename("RP6 — PS2 / GameCube (Sustained)", "fallback")
        // Must not contain any hostile char
        assertThat(result).matches("[A-Za-z0-9._-]+")
        // Must not start or end with underscore
        assertThat(result).doesNotMatch("^_.*|.*_$")
    }

    @Test
    fun `shell metacharacters are replaced`() {
        val result = toSafeFilename("foo; rm -rf /", "fallback")
        assertThat(result).matches("[A-Za-z0-9._-]+")
    }

    @Test
    fun `unicode is replaced`() {
        val result = toSafeFilename("Préset", "fallback")
        assertThat(result).matches("[A-Za-z0-9._-]+")
    }

    // --- blank / empty fallback ---

    @Test
    fun `blank string returns fallback`() {
        assertThat(toSafeFilename("   ", "preset")).isEqualTo("preset")
    }

    @Test
    fun `empty string returns fallback`() {
        assertThat(toSafeFilename("", "unknown")).isEqualTo("unknown")
    }

    @Test
    fun `string of only hostile chars returns fallback`() {
        // After replace → "____", trim('_') → "", ifBlank → fallback
        assertThat(toSafeFilename("!@#$%^&*()", "fallback")).isEqualTo("fallback")
    }

    @Test
    fun `different fallback values are respected`() {
        assertThat(toSafeFilename("", "unknown")).isEqualTo("unknown")
        assertThat(toSafeFilename("", "preset")).isEqualTo("preset")
    }

    // --- trim behaviour ---

    @Test
    fun `leading and trailing hostile chars are trimmed`() {
        // " Preset " → "_Preset_" → trim → "Preset"
        val result = toSafeFilename(" Preset ", "fallback")
        assertThat(result).isEqualTo("Preset")
    }

    // --- model name edge cases (DeviceReportExporter usage) ---

    @Test
    fun `device model with spaces and slashes produces safe filename`() {
        val result = toSafeFilename("Retroid Pocket 6/Pro", "unknown")
        assertThat(result).matches("[A-Za-z0-9._-]+")
        assertThat(result).doesNotMatch("^_.*|.*_$")
    }
}
