package io.github.mayusi.calibratesoc.data.hardware

/**
 * Map UFS chip model strings (read from /sys/block/sda/device/model)
 * to a storage class label. UFS chip vendors put their part number
 * in that file; we look up the prefix against a small table.
 *
 * Coverage is intentionally narrow — we list only chips we've seen
 * in handhelds + recent flagship phones. Unknown models fall back to
 * inferring from /sys/block/sda/device/rev (UFS spec version).
 */
object StorageClassNames {

    data class Entry(
        val modelPrefix: String,
        val vendor: String,
        val storageClass: String,
    )

    private val ENTRIES: List<Entry> = listOf(
        // Samsung KLUEG / KLUDG / KLUEK / KLUFG series
        Entry("KLUDG4", "Samsung", "UFS 3.1"),
        Entry("KLUEG4", "Samsung", "UFS 3.1"),
        Entry("KLUEG8", "Samsung", "UFS 4.0"),
        Entry("KLUDG8", "Samsung", "UFS 4.0"),
        Entry("KLUFG", "Samsung", "UFS 4.0"),
        Entry("KLUEG", "Samsung", "UFS 3.1"),
        Entry("KLM", "Samsung", "eMMC 5.1"),

        // Micron MTFC
        Entry("MT128GA", "Micron", "UFS 3.1"),
        Entry("MT256GA", "Micron", "UFS 3.1"),
        Entry("MT512GA", "Micron", "UFS 4.0"),
        Entry("MT1T0GA", "Micron", "UFS 4.0"),

        // SK Hynix H28U / H9HQ
        Entry("H28U", "SK Hynix", "UFS 3.1"),
        Entry("H9HQ", "SK Hynix", "UFS 4.0"),
        Entry("HN8T", "SK Hynix", "UFS 4.0"),

        // Sandisk / Western Digital
        Entry("SDINF", "WD", "UFS 3.1"),
        Entry("SDIN8", "WD", "eMMC 5.1"),

        // Kioxia (formerly Toshiba)
        // THGJF family is Kioxia's UFS 4.0 silicon line (gen 8).
        // Earlier UFS 3.1 silicon used the THGAF prefix.
        Entry("THGJF", "Kioxia", "UFS 4.0"),
        Entry("THGAF", "Kioxia", "UFS 3.1"),

        // YMTC (rising in mid-range Chinese devices + handhelds).
        // Retroid Pocket 6 ships YMUS9B4TF2D1C1 — YMTC's UFS 4.0 line
        // (the "YMUS9" / "YMUS4" families are gen-4 silicon). Verified
        // live on RP6.
        Entry("YMUS9", "YMTC", "UFS 4.0"),
        Entry("YMUS4", "YMTC", "UFS 4.0"),
        Entry("YMUFS3", "YMTC", "UFS 3.1"),
        Entry("YMUFS4", "YMTC", "UFS 4.0"),
    )

    fun lookup(model: String): Entry? {
        if (model.isBlank()) return null
        val upper = model.uppercase()
        return ENTRIES.firstOrNull { upper.startsWith(it.modelPrefix.uppercase()) }
    }

    /**
     * Fallback when sysfs reads are SELinux-blocked: look the device
     * up by [android.os.Build.MODEL]. We can't see the actual chip but
     * the OEM's published BoM tells us what to expect.
     *
     * Odin 3 ships with the Kioxia THGJFJT family which is part of
     * Kioxia's UFS 4.0 generation per their datasheet, even though
     * the kernel reports the on-host link as UFS 3.x (the host
     * controller in some Snapdragon 8 Elite SKUs negotiates the
     * lower 3.x mode despite the chip's 4.0 silicon). We label by
     * silicon generation, not host-link rev.
     */
    fun lookupByDevice(buildModel: String): Entry? = when {
        buildModel.equals("Odin3", ignoreCase = true) ||
            buildModel.equals("Odin 3", ignoreCase = true) ||
            buildModel.contains("Odin3", ignoreCase = true) ->
            Entry("ODIN3", "Kioxia", "UFS 4.0")
        // AYN Thor: SK Hynix HN8T374ZJKX141 (verified on-device via
        // run-as — the model file is SELinux-readable only to more
        // privileged domains, so untrusted_app needs this fallback).
        // HN8T = SK Hynix UFS 4.0 silicon.
        buildModel.contains("Thor", ignoreCase = true) &&
            buildModel.contains("AYN", ignoreCase = true) ->
            Entry("THOR", "SK Hynix", "UFS 4.0")
        buildModel.equals("AYN Thor", ignoreCase = true) ->
            Entry("THOR", "SK Hynix", "UFS 4.0")
        buildModel.contains("Odin2", ignoreCase = true) ||
            buildModel.equals("Odin 2", ignoreCase = true) ->
            Entry("ODIN2", "Samsung", "UFS 3.1")
        buildModel.contains("Pocket S", ignoreCase = true) ->
            Entry("AYNPOCKET", "Samsung", "UFS 3.1")
        // Retroid Pocket 6: YMTC YMUS9B4TF2D1C1 (UFS 4.0), verified
        // on-device. The model sysfs is SELinux-blocked from
        // untrusted_app so the Build.MODEL fallback is required.
        buildModel.contains("Pocket 6", ignoreCase = true) ->
            Entry("RP6", "YMTC", "UFS 4.0")
        buildModel.contains("Pocket 5", ignoreCase = true) ->
            Entry("RP5", "Samsung", "UFS 3.1")
        buildModel.contains("RG556", ignoreCase = true) ->
            Entry("RG556", "Anbernic", "UFS 2.2")
        else -> null
    }

    /**
     * Fallback: classify by the UFS spec revision string read from
     * /sys/block/sda/device/rev. Returns "UFS 3.1", "UFS 2.x", etc.
     * "0310" = UFS 3.1, "0400" = UFS 4.0, "0220" = UFS 2.2.
     */
    fun classFromRev(rev: String): String? = when {
        rev.isBlank() -> null
        rev.startsWith("0400") -> "UFS 4.0"
        rev.startsWith("0310") -> "UFS 3.1"
        rev.startsWith("0300") -> "UFS 3.0"
        rev.startsWith("0220") -> "UFS 2.2"
        rev.startsWith("0210") -> "UFS 2.1"
        rev.startsWith("0200") -> "UFS 2.0"
        else -> null
    }
}
