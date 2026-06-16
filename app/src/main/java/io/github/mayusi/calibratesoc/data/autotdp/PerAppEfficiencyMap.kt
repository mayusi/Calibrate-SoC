package io.github.mayusi.calibratesoc.data.autotdp

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.autoTdpDataStore by preferencesDataStore(name = "autotdp_prefs")

/**
 * Component 8: Per-app AutoTDP efficiency bindings.
 *
 * Extends the existing per-app override concept (package → profileId in
 * [ProfileRepository] / [ProfileStore]) to instead bind a package name to an
 * [AutoTdpProfileConfig]. The two maps are intentionally separate because one
 * controls which *preset* to apply (kernel tunables) and the other controls
 * which *AutoTDP control profile* the daemon should run (EFFICIENCY / BALANCED /
 * BATTERY_TARGET). They are independent axes and can co-exist.
 *
 * ## Persistence
 * The binding is stored in a dedicated DataStore ("autotdp_prefs") as a single
 * JSON string — the same atomic-rewrite pattern used by [ProfileRepository]'s
 * JSON file, but using DataStore so it lives in the same preferences directory
 * as [UserPrefs]. This keeps the autotdp namespace self-contained.
 *
 * Key: [PER_APP_MAP_KEY] — JSON-serialised [Map<String, AutoTdpProfile>].
 * (targetMilliWatts is not persisted per-app; only the profile enum is stored.
 * BATTERY_TARGET binding is therefore advisory — the caller must supply watts
 * at runtime. The most common bindings are EFFICIENCY and BALANCED.)
 *
 * ## Usage by the integration layer
 * [ForegroundAppWatcher] calls the suspend [profileForApp] when the foreground
 * app changes (it already runs on Dispatchers.IO). The result is an
 * [AutoTdpProfileConfig]? — non-null only for an explicitly-bound package, in
 * which case the watcher starts the AutoTDP daemon with that profile and stops
 * it when the bound app leaves the foreground. Unmapped apps return null →
 * the per-app efficiency map is silent for that app (no change to AutoTDP).
 *
 * This runs ALONGSIDE — and is subordinate to — the richer
 * [PerAppBundle.autoTdpGoal] path: when a package has a bundle that already
 * drives AutoTDP, the bundle wins and the efficiency-map binding is skipped for
 * that package (see [ForegroundAppWatcher]). The two never both start the daemon.
 *
 * ## Honesty invariant
 * Only explicitly mapped apps receive a profile. Unmapped → null. No implicit
 * default, no fallback that the user didn't request.
 */
@Singleton
class PerAppEfficiencyMap @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ── Flow API ──────────────────────────────────────────────────────────────

    /**
     * Observable map of all package→profile bindings. Emits the full map
     * on every change. Backed by DataStore so survives process death.
     */
    fun observeAll(): Flow<Map<String, AutoTdpProfile>> =
        context.autoTdpDataStore.data.map { prefs ->
            val raw = prefs[PER_APP_MAP_KEY] ?: return@map emptyMap()
            decodeMap(raw)
        }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the [AutoTdpProfileConfig] bound to [packageName], or null if
     * no binding exists. The returned config always has [targetMilliWatts] = null
     * (i.e. EFFICIENCY / BALANCED mode; the caller may enrich for BATTERY_TARGET).
     *
     * Reads the current binding snapshot from DataStore via [observeAll]'s first
     * emission, then delegates to the pure [lookup]. This is a real read — NOT a
     * stub — intended for the accessibility-event callback in [ForegroundAppWatcher],
     * which already runs on Dispatchers.IO. Suspends until the DataStore emits its
     * current value (effectively a one-shot read). For reactive UI, collect
     * [observeAll] instead.
     */
    suspend fun profileForApp(packageName: String): AutoTdpProfileConfig? {
        val snapshot = observeAll().first()
        return lookup(packageName, snapshot)
    }

    /**
     * Pure map lookup — give it the current map snapshot and a package name,
     * returns the matching config or null. Used by the integration layer that
     * caches the latest [observeAll] emission.
     *
     * Kept as a companion function so it can be unit-tested without DataStore.
     */
    fun profileForApp(packageName: String, snapshot: Map<String, AutoTdpProfile>): AutoTdpProfileConfig? {
        val profile = snapshot[packageName] ?: return null
        return AutoTdpProfileConfig(profile = profile, targetMilliWatts = null)
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Bind [profile] to [packageName]. Pass null to clear the binding (same
     * pattern as [ProfileRepository.setOverride] with a null profileId).
     *
     * Suspends; call from Dispatchers.IO or a CoroutineScope.
     */
    suspend fun setProfileForApp(packageName: String, profile: AutoTdpProfile?) {
        context.autoTdpDataStore.edit { prefs ->
            val current = prefs[PER_APP_MAP_KEY]?.let { decodeMap(it) } ?: emptyMap()
            val updated = if (profile == null) {
                current - packageName
            } else {
                current + (packageName to profile)
            }
            prefs[PER_APP_MAP_KEY] = encodeMap(updated)
        }
    }

    // ── Serialisation helpers ─────────────────────────────────────────────────

    private fun decodeMap(raw: String): Map<String, AutoTdpProfile> = runCatching {
        json.decodeFromString<Map<String, String>>(raw)
            .mapNotNull { (pkg, profileName) ->
                runCatching { pkg to AutoTdpProfile.valueOf(profileName) }.getOrNull()
            }
            .toMap()
    }.getOrDefault(emptyMap())

    private fun encodeMap(map: Map<String, AutoTdpProfile>): String =
        json.encodeToString(map.mapValues { it.value.name })

    companion object {
        /**
         * DataStore key for the per-app AutoTDP profile map.
         * Stored in the "autotdp_prefs" DataStore file.
         * Key name: "per_app_autotdp_map".
         */
        val PER_APP_MAP_KEY = stringPreferencesKey("per_app_autotdp_map")

        /**
         * Pure map lookup for callers that already hold a snapshot.
         * Identical contract to the instance method but callable without
         * an injected [PerAppEfficiencyMap].
         */
        fun lookup(packageName: String, snapshot: Map<String, AutoTdpProfile>): AutoTdpProfileConfig? {
            val profile = snapshot[packageName] ?: return null
            return AutoTdpProfileConfig(profile = profile, targetMilliWatts = null)
        }
    }
}
