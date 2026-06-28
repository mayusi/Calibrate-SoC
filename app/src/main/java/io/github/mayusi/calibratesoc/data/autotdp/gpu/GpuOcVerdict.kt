package io.github.mayusi.calibratesoc.data.autotdp.gpu

/**
 * UNIT 3 (ADAPTIVE MODE) — the HONEST verdict of the beyond-stock GPU-OC accept-probe.
 *
 * Adaptive mode's GPU control has two overclock tiers (see [io.github.mayusi.calibratesoc
 * .data.autotdp.adaptive.GpuOcTier]):
 *
 *  - WITHIN_VENDOR — pin the top *stock* devfreq level. Safe, needs no probe; handled
 *    elsewhere (Unit 2's band controller, Unit 5's actuation).
 *  - BEYOND_STOCK — write `max_freq` ABOVE the stock ceiling, where the kernel allows it.
 *    This tier is the only one [GpuOcProber] reasons about. It NEVER claims success it did
 *    not observe: BEYOND_STOCK is only legal when the live probe returns [Accepted].
 *
 * The four verdicts are mutually exclusive and exhaustive. Every non-[Accepted] verdict
 * forces the coordinator to fall back to the stock ceiling (WITHIN_VENDOR at most) — a
 * beyond-stock write is NEVER left applied on a non-Accepted verdict.
 *
 * PURE: a plain value type — no Android, I/O, or time. [GpuOcProber.classifyProbe] derives
 * it from raw observed numbers so the decision logic is unit-testable without real sysfs.
 */
sealed interface GpuOcVerdict {

    /**
     * The kernel exposes NO OPP above the stock ceiling — there is simply no headroom to
     * probe (max(available_frequencies) <= stock ceil). The device can never go
     * beyond-stock; the coordinator forces WITHIN_VENDOR. This is the honest "no such
     * lever" answer, distinct from a lever that exists but the kernel refused.
     */
    object Unsupported : GpuOcVerdict

    /**
     * The kernel CLAMPED the beyond-stock write: we wrote [GpuOcProber]'s OC target to
     * `max_freq`, read it back, and the readback did not equal what we wrote. The kernel
     * silently pinned `max_freq` to [clampedTo] (typically the stock ceiling or the nearest
     * legal OPP). Beyond-stock is impossible on this kernel; the prober restores the stock
     * ceiling and the coordinator stays WITHIN_VENDOR.
     *
     * @property clampedTo the value the kernel actually accepted (the readback), in Hz.
     */
    data class Rejected(val clampedTo: Long) : GpuOcVerdict

    /**
     * The kernel ACCEPTED the raised `max_freq` cap (readback matched the write) — but
     * under a bounded controlled load the GPU clock NEVER actually rose above the stock
     * ceiling. The cap is honoured on paper yet the silicon/governor never reaches into the
     * new headroom, so beyond-stock buys nothing real. We do NOT claim Accepted — raising a
     * cap the clock never touches is not an overclock. The coordinator stays WITHIN_VENDOR.
     */
    object Ineffective : GpuOcVerdict

    /**
     * The ONLY positive verdict: the kernel accepted the raised cap AND, under a bounded
     * controlled load, the GPU clock was OBSERVED to exceed the stock ceiling. Beyond-stock
     * is real on this device. The prober still restores the stock ceiling on its way out —
     * the coordinator re-applies the beyond-stock cap deliberately, so the probe itself
     * never leaves a beyond-stock write stuck.
     *
     * @property reachedHz the highest GPU clock observed during the probe load, in Hz —
     *                     strictly greater than the stock ceiling by construction.
     */
    data class Accepted(val reachedHz: Long) : GpuOcVerdict
}
