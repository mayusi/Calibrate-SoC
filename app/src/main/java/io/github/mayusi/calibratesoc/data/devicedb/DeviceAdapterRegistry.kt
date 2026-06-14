package io.github.mayusi.calibratesoc.data.devicedb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.remote.RemoteContentRepository
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
 *
 * **OTA overlay:** on each launch [RemoteContentRepository] fetches an
 * overlay from `content/adapters.json` on the main branch. Remote entries
 * WIN by key over bundled entries, so the developer can ship a corrected
 * adapter for an existing device or add a brand-new one without a new APK.
 * Bundled entries with no remote counterpart are left unchanged. When the
 * remote fetch has not yet completed (offline / first launch) the bundled
 * set is the sole source of truth.
 */
@Singleton
class DeviceAdapterRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val remoteContent: RemoteContentRepository,
) {
    /** Bundled adapters loaded once at first access. */
    private val bundled: Map<String, DeviceAdapter> by lazy { loadBundled() }

    /**
     * Merged view: bundled + remote with REMOTE WINNING by lowercase key.
     * Rebuilt on each call to pick up whatever [RemoteContentRepository]
     * has loaded since the last call (including from disk cache on startup).
     */
    private fun merged(): Map<String, DeviceAdapter> {
        val remote = remoteContent.remoteAdapters().associateBy { it.key.lowercase() }
        return if (remote.isEmpty()) bundled else bundled + remote
    }

    fun lookup(key: String?): DeviceAdapter? = key?.lowercase()?.let { merged()[it] }

    fun all(): List<DeviceAdapter> = merged().values.sortedBy { it.displayName }

    private fun loadBundled(): Map<String, DeviceAdapter> {
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
