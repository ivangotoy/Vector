package org.matrix.vector.manager.ui.screens.modules

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Use recommended" may only change what the scope screen shows; Apply deletes what it drops. */
class ReplaceShownTest {
    private val ownerA = ScopeTarget("com.example.a", 0)
    private val ownerB = ScopeTarget("com.example.b", 0)
    private val workA = ScopeTarget("com.example.a", 10)
    private val gone = ScopeTarget("com.example.uninstalled", 0)

    @Test
    fun rowsTheScreenCannotShowAreKept() {
        val draft = setOf(ownerA, workA, gone)
        val shown = setOf(ownerA, ownerB)
        assertEquals(setOf(workA, gone, ownerB), replaceShown(draft, shown, setOf(ownerB)))
    }

    @Test
    fun shownRowsAreReplacedExactly() {
        assertEquals(
            setOf(ownerA),
            replaceShown(setOf(ownerA, ownerB), setOf(ownerA, ownerB), setOf(ownerA)),
        )
    }
}
