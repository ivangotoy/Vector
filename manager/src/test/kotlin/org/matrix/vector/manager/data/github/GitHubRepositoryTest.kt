package org.matrix.vector.manager.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GitHubRepositoryTest {

    @Test
    fun theFeedWindowIsTheSameAllDaySoItsUrlCanBeCached() {
        // 2026-03-14T00:00:00Z, then the same day at 23:59:59.
        val midnight = 1_773_446_400L
        val morning = GitHubRepository.windowStart(midnight + 1, 6)
        val night = GitHubRepository.windowStart(midnight + 86_399, 6)

        assertEquals(morning, night)
        assertEquals(0L, morning % 86_400)
        assertEquals(midnight - 180 * 86_400L, morning)
        assertNotEquals(morning, GitHubRepository.windowStart(midnight + 86_400, 6))
    }

    @Test
    fun noWindowMeansFromTheStart() {
        assertEquals(0L, GitHubRepository.windowStart(1_773_446_400L, 0))
    }
}
