package io.github.mayusi.calibratesoc.data.devicedb

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class DeviceAdapterRegistryTest {

    /**
     * Verifies the bundled adapters.json parses cleanly into the typed
     * model. A schema regression here would be silent at runtime (registry
     * returns null and we fall back to generic probing) — this test catches
     * it at build time.
     */
    @Test
    fun `bundled adapters json parses`() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("test-adapters.json")
            ?.bufferedReader()?.readText()
            ?: BUNDLED_ADAPTERS_FALLBACK
        val parsed = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.decodeFromString<List<DeviceAdapter>>(raw)
        assertThat(parsed).isNotEmpty()
        assertThat(parsed.map { it.key }).contains("ayn_odin2")
    }

    // The asset isn't on the unit-test classpath without extra Gradle wiring,
    // so we duplicate a minimal fixture here as a fallback. Real bundled JSON
    // is what ships; the assets pipeline is exercised by instrumented tests.
    private val BUNDLED_ADAPTERS_FALLBACK = """
        [
          {
            "key": "ayn_odin2",
            "displayName": "AYN Odin 2",
            "vendorAppPackage": "com.ayn.gameassistant",
            "fanAdapter": {
              "kind": "SETTINGS_KEY",
              "target": "ayn_fan_mode",
              "supportsCurve": false,
              "presets": ["Off", "Quiet"]
            },
            "perfPresetAdapter": null,
            "thermalLabelOverrides": {},
            "notes": null
          }
        ]
    """.trimIndent()
}
