package org.matrix.vector.ui.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoDetailsStateTest {

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

    private val newestWithoutApk = release("50-2.0", withApk = false)
    private val previous = release("49-1.9", withApk = true)

    @Test
    fun barOffersTheReleaseItInstalls() {
        val state =
            RepoDetailsState(
                releases = listOf(newestWithoutApk, previous),
                installed = RepoVersion(48, "1.8"),
                latest = RepoVersion(50, "2.0"),
            )

        assertEquals(previous, state.installable)
        assertEquals(RepoVersion(49, "1.9"), state.offered)
        assertTrue(state.upgradable)
    }

    @Test
    fun installedPreviousIsNotOfferedAsAnUpdate() {
        val state =
            RepoDetailsState(
                releases = listOf(newestWithoutApk, previous),
                installed = RepoVersion(49, "1.9"),
                latest = RepoVersion(50, "2.0"),
            )

        assertFalse(state.upgradable)
    }

    @Test
    fun newestWithApkIsOffered() {
        val newest = release("50-2.0", withApk = true)
        val state =
            RepoDetailsState(
                releases = listOf(newest, previous),
                installed = RepoVersion(49, "1.9"),
                latest = RepoVersion(50, "2.0"),
            )

        assertEquals(RepoVersion(50, "2.0"), state.offered)
        assertTrue(state.upgradable)
        assertFalse(state.sameVersion)
    }
}
