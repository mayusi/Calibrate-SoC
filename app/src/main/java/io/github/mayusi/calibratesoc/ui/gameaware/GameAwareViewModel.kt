package io.github.mayusi.calibratesoc.ui.gameaware

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.gameaware.GamePlan
import io.github.mayusi.calibratesoc.data.gameaware.GamePlanSource
import io.github.mayusi.calibratesoc.data.gameaware.GameProfileResolver
import io.github.mayusi.calibratesoc.data.gameaware.KnownGames
import io.github.mayusi.calibratesoc.data.gameaware.PerGameRecord
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

private val Context.gameAwareDataStore: DataStore<Preferences> by preferencesDataStore(name = "game_aware")

/**
 * ViewModel for the Game-Aware screen.
 *
 * Maintains per-game records in a DataStore (JSON-encoded per record),
 * wires [KnownGames] entries, and surfaces [GameEntry] items for the UI.
 *
 * Per-game settings are persisted independently of the profile store so they
 * do not bloat [ProfileStore] with a new field. Each record is stored as
 * a JSON string under the key "pgr_<packageName>".
 */
@HiltViewModel
class GameAwareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val json: Json,
) : ViewModel() {

    /** All saved user profiles — for the profile picker in the edit sheet. */
    val savedProfiles: StateFlow<List<UserProfile>> = profileRepository.store
        .map { it.profiles }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** User-saved per-game records, keyed by package name. */
    private val _perGameRecords = MutableStateFlow<Map<String, PerGameRecord>>(emptyMap())
    val perGameRecords: StateFlow<Map<String, PerGameRecord>> = _perGameRecords.asStateFlow()

    /** All game entries (known games + user-added), sorted: user-overridden first. */
    val gameEntries: StateFlow<List<GameEntry>> = _perGameRecords
        .map { records -> buildGameEntries(records) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, buildGameEntries(emptyMap()))

    /** Currently editing entry — drives the edit bottom sheet. */
    private val _editingEntry = MutableStateFlow<GameEntry?>(null)
    val editingEntry: StateFlow<GameEntry?> = _editingEntry.asStateFlow()

    init {
        loadRecords()
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    fun openEdit(entry: GameEntry) {
        _editingEntry.value = entry
    }

    fun closeEdit() {
        _editingEntry.value = null
    }

    /**
     * Save a per-game record for [packageName].
     */
    fun saveRecord(
        packageName: String,
        profileId: String?,
        autoTdpProfile: AutoTdpProfile?,
        fpsCapHz: Int?,
        learnedGood: Boolean,
    ) {
        val record = PerGameRecord(
            packageName = packageName,
            profileId = profileId,
            autoTdpProfile = autoTdpProfile,
            fpsCapHz = fpsCapHz,
            learnedGood = learnedGood,
        )
        viewModelScope.launch {
            persistRecord(record)
        }
        _editingEntry.value = null
    }

    fun removeRecord(packageName: String) {
        viewModelScope.launch {
            deleteRecord(packageName)
        }
        _editingEntry.value = null
    }

    fun markLearnedGood(packageName: String) {
        val currentRecord = _perGameRecords.value[packageName]
        viewModelScope.launch {
            persistRecord(
                PerGameRecord(
                    packageName = packageName,
                    profileId = currentRecord?.profileId,
                    autoTdpProfile = currentRecord?.autoTdpProfile,
                    fpsCapHz = currentRecord?.fpsCapHz,
                    learnedGood = true,
                ),
            )
        }
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private fun loadRecords() {
        viewModelScope.launch {
            context.gameAwareDataStore.data.collect { prefs ->
                val records = prefs.asMap()
                    .entries
                    .filter { (k, _) -> k.name.startsWith(PREFIX) }
                    .mapNotNull { (_, v) ->
                        val raw = v as? String ?: return@mapNotNull null
                        runCatching { json.decodeFromString<PerGameRecord>(raw) }.getOrNull()
                    }
                    .associateBy { it.packageName }
                _perGameRecords.value = records
            }
        }
    }

    private suspend fun persistRecord(record: PerGameRecord) {
        context.gameAwareDataStore.edit { prefs ->
            prefs[stringPreferencesKey("$PREFIX${record.packageName}")] =
                json.encodeToString(record)
        }
    }

    private suspend fun deleteRecord(packageName: String) {
        context.gameAwareDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("$PREFIX$packageName"))
        }
    }

    // ── Build entry list ───────────────────────────────────────────────────────

    private fun buildGameEntries(records: Map<String, PerGameRecord>): List<GameEntry> {
        val knownPackages = KNOWN_PACKAGES
        val knownEntries = knownPackages.map { pkg ->
            val hint = KnownGames.defaultHintFor(pkg)
            val record = records[pkg]
            GameEntry(
                packageName = pkg,
                appLabel = resolveLabel(pkg),
                knownHintAutoTdp = hint?.autoTdpProfile,
                userRecord = record,
                isKnown = true,
            )
        }

        val userOnlyEntries = records
            .filter { (pkg, _) -> pkg !in knownPackages }
            .map { (pkg, record) ->
                GameEntry(
                    packageName = pkg,
                    appLabel = resolveLabel(pkg),
                    knownHintAutoTdp = null,
                    userRecord = record,
                    isKnown = false,
                )
            }

        return (knownEntries + userOnlyEntries)
            .sortedWith(
                compareByDescending<GameEntry> { it.userRecord != null }
                    .thenByDescending { it.isKnown }
                    .thenBy { it.appLabel.lowercase() },
            )
    }

    private fun resolveLabel(packageName: String): String =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse {
            packageName.substringAfterLast(".").replaceFirstChar { c -> c.uppercase() }
        }

    companion object {
        private const val PREFIX = "pgr_"

        /**
         * All known emulator packages from [KnownGames]. Inlined here so the
         * ViewModel can iterate without adding an API to the pure data object.
         */
        val KNOWN_PACKAGES: Set<String> = setOf(
            "org.ppsspp.ppsspp",
            "org.ppsspp.ppssppgold",
            "org.dolphinemu.dolphinemu",
            "com.dolphin.mmjr",
            "com.dolphin.mmjr2",
            "xyz.aethersx2.android",
            "xyz.nethersx2.android",
            "org.citra.citra_emu",
            "org.citra.citra_canary",
            "com.azahar.emulator",
            "com.retroarch",
            "com.retroarch.ra32",
            "com.winlator",
            "com.gamenative",
            "com.eltechs.exagear",
            "org.yuzu.yuzu_emu",
            "org.yuzu.yuzu_emu.ea",
            "dev.eden.eden_emu",
            "com.sudachi.sudachi_emu",
            "com.sudachi.sudachi_emu.preview",
            "io.github.citron_emu.citron",
            "me.magnum.melonds",
            "com.github.stenzek.duckstation",
            "com.flycast.emulator",
            "com.flycast.emulator.gles",
            "info.cemu.Cemu",
            "net.rpcs3.rpcs3",
            "xyz.aps3e.android",
            "com.fbalpha4android",
            "com.mahbox.mame4all",
            "org.mupen64plusae.bonuspack.gliden64plus",
            "paulscode.android.mupen64plusae",
            "org.xemu.app",
        )
    }
}

// ── UI model ───────────────────────────────────────────────────────────────────

/**
 * One row in the game-aware list.
 */
data class GameEntry(
    val packageName: String,
    val appLabel: String,
    val knownHintAutoTdp: AutoTdpProfile?,
    val userRecord: PerGameRecord?,
    val isKnown: Boolean,
) {
    val effectiveAutoTdp: AutoTdpProfile?
        get() = userRecord?.autoTdpProfile ?: knownHintAutoTdp

    val isLearnedGood: Boolean
        get() = userRecord?.learnedGood == true

    val source: GamePlanSource
        get() = if (userRecord != null) GamePlanSource.USER_RECORD else GamePlanSource.KNOWN_GAME_HINT
}
