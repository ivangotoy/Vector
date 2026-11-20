package org.matrix.vector.daemon

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.vector.daemon.ipc.MAX_SUBSCRIBERS_PER_PROCESS
import org.matrix.vector.daemon.ipc.oldestBeyondCap

/** Hot reloads register again without unregistering; one process keeps only its newest few. */
class SubscriberCapTest {
  @Test
  fun underTheCapNothingIsDropped() {
    assertEquals(emptyList<Long>(), oldestBeyondCap(listOf(3L, 1L, 2L), 3))
  }

  @Test
  fun theOldestAreDroppedNotTheNewest() {
    assertEquals(listOf(1L, 2L), oldestBeyondCap(listOf(4L, 1L, 3L, 2L), 2))
  }

  @Test
  fun aNewRegistrationAtTheCapDropsExactlyOne() {
    val held = (0L until MAX_SUBSCRIBERS_PER_PROCESS.toLong()).toList()
    assertEquals(listOf(0L), oldestBeyondCap(held, MAX_SUBSCRIBERS_PER_PROCESS - 1))
  }
}
