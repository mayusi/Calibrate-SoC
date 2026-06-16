package io.github.mayusi.calibratesoc.data.backup

import io.github.mayusi.calibratesoc.data.benchmark.BenchRepository
import io.github.mayusi.calibratesoc.data.benchmark.BenchRun
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.ProfileStore
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.remote.ValidationRegexes
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup/restore engine for CalibrateSoC.
 *
 * Exports ALL user data (profiles, per-app overrides, tune history, benchmark & stability runs)
 * to a single JSON file. Imports reverses the process — additive merge policy (no duplicates
 * removed, new data appended to existing).
 *
 * Note: StabilityRun is not @Serializable so stability runs are excluded from backup.
 * Only benchmark runs (BenchRun) are included.
 */
@Singleton
class BackupManager @Inject constructor(
    private val profileRepo: ProfileRepository,
    private val benchRepo: BenchRepository,
    private val tuneHistoryStore: TuneHistoryStore,
    private val json: Json,
) {
    /**
     * Snapshot all data and return as a BackupBundle.
     */
    suspend fun export(): BackupBundle {
        val profileStore = profileRepo.store.first()
        val benchRuns = benchRepo.observeAll().first()
        val tuneEntries = tuneHistoryStore.entries.first()

        return BackupBundle(
            schemaVersion = 1,
            appVersion = "1.0", // Will be injected or read from BuildConfig in real use
            exportedAtMs = System.currentTimeMillis(),
            profiles = profileStore.profiles,
            perAppOverrides = profileStore.perAppOverrides,
            tuneHistory = tuneEntries,
            benchmarkRuns = benchRuns,
        )
    }

    /**
     * Serialize a BackupBundle to JSON string with pretty printing.
     */
    suspend fun serialize(bundle: BackupBundle): String {
        return json.encodeToString(bundle)
    }

    /**
     * Import a JSON backup string. Merges additively:
     * - Profiles: inserts all (duplicates by ID are replaced)
     * - Per-app overrides: merges (later overrides earlier)
     * - Tune history: appends all (respects MAX_ENTRIES on next write via TuneHistoryStore)
     * - Benchmark runs: inserts all
     *
     * Returns an ImportResult summarizing what was restored + any errors.
     */
    suspend fun import(json: String): ImportResult {
        val errors = mutableListOf<String>()
        var profilesRestored = 0
        var tuneEntriesRestored = 0
        var benchRunsRestored = 0

        // Parse the JSON. kotlinx.serialization with ignoreUnknownKeys handles
        // malformed JSON and missing required fields by throwing, which is caught here.
        val bundle = runCatching {
            this.json.decodeFromString<BackupBundle>(json)
        }.getOrElse { e ->
            errors.add("Failed to parse backup JSON: ${e.message}")
            return ImportResult(
                profilesRestored = 0,
                tuneEntriesRestored = 0,
                benchRunsRestored = 0,
                stabilityRunsRestored = 0,
                errors = errors,
            )
        }

        // Abort on a schema version we don't understand — continuing would import
        // fields whose semantics we don't know, which could feed unvalidated data
        // into the script generator. A crafted newer-version backup is rejected
        // loudly here rather than silently importing garbage.
        if (bundle.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            errors.add(
                "Backup schema version ${bundle.schemaVersion} is newer than supported " +
                    "($SUPPORTED_SCHEMA_VERSION). Upgrade the app to import this backup.",
            )
            return ImportResult(
                profilesRestored = 0,
                tuneEntriesRestored = 0,
                benchRunsRestored = 0,
                stabilityRunsRestored = 0,
                errors = errors,
            )
        }

        // Restore profiles — validate each one before saving.
        // Rejection of a single invalid profile is recorded as an error but
        // does not abort the rest of the import.
        runCatching {
            for (profile in bundle.profiles) {
                val validationError = validateProfile(profile)
                if (validationError != null) {
                    errors.add("Skipping profile '${profile.id}': $validationError")
                    continue
                }
                profileRepo.saveProfile(profile)
                profilesRestored++
            }
        }.onFailure { e ->
            errors.add("Error restoring profiles: ${e.message}")
        }

        // Restore per-app overrides: merge them into the current store.
        runCatching {
            val currentStore = profileRepo.store.first()
            val mergedOverrides = currentStore.perAppOverrides + bundle.perAppOverrides
            // Re-save all profiles to update the store with the merged overrides.
            // This is a bit indirect, but we use the existing saveProfile method which
            // updates the full store. So we'll do a targeted update via setOverride.
            for ((packageName, profileId) in bundle.perAppOverrides) {
                profileRepo.setOverride(packageName, profileId)
            }
        }.onFailure { e ->
            errors.add("Error restoring per-app overrides: ${e.message}")
        }

        // Restore tune history entries.
        runCatching {
            for (entry in bundle.tuneHistory) {
                tuneHistoryStore.append(entry)
                tuneEntriesRestored++
            }
        }.onFailure { e ->
            errors.add("Error restoring tune history: ${e.message}")
        }

        // Restore benchmark runs.
        runCatching {
            for (run in bundle.benchmarkRuns) {
                benchRepo.save(run)
                benchRunsRestored++
            }
        }.onFailure { e ->
            errors.add("Error restoring benchmark runs: ${e.message}")
        }

        return ImportResult(
            profilesRestored = profilesRestored,
            tuneEntriesRestored = tuneEntriesRestored,
            benchRunsRestored = benchRunsRestored,
            stabilityRunsRestored = 0, // Not serializable, excluded.
            errors = errors,
        )
    }

    /**
     * Validate a [UserProfile] imported from a backup before it is saved.
     *
     * Returns null when the profile is acceptable, or a human-readable error
     * string describing the first failing constraint. Caller logs/records the
     * error and skips the profile.
     *
     * **Why this exists:** imported profiles feed directly into [AynScriptGenerator],
     * which interpolates [UserProfile.name], [UserProfile.cpuPolicyGovernor], and
     * [UserProfile.gpuGovernor] into generated root scripts. Shell injection is
     * mitigated at the script-generation layer (via shellSingleQuote), but
     * defence-in-depth means we also reject at import time so malformed data
     * never reaches the script generator in the first place.
     *
     * Validation rules:
     *  - Free-text DISPLAY fields (name, description): these never reach the shell
     *    un-escaped — the script generator [commentSafe]/[shellSingleQuote]-escapes
     *    everything it emits. So they only need protection against ASCII control
     *    characters + line breaks (which could break a single-line UI label or a
     *    script comment). Human punctuation like `/ ( ) & —` is legitimate in a
     *    display name ("PS2 / GameCube (Sustained)") and must be allowed.
     *  - Governor strings (cpuPolicyGovernor values, gpuGovernor): reject shell
     *    metacharacters AND whitespace AND `/`, since kernel governor names are
     *    always a single lowercase token (e.g. "schedutil", "performance"). These
     *    DO reach the script as an executable value, so they stay strict.
     */
    internal fun validateProfile(profile: UserProfile): String? {
        // Shared canonical regexes — single source of truth in ValidationRegexes.
        val displayUnsafe = ValidationRegexes.DISPLAY_UNSAFE
        val governorInvalid = ValidationRegexes.GOVERNOR_INVALID

        if (displayUnsafe.containsMatchIn(profile.name)) {
            return "name contains control characters"
        }
        if (profile.name.isBlank()) {
            return "name must not be blank"
        }
        if (displayUnsafe.containsMatchIn(profile.description)) {
            return "description contains control characters"
        }
        for ((policyId, gov) in profile.cpuPolicyGovernor) {
            if (governorInvalid.containsMatchIn(gov)) {
                return "cpuPolicyGovernor[$policyId] '$gov' contains disallowed characters"
            }
            if (gov.isBlank()) {
                return "cpuPolicyGovernor[$policyId] must not be blank"
            }
        }
        profile.gpuGovernor?.let { gov ->
            if (governorInvalid.containsMatchIn(gov)) {
                return "gpuGovernor '$gov' contains disallowed characters"
            }
            if (gov.isBlank()) {
                return "gpuGovernor must not be blank"
            }
        }
        // extraSysfs carries arbitrary kernel paths + values that can become a
        // root-script write. The apply path (ProfileApplier) validates these too,
        // but we reject at the import trust boundary as well (defence-in-depth) so
        // a malicious backup/shared profile never reaches the saved store with an
        // unsafe path. Mirrors the OTA validator's extraSysfs check.
        for ((path, value) in profile.extraSysfs) {
            TunableMetadata.validateCustomSysfsPath(path)?.let { err ->
                return "extraSysfs path '$path' is invalid: $err"
            }
            // SEC-3: mirror the OTA validator (RemoteContentValidator.validatePreset).
            // For an UNKNOWN path, TunableMetadata.forId returns a RAW_STRING whose
            // validate() returns null — i.e. NO value check at all — so without this
            // a crafted backup/share could carry a value like "0; reboot" or a control
            // char straight into the script generator. The generator shell-quotes its
            // output, but we reject shell metacharacters + control chars at this trust
            // boundary too (defence-in-depth, identical rule to the OTA path).
            if (ValidationRegexes.SHELL_META.containsMatchIn(value)) {
                return "extraSysfs value for '$path' contains disallowed characters"
            }
            // Then apply any value-kind constraint the metadata declares (range/enum/bool).
            val valueError = TunableMetadata
                .forId(TunableId(kind = TunableKind.SYSFS, target = path))
                .validate(value)
            if (valueError != null) {
                return "extraSysfs value for '$path' is invalid: $valueError"
            }
        }
        return null
    }

    companion object {
        /** The only schema version this build understands. Imports with a higher
         *  version are rejected outright rather than silently importing unknown fields. */
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

/**
 * Backup bundle: all exportable data in one structure.
 */
@Serializable
data class BackupBundle(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val exportedAtMs: Long,
    val profiles: List<io.github.mayusi.calibratesoc.data.profiles.UserProfile>,
    val perAppOverrides: Map<String, String>,
    val tuneHistory: List<TuneHistoryEntry>,
    val benchmarkRuns: List<BenchRun>,
)

/**
 * Result of an import operation.
 */
data class ImportResult(
    val profilesRestored: Int,
    val tuneEntriesRestored: Int,
    val benchRunsRestored: Int,
    val stabilityRunsRestored: Int,
    val errors: List<String>,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val allOk: Boolean get() = !hasErrors

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (profilesRestored > 0) parts.add("$profilesRestored profile${if (profilesRestored != 1) "s" else ""}")
        if (tuneEntriesRestored > 0) parts.add("$tuneEntriesRestored tune ${if (tuneEntriesRestored != 1) "entries" else "entry"}")
        if (benchRunsRestored > 0) parts.add("$benchRunsRestored benchmark run${if (benchRunsRestored != 1) "s" else ""}")

        return if (parts.isEmpty()) {
            "No data to restore"
        } else {
            "Restored " + parts.joinToString(", ") + "."
        }
    }
}
