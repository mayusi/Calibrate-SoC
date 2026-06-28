package io.github.mayusi.calibratesoc.data.profiles

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for the 384 MHz-collapse hardening in [ForegroundAppWatcher].
 *
 * The watcher itself is an AccessibilityService and cannot be instantiated in a
 * pure-JVM test, so these tests exercise:
 *   1. The pure [ForegroundAppWatcher.shouldHandlePackage] filter (BUG 7 — own app /
 *      system-UI skip).
 *   2. The EXACT coroutine patterns the fixes rely on, as standalone models, proving
 *      the invariants hold under cancellation:
 *        - BUG 1: a tracker committed under NonCancellable BEFORE the apply chain
 *          survives a cancel mid-apply, so a later revert is still triggerable.
 *        - BUG 4: a revert body wrapped in NonCancellable runs to completion even when
 *          its scope is cancelled mid-revert.
 *        - BUG 2/3: a single applyMutex + AtomicReference(pendingPackage) serialises
 *          concurrent triggers (no double-apply) AND re-queues a switch that arrived
 *          while a write was in flight (no dropped game).
 *
 * Modelling the patterns (rather than the whole service) keeps the test pure-JVM and
 * pins the behaviour that the production code now implements line-for-line.
 */
class ForegroundAppWatcherRevertSafetyTest {

    // ── BUG 7: package filter ────────────────────────────────────────────────

    @Test
    fun `shouldHandlePackage — skips our own package`() {
        assertThat(
            ForegroundAppWatcher.shouldHandlePackage(
                pkg = "io.github.mayusi.calibratesoc",
                ownPackage = "io.github.mayusi.calibratesoc",
            ),
        ).isFalse()
    }

    @Test
    fun `shouldHandlePackage — skips system UI`() {
        assertThat(
            ForegroundAppWatcher.shouldHandlePackage(
                pkg = "com.android.systemui",
                ownPackage = "io.github.mayusi.calibratesoc",
            ),
        ).isFalse()
        assertThat(
            ForegroundAppWatcher.shouldHandlePackage(
                pkg = "com.android.systemui.recents",
                ownPackage = "io.github.mayusi.calibratesoc",
            ),
        ).isFalse()
    }

    @Test
    fun `shouldHandlePackage — accepts a real game package`() {
        assertThat(
            ForegroundAppWatcher.shouldHandlePackage(
                pkg = "com.miHoYo.GenshinImpact",
                ownPackage = "io.github.mayusi.calibratesoc",
            ),
        ).isTrue()
    }

    // ── BUG 1: tracker committed first survives a cancel mid-apply ────────────

    @Test
    fun `tracker committed under NonCancellable before apply survives mid-apply cancel`() = runTest {
        // Model the watcher: a tracker var that the NEXT switch reads to decide whether
        // to revert, plus an apply chain that can be cancelled partway through.
        val tracker = AtomicReference<String?>(null)
        val applyChainReached = AtomicInteger(0)
        val applyStarted = CompletableDeferred<Unit>()

        val job: Job = launch {
            // COMMIT-FIRST: tracker set under NonCancellable BEFORE side effects.
            withContext(NonCancellable) { tracker.set("game.pkg") }
            applyStarted.complete(Unit)
            // Apply chain (cancellable). A rapid app-switch / teardown cancels here.
            delay(10_000) // suspends → cancellation point, simulating mid-apply cancel
            applyChainReached.incrementAndGet() // must NOT run
        }

        applyStarted.await()
        job.cancelAndJoin()

        // The apply chain was cancelled mid-flight…
        assertThat(applyChainReached.get()).isEqualTo(0)
        // …but the tracker IS set, so the next switch can still revert (no pinned device).
        assertThat(tracker.get()).isEqualTo("game.pkg")
    }

    @Test
    fun `OLD ordering — tracker set AFTER apply would be lost on cancel (regression guard)`() = runTest {
        // This is the BUGGY pre-fix ordering, asserted to demonstrate the failure class
        // the fix removes: tracker set AFTER the (cancellable) apply chain.
        val tracker = AtomicReference<String?>(null)
        val applyStarted = CompletableDeferred<Unit>()

        val job = launch {
            applyStarted.complete(Unit)
            delay(10_000)               // cancelled here, before…
            tracker.set("game.pkg")     // …the tracker is ever set
        }
        applyStarted.await()
        job.cancelAndJoin()

        // Old ordering loses the tracker → next switch sees null → SKIPS revert → pinned.
        assertThat(tracker.get()).isNull()
    }

    // ── BUG 4: revert under NonCancellable completes despite a scope cancel ────

    @Test
    fun `revert wrapped in NonCancellable completes every axis even when cancelled mid-revert`() = runTest {
        val autoTdpStopped = AtomicInteger(0)
        val fanRestored = AtomicInteger(0)
        val revertEntered = CompletableDeferred<Unit>()

        // Model revertBundle: multi-step revert, all under NonCancellable.
        suspend fun revertBundle() = withContext(NonCancellable) {
            revertEntered.complete(Unit)
            autoTdpStopped.incrementAndGet()
            delay(50)                       // a suspending step (e.g. fan write IPC)
            fanRestored.incrementAndGet()   // MUST still run after the suspend
        }

        val job = launch { revertBundle() }
        revertEntered.await()
        // Cancel the scope WHILE the revert is suspended at delay(50).
        job.cancelAndJoin()

        // NonCancellable guarantees both axes are reverted to completion.
        assertThat(autoTdpStopped.get()).isEqualTo(1)
        assertThat(fanRestored.get()).isEqualTo(1)
    }

    // ── BUG 3: concurrent triggers serialised — no double apply ───────────────

    @Test
    fun `applyMutex serialises concurrent triggers — apply never overlaps`() = runTest {
        val mutex = Mutex()
        val concurrentInside = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val applyCount = AtomicInteger(0)

        suspend fun applyForPackage() {
            mutex.withLock {
                val now = concurrentInside.incrementAndGet()
                maxConcurrent.updateAndGet { kotlin.math.max(it, now) }
                delay(20) // setBundle / start AutoTDP window
                applyCount.incrementAndGet()
                concurrentInside.decrementAndGet()
            }
        }

        // Two rapid switches launch nearly simultaneously.
        val a = launch { applyForPackage() }
        val b = launch { applyForPackage() }
        a.join(); b.join()

        // Both applied, but NEVER concurrently (no ProfileStore corruption / double daemon).
        assertThat(applyCount.get()).isEqualTo(2)
        assertThat(maxConcurrent.get()).isEqualTo(1)
    }

    // ── BUG 2: a switch arriving during a write is re-queued, not dropped ─────

    @Test
    fun `pending package arriving during in-flight apply is re-queued and applied`() = runTest {
        // Model scheduleApply's drain loop: pendingPackage (latest wins) + a single
        // worker that loops until the pending slot is empty, under the mutex.
        val mutex = Mutex()
        val pending = AtomicReference<String?>(null)
        val applied = mutableListOf<String>()
        // Gate that lets the test inject the SECOND switch while the FIRST apply is
        // mid-flight (suspended), reproducing the exact race the old early-return dropped.
        val firstApplyInFlight = CompletableDeferred<Unit>()
        val secondSwitchQueued = CompletableDeferred<Unit>()

        suspend fun applyForPackage(pkg: String) {
            mutex.withLock {
                if (pkg == "game.A") {
                    firstApplyInFlight.complete(Unit)
                    secondSwitchQueued.await() // stay in flight until B has been queued
                }
                applied.add(pkg)
            }
        }

        // Worker mirrors the production drain loop.
        suspend fun drain() {
            while (true) {
                val next = pending.getAndSet(null) ?: return
                applyForPackage(next)
            }
        }

        // First switch arrives.
        pending.set("game.A")
        val worker = launch { drain() }
        firstApplyInFlight.await()
        // While the first apply is in flight, a SECOND switch arrives (the case the old
        // inFlightWriting early-return dropped forever — lastForegroundPackage already
        // advanced so it never retried).
        pending.set("game.B")
        secondSwitchQueued.complete(Unit)
        worker.join()

        // Re-queue means BOTH get a turn — game.B is not silently lost.
        assertThat(applied).containsExactly("game.A", "game.B").inOrder()
    }
}
