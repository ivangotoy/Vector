package org.matrix.vector.daemon

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.vector.daemon.ipc.sqlStringLiteral

/**
 * `db backup` puts the user's path into `VACUUM INTO '<path>'`; a quote in it must not end the
 * literal.
 */
class CliHandlerSqlTest {
  @Test
  fun quotesAreDoubled() {
    assertEquals("'/sdcard/it''s.db'", sqlStringLiteral("/sdcard/it's.db"))
    assertEquals("''''''", sqlStringLiteral("''"))
  }

  @Test
  fun plainPathIsOnlyQuoted() {
    assertEquals("'/data/local/tmp/vector.db'", sqlStringLiteral("/data/local/tmp/vector.db"))
  }
}
