package io.github.mayusi.calibratesoc.data.tunables

/**
 * Result of a single tunable write attempt. Carries enough detail for
 * the UI to explain failures and for the snapshot store to decide
 * whether to record a revert entry.
 *
 * The [TunableWriter] wrapper guarantees that a `Success` is preceded by
 * a snapshot record; callers don't need to think about that.
 */
sealed interface WriteResult {
    val id: TunableId

    /** Write went through and the kernel/system accepted it. */
    data class Success(
        override val id: TunableId,
        val previousValue: String?,
        val newValue: String,
    ) : WriteResult

    /** Capability tier disallows this write — e.g. root-only tunable on a
     *  Shizuku tier. UI should never have surfaced the control. */
    data class CapabilityDenied(
        override val id: TunableId,
        val reason: String,
    ) : WriteResult

    /** Privilege layer accepted the call but the kernel/system rejected
     *  it — SELinux denial, EBUSY (governor protecting an OPP), etc. */
    data class Rejected(
        override val id: TunableId,
        val errno: Int?,
        val message: String,
    ) : WriteResult

    /** Unexpected failure (IO, binder death, etc.). */
    data class Failed(
        override val id: TunableId,
        val error: Throwable,
    ) : WriteResult
}
