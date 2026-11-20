package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.vector.manager.data.github.GitHubRepository

class FrameworkUpdateRepositoryTest {
    @get:Rule val dir = TemporaryFolder()

    private val releases =
        """[{"id":1,"tag_name":"v2.0","name":"v2.0","prerelease":false,""" +
            """"target_commitish":"master","published_at":"2026-03-01T12:00:00Z",""" +
            """"assets":[{"id":11,"name":"Vector-v2.0-2000-Release.zip","size":1,""" +
            """"browser_download_url":"https://example.com/Vector-v2.0-2000-Release.zip",""" +
            """"digest":"sha256:${"0".repeat(64)}"}]}]"""

    /** Answers the release list from the fixture above and 404 to anything else. No network. */
    private val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val found = "/releases?per_page=" in request.url.toString()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (found) 200 else 404)
                        .message("fixture")
                        .body((if (found) releases else "").toByteArray().toResponseBody())
                        .build()
                }
            )
            .build()

    @Test
    fun anEvaluationIsNotPublishedAsTheInstalledFramework() {
        val updates = FrameworkUpdateRepository(GitHubRepository(client, dir.root))

        val laidOut = runBlocking { updates.evaluate(1_000) }

        assertEquals(1_000L, laidOut?.installedVersionCode)
        assertEquals(2_000L, laidOut?.available?.versionCode)
        assertEquals(0L, updates.state.value.installedVersionCode)
    }

    @Test
    fun aRefreshIsPublished() {
        val updates = FrameworkUpdateRepository(GitHubRepository(client, dir.root))

        runBlocking { updates.refresh(1_000) }

        assertEquals(1_000L, updates.state.value.installedVersionCode)
        assertEquals(2_000L, updates.state.value.available?.versionCode)
    }
}
