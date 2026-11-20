package org.matrix.vector.ui.update

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sizes follow the app's language, whatever the JVM default is. */
class FormatSizeTest {
    @Test
    fun theDecimalSeparatorIsTheGivenLocales() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("1,5 MB", formatSize(1_572_864, Locale.GERMANY))
            assertEquals("1.5 MB", formatSize(1_572_864, Locale.US))
            assertEquals("2 kB", formatSize(2048, Locale.GERMANY))
            assertEquals("512 B", formatSize(512, Locale.GERMANY))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
