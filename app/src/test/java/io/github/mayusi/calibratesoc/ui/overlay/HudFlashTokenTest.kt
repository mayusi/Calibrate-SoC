package io.github.mayusi.calibratesoc.ui.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * JVM-pure unit tests for the atomic flash-token discipline used in
 * [HudTuneController.flashActionMessage].
 *
 * The bug being guarded (BUG 2 from the adversarial review): if two profile
 * chips are tapped within 6 s, the first coroutine could wake up and clear
 * the *second* message (or fail to, depending on string equality). The
 * string-equality guard in the old applyProfileViaScript hand-roll was
 * incorrect. The fix routes through flashActionMessage(), which uses an
 * AtomicLong token so only the *most recent* flash can clear.
 *
 * These tests model the token state machine directly — no coroutines,
 * no Android, no Compose.
 */
class HudFlashTokenTest {

    // ── Minimal simulation of the flashToken discipline ───────────────────────

    /**
     * Simulates the contract of [HudTuneController.flashActionMessage]:
     *
     *  1. Increment the global token and capture it as `myToken`.
     *  2. Show the message immediately.
     *  3. After the delay, clear only if flashToken is STILL == myToken.
     *
     * Returns the token value that was "captured" by this flash call.
     */
    private fun simulateFlash(
        globalToken: AtomicLong,
        onShow: (String) -> Unit,
    ): Long {
        val token = globalToken.incrementAndGet()
        onShow("msg-$token")
        return token
    }

    /**
     * Simulates the "delay elapsed → try to clear" step for a previously
     * captured [token].
     */
    private fun simulateClear(
        globalToken: AtomicLong,
        capturedToken: Long,
        onClear: () -> Unit,
    ) {
        if (globalToken.get() == capturedToken) onClear()
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `single flash — delay clear succeeds when no second tap`() {
        val globalToken = AtomicLong(0)
        var currentMsg: String? = null

        val token = simulateFlash(globalToken) { msg -> currentMsg = msg }
        assertThat(currentMsg).isEqualTo("msg-1")

        // Delay elapses — no second tap — token unchanged → clear is allowed.
        simulateClear(globalToken, token) { currentMsg = null }
        assertThat(currentMsg).isNull()
    }

    @Test
    fun `second flash before delay — first clear does not wipe second message`() {
        val globalToken = AtomicLong(0)
        var currentMsg: String? = null

        val token1 = simulateFlash(globalToken) { msg -> currentMsg = msg }
        // Second tap arrives before the first delay elapses.
        val token2 = simulateFlash(globalToken) { msg -> currentMsg = msg }

        assertThat(currentMsg).isEqualTo("msg-2")

        // First coroutine wakes up — token != capturedToken → does NOT clear.
        simulateClear(globalToken, token1) { currentMsg = null }
        assertThat(currentMsg).isEqualTo("msg-2")  // still showing second message!

        // Second coroutine wakes up — token matches → clears correctly.
        simulateClear(globalToken, token2) { currentMsg = null }
        assertThat(currentMsg).isNull()
    }

    @Test
    fun `many rapid taps — only the last clear wins`() {
        val globalToken = AtomicLong(0)
        var currentMsg: String? = null

        val tokens = mutableListOf<Long>()
        repeat(10) {
            tokens += simulateFlash(globalToken) { msg -> currentMsg = msg }
        }

        // All coroutines except the last are stale and must not clear.
        for (i in 0 until tokens.size - 1) {
            simulateClear(globalToken, tokens[i]) { currentMsg = null }
            assertThat(currentMsg).isEqualTo("msg-10")  // message unchanged
        }

        // Last clear fires — should clear.
        simulateClear(globalToken, tokens.last()) { currentMsg = null }
        assertThat(currentMsg).isNull()
    }

    @Test
    fun `token counter increments monotonically — no collision between calls`() {
        val globalToken = AtomicLong(0)
        val capturedTokens = mutableListOf<Long>()

        repeat(100) {
            capturedTokens += simulateFlash(globalToken) { }
        }

        // All tokens are strictly increasing — no two are equal.
        assertThat(capturedTokens).hasSize(100)
        for (i in 1 until capturedTokens.size) {
            assertThat(capturedTokens[i]).isGreaterThan(capturedTokens[i - 1])
        }
    }

    @Test
    fun `string-equality guard is insufficient — motivating example`() {
        // Demonstrates WHY the old string-compare guard was wrong: two taps
        // with the SAME profile produce identical message strings, so the
        // first coroutine would unconditionally clear the second message.
        //
        // The token approach is immune because it compares identity (Long),
        // not content (String).

        val globalToken = AtomicLong(0)
        val messages = mutableListOf<String>()
        var currentMsg: String? = null

        // Tap profile "Balanced" twice in rapid succession — same string each time.
        val msgText = "Wrote Balanced → /sdcard/CalibrateSoC."
        val token1 = globalToken.incrementAndGet()
        currentMsg = msgText
        messages += msgText

        val token2 = globalToken.incrementAndGet()
        currentMsg = msgText  // same string — would fool a string-equality guard
        messages += msgText

        // Old string-equality clear: both coroutines can clear (same string == match).
        // The first one clears 4 seconds before it should.
        val oldGuardWouldClear1 = (currentMsg == msgText)  // true — wrong!
        val oldGuardWouldClear2 = (currentMsg == msgText)  // true — correct for the second

        // New token guard: only the second coroutine clears.
        val newGuardWouldClear1 = (globalToken.get() == token1)  // false — correct!
        val newGuardWouldClear2 = (globalToken.get() == token2)  // true — correct!

        assertThat(oldGuardWouldClear1).isTrue()   // old guard erroneously allows early clear
        assertThat(newGuardWouldClear1).isFalse()  // new guard correctly blocks it
        assertThat(newGuardWouldClear2).isTrue()   // new guard correctly allows final clear
    }
}
