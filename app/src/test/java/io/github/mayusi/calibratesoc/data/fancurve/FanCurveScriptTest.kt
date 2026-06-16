package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The apply script must build the EXACT reverse-engineered sequence, in order:
 *   1. write config.xml (decode base64 → atomic replace),
 *   2. kill -9 com.odin.settings,
 *   3. fan_mode bounce 3 → 4.
 * Bouncing without the kill does NOT reload an externally edited curve, so both
 * the kill and the 3→4 bounce must be present and ordered.
 */
class FanCurveScriptTest {

    private val B64 = "PG1hcD48L21hcD4=" // base64 of "<map></map>"

    @Test
    fun `apply script writes the file before killing the service`() {
        val script = FanCurveScript.buildApplyScript(B64)
        val decodeIdx = script.indexOf("base64 -d")
        val killIdx = script.indexOf("kill -9")
        assertThat(decodeIdx).isGreaterThan(-1)
        assertThat(killIdx).isGreaterThan(-1)
        assertThat(decodeIdx).isLessThan(killIdx)
    }

    @Test
    fun `apply script targets the correct config path`() {
        val script = FanCurveScript.buildApplyScript(B64)
        assertThat(script).contains(FanCurveScript.CONFIG_XML_PATH)
    }

    @Test
    fun `apply script kills the odin settings package`() {
        val script = FanCurveScript.buildApplyScript(B64)
        assertThat(script).contains("kill -9 \$(pidof ${FanCurveScript.ODIN_SETTINGS_PKG})")
    }

    @Test
    fun `apply script bounces fan_mode 3 then 4 after the kill`() {
        val script = FanCurveScript.buildApplyScript(B64)
        val killIdx = script.indexOf("kill -9")
        val bounce3Idx = script.indexOf("settings put system fan_mode 3")
        val smart4Idx = script.indexOf("settings put system fan_mode 4")

        assertThat(bounce3Idx).isGreaterThan(killIdx)   // bounce happens AFTER the kill
        assertThat(smart4Idx).isGreaterThan(bounce3Idx) // 4 comes AFTER 3
    }

    @Test
    fun `apply script ends in Smart mode (4)`() {
        val script = FanCurveScript.buildApplyScript(B64)
        // The last fan_mode write must be 4 (Smart) — never leave the fan in a
        // transient mode.
        val last4 = script.lastIndexOf("settings put system fan_mode 4")
        val last3 = script.lastIndexOf("settings put system fan_mode 3")
        assertThat(last4).isGreaterThan(last3)
    }

    @Test
    fun `apply script uses an atomic temp-then-move write`() {
        val script = FanCurveScript.buildApplyScript(B64)
        // Decode to a temp file, then mv over the real path — never a partial write.
        assertThat(script).contains(".calibrate.tmp")
        assertThat(script).contains("mv -f")
        val tmpIdx = script.indexOf(".calibrate.tmp")
        val mvIdx = script.indexOf("mv -f")
        assertThat(tmpIdx).isLessThan(mvIdx)
    }

    @Test
    fun `apply script embeds the base64 payload`() {
        val script = FanCurveScript.buildApplyScript(B64)
        assertThat(script).contains(B64)
    }

    @Test
    fun `read commands target the documented nodes`() {
        assertThat(FanCurveScript.readConfigCommand()).contains(FanCurveScript.CONFIG_XML_PATH)
        assertThat(FanCurveScript.readFanDutyCommand()).isEqualTo("cat /sys/class/gpio5_pwm2/duty")
        assertThat(FanCurveScript.readFanPeriodCommand()).isEqualTo("cat /sys/class/gpio5_pwm2/period")
        assertThat(FanCurveScript.readFanModeCommand()).isEqualTo("settings get system fan_mode")
    }

    @Test
    fun `shellQuote neutralizes embedded single quotes`() {
        val quoted = FanCurveScript.shellQuote("a'b")
        assertThat(quoted).isEqualTo("'a'\\''b'")
    }
}
