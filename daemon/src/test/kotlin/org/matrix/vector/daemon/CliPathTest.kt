package org.matrix.vector.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

/** `db backup`/`db restore` paths: the daemon's own directory is the module's, not the caller's. */
class CliPathTest {
  @Test
  fun relativePathResolvesInTheCallersDirectory() {
    assertEquals("/data/local/tmp/vector.db", absolutePath("vector.db") { "/data/local/tmp" })
    assertEquals(
        "/data/local/tmp/sub/vector.db",
        absolutePath("sub/vector.db") { "/data/local/tmp" },
    )
  }

  @Test
  fun absolutePathIsKeptAndTheDirectoryNotRead() {
    assertEquals(
        "/sdcard/vector.db",
        absolutePath("/sdcard/vector.db") { throw AssertionError("cwd read for an absolute path") },
    )
  }
}
