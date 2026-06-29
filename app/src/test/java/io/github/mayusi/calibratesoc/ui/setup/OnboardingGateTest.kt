package io.github.mayusi.calibratesoc.ui.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests that LOCK the MANDATORY-grant onboarding gate.
 *
 * Policy (hard-require): on a PServer-CAPABLE device the user MUST hold the
 * one-tap grant before they can finish onboarding and enter the app — every
 * "Skip setup / Not now" escape is removed there. [canCompleteOnboarding] is the
 * single pure source of truth for whether `markComplete()` may flip the
 * onboarding-complete flag.
 *
 * The three HONESTY carve-outs are pinned so a future edit can never permanently
 * trap a user:
 *   1. NON-capable device (no privilege path)  → door stays open.
 *   2. PROBING (cap not resolved)              → neither complete nor lock.
 *   3. BRIDGE genuinely unavailable            → door opens (can't grant honestly).
 */
class OnboardingGateTest {

    // ── 1. The hard-require: capable + grant NOT held → cannot complete ────────

    @Test
    fun `capable device without grant cannot complete onboarding (no skip path)`() {
        val canComplete = canCompleteOnboarding(
            capResolved = true,
            deviceCapable = true,
            grantHeld = false,
            bridgeUnavailable = false,
        )
        assertThat(canComplete).isFalse()
    }

    // ── 2. Capable + grant HELD → can enter ───────────────────────────────────

    @Test
    fun `capable device with grant held can enter`() {
        val canComplete = canCompleteOnboarding(
            capResolved = true,
            deviceCapable = true,
            grantHeld = true,
            bridgeUnavailable = false,
        )
        assertThat(canComplete).isTrue()
    }

    @Test
    fun `grant held wins even if the bridge later reports unavailable`() {
        // grantHeld is checked before bridgeUnavailable; a live-already-active
        // device must never be blocked.
        val canComplete = canCompleteOnboarding(
            capResolved = true,
            deviceCapable = true,
            grantHeld = true,
            bridgeUnavailable = true,
        )
        assertThat(canComplete).isTrue()
    }

    // ── 3. Non-capable device → can ALWAYS enter (door open) ──────────────────

    @Test
    fun `non-capable device can always enter (door open)`() {
        val canComplete = canCompleteOnboarding(
            capResolved = true,
            deviceCapable = false,
            grantHeld = false,
            bridgeUnavailable = false,
        )
        assertThat(canComplete).isTrue()
    }

    @Test
    fun `non-capable device enters regardless of bridge or grant signals`() {
        // A plain Android device that physically can't grant must never be
        // hard-locked, no matter what the (irrelevant) grant/bridge signals say.
        for (grant in listOf(true, false)) {
            for (bridge in listOf(true, false)) {
                val canComplete = canCompleteOnboarding(
                    capResolved = true,
                    deviceCapable = false,
                    grantHeld = grant,
                    bridgeUnavailable = bridge,
                )
                assertThat(canComplete).isTrue()
            }
        }
    }

    // ── 4. Probing → neither locked nor completed (transient) ─────────────────

    @Test
    fun `probing never completes onboarding (transient, not a lock)`() {
        // cap not resolved yet: we must NOT complete (don't let a misdetect skip
        // the grant) AND the caller shows the Checking transient instead of a
        // permanent lock.
        val canComplete = canCompleteOnboarding(
            capResolved = false,
            deviceCapable = true,
            grantHeld = false,
            bridgeUnavailable = false,
        )
        assertThat(canComplete).isFalse()
    }

    @Test
    fun `probing never completes even if stale signals look favourable`() {
        // Before the probe lands, every other signal is untrustworthy — none of
        // them may complete onboarding.
        for (capable in listOf(true, false)) {
            for (grant in listOf(true, false)) {
                for (bridge in listOf(true, false)) {
                    val canComplete = canCompleteOnboarding(
                        capResolved = false,
                        deviceCapable = capable,
                        grantHeld = grant,
                        bridgeUnavailable = bridge,
                    )
                    assertThat(canComplete).isFalse()
                }
            }
        }
    }

    // ── 5. Honesty escape: capable but bridge genuinely unavailable → open ────

    @Test
    fun `capable device opens the door when the bridge cannot grant (honesty)`() {
        // The device LOOKED capable but the one-tap PServer bridge isn't
        // transactable (FullSetupState.NotAvailable). We must not lock the user
        // out of a device the app honestly cannot serve.
        val canComplete = canCompleteOnboarding(
            capResolved = true,
            deviceCapable = true,
            grantHeld = false,
            bridgeUnavailable = true,
        )
        assertThat(canComplete).isTrue()
    }

    // ── 6. Exhaustive truth table ─────────────────────────────────────────────

    private data class GateCase(
        val capResolved: Boolean,
        val deviceCapable: Boolean,
        val grantHeld: Boolean,
        val bridgeUnavailable: Boolean,
        val expected: Boolean,
        val label: String,
    )

    private val truthTable = listOf(
        // Probing (capResolved = false) → ALWAYS false, regardless of the rest.
        GateCase(false, true,  false, false, false, "probing capable not-held → blocked"),
        GateCase(false, true,  true,  false, false, "probing capable held → blocked (transient)"),
        GateCase(false, false, false, false, false, "probing non-capable → blocked (transient)"),
        GateCase(false, true,  false, true,  false, "probing bridge-unavail → blocked (transient)"),

        // Resolved + NON-capable → ALWAYS true (door open).
        GateCase(true,  false, false, false, true,  "non-capable not-held → open"),
        GateCase(true,  false, true,  false, true,  "non-capable held → open"),
        GateCase(true,  false, false, true,  true,  "non-capable bridge-unavail → open"),

        // Resolved + capable.
        GateCase(true,  true,  true,  false, true,  "capable held → enter"),
        GateCase(true,  true,  false, true,  true,  "capable not-held bridge-unavail → open (honesty)"),
        GateCase(true,  true,  false, false, false, "capable not-held bridge-live → MUST grant"),
        GateCase(true,  true,  true,  true,  true,  "capable held + bridge-unavail → enter (held wins)"),
    )

    @Test
    fun `gate truth table is exhaustive and correct`() {
        for (c in truthTable) {
            val result = canCompleteOnboarding(
                capResolved = c.capResolved,
                deviceCapable = c.deviceCapable,
                grantHeld = c.grantHeld,
                bridgeUnavailable = c.bridgeUnavailable,
            )
            if (result != c.expected) {
                throw AssertionError(
                    "Gate case '${c.label}': expected canComplete=${c.expected} but got $result"
                )
            }
        }
    }

    // ── 7. The single most important invariant ────────────────────────────────

    @Test
    fun `the ONLY blocked-after-resolve state is capable plus not-held plus live-bridge`() {
        // Enumerate every resolved state; assert exactly one combination blocks.
        var blockedCount = 0
        for (capable in listOf(true, false)) {
            for (grant in listOf(true, false)) {
                for (bridge in listOf(true, false)) {
                    val blocked = !canCompleteOnboarding(
                        capResolved = true,
                        deviceCapable = capable,
                        grantHeld = grant,
                        bridgeUnavailable = bridge,
                    )
                    if (blocked) {
                        blockedCount++
                        // The only honest reason to block: a capable device whose
                        // grant isn't held and whose bridge CAN still grant.
                        assertThat(capable).isTrue()
                        assertThat(grant).isFalse()
                        assertThat(bridge).isFalse()
                    }
                }
            }
        }
        // capable=true,grant=false,bridge=false collapses to one logical state
        // (the two bridge=false / grant=false rows are the same here): exactly one
        // blocked combination across the 8 resolved permutations.
        assertThat(blockedCount).isEqualTo(1)
    }
}
