package io.github.mayusi.calibratesoc.data.autotdp.adaptive

import io.github.mayusi.calibratesoc.data.autotdp.GoalParams
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile

/**
 * UNIT 1 (ADAPTIVE MODE) — the RESOLVED OUTPUT of [AdaptivePolicy.resolve].
 *
 * This is the STABLE PUBLIC API that Units 2 (the adaptive engine wiring) and 5 (the
 * snap/actuation layer) consume. It is a complete, control-ready description of what
 * adaptive mode wants this session: which CPU goal + its params to ride, the GPU band /
 * floor / OC tier / soft-temp, and the DDR bias. Everything here is derived purely from
 * the [AdaptiveIntent] weights (plus tier gating from caps/opt-in/probe) — it carries no
 * device-specific snapped values; Unit 5 snaps the band/floor onto the real OPP table.
 *
 * NAME THESE EXACTLY: siblings compile against [AdaptiveSetpoints], [GpuBand],
 * [GpuOcTier], [DdrBias]. Reuses the existing [GoalProfile] / [GoalParams] types so the
 * CPU goal slots straight into the band controller with no translation.
 *
 * PURE: a plain value object — no Android, I/O, or time.
 *
 * @property cpuGoal          which [GoalProfile] the band controller rides for the CPU.
 * @property cpuGoalParams    the per-mode [GoalParams]; adaptive only ever TIGHTENS the
 *                            temp ceiling onto it (strictly safer), never loosens.
 * @property gpuBand          the resolved GPU busy% target window [low, high].
 * @property gpuFloorFraction the minimum GPU clock as a fraction (0.15..0.90) of the
 *                            device's usable GPU range — the floor the engine never
 *                            drops the GPU below. Snapped to a real level by Unit 5.
 * @property gpuOcTier        how far GPU overclock is allowed (gated by opt-in + probe).
 * @property gpuSoftTempC     GPU soft die-temp (°C) for thermal pre-emption.
 * @property ddrBias          DDR/bus frequency-governor bias.
 */
data class AdaptiveSetpoints(
    val cpuGoal: GoalProfile,
    val cpuGoalParams: GoalParams,
    val gpuBand: GpuBand,
    val gpuFloorFraction: Float,
    val gpuOcTier: GpuOcTier,
    val gpuSoftTempC: Int,
    val ddrBias: DdrBias,
)

/**
 * A GPU busy% target window: the controller hunts the lowest GPU clock that keeps GPU
 * busy% inside [low]..[high]. Mirrors [GoalProfile]'s band semantics, but adaptive mode
 * synthesizes the edges from the intent weights instead of picking a fixed curated band.
 *
 * @property low  lower edge of the GPU busy% band (inclusive), 20..80.
 * @property high upper edge of the GPU busy% band (inclusive), 30..95.
 */
data class GpuBand(
    val low: Int,
    val high: Int,
) {
    /** Band width in points (high - low). */
    val widthPct: Int get() = high - low
}

/**
 * How far adaptive mode is allowed to overclock the GPU.
 *
 *  - [OFF]           no overclock; stay within the stock/default ceiling.
 *  - [WITHIN_VENDOR] push to the vendor-published top level (still "supported" silicon).
 *  - [BEYOND_STOCK]  go past the stock ceiling — ONLY when the user opted in AND the
 *                    OC probe passed (gated by all three conditions in the policy).
 */
enum class GpuOcTier { OFF, WITHIN_VENDOR, BEYOND_STOCK }

/**
 * DDR / memory-bus frequency-governor bias.
 *
 *  - [LOW]    bias the bus toward low frequencies (battery/efficiency lean).
 *  - [NORMAL] leave the vendor default bias.
 *  - [HIGH]   bias the bus toward high frequencies (performance lean — fewer stalls).
 */
enum class DdrBias { LOW, NORMAL, HIGH }
