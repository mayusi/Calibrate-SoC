package io.github.mayusi.calibratesoc.ui.profiles

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [GameTuneViewModel.isDeviceMismatch] — the real
 * device-targeting predicate that backs the import-time "Device mismatch"
 * warning (BUG 3 fix; previously hardcoded false).
 *
 * Mirrors the semantics of
 * [io.github.mayusi.calibratesoc.data.profiles.PresetSafetyGate] Gate 1:
 *  - untargeted tune (null/empty) → never a mismatch (applies anywhere)
 *  - targeted tune + matching device key → no mismatch
 *  - targeted tune + foreign device key → mismatch
 *  - targeted tune + unknown (null) device key → mismatch (fail-safe)
 */
class GameTuneDeviceMismatchTest {

    // ── Untargeted tunes never mismatch ───────────────────────────────────────

    @Test
    fun `null targets is never a mismatch`() {
        assertThat(GameTuneViewModel.isDeviceMismatch(null, "odin3")).isFalse()
        assertThat(GameTuneViewModel.isDeviceMismatch(null, null)).isFalse()
    }

    @Test
    fun `empty targets is never a mismatch`() {
        assertThat(GameTuneViewModel.isDeviceMismatch(emptyList(), "odin3")).isFalse()
        assertThat(GameTuneViewModel.isDeviceMismatch(emptyList(), null)).isFalse()
    }

    // ── Matching device key → no mismatch ─────────────────────────────────────

    @Test
    fun `matching device key is not a mismatch`() {
        assertThat(GameTuneViewModel.isDeviceMismatch(listOf("odin3"), "odin3")).isFalse()
    }

    @Test
    fun `device key present in multi-target list is not a mismatch`() {
        assertThat(
            GameTuneViewModel.isDeviceMismatch(listOf("rp6", "odin3", "ayaneo"), "odin3"),
        ).isFalse()
    }

    // ── Foreign device key → mismatch ─────────────────────────────────────────

    @Test
    fun `foreign device key is a mismatch`() {
        // A tune built for an Odin 3 imported onto an RP6.
        assertThat(GameTuneViewModel.isDeviceMismatch(listOf("odin3"), "rp6")).isTrue()
    }

    @Test
    fun `device key absent from multi-target list is a mismatch`() {
        assertThat(
            GameTuneViewModel.isDeviceMismatch(listOf("rp6", "ayaneo"), "odin3"),
        ).isTrue()
    }

    // ── Unknown current device key → mismatch when targeted (fail-safe) ───────

    @Test
    fun `targeted tune with unknown current device key is a mismatch`() {
        // We cannot prove an unknown device is in the target set, so fail safe —
        // exactly what PresetSafetyGate Gate 1 does (currentKey == null → reject).
        assertThat(GameTuneViewModel.isDeviceMismatch(listOf("odin3"), null)).isTrue()
    }
}
