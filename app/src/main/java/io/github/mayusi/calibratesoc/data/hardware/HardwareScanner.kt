package io.github.mayusi.calibratesoc.data.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.storage.StorageManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.capability.SoCDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [HardwareReport] from public APIs + sysfs reads. All
 * detection is one-shot (the report is recomputed on demand from
 * the Hardware screen). Speed tests are separate jobs that mutate
 * the report's measurement fields after the fact.
 *
 * Permissions: nothing beyond what we already hold. Some reads
 * (e.g. /sys/block/sda/device/model) require SELinux access that
 * untrusted_app may not have — we degrade gracefully to
 * Confidence.UNKNOWN rather than crashing.
 */
@Singleton
class HardwareScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socDetector: SoCDetector,
    private val pServerWriter: io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter,
) {
    suspend fun scan(): HardwareReport = withContext(Dispatchers.IO) {
        HardwareReport(
            soc = scanSoc(),
            memory = scanMemory(),
            storage = scanStorage(),
            display = scanDisplay(),
            battery = scanBattery(),
            radios = scanRadios(),
        )
    }

    /**
     * Read a sysfs file. If our app's SELinux domain denies the read,
     * fall back to PServer (Odin's vendor service in a permissive
     * domain). This is the same trick the HUD uses for FPS; works
     * for any sysfs path the kernel exposes once Force SELinux is
     * ON and PServer is reachable.
     */
    private suspend fun readSysfsWithPServerFallback(path: String): String? {
        // Direct read first — fast, no IPC.
        val direct = runCatching { File(path).readText().trim() }.getOrNull()
        if (!direct.isNullOrEmpty()) return direct
        // PServer fallback. cat the file via the vendor's permissive
        // shell; trim the trailing newline.
        val result = pServerWriter.executeShell("cat $path") ?: return null
        val (status, out) = result
        if (status != 0) return null
        return out.trim().takeIf { it.isNotEmpty() }
    }

    // --- SoC ----------------------------------------------------------

    private fun scanSoc(): SocInfo {
        val (_, soc) = socDetector.detect()
        val entry = SocFriendlyNames.lookup(soc.socModel)
        return SocInfo(
            manufacturer = soc.socManufacturer.ifBlank { "—" },
            model = soc.socModel.ifBlank { "—" },
            friendlyName = entry?.friendly ?: soc.socModel.ifBlank { "Unknown SoC" },
            confidence = if (entry != null) Confidence.MEDIUM else Confidence.LOW,
            coreCount = Runtime.getRuntime().availableProcessors(),
            gpuName = entry?.gpu,
        )
    }

    // --- Memory -------------------------------------------------------

    private fun scanMemory(): MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalMb = info.totalMem / (1024L * 1024L)
        val availableMb = info.availMem / (1024L * 1024L)

        val socModel = runCatching { socDetector.detect().second.socModel }.getOrDefault("")
        val socEntry = SocFriendlyNames.lookup(socModel)
        return MemoryInfo(
            totalMb = totalMb,
            availableMb = availableMb,
            inferredType = socEntry?.ramType ?: "—",
            inferredConfidence = if (socEntry != null) Confidence.MEDIUM else Confidence.UNKNOWN,
        )
    }

    // --- Storage ------------------------------------------------------

    private suspend fun scanStorage(): List<StorageVolume> {
        val volumes = mutableListOf<StorageVolume>()

        // Internal (the user-visible filesDir's mount). We always have
        // this. Vendor model + UFS rev probe goes through /sys/block/sda
        // which on most Android UFS devices is mapped to internal
        // storage.
        val internalFile = context.filesDir
        val internalTotal = internalFile.totalSpace.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val internalFree = internalFile.usableSpace.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val (vendorModel, ufsClass, confidence) = probeInternalStorageClass()
        volumes += StorageVolume(
            label = "Internal",
            totalGb = internalTotal,
            freeGb = internalFree,
            inferredClass = ufsClass,
            inferredConfidence = confidence,
            vendorModel = vendorModel,
        )

        // SD card / additional volumes via StorageManager. API 24+ ships
        // getStorageVolumes(). We surface anything not-primary as a
        // separate row; class inference is "—" because removable SD is
        // microSD with its own speed class system (not UFS/eMMC).
        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                ?: return@runCatching
            sm.storageVolumes.filterNot { it.isPrimary }.forEachIndexed { idx, vol ->
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else null
                val total = (dir?.totalSpace?.toDouble() ?: 0.0) / (1024.0 * 1024.0 * 1024.0)
                val free = (dir?.usableSpace?.toDouble() ?: 0.0) / (1024.0 * 1024.0 * 1024.0)
                volumes += StorageVolume(
                    label = vol.getDescription(context) ?: "SD card ${idx + 1}",
                    totalGb = total,
                    freeGb = free,
                    inferredClass = "microSD",
                    inferredConfidence = Confidence.MEDIUM,
                    vendorModel = null,
                )
            }
        }
        return volumes
    }

    private suspend fun probeInternalStorageClass(): Triple<String?, String, Confidence> {
        val model = readSysfsWithPServerFallback("/sys/block/sda/device/model")
            ?: readSysfsWithPServerFallback("/sys/block/mmcblk0/device/name")
        val rev = readSysfsWithPServerFallback("/sys/block/sda/device/rev")
            ?: readSysfsWithPServerFallback("/sys/block/mmcblk0/device/fwrev")
        val byModel = model?.let { StorageClassNames.lookup(it) }
        if (byModel != null) {
            // We have the real chip model string AND a match — show the
            // vendor + the raw model string (e.g. "YMTC · YMUS9B4TF2D1C1").
            return Triple("${byModel.vendor} · $model", byModel.storageClass, Confidence.HIGH)
        }
        val byRev = rev?.let { StorageClassNames.classFromRev(it) }
        if (byRev != null) {
            return Triple(model, byRev, Confidence.MEDIUM)
        }
        // Sysfs blocked entirely (SELinux on untrusted_app + PServer
        // wire format unreliable — common: the real app UID can't read
        // /sys/block/sda/device/model even though run-as can). Fall back
        // to a per-device lookup based on the OEM's published BoM, and
        // surface the vendor name as the "model" so the user still sees
        // e.g. "YMTC" instead of a blank vendor row.
        val byDevice = StorageClassNames.lookupByDevice(android.os.Build.MODEL)
            ?: StorageClassNames.lookupByDevice(android.os.Build.DEVICE)
        if (byDevice != null) {
            return Triple(byDevice.vendor, byDevice.storageClass, Confidence.MEDIUM)
        }
        return Triple(model, "—", Confidence.UNKNOWN)
    }

    // --- Display ------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun scanDisplay(): DisplayInfo {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val d: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val width = d?.mode?.physicalWidth ?: 0
        val height = d?.mode?.physicalHeight ?: 0
        val density = context.resources.displayMetrics.densityDpi
        val refresh = d?.refreshRate ?: 60f
        val supported = d?.supportedModes?.map { it.refreshRate }?.distinct()?.sortedDescending() ?: emptyList()
        val hdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            d?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() ?: false
        } else false
        return DisplayInfo(
            widthPx = width,
            heightPx = height,
            densityDpi = density,
            refreshHz = refresh,
            supportedRefreshHz = supported,
            hdrSupported = hdr,
        )
    }

    // --- Battery ------------------------------------------------------

    private fun scanBattery(): BatteryInfo {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        // --- Framework API (works on every device regardless of SELinux) ---
        val tech = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        val healthInt = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH,
            BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthStatus = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD              -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT          -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD               -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE       -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD               -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified failure"
            else                                             -> null  // UNKNOWN → omit
        }

        // --- Sysfs reads (may be SELinux-denied on kalama / RP6 / Thor) ---
        val designUah = readFirstReadable("/sys/class/power_supply/battery/charge_full_design")
            ?.toLongOrNull()
        val currentUah = readFirstReadable("/sys/class/power_supply/battery/charge_full")
            ?.toLongOrNull()
        val cycleCount = readFirstReadable("/sys/class/power_supply/battery/cycle_count")
            ?.toIntOrNull()
        // Also try the sysfs health string as a cross-check (lower priority
        // than the API value above since it may be unreadable).
        val sysfsTechnology = readFirstReadable("/sys/class/power_supply/battery/technology")

        val designMah = designUah?.let { (it / 1000L).toInt() }
        val currentMah = currentUah?.let { (it / 1000L).toInt() }
        val healthPercent = if (designUah != null && currentUah != null && designUah > 0) {
            ((currentUah.toDouble() / designUah) * 100).toInt().coerceIn(0, 100)
        } else null

        // --- Device-DB fallback for design capacity when sysfs is denied ---
        val (resolvedDesignMah, designFromSysfs) = if (designMah != null) {
            Pair(designMah, true)
        } else {
            val dbMah = BatteryDesignSpecs.lookupByModel(Build.MODEL)
            Pair(dbMah, false)
        }

        return BatteryInfo(
            designCapacityMah = resolvedDesignMah,
            currentCapacityMah = currentMah,
            cycleCount = cycleCount,
            healthPercent = healthPercent,
            technology = tech ?: sysfsTechnology,
            healthStatus = healthStatus,
            designCapacityFromSysfs = designFromSysfs,
        )
    }

    // --- Radios -------------------------------------------------------

    private fun scanRadios(): RadioInfo {
        val pm = context.packageManager
        val wifiStd = resolveWifi()

        // No public Bluetooth-version API. Devices vary; we just
        // report whether BT is present and let the user check.
        val btPresent = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val bt = if (btPresent) "BT present" else "—"

        val nfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC)

        val gps = mutableListOf<String>()
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                gps += "GPS"
                // GNSS constellation enumeration requires fine-location
                // permission which we don't want to take just for the
                // hardware screen. Just note the receiver is present.
            }
        }
        return RadioInfo(
            wifiStandard = wifiStd,
            bluetoothVersion = bt,
            nfcPresent = nfc,
            gpsConstellations = gps,
        )
    }

    /**
     * Resolve an HONEST Wi-Fi description for the Radios card.
     *
     * BUG FIX: the old read used only `WifiManager.getConnectionInfo().wifiStandard`
     * and printed a bare "—" for anything that wasn't a known standard int. Two
     * failure modes made it show "—" while Wi-Fi was clearly connected:
     *   1. No ACCESS_WIFI_STATE permission was declared, so getConnectionInfo()
     *      returned a blanked WifiInfo (standard = UNKNOWN/0 → "—"). (Permission now
     *      declared in the manifest.)
     *   2. On Android 12+ getConnectionInfo() is deprecated and can return
     *      WIFI_STANDARD_UNKNOWN for the standard even on a healthy connection.
     *
     * Now we: (a) detect connectivity via ConnectivityManager (TRANSPORT_WIFI on the
     * active network — needs no location permission), (b) read WifiInfo via the modern
     * NetworkCapabilities.transportInfo path on API 31+ (falling back to the deprecated
     * getConnectionInfo()), and (c) NEVER show a bare "—" when Wi-Fi is up — we fall
     * back to the link speed ("Connected · 866 Mbps") or at least "Connected". When the
     * device has no Wi-Fi at all we say so honestly; when the read can't even tell, we
     * surface that honestly too.
     */
    @Suppress("DEPRECATION")
    private fun resolveWifi(): String {
        val pm = context.packageManager
        val hasWifiHardware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        val caps = runCatching {
            cm?.let { it.getNetworkCapabilities(it.activeNetwork) }
        }.getOrNull()
        val wifiConnected = caps?.hasTransport(
            android.net.NetworkCapabilities.TRANSPORT_WIFI,
        ) ?: false

        // Prefer the modern transportInfo WifiInfo (API 31+); fall back to the
        // deprecated WifiManager.getConnectionInfo() so older SDKs still resolve.
        val wifiInfo: android.net.wifi.WifiInfo? = run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wifiConnected) {
                (caps?.transportInfo as? android.net.wifi.WifiInfo)
            } else null
        } ?: runCatching {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.connectionInfo
        }.getOrNull()

        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wifiInfo?.wifiStandard }.getOrNull()
        } else null

        // Link speed is in Mbps; -1 / 0 means "unknown".
        val linkSpeedMbps = runCatching { wifiInfo?.linkSpeed }.getOrNull()
            ?.takeIf { it > 0 }

        return describeWifi(
            standard = standard,
            wifiConnected = wifiConnected,
            linkSpeedMbps = linkSpeedMbps,
            hasWifiHardware = hasWifiHardware,
        )
    }

    // --- Helpers ------------------------------------------------------

    private fun readFirstReadable(vararg paths: String): String? {
        for (p in paths) {
            val v = runCatching { File(p).readText().trim() }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    companion object {
        /**
         * PURE honest Wi-Fi description. Separated from the Android plumbing in
         * [resolveWifi] so it is unit-testable.
         *
         * Honesty rules (in priority order):
         *  - A KNOWN standard always wins (most informative + true).
         *  - If Wi-Fi is connected but the standard is unknown, NEVER return "—":
         *    show the link speed if we have it ("Connected · 866 Mbps"), else just
         *    "Connected".
         *  - If Wi-Fi is NOT connected: "Not connected" (the device has Wi-Fi but isn't
         *    on a network) or "—" only when there's no Wi-Fi hardware at all.
         *
         * @param standard the WifiInfo.wifiStandard int (4/5/6/7/8…), or null/0 when
         *   unknown/unavailable.
         * @param wifiConnected true when the active network reports TRANSPORT_WIFI.
         * @param linkSpeedMbps the WifiInfo.linkSpeed in Mbps when > 0, else null.
         * @param hasWifiHardware whether the device declares FEATURE_WIFI at all.
         */
        internal fun describeWifi(
            standard: Int?,
            wifiConnected: Boolean,
            linkSpeedMbps: Int?,
            hasWifiHardware: Boolean,
        ): String {
            val byStandard = when (standard) {
                4 -> "Wi-Fi 4 (802.11n)"
                5 -> "Wi-Fi 5 (802.11ac)"
                6 -> "Wi-Fi 6 (802.11ax)"
                7 -> "Wi-Fi 6E"
                8 -> "Wi-Fi 7 (802.11be)"
                else -> null
            }
            if (byStandard != null) {
                // When connected, append the live link speed for extra signal.
                return if (wifiConnected && linkSpeedMbps != null) {
                    "$byStandard · $linkSpeedMbps Mbps"
                } else {
                    byStandard
                }
            }
            // Standard unknown. If we're clearly on Wi-Fi, say so honestly — never "—".
            if (wifiConnected) {
                return if (linkSpeedMbps != null) "Connected · $linkSpeedMbps Mbps" else "Connected"
            }
            // Not on Wi-Fi. Distinguish "has radio, just not connected" from "no radio".
            return if (hasWifiHardware) "Not connected" else "—"
        }
    }
}
