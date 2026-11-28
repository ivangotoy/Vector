package org.matrix.vector.daemon

import java.io.FileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `log` streaming: a log descriptor that did not arrive is a failure, not an empty log. */
class CliLogFdTest {
  private val announced = CliResponse(success = true, isFdAttached = true)

  private fun answer(trigger: Int, received: Array<FileDescriptor>?): Pair<CliResponse, Int> {
    var streamed = 0
    val response = withAttachedLog(announced, trigger, { received }) { streamed++ }
    return response to streamed
  }

  @Test
  fun aMissingDescriptorFails() {
    for ((trigger, received) in
        listOf(1 to null, 1 to emptyArray<FileDescriptor>(), 0 to arrayOf(FileDescriptor.out))) {
      val (response, streamed) = answer(trigger, received)
      assertFalse("trigger $trigger, ${received?.size} descriptors", response.success)
      assertEquals(0, streamed)
    }
  }

  @Test
  fun anArrivedDescriptorIsStreamed() {
    val (response, streamed) = answer(1, arrayOf(FileDescriptor.out))
    assertTrue(response.success)
    assertEquals(1, streamed)
  }
}
