package app.archivepocket.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OperationManagerTest {
    private fun manager(starts: AtomicInteger = AtomicInteger(), cleanup: () -> String? = { null }) =
        OperationManager(onStart = { starts.incrementAndGet() }, startupCleanup = cleanup)

    private fun OperationManager.await(condition: (OperationState) -> Boolean): OperationState =
        runBlocking { withTimeout(10_000) { state.first(condition) } }

    @Test fun secondOperationIsRejectedWhileOneRuns() {
        val starts = AtomicInteger()
        val manager = manager(starts)
        val release = CountDownLatch(1)
        val first = manager.start("Extract archive") { assertTrue(release.await(10, TimeUnit.SECONDS)) }
        assertNotNull(first)
        assertTrue(manager.state.value.running)
        assertNull(manager.start("Copy") { throw AssertionError("must not run") })
        release.countDown()
        val finished = manager.await { !it.running }
        assertEquals(first, finished.finished)
        val outcome = manager.takeOutcome()!!
        assertEquals("Extract archive", outcome.label)
        assertNull(outcome.error)
        assertNull(manager.takeOutcome())
        assertNotNull(manager.start("Copy") { })
        manager.await { !it.running && it.outcomes.isNotEmpty() }
        assertEquals(2, starts.get())
    }

    @Test fun cancelAnswersPendingQuestionAndStopsOperation() {
        val manager = manager()
        val replaced = AtomicBoolean(true)
        manager.start("Copy") { control ->
            replaced.set(manager.confirm("photo.jpg"))
            control.check()
        }
        manager.await { it.collision == "photo.jpg" }
        manager.cancel()
        val finished = manager.await { !it.running }
        assertNull(finished.collision)
        assertFalse(finished.cancelling)
        assertFalse(replaced.get())
        assertTrue(manager.takeOutcome()!!.error is PocketError)
    }

    @Test fun answerFromAnyScreenReachesTheOperation() {
        val manager = manager()
        val replaced = AtomicBoolean(false)
        manager.start("Move") { replaced.set(manager.confirm("notes")) }
        manager.await { it.collision == "notes" }
        manager.answerCollision(true)
        manager.await { !it.running }
        assertTrue(replaced.get())
        assertNull(manager.takeOutcome()!!.error)
    }

    @Test fun startupCleanupFinishesBeforeTheFirstOperation() {
        val release = CountDownLatch(1)
        val cleaned = AtomicBoolean(false)
        val manager = manager { assertTrue(release.await(10, TimeUnit.SECONDS)); cleaned.set(true); null }
        val sawCleanup = AtomicBoolean(false)
        manager.start("Delete") { sawCleanup.set(cleaned.get()) }
        release.countDown()
        manager.await { !it.running }
        assertTrue(sawCleanup.get())
    }

    @Test fun startupReportIsShownOnce() {
        val manager = manager { "Extracting \u201cphotos.zip\u201d did not finish." }
        manager.await { it.outcomes.isNotEmpty() }
        val outcome = manager.takeOutcome()!!
        assertNull(outcome.error)
        assertEquals("Extracting \u201cphotos.zip\u201d did not finish.", outcome.note)
        assertNull(manager.takeOutcome())
    }

    @Test fun failedStartupCleanupIsReported() {
        val manager = manager { throw PocketError("Cannot clean previous private temporary files.") }
        val reported = manager.await { it.outcomes.isNotEmpty() }
        assertFalse(reported.running)
        assertEquals("Cannot clean previous private temporary files.", manager.takeOutcome()!!.error?.message)
    }

    @Test fun quietOperationIsCancellableAndMarksItsResult() {
        val manager = manager()
        val copying = CountDownLatch(1)
        manager.start("Open archive", expectedBytes = { 4096L }, quiet = true) { control ->
            copying.countDown()
            while (true) { control.check(); Thread.sleep(5) }
        }
        assertTrue(copying.await(10, TimeUnit.SECONDS))
        assertEquals(4096L, manager.await { it.total > 0 }.total)
        manager.cancel()
        manager.await { !it.running }
        val outcome = manager.takeOutcome()!!
        assertTrue(outcome.quiet)
        assertTrue(outcome.error is PocketError)
        manager.start("Copy") { }
        manager.await { !it.running && it.outcomes.isNotEmpty() }
        assertFalse(manager.takeOutcome()!!.quiet)
    }
}
