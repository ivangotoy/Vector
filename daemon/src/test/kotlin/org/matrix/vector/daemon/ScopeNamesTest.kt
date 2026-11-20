package org.matrix.vector.daemon

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.vector.daemon.ipc.isScopeTargetName

/**
 * A module's scope request becomes the path of the prompt's action URI, and the answer is read back
 * from that URI: a name that is not a package name could carry its own `?action=` into it.
 */
class ScopeNamesTest {
  @Test
  fun packageNamesAndTheFrameworkAreAccepted() {
    val names = listOf("system", "android", "com.example.app", "com.example.app_2", "a.b1.c")
    assertEquals(emptyList<String>(), names.filterNot { isScopeTargetName(it) })
  }

  @Test
  fun namesThatCouldRewriteThePromptUriAreRefused() {
    val names =
        listOf(
            "com.example.app?action=approve",
            "com.example.app#x",
            "com.example.app/../x",
            "com.example.a,com.example.b",
            "com.example%2Capp",
            "",
            ".com.example",
            "com..example",
            "com.example.app ",
        )
    assertEquals(emptyList<String>(), names.filter { isScopeTargetName(it) })
  }
}
