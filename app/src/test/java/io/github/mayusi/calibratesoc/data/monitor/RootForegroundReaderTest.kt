package io.github.mayusi.calibratesoc.data.monitor

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pure-JVM tests for [RootForegroundReader].
 *
 * Covers:
 *  1. Parse logic: various real-world mResumedActivity and mCurrentFocus formats
 *     across Android versions and OEM kernels — tested via the internal helpers.
 *  2. Transactability gate: returns null immediately when PServer is not live,
 *     with zero executeShell calls.
 *  3. Source selection: root activity-dump preferred; window-dump fallback;
 *     honest null when both fail.
 *  4. Honesty: null returned (never fabricated) when executeShell returns null
 *     or stdout is blank/unparseable.
 *
 * [PServerWriter] is mocked with MockK — no Android binder touched.
 */
class RootForegroundReaderTest {

    // ── extractPkgFromActivityRecord — parse logic (no PServer needed) ───────

    private val pserver = mockk<PServerWriter>(relaxed = true)
    private val reader = RootForegroundReader(pserver)

    @Test
    fun `extractPkgFromActivityRecord parses standard Android 12 format`() {
        val output = "    mResumedActivity: ActivityRecord{abc123 u0 com.foo.game/.MainActivity t42}"
        assertThat(reader.extractPkgFromActivityRecord(output)).isEqualTo("com.foo.game")
    }

    @Test
    fun `extractPkgFromActivityRecord parses fully-qualified class component`() {
        val output = "  mResumedActivity: ActivityRecord{0 u0 com.foo.game/com.foo.game.MainActivity t1}"
        assertThat(reader.extractPkgFromActivityRecord(output)).isEqualTo("com.foo.game")
    }

    @Test
    fun `extractPkgFromActivityRecord handles equals-sign separator (older Android)`() {
        val output = "mResumedActivity=ActivityRecord{abc u0 com.foo.game/.Main t1}"
        assertThat(reader.extractPkgFromActivityRecord(output)).isEqualTo("com.foo.game")
    }

    @Test
    fun `extractPkgFromActivityRecord returns null for blank output (honest null)`() {
        assertThat(reader.extractPkgFromActivityRecord("")).isNull()
        assertThat(reader.extractPkgFromActivityRecord("   ")).isNull()
    }

    @Test
    fun `extractPkgFromActivityRecord returns null when line has no slash token`() {
        // No slash in any token → no component → null (never guess from bare pkg token)
        val output = "  mResumedActivity: ActivityRecord{0 u0 com.foo.game t1}"
        assertThat(reader.extractPkgFromActivityRecord(output)).isNull()
    }

    @Test
    fun `extractPkgFromActivityRecord returns null for non-matching line`() {
        val output = "  mSomethingElse: ActivityRecord{0 u0 com.foo.game/.Main t1}"
        assertThat(reader.extractPkgFromActivityRecord(output)).isNull()
    }

    @Test
    fun `extractPkgFromActivityRecord handles multi-line output and finds first match`() {
        val output = """
            mTopResumedActivity: null
            mResumedActivity: ActivityRecord{1 u0 com.bar.baz/.Main t5}
            mSomethingElse: ActivityRecord{2 u0 com.other/.Main t6}
        """.trimIndent()
        assertThat(reader.extractPkgFromActivityRecord(output)).isEqualTo("com.bar.baz")
    }

    @Test
    fun `extractPkgFromActivityRecord rejects single-segment token before slash`() {
        // "MainActivity" has no dot → PACKAGE_PATTERN rejects it → null (honest)
        val output = "  mResumedActivity: ActivityRecord{0 u0 MainActivity/com.foo.Main t1}"
        assertThat(reader.extractPkgFromActivityRecord(output)).isNull()
    }

    // ── extractPkgFromWindowFocus ─────────────────────────────────────────────

    @Test
    fun `extractPkgFromWindowFocus parses standard mCurrentFocus format`() {
        val output = "  mCurrentFocus=Window{abc u0 com.foo.game/com.foo.game.MainActivity}"
        assertThat(reader.extractPkgFromWindowFocus(output)).isEqualTo("com.foo.game")
    }

    @Test
    fun `extractPkgFromWindowFocus parses short-form component`() {
        val output = "  mCurrentFocus=Window{123 u0 com.foo.game/.Main}"
        assertThat(reader.extractPkgFromWindowFocus(output)).isEqualTo("com.foo.game")
    }

    @Test
    fun `extractPkgFromWindowFocus returns null for blank output`() {
        assertThat(reader.extractPkgFromWindowFocus("")).isNull()
    }

    @Test
    fun `extractPkgFromWindowFocus returns null for non-matching line`() {
        val output = "  mFocusedApp=ActivityRecord{0 u0 com.foo.game/.Main t1}"
        assertThat(reader.extractPkgFromWindowFocus(output)).isNull()
    }

    @Test
    fun `extractPkgFromWindowFocus returns null when no slash token present`() {
        // StatusBar or other system windows have no component token
        val output = "  mCurrentFocus=Window{123 u0 StatusBar}"
        assertThat(reader.extractPkgFromWindowFocus(output)).isNull()
    }

    @Test
    fun `extractPkgFromWindowFocus is case-insensitive on mCurrentFocus keyword`() {
        // Some OEM kernels capitalise differently (mCurrentfocus vs mCurrentFocus)
        val output = "  mCurrentfocus=Window{0 u0 com.foo.game/.Main}"
        assertThat(reader.extractPkgFromWindowFocus(output)).isEqualTo("com.foo.game")
    }

    // ── Transactability gate ──────────────────────────────────────────────────

    @Test
    fun `readForegroundPkg returns null immediately when PServer is not transactable`() = runBlocking {
        every { pserver.transactableNow() } returns false
        assertThat(reader.readForegroundPkg()).isNull()
        // Zero shell calls — never pay IPC when PServer is not live
        coVerify(exactly = 0) { pserver.executeShell(any()) }
    }

    // ── Source selection: activity-dump preferred, window-dump fallback ───────

    @Test
    fun `readForegroundPkg returns package from activity dump when PServer live`() = runBlocking {
        every { pserver.transactableNow() } returns true
        val activityCmd = "dumpsys activity activities | grep 'mResumedActivity'"
        coEvery { pserver.executeShell(activityCmd) } returns
            (0 to "  mResumedActivity: ActivityRecord{0 u0 com.example.game/.Main t1}")

        assertThat(reader.readForegroundPkg()).isEqualTo("com.example.game")
        // Only the activity-dump command should have been called (window not needed)
        coVerify(exactly = 1) { pserver.executeShell(activityCmd) }
        coVerify(exactly = 0) { pserver.executeShell("dumpsys window | grep 'mCurrentFocus'") }
    }

    @Test
    fun `readForegroundPkg falls back to window dump when activity dump yields blank`() = runBlocking {
        every { pserver.transactableNow() } returns true
        val activityCmd = "dumpsys activity activities | grep 'mResumedActivity'"
        val windowCmd   = "dumpsys window | grep 'mCurrentFocus'"
        coEvery { pserver.executeShell(activityCmd) } returns (1 to "")
        coEvery { pserver.executeShell(windowCmd) } returns
            (0 to "  mCurrentFocus=Window{0 u0 com.example.game/.Main}")

        assertThat(reader.readForegroundPkg()).isEqualTo("com.example.game")
        coVerify(exactly = 1) { pserver.executeShell(activityCmd) }
        coVerify(exactly = 1) { pserver.executeShell(windowCmd) }
    }

    @Test
    fun `readForegroundPkg returns null when both dumps yield blank (honest null)`() = runBlocking {
        every { pserver.transactableNow() } returns true
        val activityCmd = "dumpsys activity activities | grep 'mResumedActivity'"
        val windowCmd   = "dumpsys window | grep 'mCurrentFocus'"
        coEvery { pserver.executeShell(activityCmd) } returns (1 to "")
        coEvery { pserver.executeShell(windowCmd) } returns (1 to "")

        assertThat(reader.readForegroundPkg()).isNull()
    }

    @Test
    fun `readForegroundPkg returns null when executeShell returns null for both paths`() = runBlocking {
        every { pserver.transactableNow() } returns true
        coEvery { pserver.executeShell(any()) } returns null

        // executeShell null → both paths return null → honest null, never fabricated
        assertThat(reader.readForegroundPkg()).isNull()
    }

    @Test
    fun `readForegroundPkg returns null when activity dump output is unparseable`() = runBlocking {
        every { pserver.transactableNow() } returns true
        val activityCmd = "dumpsys activity activities | grep 'mResumedActivity'"
        val windowCmd   = "dumpsys window | grep 'mCurrentFocus'"
        // stdout has the keyword but no slash token → parse yields null
        coEvery { pserver.executeShell(activityCmd) } returns
            (0 to "  mResumedActivity: ActivityRecord{0 u0 NoComponentHere t1}")
        coEvery { pserver.executeShell(windowCmd) } returns (1 to "")

        assertThat(reader.readForegroundPkg()).isNull()
    }
}
