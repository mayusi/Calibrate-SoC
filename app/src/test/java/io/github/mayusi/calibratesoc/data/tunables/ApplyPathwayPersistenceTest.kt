package io.github.mayusi.calibratesoc.data.tunables

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Guards the CHANGE 3 trap: [ApplyPathway.AYN_SETTINGS_KEY] is a SEPARATE,
 * PERSISTED serialized enum in the tune-history store — distinct from the
 * PrivilegeTier.AYN_SETTINGS that was renamed to VENDOR_SETTINGS. Renaming this
 * one would break deserialization of every tune-history file already on disk, so
 * it was deliberately LEFT INTACT.
 *
 * These tests fail loudly if anyone later renames the persisted enum constant.
 */
class ApplyPathwayPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ApplyPathway still defines the persisted AYN_SETTINGS_KEY constant`() {
        // entries() / name lookups must keep working for stored history.
        assertThat(ApplyPathway.entries.map { it.name }).contains("AYN_SETTINGS_KEY")
        assertThat(ApplyPathway.valueOf("AYN_SETTINGS_KEY")).isEqualTo(ApplyPathway.AYN_SETTINGS_KEY)
    }

    @Test
    fun `TuneHistoryEntry round-trips AYN_SETTINGS_KEY through JSON`() {
        val entry = TuneHistoryEntry(
            appliedAtMs = 1_700_000_000_000L,
            presetName = "Vendor preset",
            presetDescription = "Applied via vendor Settings key",
            pathway = ApplyPathway.AYN_SETTINGS_KEY,
        )
        val encoded = json.encodeToString(entry)
        // The serialized form must contain the legacy constant name verbatim.
        assertThat(encoded).contains("AYN_SETTINGS_KEY")

        val decoded = json.decodeFromString<TuneHistoryEntry>(encoded)
        assertThat(decoded.pathway).isEqualTo(ApplyPathway.AYN_SETTINGS_KEY)
    }

    @Test
    fun `an OLD on-disk history file with AYN_SETTINGS_KEY still deserializes`() {
        // Simulates a tune-history record written by a PRIOR app version. If the
        // enum constant had been renamed, this would throw — breaking stored history.
        val legacyJson = """
            {
              "appliedAtMs": 1690000000000,
              "presetName": "Legacy vendor tune",
              "presetDescription": "old record",
              "pathway": "AYN_SETTINGS_KEY"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<TuneHistoryEntry>(legacyJson)
        assertThat(decoded.pathway).isEqualTo(ApplyPathway.AYN_SETTINGS_KEY)
        assertThat(decoded.presetName).isEqualTo("Legacy vendor tune")
    }
}
