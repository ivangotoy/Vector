package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A restore counts what happened, including scopes the daemon refused. */
class ApplyRestoreTest {
    private val target = BackupRepository.BackupTarget("com.example.app", 0)

    private fun module(name: String, scope: List<BackupRepository.BackupTarget> = listOf(target)) =
        BackupRepository.BackupModule(name, enabled = true, scope = scope)

    @Test
    fun aRefusedEnableSkipsTheModule() {
        val outcome = runBlocking {
            BackupRepository.applyRestore(
                listOf(module("com.example.a"), module("com.example.b")),
                enable = { name, _ -> name != "com.example.b" },
                setScope = { _, _ -> true },
            )
        }
        assertEquals(BackupRepository.RestoreOutcome(1, 1, emptyList()), outcome)
    }

    @Test
    fun aRefusedScopeIsRestoredAndNamed() {
        val outcome = runBlocking {
            BackupRepository.applyRestore(
                listOf(module("com.example.a"), module("com.example.b")),
                enable = { _, _ -> true },
                setScope = { name, _ -> name != "com.example.b" },
            )
        }
        assertEquals(BackupRepository.RestoreOutcome(2, 0, listOf("com.example.b")), outcome)
    }

    @Test
    fun anEmptyScopeIsNotWritten() {
        val asked = mutableListOf<String>()
        val outcome = runBlocking {
            BackupRepository.applyRestore(
                listOf(module("com.example.a", scope = emptyList())),
                enable = { _, _ -> true },
                setScope = { name, _ ->
                    asked += name
                    false
                },
            )
        }
        assertEquals(emptyList<String>(), asked)
        assertEquals(BackupRepository.RestoreOutcome(1, 0, emptyList()), outcome)
    }
}
