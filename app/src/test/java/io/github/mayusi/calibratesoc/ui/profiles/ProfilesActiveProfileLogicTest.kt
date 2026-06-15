package io.github.mayusi.calibratesoc.ui.profiles

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.tunables.ApplyPathway
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry
import org.junit.Test

/**
 * Unit tests for the pure active-profile matching logic introduced in
 * ProfilesViewModel.  The ViewModel derives [activeProfileId] via
 * [combine] of [store] and [tuneHistoryStore.entries]; this class
 * exercises the matching predicate directly so we can test all
 * edge-cases without needing a full ViewModel / Hilt graph.
 */
class ProfilesActiveProfileLogicTest {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun profile(
        id: String,
        name: String,
        applyOnBoot: Boolean = false,
    ) = UserProfile(
        id = id,
        name = name,
        description = "",
        createdAtMs = 1_000_000L,
        applyOnBoot = applyOnBoot,
    )

    private fun historyEntry(presetName: String) = TuneHistoryEntry(
        appliedAtMs = System.currentTimeMillis(),
        presetName = presetName,
        presetDescription = "",
        pathway = ApplyPathway.DIRECT_ROOT,
    )

    /**
     * Replicates the matching predicate used in ProfilesViewModel.activeProfileId.
     * Returns the matching profile id or null.
     */
    private fun resolveActiveProfileId(
        store: ProfileStore,
        history: List<TuneHistoryEntry>,
    ): String? {
        val lastPresetName = history.firstOrNull()?.presetName?.trim() ?: return null
        return store.profiles.firstOrNull { p ->
            p.name.trim().equals(lastPresetName, ignoreCase = true)
        }?.id
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `returns null when history is empty`() {
        val store = ProfileStore(profiles = listOf(profile("p1", "Gaming")))
        val result = resolveActiveProfileId(store, emptyList())
        assertThat(result).isNull()
    }

    @Test
    fun `returns null when no profile name matches last history entry`() {
        val store = ProfileStore(profiles = listOf(profile("p1", "Gaming")))
        val history = listOf(historyEntry("Battery Saver"))
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isNull()
    }

    @Test
    fun `returns null when profiles list is empty`() {
        val store = ProfileStore(profiles = emptyList())
        val history = listOf(historyEntry("Gaming"))
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isNull()
    }

    @Test
    fun `matches profile by exact name`() {
        val store = ProfileStore(
            profiles = listOf(
                profile("p1", "Gaming"),
                profile("p2", "Battery Saver"),
            ),
        )
        val history = listOf(historyEntry("Gaming"))
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isEqualTo("p1")
    }

    @Test
    fun `match is case-insensitive`() {
        val store = ProfileStore(profiles = listOf(profile("p1", "Gaming")))
        val history = listOf(historyEntry("gaming"))
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isEqualTo("p1")
    }

    @Test
    fun `match trims leading and trailing whitespace`() {
        val store = ProfileStore(profiles = listOf(profile("p1", "Gaming")))
        val history = listOf(historyEntry("  Gaming  "))
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isEqualTo("p1")
    }

    @Test
    fun `uses most-recent history entry (first in list)`() {
        // TuneHistoryStore keeps newest-first. The first entry is the
        // most recent tune. Older entries must be ignored.
        val store = ProfileStore(
            profiles = listOf(
                profile("p1", "Gaming"),
                profile("p2", "Battery Saver"),
            ),
        )
        val history = listOf(
            historyEntry("Battery Saver"), // newest
            historyEntry("Gaming"),         // older
        )
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isEqualTo("p2")
    }

    @Test
    fun `returns null when history preset does not match any profile (history has entries)`() {
        val store = ProfileStore(
            profiles = listOf(
                profile("p1", "Gaming"),
                profile("p2", "Performance"),
            ),
        )
        // The last applied tune was an ad-hoc Tune screen preset, not a saved profile.
        val history = listOf(historyEntry("Custom Tune"))
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isNull()
    }

    @Test
    fun `handles profile names with special characters`() {
        val store = ProfileStore(profiles = listOf(profile("p1", "Retroid Pocket 6 (Max)")))
        val history = listOf(historyEntry("Retroid Pocket 6 (Max)"))
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isEqualTo("p1")
    }

    @Test
    fun `first matching profile wins when multiple profiles share a name`() {
        // Duplicate profile names are disallowed in practice but the logic
        // should still be deterministic: the first profile in the list wins.
        val store = ProfileStore(
            profiles = listOf(
                profile("p1", "Gaming"),
                profile("p2", "Gaming"),
            ),
        )
        val history = listOf(historyEntry("Gaming"))
        val result = resolveActiveProfileId(store, history)
        assertThat(result).isEqualTo("p1")
    }
}
