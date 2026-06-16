package io.github.mayusi.calibratesoc.data.gameaware

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * GUARDRAIL 4 — the IIC/GameNative Wine-wrapper foreground package must be recognised as
 * a game so the classifier can anchor it (DEFECT A trigger #1). The live foreground pkg
 * is "app.gamenative.iic"; without a prefix entry [KnownGames.defaultHintFor] returned
 * null and AUTO fell to BATTERY_SAVER on a low/null-GPU tick.
 */
class KnownGamesWrapperTest {

    @Test
    fun `defaultHintFor app_gamenative_iic is non-null`() {
        assertThat(KnownGames.defaultHintFor("app.gamenative.iic")).isNotNull()
    }

    @Test
    fun `defaultHintFor app_gamenative base package is non-null`() {
        assertThat(KnownGames.defaultHintFor("app.gamenative")).isNotNull()
    }

    @Test
    fun `winlator variants resolve via prefix`() {
        assertThat(KnownGames.defaultHintFor("com.winlator")).isNotNull()
        // A community winlator fork with a longer package still matches the prefix.
        assertThat(KnownGames.defaultHintFor("com.winlator.cmod")).isNotNull()
    }

    @Test
    fun `a genuinely unknown package is still null`() {
        assertThat(KnownGames.defaultHintFor("com.example.notagame")).isNull()
    }
}
