package io.github.mayusi.calibratesoc.data.capability

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derives [DeviceIdentity] + [SoCIdentity] from cheap, always-available
 * signals. No EGL / no Vulkan introspection here — the heuristic family
 * inference is enough to pick the right GPU probe path. Real GPU strings
 * (`GL_RENDERER`) would require an offscreen context which is overkill
 * for capability detection.
 *
 * Extends EmuTran's DeviceDetector pattern (same bucket of `manuf+model`
 * substring checks), with the family inference layered on top so we know
 * whether to probe `/sys/class/kgsl/` vs the Mali devfreq directory under `/sys/class/devfreq/`.
 */
@Singleton
class SoCDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun detect(): Pair<DeviceIdentity, SoCIdentity> {
        val device = DeviceIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            knownHandheldKey = handheldKeyFor(Build.MANUFACTURER, Build.MODEL),
        )

        val socManuf = socManufacturer()
        val socModel = socModel()
        val family = inferGpuFamily(socManuf, socModel, device.hardware)

        return device to SoCIdentity(socManuf, socModel, family)
    }

    /**
     * Build.SOC_* is API 31+. On older devices fall back to the system
     * properties — they exist back to Android 8 on most OEMs.
     */
    private fun socManufacturer(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
                ?.let { return it }
        }
        return readProp("ro.soc.manufacturer").orEmpty()
    }

    private fun socModel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
                ?.let { return it }
        }
        return readProp("ro.soc.model").orEmpty()
    }

    /**
     * Vendor bucket key (lowercase) that the device-db can join on. NULL =
     * unknown / generic device. We deliberately overlap with EmuTran's
     * HandheldVendor enum naming so a future shared library is trivial.
     */
    private fun handheldKeyFor(manuf: String?, model: String?): String? {
        // Manufacturer + model concat, normalized: lowercased, then
        // stripped of non-alphanumerics so we match "Odin3" (no space)
        // the same way we match "odin 3" / "odin-3" / "odin_3". This
        // is what the Odin 3 firmware actually reports — verified
        // live: manufacturer="ayn", model="Odin3" => "aynodin3".
        val m = (manuf.orEmpty() + " " + model.orEmpty()).lowercase()
        val collapsed = m.replace(Regex("[^a-z0-9]"), "")
        fun matches(needle: String) = needle in m || needle.replace(" ", "") in collapsed
        return when {
            "ayn" in m && "thor" in m -> "ayn_thor"
            matches("ayn") && (matches("odin 3") || "odin3" in collapsed) -> "ayn_odin3"
            matches("ayn") && (matches("odin 2") || "odin2" in collapsed) -> "ayn_odin2"
            "ayn" in m || "odin" in m -> "ayn"
            // RP6 reports manufacturer="Moorechip", model="Retroid
            // Pocket 6" — so "retroid" is in the combined string via the
            // model. Match pocket 6 BEFORE the generic retroid bucket.
            "retroid" in m && (matches("pocket 6") || "pocket6" in collapsed) -> "retroid_pocket6"
            "retroid" in m && matches("pocket 5") -> "retroid_pocket5"
            "retroid" in m && matches("pocket 4") -> "retroid_pocket4"
            "retroid" in m -> "retroid"
            // Moorechip is Retroid's OEM identity — catch devices that
            // report it as manufacturer without "retroid" in the model.
            "moorechip" in m && (matches("pocket 6") || "pocket6" in collapsed) -> "retroid_pocket6"
            "moorechip" in m -> "retroid"
            "ayaneo" in m && matches("pocket s") -> "ayaneo_pocket_s"
            "ayaneo" in m -> "ayaneo"
            "anbernic" in m && "rg556" in m -> "anbernic_rg556"
            "anbernic" in m && "ds" in m -> "anbernic_ds"
            "anbernic" in m -> "anbernic"
            else -> null
        }
    }

    private fun inferGpuFamily(socManuf: String, socModel: String, hardware: String): GpuFamily {
        val all = (socManuf + " " + socModel + " " + hardware).lowercase()
        return when {
            // Qualcomm reports its SoC under many naming schemes:
            //   - "qualcomm" / "snapdragon" (marketing)
            //   - SMxxxx (commercial part, e.g. SM8550 = 8 Gen 2)
            //   - QCSxxxx / QCMxxxx (IoT / embedded variants — the AYN
            //     Thor reports ro.soc.model=QCS8550, ro.soc.manufacturer=QTI)
            //   - "qti" (Qualcomm Technologies Inc — Thor's manufacturer)
            //   - platform codenames: kalama (8 Gen 2), pineapple/sun
            //     (8 Gen 3/Elite), lahaina, taro, etc.
            // Even when none of these match (Odin 3 reports CQ8725S), the
            // CapabilityProbe.upgradeFamilyByPathPresence() fallback catches
            // it via /sys/class/kgsl presence — but matching here keeps the
            // device-report accurate when sysfs reads are blocked.
            "qualcomm" in all || "snapdragon" in all || "qti" in all ||
                "sm8" in all || "sm7" in all || "sm6" in all ||
                "qcs8" in all || "qcm8" in all || "qcs6" in all ||
                // SGxxxx = Snapdragon G-series gaming SKUs (G3x Gen 2 =
                // SG8275 on the AYANEO Pocket DS).
                "sg8" in all || "sg6" in all ||
                "kalama" in all || "pineapple" in all || "lahaina" in all ||
                "taro" in all || "cape" in all || "sun" in all -> GpuFamily.ADRENO
            "samsung" in all && ("exynos 24" in all || "exynos 25" in all) -> GpuFamily.XCLIPSE
            "samsung" in all || "exynos" in all -> GpuFamily.MALI
            "mediatek" in all || "dimensity" in all || "helio" in all || "mt6" in all -> GpuFamily.POWERVR_OR_MALI_MTK
            "tegra" in all || "tegra234" in all || "tegra239" in all -> GpuFamily.MALI
            "unisoc" in all || "tiger" in all -> GpuFamily.MALI
            else -> GpuFamily.UNKNOWN
        }
    }

    private fun readProp(key: String): String? = try {
        val process = ProcessBuilder("/system/bin/getprop", key)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        out.ifBlank { null }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
