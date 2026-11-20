package org.matrix.vector.ui.logs

import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelVerboseTest {

    /** A host whose verbose write answers with [answer], or throws when it is null. */
    private class FakeSource(var answer: Boolean?) : LogSource {
        override suspend fun parts(verbose: Boolean): List<String> = emptyList()

        override suspend fun open(verbose: Boolean, part: String?): Result<LogContent?> =
            Result.success(null)

        override val canConfigureVerbose = true

        override suspend fun isVerboseEnabled() = false

        override suspend fun setVerboseEnabled(enabled: Boolean): Boolean =
            answer ?: throw IOException("daemon unreachable")

        override val canSaveArchive = false
        override val archiveMimeType = "application/zip"

        override fun archiveName() = "logs.zip"

        override suspend fun saveArchive(uri: Uri, verbose: Boolean): Result<Unit> =
            Result.success(Unit)

        override val resetKind: LogResetKind? = null

        override suspend fun reset(verbose: Boolean) = false

        override val wordWrap: StateFlow<Boolean> = MutableStateFlow(false)
        override val tracesInline: StateFlow<Boolean> = MutableStateFlow(false)

        override fun setWordWrap(enabled: Boolean) = Unit

        override fun setTracesInline(inline: Boolean) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun failedWriteDoesNotShowTheRequestedValue() {
        val viewModel = LogsViewModel(FakeSource(answer = null))

        viewModel.setVerbose(true)

        assertFalse(viewModel.verboseEnabled.value)
        assertFalse(viewModel.verboseEnforced.value)
    }

    @Test
    fun failedWriteKeepsTheEnforcedState() {
        val source = FakeSource(answer = true)
        val viewModel = LogsViewModel(source)
        viewModel.setVerbose(false)
        assertTrue(viewModel.verboseEnabled.value)
        assertTrue(viewModel.verboseEnforced.value)

        source.answer = null
        viewModel.setVerbose(true)

        assertTrue(viewModel.verboseEnabled.value)
        assertTrue(viewModel.verboseEnforced.value)
    }
}
