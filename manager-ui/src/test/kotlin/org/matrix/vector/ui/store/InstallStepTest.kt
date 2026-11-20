package org.matrix.vector.ui.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStepTest {

    private val page = "com.example.page"
    private val other = "com.example.other"

    @Test
    fun anotherPackagesStepIsIdleOnThisPage() {
        val steps =
            listOf(
                InstallStep.Downloading(other, 1, 2),
                InstallStep.Installing(other),
                InstallStep.Confirming(other),
                InstallStep.Done(other),
                InstallStep.Failed(other, "no space"),
            )
        for (step in steps) assertEquals(InstallStep.Idle, step.forPage(page))
    }

    @Test
    fun thisPackagesStepIsShown() {
        val steps =
            listOf(
                InstallStep.Idle,
                InstallStep.Downloading(page, 1, 2),
                InstallStep.Installing(page),
                InstallStep.Confirming(page),
                InstallStep.Done(page),
                InstallStep.Failed(page, null),
            )
        for (step in steps) assertEquals(step, step.forPage(page))
    }

    @Test
    fun inProgressCoversTheRunningSteps() {
        assertTrue(InstallStep.Downloading(other, 0, 0).inProgress)
        assertTrue(InstallStep.Installing(other).inProgress)
        assertTrue(InstallStep.Confirming(other).inProgress)
        assertFalse(InstallStep.Idle.inProgress)
        assertFalse(InstallStep.Done(other).inProgress)
        assertFalse(InstallStep.Failed(other, null).inProgress)
    }
}
