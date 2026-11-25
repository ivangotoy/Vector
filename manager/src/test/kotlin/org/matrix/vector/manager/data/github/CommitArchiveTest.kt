package org.matrix.vector.manager.data.github

import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
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

    @Test
    fun aFailedCompactionIsTriedAgain() {
        val unique = (0 until 10).map(::commit)
        val archive = archiveOf(unique + unique + unique)
        val file = dir.root.listFiles()!!.first { it.readLines().size == 30 }
        // The temporary exists and the directory is read-only: the write succeeds, the rename not.
        File(file.parentFile, file.name + ".tmp").createNewFile()
        dir.root.setWritable(false)
        try {
            // Root ignores the mode, and then nothing fails; the test needs an ordinary user.
            assumeFalse(dir.root.canWrite())
            archive.compactIfWasteful()
        } finally {
            dir.root.setWritable(true)
        }
        assertEquals(30, file.readLines().size)

        archive.compactIfWasteful()

        assertEquals(10, file.readLines().size)
    }
}
