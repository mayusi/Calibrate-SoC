package io.github.mayusi.calibratesoc.data.hardware

/**
 * Build.SOC_MODEL / ro.soc.model returns terse codenames like
 * "CQ8725S" (Snapdragon 8 Elite), "SM8650" (Snapdragon 8 Gen 3),
 * "MT6989" (Dimensity 9300). End users want the marketing name.
 *
 * Each entry: codename substring → (friendly name, GPU name,
 * RAM type LPDDRx classification).
 *
 * Match is case-insensitive substring on the SoC model string. We
 * pick the FIRST match in iteration order, so put more specific
 * entries before more general ones.
 */
object SocFriendlyNames {

    data class Entry(
        val codenameContains: String,
        val friendly: String,
        val gpu: String,
        val ramType: String,
    )

    private val ENTRIES: List<Entry> = listOf(
        // Qualcomm — most common in handhelds.
        // NOTE: handheld OEMs often ship the embedded "QCS"/"QCM" SKUs
        // instead of the commercial "SM" part — they are the same
        // silicon. AYN Thor reports QCS8550 (= SM8550 = 8 Gen 2). Put
        // these BEFORE the SM entries so the more specific embedded
        // codename matches first.
        Entry("QCS8550", "Snapdragon 8 Gen 2", "Adreno 740", "LPDDR5X"),
        Entry("QCM8550", "Snapdragon 8 Gen 2", "Adreno 740", "LPDDR5X"),
        Entry("CQ8725", "Snapdragon 8 Elite", "Adreno 830", "LPDDR5X"),
        Entry("SM8750", "Snapdragon 8 Elite", "Adreno 830", "LPDDR5X"),
        Entry("SM8650", "Snapdragon 8 Gen 3", "Adreno 750", "LPDDR5X"),
        Entry("SM8550", "Snapdragon 8 Gen 2", "Adreno 740", "LPDDR5X"),
        Entry("SM8475", "Snapdragon 8+ Gen 1", "Adreno 730", "LPDDR5"),
        Entry("SM8450", "Snapdragon 8 Gen 1", "Adreno 730", "LPDDR5"),
        Entry("SM8350", "Snapdragon 888", "Adreno 660", "LPDDR5"),
        Entry("SM8250", "Snapdragon 865", "Adreno 650", "LPDDR5"),
        Entry("SM7475", "Snapdragon 7+ Gen 2", "Adreno 725", "LPDDR5"),
        Entry("SM7325", "Snapdragon 778G", "Adreno 642L", "LPDDR5"),
        Entry("SM7250", "Snapdragon 765G", "Adreno 620", "LPDDR4X"),
        // Snapdragon G3x Gen 2 ships under both SM6450 (commercial) and
        // SG8275 (the gaming-handheld SKU AYANEO uses on the Pocket DS).
        // Same silicon. Verified on-device: ro.soc.model=SG8275.
        Entry("SG8275", "Snapdragon G3x Gen 2", "Adreno A32", "LPDDR5X"),
        Entry("SM6450", "Snapdragon G3x Gen 2", "Adreno A32", "LPDDR5"),
        Entry("SM6375", "Snapdragon G3x Gen 1", "Adreno 619", "LPDDR4X"),

        // MediaTek — Dimensity / Helio
        Entry("MT6991", "Dimensity 9400", "Immortalis-G925", "LPDDR5X"),
        Entry("MT6989", "Dimensity 9300", "Immortalis-G720", "LPDDR5T"),
        Entry("MT6985", "Dimensity 9200", "Immortalis-G715", "LPDDR5X"),
        Entry("MT6983", "Dimensity 9000", "Mali-G710", "LPDDR5X"),
        Entry("MT6886", "Dimensity 8300", "Mali-G615", "LPDDR5X"),
        Entry("MT6877", "Dimensity 1200", "Mali-G77", "LPDDR4X"),
        Entry("MT6789", "Helio G99", "Mali-G57", "LPDDR4X"),
        Entry("MT6785", "Helio G90T", "Mali-G76", "LPDDR4X"),
        Entry("MT6781", "Helio G96", "Mali-G57", "LPDDR4X"),

        // Samsung Exynos
        Entry("S5E9945", "Exynos 2400", "Xclipse 940", "LPDDR5X"),
        Entry("S5E9935", "Exynos 2300", "Xclipse 930", "LPDDR5X"),
        Entry("S5E9925", "Exynos 2200", "Xclipse 920", "LPDDR5"),
        Entry("S5E9840", "Exynos 990", "Mali-G77", "LPDDR5"),

        // Unisoc / Tegra / others (handheld-relevant)
        Entry("Tegra234", "Nvidia Tegra T234", "Ampere", "LPDDR5"),
        Entry("Tegra210", "Nvidia Tegra X1+", "Maxwell", "LPDDR4"),
        Entry("T606", "Unisoc Tiger T606", "Mali-G57", "LPDDR4X"),
        Entry("T618", "Unisoc Tiger T618", "Mali-G52", "LPDDR4X"),
        Entry("T820", "Unisoc Tiger T820", "Mali-G57", "LPDDR4X"),
    )

    fun lookup(model: String): Entry? {
        if (model.isBlank()) return null
        val upper = model.uppercase()
        return ENTRIES.firstOrNull { upper.contains(it.codenameContains.uppercase()) }
    }
}
