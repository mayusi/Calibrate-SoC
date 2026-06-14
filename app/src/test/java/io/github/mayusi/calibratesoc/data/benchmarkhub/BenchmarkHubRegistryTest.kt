package io.github.mayusi.calibratesoc.data.benchmarkhub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [BenchmarkAppRegistry].
 *
 * These are pure Kotlin / data-layer tests — no Android context required.
 */
class BenchmarkHubRegistryTest {

    @Test
    fun `registry has at least 5 known apps`() {
        assertThat(BenchmarkAppRegistry.ALL.size).isAtLeast(5)
    }

    @Test
    fun `all apps have non-blank display names`() {
        BenchmarkAppRegistry.ALL.forEach { app ->
            assertThat(app.displayName).isNotEmpty()
        }
    }

    @Test
    fun `all apps have non-blank package names`() {
        BenchmarkAppRegistry.ALL.forEach { app ->
            assertThat(app.packageName).isNotEmpty()
        }
    }

    @Test
    fun `all play store URIs start with market or https`() {
        BenchmarkAppRegistry.ALL.forEach { app ->
            assertThat(
                app.playStoreUri.startsWith("market://") || app.playStoreUri.startsWith("https://")
            ).isTrue()
        }
    }

    @Test
    fun `3DMark is present with correct package name`() {
        val dmandroid = BenchmarkAppRegistry.ALL.find {
            it.packageName == "com.futuremark.dmandroid.application"
        }
        assertThat(dmandroid).isNotNull()
        assertThat(dmandroid!!.displayName).isEqualTo("3DMark")
    }

    @Test
    fun `AnTuTu is present with correct package name`() {
        val antutu = BenchmarkAppRegistry.ALL.find {
            it.packageName == "com.antutu.ABenchMark"
        }
        assertThat(antutu).isNotNull()
    }

    @Test
    fun `Geekbench 6 is present with correct package name`() {
        val gb = BenchmarkAppRegistry.ALL.find {
            it.packageName == "com.primatelabs.geekbench6"
        }
        assertThat(gb).isNotNull()
    }

    @Test
    fun `byPackage lookup returns correct app`() {
        val result = BenchmarkAppRegistry.byPackage["com.futuremark.dmandroid.application"]
        assertThat(result).isNotNull()
        assertThat(result!!.displayName).isEqualTo("3DMark")
    }

    @Test
    fun `byPackage lookup returns null for unknown package`() {
        assertThat(BenchmarkAppRegistry.byPackage["com.unknown.benchmark"]).isNull()
    }

    @Test
    fun `displayNames list has same count as ALL list`() {
        assertThat(BenchmarkAppRegistry.displayNames.size).isEqualTo(BenchmarkAppRegistry.ALL.size)
    }

    @Test
    fun `HONESTY_NOTE is non-empty and mentions manual entry`() {
        assertThat(BenchmarkAppRegistry.HONESTY_NOTE).isNotEmpty()
        // Should tell the user these are manually entered scores
        assertThat(BenchmarkAppRegistry.HONESTY_NOTE.lowercase()).contains("you")
    }

    @Test
    fun `no duplicate package names in registry`() {
        val packages = BenchmarkAppRegistry.ALL.map { it.packageName }
        assertThat(packages.distinct().size).isEqualTo(packages.size)
    }

    @Test
    fun `CPU throttling test is in registry`() {
        val cpu = BenchmarkAppRegistry.ALL.find { it.packageName == "skynet.cputhrottlingtest" }
        assertThat(cpu).isNotNull()
    }
}
