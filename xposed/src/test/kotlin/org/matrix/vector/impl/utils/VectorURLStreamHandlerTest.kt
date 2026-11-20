package org.matrix.vector.impl.utils

import java.io.File
import java.net.URL
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Closing the stream of one resource must leave the module's other resources readable. */
class VectorURLStreamHandlerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun jarWith(vararg entries: Pair<String, String>): File {
        val file = tmp.newFile("module.apk")
        JarOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    // Built by hand: getEntryUrlOrNull encodes the name with android.net.Uri, a stub on the host.
    private fun url(handler: VectorURLStreamHandler, jar: File, entry: String) =
        URL("jar", null, -1, "${jar.toURI()}!/$entry", handler)

    private fun read(url: URL) = url.openStream().use { it.readBytes().decodeToString() }

    @Test
    fun resourcesStayReadableAfterAStreamIsClosed() {
        val jar = jarWith("a.txt" to "first", "b.txt" to "second")
        val handler = VectorURLStreamHandler(jar.path)
        try {
            assertEquals("first", read(url(handler, jar, "a.txt")))
            assertEquals("second", read(url(handler, jar, "b.txt")))
            assertEquals("first", read(url(handler, jar, "a.txt")))
        } finally {
            handler.close()
        }
    }
}
