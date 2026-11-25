package org.matrix.vector.manager.data.repository

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** A framework zip is flashed as root: it is fetched over https and kept there. */
class FrameworkDownloadTest {
    private val client = OkHttpClient()

    @Test
    fun anHttpUrlIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            frameworkDownloadCall(client, "http://example.com/Vector-v2.2-3300-Release.zip")
        }
    }

    @Test
    fun anHttpsUrlIsFetched() {
        val call = frameworkDownloadCall(client, "https://example.com/Vector-v2.2-3300-Release.zip")
        assertEquals("https", call.request().url.scheme)
        assertEquals(true, call.request().cacheControl.noStore)
    }

    @Test
    fun aRedirectToHttpIsNotFollowed() {
        assertFalse(httpsOnly(client).followSslRedirects)
    }
}
