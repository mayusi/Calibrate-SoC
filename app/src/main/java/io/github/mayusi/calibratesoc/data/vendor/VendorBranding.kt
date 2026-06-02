package io.github.mayusi.calibratesoc.data.vendor

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport

/**
 * Per-device vendor branding for user-facing copy. The app supports
 * several handheld families that all share the same RO-firmware tuning
 * mechanism (Settings.System keys + "Run script as Root"), but a user
 * on a Retroid shouldn't see "AYN" anywhere, and an AYANEO user
 * shouldn't see "Odin". This resolves the right brand words from the
 * device so every screen can speak the user's language.
 *
 * Derivation keys on the handheldKey the capability probe computed
 * (ayn_odin3 / retroid_pocket6 / ayaneo_* / ...) with a Build.MANUFACTURER
 * fallback, so it stays correct even on devices without a bundled
 * adapter.
 */
enum class VendorBrand(
    /** Short brand word, e.g. "Retroid". */
    val brand: String,
    /** Name of the vendor settings app that hosts the script runner. */
    val settingsApp: String,
) {
    AYN("AYN", "Odin Settings"),
    RETROID("Retroid", "Retroid Settings"),
    AYANEO("AYANEO", "AYANEO Settings"),
    ANBERNIC("Anbernic", "device settings"),
    GENERIC("your device", "your device's settings"),
    ;

    /** "<Brand> tier" label for the privilege chip. */
    val tierLabel: String get() = if (this == GENERIC) "Vendor tier" else "$brand tier"
}

object VendorBranding {

    fun of(report: CapabilityReport?): VendorBrand {
        val key = report?.device?.knownHandheldKey?.lowercase().orEmpty()
        val manuf = report?.device?.manufacturer?.lowercase().orEmpty()
        return when {
            key.startsWith("ayn") || "odin" in key ||
                manuf == "ayn" -> VendorBrand.AYN
            key.startsWith("retroid") || "moorechip" in manuf ||
                "retroid" in manuf -> VendorBrand.RETROID
            key.startsWith("ayaneo") || "ayaneo" in manuf -> VendorBrand.AYANEO
            key.startsWith("anbernic") || "anbernic" in manuf -> VendorBrand.ANBERNIC
            else -> VendorBrand.GENERIC
        }
    }
}
