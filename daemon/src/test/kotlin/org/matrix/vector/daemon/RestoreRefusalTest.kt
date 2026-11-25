package org.matrix.vector.daemon

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.vector.daemon.data.restoreRefusal

/** `db restore` refuses a file the daemon would open as an empty database. */
class RestoreRefusalTest {
  @get:Rule val dir = TemporaryFolder()

  private fun database(userVersion: Int): File {
    val header = ByteArray(4096)
    "SQLite format 3\u0000".toByteArray().copyInto(header)
    ByteBuffer.wrap(header, 60, 4).putInt(userVersion)
    return dir.newFile().apply { writeBytes(header) }
  }

  @Test
  fun aFileThatIsNotADatabaseIsRefused() {
    assertNotNull(restoreRefusal(dir.newFile().apply { writeText("modules\n") }))
    assertNotNull(restoreRefusal(dir.newFile()))
  }

  @Test
  fun aVersionTheDowngradeWouldWipeIsRefused() {
    assertNotNull(restoreRefusal(database(6)))
    assertNotNull(restoreRefusal(database(100)))
  }

  @Test
  fun ourOwnAndLsposedDatabasesAreTaken() {
    assertEquals(null, restoreRefusal(database(5)))
    assertEquals(null, restoreRefusal(database(1)))
    assertEquals(null, restoreRefusal(database(101)))
  }
}
