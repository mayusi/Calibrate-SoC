package io.github.mayusi.calibratesoc.data.tunables.writer

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vendor preset flipper. AYN's perf / fan modes are implemented as
 * Settings.System keys that the OEM system app subscribes to; flipping
 * the key triggers the real kernel change inside their service. This is
 * the same pathway langerhans `OdinTools` uses and it works on stock
 * firmware without root.
 *
 * Requires WRITE_SECURE_SETTINGS, which the user grants via Shizuku
 * (`adb shell pm grant ${appId} android.permission.WRITE_SECURE_SETTINGS`)
 * or root. We don't ask Android for it because it's a signature/system
 * permission and would otherwise be denied silently.
 *
 * VENDOR_INTENT support (AYANEO AYASpace) is stubbed — those vendors
 * dispatch via private binder. We surface them as CapabilityDenied with
 * a clear "unsupported on this device" message rather than silently
 * doing nothing.
 */
@Singleton
class SettingsKeyWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : SysfsWriter {

    override suspend fun read(id: TunableId): String? = withContext(Dispatchers.IO) {
        when (id.kind) {
            TunableKind.SETTINGS_SYSTEM -> runCatching {
                Settings.System.getString(context.contentResolver, id.target)
            }.getOrNull()
            TunableKind.VENDOR_INTENT, TunableKind.SYSFS -> null
        }
    }

    override suspend fun canWrite(id: TunableId): Boolean = id.kind == TunableKind.SETTINGS_SYSTEM

    override suspend fun write(id: TunableId, value: String): WriteResult =
        withContext(Dispatchers.IO) {
            when (id.kind) {
                TunableKind.SETTINGS_SYSTEM -> {
                    val previous = runCatching {
                        Settings.System.getString(context.contentResolver, id.target)
                    }.getOrNull()
                    val ok = runCatching {
                        Settings.System.putString(context.contentResolver, id.target, value)
                    }.getOrDefault(false)
                    if (ok) WriteResult.Success(id, previous, value)
                    else WriteResult.Rejected(
                        id = id,
                        errno = null,
                        message = "Settings.System.putString returned false — likely missing " +
                            "WRITE_SECURE_SETTINGS (grant via adb or root).",
                    )
                }
                TunableKind.VENDOR_INTENT -> WriteResult.CapabilityDenied(
                    id = id,
                    reason = "VENDOR_INTENT writer not implemented (AYANEO AYASpace path pending).",
                )
                TunableKind.SYSFS -> WriteResult.CapabilityDenied(
                    id = id,
                    reason = "SettingsKeyWriter does not handle SYSFS.",
                )
            }
        }

    /**
     * Writes a vendor [SETTINGS_SYSTEM] key that is SUPPOSED to drive a specific
     * kernel node, then READS BACK that node to confirm the write actually moved
     * the kernel — not just the Settings database row.
     *
     * Why this exists (the AYANEO honesty problem): on AYN/Retroid the vendor
     * service subscribes to keys like `performance_mode` / `fan_mode` and flips a
     * real kernel node when the Settings row changes. On AYANEO (and some GPD
     * firmware) fan/perf are driven by a PRIVATE BINDER, not these Settings keys —
     * so `putString` SUCCEEDS but NOTHING changes in the kernel. We must NOT report
     * such a write as a live tuning success.
     *
     * Contract:
     *   - Snapshots [kernelNodePath] BEFORE the key write.
     *   - Writes the key via [write].
     *   - Re-reads [kernelNodePath] AFTER. If the value changed (or matches the
     *     caller-supplied [expectedNodeValue] when given), the key is LIVE on this
     *     device → [WriteResult.Success] (newValue = the key value we wrote).
     *   - If the node did NOT move, the key is INERT on this device → a
     *     [WriteResult.Rejected] that honestly says the key changed nothing in the
     *     kernel, so callers do NOT count it as a live cpufreq/kernel path.
     *
     * Read access: sysfs nodes are world-readable on essentially all kernels, so a
     * plain app-UID file read suffices to OBSERVE movement even though we cannot
     * write the node directly. If the node cannot be read at all we return the raw
     * key-write result (we cannot prove inertness, so we do not fabricate it) but
     * flag the uncertainty in the message — never a false "live" claim.
     *
     * @param expectedNodeValue Optional. When non-null, the node must equal this
     *   exact (trimmed) value after the write for it to count as moved. When null,
     *   ANY change from the pre-write snapshot counts as moved.
     */
    suspend fun writeAndVerifyKernelNode(
        id: TunableId,
        value: String,
        kernelNodePath: String,
        expectedNodeValue: String? = null,
    ): WriteResult = withContext(Dispatchers.IO) {
        if (id.kind != TunableKind.SETTINGS_SYSTEM) {
            return@withContext WriteResult.CapabilityDenied(
                id,
                "writeAndVerifyKernelNode requires a SETTINGS_SYSTEM key.",
            )
        }

        val nodeBefore = readKernelNode(kernelNodePath)

        val keyResult = write(id, value)
        // If the key write itself failed, surface that as-is — nothing to verify.
        if (keyResult !is WriteResult.Success) return@withContext keyResult

        val nodeAfter = readKernelNode(kernelNodePath)

        when (evaluateNodeMovement(nodeBefore, nodeAfter, expectedNodeValue)) {
            NodeMovement.MOVED ->
                // The vendor subscribes to this key on THIS device — genuinely live.
                WriteResult.Success(id, previousValue = nodeBefore, newValue = value)
            NodeMovement.UNREADABLE ->
                // We could not read the node at all — cannot prove inertness. Return
                // the key-write success but make the uncertainty explicit so the caller
                // does not silently assume a live kernel effect.
                WriteResult.Rejected(
                    id = id,
                    errno = null,
                    message = "Vendor key '${id.target}' was written, but the kernel node " +
                        "'$kernelNodePath' could not be read to confirm the effect — treating as " +
                        "UNVERIFIED, not a live kernel write.",
                )
            NodeMovement.INERT ->
                // Key write succeeded but the kernel node did NOT move → INERT here.
                WriteResult.Rejected(
                    id = id,
                    errno = null,
                    message = "Vendor key '${id.target}' was written but kernel node " +
                        "'$kernelNodePath' did not change (still '$nodeAfter'). This key is INERT " +
                        "on this device — the vendor likely drives fan/perf via a private binder, " +
                        "not this Settings key. Not counted as a live kernel write.",
                )
        }
    }

    /** Outcome of comparing a kernel node before/after a vendor-key write. */
    internal enum class NodeMovement {
        /** The node changed (or matched the expected value) — the key is live here. */
        MOVED,
        /** The node could not be read at all — movement is UNVERIFIED. */
        UNREADABLE,
        /** The node was readable but did not move — the key is INERT here. */
        INERT,
    }

    /**
     * Pure decision: did the kernel node move in response to the vendor-key write?
     *
     * - When [expectedNodeValue] is non-null, the node must equal it (trimmed) AFTER
     *   the write to count as MOVED.
     * - Otherwise, ANY change from [nodeBefore] to [nodeAfter] counts as MOVED.
     * - If BOTH reads are null (node unreadable), the result is UNREADABLE — we never
     *   fabricate a "live" result we couldn't observe.
     * - A readable node that did not change is INERT.
     *
     * Extracted as a pure function so the honesty invariant is unit-testable without
     * the Android Settings framework.
     */
    internal fun evaluateNodeMovement(
        nodeBefore: String?,
        nodeAfter: String?,
        expectedNodeValue: String?,
    ): NodeMovement {
        if (nodeBefore == null && nodeAfter == null) return NodeMovement.UNREADABLE
        val moved = when {
            expectedNodeValue != null -> nodeAfter?.trim() == expectedNodeValue.trim()
            else -> nodeAfter != null && nodeAfter != nodeBefore
        }
        return if (moved) NodeMovement.MOVED else NodeMovement.INERT
    }

    /** Reads a sysfs node at the app UID. Sysfs is world-readable on all known
     *  kernels, so this observes the node even when we cannot WRITE it. Returns
     *  null on any error (absent node, permission, etc.). */
    private fun readKernelNode(path: String): String? =
        runCatching { java.io.File(path).readText().trim().ifBlank { null } }.getOrNull()
}
