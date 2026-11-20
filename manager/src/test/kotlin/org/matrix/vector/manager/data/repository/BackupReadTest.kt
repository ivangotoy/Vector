package org.matrix.vector.manager.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupReadTest {

    private fun gzip(bytes: ByteArray): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun backup(version: Int, padding: Int = 0) =
        gzip(
            ("""{"version":$version,"build":"test","createdAt":1,"modules":""" +
                    """[{"packageName":"com.example.module","enabled":true,"scope":""" +
                    """[{"packageName":"com.example.app","userId":0}]}]""" +
                    " ".repeat(padding) +
                    "}")
                .toByteArray()
        )

    @Test
    fun aBackupOfThisFormatReadsBack() {
        val read = BackupRepository.readBackup(backup(1))

        assertEquals(listOf("com.example.module"), read.modules.map { it.packageName })
        assertEquals("com.example.app", read.modules.single().scope.single().packageName)
    }

    @Test
    fun aFileThatInflatesPastTheLimitIsRefusedBeforeItIsHeld() {
        assertThrows(IllegalStateException::class.java) {
            BackupRepository.readBackup(backup(1, padding = 1_000_000), limit = 64 * 1024)
        }
    }

    @Test
    fun aNewerFormatIsRefused() {
        assertThrows(IllegalStateException::class.java) {
            BackupRepository.readBackup(backup(BackupRepository.FORMAT_VERSION + 1))
        }
    }
}
