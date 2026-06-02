package io.github.mayusi.calibratesoc.data.tunables

import kotlinx.serialization.Serializable

/**
 * One pre-write snapshot record. The store persists a list of these so a
 * reboot can replay the original values back via the same writer chain.
 *
 * `previousValue` may be null when the path didn't exist or was empty
 * before the first write — on revert we then DELETE / NO-OP rather than
 * writing a literal empty string, which most kernel parsers reject.
 */
@Serializable
data class TunableSnapshot(
    val id: TunableId,
    val previousValue: String?,
    val writtenAtMs: Long,
    /** Free-form context — e.g. "applied preset Balanced", "manual Tune
     *  UI change". Helps debug a misbehaving preset. */
    val reason: String,
)

/** Container persisted as JSON to /data/data/.../files/last_known_good.json. */
@Serializable
data class SnapshotJournal(
    val version: Int = 1,
    val entries: List<TunableSnapshot> = emptyList(),
)
