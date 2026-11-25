package org.matrix.vector.manager.ui.screens.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.vector.manager.data.github.FrameworkRelease
import org.matrix.vector.manager.data.repository.FrameworkUpdateState

/** Without the daemon the page lays releases out against the manager's own version, on its own. */
class FrameworkUpdatePageStateTest {
    private fun canary(code: Long) =
        FrameworkRelease(
            tag = "canary-$code",
            title = "canary-$code",
            versionCode = code,
            isCanary = true,
            notesMarkdown = null,
            htmlUrl = null,
            epochSeconds = 0,
            commit = null,
            zips = emptyList(),
        )

    private val local =
        FrameworkUpdateState(installedVersionCode = 3300, catalog = listOf(canary(3310)))

    @Test
    fun withoutTheDaemonATappedCanaryIsFound() {
        val page = pageState(FrameworkUpdateState(), local)
        assertTrue(usesFallback(FrameworkUpdateState(), local))
        assertEquals(3310L, page.catalog.firstOrNull { it.versionCode == 3310L }?.versionCode)
    }

    @Test
    fun aStateTheDaemonPublishedWins() {
        val shared =
            FrameworkUpdateState(installedVersionCode = 3305, catalog = listOf(canary(3310)))
        assertSame(shared, pageState(shared, local))
        assertFalse(usesFallback(shared, local))
    }

    @Test
    fun withoutAFallbackTheSharedStateIsShown() {
        val shared = FrameworkUpdateState()
        assertSame(shared, pageState(shared, null))
        assertFalse(usesFallback(shared, null))
    }
}
