package io.github.mayusi.calibratesoc.data.fancurve

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.fancurve.FanCurve.Companion.SENTINEL_TEMP_C
import org.junit.Test

/**
 * The serializer must produce EXACTLY the JSON the Odin stock controller stores
 * — including the INT_MAX (2147483647) sentinel on the final point — and parse
 * that same shape back losslessly.
 */
class FanCurveJsonTest {

    /** The verified stock curve as it appears on-device (compact, key order a,b). */
    private val STOCK_JSON =
        "[{\"a\":0,\"b\":20},{\"a\":25,\"b\":20},{\"a\":45,\"b\":20}," +
            "{\"a\":65,\"b\":30},{\"a\":85,\"b\":45},{\"a\":105,\"b\":60}," +
            "{\"a\":2147483647,\"b\":60}]"

    @Test
    fun `serialize produces the exact stock JSON`() {
        assertThat(FanCurveJson.serialize(FanCurve.STOCK)).isEqualTo(STOCK_JSON)
    }

    @Test
    fun `the sentinel serializes as the literal INT_MAX integer`() {
        val json = FanCurveJson.serialize(FanCurve.STOCK)
        assertThat(json).contains("\"a\":2147483647")
        // No quotes around the number, no overflow / negative wrap.
        assertThat(json).doesNotContain("-2147483648")
    }

    @Test
    fun `parse reads the stock JSON back to the stock curve`() {
        val result = FanCurveJson.parse(STOCK_JSON)
        assertThat(result).isInstanceOf(FanCurveParse.Ok::class.java)
        assertThat((result as FanCurveParse.Ok).curve).isEqualTo(FanCurve.STOCK)
    }

    @Test
    fun `parse preserves the INT_MAX sentinel exactly`() {
        val curve = (FanCurveJson.parse(STOCK_JSON) as FanCurveParse.Ok).curve
        assertThat(curve.points.last().tempC).isEqualTo(SENTINEL_TEMP_C)
        assertThat(curve.points.last().isSentinel).isTrue()
    }

    @Test
    fun `serialize then parse round-trips every preset`() {
        FanCurvePreset.entries.forEach { preset ->
            val json = FanCurveJson.serialize(preset.curve)
            val back = FanCurveJson.parse(json)
            assertThat(back).isInstanceOf(FanCurveParse.Ok::class.java)
            assertThat((back as FanCurveParse.Ok).curve).isEqualTo(preset.curve)
        }
    }

    @Test
    fun `parse tolerates surrounding whitespace`() {
        val result = FanCurveJson.parse("  \n$STOCK_JSON\n ")
        assertThat(result).isInstanceOf(FanCurveParse.Ok::class.java)
    }

    @Test
    fun `parse tolerates unknown extra keys on a point`() {
        val json = "[{\"a\":0,\"b\":20,\"extra\":99},{\"a\":2147483647,\"b\":60}]"
        val result = FanCurveJson.parse(json)
        assertThat(result).isInstanceOf(FanCurveParse.Ok::class.java)
        val curve = (result as FanCurveParse.Ok).curve
        assertThat(curve.points.first()).isEqualTo(FanCurvePoint(0, 20))
    }

    @Test
    fun `parse rejects non-array top level`() {
        assertThat(FanCurveJson.parse("{\"a\":0,\"b\":20}"))
            .isInstanceOf(FanCurveParse.Error::class.java)
    }

    @Test
    fun `parse rejects an empty array`() {
        assertThat(FanCurveJson.parse("[]")).isInstanceOf(FanCurveParse.Error::class.java)
    }

    @Test
    fun `parse rejects a point missing the duty key`() {
        assertThat(FanCurveJson.parse("[{\"a\":0}]"))
            .isInstanceOf(FanCurveParse.Error::class.java)
    }

    @Test
    fun `parse never throws on garbage input`() {
        // Must return Error, never crash the read path.
        assertThat(FanCurveJson.parse("not json at all"))
            .isInstanceOf(FanCurveParse.Error::class.java)
        assertThat(FanCurveJson.parse(""))
            .isInstanceOf(FanCurveParse.Error::class.java)
    }
}
