package org.matrix.vector.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

/** Package events reach the installed manager only when its signature was verified. */
class PackageEventTargetsTest {
  @Test
  fun anUnverifiedInstalledManagerIsLeftOut() {
    assertEquals(listOf(BuildConfig.MANAGER_INJECTED_PKG_NAME), packageEventTargets(false))
  }

  @Test
  fun aVerifiedInstalledManagerIsIncluded() {
    assertEquals(
        listOf(BuildConfig.MANAGER_INJECTED_PKG_NAME, BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME),
        packageEventTargets(true),
    )
  }
}
