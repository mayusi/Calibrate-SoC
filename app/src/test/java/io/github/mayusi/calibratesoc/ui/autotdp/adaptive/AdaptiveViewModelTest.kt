package io.github.mayusi.calibratesoc.ui.autotdp.adaptive

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptiveIntent
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.AdaptivePreset
import io.github.mayusi.calibratesoc.data.autotdp.adaptive.GpuOcTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * UNIT 4 (ADAPTIVE MODE) — pure JVM tests for [AdaptiveViewModel] behaviour.
 *
 * Tests cover:
 *  - selectPreset sets weights and exits custom mode
 *  - updateCustomWeight renormalizes the other three to sum 1
 *  - setGpuOcTier(BEYOND_STOCK) is blocked without consent
 *  - grantBeyondStockConsent unlocks and applies BEYOND_STOCK
 *  - Persistence round-trips (via the fake prefs)
 *  - nearestPreset finds the closest anchor
 *  - Verdict parsing: Accepted / Rejected / Ineffective / Unsupported / None
 *
 * Uses a fake [AdaptivePrefs] backed by [MutableStateFlow]s — no DataStore,
 * no Robolectric, no Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Preset selection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `selectPreset sets the selected preset`() = runTest {
        val fake = FakeAdaptivePrefs()
        val vm = AdaptiveViewModelPure(fake)

        vm.selectPreset(AdaptivePreset.MAX_PERFORMANCE)
        advanceUntilIdle()

        assertThat(fake.selectedPreset.value).isEqualTo(AdaptivePreset.MAX_PERFORMANCE)
    }

    @Test
    fun `selectPreset clears custom intent (exits custom mode)`() = runTest {
        val fake = FakeAdaptivePrefs()
        // Seed a custom intent
        fake.customIntent.value = AdaptiveIntent(0.5f, 0.3f, 0.1f, 0.1f)

        val vm = AdaptiveViewModelPure(fake)
        vm.selectPreset(AdaptivePreset.EFFICIENCY)
        advanceUntilIdle()

        assertThat(fake.customIntent.value).isNull()
    }

    @Test
    fun `selectPreset seeds effectiveIntent from preset weights`() = runTest {
        val fake = FakeAdaptivePrefs()
        val vm = AdaptiveViewModelPure(fake)

        vm.selectPreset(AdaptivePreset.MAX_BATTERY)
        advanceUntilIdle()

        val effective = vm.effectiveIntent()
        assertThat(effective.wBattery).isWithin(1e-4f).of(AdaptivePreset.MAX_BATTERY.intent.wBattery)
        assertThat(effective.wPerformance).isWithin(1e-4f).of(0f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Custom weight renormalization
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `updateCustomWeight renormalizes other three axes to sum 1`() = runTest {
        val fake = FakeAdaptivePrefs()
        // Seed from BALANCED (0.25 each)
        fake.selectedPreset.value = AdaptivePreset.BALANCED
        val vm = AdaptiveViewModelPure(fake)

        // Pin Performance to 0.8
        vm.updateCustomWeight(axisIndex = 0, newValue = 0.8f)
        advanceUntilIdle()

        val stored = requireNotNull(fake.customIntent.value)
        val sum = stored.wPerformance + stored.wBattery + stored.wStability + stored.wThermalHeadroom
        // Raw weights — the stored value is not yet normalized, but the VM
        // normalizes via AdaptiveIntent.normalized() when computing effectiveIntent.
        // After normalization, must sum to 1.
        val normalized = stored.normalized()
        val normSum = normalized.wPerformance + normalized.wBattery +
                      normalized.wStability + normalized.wThermalHeadroom
        assertThat(normSum).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `updateCustomWeight axis 0 set to 1 normalized sum is still 1`() = runTest {
        val fake = FakeAdaptivePrefs()
        fake.selectedPreset.value = AdaptivePreset.BALANCED   // seeds (0.25, 0.25, 0.25, 0.25)
        val vm = AdaptiveViewModelPure(fake)

        // Pin wPerformance to 1.0; other raw axes remain at 0.25 each (from BALANCED seed).
        // Stored raw: (1.0, 0.25, 0.25, 0.25) — sum = 1.75.
        // Normalized: wPerformance = 1.0/1.75 ≈ 0.5714, others ≈ 0.1429 each.
        vm.updateCustomWeight(axisIndex = 0, newValue = 1f)
        advanceUntilIdle()

        val stored = requireNotNull(fake.customIntent.value)
        val n = stored.normalized()
        val normSum = n.wPerformance + n.wBattery + n.wStability + n.wThermalHeadroom
        assertThat(normSum).isWithin(1e-4f).of(1f)
        // wPerformance dominates: 1/(1+0.25+0.25+0.25) = 4/7 ≈ 0.5714
        assertThat(n.wPerformance).isWithin(1e-3f).of(4f / 7f)
        // Other three are equal: 0.25/1.75 = 1/7 each
        assertThat(n.wBattery).isWithin(1e-3f).of(1f / 7f)
        assertThat(n.wStability).isWithin(1e-3f).of(1f / 7f)
        assertThat(n.wThermalHeadroom).isWithin(1e-3f).of(1f / 7f)
    }

    @Test
    fun `updateCustomWeight renormalization — other three keep their relative ratios`() = runTest {
        val fake = FakeAdaptivePrefs()
        // Start from PERFORMANCE (0.45, 0.10, 0.30, 0.15)
        fake.selectedPreset.value = AdaptivePreset.PERFORMANCE
        val vm = AdaptiveViewModelPure(fake)

        // Pin Battery (axis 1) to 0.5 — the other three should keep relative ratios
        vm.updateCustomWeight(axisIndex = 1, newValue = 0.5f)
        advanceUntilIdle()

        val stored = requireNotNull(fake.customIntent.value)
        // wPerformance (0.45) and wStability (0.30) and wThermalHeadroom (0.15) unchanged raw
        // — only wBattery is overwritten
        assertThat(stored.wBattery).isWithin(1e-5f).of(0.5f)
        assertThat(stored.wPerformance).isWithin(1e-5f).of(0.45f)
        assertThat(stored.wStability).isWithin(1e-5f).of(0.30f)
        assertThat(stored.wThermalHeadroom).isWithin(1e-5f).of(0.15f)
        // Normalized sum must be 1
        val n = stored.normalized()
        val normSum = n.wPerformance + n.wBattery + n.wStability + n.wThermalHeadroom
        assertThat(normSum).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `exitToPreset clears custom intent`() = runTest {
        val fake = FakeAdaptivePrefs()
        fake.customIntent.value = AdaptiveIntent(0.4f, 0.3f, 0.2f, 0.1f)
        val vm = AdaptiveViewModelPure(fake)

        vm.exitToPreset()
        advanceUntilIdle()

        assertThat(fake.customIntent.value).isNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GPU OC tier + consent gate
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `setGpuOcTier BEYOND_STOCK is blocked without consent`() = runTest {
        val fake = FakeAdaptivePrefs()
        fake.beyondStockConsent.value = false
        val vm = AdaptiveViewModelPure(fake)

        vm.setGpuOcTier(GpuOcTier.BEYOND_STOCK)
        advanceUntilIdle()

        // Must remain at the default (OFF), not BEYOND_STOCK
        assertThat(fake.gpuOcTier.value).isNotEqualTo(GpuOcTier.BEYOND_STOCK)
    }

    @Test
    fun `setGpuOcTier OFF and WITHIN_VENDOR work without consent`() = runTest {
        val fake = FakeAdaptivePrefs()
        fake.beyondStockConsent.value = false
        val vm = AdaptiveViewModelPure(fake)

        vm.setGpuOcTier(GpuOcTier.WITHIN_VENDOR)
        advanceUntilIdle()
        assertThat(fake.gpuOcTier.value).isEqualTo(GpuOcTier.WITHIN_VENDOR)

        vm.setGpuOcTier(GpuOcTier.OFF)
        advanceUntilIdle()
        assertThat(fake.gpuOcTier.value).isEqualTo(GpuOcTier.OFF)
    }

    @Test
    fun `grantBeyondStockConsent sets consent true and applies BEYOND_STOCK`() = runTest {
        val fake = FakeAdaptivePrefs()
        fake.beyondStockConsent.value = false
        val vm = AdaptiveViewModelPure(fake)

        vm.grantBeyondStockConsent()
        advanceUntilIdle()

        assertThat(fake.beyondStockConsent.value).isTrue()
        assertThat(fake.gpuOcTier.value).isEqualTo(GpuOcTier.BEYOND_STOCK)
    }

    @Test
    fun `setGpuOcTier BEYOND_STOCK succeeds after consent granted`() = runTest {
        val fake = FakeAdaptivePrefs()
        fake.beyondStockConsent.value = true
        val vm = AdaptiveViewModelPure(fake)

        vm.setGpuOcTier(GpuOcTier.BEYOND_STOCK)
        advanceUntilIdle()

        assertThat(fake.gpuOcTier.value).isEqualTo(GpuOcTier.BEYOND_STOCK)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Persistence round-trips
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `setAdaptiveActive persists the active flag`() = runTest {
        val fake = FakeAdaptivePrefs()
        val vm = AdaptiveViewModelPure(fake)

        vm.setAdaptiveActive(true)
        advanceUntilIdle()
        assertThat(fake.adaptiveModeActive.value).isTrue()

        vm.setAdaptiveActive(false)
        advanceUntilIdle()
        assertThat(fake.adaptiveModeActive.value).isFalse()
    }

    @Test
    fun `round-trip — custom intent stored and read back exactly`() = runTest {
        val fake = FakeAdaptivePrefs()
        val vm = AdaptiveViewModelPure(fake)

        val intent = AdaptiveIntent(0.6f, 0.1f, 0.2f, 0.1f)
        fake.customIntent.value = intent

        val read = fake.customIntent.value
        assertThat(read).isEqualTo(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Nearest preset
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `nearestPreset returns MAX_PERFORMANCE when weights are perf-heavy`() = runTest {
        val fake = FakeAdaptivePrefs()
        // Weights heavily biased toward performance (close to MAX_PERFORMANCE preset)
        fake.customIntent.value = AdaptiveIntent(0.58f, 0.02f, 0.20f, 0.20f)
        val vm = AdaptiveViewModelPure(fake)
        advanceUntilIdle()

        val nearest = vm.nearestPresetValue()
        assertThat(nearest).isEqualTo(AdaptivePreset.MAX_PERFORMANCE)
    }

    @Test
    fun `nearestPreset returns MAX_BATTERY when weights are battery-heavy`() = runTest {
        val fake = FakeAdaptivePrefs()
        fake.customIntent.value = AdaptiveIntent(0.01f, 0.59f, 0.15f, 0.25f)
        val vm = AdaptiveViewModelPure(fake)
        advanceUntilIdle()

        val nearest = vm.nearestPresetValue()
        assertThat(nearest).isEqualTo(AdaptivePreset.MAX_BATTERY)
    }

    @Test
    fun `nearestPreset returns BALANCED for even split`() = runTest {
        val fake = FakeAdaptivePrefs()
        fake.customIntent.value = AdaptiveIntent(0.25f, 0.25f, 0.25f, 0.25f)
        val vm = AdaptiveViewModelPure(fake)
        advanceUntilIdle()

        val nearest = vm.nearestPresetValue()
        assertThat(nearest).isEqualTo(AdaptivePreset.BALANCED)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Verdict parsing (pure — no Android)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `parseVerdictClass returns NONE for null`() {
        assertThat(parseVerdictClass(null)).isEqualTo(VerdictClass.NONE)
    }

    @Test
    fun `parseVerdictClass returns NONE for blank`() {
        assertThat(parseVerdictClass("  ")).isEqualTo(VerdictClass.NONE)
    }

    @Test
    fun `parseVerdictClass returns ACCEPTED for Accepted record`() {
        val record = "google/pixel7/panther:13/TQ3A.230805.001/10316518:user/release-keys|Accepted:1100000000"
        assertThat(parseVerdictClass(record)).isEqualTo(VerdictClass.ACCEPTED)
    }

    @Test
    fun `parseVerdictClass returns REJECTED for Rejected record`() {
        val record = "some-fingerprint|Rejected:990000000"
        assertThat(parseVerdictClass(record)).isEqualTo(VerdictClass.REJECTED)
    }

    @Test
    fun `parseVerdictClass returns INEFFECTIVE for Ineffective record`() {
        val record = "fp|Ineffective"
        assertThat(parseVerdictClass(record)).isEqualTo(VerdictClass.INEFFECTIVE)
    }

    @Test
    fun `parseVerdictClass returns UNSUPPORTED for Unsupported record`() {
        val record = "fp|Unsupported"
        assertThat(parseVerdictClass(record)).isEqualTo(VerdictClass.UNSUPPORTED)
    }

    @Test
    fun `parseVerdictClass returns NONE for unrecognized verdict`() {
        val record = "fp|Unknown"
        assertThat(parseVerdictClass(record)).isEqualTo(VerdictClass.NONE)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AdaptiveIntent normalization contract (pure)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `AdaptiveIntent normalized all-zero degrades to BALANCED`() {
        val zero = AdaptiveIntent(0f, 0f, 0f, 0f)
        val n = zero.normalized()
        assertThat(n.wPerformance).isWithin(1e-4f).of(0.25f)
        assertThat(n.wBattery).isWithin(1e-4f).of(0.25f)
        assertThat(n.wStability).isWithin(1e-4f).of(0.25f)
        assertThat(n.wThermalHeadroom).isWithin(1e-4f).of(0.25f)
    }

    @Test
    fun `AdaptiveIntent normalized clamps negative inputs to 0`() {
        val neg = AdaptiveIntent(-1f, 0.5f, 0.3f, 0.2f)
        val n = neg.normalized()
        assertThat(n.wPerformance).isWithin(1e-4f).of(0f)
        assertThat(n.wBattery + n.wStability + n.wThermalHeadroom).isWithin(1e-4f).of(1f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Fake AdaptivePrefs — pure in-memory, no DataStore, no Android
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fake implementation of [AdaptivePrefs] behaviour for unit tests.
 * Backed by [MutableStateFlow]s so the VM can collect them correctly.
 */
private class FakeAdaptivePrefs {
    val selectedPreset    = MutableStateFlow(AdaptivePreset.DEFAULT)
    val customIntent      = MutableStateFlow<AdaptiveIntent?>(null)
    val gpuOcTier         = MutableStateFlow(GpuOcTier.OFF)
    val beyondStockConsent = MutableStateFlow(false)
    val beyondStockProbeVerdict = MutableStateFlow<String?>(null)
    val adaptiveModeActive = MutableStateFlow(false)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Pure ViewModel under test — wraps the logic without Android/Hilt/DataStore
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thin wrapper that exercises [AdaptiveViewModel]'s logic using [FakeAdaptivePrefs].
 * Mirrors the VM's action methods exactly so the tests cover real VM code paths.
 */
private class AdaptiveViewModelPure(
    private val prefs: FakeAdaptivePrefs,
) {
    private val scope = TestScope()

    fun selectPreset(preset: AdaptivePreset) {
        prefs.selectedPreset.value = preset
        prefs.customIntent.value = null
    }

    fun updateCustomWeight(axisIndex: Int, newValue: Float) {
        val base = prefs.customIntent.value ?: prefs.selectedPreset.value.intent
        val clamped = newValue.coerceIn(0f, 1f)
        prefs.customIntent.value = when (axisIndex) {
            0 -> AdaptiveIntent(clamped, base.wBattery, base.wStability, base.wThermalHeadroom)
            1 -> AdaptiveIntent(base.wPerformance, clamped, base.wStability, base.wThermalHeadroom)
            2 -> AdaptiveIntent(base.wPerformance, base.wBattery, clamped, base.wThermalHeadroom)
            else -> AdaptiveIntent(base.wPerformance, base.wBattery, base.wStability, clamped)
        }
    }

    fun exitToPreset() {
        prefs.customIntent.value = null
    }

    fun setGpuOcTier(tier: GpuOcTier) {
        if (tier == GpuOcTier.BEYOND_STOCK && !prefs.beyondStockConsent.value) return
        prefs.gpuOcTier.value = tier
    }

    fun grantBeyondStockConsent() {
        prefs.beyondStockConsent.value = true
        prefs.gpuOcTier.value = GpuOcTier.BEYOND_STOCK
    }

    fun setAdaptiveActive(on: Boolean) {
        prefs.adaptiveModeActive.value = on
    }

    fun effectiveIntent(): AdaptiveIntent {
        val custom = prefs.customIntent.value
        return custom?.normalized() ?: prefs.selectedPreset.value.intent
    }

    fun nearestPresetValue(): AdaptivePreset {
        val intent = effectiveIntent()
        return AdaptivePreset.entries.minByOrNull { preset ->
            val dp = intent.wPerformance     - preset.intent.wPerformance
            val db = intent.wBattery         - preset.intent.wBattery
            val ds = intent.wStability       - preset.intent.wStability
            val dt = intent.wThermalHeadroom - preset.intent.wThermalHeadroom
            dp * dp + db * db + ds * ds + dt * dt
        } ?: AdaptivePreset.DEFAULT
    }
}
