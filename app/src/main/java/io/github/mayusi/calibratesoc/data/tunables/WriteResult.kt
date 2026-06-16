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

    /**
     * Write went through and the kernel/system accepted it.
     *
     * [verified] is true when the write was CONFIRMED by a readback (the node was
     * re-read and matched the intended value). It is false for an ACCEPTED-BUT-UNVERIFIED
     * write: the privilege layer accepted the command but the node is not app-readable so
     * we could not confirm it landed (e.g. an AYANEO CPU-cap node that EACCESes the app
     * UID — the binder accepted the AIDL command, but we can't read scaling_max_freq back).
     *
     * HONESTY: an unverified Success is NOT proof the node moved — it lets AutoTDP proceed
     * without ever CLAIMING the node moved. HIGH-1: [TunableWriter.revertAll] treats an
     * unverified revert of a critical node (the CPU cap) as not-fully-confirmed and keeps
     * the journal so [BootRevertReceiver] remains the backstop. Defaults to true so every
     * existing verified writer (Root / Shizuku / PServer / verified AYANEO readbacks) keeps
     * its current "confirmed" semantics untouched.
     */
    data class Success(
        override val id: TunableId,
        val previousValue: String?,
        val newValue: String,
        val verified: Boolean = true,
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
