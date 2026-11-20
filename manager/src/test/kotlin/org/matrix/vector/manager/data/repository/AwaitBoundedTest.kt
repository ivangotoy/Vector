package org.matrix.vector.manager.data.repository

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call that blocks its thread, the way a Binder transaction does, must not hold the wait. */
class AwaitBoundedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val late = CopyOnWriteArrayList<String>()
    private val delivered = CountDownLatch(1)

    private fun keepLate(value: String) {
        late += value
        delivered.countDown()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun aBlockedCallTimesOutAndItsLateAnswerGoesToOnLate() {
        val release = CountDownLatch(1)
        // Only so that a wait which cannot time out ends this test rather than hanging the build.
        thread(isDaemon = true) {
            Thread.sleep(10_000)
            release.countDown()
        }

        val answer = runBlocking {
            awaitBounded(100, scope, ::keepLate) {
                release.await()
                "descriptor"
            }
        }

        assertNull(answer)
        assertEquals("the wait ended while the call was still blocked", 1L, release.count)
        assertTrue("nothing reaches onLate before the call ends", late.isEmpty())

        release.countDown()
        assertTrue(delivered.await(30, TimeUnit.SECONDS))
        assertEquals(listOf("descriptor"), late)
    }

    @Test
    fun anAnswerInTimeIsReturnedAndNotHandedToOnLate() {
        val answer = runBlocking { awaitBounded(60_000, scope, ::keepLate) { "descriptor" } }

        assertEquals("descriptor", answer)
        assertTrue(late.isEmpty())
    }

    @Test
    fun aCallThatAnswersNothingGivesNullAndNothingLate() {
        val answer = runBlocking { awaitBounded<String>(60_000, scope, ::keepLate) { null } }

        assertNull(answer)
        Thread.sleep(100)
        assertTrue(late.isEmpty())
    }
}
