package io.github.mayusi.calibratesoc.ui.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [ForceSelinuxGate] — the pure decision logic for WHEN the
 * wizard may even offer "Force SELinux" (SELinux Permissive).
 *
 * The contract:
 *   - Offer Force SELinux ONLY when there is NO no-Permissive live path:
 *     not chmod-direct-writable, no PServer, no AYANEO binder, not root.
 *   - If ANY one of those live paths exists, NEVER offer it (Permissive
 *     would be pointless AND it breaks emulation / weakens security).
 *
 * This is exercised directly against the real [ForceSelinuxGate] object so
 * the production logic — not a copy — is what's verified.
 */
class ForceSelinuxGateTest {

    private fun signals(
        sysfs: Boolean = false,
        pserver: Boolean = false,
        ayaneo: Boolean = false,
        root: Boolean = false,
    ) = ForceSelinuxGate.Signals(
        sysfsDirectlyWritable = sysfs,
        pserverSysfsLive = pserver,
        ayaneoBinderLive = ayaneo,
        isRoot = root,
    )

    // ── The genuine last-resort case: offer it ────────────────────────────────

    @Test
    fun `offers Force SELinux only when no live path exists`() {
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals())).isTrue()
    }

    // ── Any single live path suppresses the offer ─────────────────────────────

    @Test
    fun `PServer live path suppresses the offer (Odin AYN)`() {
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals(pserver = true))).isFalse()
    }

    @Test
    fun `AYANEO binder live path suppresses the offer`() {
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals(ayaneo = true))).isFalse()
    }

    @Test
    fun `root suppresses the offer`() {
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals(root = true))).isFalse()
    }

    @Test
    fun `already-live chmod-direct sysfs suppresses the offer`() {
        // If the chmod-direct path is already live, Force SELinux already did
        // its one job — never re-push it.
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals(sysfs = true))).isFalse()
    }

    @Test
    fun `multiple live paths still suppress the offer`() {
        assertThat(
            ForceSelinuxGate.shouldOfferForceSelinux(
                signals(pserver = true, ayaneo = true, root = true),
            ),
        ).isFalse()
    }

    // ── hasAnyNoPermissiveLivePath is the exact complement ────────────────────

    @Test
    fun `gate is the strict complement of hasAnyNoPermissiveLivePath`() {
        val cases = listOf(
            signals(),
            signals(sysfs = true),
            signals(pserver = true),
            signals(ayaneo = true),
            signals(root = true),
            signals(pserver = true, root = true),
        )
        for (s in cases) {
            assertThat(ForceSelinuxGate.shouldOfferForceSelinux(s))
                .isEqualTo(!ForceSelinuxGate.hasAnyNoPermissiveLivePath(s))
        }
    }

    // ── Device-shaped scenarios (honesty: real handhelds never get pushed) ────

    @Test
    fun `Odin AYANEO RP6 representative live devices never get Force SELinux`() {
        // Odin/AYN via PServer.
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals(pserver = true))).isFalse()
        // AYANEO via gamewindow binder.
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals(ayaneo = true))).isFalse()
        // A device that ran the script and got chmod-direct live.
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals(sysfs = true))).isFalse()
        // A rooted device.
        assertThat(ForceSelinuxGate.shouldOfferForceSelinux(signals(root = true))).isFalse()
    }

    @Test
    fun `a device with literally no live path is the only one offered the last resort`() {
        assertThat(
            ForceSelinuxGate.shouldOfferForceSelinux(
                signals(sysfs = false, pserver = false, ayaneo = false, root = false),
            ),
        ).isTrue()
    }
}
