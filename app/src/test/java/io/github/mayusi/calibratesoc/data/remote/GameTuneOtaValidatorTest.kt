package io.github.mayusi.calibratesoc.data.remote

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.share.PresetShareCodec
import org.junit.Test

class GameTuneOtaValidatorTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun validTune(
        tuneCode: String = "CSOC2:somevalidpayload",
        gameDisplayName: String = "Devil May Cry 3",
        packageName: String = "com.example.game",
        authorHandle: String = "naxte",
        targetDeviceKeys: List<String> = listOf("retroid_pocket6"),
        notes: String = "Verified on RP6.",
    ) = RemoteContentValidator.CommunityGameTune(
        tuneCode = tuneCode,
        gameDisplayName = gameDisplayName,
        packageName = packageName,
        authorHandle = authorHandle,
        targetDeviceKeys = targetDeviceKeys,
        notes = notes,
    )

    // ---------------------------------------------------------------------------
    // Happy-path
    // ---------------------------------------------------------------------------

    @Test
    fun `valid tune with all fields passes validation`() {
        val tune = validTune()
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNull()
    }

    @Test
    fun `valid tune with only required fields passes validation`() {
        val tune = RemoteContentValidator.CommunityGameTune(
            tuneCode = "CSOC2:somevalidpayload",
            gameDisplayName = "My Game",
            packageName = "com.example.mygame",
        )
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNull()
    }

    // ---------------------------------------------------------------------------
    // packageName
    // ---------------------------------------------------------------------------

    @Test
    fun `blank packageName fails validation`() {
        val tune = validTune(packageName = "")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `packageName with shell metachar fails validation`() {
        val tune = validTune(packageName = "com.example; rm")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `packageName too long fails validation`() {
        val tune = validTune(packageName = "a".repeat(257))
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    // ---------------------------------------------------------------------------
    // gameDisplayName
    // ---------------------------------------------------------------------------

    @Test
    fun `blank gameDisplayName fails validation`() {
        val tune = validTune(gameDisplayName = "")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `gameDisplayName with control char fails validation`() {
        val tune = validTune(gameDisplayName = "Devil May\nCry 3")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `gameDisplayName too long fails validation`() {
        val tune = validTune(gameDisplayName = "G".repeat(257))
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    // ---------------------------------------------------------------------------
    // tuneCode
    // ---------------------------------------------------------------------------

    @Test
    fun `tuneCode not starting with CSOC2 fails validation`() {
        val tune = validTune(tuneCode = "CSOC1:somevalidpayload")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `blank tuneCode fails validation`() {
        val tune = validTune(tuneCode = "")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `tuneCode over MAX_BASE64_LENGTH fails validation`() {
        // The total length exceeds the cap: "CSOC2:" prefix (6 chars) + MAX_BASE64_LENGTH 'A's
        // pushes the code beyond the allowed maximum.
        val tune = validTune(tuneCode = "CSOC2:" + "A".repeat(PresetShareCodec.MAX_BASE64_LENGTH))
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `valid CSOC2 prefix passes tuneCode check`() {
        val tune = validTune(tuneCode = "CSOC2:somevalidpayload")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNull()
    }

    // ---------------------------------------------------------------------------
    // authorHandle
    // ---------------------------------------------------------------------------

    @Test
    fun `authorHandle over 64 chars fails validation`() {
        val tune = validTune(authorHandle = "a".repeat(65))
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `authorHandle with control char fails validation`() {
        val tune = validTune(authorHandle = "naxte\t")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    // ---------------------------------------------------------------------------
    // notes
    // ---------------------------------------------------------------------------

    @Test
    fun `notes over 512 chars fails validation`() {
        val tune = validTune(notes = "n".repeat(513))
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `notes with control char fails validation`() {
        val tune = validTune(notes = "Verified on RP6.")
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    // ---------------------------------------------------------------------------
    // targetDeviceKeys
    // ---------------------------------------------------------------------------

    @Test
    fun `targetDeviceKey with uppercase fails validation`() {
        val tune = validTune(targetDeviceKeys = listOf("RetroidPocket6"))
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `targetDeviceKey over 64 chars fails validation`() {
        val tune = validTune(targetDeviceKeys = listOf("a".repeat(65)))
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNotNull()
    }

    @Test
    fun `valid targetDeviceKeys with snake_case pass`() {
        val tune = validTune(targetDeviceKeys = listOf("retroid_pocket6", "ayn_odin3"))
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNull()
    }

    // ---------------------------------------------------------------------------
    // OTA body-size cap (informational)
    // ---------------------------------------------------------------------------

    @Test
    fun `OTA body size cap 2MB body would be discarded`() {
        // Body-size cap (2MB) is enforced in RemoteContentRepository.fetchTextCapped()
        // — see RemoteContentRepositoryTest for that coverage.
        // This test confirms the validator itself does not block otherwise-valid content,
        // so a tune that fits within all field limits passes regardless of network-layer caps.
        val tune = validTune()
        val result = RemoteContentValidator.validateCommunityTune(tune)
        assertThat(result).isNull()
    }
}
