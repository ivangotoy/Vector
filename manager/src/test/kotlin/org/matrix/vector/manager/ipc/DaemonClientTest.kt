package org.matrix.vector.manager.ipc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.vector.ipc.IManagerService

class DaemonClientTest {

    @Test
    fun aDaemonThatCannotBeAskedIsAFailureNotAPackageWithoutAScreen() {
        val client = DaemonClient(MutableStateFlow<IManagerService?>(null))

        val opened = runBlocking {
            client.openAppUi("com.example.module", 0, companionFirst = true)
        }

        assertTrue(opened.isFailure)
    }
}
