package io.github.mayusi.calibratesoc.data.script

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerCommandGuard
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests for the one-trust auto-setup engine ([AdvancedPermissionsScript.grantViaExecutor])
 * and the guard's acceptance of every grant command it issues.
 *
 * The engine's pure core takes an injected executor + readback so it can be
 * exercised with zero Android dependencies (no PackageManager, no PServer). We
 * verify three honesty invariants:
 *   1. When PServer IS transactable, it issues a clean `pm grant <pkg> <perm>`
 *      for every (package, perm) pair and reports ONLY the perms the readback
 *      says are actually held — never the commands merely sent.
 *   2. When PServer is NOT transactable, it issues NO commands and returns
 *      NotAvailable — never a fabricated success.
 *   3. Every grant command it would send is ALLOWED by [PServerCommandGuard]
 *      (so the build needs no guard widening and nothing is bypassed).
 */
class AdvancedPermissionsGrantTest {

    // A bare instance is fine: grantViaExecutor never touches the constructor
    // dependencies (context / pServerWriter) — it operates purely on its args.
    private val script = AdvancedPermissionsScript(
        context = mockk(relaxed = true),
        pServerWriter = mockk(relaxed = true),
    )

    private val pkgs = listOf("io.github.mayusi.calibratesoc", "io.github.mayusi.calibratesoc.debug")

    private val allThreePerms = listOf(
        "android.permission.DUMP",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.WRITE_SECURE_SETTINGS",
    )

    private fun grants(
        dump: Boolean = false,
        usage: Boolean = false,
        secure: Boolean = false,
        sysfs: Boolean = false,
    ) = AdvancedPermissionsScript.Grants(
        dump = dump,
        usageStats = usage,
        writeSecureSettings = secure,
        sysfsWritable = sysfs,
    )

    // ── 1. Transactable: issues the grants + reports only held perms ──────────

    @Test
    fun grantViaExecutor_issuesAllGrantCommands_forEveryPackageAndPerm() = runTest {
        val issued = mutableListOf<String>()
        val result = script.grantViaExecutor(
            packages = pkgs,
            isTransactable = { true },
            executeShell = { cmd -> issued.add(cmd); 0 to "" },
            // Pretend the readback shows all three landed.
            readback = { grants(dump = true, usage = true, secure = true) },
        )

        // Exactly one `pm grant <pkg> <perm>` per (package, perm) pair, clean shape.
        val expected = pkgs.flatMap { p -> allThreePerms.map { perm -> "pm grant $p $perm" } }
        assertThat(issued).containsExactlyElementsIn(expected)

        assertThat(result).isInstanceOf(AdvancedPermissionsScript.GrantResult.Completed::class.java)
        val completed = result as AdvancedPermissionsScript.GrantResult.Completed
        assertThat(completed.allGranted).isTrue()
        assertThat(completed.issuedCommands).containsExactlyElementsIn(expected)
    }

    @Test
    fun grantViaExecutor_reportsOnlyActuallyHeldPerms_notWhatWasSent() = runTest {
        // We SEND all three grants, but the readback says only DUMP landed (e.g.
        // an OTA flipped SELinux mid-flight). The result MUST be honest: held=DUMP
        // only, allGranted=false — never claim a perm we couldn't confirm.
        val result = script.grantViaExecutor(
            packages = pkgs,
            isTransactable = { true },
            executeShell = { _ -> 0 to "" },
            readback = { grants(dump = true, usage = false, secure = false) },
        )

        val completed = result as AdvancedPermissionsScript.GrantResult.Completed
        assertThat(completed.held.dump).isTrue()
        assertThat(completed.held.usageStats).isFalse()
        assertThat(completed.held.writeSecureSettings).isFalse()
        assertThat(completed.allGranted).isFalse()
    }

    // ── 2. Not transactable: no grants, honest NotAvailable ───────────────────

    @Test
    fun grantViaExecutor_pserverNotTransactable_issuesNothing_returnsNotAvailable() = runTest {
        var executorCalled = false
        var readbackCalled = false
        val result = script.grantViaExecutor(
            packages = pkgs,
            isTransactable = { false },
            executeShell = { _ -> executorCalled = true; 0 to "" },
            readback = { readbackCalled = true; grants(dump = true) },
        )

        // No commands issued, no readback — and definitely no fabricated success.
        assertThat(executorCalled).isFalse()
        assertThat(readbackCalled).isFalse()
        assertThat(result).isSameInstanceAs(AdvancedPermissionsScript.GrantResult.NotAvailable)
    }

    @Test
    fun grantViaExecutor_executorFailureDoesNotCrash_stillReportsReadback() = runTest {
        // A sibling-variant grant that throws (variant not installed) must not abort
        // the run; the readback remains the source of truth.
        val result = script.grantViaExecutor(
            packages = pkgs,
            isTransactable = { true },
            executeShell = { _ -> throw RuntimeException("variant not installed") },
            readback = { grants(dump = true, usage = true, secure = true) },
        )
        val completed = result as AdvancedPermissionsScript.GrantResult.Completed
        assertThat(completed.allGranted).isTrue()
    }

    // ── 3. Guard permits every grant command we issue (no widening needed) ────

    @Test
    fun guardPermitsEveryGrantCommandTheEngineIssues() = runTest {
        val issued = mutableListOf<String>()
        script.grantViaExecutor(
            packages = pkgs,
            isTransactable = { true },
            executeShell = { cmd -> issued.add(cmd); 0 to "" },
            readback = { grants() },
        )
        // Each issued command must pass the unbypassable chokepoint guard.
        for (cmd in issued) {
            assertThat(PServerCommandGuard.inspect(cmd))
                .isInstanceOf(PServerCommandGuard.Verdict.Allow::class.java)
        }
    }

    @Test
    fun guardAllowsTheThreeKnownGrantsForOurPackage() {
        val cmds = listOf(
            "pm grant io.github.mayusi.calibratesoc android.permission.DUMP",
            "pm grant io.github.mayusi.calibratesoc android.permission.PACKAGE_USAGE_STATS",
            "pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS",
            "pm grant io.github.mayusi.calibratesoc.debug android.permission.DUMP",
            "pm grant io.github.mayusi.calibratesoc.debug android.permission.PACKAGE_USAGE_STATS",
            "pm grant io.github.mayusi.calibratesoc.debug android.permission.WRITE_SECURE_SETTINGS",
        )
        for (cmd in cmds) {
            assertThat(PServerCommandGuard.inspect(cmd))
                .isInstanceOf(PServerCommandGuard.Verdict.Allow::class.java)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FULL one-trust setup ([grantAllViaExecutor]) — the SUPERSET engine.
    //  Same honesty invariants as the 3-perm engine, extended to the four
    //  special-access toggles (Usage Access, Overlay, Battery, Notifications).
    // ══════════════════════════════════════════════════════════════════════════

    private fun fullHeld(
        rootPerms: Boolean = false,
        usage: Boolean = false,
        overlay: Boolean = false,
        battery: Boolean = false,
        notifications: Boolean = false,
    ) = mapOf(
        AdvancedPermissionsScript.SetupItem.ROOT_PERMS to rootPerms,
        AdvancedPermissionsScript.SetupItem.USAGE_ACCESS to usage,
        AdvancedPermissionsScript.SetupItem.OVERLAY to overlay,
        AdvancedPermissionsScript.SetupItem.BATTERY to battery,
        AdvancedPermissionsScript.SetupItem.NOTIFICATIONS to notifications,
    )

    /** Every command [grantAllViaExecutor] issues for one package, SDK 33+. */
    private fun expectedFullCommandsFor(pkg: String): List<String> = listOf(
        "pm grant $pkg android.permission.DUMP",
        "pm grant $pkg android.permission.PACKAGE_USAGE_STATS",
        "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
        "pm grant $pkg android.permission.POST_NOTIFICATIONS",
        "appops set $pkg android:get_usage_stats allow",
        "appops set $pkg android:system_alert_window allow",
        "dumpsys deviceidle whitelist +$pkg",
    )

    @Test
    fun grantAllViaExecutor_sdk33_issuesAll7CommandsPerPackage() = runTest {
        val issued = mutableListOf<String>()
        val result = script.grantAllViaExecutor(
            packages = pkgs,
            attemptNotifications = true,
            isTransactable = { true },
            executeShell = { cmd -> issued.add(cmd); 0 to "" },
            readback = { fullHeld(rootPerms = true, usage = true, overlay = true, battery = true, notifications = true) },
        )

        // 7 commands per package (4 pm-grants incl. POST_NOTIFICATIONS, 2 appops, 1 deviceidle).
        val expected = pkgs.flatMap { expectedFullCommandsFor(it) }
        assertThat(issued).containsExactlyElementsIn(expected)
        assertThat(issued).hasSize(pkgs.size * 7)

        val completed = result as AdvancedPermissionsScript.FullSetupResult.Completed
        assertThat(completed.allGranted).isTrue()
        assertThat(completed.issued).containsExactlyElementsIn(expected)
    }

    @Test
    fun grantAllViaExecutor_sdkBelow33_skipsPostNotificationsGracefully() = runTest {
        val issued = mutableListOf<String>()
        script.grantAllViaExecutor(
            packages = listOf("io.github.mayusi.calibratesoc"),
            attemptNotifications = false,
            isTransactable = { true },
            executeShell = { cmd -> issued.add(cmd); 0 to "" },
            // On SDK < 33 notifications are on by default → readback reports held.
            readback = { fullHeld(rootPerms = true, usage = true, overlay = true, battery = true, notifications = true) },
        )
        // POST_NOTIFICATIONS must NOT be issued; the other 6 commands are.
        assertThat(issued).doesNotContain(
            "pm grant io.github.mayusi.calibratesoc android.permission.POST_NOTIFICATIONS",
        )
        assertThat(issued).containsExactly(
            "pm grant io.github.mayusi.calibratesoc android.permission.DUMP",
            "pm grant io.github.mayusi.calibratesoc android.permission.PACKAGE_USAGE_STATS",
            "pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS",
            "appops set io.github.mayusi.calibratesoc android:get_usage_stats allow",
            "appops set io.github.mayusi.calibratesoc android:system_alert_window allow",
            "dumpsys deviceidle whitelist +io.github.mayusi.calibratesoc",
        )
    }

    @Test
    fun grantAllViaExecutor_reportsPerItemHeldOffReadback_notWhatWasSent() = runTest {
        // We SEND every command, but the readback says only ROOT_PERMS + OVERLAY
        // landed (e.g. appops get_usage_stats silently failed). The result MUST be
        // honest per item — never claim Usage/Battery/Notifications we couldn't confirm.
        val result = script.grantAllViaExecutor(
            packages = pkgs,
            attemptNotifications = true,
            isTransactable = { true },
            executeShell = { _ -> 0 to "" },
            readback = { fullHeld(rootPerms = true, usage = false, overlay = true, battery = false, notifications = false) },
        )
        val completed = result as AdvancedPermissionsScript.FullSetupResult.Completed
        assertThat(completed.held[AdvancedPermissionsScript.SetupItem.ROOT_PERMS]).isTrue()
        assertThat(completed.held[AdvancedPermissionsScript.SetupItem.OVERLAY]).isTrue()
        assertThat(completed.held[AdvancedPermissionsScript.SetupItem.USAGE_ACCESS]).isFalse()
        assertThat(completed.held[AdvancedPermissionsScript.SetupItem.BATTERY]).isFalse()
        assertThat(completed.held[AdvancedPermissionsScript.SetupItem.NOTIFICATIONS]).isFalse()
        assertThat(completed.allGranted).isFalse()
    }

    @Test
    fun grantAllViaExecutor_pserverNotTransactable_issuesNothing_returnsNotAvailable() = runTest {
        var executorCalled = false
        var readbackCalled = false
        val result = script.grantAllViaExecutor(
            packages = pkgs,
            attemptNotifications = true,
            isTransactable = { false },
            executeShell = { _ -> executorCalled = true; 0 to "" },
            readback = { readbackCalled = true; fullHeld(rootPerms = true) },
        )
        // No commands, no readback — and definitely no fabricated success.
        assertThat(executorCalled).isFalse()
        assertThat(readbackCalled).isFalse()
        assertThat(result).isSameInstanceAs(AdvancedPermissionsScript.FullSetupResult.NotAvailable)
    }

    @Test
    fun grantAllViaExecutor_executorFailureDoesNotCrash_stillReportsReadback() = runTest {
        // A sibling-variant command that throws (variant not installed) must not abort
        // the run; the readback remains the source of truth.
        val result = script.grantAllViaExecutor(
            packages = pkgs,
            attemptNotifications = true,
            isTransactable = { true },
            executeShell = { _ -> throw RuntimeException("variant not installed") },
            readback = { fullHeld(rootPerms = true, usage = true, overlay = true, battery = true, notifications = true) },
        )
        val completed = result as AdvancedPermissionsScript.FullSetupResult.Completed
        assertThat(completed.allGranted).isTrue()
    }

    @Test
    fun guardPermitsEveryFullSetupCommandTheEngineIssues() = runTest {
        val issued = mutableListOf<String>()
        script.grantAllViaExecutor(
            packages = pkgs,
            attemptNotifications = true,
            isTransactable = { true },
            executeShell = { cmd -> issued.add(cmd); 0 to "" },
            readback = { fullHeld() },
        )
        // Every issued command — pm grant (incl POST_NOTIFICATIONS), appops set,
        // deviceidle whitelist — must pass the unbypassable chokepoint guard.
        for (cmd in issued) {
            assertThat(PServerCommandGuard.inspect(cmd))
                .isInstanceOf(PServerCommandGuard.Verdict.Allow::class.java)
        }
    }
}
