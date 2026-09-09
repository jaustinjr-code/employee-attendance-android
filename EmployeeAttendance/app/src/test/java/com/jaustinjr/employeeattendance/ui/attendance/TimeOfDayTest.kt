package com.jaustinjr.employeeattendance.ui.attendance

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeOfDayTest {

    @Test
    fun `hours before noon are morning`() {
        assertEquals(TimeOfDay.MORNING, TimeOfDay.fromHour(0))
        assertEquals(TimeOfDay.MORNING, TimeOfDay.fromHour(5))
        assertEquals(TimeOfDay.MORNING, TimeOfDay.fromHour(11))
    }

    @Test
    fun `noon through 5pm is afternoon`() {
        assertEquals(TimeOfDay.AFTERNOON, TimeOfDay.fromHour(12))
        assertEquals(TimeOfDay.AFTERNOON, TimeOfDay.fromHour(15))
        assertEquals(TimeOfDay.AFTERNOON, TimeOfDay.fromHour(17))
    }

    @Test
    fun `6pm onwards is evening`() {
        assertEquals(TimeOfDay.EVENING, TimeOfDay.fromHour(18))
        assertEquals(TimeOfDay.EVENING, TimeOfDay.fromHour(23))
    }
}
