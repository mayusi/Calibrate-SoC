package io.github.mayusi.calibratesoc.data.baseline

import io.github.mayusi.calibratesoc.data.tunables.TunableSnapshot
import kotlinx.serialization.Serializable

/**
 * Frozen stock state captured on the FIRST launch of the app, BEFORE
 * any user action has had a chance to mutate it. Functionally the
 * "factory reset" target — distinct from the running snapshot journal
 * which only records values we've personally written.
 *
 * Why we need this separate from [TunableSnapshot]: the snapshot
 * journal captures what was visible at the moment of OUR first write,
 * which on a device that's already been tuned (AYN's stock UI was
 * used, langerhans OdinTools applied a preset, TheOldTaylor's script
 * was run earlier) could be very far from the factory-default values.
 * The factory baseline is the absolute reference point.
 *
 * Stored at /data/data/.../files/factory_baseline.json. Written
 * exactly once per install — re-launches read it but never overwrite
 * (unless the user explicitly resets via Settings).
 */
@Serializable
data class FactoryBaseline(
    val capturedAtMs: Long,
    val appVersionAtCapture: String,
    val deviceModel: String,
    val socModel: String,
    val tunables: List<TunableSnapshot>,
)
