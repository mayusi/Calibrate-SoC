package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The read-modify-write of config.xml MUST swap ONLY the fan-curve value and
 * preserve every other key byte-for-byte — clobbering the file would wipe the
 * user's other Odin settings.
 */
class SharedPrefsXmlTest {

    /** A representative com.odin.settings config.xml with several keys around
     *  the fan-curve value. */
    private val SAMPLE = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <int name="brightness_level" value="180" />
            <boolean name="rgb_enabled" value="true" />
            <string name="fan_temp_control_curve_point_key">[{"a":0,"b":20},{"a":2147483647,"b":60}]</string>
            <string name="equalizer_preset">flat</string>
            <int name="fan_mode" value="4" />
        </map>
    """.trimIndent()

    private val CURVE_KEY = FanCurveScript.CURVE_PREF_KEY

    @Test
    fun `readString extracts the current curve value`() {
        val value = SharedPrefsXml.readString(SAMPLE, CURVE_KEY)
        assertThat(value).isEqualTo("[{\"a\":0,\"b\":20},{\"a\":2147483647,\"b\":60}]")
    }

    @Test
    fun `replaceString swaps only the curve value and preserves other keys`() {
        val newCurveJson = FanCurveJson.serialize(FanCurve.STOCK)
        val result = SharedPrefsXml.replaceString(SAMPLE, CURVE_KEY, newCurveJson)
        assertThat(result).isInstanceOf(ReplaceResult.Replaced::class.java)
        val out = (result as ReplaceResult.Replaced).xml

        // The new curve value is present.
        assertThat(SharedPrefsXml.readString(out, CURVE_KEY)).isEqualTo(newCurveJson)

        // Every OTHER key is preserved verbatim.
        assertThat(out).contains("<int name=\"brightness_level\" value=\"180\" />")
        assertThat(out).contains("<boolean name=\"rgb_enabled\" value=\"true\" />")
        assertThat(out).contains("<string name=\"equalizer_preset\">flat</string>")
        assertThat(out).contains("<int name=\"fan_mode\" value=\"4\" />")
        // The XML declaration survives.
        assertThat(out).contains("<?xml version='1.0'")
    }

    @Test
    fun `replaceString round-trips through the parser`() {
        val newCurve = FanCurvePreset.COOL.curve
        val newJson = FanCurveJson.serialize(newCurve)
        val out = (SharedPrefsXml.replaceString(SAMPLE, CURVE_KEY, newJson) as ReplaceResult.Replaced).xml
        val readBack = SharedPrefsXml.readString(out, CURVE_KEY)!!
        val parsed = FanCurveJson.parse(readBack) as FanCurveParse.Ok
        assertThat(parsed.curve).isEqualTo(newCurve)
    }

    @Test
    fun `replaceString inserts when the key is absent`() {
        val withoutCurve = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <int name="brightness_level" value="180" />
            </map>
        """.trimIndent()
        val newJson = FanCurveJson.serialize(FanCurve.STOCK)
        val result = SharedPrefsXml.replaceString(withoutCurve, CURVE_KEY, newJson)
        assertThat(result).isInstanceOf(ReplaceResult.Inserted::class.java)
        val out = (result as ReplaceResult.Inserted).xml
        assertThat(SharedPrefsXml.readString(out, CURVE_KEY)).isEqualTo(newJson)
        // Existing key still there.
        assertThat(out).contains("<int name=\"brightness_level\" value=\"180\" />")
    }

    @Test
    fun `replaceString refuses a non-prefs document`() {
        val notPrefs = "<html><body>not a prefs file</body></html>"
        val result = SharedPrefsXml.replaceString(notPrefs, CURVE_KEY, "[]")
        assertThat(result).isEqualTo(ReplaceResult.NotPrefsFile)
    }

    @Test
    fun `escape and unescape round-trip XML special characters`() {
        val raw = "a < b & c > d"
        val escaped = SharedPrefsXml.escape(raw)
        assertThat(escaped).doesNotContain("< b")
        assertThat(SharedPrefsXml.unescape(escaped)).isEqualTo(raw)
    }

    @Test
    fun `readString returns null for an absent key`() {
        assertThat(SharedPrefsXml.readString(SAMPLE, "nonexistent_key")).isNull()
    }

    @Test
    fun `replaceString does not duplicate the curve element`() {
        val newJson = FanCurveJson.serialize(FanCurve.STOCK)
        val out = (SharedPrefsXml.replaceString(SAMPLE, CURVE_KEY, newJson) as ReplaceResult.Replaced).xml
        val occurrences = Regex("name=\"$CURVE_KEY\"").findAll(out).count()
        assertThat(occurrences).isEqualTo(1)
    }
}
