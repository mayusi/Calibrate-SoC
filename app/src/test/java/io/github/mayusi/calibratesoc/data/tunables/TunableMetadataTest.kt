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

    @Test
    fun `custom sysfs path rejects paths with newlines`() {
        assertThat(TunableMetadata.validateCustomSysfsPath("/sys/block/sda\n/queue/scheduler")).isNotNull()
        assertThat(TunableMetadata.validateCustomSysfsPath("/proc/sys/vm/swappiness\r")).isNotNull()
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
