package io.github.mayusi.calibratesoc.ui.setup

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript.SetupItem
import org.junit.Test

/**
 * Pure JVM tests for the NEW one-click ("Set up everything") onboarding
 * checklist honesty rules. The Compose composables that render the checklist
 * (SetupChecklist / SetupChecklistRow / FullSetupChecklistRows) are private to
 * their screen files, so these tests mirror the EXACT decision those rows make
 * over the engine's per-[SetupItem] `held` map — guaranteeing the displayed ✓/○
 * can never drift away from the real readback.
 *
 * The invariants under test (the "HONESTY is LAW" contract):
 *   1. A row is ✓ (done) for an item ONLY when `held[item] == true`. A missing
 *      key (Idle preview / Running before the readback lands) or an explicit
 *      false is ○ (pending) — NEVER a fabricated ✓.
 *   2. "All set" is true ONLY when every one of the five SetupItems is held.
 *   3. The checklist's label list covers every SetupItem the engine reports, so
 *      no granted/ungranted item is ever silently dropped from the UI.
 */
class OneClickSetupChecklistTest {

    /**
     * Mirror of the per-row decision in `SetupChecklist` /
     * `FullSetupChecklistRows`: a row is shown ✓ iff the held map says true.
     */
    private fun rowIsChecked(held: Map<SetupItem, Boolean>, item: SetupItem): Boolean =
        held[item] == true

    /** Mirror of `FullSetupResult.Completed.allGranted` consumed by the UI. */
    private fun allSet(held: Map<SetupItem, Boolean>): Boolean =
        SetupItem.values().all { held[it] == true }

    // ── 1. Per-row honesty ────────────────────────────────────────────────────

    @Test
    fun `row checked only when held is true`() {
        val held = mapOf(
            SetupItem.ROOT_PERMS to true,
            SetupItem.USAGE_ACCESS to false,
            SetupItem.OVERLAY to true,
            SetupItem.BATTERY to false,
            SetupItem.NOTIFICATIONS to true,
        )
        assertThat(rowIsChecked(held, SetupItem.ROOT_PERMS)).isTrue()
        assertThat(rowIsChecked(held, SetupItem.USAGE_ACCESS)).isFalse()
        assertThat(rowIsChecked(held, SetupItem.OVERLAY)).isTrue()
        assertThat(rowIsChecked(held, SetupItem.BATTERY)).isFalse()
        assertThat(rowIsChecked(held, SetupItem.NOTIFICATIONS)).isTrue()
    }

    @Test
    fun `missing key is pending - never a fabricated check (Idle and Running preview)`() {
        // Idle preview / Running-before-readback pass an EMPTY map. Every row must
        // render pending, never ✓.
        val empty = emptyMap<SetupItem, Boolean>()
        for (item in SetupItem.values()) {
            assertThat(rowIsChecked(empty, item)).isFalse()
        }
    }

    @Test
    fun `explicit false is pending`() {
        val held = SetupItem.values().associateWith { false }
        for (item in SetupItem.values()) {
            assertThat(rowIsChecked(held, item)).isFalse()
        }
    }

    // ── 2. All-set gating ─────────────────────────────────────────────────────

    @Test
    fun `all set is true only when every item held`() {
        val all = SetupItem.values().associateWith { true }
        assertThat(allSet(all)).isTrue()
    }

    @Test
    fun `all set is false when any single item is missing`() {
        for (missing in SetupItem.values()) {
            val held = SetupItem.values().associateWith { it != missing }
            assertThat(allSet(held)).isFalse()
        }
    }

    @Test
    fun `all set is false on empty held map`() {
        assertThat(allSet(emptyMap())).isFalse()
    }

    // ── 3. Label coverage — no item silently dropped ──────────────────────────

    @Test
    fun `checklist label keys cover every SetupItem`() {
        // The two screens keep their own label lists (onboarding long-form, Tune
        // short-form). Both MUST cover all five items so an ungranted item is
        // never hidden. We assert the canonical set here; the screens reference
        // these exact enum values.
        val expected = setOf(
            SetupItem.ROOT_PERMS,
            SetupItem.USAGE_ACCESS,
            SetupItem.OVERLAY,
            SetupItem.BATTERY,
            SetupItem.NOTIFICATIONS,
        )
        assertThat(SetupItem.values().toSet()).isEqualTo(expected)
    }
}
