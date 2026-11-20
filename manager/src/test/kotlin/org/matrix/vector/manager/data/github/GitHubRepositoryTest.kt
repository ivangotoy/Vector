package org.matrix.vector.manager.data.github

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubRepositoryTest {
    @get:Rule val dir = TemporaryFolder()

    /** Every request the repository made. Nothing here reaches a network. */
    private val asked = CopyOnWriteArrayList<Request>()

    private var head = commits(0 until 100)

    /** Answers the three feed requests from fixtures, and 404 to anything else. */
    private val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    asked += request
                    val url = request.url.toString()
                    val (code, body, link) =
                        when {
                            "/commits?since=" in url -> Triple(200, head, null)
                            url.endsWith("/commits?per_page=1") ->
                                Triple(200, "[]", "<$url&page=250>; rel=\"last\"")
                            else -> Triple(404, "", null)
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("fixture")
                        .apply { if (link != null) header("Link", link) }
                        .body(body.toByteArray().toResponseBody())
                        .build()
                }
            )
            .build()

    private fun repository() = GitHubRepository(client, dir.root) { 6 }

    private fun commits(range: IntRange): String =
        range.joinToString(",", "[", "]") { i ->
            val date = "2026-03-%02dT12:00:00Z".format(1 + i % 28)
            """{"sha":"%040x","commit":{"message":"change $i","author":{"name":"Example","""
                .format(i) +
                """"email":"dev@example.com","date":"$date"}},"author":{"login":"example-dev"}}"""
        }

    @Test
    fun aCachedLoadOnlyAsksTheCache() {
        runBlocking { repository().load(GitHubRepository.Freshness.Cached) }

        assertTrue(asked.isNotEmpty())
        asked.forEach { assertTrue("${it.url} may use the network", it.cacheControl.onlyIfCached) }
    }

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
