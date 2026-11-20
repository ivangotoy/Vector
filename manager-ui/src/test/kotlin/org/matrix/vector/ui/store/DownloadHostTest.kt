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
    fun noHostNoLine() {
        assertNull(downloadHost(null))
        assertNull(downloadHost("module.apk"))
        assertNull(downloadHost("https://exa mple.com/x.apk"))
    }
}
