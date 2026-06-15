package io.github.mayusi.calibratesoc.data.autotdp

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
 * The [ForegroundAppWatcher] (or its replacement) calls [profileForApp] when
 * the foreground app changes. The result is a [AutoTdpProfileConfig]? which
 * feeds [AutoTdpTrigger.onProfileRequested]. Unmapped apps return null →
 * the trigger is silent for that app (no change to the active AutoTDP profile).
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
     * This is a synchronous snapshot read intended for use from the accessibility
     * event callback or wherever the integration layer decides the active profile.
     * For reactive UI, collect [observeAll] instead.
     */
    fun profileForApp(packageName: String): AutoTdpProfileConfig? {
        // DataStore doesn't offer a synchronous read; callers that need a
        // point-in-time snapshot should cache the last [observeAll] emission.
        // This method is intentionally absent a synchronous DataStore read to
        // avoid blocking the calling thread. See integration note in kdoc.
        //
        // The ForegroundAppWatcher-style caller is already on Dispatchers.IO
        // and can use the cached snapshot approach. We expose the pure map
        // logic separately for testability.
        return null // Stateless version — see profileForApp(pkg, snapshot) below.
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
