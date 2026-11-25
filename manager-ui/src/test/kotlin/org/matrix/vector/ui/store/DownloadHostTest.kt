package org.matrix.vector.ui.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The install dialog names the host the APK comes from. */
class DownloadHostTest {
    @Test
    fun theHostOfADownloadUrl() {
        assertEquals("example.com", downloadHost("https://Example.com/owner/repo/module.apk"))
        assertEquals("192.0.2.1", downloadHost("https://192.0.2.1:8443/module.apk"))
    }

    @Test
    fun aUrlTheInstallerFetchesNamesItsHost() {
        // OkHttp, which the installer downloads with, takes these; java.net.URI refused them.
        assertEquals("example.com", downloadHost("https://example.com/owner/repo/my module.apk"))
        assertEquals("example.com", downloadHost("https://example.com/a|b.apk"))
        assertEquals("dl_mirror.example.com", downloadHost("https://dl_mirror.example.com/m.apk"))
        assertEquals("xn--exmple-cua.com", downloadHost("https://exämple.com/m.apk"))
    }

    @Test
    fun noHostNoLine() {
        assertNull(downloadHost(null))
        assertNull(downloadHost("module.apk"))
        assertNull(downloadHost("https://exa mple.com/x.apk"))
    }
}
