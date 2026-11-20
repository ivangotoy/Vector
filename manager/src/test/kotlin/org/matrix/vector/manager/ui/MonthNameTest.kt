package org.matrix.vector.manager.ui

import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.vector.manager.ui.components.monthName

/** The commit timeline's month separators, named on the 31st of a month. */
class MonthNameTest {
    @Test
    fun everyMonthIsNamedItselfOnThe31st() {
        val today = Calendar.getInstance(Locale.ENGLISH).apply { set(2026, Calendar.MARCH, 31) }
        val expected =
            listOf(
                "January",
                "February",
                "March",
                "April",
                "May",
                "June",
                "July",
                "August",
                "September",
                "October",
                "November",
                "December",
            )
        assertEquals(expected, (0..11).map { monthName(it, Locale.ENGLISH, today) })
    }
}
