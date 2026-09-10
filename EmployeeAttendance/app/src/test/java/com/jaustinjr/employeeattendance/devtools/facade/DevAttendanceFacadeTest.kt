package com.jaustinjr.employeeattendance.devtools.facade

import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.devtools.FakeAttendanceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevAttendanceFacadeTest {

    private val repository = FakeAttendanceRepository()
    private val facade = RepositoryDevAttendanceFacade(repository)

    @Test
    fun `developer writes are tagged as simulated, not as a real automatic event`() {
        facade.recordSimulatedClockIn("office", 1_000L)
        facade.recordSimulatedClockOut("office", 2_000L)

        assertEquals(2, repository.events.size)
        repository.events.forEach { assertEquals(ClockSource.SIMULATED, it.source) }
        assertEquals(ClockType.CLOCK_IN, repository.events[0].type)
        assertEquals(1_000L, repository.events[0].epochMillis)
    }

    @Test
    fun `a simulated clock-out is not reported as a manual one`() {
        facade.recordSimulatedClockOut("office", 2_000L)

        // `lastClockOutManual` is the only production branch on ClockSource; SIMULATED must land in
        // the same bucket as AUTO so no display rule changes.
        assertFalse(repository.attendance.value.getValue("office").lastClockOutManual)
    }

    @Test
    fun `clearing simulated attendance leaves genuine history intact`() {
        repository.recordClockIn("office", 1_000L, ClockSource.MANUAL)
        repository.recordClockIn("office", 2_000L, ClockSource.AUTO)
        facade.recordSimulatedClockIn("office", 3_000L)
        facade.recordSimulatedClockOut("office", 4_000L)

        facade.clearSimulatedAttendance()

        assertEquals(
            listOf(ClockSource.MANUAL, ClockSource.AUTO),
            repository.events.map { it.source },
        )
    }

    @Test
    fun `clearing all attendance is still available and still takes everything`() {
        repository.recordClockIn("office", 1_000L, ClockSource.MANUAL)
        facade.recordSimulatedClockIn("office", 2_000L)

        facade.clearAllAttendance()

        assertTrue(repository.events.isEmpty())
    }

    @Test
    fun `clocked-in state is readable without exposing the derived map`() {
        assertFalse(facade.isClockedIn("office"))

        facade.recordSimulatedClockIn("office", 1_000L)

        assertTrue(facade.isClockedIn("office"))
    }

    /**
     * A compile-time contract expressed as a test: the facade must not offer `undoLast`. If someone
     * adds it, the reviewer finding this fix was for is back — a preview's Undo (or any developer
     * "take that back") would delete the most recent *genuine* attendance event.
     */
    @Test
    fun `the facade exposes no undo at all`() {
        val methodNames = DevAttendanceFacade::class.java.methods.map { it.name }

        assertFalse(methodNames.toString(), methodNames.any { it.contains("undo", ignoreCase = true) })
    }
}
