package io.github.mayusi.calibratesoc.data.autotdp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UNIT 4 (RICHER GOAL MODES) — the per-mode OBJECTIVE SETPOINTS for the three new
 * goals (TARGET_TEMP_CEILING / TARGET_FPS_FLOOR / TARGET_RUNTIME).
 *
 * Two pieces live here:
 *
 *  1. [GoalParams] — a PURE, immutable value object carrying the three user setpoints.
 *     It is threaded end-to-end (UI → controller → engine goal-resolution / service
 *     outer clamp). It has NO Android, I/O, or time, so the engine stays unit-testable.
 *     The [GoalProfile] enum stays pure & serializable — the per-user setpoint NEVER
 *     rides on the enum instance; it rides on this object instead.
 *
 *  2. [GoalParamsPrefs] — the DataStore persistence (its own isolated store, mirroring
 *     [ChargingBundlePrefs]) so the three sliders survive process death and stop/start.
 *
 * HONESTY/SAFETY: these are USER-INTENT setpoints. They are inputs to the OUTER clamp
 * (which can only ever TIGHTEN the cap — strictly safer); they can never raise the cap
 * past safety, bypass the 40% floor, or disable the thermal kill / revert.
 */
data class GoalParams(
    /** TARGET_FPS_FLOOR: the minimum FPS to hold (hold ≥ this). One of 30/40/45/60/90. */
    val fpsFloor: Int = DEFAULT_FPS_FLOOR,
    /** TARGET_TEMP_CEILING: the die-temp ceiling to hold at/below (°C), 70..95. */
    val tempCeilingC: Int = DEFAULT_TEMP_CEILING_C,
    /** TARGET_RUNTIME: "make it last H hours", 1.0..6.0. */
    val targetRuntimeHours: Float = DEFAULT_RUNTIME_HOURS,
) {
    companion object {
        /** Default minimum FPS for TARGET_FPS_FLOOR. */
        const val DEFAULT_FPS_FLOOR = 60

        /** Default die-temp ceiling (°C) for TARGET_TEMP_CEILING. */
        const val DEFAULT_TEMP_CEILING_C = 80

        /** Default runtime target (hours) for TARGET_RUNTIME. */
        const val DEFAULT_RUNTIME_HOURS = 3f

        /** Allowed FPS-floor steps the slider snaps to. */
        val FPS_FLOOR_STEPS = listOf(30, 40, 45, 60, 90)

        /** Inclusive temp-ceiling slider bounds (°C). */
        const val TEMP_CEILING_MIN_C = 70
        const val TEMP_CEILING_MAX_C = 95

        /** Inclusive runtime slider bounds (hours). */
        const val RUNTIME_HOURS_MIN = 1f
        const val RUNTIME_HOURS_MAX = 6f

        /** The product default (all three at their default setpoints). */
        val DEFAULT = GoalParams()

        /**
         * Clamp/snap a raw FPS-floor selection to the nearest allowed step. Keeps the
         * persisted value honest even if a future caller passes an off-list number.
         */
        fun snapFpsFloor(raw: Int): Int =
            FPS_FLOOR_STEPS.minByOrNull { kotlin.math.abs(it - raw) } ?: DEFAULT_FPS_FLOOR

        /** Clamp a temp ceiling into the supported band. */
        fun clampTempCeiling(raw: Int): Int =
            raw.coerceIn(TEMP_CEILING_MIN_C, TEMP_CEILING_MAX_C)

        /** Clamp a runtime-hours selection into the supported band. */
        fun clampRuntimeHours(raw: Float): Float =
            raw.coerceIn(RUNTIME_HOURS_MIN, RUNTIME_HOURS_MAX)
    }

    /** Defensive: a copy with every field clamped/snapped to its supported range. */
    fun sanitized(): GoalParams = GoalParams(
        fpsFloor = snapFpsFloor(fpsFloor),
        tempCeilingC = clampTempCeiling(tempCeilingC),
        targetRuntimeHours = clampRuntimeHours(targetRuntimeHours),
    )
}

/**
 * DataStore-backed persistence for [GoalParams].
 *
 * Stored in its own DataStore ("goal_params") — isolated from "user_prefs" and
 * "charging_bundle" so this file needs no edits to any sibling prefs file and cannot
 * collide on merge. Mirrors the read-Flow / suspend-setter shape of [ChargingBundlePrefs].
 */
@Singleton
class GoalParamsPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** TARGET_FPS_FLOOR minimum-FPS slider. */
    val fpsFloor: Flow<Int> = context.goalParamsStore.data.map { prefs ->
        GoalParams.snapFpsFloor(prefs[FPS_FLOOR_KEY] ?: GoalParams.DEFAULT_FPS_FLOOR)
    }

    /** TARGET_TEMP_CEILING die-temp ceiling slider (°C). */
    val tempCeilingC: Flow<Int> = context.goalParamsStore.data.map { prefs ->
        GoalParams.clampTempCeiling(prefs[TEMP_CEILING_KEY] ?: GoalParams.DEFAULT_TEMP_CEILING_C)
    }

    /** TARGET_RUNTIME hours slider. */
    val targetRuntimeHours: Flow<Float> = context.goalParamsStore.data.map { prefs ->
        GoalParams.clampRuntimeHours(prefs[RUNTIME_HOURS_KEY] ?: GoalParams.DEFAULT_RUNTIME_HOURS)
    }

    /** The full bundle as a single Flow for the engine/service to thread in. */
    val params: Flow<GoalParams> =
        combine(fpsFloor, tempCeilingC, targetRuntimeHours) { fps, temp, hours ->
            GoalParams(fpsFloor = fps, tempCeilingC = temp, targetRuntimeHours = hours)
        }

    suspend fun setFpsFloor(fps: Int) {
        context.goalParamsStore.edit { it[FPS_FLOOR_KEY] = GoalParams.snapFpsFloor(fps) }
    }

    suspend fun setTempCeilingC(tempC: Int) {
        context.goalParamsStore.edit { it[TEMP_CEILING_KEY] = GoalParams.clampTempCeiling(tempC) }
    }

    suspend fun setTargetRuntimeHours(hours: Float) {
        context.goalParamsStore.edit { it[RUNTIME_HOURS_KEY] = GoalParams.clampRuntimeHours(hours) }
    }

    private companion object {
        val FPS_FLOOR_KEY     = intPreferencesKey("goal_fps_floor")
        val TEMP_CEILING_KEY  = intPreferencesKey("goal_temp_ceiling_c")
        val RUNTIME_HOURS_KEY = floatPreferencesKey("goal_target_runtime_hours")
    }
}

// DataStore extension — isolated store, no collision with user_prefs / charging_bundle.
private val Context.goalParamsStore by preferencesDataStore(name = "goal_params")
