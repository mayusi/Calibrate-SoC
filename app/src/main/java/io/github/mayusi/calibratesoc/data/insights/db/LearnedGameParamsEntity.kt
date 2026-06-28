package io.github.mayusi.calibratesoc.data.insights.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted per-game learned AutoTDP parameters.
 *
 * Keyed by [pkg] (the Android package name, e.g. "com.rp.retroarch") — the
 * only stable, machine-readable identity for a game. One row per package;
 * upserted after each session via [LearnedGameParamsDao.upsert].
 *
 * All tuning fields are nullable: null means "not yet observed / not enough
 * data". Unit 1 fills these fields with real observations; Unit 0 only
 * declares the schema so other units can build without colliding.
 *
 * Column types match Room's SQL mapping exactly (Room schema validation is
 * strict — mismatch crashes on first open):
 *   Int?  → INTEGER (nullable)
 *   Long  → INTEGER NOT NULL
 *   Int   → INTEGER NOT NULL
 *
 * Schema first included in [BenchDatabase] v10.
 */
@Entity(tableName = "learned_game_params")
data class LearnedGameParamsEntity(

    /** Android package name — primary key and stable game identity. */
    @PrimaryKey val pkg: String,

    /**
     * Observed safe sustained CPU/GPU cap in kHz that kept the game thermally
     * stable across past sessions. Null until enough sessions are observed.
     */
    val safeSustainedCapKhz: Int? = null,

    /**
     * Median time in seconds from session start until the first thermal
     * throttle onset was observed. Null until observed.
     */
    val throttleOnsetSec: Int? = null,

    /**
     * Center of the AutoTDP band (% of max cap) that produced stable thermals
     * for this game historically. Null until observed.
     */
    val observedBandCenterPct: Int? = null,

    /** Number of sessions that contributed to these learned params. */
    val sessionCount: Int = 0,

    /** Wall-clock epoch ms of the last upsert. */
    val lastUpdatedMs: Long = 0,
)
