package io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for the AYANEO AIDL command builder — exact payload-string assertions
 * against the decompiled + live-verified wire protocol. No Android, no IPC.
 */
class AyaneoCommandsTest {

    @Test
    fun `wrap prefixes clientId and performance tag`() {
        assertThat(AyaneoCommands.wrap("com_set_performance_gpu:680000000"))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_gpu:680000000")
    }

    @Test
    fun `setCpuFreq formats repCpu_freqHz`() {
        assertThat(AyaneoCommands.setCpuFreq(repCpu = 7, freqHz = 2_419_200_000L))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_cpu:7_2419200000")
    }

    @Test
    fun `setCpuFreqFromKhz converts kHz to Hz (x1000)`() {
        // 2419200 kHz → 2419200000 Hz.
        assertThat(AyaneoCommands.setCpuFreqFromKhz(repCpu = 3, freqKhz = 2_419_200L))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_cpu:3_2419200000")
    }

    @Test
    fun `setGpuMaxFreq formats Hz directly`() {
        assertThat(AyaneoCommands.setGpuMaxFreq(585_000_000L))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_gpu:585000000")
    }

    @Test
    fun `setGpuIsFixed emits true and false`() {
        assertThat(AyaneoCommands.setGpuIsFixed(true))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_gpu_is_fixed:true")
        assertThat(AyaneoCommands.setGpuIsFixed(false))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_gpu_is_fixed:false")
    }

    @Test
    fun `governorToken maps performance, powersave, and the schedutil default`() {
        assertThat(AyaneoCommands.governorToken("performance")).isEqualTo("HIGH_PERFORMANCE")
        assertThat(AyaneoCommands.governorToken("powersave")).isEqualTo("POWER_SAVING")
        assertThat(AyaneoCommands.governorToken("schedutil")).isEqualTo("BALANCED")
        assertThat(AyaneoCommands.governorToken("walt")).isEqualTo("BALANCED")
        assertThat(AyaneoCommands.governorToken("UNKNOWN")).isEqualTo("BALANCED")
        // Case-insensitive.
        assertThat(AyaneoCommands.governorToken("PERFORMANCE")).isEqualTo("HIGH_PERFORMANCE")
    }

    @Test
    fun `setScheduler emits the mapped token`() {
        assertThat(AyaneoCommands.setScheduler(AyaneoCommands.governorToken("performance")))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_scheduler:HIGH_PERFORMANCE")
    }

    @Test
    fun `setFanMode emits the fan preset token`() {
        assertThat(AyaneoCommands.setFanMode(AyaneoCommands.FAN_MODE_TURBO))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_fan:FAN_MODE_TURBO")
    }

    @Test
    fun `setFanCurve serializes points as temp,duty pipe-separated under FAN_MODE_CUSTOM`() {
        val curve = listOf(45 to 20, 65 to 30, 85 to 45, 105 to 60)
        assertThat(AyaneoCommands.setFanCurve(curve))
            .isEqualTo(
                "calibrate:msg_type_performance:com_set_fan_speed_strategy:" +
                    "FAN_MODE_CUSTOM-45,20|65,30|85,45|105,60"
            )
    }

    @Test
    fun `setFanCurveLinear emits LINEAR and STEP`() {
        assertThat(AyaneoCommands.setFanCurveLinear(true))
            .isEqualTo("calibrate:msg_type_performance:com_set_fan_speed_is_linear:LINEAR")
        assertThat(AyaneoCommands.setFanCurveLinear(false))
            .isEqualTo("calibrate:msg_type_performance:com_set_fan_speed_is_linear:STEP")
    }

    @Test
    fun `reset emits the mode int (revert-to-stock bundle)`() {
        assertThat(AyaneoCommands.reset(0))
            .isEqualTo("calibrate:msg_type_performance:com_set_performance_reset:0")
    }
}
