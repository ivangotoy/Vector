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

/**
 * The module page shows the fetched record unless it names an older release than the Store list's
 * entry; then the entry, with a retry that reads the list again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepoDetailsSeedTest {
    private val name = "com.example.module"

    private fun module(latest: String?, releases: List<String>?, time: String? = null) =
        OnlineModule(
            name = name,
            description = null,
            summary = null,
            url = null,
            homepageUrl = null,
            sourceUrl = null,
            readmeHTML = null,
            latestRelease = latest,
            latestReleaseTime = time,
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
                            listOf(
                                ReleaseAsset(
                                    name = "m-$tag.apk",
                                    downloadUrl = "https://example.com/m-$tag.apk",
                                )
                            ),
                    )
                },
        )

    private class Source(seed: OnlineModule?, private val detail: OnlineModule?) : StoreDataSource {
        val refreshes = mutableListOf<Boolean>()
        var detailCalls = 0

        override val catalog: StateFlow<StoreCatalog> =
            MutableStateFlow(StoreCatalog(modules = listOfNotNull(seed), loaded = true))
        override val isRefreshing: StateFlow<Boolean> = MutableStateFlow(false)
        override val installedVersions: StateFlow<Map<String, RepoVersion>> =
            MutableStateFlow(emptyMap())

        override suspend fun refresh(force: Boolean) {
            refreshes += force
        }

        override suspend fun details(packageName: String): OnlineModule? {
            detailCalls++
            return detail
        }

        override fun refreshInstalled() = Unit
    }

    private object Settings : StoreSettings {
        override val updateChannel: StateFlow<String> = MutableStateFlow("stable")
        override val mutedUpdates: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val storeInstalls: StateFlow<Map<String, StoreInstall>> =
            MutableStateFlow(emptyMap())

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
    fun aDetailAheadOfAStaleEntryIsShown() {
        // The list came from its cache, or from past the early stop of the site walk.
        assertEquals(
            listOf("50-2.0"),
            shown(module("49-1.9", listOf("49-1.9")), module("50-2.0", listOf("50-2.0"))),
        )
    }

    @Test
    fun releaseTimesOrderDifferentTags() {
        val entry = module("50-2.0", listOf("50-2.0"), time = "2026-03-02T00:00:00Z")
        // A later release under a lower code (a republished tag) is ahead; an earlier one trails.
        assertEquals(
            listOf("48-2.1"),
            shown(entry, module("48-2.1", listOf("48-2.1"), time = "2026-03-05T00:00:00Z")),
        )
        assertEquals(
            listOf("50-2.0"),
            shown(entry, module("51-1.9", listOf("51-1.9"), time = "2026-03-01T00:00:00Z")),
        )
    }

    @Test
    fun equalTimesFallBackToTheVersionCode() {
        val time = "2026-03-02T00:00:00Z"
        val entry = module("50-2.0", listOf("50-2.0"), time = time)
        assertEquals(listOf("50-2.0"), shown(entry, module("49-1.9", listOf("49-1.9"), time = time)))
    }

    @Test
    fun aDetailWithoutASummaryIsComparedByItsNewestRelease() {
        val detail = module(null, listOf("50-2.0", "49-1.9"))
        assertEquals(listOf("50-2.0", "49-1.9"), shown(module("50-2.0", null), detail))
        assertEquals(null, shown(module("51-2.1", null), detail))
    }

    @Test
    fun anEntryThatNamesNoReleaseTakesTheDetail() {
        assertEquals(listOf("49-1.9"), shown(module(null, null), module("49-1.9", listOf("49-1.9"))))
    }

    private fun pageWith(seed: OnlineModule?, detail: OnlineModule?): Pair<RepoDetailsViewModel, Source> {
        val source = Source(seed, detail)
        val scope = MainScope()
        val viewModel = RepoDetailsViewModel(name, source, Settings, null, scope)
        scope.launch { viewModel.state.collect {} }
        return viewModel to source
    }

    @Test
    fun aDetailSetAsideIsFlaggedAndItsRetryReadsTheListAgain() {
        val (viewModel, source) = pageWith(module("50-2.0", null), module("49-1.9", listOf("49-1.9")))
        assertEquals(true, viewModel.state.value.detailSetAside)
        assertEquals(DetailFetch.Loaded, viewModel.state.value.fetch)

        viewModel.retry()

        assertEquals(listOf(true), source.refreshes)
        assertEquals(2, source.detailCalls)
    }

    @Test
    fun aRetryWithNothingSetAsideOnlyFetchesTheDetail() {
        val (viewModel, source) = pageWith(module("50-2.0", null), null)
        assertEquals(false, viewModel.state.value.detailSetAside)

        viewModel.retry()

        assertEquals(emptyList<Boolean>(), source.refreshes)
        assertEquals(2, source.detailCalls)
    }
}
