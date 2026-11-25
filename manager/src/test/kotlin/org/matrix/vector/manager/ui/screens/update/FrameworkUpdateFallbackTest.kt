package org.matrix.vector.manager.ui.screens.update

import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.manager.data.github.GitHubRepository
import org.matrix.vector.manager.data.repository.FlashStep
import org.matrix.vector.manager.data.repository.FrameworkInstaller
import org.matrix.vector.manager.data.repository.FrameworkUpdateRepository
import org.matrix.vector.manager.ipc.DaemonClient
import org.matrix.vector.manager.ui.screens.canary.CanaryViewModel

/**
 * The update page and the canary list, built as the app builds them but with a daemon that does not
 * answer: the pages lay the releases out against the manager's own version, nothing can be flashed
 * against that, and a state the daemon publishes later wins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameworkUpdateFallbackTest {
    @get:Rule val dir = TemporaryFolder()

    private val releases =
        """[{"id":1,"tag_name":"v2.0","name":"v2.0","prerelease":false,""" +
            """"target_commitish":"master","published_at":"2026-03-01T12:00:00Z",""" +
            """"assets":[{"id":11,"name":"Vector-v2.0-2000-Release.zip","size":1,""" +
            """"browser_download_url":"https://example.com/Vector-v2.0-2000-Release.zip",""" +
            """"digest":"sha256:${"0".repeat(64)}"}]}]"""

    /** The release list (both addresses: build() points it at the fork's feed), 404 otherwise. */
    private val client =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val url = request.url.toString()
                    val found =
                        "/releases?per_page=" in url || url.endsWith("/.forgejo/releases.json")
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

    /** A daemon client whose binder never arrives: every call fails. */
    private val daemon = DaemonClient(MutableStateFlow<IManagerService?>(null))
    private val github by lazy { GitHubRepository(client, dir.root) }
    private val updates by lazy { FrameworkUpdateRepository(github) }
    private val installer by lazy { FrameworkInstaller(ContextWrapper(null), client, daemon) }

    private fun page() =
        FrameworkUpdateViewModel(
            daemon = daemon,
            updates = updates,
            installer = installer,
            updateVariant = MutableStateFlow("release"),
            setUpdateVariant = {},
            ownVersionCode = 1_000,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun withoutTheDaemonThePageLaysReleasesOutAgainstTheManager() {
        val viewModel = page()
        val laidOut = runBlocking {
            withTimeout(10_000) { viewModel.update.first { it.installedVersionCode > 0 } }
        }
        assertEquals(1_000L, laidOut.installedVersionCode)
        assertEquals(2_000L, laidOut.available?.versionCode)
        assertEquals(0L, updates.state.value.installedVersionCode)
    }

    @Test
    fun nothingIsFlashedAgainstTheFallback() {
        val viewModel = page()
        runBlocking {
            withTimeout(10_000) {
                viewModel.onFallback.first { it }
                viewModel.chosenZip.first { it?.downloadUrl != null }
            }
        }

        viewModel.flash()

        assertEquals(FlashStep.Idle, installer.state.value)
    }

    @Test
    fun theCanaryListTakesTheDaemonStateOnceItIsPublished() {
        val viewModel =
            CanaryViewModel(
                daemon = daemon,
                github = github,
                updates = updates,
                ownVersionCode = 1_000,
            )
        runBlocking {
            withTimeout(10_000) { viewModel.update.first { it.installedVersionCode == 1_000L } }
            // The daemon answers later; the home screen publishes its state.
            updates.refresh(1_500)
            withTimeout(10_000) { viewModel.update.first { it.installedVersionCode == 1_500L } }
        }
    }
}
