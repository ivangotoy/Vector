package org.matrix.vector.ui.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Store list offers what it can install: the newest release that carries an APK. */
class OfferedOnTest {

    private fun release(tag: String, withApk: Boolean) =
        Release(
            id = tag,
            name = tag,
            tagName = tag,
            url = null,
            descriptionHTML = null,
            publishedAt = null,
            releaseAssets =
                if (withApk)
                    listOf(
                        ReleaseAsset(
                            name = "module-$tag.apk",
                            downloadUrl = "https://example.com/module-$tag.apk",
                        )
                    )
                else emptyList(),
        )

    private fun module(latestRelease: String?, releases: List<Release>?) =
        OnlineModule(
            name = "com.example.module",
            description = null,
            summary = null,
            url = null,
            homepageUrl = null,
            sourceUrl = null,
            readmeHTML = null,
            latestRelease = latestRelease,
            releases = releases,
        )

    @Test
    fun aNewestReleaseWithoutAnApkIsNotOffered() {
        val m = module("50-2.0", listOf(release("50-2.0", false), release("49-1.9", true)))
        assertEquals(RepoVersion(49, "1.9"), m.offeredOn(StoreChannel.Stable))
    }

    @Test
    fun aNewestReleaseWithAnApkIsOffered() {
        val m = module("50-2.0", listOf(release("50-2.0", true), release("49-1.9", true)))
        assertEquals(RepoVersion(50, "2.0"), m.offeredOn(StoreChannel.Stable))
    }

    @Test
    fun anEntryWithoutReleasesFallsBackToItsTag() {
        val m = module("50-2.0", null)
        assertEquals(RepoVersion(50, "2.0"), m.offeredOn(StoreChannel.Stable))
    }
}
