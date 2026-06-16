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

    // ── H1: metadata capture + verified restore ────────────────────────────────

    @Test
    fun `metadata restore is chained with AND before the point of no return`() {
        // The write + chmod + chown + context restore must be in the `if`
        // condition (so any failure aborts), and the kill must be in the `then`
        // branch AFTER it. We assert the perms/context restore precedes the kill.
        val script = FanCurveScript.buildApplyScript(B64)
        val chmodIdx = script.indexOf("chmod")
        val chownIdx = script.indexOf("chown")
        val thenIdx = script.indexOf("; then ")
        val killIdx = script.indexOf("kill -9")
        assertThat(chmodIdx).isGreaterThan(-1)
        assertThat(chownIdx).isGreaterThan(-1)
        assertThat(chmodIdx).isLessThan(thenIdx)
        assertThat(chownIdx).isLessThan(thenIdx)
        assertThat(thenIdx).isLessThan(killIdx)
    }

    @Test
    fun `apply script aborts with exit 1 and does not kill on restore failure`() {
        val script = FanCurveScript.buildApplyScript(B64)
        // The else branch must exit non-zero WITHOUT the kill/bounce.
        assertThat(script).contains("; else ")
        assertThat(script).contains("exit 1")
        val elseIdx = script.indexOf("; else ")
        val killIdx = script.indexOf("kill -9")
        // The kill lives in the THEN branch, before the else branch.
        assertThat(killIdx).isLessThan(elseIdx)
        // The failure marker is in the else branch.
        assertThat(script).contains(FanCurveScript.APPLY_FAILED_MARKER)
    }

    @Test
    fun `captured metadata is restored EXACTLY (owner mode context)`() {
        val meta = ConfigFileMetadata(
            ownerGroup = "system:system",
            mode = "660",
            seContext = "u:object_r:system_app_data_file:s0",
        )
        val script = FanCurveScript.buildApplyScript(B64, meta)
        assertThat(script).contains("chown 'system:system'")
        assertThat(script).contains("chmod '660'")
        assertThat(script).contains("chcon 'u:object_r:system_app_data_file:s0'")
    }

    @Test
    fun `unknown metadata falls back to sibling-dir owner, 660, and restorecon`() {
        val script = FanCurveScript.buildApplyScript(B64, ConfigFileMetadata.UNKNOWN)
        assertThat(script).contains("chown --reference=")
        assertThat(script).contains("chmod '660'")
        assertThat(script).contains("restorecon")
        assertThat(script).doesNotContain("chcon")
    }

    @Test
    fun `metadata read command captures owner group, mode, and SELinux context`() {
        val cmd = FanCurveScript.readConfigMetadataCommand()
        assertThat(cmd).contains("%U:%G")
        assertThat(cmd).contains("%a")
        assertThat(cmd).contains("%C")
        assertThat(cmd).contains(FanCurveScript.CONFIG_XML_PATH)
    }

    @Test
    fun `parseConfigMetadata reads a well-formed two-line stat output`() {
        val raw = "system:system 660\nu:object_r:system_app_data_file:s0"
        val meta = FanCurveScript.parseConfigMetadata(raw)
        assertThat(meta.ownerGroup).isEqualTo("system:system")
        assertThat(meta.mode).isEqualTo("660")
        assertThat(meta.seContext).isEqualTo("u:object_r:system_app_data_file:s0")
    }

    @Test
    fun `parseConfigMetadata treats placeholders and junk as unknown`() {
        // Our padding line when stat fails: "-:- -" then "-".
        val meta = FanCurveScript.parseConfigMetadata("-:- -\n-")
        assertThat(meta.ownerGroup).isNull()
        assertThat(meta.mode).isNull()
        assertThat(meta.seContext).isNull()

        // Blank / null → UNKNOWN.
        assertThat(FanCurveScript.parseConfigMetadata(null)).isEqualTo(ConfigFileMetadata.UNKNOWN)
        assertThat(FanCurveScript.parseConfigMetadata("   ")).isEqualTo(ConfigFileMetadata.UNKNOWN)
    }

    @Test
    fun `parseConfigMetadata accepts a 4-digit octal mode and rejects non-octal`() {
        // A 4-digit octal mode (e.g. 0660) is accepted.
        assertThat(FanCurveScript.parseConfigMetadata("system:system 0660\nu:object_r:x:s0").mode)
            .isEqualTo("0660")
        // A bogus mode (letters) is rejected.
        assertThat(FanCurveScript.parseConfigMetadata("system:system rwx\nu:object_r:x:s0").mode)
            .isNull()
        // A non-label context token is rejected (no object_r / too few colons).
        assertThat(FanCurveScript.parseConfigMetadata("system:system 660\nbogus").seContext)
            .isNull()
    }
}
