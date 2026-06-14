package io.github.mayusi.calibratesoc.data.tunables

/**
 * Honest representation of the voltage / undervolt capability on stock
 * Snapdragon (and virtually all locked-bootloader) Android devices.
 *
 * The situation is simple: there is NO sysfs path for CPU or GPU voltage
 * control on stock Qualcomm kernels. This is not a missing feature in
 * Calibrate SoC — it is a hardware/kernel limitation:
 *
 *   - CPU voltage is managed entirely by the on-die voltage regulator (PMIC)
 *     under direct control of the DVFS firmware. There is no userspace sysfs
 *     node that exposes voltage control.
 *   - GPU voltage (Adreno) can be adjusted ONLY via Device Tree Blob (DTB)
 *     patching on kernels with an unlocked bootloader. The technique is
 *     known as "KonaBess GPU DTB undervolt" and requires:
 *       a) unlocked bootloader
 *       b) custom kernel / patched DTB
 *     Neither is present on factory-locked devices.
 *
 * This object surfaces that honest statement so the UI can show a
 * truthful "Voltage control: not available on this device" card instead
 * of hiding the section entirely (which would leave users wondering why
 * comparable apps on desktop can tune voltage).
 *
 * This is DATA only — no sysfs probe, no write path, no TunableId.
 */
object VoltageControl {

    /** Availability status — always [UNAVAILABLE_STOCK_KERNEL] on production devices. */
    enum class Availability {
        /**
         * No sysfs path for voltage exists. Stock kernel, locked bootloader.
         * All Qualcomm devices shipped since SM8x50 (Snapdragon 855+) and
         * all MediaTek devices fall into this category.
         */
        UNAVAILABLE_STOCK_KERNEL,
        /**
         * A custom kernel with voltage tables patched into the DTB is running.
         * Calibrate SoC does not attempt to detect or expose this path —
         * it would require parsing and modifying binary DTB blobs, which is
         * out of scope. Users with custom kernels should use dedicated tools
         * (KonaBess, EX Kernel Manager, etc.).
         */
        REQUIRES_CUSTOM_KERNEL,
    }

    /**
     * What voltage control requires — displayed to the user.
     *
     * This is intentionally longer than a typical description because
     * "voltage control" is a feature users frequently ask about and
     * confusion about why it's absent is common.
     */
    val cpuVoltageAvailability: Availability = Availability.UNAVAILABLE_STOCK_KERNEL

    val cpuVoltageUnavailableExplanation: String =
        "CPU voltage control is not available on stock Snapdragon / MediaTek kernels. " +
            "The PMIC and DVFS firmware handle voltage automatically and do not expose " +
            "a writable sysfs node. Undervolting requires a custom kernel patched with " +
            "modified voltage tables — this cannot be done via sysfs writes."

    val gpuVoltageAvailability: Availability = Availability.UNAVAILABLE_STOCK_KERNEL

    val gpuVoltageUnavailableExplanation: String =
        "GPU undervolt (Adreno) is not possible on stock kernels or locked bootloaders. " +
            "The technique (KonaBess-style DTB patching) requires an unlocked bootloader " +
            "and a custom kernel. Calibrate SoC does not support this path; use KonaBess " +
            "or EX Kernel Manager with a compatible custom kernel instead."

    /**
     * Single summary for the UI Capability card.
     */
    val unavailableSummary: String =
        "Voltage control: not possible on this device. " +
            "CPU and GPU voltage is managed by firmware; no sysfs path exists. " +
            "A custom kernel with unlocked bootloader is required."
}
