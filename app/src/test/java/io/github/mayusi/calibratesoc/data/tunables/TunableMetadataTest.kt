package io.github.mayusi.calibratesoc.data.tunables

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TunableMetadataTest {

    // =========================================================================
    // BOOL validation
    // =========================================================================

    @Test
    fun `BOOL kind accepts 0 and 1 only`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/class/thermal/thermal_zone0/mode")
        // mode is ENUM, not BOOL — use GPU throttling which is BOOL
        val throttlingId = TunableId(TunableKind.SYSFS, "/sys/class/kgsl/kgsl-3d0/throttling")
        val meta = TunableMetadata.forId(throttlingId)
        assertThat(meta.valueKind).isInstanceOf(TunableMetadata.ValueKind.BOOL::class.java)
        assertThat(meta.validate("0")).isNull()
        assertThat(meta.validate("1")).isNull()
        assertThat(meta.validate("2")).isNotNull()
        assertThat(meta.validate("true")).isNotNull()
        assertThat(meta.validate("")).isNotNull()
    }

    // =========================================================================
    // INT_RANGE validation
    // =========================================================================

    @Test
    fun `INT_RANGE rejects values below minimum`() {
        val id = TunableId(TunableKind.SYSFS, "/proc/sys/vm/swappiness")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.valueKind).isInstanceOf(TunableMetadata.ValueKind.INT_RANGE::class.java)
        assertThat(meta.validate("0")).isNull()   // exactly min
        assertThat(meta.validate("-1")).isNotNull()
    }

    @Test
    fun `INT_RANGE rejects values above maximum`() {
        val id = TunableId(TunableKind.SYSFS, "/proc/sys/vm/swappiness")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.validate("200")).isNull() // exactly max (INT_RANGE max=200)
        assertThat(meta.validate("201")).isNotNull()
    }

    @Test
    fun `INT_RANGE rejects non-integer string`() {
        val id = TunableId(TunableKind.SYSFS, "/proc/sys/vm/vfs_cache_pressure")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.validate("abc")).isNotNull()
        assertThat(meta.validate("3.14")).isNotNull()
        assertThat(meta.validate("50")).isNull()
    }

    // =========================================================================
    // ENUM validation
    // =========================================================================

    @Test
    fun `ENUM accepts only declared options`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/class/thermal/thermal_zone0/mode")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.valueKind).isInstanceOf(TunableMetadata.ValueKind.ENUM::class.java)
        assertThat(meta.validate("enabled")).isNull()
        assertThat(meta.validate("disabled")).isNull()
        assertThat(meta.validate("on")).isNotNull()
        assertThat(meta.validate("1")).isNotNull()
        assertThat(meta.validate("")).isNotNull()
    }

    @Test
    fun `ENUM with empty options list accepts any value`() {
        // When a dynamic ENUM has not yet been populated (e.g. governor list
        // not yet probed), empty options means no validation constraint.
        val kind = TunableMetadata.ValueKind.ENUM(emptyList())
        val info = TunableMetadata.TunableInfo(
            name = "test",
            description = "test",
            risk = TunableMetadata.Risk.LOW,
            valueKind = kind,
        )
        assertThat(info.validate("anything")).isNull()
    }

    // =========================================================================
    // Governor validation (cpu governor not in list = rejected)
    // =========================================================================

    @Test
    fun `governor tunable name in well-known list is resolved to proper metadata`() {
        val id = TunableId(
            TunableKind.SYSFS,
            "/sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us",
        )
        val meta = TunableMetadata.forId(id)
        assertThat(meta.name).contains("Rate Limit")
        assertThat(meta.risk).isEqualTo(TunableMetadata.Risk.LOW)
        assertThat(meta.valueKind).isInstanceOf(TunableMetadata.ValueKind.INT_RANGE::class.java)
    }

    @Test
    fun `unknown governor tunable falls through to RAW_STRING with LOW risk`() {
        val id = TunableId(
            TunableKind.SYSFS,
            "/sys/devices/system/cpu/cpufreq/policy4/schedutil/some_exotic_param",
        )
        val meta = TunableMetadata.forId(id)
        assertThat(meta.valueKind).isEqualTo(TunableMetadata.ValueKind.RAW_STRING)
        assertThat(meta.risk).isEqualTo(TunableMetadata.Risk.LOW)
    }

    // =========================================================================
    // CPU core online — cpu0 special case
    // =========================================================================

    @Test
    fun `cpuOnline for cpu0 is DANGEROUS and rejects offline value 0`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/devices/system/cpu/cpu0/online")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.risk).isEqualTo(TunableMetadata.Risk.DANGEROUS)
        assertThat(meta.validate("0")).isNotNull()  // must be rejected
        assertThat(meta.validate("1")).isNull()
    }

    @Test
    fun `cpuOnline for cpu1 is MEDIUM risk and accepts both 0 and 1`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/devices/system/cpu/cpu1/online")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.risk).isEqualTo(TunableMetadata.Risk.MEDIUM)
        assertThat(meta.validate("0")).isNull()
        assertThat(meta.validate("1")).isNull()
        assertThat(meta.validate("2")).isNotNull()
    }

    // =========================================================================
    // Thermal trip point — DANGEROUS
    // =========================================================================

    @Test
    fun `thermal trip point is DANGEROUS`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/class/thermal/thermal_zone0/trip_point_0_temp")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.risk).isEqualTo(TunableMetadata.Risk.DANGEROUS)
    }

    @Test
    fun `thermal trip point rejects negative temperature`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/class/thermal/thermal_zone2/trip_point_1_temp")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.validate("-1")).isNotNull()
        assertThat(meta.validate("85000")).isNull()   // 85°C — reasonable
    }

    // =========================================================================
    // Custom sysfs path validation
    // =========================================================================

    @Test
    fun `custom sysfs path requires slash-sys or slash-proc prefix`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda/queue/scheduler")).isNull()
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sys/vm/swappiness")).isNull()
        assertThat(TunableMetadata.validateCustomSysfsPath("/dev/stune/top-app/schedtune.boost")).isNotNull()
        assertThat(TunableMetadata.validateCustomSysfsPath("/data/local/tmp/evil")).isNotNull()
        assertThat(TunableMetadata.validateCustomSysfsPath("etc/passwd")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects path traversal`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/../../etc/passwd")).isNotNull()
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sys/../../../etc/shadow")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects dangerous proc paths`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sysrq-trigger")).isNotNull()
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sys/kernel/panic")).isNotNull()
    }

    // =========================================================================
    // isDangerousPath — component-aware blocklist matching (FIX 1)
    // =========================================================================

    @Test
    fun `blocklist bare name sysrq-trigger blocked as path component`() {
        // "/proc/sysrq-trigger": component "sysrq-trigger" == bare blocklist entry → blocked
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sysrq-trigger")).isNotNull()
    }

    @Test
    fun `blocklist full-path entry proc sys kernel panic blocked by prefix match`() {
        // "/proc/sys/kernel/panic": matched by full-path entry "/proc/sys/kernel/panic" → blocked
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sys/kernel/panic")).isNotNull()
    }

    @Test
    fun `blocklist bare name reboot does not false-positive on reboot_mode component`() {
        // "/sys/devices/virtual/reboot_mode/reboot_mode": no component is exactly "reboot",
        // only "reboot_mode" → must be ALLOWED (false-positive fix)
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/devices/virtual/reboot_mode/reboot_mode")).isNull()
    }

    @Test
    fun `blocklist bare name drop_caches blocked as path component`() {
        // "/proc/sys/vm/drop_caches": component "drop_caches" == bare blocklist entry → blocked
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sys/vm/drop_caches")).isNotNull()
    }

    // =========================================================================
    // NUL byte in path (FIX 2)
    // =========================================================================

    @Test
    fun `custom sysfs path rejects real NUL byte in path`() {
        // A path containing an actual NUL character (code point 0) must be rejected.
        val pathWithNul = "/sys/block/sda" + ' ' + "evil"
        assertThat(TunableMetadata.validateCustomSysfsPath(pathWithNul)).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects paths with newlines`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda\n/queue/scheduler")).isNotNull()
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sys/vm/swappiness\r")).isNotNull()
    }

    // =========================================================================
    // validateCustomSysfsPath — shell-metacharacter rejection (S0a security fix)
    // =========================================================================

    @Test
    fun `custom sysfs path rejects semicolon`() {
        // ; chains commands — a path containing ; could inject a second shell command.
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda;rm -rf /")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects backtick`() {
        // Backtick triggers command substitution in the shell even inside some quoting contexts.
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/`whoami`")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects dollar sign`() {
        // $ triggers variable expansion.
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/\$HOME")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects pipe`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda|cat /etc/passwd")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects ampersand`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda&evil")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects less-than`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda<evil")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects greater-than`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda>evil")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects open parenthesis`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/(evil)")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects close parenthesis`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/evil)cmd")).isNotNull()
    }

    @Test
    fun `custom sysfs path rejects NUL byte`() {
        // NUL is the classic string-terminator injection.
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda evil")).isNotNull()
    }

    @Test
    fun `custom sysfs path still accepts a normal scaling_max_freq path`() {
        // Normal, well-formed sysfs path must still pass after the metachar fix.
        assertThat(
            TunableMetadata.validateCustomSysfsPath(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            ),
        ).isNull()
    }

    @Test
    fun `custom sysfs path still accepts a normal proc sysctl path`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sys/vm/swappiness")).isNull()
    }

    // =========================================================================
    // validateCustomSysfsPath — canonical-resolution / symlink checks
    //
    // All tests below use the injectable-resolver overload so no real filesystem
    // is needed.  The public fun delegates to this overload with a real
    // java.io.File.canonicalPath resolver; the resolver returns null for the
    // non-existent paths used by the string-level tests above, so those tests
    // are unaffected by the new code path.
    // =========================================================================

    @Test
    fun `symlink that escapes to proc panic is rejected`() {
        // /sys/vendor/power/perf_mode is an innocent-looking name, but on this
        // hypothetical device it is a symlink pointing to /proc/sys/kernel/panic.
        val result = TunableMetadata.validateCustomSysfsPath(
            "/sys/vendor/power/perf_mode",
        ) { _ -> "/proc/sys/kernel/panic" }
        assertThat(result).isNotNull()
        assertThat(result).contains("/proc/sys/kernel/panic")
    }

    @Test
    fun `symlink that resolves within sys is accepted`() {
        // Many real vendor CPUfreq nodes are symlinks that canonicalise to a
        // longer /sys path (e.g. policy0 -> cpu0/cpufreq/policy0).
        val result = TunableMetadata.validateCustomSysfsPath(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        ) { _ -> "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq" }
        assertThat(result).isNull()
    }

    @Test
    fun `unresolvable path (resolver returns null) falls back to string validation and passes`() {
        // Path doesn't exist on this host JVM / test device -- resolver returns null.
        // The string checks already passed, so the result must be null (pass).
        val result = TunableMetadata.validateCustomSysfsPath(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        ) { _ -> null }
        assertThat(result).isNull()
    }

    @Test
    fun `symlink that resolves to dangerous proc drop_caches is rejected`() {
        val result = TunableMetadata.validateCustomSysfsPath(
            "/sys/kernel/fast_clear",
        ) { _ -> "/proc/sys/vm/drop_caches" }
        assertThat(result).isNotNull()
        assertThat(result).contains("drop_caches")
    }

    @Test
    fun `symlink that resolves outside sys and proc entirely is rejected`() {
        val result = TunableMetadata.validateCustomSysfsPath(
            "/sys/fs/cgroup/cpu/cpuset",
        ) { _ -> "/data/local/tmp/evil" }
        assertThat(result).isNotNull()
        assertThat(result).contains("outside /sys or /proc")
    }

    @Test
    fun `string-level metachar rejection still fires before resolver is consulted`() {
        // The resolver must never be reached for paths rejected at the string level.
        // We verify this by passing a resolver that would PASS the path -- if it were
        // consulted and returned null (non-existent), the function would also pass.
        // Instead the semicolon must cause rejection at the string stage.
        val resolverCalled = booleanArrayOf(false)
        val result = TunableMetadata.validateCustomSysfsPath(
            "/sys/block/sda;rm -rf /",
        ) { _ -> resolverCalled[0] = true; null }
        assertThat(result).isNotNull() // rejected at string stage
        assertThat(resolverCalled[0]).isFalse()
    }

    // =========================================================================
    // KernelTunables.customSysfsRule — throws on invalid path
    // =========================================================================

    @Test
    fun `customSysfsRule throws IllegalArgumentException for invalid path`() {
        try {
            KernelTunables.customSysfsRule("/dev/evil")
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("/sys/ or /proc/")
        }
    }

    @Test
    fun `customSysfsRule returns valid TunableId for correct path`() {
        val id = KernelTunables.customSysfsRule("/sys/block/sda/queue/read_ahead_kb")
        assertThat(id.target).isEqualTo("/sys/block/sda/queue/read_ahead_kb")
        assertThat(id.kind).isEqualTo(TunableKind.SYSFS)
    }

    // =========================================================================
    // schedtune / uclamp INT_RANGE
    // =========================================================================

    @Test
    fun `schedtune boost clamps to 0-100`() {
        val id = TunableId(TunableKind.SYSFS, "/dev/stune/top-app/schedtune.boost")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.valueKind).isInstanceOf(TunableMetadata.ValueKind.INT_RANGE::class.java)
        assertThat(meta.validate("0")).isNull()
        assertThat(meta.validate("100")).isNull()
        assertThat(meta.validate("101")).isNotNull()
        assertThat(meta.validate("-1")).isNotNull()
    }

    @Test
    fun `uclamp min clamps to 0-1024`() {
        val id = TunableId(TunableKind.SYSFS, "/dev/cpuctl/top-app/cpu.uclamp.min")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.validate("0")).isNull()
        assertThat(meta.validate("1024")).isNull()
        assertThat(meta.validate("1025")).isNotNull()
    }

    // =========================================================================
    // Frequency knobs
    // =========================================================================

    @Test
    fun `gpu idle timer validates integer range`() {
        val id = TunableId(TunableKind.SYSFS, "/sys/class/kgsl/kgsl-3d0/idle_timer")
        val meta = TunableMetadata.forId(id)
        assertThat(meta.validate("64")).isNull()
        assertThat(meta.validate("-1")).isNotNull()
        assertThat(meta.validate("text")).isNotNull()
    }

    // =========================================================================
    // isReversibleSafely — always true in this implementation
    // =========================================================================

    @Test
    fun `all probed metadata is reversible safely`() {
        val paths = listOf(
            "/sys/class/thermal/thermal_zone0/mode",
            "/sys/class/thermal/thermal_zone0/trip_point_0_temp",
            "/sys/class/thermal/cooling_device0/cur_state",
            "/proc/sys/vm/swappiness",
            "/dev/stune/top-app/schedtune.boost",
        )
        for (path in paths) {
            val meta = TunableMetadata.forId(TunableId(TunableKind.SYSFS, path))
            assertThat(meta.isReversibleSafely).isTrue()
        }
    }
}
