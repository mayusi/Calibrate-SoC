package io.github.mayusi.calibratesoc.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.net.GitHubCertPins
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.update.ApkDownloader
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OTA content channel. Fetches small JSON files from the repo's `content/`
 * directory on the main branch over HTTPS (GitHub raw) and caches them in
 * [Context.filesDir]/remote_content/. The bundled assets are always the
 * fallback; remote content overlays them when present and valid.
 *
 * Security properties:
 *  1. All URLs are hardcoded — never derived from untrusted input.
 *  2. Every URL is validated through [ApkDownloader.isAllowedUrl] (HTTPS +
 *     GitHub host allowlist) before any network call is made.
 *  3. JSON is decoded with strict type deserialization via kotlinx.serialization.
 *     A malformed/partial response is discarded entirely (all-or-nothing).
 *  4. Remote data is never auto-applied and never injected into scripts unescaped;
 *     all string fields are validated against the same shell-metachar rejection
 *     used by the backup importer.
 *  5. The repository never throws at callers — every failure path logs a
 *     warning and returns, leaving the bundled set intact.
 *
 * Throttle: a refresh is skipped if one completed within [REFRESH_INTERVAL_MS]
 * (12 hours). Last-refresh epoch is stored in a plain file in the cache dir.
 */
@Singleton
class RemoteContentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    // ── OkHttp clients — conservative timeouts, mirrors UpdateChecker ────────

    // Pinned client: TLS cert pinning on GitHub hosts as defence-in-depth.
    // See GitHubCertPins for threat model and fail-open rationale.
    private val pinnedClient: OkHttpClient = GitHubCertPins.pinnedClient(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS),
    )

    // Unpinned fallback: same timeouts, no CertificatePinner.
    private val unpinnedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── In-memory cache (overlays the bundled set after a successful fetch) ──

    @Volatile private var remoteAdapters: List<DeviceAdapter> = emptyList()
    @Volatile private var remotePresets: List<Preset> = emptyList()

    /** True after the first successful (or cache-hit) load so callers can
     *  tell whether we have data beyond the bundled set. */
    private val _loaded = AtomicBoolean(false)
    val isLoaded: Boolean get() = _loaded.get()

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Remote-only adapter list. Merged into the bundled set by [DeviceAdapterRegistry]. */
    fun remoteAdapters(): List<DeviceAdapter> = remoteAdapters

    /** Remote-only community preset list. Merged into the preset list by [PresetGenerator]. */
    fun remotePresets(): List<Preset> = remotePresets

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Performs a best-effort refresh: fetches the manifest, then (if content
     * has changed) re-fetches the data files and updates the in-memory and
     * on-disk caches.
     *
     * NEVER throws. NEVER blocks startup. NEVER crashes on bad data.
     * Caller should invoke from a background dispatcher ([kotlinx.coroutines.Dispatchers.IO]).
     *
     * @return true on successful fetch+parse (or throttle-skip), false on any failure.
     */
    suspend fun refresh(): Boolean {
        // Warm in-memory from disk cache first so we always have data even if
        // the network fetch below is throttled or fails.
        warmFromCache()

        // Throttle: skip if we fetched recently.
        if (isThrottled()) {
            Log.d(TAG, "OTA refresh skipped — fetched recently")
            return true
        }

        return runCatching { doRefresh() }.getOrElse { e ->
            Log.w(TAG, "OTA refresh failed", e)
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun cacheDir(): File = File(context.filesDir, "remote_content").also { it.mkdirs() }

    private fun manifestCacheFile()  = File(cacheDir(), "manifest.json")
    private fun adaptersCacheFile()  = File(cacheDir(), "adapters.json")
    private fun presetsCacheFile()   = File(cacheDir(), "presets.json")
    private fun lastFetchFile()      = File(cacheDir(), "last_fetch_ms.txt")

    /** Warm [remoteAdapters] and [remotePresets] from the on-disk cache
     *  (set on a previous launch when network was available). */
    private fun warmFromCache() {
        val adaptersFile = adaptersCacheFile()
        if (adaptersFile.exists()) {
            runCatching {
                val raw = adaptersFile.readText()
                val parsed = json.decodeFromString<List<DeviceAdapter>>(raw)
                val validated = parsed.filter { validateAdapter(it) == null }
                remoteAdapters = validated
                Log.d(TAG, "Warmed ${validated.size} remote adapter(s) from disk cache")
            }.onFailure { e ->
                Log.w(TAG, "Could not load adapter cache — will re-fetch", e)
            }
        }

        val presetsFile = presetsCacheFile()
        if (presetsFile.exists()) {
            runCatching {
                val raw = presetsFile.readText()
                val parsed = json.decodeFromString<List<Preset>>(raw)
                val validated = parsed.filter { validatePreset(it) == null }
                remotePresets = validated
                Log.d(TAG, "Warmed ${validated.size} remote preset(s) from disk cache")
            }.onFailure { e ->
                Log.w(TAG, "Could not load preset cache — will re-fetch", e)
            }
        }

        if (remoteAdapters.isNotEmpty() || remotePresets.isNotEmpty()) {
            _loaded.set(true)
        }
    }

    private fun isThrottled(): Boolean {
        val f = lastFetchFile()
        if (!f.exists()) return false
        val last = f.readText().toLongOrNull() ?: return false
        return System.currentTimeMillis() - last < REFRESH_INTERVAL_MS
    }

    private fun doRefresh(): Boolean {
        // 1. Fetch + parse manifest to check whether content changed.
        val manifestJson = fetchText(MANIFEST_URL) ?: return false
        val manifest = runCatching {
            json.decodeFromString<RemoteContentManifest>(manifestJson)
        }.getOrElse { e ->
            Log.w(TAG, "Could not parse remote manifest — aborting OTA refresh", e)
            return false
        }

        // Compare with cached manifest version to skip unchanged content.
        val cachedVersion = runCatching {
            val raw = manifestCacheFile().readText()
            json.decodeFromString<RemoteContentManifest>(raw).version
        }.getOrDefault(-1)

        if (manifest.version == cachedVersion) {
            Log.d(TAG, "Remote content version ${manifest.version} matches cache — no update needed")
            recordFetch()
            return true
        }

        Log.i(TAG, "Remote content version ${manifest.version} (cached $cachedVersion) — fetching data files")

        // 2. Fetch adapters.json
        val adaptersJson = fetchText(ADAPTERS_URL) ?: run {
            Log.w(TAG, "Could not fetch remote adapters — keeping existing cache")
            null
        }
        // 3. Fetch presets.json
        val presetsJson = fetchText(PRESETS_URL) ?: run {
            Log.w(TAG, "Could not fetch remote presets — keeping existing cache")
            null
        }

        // 4. Parse + validate adapters
        if (adaptersJson != null) {
            val parsed = runCatching {
                json.decodeFromString<List<DeviceAdapter>>(adaptersJson)
            }.getOrElse { e ->
                Log.w(TAG, "Remote adapters.json failed to parse — keeping existing cache", e)
                null
            }
            if (parsed != null) {
                val valid = mutableListOf<DeviceAdapter>()
                val rejected = mutableListOf<String>()
                for (adapter in parsed) {
                    val err = validateAdapter(adapter)
                    if (err == null) valid.add(adapter)
                    else rejected.add("adapter '${adapter.key}': $err")
                }
                if (rejected.isNotEmpty()) {
                    Log.w(TAG, "Rejected ${rejected.size} remote adapter(s): $rejected")
                }
                // Persist to disk only after successful parse.
                adaptersCacheFile().writeText(adaptersJson)
                remoteAdapters = valid
                Log.i(TAG, "Applied ${valid.size} remote adapter override(s)")
            }
        }

        // 5. Parse + validate presets
        if (presetsJson != null) {
            val parsed = runCatching {
                json.decodeFromString<List<Preset>>(presetsJson)
            }.getOrElse { e ->
                Log.w(TAG, "Remote presets.json failed to parse — keeping existing cache", e)
                null
            }
            if (parsed != null) {
                val valid = mutableListOf<Preset>()
                val rejected = mutableListOf<String>()
                for (preset in parsed) {
                    val err = validatePreset(preset)
                    if (err == null) valid.add(preset)
                    else rejected.add("preset '${preset.id}': $err")
                }
                if (rejected.isNotEmpty()) {
                    Log.w(TAG, "Rejected ${rejected.size} remote preset(s): $rejected")
                }
                presetsCacheFile().writeText(presetsJson)
                remotePresets = valid
                Log.i(TAG, "Applied ${valid.size} remote community preset(s)")
            }
        }

        // 6. Persist manifest + throttle timestamp.
        manifestCacheFile().writeText(manifestJson)
        recordFetch()
        _loaded.set(true)
        return true
    }

    /** Fetch a URL as a String. Returns null on any network/IO failure. */
    private fun fetchText(url: String): String? {
        // Security: all URLs are hardcoded constants checked at compile time,
        // but we still run the allowlist as defense-in-depth.
        if (!ApkDownloader.isAllowedUrl(url)) {
            Log.e(TAG, "Rejected OTA URL (not HTTPS or not a GitHub host): $url")
            return null
        }
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            // Cert-pinned fetch with fail-open fallback on pin mismatch.
            GitHubCertPins.executeWithPinFallback(
                tag = "RemoteContent.fetchText($url)",
                pinnedAttempt = {
                    pinnedClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "HTTP ${resp.code} fetching $url (pinned)")
                            null
                        } else {
                            resp.body?.string()
                        }
                    }
                },
                unpinnedAttempt = {
                    unpinnedClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "HTTP ${resp.code} fetching $url (unpinned fallback)")
                            null
                        } else {
                            resp.body?.string()
                        }
                    }
                },
            )
        }.getOrElse { e ->
            Log.w(TAG, "Network error fetching $url", e)
            null
        }
    }

    private fun recordFetch() {
        runCatching { lastFetchFile().writeText(System.currentTimeMillis().toString()) }
    }

    // ── Validation — delegates to pure-JVM RemoteContentValidator ────────────

    /** Delegates to [RemoteContentValidator.validateAdapter]. */
    internal fun validateAdapter(adapter: DeviceAdapter): String? =
        RemoteContentValidator.validateAdapter(adapter)

    /** Delegates to [RemoteContentValidator.validatePreset]. */
    internal fun validatePreset(preset: Preset): String? =
        RemoteContentValidator.validatePreset(preset)

    companion object {
        private const val TAG = "RemoteContent"

        /** 12 hours in milliseconds. */
        const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L

        // URLs are defined in RemoteContentValidator (pure-JVM) so unit tests
        // can verify them without loading this Android class.
        val MANIFEST_URL get() = RemoteContentValidator.MANIFEST_URL
        val ADAPTERS_URL get() = RemoteContentValidator.ADAPTERS_URL
        val PRESETS_URL  get() = RemoteContentValidator.PRESETS_URL
    }
}
