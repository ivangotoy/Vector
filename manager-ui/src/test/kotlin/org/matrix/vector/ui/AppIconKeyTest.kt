package org.matrix.vector.ui

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** An update keeps the uid but moves the APK: its icon must not be served from the old entry. */
class AppIconKeyTest {
    private fun info(sourceDir: String) =
        ApplicationInfo().apply {
            packageName = "com.example.app"
            uid = 10123
            this.sourceDir = sourceDir
        }

    @Test
    fun anUpdatedApkGetsANewKey() {
        assertNotEquals(
            AppIconCache.keyFor(info("/data/app/~~a/com.example.app-1/base.apk"), 48),
            AppIconCache.keyFor(info("/data/app/~~b/com.example.app-2/base.apk"), 48),
        )
    }

    @Test
    fun theSameInstallKeepsItsKey() {
        assertEquals(
            AppIconCache.keyFor(info("/data/app/~~a/com.example.app-1/base.apk"), 48),
            AppIconCache.keyFor(info("/data/app/~~a/com.example.app-1/base.apk"), 48),
        )
    }
}
