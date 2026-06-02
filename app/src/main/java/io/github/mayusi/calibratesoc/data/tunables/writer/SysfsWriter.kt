package io.github.mayusi.calibratesoc.data.tunables.writer

import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.WriteResult

/**
 * Backend for writing a single value to a kernel sysfs path or Settings
 * key. Each capability tier ships a concrete implementation; the
 * [WriterRegistry] picks one at runtime based on the CapabilityReport.
 *
 * Implementations MUST be safe to call from any thread; the higher-level
 * TunableWriter dispatches them on Dispatchers.IO.
 *
 * They MUST NOT snapshot — that's the [io.github.mayusi.calibratesoc.data.tunables.TunableWriter]
 * wrapper's job. Snapshot-once / write-once invariants are easier to
 * keep when every implementor is a thin adapter.
 */
interface SysfsWriter {
    /** Returns the current value if reachable, else null. Used for the
     *  pre-write snapshot AND for read-back verification after writing. */
    suspend fun read(id: TunableId): String?

    /** Returns true if this writer can serve [id] on the current device. */
    suspend fun canWrite(id: TunableId): Boolean

    /** Performs the write. Implementations should NOT verify the result —
     *  the wrapper re-reads and compares. */
    suspend fun write(id: TunableId, value: String): WriteResult
}
