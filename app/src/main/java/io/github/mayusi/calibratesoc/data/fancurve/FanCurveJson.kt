package io.github.mayusi.calibratesoc.data.fancurve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * (De)serializes a [FanCurve] to and from the EXACT JSON the Odin stock fan
 * controller stores under the `fan_temp_control_curve_point_key` preference.
 *
 * Wire shape (a JSON *string* value inside the SharedPreferences XML):
 * ```
 * [{"a":0,"b":20},{"a":25,"b":20},...,{"a":2147483647,"b":60}]
 * ```
 *  - `a` = threshold temperature (°C), the final point using INT_MAX
 *    (2147483647) as the catch-all ceiling.
 *  - `b` = fan duty percent.
 *
 * INT_MAX is preserved exactly: it serializes as the literal `2147483647`
 * (an Int, no quotes, no overflow) and parses back to [FanCurve.SENTINEL_TEMP_C].
 *
 * This object owns ONLY the JSON shape. Embedding the produced string into the
 * SharedPreferences XML (and parsing the XML around it) is handled by
 * [SharedPrefsXml] so the two concerns stay separately testable.
 */
object FanCurveJson {

    /** Compact, key-stable JSON. The stock file is compact (no whitespace), and
     *  matching it keeps diffs against the original minimal. */
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    /**
     * Serialize [curve] to the stock JSON string `[{"a":t,"b":d},...]`.
     *
     * Emits keys in the canonical `a` then `b` order, compact, with INT_MAX
     * preserved as the literal integer. Does NOT validate — call
     * [FanCurve.validate] first if you need the structural guarantee.
     */
    fun serialize(curve: FanCurve): String {
        val array = buildJsonArray {
            curve.points.forEach { point ->
                add(
                    JsonObject(
                        linkedMapOf(
                            "a" to JsonPrimitive(point.tempC),
                            "b" to JsonPrimitive(point.dutyPct),
                        ),
                    ),
                )
            }
        }
        return json.encodeToString(JsonArray.serializer(), array)
    }

    /**
     * Parse the stock JSON string back into a [FanCurve].
     *
     * Tolerant of:
     *  - extra/unknown keys on a point object (ignored),
     *  - surrounding whitespace.
     *
     * Strict about:
     *  - the top level being a JSON array,
     *  - each element being an object carrying integer `a` and `b`.
     *
     * Returns [FanCurveParse.Ok] on success, [FanCurveParse.Error] with a
     * reason otherwise. NEVER throws — a malformed device file must not crash
     * the read path; the caller surfaces the error honestly.
     *
     * Note: this does NOT call [FanCurve.validate]. A device may legitimately
     * hold a curve we'd warn about; the caller decides what to do with it.
     */
    fun parse(raw: String): FanCurveParse {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return FanCurveParse.Error("Empty curve JSON.")
        }
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrElse {
            return FanCurveParse.Error("Not valid JSON: ${it.message}")
        }
        val array = element as? JsonArray
            ?: return FanCurveParse.Error("Top-level JSON is not an array.")
        if (array.isEmpty()) {
            return FanCurveParse.Error("Curve array is empty.")
        }

        val points = ArrayList<FanCurvePoint>(array.size)
        array.forEachIndexed { i, el ->
            val obj = (el as? JsonObject)
                ?: return FanCurveParse.Error("Element $i is not an object.")
            val temp = obj["a"]?.intOrNull()
                ?: return FanCurveParse.Error("Element $i is missing an integer 'a' (temp).")
            val duty = obj["b"]?.intOrNull()
                ?: return FanCurveParse.Error("Element $i is missing an integer 'b' (duty).")
            points += FanCurvePoint(tempC = temp, dutyPct = duty)
        }
        return FanCurveParse.Ok(FanCurve(points))
    }

    /** Safe int extraction from a JsonElement: only succeeds for a numeric
     *  primitive, returns null otherwise (so we never throw on a string/object). */
    private fun kotlinx.serialization.json.JsonElement.intOrNull(): Int? {
        val prim = this as? JsonPrimitive ?: return null
        if (prim.isString) return null
        return runCatching { prim.int }.getOrNull()
    }
}

/** Result of [FanCurveJson.parse]. */
sealed interface FanCurveParse {
    data class Ok(val curve: FanCurve) : FanCurveParse
    data class Error(val reason: String) : FanCurveParse
}
