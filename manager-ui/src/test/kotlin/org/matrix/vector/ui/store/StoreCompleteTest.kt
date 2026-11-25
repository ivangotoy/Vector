package org.matrix.vector.ui.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An installed module whose list entry carries no releases (the site corrected it) is given them
 * from its detail record, also when that record is ahead of the entry.
 */
class StoreCompleteTest {
    private val name = "com.example.module"

    private val page =
        """{"pageCount":1,"modules":[{"name":"$name","latestRelease":"50-2.0",""" +
            """"latestReleaseTime":"2026-03-02T00:00:00Z"}]}"""

    // Ahead of the entry: the module released again between the page and the record being read.
    private val detail =
        """{"name":"$name","latestRelease":"51-2.1","latestReleaseTime":"2026-03-05T00:00:00Z",""" +
            """"releases":[{"name":"51-2.1","tagName":"51-2.1","releaseAssets":[""" +
            """{"name":"m-51.apk","downloadUrl":"https://example.com/m-51.apk"}]}]}"""

    /** No mirror list; one site page; the detail record. Nothing reaches a network. */
    private val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    val body =
                        when {
                            url.endsWith("/module-list/1.json") -> page
                            url.endsWith("/module/$name.json") -> detail
                            else -> null
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (body != null) 200 else 404)
                        .message("fixture")
                        .body((body ?: "").toByteArray().toResponseBody())
                        .build()
                }
            )
            .build()

    @Test
    fun anInstalledModuleTakesReleasesFromARecordAheadOfItsEntry() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository =
                StoreRepository(client, scope, { _, _, _ -> }) { mapOf(name to RepoVersion(49, "1.9")) }
            val tags = runBlocking {
                repository.refreshInstalled()
                repository.refresh(force = false)
                withTimeout(10_000) {
                    repository.catalog
                        .first { catalog -> catalog.modules.any { !it.releases.isNullOrEmpty() } }
                        .modules
                        .single()
                        .releases
                        ?.map { it.tagName }
                }
            }
            assertEquals(listOf("51-2.1"), tags)
        } finally {
            scope.cancel()
        }
    }
}
