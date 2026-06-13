package io.github.mayusi.calibratesoc.data.script

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
import org.junit.Test

/**
 * Locks in the exact shell the generator emits for a 3-cluster device
 * (Thor / RP6 / Pocket DS topology). The generated script is the ONLY
 * way these stock devices can change clocks (run via the vendor "Run
 * script as Root" runner), so its correctness is load-bearing.
 */
class AynScriptGeneratorTest {

    private val report = CapabilityReport(
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
        privilege = PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = emptyList(),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false, retroidGameAssistant = true),
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

    private val balanced = Preset(
        id = "builtin_balanced",
        name = "Balanced",
        description = "3-cluster balanced",
        verification = VerificationTier.GENERIC_KNOWN_FAMILY,
        cpuPolicyMaxKhz = mapOf(0 to 1613000, 3 to 2242000, 7 to 2390000),
        cpuPolicyMinKhz = mapOf(0 to 307200, 3 to 499200, 7 to 595200),
    )

    @Test
    fun `script writes all three clusters with chmod sandwich`() {
        val sh = AynScriptGenerator().generate(balanced, report, adapter)

        // Every cluster's max is written.
        assertThat(sh).contains("policy0/scaling_max_freq")
        assertThat(sh).contains("'1613000'")
        assertThat(sh).contains("policy3/scaling_max_freq")
        assertThat(sh).contains("'2242000'")
        assertThat(sh).contains("policy7/scaling_max_freq")
        assertThat(sh).contains("'2390000'")

        // chmod sandwich present (perfd can't clobber after seal).
        assertThat(sh).contains("chmod 666 /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq")
        assertThat(sh).contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq")
    }

    @Test
    fun `every write is guarded on path existence and error-tolerant`() {
        val sh = AynScriptGenerator().generate(balanced, report, adapter)
        // A missing policy node on a 2-cluster device must not abort.
        assertThat(sh).contains("[ -e /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq ]")
        // perfd stop is error-tolerant.
        assertThat(sh).contains("stop perfd 2>/dev/null")
    }

    @Test
    fun `script ends with a verification readback for every touched policy`() {
        val sh = AynScriptGenerator().generate(balanced, report, adapter)
        assertThat(sh).contains("verifying")
        assertThat(sh).contains("policy0 max=")
        assertThat(sh).contains("policy3 max=")
        assertThat(sh).contains("policy7 max=")
    }

    @Test
    fun `safety guard skips a min that exceeds 1_5 GHz`() {
        val dangerous = balanced.copy(
            cpuPolicyMinKhz = mapOf(7 to 3000000), // 3 GHz min = cook
        )
        val sh = AynScriptGenerator().generate(dangerous, report, adapter)
        assertThat(sh).contains("SAFETY: skipping min for policy7")
        // Must NOT emit a real min write of 3 GHz.
        assertThat(sh).doesNotContain("printf %s '3000000' > /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq")
    }

    @Test
    fun `min is written low first then target to satisfy kernel ordering`() {
        val sh = AynScriptGenerator().generate(balanced, report, adapter)
        // The low-floor min write (300000) precedes the max write,
        // which precedes the target min write.
        val lowMinIdx = sh.indexOf("printf %s '300000' > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")
        val maxIdx = sh.indexOf("'1613000' > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")
        val targetMinIdx = sh.indexOf("'307200' > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq")
        assertThat(lowMinIdx).isGreaterThan(-1)
        assertThat(maxIdx).isGreaterThan(lowMinIdx)
        assertThat(targetMinIdx).isGreaterThan(maxIdx)
    }

    // ── shellSingleQuote security tests ─────────────────────────────────────

    private val gen = AynScriptGenerator()

    @Test
    fun `shellSingleQuote wraps a normal name in single quotes`() {
        assertThat(gen.shellSingleQuote("Performance")).isEqualTo("'Performance'")
    }

    @Test
    fun `shellSingleQuote escapes an apostrophe correctly`() {
        // "Mike's tune" → 'Mike'\''s tune'
        assertThat(gen.shellSingleQuote("Mike's tune")).isEqualTo("'Mike'\\''s tune'")
    }

    @Test
    fun `shellSingleQuote neutralises full shell injection payload`() {
        val payload = "foo' ; rm -rf /data ; echo '"
        val quoted = gen.shellSingleQuote(payload)
        // Must contain the POSIX escaped-quote sequence for the apostrophe.
        assertThat(quoted).contains("'\\''")
        // Must start and end with a single-quote so the value is always wrapped.
        assertThat(quoted).startsWith("'")
        assertThat(quoted).endsWith("'")
        // The semicolons must remain inside quoted regions — confirmed by the
        // fact that the result is a single-quoted expression (no unquoted `;`).
        // Concretely: the injection sequence '; rm' must not appear unquoted.
        // After escaping, the original ' is replaced by '\'', so the '; rm'
        // literal should no longer be a boundary between quoted sections.
        assertThat(quoted).doesNotContain("'; rm")
    }

    /**
     * Every shell command line whose first token is `echo`, `printf`, or
     * `stop` — i.e. the lines that actually run a command with an embedded
     * value. We assert these are SAFE: any embedded apostrophe is POSIX-escaped
     * to `'\''` so the value can never break out of its single-quoted region.
     *
     * Note: a substring like `; rm -rf` legitimately appears inside a safely
     * single-quoted literal (e.g. `echo '... ; rm -rf ...'`), so the right
     * property to assert is "the apostrophe was escaped", NOT "the substring is
     * absent" — the latter would flag safe output.
     */
    @Test
    fun `generated script escapes an injection payload in the preset name`() {
        val malicious = balanced.copy(
            name = "foo' ; rm -rf /data ; echo '",
            cpuPolicyGovernor = emptyMap(),
        )
        val sh = AynScriptGenerator().generate(malicious, report, adapter)
        // The apostrophe in the name must be POSIX-escaped on the echo line:
        // foo'... → 'foo'\''...  (close quote, escaped literal ', reopen quote)
        assertThat(sh).contains("'foo'\\''")
        // The name's apostrophe must NEVER appear as a bare quote-then-command
        // boundary on the echo line, i.e. `foo' ;` (close quote then `;`) must
        // not exist — it is always `foo'\''` instead.
        assertThat(sh).doesNotContain("'foo' ;")
    }

    @Test
    fun `generated script escapes a malicious governor value`() {
        val malicious = balanced.copy(
            cpuPolicyGovernor = mapOf(0 to "schedutil' ; cat /data/data/io.github.mayusi.calibratesoc ; echo '"),
        )
        val sh = AynScriptGenerator().generate(malicious, report, adapter)
        // The apostrophe in the governor must be escaped (schedutil'\'' ...),
        // never left as a bare `schedutil' ;` quote-break.
        assertThat(sh).contains("schedutil'\\''")
        assertThat(sh).doesNotContain("'schedutil' ;")
    }

    @Test
    fun `commentSafe strips newlines so a name cannot escape a comment line`() {
        val gen = AynScriptGenerator()
        val nasty = "Cool\nrm -rf /data"
        // commentSafe collapses CR/LF to spaces.
        assertThat(gen.commentSafe(nasty)).isEqualTo("Cool rm -rf /data")
        assertThat(gen.commentSafe(nasty)).doesNotContain("\n")

        val malicious = balanced.copy(name = "Cool\nrm -rf /data", cpuPolicyGovernor = emptyMap())
        val sh = gen.generate(malicious, report, adapter)
        // The `# Preset:` comment line must stay a single line — the injected
        // newline must not split it into a real command line.
        assertThat(sh).contains("# Preset: Cool rm -rf /data")
        assertThat(sh).doesNotContain("# Preset: Cool\nrm -rf /data")
        // (The echo line keeps the newline but INSIDE single quotes, which is
        // harmless — echo just prints a two-line literal; it executes nothing.)
    }
}
