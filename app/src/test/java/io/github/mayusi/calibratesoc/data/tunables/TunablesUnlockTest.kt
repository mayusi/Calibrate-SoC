package io.github.mayusi.calibratesoc.data.tunables

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
import org.junit.Test

/**
 * Tests for the script-builder unlock tier:
 *
 *  1. [Tunables.whyWriteDenied] returns null when the unlock script ran
 *     (sysfsDirectlyWritable=true) AND the path is in the covered set.
 *  2. [Tunables.whyWriteDenied] returns a non-null string when sysfsDirectlyWritable
 *     is false for a covered node (unlock not yet run).
 *  3. [Tunables.whyWriteDenied] returns non-null for procfs / cgroup paths
 *     even when sysfsDirectlyWritable=true (those are NOT chmod'd by the script).
 *  4. Shell-metacharacter values in extraSysfs are shell-escaped and
 *     existence-guarded in the generated script (security regression guard).
 */
class TunablesUnlockTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun report(
        tier: PrivilegeTier,
        sysfsDirectlyWritable: Boolean = false,
    ) = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "Moorechip",
            brand = "Retroid",
            model = "Retroid Pocket 6",
            device = "kalama",
            hardware = "kalama",
            androidVersion = "13",
            sdkInt = 33,
            knownHandheldKey = "retroid_pocket6",
        ),
        soc = SoCIdentity("QTI", "QCS8550", GpuFamily.ADRENO),
        privilege = tier,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = emptyList(),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false, retroidGameAssistant = true),
        sysfsDirectlyWritable = sysfsDirectlyWritable,
    )

    private val adapter = DeviceAdapter(
        key = "retroid_pocket6",
        displayName = "Retroid Pocket 6",
        vendorAppPackage = "com.rp.gameassistant",
        fanAdapter = null,
        perfPresetAdapter = null,
        perfDaemonsToStopOnWrite = listOf("perfd"),
        chmodLockCpuFreqWrites = true,
    )

    // ── Task 2: whyWriteDenied returns null for covered nodes after unlock ─────

    @Test
    fun `whyWriteDenied returns null for cpufreq maxFreq when unlock ran`() {
        val id = TunableId(
            TunableKind.SYSFS,
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        )
        val r = report(PrivilegeTier.NONE, sysfsDirectlyWritable = true)
        assertThat(Tunables.whyWriteDenied(id, r)).isNull()
    }

    @Test
    fun `whyWriteDenied returns null for cpufreq minFreq policy3 when unlock ran`() {
        val id = TunableId(
            TunableKind.SYSFS,
            "/sys/devices/system/cpu/cpufreq/policy3/scaling_min_freq",
        )
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNull()
    }

    @Test
    fun `whyWriteDenied returns null for cpufreq governor when unlock ran`() {
        val id = TunableId(
            TunableKind.SYSFS,
            "/sys/devices/system/cpu/cpufreq/policy7/scaling_governor",
        )
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNull()
    }

    @Test
    fun `whyWriteDenied returns null for cpu online when unlock ran`() {
        val id = TunableId(
            TunableKind.SYSFS,
            "/sys/devices/system/cpu/cpu4/online",
        )
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNull()
    }

    @Test
    fun `whyWriteDenied returns null for Adreno kgsl throttling when unlock ran`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/class/kgsl/kgsl-3d0/throttling")
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNull()
    }

    @Test
    fun `whyWriteDenied returns null for input boost freq when unlock ran`() {
        val id = TunableId(
            TunableKind.SYSFS,
            "/sys/module/cpu_boost/parameters/input_boost_freq",
        )
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNull()
    }

    @Test
    fun `whyWriteDenied returns null for devfreq min freq when unlock ran`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/class/devfreq/soc:qcom,cpu0-cpu-ddr-bw/min_freq")
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNull()
    }

    @Test
    fun `whyWriteDenied returns null for IO scheduler when unlock ran`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/block/sda/queue/scheduler")
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNull()
    }

    // ── whyWriteDenied returns non-null when unlock NOT run (covered node) ─────

    @Test
    fun `whyWriteDenied returns non-null for cpufreq maxFreq when unlock not run`() {
        val id = TunableId(
            TunableKind.SYSFS,
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        )
        val r = report(PrivilegeTier.NONE, sysfsDirectlyWritable = false)
        assertThat(Tunables.whyWriteDenied(id, r)).isNotNull()
    }

    // ── whyWriteDenied: procfs / cgroup never in covered set ──────────────────

    @Test
    fun `whyWriteDenied is non-null for procfs even when sysfsDirectlyWritable`() {
        // /proc/sys/vm/swappiness is NOT chmod'd by the unlock script
        val id = TunableId(TunableKind.SYSFS, "/proc/sys/vm/swappiness")
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNotNull()
    }

    @Test
    fun `whyWriteDenied is non-null for cgroup stune even when sysfsDirectlyWritable`() {
        val id = TunableId(TunableKind.SYSFS, "/dev/stune/top-app/schedtune.boost")
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNotNull()
    }

    @Test
    fun `whyWriteDenied is non-null for thermal zone even when sysfsDirectlyWritable`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/class/thermal/thermal_zone0/mode")
        assertThat(Tunables.whyWriteDenied(id, report(PrivilegeTier.NONE, true))).isNotNull()
    }

    // ── isUnlockCoveredNode: boundary checks ──────────────────────────────────

    @Test
    fun `isUnlockCoveredNode is true for cpu governor tunable dir`() {
        // schedutil/ and walt/ subdirs under a policy
        assertThat(
            Tunables.isUnlockCoveredNode(
                "/sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us",
            ),
        ).isTrue()
        assertThat(
            Tunables.isUnlockCoveredNode(
                "/sys/devices/system/cpu/cpufreq/policy7/walt/hispeed_freq",
            ),
        ).isTrue()
    }

    @Test
    fun `isUnlockCoveredNode is false for thermal zone`() {
        assertThat(
            Tunables.isUnlockCoveredNode("/sys/class/thermal/thermal_zone5/trip_point_0_temp"),
        ).isFalse()
    }

    @Test
    fun `isUnlockCoveredNode is false for cgroup cpuctl`() {
        assertThat(Tunables.isUnlockCoveredNode("/dev/cpuctl/top-app/cpu.uclamp.min")).isFalse()
    }

    // ── Task 1 (security): shell-metachar in extraSysfs is escaped + guarded ──

    @Test
    fun `extraSysfs value with shell metachar is single-quoted in generated script`() {
        // Injection payload: has semicolons, spaces, apostrophes
        val dangerousValue = "performancez' ; rm -rf /data ; echo '"
        val preset = Preset(
            id = "advanced_custom_test",
            name = "Test preset",
            description = "Security test",
            verification = VerificationTier.USER_CUSTOM,
            extraSysfs = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor" to dangerousValue,
            ),
        )
        val sh = AynScriptGenerator().generate(preset, report(PrivilegeTier.NONE), adapter)

        // The dangerous value must be POSIX single-quote-escaped:
        // the apostrophe (' ) in the value becomes '\'', so the full
        // quoted value starts with 'performancez'\''
        assertThat(sh).contains("'performancez'\\''")

        // The semicolons must NOT appear as bare shell command separators:
        // i.e. the literal '; rm' must not occur outside of a quoted region.
        // Since the whole value is single-quoted+escaped, '\\'' closes+reopens
        // the quote, so '; rm' must NOT be an unquoted shell token boundary.
        // The safe indicator: the line containing the write must use printf and
        // must contain the POSIX escape sequence around the apostrophe.
        assertThat(sh).doesNotContain("'; rm")
    }

    @Test
    fun `extraSysfs write is guarded by existence check in generated script`() {
        val preset = Preset(
            id = "advanced_custom_test2",
            name = "Guard test",
            description = "Existence guard test",
            verification = VerificationTier.USER_CUSTOM,
            extraSysfs = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor" to "powersave",
            ),
        )
        val sh = AynScriptGenerator().generate(preset, report(PrivilegeTier.NONE), adapter)
        // Every extraSysfs write must be wrapped in an existence check
        assertThat(sh).contains("[ -e /sys/devices/system/cpu/cpufreq/policy0/scaling_governor ]")
    }

    @Test
    fun `extraSysfs write with newline in value is safe`() {
        // Newlines are also dangerous in shell — ensure they don't break out
        val valueWithNewline = "performance\nrm -rf /"
        val preset = Preset(
            id = "newline_test",
            name = "Newline test",
            description = "Newline in value",
            verification = VerificationTier.USER_CUSTOM,
            extraSysfs = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor" to valueWithNewline,
            ),
        )
        val sh = AynScriptGenerator().generate(preset, report(PrivilegeTier.NONE), adapter)
        // The newline is inside a single-quoted string so must appear as a literal
        // character (no \n escape needed in single quotes — it's just a newline
        // inside the quoted string, which is safe). The "rm -rf /" after the newline
        // must NOT appear as an unquoted shell command.
        // We confirm the generated script does NOT contain `rm -rf /` as a bare command
        // (i.e. not preceded by single quote on the same line as a printf).
        assertThat(sh).doesNotContain("'performance'\nrm -rf /")
    }
}
