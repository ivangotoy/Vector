package org.matrix.vector.manager.data.github

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommitArchiveTest {
    @get:Rule val dir = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun commit(i: Int) =
        GhCommit(
            sha = "%040x".format(i),
            commit =
                GhCommitDetail(
                    message = "change $i",
                    author = GhCommitAuthor("Example", "2026-03-01T12:00:00Z", "dev@example.com"),
                ),
        )

    private fun archiveOf(history: List<GhCommit>): CommitArchive {
        val file = dir.newFile()
        file.writeText(history.joinToString("\n", postfix = "\n") { json.encodeToString(it) })
        return CommitArchive(file, dir.newFile(), json)
    }

    /**
     * An append racing a parse must not be lost from what [CommitArchive.read] serves.
     *
     * The writer starts at a point swept from 0.3 to 1.8 parse lengths, round by round, so that
     * some rounds land after the reader has reached the end of the file and before it publishes. A
     * correct archive never fails this; a racy one loses the append in those rounds.
     */
    @Test
    fun anAppendDuringAParseIsNotHiddenByIt() {
        val history = (0 until 3_000).map(::commit)
        // Warmed up, then timed: the sweep below needs to know how long one parse takes.
        repeat(5) { archiveOf(history).read() }
        val parseNanos =
            (0 until 5).minOf {
                val archive = archiveOf(history)
                val started = System.nanoTime()
                archive.read()
                System.nanoTime() - started
            }

        val stale = mutableListOf<Int>()
        repeat(ROUNDS) { round ->
            val archive = archiveOf(history)
            val appended = commit(100_000 + round)
            val start = CyclicBarrier(2)

            val reader = thread {
                start.await()
                archive.read()
            }
            val writer = thread {
                start.await()
                LockSupport.parkNanos(parseNanos * (30 + 150 * round / ROUNDS) / 100)
                archive.append(listOf(appended))
            }
            reader.join()
            writer.join()

            if (archive.read().none { it.sha == appended.sha }) stale += round
        }
        assertEquals("rounds that lost the append", emptyList<Int>(), stale)
    }

    private companion object {
        const val ROUNDS = 100
    }
}
