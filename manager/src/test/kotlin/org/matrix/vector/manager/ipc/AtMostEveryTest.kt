package org.matrix.vector.manager.ipc

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtMostEveryTest {

    @Test
    fun aFloodIsFoldedAndItsFirstAndLastValuesAreKept() {
        val flood = flow {
            emit(0)
            delay(50)
            for (i in 1..1_000) emit(i)
        }

        val seen = runBlocking { flood.atMostEvery(200).toList() }

        assertEquals(0, seen.first())
        assertEquals(1_000, seen.last())
        assertTrue("${seen.size} of 1001 handled", seen.size <= 10)
    }
}
