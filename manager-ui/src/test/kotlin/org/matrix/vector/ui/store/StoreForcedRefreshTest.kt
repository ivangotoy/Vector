package org.matrix.vector.ui.store

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A forced refresh that finds another one running waits for it, so the module page's retry
 * compares against the refreshed list rather than the one the running refresh is replacing. When
 * the one it waited for was not forced, it then makes its own forced request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreForcedRefreshTest {
    private val name = "com.example.module"

    private fun page(latest: String, time: String) =
        """{"pageCount":1,"modules":[{"name":"$name","latestRelease":"$latest",""" +
            """"latestReleaseTime":"$time"}]}"""

    @Volatile private var pageBody = page("50-2.0", "2026-03-05T00:00:00Z")

    // The mirror's record, trailing the entry the first list names.
    private val detail =
        """{"name":"$name","latestRelease":"49-1.9","latestReleaseTime":"2026-03-01T00:00:00Z",""" +
            """"releases":[{"name":"49-1.9","tagName":"49-1.9","releaseAssets":[""" +
            """{"name":"m-49.apk","downloadUrl":"https://example.com/m-49.apk"}]}]}"""

    /** Set: the next page request signals [entered] and is held until [release]. */
    @Volatile private var hold = false
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    /** For each request of the site's page, whether it bypassed the cache (a forced refresh). */
    private val pageNoCache = CopyOnWriteArrayList<Boolean>()

    /** What the list said each time the record was asked for. */
    private val listAtDetail = CopyOnWriteArrayList<String?>()
    private lateinit var repository: StoreRepository

    private val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    val body =
                        when {
                            url.endsWith("/module-list/1.json") -> {
                                pageNoCache += request.cacheControl.noCache
                                if (hold) {
                                    hold = false
                                    entered.countDown()
                                    try {
                                        release.await(10, TimeUnit.SECONDS)
                                    } finally {
                                        release.countDown()
                                    }
                                }
                                pageBody
                            }
                            url.endsWith("/module/$name.json") -> {
                                listAtDetail +=
                                    repository.catalog.value.modules.firstOrNull()?.latestRelease
                                detail
                            }
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = StoreRepository(client, scope, { _, _, _ -> }) { emptyMap() }
    }

    @After
    fun tearDown() {
        release.countDown()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun aForcedRefreshDuringAnotherReturnsWithItsCatalogue() {
        hold = true
        scope.launch { repository.refresh(force = false) }
        assertEquals(true, entered.await(10, TimeUnit.SECONDS))

        val seen = AtomicReference<String?>("not returned")
        val forced = scope.launch {
            repository.refresh(force = true)
            seen.set(repository.catalog.value.modules.firstOrNull()?.latestRelease)
        }
        // Room for a forced call that does not wait to return before the running one ends.
        Thread.sleep(500)
        release.countDown()
        runBlocking { withTimeout(10_000) { forced.join() } }

        assertEquals("50-2.0", seen.get())
    }

    /**
     * Runs [first] until its page request is held, then [second], and lets both finish. [second]
     * starts undispatched, so it has found the lock taken and is waiting on it before [first] is
     * released.
     */
    private fun overlapping(first: Boolean, second: Boolean) {
        hold = true
        scope.launch { repository.refresh(force = first) }
        assertEquals(true, entered.await(10, TimeUnit.SECONDS))
        val waiting =
            scope.launch(start = CoroutineStart.UNDISPATCHED) { repository.refresh(force = second) }
        release.countDown()
        runBlocking { withTimeout(10_000) { waiting.join() } }
    }

    @Test
    fun aForcedRefreshAfterAnUnforcedOneMakesItsOwnForcedRequest() {
        overlapping(first = false, second = true)

        assertEquals(listOf(false, true), pageNoCache.toList())
    }

    @Test
    fun aForcedRefreshAfterAForcedOneDownloadsNothingMore() {
        overlapping(first = true, second = true)

        assertEquals(listOf(true), pageNoCache.toList())
    }

    private object Settings : StoreSettings {
        override val updateChannel: StateFlow<String> = MutableStateFlow("stable")
        override val mutedUpdates: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val storeInstalls: StateFlow<Map<String, StoreInstall>> =
            MutableStateFlow(emptyMap())

        override fun setUpdateChannel(token: String) = Unit

        override fun setUpdatesMuted(packageName: String, muted: Boolean) = Unit
    }

    @Test
    fun theRetryAsksForTheRecordAfterTheRunningRefreshEnded() {
        runBlocking { repository.refresh(force = false) }
        val pageScope = MainScope()
        val viewModel = RepoDetailsViewModel(name, repository, Settings, null, pageScope)
        pageScope.launch { viewModel.state.collect {} }
        runBlocking { withTimeout(10_000) { viewModel.state.first { it.detailSetAside } } }

        // A pull-to-refresh is running; the site now names the record's release.
        pageBody = page("49-1.9", "2026-03-06T00:00:00Z")
        hold = true
        scope.launch { repository.refresh(force = true) }
        assertEquals(true, entered.await(10, TimeUnit.SECONDS))

        viewModel.retry()
        Thread.sleep(500)
        release.countDown()
        runBlocking { withTimeout(10_000) { while (listAtDetail.size < 2) kotlinx.coroutines.delay(20) } }
        pageScope.cancel()

        assertEquals(listOf("50-2.0", "49-1.9"), listAtDetail.toList())
    }
}
