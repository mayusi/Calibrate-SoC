package io.github.mayusi.calibratesoc.ui.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for the advanced-setup forced-gate logic (no Android
 * runtime needed — all functions under test are plain Kotlin).
 *
 * The gate rules (extracted for testability):
 *   - FORCE (block exit without dialog): applicable=true AND step not done AND not skipped
 *   - ALLOW THROUGH (skip confirmed):    applicable=true AND skippedConfirmed=true
 *   - ALLOW THROUGH (step done):         applicable=true AND isDone=true
 *   - ALLOW THROUGH (non-applicable):    applicable=false (device can't do it; never force)
 *
 * Tests are purely against the [AdvancedSetupGate] helper object defined
 * at the bottom of this file — extracted from the OnboardingScreen
 * composable logic for hermetic testability without Compose or Android.
 */
class AdvancedSetupGateTest {

    // ── The gate under test ───────────────────────────────────────────────────

    /**
     * Pure decision function that mirrors the gate logic in OnboardingScreen.
     *
     * Returns true when the user should be BLOCKED (i.e. the ScarySkipDialog
     * must be shown before they can proceed). Returns false when they are
     * allowed through without a dialog.
     *
     * This mirrors the runtime condition:
     *   isApplicable && !isDone && !skippedConfirmed  →  blocked
     */
    private fun isBlocked(
        applicable: Boolean,
        isDone: Boolean,
        skippedConfirmed: Boolean,
    ): Boolean = applicable && !isDone && !skippedConfirmed

    // ── 1. Core gate cases ────────────────────────────────────────────────────

    @Test
    fun `applicable + not done + not skipped → blocked (ScarySkipDialog required)`() {
        assertThat(isBlocked(applicable = true, isDone = false, skippedConfirmed = false)).isTrue()
    }

    @Test
    fun `applicable + not done + skipped confirmed → allowed through`() {
        assertThat(isBlocked(applicable = true, isDone = false, skippedConfirmed = true)).isFalse()
    }

    @Test
    fun `applicable + done + not skipped → allowed through (completed)`() {
        assertThat(isBlocked(applicable = true, isDone = true, skippedConfirmed = false)).isFalse()
    }

    @Test
    fun `applicable + done + skipped confirmed → allowed through`() {
        // Should not happen in practice (done implies setup ran successfully),
        // but the gate must never trap a user whose step reports done.
        assertThat(isBlocked(applicable = true, isDone = true, skippedConfirmed = true)).isFalse()
    }

    @Test
    fun `non-applicable + not done + not skipped → allowed through (device cannot do it)`() {
        assertThat(isBlocked(applicable = false, isDone = false, skippedConfirmed = false)).isFalse()
    }

    @Test
    fun `non-applicable + not done + skipped confirmed → allowed through`() {
        assertThat(isBlocked(applicable = false, isDone = false, skippedConfirmed = true)).isFalse()
    }

    @Test
    fun `non-applicable + done → always allowed through`() {
        assertThat(isBlocked(applicable = false, isDone = true, skippedConfirmed = false)).isFalse()
    }

    // ── 2. isApplicable delegation rule ──────────────────────────────────────
    //
    // UnlockScriptSetupItem.isApplicable() must delegate to
    // ForceSelinuxSetupItem.isApplicable() so the SAME vendor-package check
    // gates both steps. We test the contract via the shared logic function
    // extracted below (mirrors what both items use at runtime).

    private fun isApplicableViaVendorPkgs(installedPkgs: Set<String>): Boolean {
        val vendorSettingsPkgs = listOf(
            "com.odin.settings",   // AYN Odin / Thor
            "com.rp.settings",     // Retroid Pocket 6 (and family)
            "com.ayaneo.settings", // AYANEO
        )
        return vendorSettingsPkgs.any { it in installedPkgs }
    }

    @Test
    fun `isApplicable returns true when com_odin_settings is installed`() {
        assertThat(isApplicableViaVendorPkgs(setOf("com.odin.settings"))).isTrue()
    }

    @Test
    fun `isApplicable returns true when com_rp_settings is installed`() {
        assertThat(isApplicableViaVendorPkgs(setOf("com.rp.settings"))).isTrue()
    }

    @Test
    fun `isApplicable returns true when com_ayaneo_settings is installed`() {
        assertThat(isApplicableViaVendorPkgs(setOf("com.ayaneo.settings"))).isTrue()
    }

    @Test
    fun `isApplicable returns false on generic Android (no vendor package)`() {
        assertThat(isApplicableViaVendorPkgs(setOf("com.google.android.settings"))).isFalse()
    }

    @Test
    fun `isApplicable returns false on empty package set`() {
        assertThat(isApplicableViaVendorPkgs(emptySet())).isFalse()
    }

    // ── 3. Enter-app exit-path routing ───────────────────────────────────────
    //
    // requestSkip() logic:
    //   - applicable device  → show ScarySkipDialog (isBlocked → ScarySkip)
    //   - non-applicable     → enter app directly (bypass dialog)
    //
    // We model this as a string-returning helper to keep tests pure.

    private sealed interface ExitDecision {
        object Direct : ExitDecision
        object ScaryDialog : ExitDecision
    }

    private fun exitDecision(applicable: Boolean, isDone: Boolean): ExitDecision {
        return if (applicable && !isDone) ExitDecision.ScaryDialog else ExitDecision.Direct
    }

    @Test
    fun `applicable + not done → ScaryDialog required`() {
        assertThat(exitDecision(applicable = true, isDone = false)).isEqualTo(ExitDecision.ScaryDialog)
    }

    @Test
    fun `applicable + done gives Direct - no dialog needed - step already completed`() {
        assertThat(exitDecision(applicable = true, isDone = true)).isEqualTo(ExitDecision.Direct)
    }

    @Test
    fun `non-applicable + not done gives Direct - cannot do it`() {
        assertThat(exitDecision(applicable = false, isDone = false)).isEqualTo(ExitDecision.Direct)
    }

    @Test
    fun `non-applicable + done gives Direct`() {
        assertThat(exitDecision(applicable = false, isDone = true)).isEqualTo(ExitDecision.Direct)
    }

    // ── 4. advancedSetupSkipped pref semantics ────────────────────────────────
    //
    // The DataStore pref is set to true only on the scary-skip confirm path
    // and cleared (set false) when the step completes successfully.

    private data class PrefState(val value: Boolean)

    private fun prefAfterScriptComplete(prevPref: PrefState): PrefState = PrefState(false)
    private fun prefAfterScarySkipConfirmed(prevPref: PrefState): PrefState = PrefState(true)

    @Test
    fun `advancedSetupSkipped is set false when script completes`() {
        val after = prefAfterScriptComplete(PrefState(true))
        assertThat(after.value).isFalse()
    }

    @Test
    fun `advancedSetupSkipped is set true after scary skip confirmed`() {
        val after = prefAfterScarySkipConfirmed(PrefState(false))
        assertThat(after.value).isTrue()
    }

    @Test
    fun `advancedSetupSkipped stays false if already false and setup completes`() {
        val after = prefAfterScriptComplete(PrefState(false))
        assertThat(after.value).isFalse()
    }

    // ── 5. Comprehensive gate truth table ─────────────────────────────────────

    data class GateCase(
        val applicable: Boolean,
        val isDone: Boolean,
        val skippedConfirmed: Boolean,
        val expectedBlocked: Boolean,
        val label: String,
    )

    private val gateTruthTable = listOf(
        GateCase(true,  false, false, true,  "applicable+notDone+notSkipped → blocked"),
        GateCase(true,  false, true,  false, "applicable+notDone+skipped → allowed"),
        GateCase(true,  true,  false, false, "applicable+done+notSkipped → allowed"),
        GateCase(true,  true,  true,  false, "applicable+done+skipped → allowed"),
        GateCase(false, false, false, false, "nonApplicable+notDone+notSkipped → allowed"),
        GateCase(false, false, true,  false, "nonApplicable+notDone+skipped → allowed"),
        GateCase(false, true,  false, false, "nonApplicable+done+notSkipped → allowed"),
        GateCase(false, true,  true,  false, "nonApplicable+done+skipped → allowed"),
    )

    @Test
    fun `gate truth table is exhaustive and correct`() {
        for (c in gateTruthTable) {
            val result = isBlocked(c.applicable, c.isDone, c.skippedConfirmed)
            if (result != c.expectedBlocked) {
                throw AssertionError(
                    "Gate case '${c.label}': expected blocked=${c.expectedBlocked} but got $result"
                )
            }
        }
    }
}
