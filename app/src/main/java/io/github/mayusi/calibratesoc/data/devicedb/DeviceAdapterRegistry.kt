package io.github.mayusi.calibratesoc.data.devicedb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled device-adapter JSON from assets and indexes it by
 * key. Lookups are case-insensitive; unknown keys return null and the
 * downstream code falls back to generic sysfs discovery.
 *
 * The bundled set is intentionally small (the handhelds we've actually
 * researched). Users with unknown devices can opt in to the crowdsourced
 * device DB via the "Report unknown device" button in Device Info, which
 * uploads the anonymised CapabilityReport JSON to a public GitHub repo
 * for the community to triage and add an adapter.
 */
@Singleton
class DeviceAdapterRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val cache: Map<String, DeviceAdapter> by lazy { load() }

    fun lookup(key: String?): DeviceAdapter? = key?.lowercase()?.let(cache::get)

    fun all(): List<DeviceAdapter> = cache.values.sortedBy { it.displayName }

    private fun load(): Map<String, DeviceAdapter> {
        val asset = runCatching {
            context.assets.open(ADAPTERS_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyMap()
        val list = runCatching {
            json.decodeFromString<List<DeviceAdapter>>(asset)
        }.getOrElse { emptyList() }
        return list.associateBy { it.key.lowercase() }
    }

    private companion object {
        const val ADAPTERS_PATH = "devicedb/adapters.json"
    }
}
