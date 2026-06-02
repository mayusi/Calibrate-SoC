package io.github.mayusi.calibratesoc.data.tunables

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TunableSnapshotStoreTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var store: TunableSnapshotStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns tempDir.root
        store = TunableSnapshotStore(ctx, FileSystem.SYSTEM, json)
    }

    @Test
    fun `empty when file missing`() = runTest {
        assertThat(store.read().entries).isEmpty()
    }

    @Test
    fun `append persists`() = runTest {
        val id = TunableId(TunableKind.SYSFS, "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")
        store.append(TunableSnapshot(id, "1804800", 100L, "test"))
        val again = store.read()
        assertThat(again.entries).hasSize(1)
        assertThat(again.entries.first().previousValue).isEqualTo("1804800")
    }

    @Test
    fun `append coalesces duplicates keeping first snapshot`() = runTest {
        val id = TunableId(TunableKind.SYSFS, "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")
        // First write captures the true stock value.
        store.append(TunableSnapshot(id, "STOCK_VALUE", 100L, "first"))
        // Second write shouldn't overwrite the stock value with our own
        // recently-written one — that would break boot-revert.
        store.append(TunableSnapshot(id, "WE_OVERWROTE_IT", 200L, "second"))
        val entries = store.read().entries
        assertThat(entries).hasSize(1)
        assertThat(entries.first().previousValue).isEqualTo("STOCK_VALUE")
    }

    @Test
    fun `clear empties journal`() = runTest {
        val id = TunableId(TunableKind.SETTINGS_SYSTEM, "ayn_perf_mode")
        store.append(TunableSnapshot(id, "standard", 100L, "test"))
        store.clear()
        assertThat(store.read().entries).isEmpty()
    }
}
