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
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.FreqRange
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

    /**
     * Helper: assert that generate() returned Ok (not Rejected) and
     * return the script body string for further assertions.
     */
    private fun ScriptGenerateResult.expectOk(): String {
        assertThat(this).isInstanceOf(ScriptGenerateResult.Ok::class.java)
        return (this as ScriptGenerateResult.Ok).script
    }

    private fun policy(id: Int, vararg freqsKhz: Int) = CpuPolicyProbe(
        policyId = id,
        onlineCores = listOf(id),
        availableFreqsKhz = freqsKhz.toList(),
        availableGovernors = listOf("schedutil"),
        currentMinKhz = freqsKhz.first(),
        currentMaxKhz = freqsKhz.last(),
        currentGovernor = "schedutil",
        hardwareLimitsKhz = FreqRange(freqsKhz.first(), freqsKhz.last()),
    )

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
        // RP6 has 3 clusters: little (policy0), mid (policy3), big (policy7).
        // These must match the policies referenced by `balanced` so the safety
        // gate (Gate 2) does not reject the test preset.
        cpuPolicies = listOf(
            policy(0, 307000, 1613000, 2016000),
            policy(3, 499000, 2242000, 2803000),
            policy(7, 595000, 2390000, 3187000),
        ),
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
        val sh = AynScriptGenerator().generate(balanced, report, adapter).expectOk()

        // Every cluster's max is written.
        assertThat(sh).contains("policy0/scaling_max_freq")
        assertThat(sh).contains("'1613000'")
        assertThat(sh).contains("policy3/scaling_max_freq")
        assertThat(sh).contains("'2242000'")
        assertThat(sh).contains("policy7/scaling_max_freq")
        assertThat(sh).contains("'2390000'")

        // chmod sandwich present (perfd can't clobber after seal).
        // After S0(b), paths are single-quoted in command position.
        assertThat(sh).contains("chmod 666 '/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq'")
        assertThat(sh).contains("chmod 444 '/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq'")
    }

    @Test
    fun `every write is guarded on path existence and error-tolerant`() {
        val sh = AynScriptGenerator().generate(balanced, report, adapter).expectOk()
        // A missing policy node on a 2-cluster device must not abort.
        // After S0(b), paths are single-quoted — the existence test uses the quoted form.
        assertThat(sh).contains("[ -e '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq' ]")
        // perfd stop is error-tolerant.
        assertThat(sh).contains("stop perfd 2>/dev/null")
    }

    @Test
    fun `script ends with a verification readback for every touched policy`() {
        val sh = AynScriptGenerator().generate(balanced, report, adapter).expectOk()
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
        val sh = AynScriptGenerator().generate(dangerous, report, adapter).expectOk()
        assertThat(sh).contains("SAFETY: skipping min for policy7")
        // Must NOT emit a real min write of 3 GHz (path is now single-quoted too).
        assertThat(sh).doesNotContain("printf %s '3000000' > '/sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq'")
    }

    @Test
    fun `min is written low first then target to satisfy kernel ordering`() {
        val sh = AynScriptGenerator().generate(balanced, report, adapter).expectOk()
        // The low-floor min write (300000) precedes the max write,
        // which precedes the target min write.
        // After S0(b), paths are single-quoted in the printf redirect target.
        val lowMinIdx = sh.indexOf("printf %s '300000' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'")
        val maxIdx = sh.indexOf("'1613000' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'")
        val targetMinIdx = sh.indexOf("'307200' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'")
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
        val sh = AynScriptGenerator().generate(malicious, report, adapter).expectOk()
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
        val sh = AynScriptGenerator().generate(malicious, report, adapter).expectOk()
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
        val sh = gen.generate(malicious, report, adapter).expectOk()
        // The `# Preset:` comment line must stay a single line — the injected
        // newline must not split it into a real command line.
        assertThat(sh).contains("# Preset: Cool rm -rf /data")
        assertThat(sh).doesNotContain("# Preset: Cool\nrm -rf /data")
        // (The echo line keeps the newline but INSIDE single quotes, which is
        // harmless — echo just prints a two-line literal; it executes nothing.)
    }

    // ── extraSysfs escaping + existence-guard tests ──────────────────────────

    @Test
    fun `extraSysfs value with shell metacharacters is escaped and guarded`() {
        // Use a RAW_STRING tunable (input_boost_freq) so the value passes
        // TunableMetadata validation and reaches the shell-escaping step.
        // If the apostrophe were left bare it would close the single-quote
        // context and allow '; rm -rf /' to execute.
        val injectionValue = "0:1209600'; rm -rf / ; echo '"
        val preset = balanced.copy(
            extraSysfs = mapOf(
                "/sys/module/cpu_boost/parameters/input_boost_freq" to injectionValue,
            ),
        )
        val sh = AynScriptGenerator().generate(preset, report, adapter).expectOk()

        // 1. The apostrophe in the value must be POSIX-escaped (foo'\'' ...).
        assertThat(sh).contains("'\\''")
        // 2. The raw injection sequence (close quote then command) must not appear.
        assertThat(sh).doesNotContain("1209600' ;")
        // 3. Every extraSysfs write must be existence-guarded (path now single-quoted).
        assertThat(sh).contains("[ -e '/sys/module/cpu_boost/parameters/input_boost_freq' ]")
    }

    @Test
    fun `extraSysfs emits chmod sandwich existence guard and correct value`() {
        val preset = balanced.copy(
            extraSysfs = mapOf(
                "/proc/sys/vm/swappiness" to "60",
                "/sys/module/cpu_boost/parameters/input_boost_ms" to "40",
            ),
        )
        val sh = AynScriptGenerator().generate(preset, report, adapter).expectOk()

        // Existence guard present for each path (after S0(b), path is single-quoted).
        assertThat(sh).contains("[ -e '/proc/sys/vm/swappiness' ]")
        assertThat(sh).contains("[ -e '/sys/module/cpu_boost/parameters/input_boost_ms' ]")

        // Value emitted correctly (single-quoted value) with single-quoted path.
        assertThat(sh).contains("printf %s '60' > '/proc/sys/vm/swappiness'")
        assertThat(sh).contains("printf %s '40' > '/sys/module/cpu_boost/parameters/input_boost_ms'")

        // chmod sandwich applied (path is single-quoted).
        assertThat(sh).contains("chmod 666 '/proc/sys/vm/swappiness'")
        assertThat(sh).contains("chmod 444 '/proc/sys/vm/swappiness'")
    }

    @Test
    fun `extraSysfs skips invalid path with comment and never emits it as a command`() {
        // A path that doesn't start with /sys/ or /proc/ must be rejected.
        val preset = balanced.copy(
            extraSysfs = mapOf("/data/dangerous_path" to "1"),
        )
        val sh = AynScriptGenerator().generate(preset, report, adapter).expectOk()

        // Must be skipped with a comment, not emitted as a command.
        assertThat(sh).contains("# SKIPPED (invalid path)")
        // The raw path must not appear in any executable context (unquoted or quoted).
        assertThat(sh).doesNotContain("printf %s '1' > '/data/dangerous_path'")
        assertThat(sh).doesNotContain("[ -e '/data/dangerous_path' ]")
        assertThat(sh).doesNotContain("[ -e /data/dangerous_path ]")
    }

    @Test
    fun `extraSysfs skips path-traversal attempt with comment`() {
        val preset = balanced.copy(
            extraSysfs = mapOf("/sys/../../etc/shadow" to "root"),
        )
        val sh = AynScriptGenerator().generate(preset, report, adapter).expectOk()

        assertThat(sh).contains("# SKIPPED (invalid path)")
        assertThat(sh).doesNotContain("printf %s 'root' > '/sys/../../etc/shadow'")
        assertThat(sh).doesNotContain("printf %s 'root' > /sys/../../etc/shadow")
    }

    // ── S0(b) path-quoting tests ─────────────────────────────────────────────

    @Test
    fun `paths are single-quoted in existence guard and chmod sandwich for cpu freq`() {
        // After S0(b), the path in every shell command is wrapped in single-quotes
        // (shellSingleQuote), not left bare. This test locks in the quoted form for
        // a well-known policy path so regressions are caught immediately.
        val sh = AynScriptGenerator().generate(balanced, report, adapter).expectOk()

        // The path must appear quoted in the existence guard, chmod, and redirect.
        val policyPath = "'/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"
        assertThat(sh).contains("[ -e $policyPath ]")
        assertThat(sh).contains("chmod 666 $policyPath")
        assertThat(sh).contains("chmod 444 $policyPath")
        assertThat(sh).contains("> $policyPath")

        // The path must NOT appear in an unquoted form in command position.
        // (It may appear unquoted in comments or the verification echo, which is fine.)
        assertThat(sh).doesNotContain("[ -e /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq ]")
    }

    @Test
    fun `paths are single-quoted for extraSysfs write`() {
        val preset = balanced.copy(
            extraSysfs = mapOf("/proc/sys/vm/swappiness" to "60"),
        )
        val sh = AynScriptGenerator().generate(preset, report, adapter).expectOk()

        assertThat(sh).contains("[ -e '/proc/sys/vm/swappiness' ]")
        assertThat(sh).contains("chmod 666 '/proc/sys/vm/swappiness'")
        assertThat(sh).contains("printf %s '60' > '/proc/sys/vm/swappiness'")
        assertThat(sh).contains("chmod 444 '/proc/sys/vm/swappiness'")
    }
}
