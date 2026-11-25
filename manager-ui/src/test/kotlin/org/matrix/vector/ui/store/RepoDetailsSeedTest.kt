package org.matrix.vector.ui.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The module page shows the fetched record only when it names the release the Store list does. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepoDetailsSeedTest {
    private val name = "com.example.module"

    private fun module(latest: String?, releases: List<String>?) =
        OnlineModule(
            name = name,
            description = null,
            summary = null,
            url = null,
            homepageUrl = null,
            sourceUrl = null,
            readmeHTML = null,
            latestRelease = latest,
            releases =
                releases?.map { tag ->
                    Release(
                        id = tag,
                        name = tag,
                        tagName = tag,
                        url = null,
                        descriptionHTML = null,
                        publishedAt = null,
                        releaseAssets =
                            listOf(ReleaseAsset(name = "m-$tag.apk", downloadUrl = "https://example.com/m-$tag.apk")),
                    )
                },
        )

    private class Source(seed: OnlineModule?, private val detail: OnlineModule?) : StoreDataSource {
        override val catalog: StateFlow<StoreCatalog> =
            MutableStateFlow(StoreCatalog(modules = listOfNotNull(seed), loaded = true))
        override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
        override val installedVersions: StateFlow<Map<String, RepoVersion>> = MutableStateFlow(emptyMap())

        override suspend fun refresh(force: Boolean) = Unit

        override suspend fun details(packageName: String): OnlineModule? = detail

        override fun refreshInstalled() = Unit
    }

    private object Settings : StoreSettings {
        override val updateChannel: StateFlow<String> = MutableStateFlow("stable")
        override val mutedUpdates: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val storeInstalls: StateFlow<Map<String, StoreInstall>> = MutableStateFlow(emptyMap())

        override fun setUpdateChannel(token: String) = Unit

        override fun setUpdatesMuted(packageName: String, muted: Boolean) = Unit
    }

    /** The release tags the page lists, with [seed] in the catalogue and [detail] fetched. */
    private fun shown(seed: OnlineModule?, detail: OnlineModule?): List<String?>? {
        val scope = MainScope()
        val viewModel = RepoDetailsViewModel(name, Source(seed, detail), Settings, null, scope)
        val collecting = scope.launch { viewModel.state.collect {} }
        val latest = viewModel.state.value.module?.releases?.map { it.tagName }
        collecting.cancel()
        return latest
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aDetailBehindTheCorrectedEntryIsNotShown() {
        // The site corrected the entry to 50 (no releases yet); the mirror's record still says 49.
        assertEquals(null, shown(module("50-2.0", null), module("49-1.9", listOf("49-1.9"))))
    }

    @Test
    fun aDetailOfTheSameReleaseIsShown() {
        val detail = module("50-2.0", listOf("50-2.0", "49-1.9"))
        assertEquals(listOf("50-2.0", "49-1.9"), shown(module("50-2.0", listOf("50-2.0")), detail))
    }

    @Test
    fun withoutAnEntryTheDetailIsShown() {
        assertEquals(listOf("49-1.9"), shown(null, module("49-1.9", listOf("49-1.9"))))
    }

    @Test
    fun aDetailAheadOfAStaleEntryIsNotShownEither() {
        // StoreRepository.complete's rule: only a record of the very release the entry names.
        assertEquals(listOf("49-1.9"), shown(module("49-1.9", listOf("49-1.9")), module("50-2.0", listOf("50-2.0"))))
    }
}
