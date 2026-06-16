package io.github.mayusi.calibratesoc.ui.autotdp

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.autotdp.WorkloadContext
import org.junit.Test

/**
 * JVM unit tests for [GoalProfileUi] — pure string-mapping helpers.
 *
 * No Android, no mocks, no coroutines.
 *
 * Covers:
 *  1. goalLabel(goal) — all 5 modes produce non-empty sentence-case strings.
 *  2. goalShortLabel(goal) — compact label for HUD / chip; ≤12 chars.
 *  3. goalDescription(goal) — each goal has a description.
 *  4. detectedContextLabel(context) — all WorkloadContext values map.
 *  5. detectedContextShort(context) — compact label ≤10 chars.
 *  6. autoDetectedLine — composite "Heavy 3D -> Balanced smart" line.
 *  7. Honesty: AUTO is described as "auto-detect"; it has no band/power description.
 */
class GoalProfileUiTest {

    // ── 1. goalLabel ─────────────────────────────────────────────────────────────

    @Test
    fun goalLabel_allModes_nonEmpty() {
        GoalProfile.entries.forEach { goal ->
            val label = GoalProfileUi.goalLabel(goal)
            assertThat(label).isNotEmpty()
        }
    }

    @Test
    fun goalLabel_sentenceCase_firstCharUpper() {
        GoalProfile.entries.forEach { goal ->
            val label = GoalProfileUi.goalLabel(goal)
            assertWithMessage("goalLabel($goal) first char should be uppercase")
                .that(label.first().isUpperCase())
                .isTrue()
        }
    }

    @Test
    fun goalLabel_auto_containsAutoOrDetect() {
        val label = GoalProfileUi.goalLabel(GoalProfile.AUTO)
        val lower = label.lowercase()
        assertThat(lower.contains("auto") || lower.contains("detect")).isTrue()
    }

    @Test
    fun goalLabel_maxFps_containsFpsOrMax() {
        val label = GoalProfileUi.goalLabel(GoalProfile.MAX_FPS).lowercase()
        assertThat(label.contains("fps") || label.contains("max") || label.contains("performance")).isTrue()
    }

    @Test
    fun goalLabel_batterySaver_containsBattery() {
        val label = GoalProfileUi.goalLabel(GoalProfile.BATTERY_SAVER).lowercase()
        assertThat(label.contains("battery") || label.contains("saver")).isTrue()
    }

    @Test
    fun goalLabel_coolQuiet_containsCoolOrQuiet() {
        val label = GoalProfileUi.goalLabel(GoalProfile.COOL_QUIET).lowercase()
        assertThat(label.contains("cool") || label.contains("quiet") || label.contains("thermal")).isTrue()
    }

    // ── 2. goalShortLabel ────────────────────────────────────────────────────────

    @Test
    fun goalShortLabel_allModes_atMost12Chars() {
        GoalProfile.entries.forEach { goal ->
            val short = GoalProfileUi.goalShortLabel(goal)
            assertWithMessage("goalShortLabel($goal) must be ≤12 chars")
                .that(short.length)
                .isAtMost(12)
        }
    }

    @Test
    fun goalShortLabel_allModes_nonEmpty() {
        GoalProfile.entries.forEach { goal ->
            assertThat(GoalProfileUi.goalShortLabel(goal)).isNotEmpty()
        }
    }

    @Test
    fun goalShortLabel_distinctAcrossModes() {
        val labels = GoalProfile.entries.map { GoalProfileUi.goalShortLabel(it) }
        assertThat(labels.toSet()).hasSize(GoalProfile.entries.size)
    }

    // ── 3. goalDescription ───────────────────────────────────────────────────────

    @Test
    fun goalDescription_allModes_nonEmpty() {
        GoalProfile.entries.forEach { goal ->
            val desc = GoalProfileUi.goalDescription(goal)
            assertThat(desc).isNotEmpty()
        }
    }

    @Test
    fun goalDescription_auto_mentionsAutoOrContext() {
        val desc = GoalProfileUi.goalDescription(GoalProfile.AUTO).lowercase()
        assertThat(
            desc.contains("detect") ||
                desc.contains("classif") ||
                desc.contains("auto") ||
                desc.contains("workload")
        ).isTrue()
    }

    // ── 4. detectedContextLabel ──────────────────────────────────────────────────

    @Test
    fun detectedContextLabel_allContexts_nonEmpty() {
        WorkloadContext.entries.forEach { ctx ->
            val label = GoalProfileUi.detectedContextLabel(ctx)
            assertThat(label).isNotEmpty()
        }
    }

    @Test
    fun detectedContextLabel_heavyGame_containsGameOrHeavy() {
        val label = GoalProfileUi.detectedContextLabel(WorkloadContext.HEAVY_GAME).lowercase()
        assertThat(
            label.contains("game") ||
                label.contains("heavy") ||
                label.contains("3d") ||
                label.contains("gaming")
        ).isTrue()
    }

    @Test
    fun detectedContextLabel_idle_containsIdleOrBackground() {
        val label = GoalProfileUi.detectedContextLabel(WorkloadContext.IDLE).lowercase()
        assertThat(label.contains("idle") || label.contains("background") || label.contains("low")).isTrue()
    }

    @Test
    fun detectedContextLabel_video_containsVideoOrMedia() {
        val label = GoalProfileUi.detectedContextLabel(WorkloadContext.VIDEO).lowercase()
        assertThat(label.contains("video") || label.contains("media") || label.contains("stream")).isTrue()
    }

    @Test
    fun detectedContextLabel_unknown_notRawEnumName() {
        val label = GoalProfileUi.detectedContextLabel(WorkloadContext.UNKNOWN)
        assertThat(label).isNotEqualTo("UNKNOWN")
        assertThat(label).isNotEmpty()
    }

    // ── 5. detectedContextShort ──────────────────────────────────────────────────

    @Test
    fun detectedContextShort_allContexts_atMost10Chars() {
        WorkloadContext.entries.forEach { ctx ->
            val short = GoalProfileUi.detectedContextShort(ctx)
            assertWithMessage("detectedContextShort($ctx) must be ≤10 chars")
                .that(short.length)
                .isAtMost(10)
        }
    }

    @Test
    fun detectedContextShort_allContexts_nonEmpty() {
        WorkloadContext.entries.forEach { ctx ->
            assertThat(GoalProfileUi.detectedContextShort(ctx)).isNotEmpty()
        }
    }

    // ── 6. autoDetectedLine ──────────────────────────────────────────────────────

    @Test
    fun autoDetectedLine_containsArrow() {
        val line = GoalProfileUi.autoDetectedLine(WorkloadContext.HEAVY_GAME, GoalProfile.BALANCED_SMART)
        assertThat(line.contains("->") || line.contains("→") || line.contains(" to ")).isTrue()
    }

    @Test
    fun autoDetectedLine_containsContextLabel() {
        val ctx = WorkloadContext.VIDEO
        val goal = GoalProfile.COOL_QUIET
        val line = GoalProfileUi.autoDetectedLine(ctx, goal).lowercase()
        val contextLabel = GoalProfileUi.detectedContextLabel(ctx).lowercase()
        assertThat(
            line.contains(contextLabel) ||
                line.contains("video") ||
                line.contains("media")
        ).isTrue()
    }

    @Test
    fun autoDetectedLine_allCombinations_nonEmpty() {
        WorkloadContext.entries.forEach { ctx ->
            GoalProfile.entries
                .filter { it != GoalProfile.AUTO }
                .forEach { goal ->
                    assertWithMessage("autoDetectedLine($ctx, $goal) must not be empty")
                        .that(GoalProfileUi.autoDetectedLine(ctx, goal))
                        .isNotEmpty()
                }
        }
    }

    // ── 7. Honesty invariants ────────────────────────────────────────────────────

    @Test
    fun goalDescription_auto_noBandRanges() {
        val desc = GoalProfileUi.goalDescription(GoalProfile.AUTO).lowercase()
        // AUTO has no hard power ceiling — must not claim specific band ranges
        assertThat(desc).doesNotContain("70–95")
        assertThat(desc).doesNotContain("35–60")
    }

    @Test
    fun goalLabel_noEmojiCodePoints() {
        // Project rule: no emoji in UI strings
        GoalProfile.entries.forEach { goal ->
            val label = GoalProfileUi.goalLabel(goal)
            label.codePoints().forEach { cp ->
                val type = Character.getType(cp)
                assertWithMessage("goalLabel($goal) must not contain emoji (codepoint $cp)")
                    .that(type)
                    .isNotIn(listOf(Character.OTHER_SYMBOL.toInt(), Character.SURROGATE.toInt()))
            }
        }
    }
}
