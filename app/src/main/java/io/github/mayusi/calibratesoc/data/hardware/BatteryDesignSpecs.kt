package io.github.mayusi.calibratesoc.data.hardware

/**
 * Device-DB fallback for battery design capacity when
 * /sys/class/power_supply/battery/charge_full_design is SELinux-denied
 * (e.g. vendor_sysfs_battery_supply label on kalama / Snapdragon 8 Gen 2
 * firmware used in Retroid Pocket 6 and AYN Thor).
 *
 * Values are VERIFIED from live sysfs reads or OEM published specs.
 * DO NOT add unverified values — return null and let the UI say so.
 *
 * Matching style mirrors [StorageClassNames.lookupByDevice].
 */
object BatteryDesignSpecs {

    /**
     * Returns the design capacity in mAh for a known device, or null if
     * the device is unknown / unverified.
     *
     * @param buildModel [android.os.Build.MODEL]
     */
    fun lookupByModel(buildModel: String): Int? = when {
        // AYN Odin 3 — verified live via sysfs: charge_full_design=7838000 µAh
        buildModel.equals("Odin3", ignoreCase = true) ||
            buildModel.equals("Odin 3", ignoreCase = true) ||
            buildModel.contains("Odin3", ignoreCase = true) -> 7838

        // Retroid Pocket 6 — verified live via sysfs: charge_full_design=6442000 µAh
        buildModel.contains("Pocket 6", ignoreCase = true) ||
            buildModel.contains("RetroidPocket6", ignoreCase = true) -> 6442

        // AYN Thor — no verified number available; omit rather than guess.
        // Do NOT add a placeholder here.

        else -> null
    }
}
