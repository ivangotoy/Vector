package org.matrix.vector.ui.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the module page and StoreRepository.complete share: a record is set aside only when it
 * is known to name an older release than the list's entry.
 */
class DetailTrailsTest {
    private fun module(latest: String?, time: String? = null, betaTime: String? = null) =
        OnlineModule(
            name = "com.example.module",
            description = null,
            summary = null,
            url = null,
            homepageUrl = null,
            sourceUrl = null,
            readmeHTML = null,
            latestRelease = latest,
            latestReleaseTime = time,
            latestBetaReleaseTime = betaTime,
        )

    @Test
    fun theSameTagNeverTrails() {
        assertFalse(
            detailTrails(
                module("50-2.0", time = "2026-03-01T00:00:00Z"),
                module("50-2.0", time = "2026-03-09T00:00:00Z"),
                considerBeta = false,
            )
        )
    }

    @Test
    fun aDetailAheadDoesNotTrail() {
        assertFalse(detailTrails(module("51-2.1"), module("50-2.0"), considerBeta = true))
        assertTrue(detailTrails(module("49-1.9"), module("50-2.0"), considerBeta = true))
    }

    @Test
    fun anEarlierBetaTrailsOnlyWhereBetasCount() {
        val entry = module("50-2.0", betaTime = "2026-03-05T00:00:00Z")
        val detail = module("50-2.0", betaTime = "2026-03-01T00:00:00Z")
        assertTrue(detailTrails(detail, entry, considerBeta = true))
        assertFalse(detailTrails(detail, entry, considerBeta = false))
        assertFalse(detailTrails(entry, detail, considerBeta = true))
        assertFalse(detailTrails(module("50-2.0"), entry, considerBeta = true))
    }

    @Test
    fun anUndecidablePairDoesNotTrail() {
        assertFalse(detailTrails(module("beta"), module("50-2.0"), considerBeta = true))
        assertFalse(detailTrails(module(null), module("50-2.0"), considerBeta = true))
    }
}
