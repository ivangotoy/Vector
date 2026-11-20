package org.matrix.vector.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The CLI socket carries answers longer than the 65535 bytes `writeUTF` could hold. */
class CliFrameTest {
  private fun roundTrip(text: String): String {
    val bytes = ByteArrayOutputStream()
    writeFrame(DataOutputStream(bytes), text)
    return readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
  }

  @Test
  fun longAnswerArrivesWhole() {
    val text = (1..5000).joinToString(",\n") { "  { \"APP_PACKAGE\": \"com.example.app$it\" }" }
    assertEquals(text, roundTrip(text))
  }

  @Test
  fun nonAsciiTextIsKept() {
    assertEquals("Модуль 模块 \u0000 ok", roundTrip("Модуль 模块 \u0000 ok"))
  }

  @Test
  fun lengthOutsideTheLimitIsRefused() {
    for (size in listOf(-1, MAX_FRAME_BYTES + 1)) {
      val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }.toByteArray()
      assertThrows(IOException::class.java) {
        readFrame(DataInputStream(ByteArrayInputStream(bytes)))
      }
    }
  }
}
