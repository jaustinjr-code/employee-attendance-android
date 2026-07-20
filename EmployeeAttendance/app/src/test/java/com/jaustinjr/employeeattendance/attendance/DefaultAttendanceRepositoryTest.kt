package com.jaustinjr.employeeattendance.attendance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAttendanceRepositoryTest {

    /** In-memory [AttendanceLocalDataSource] backing the persistence assertions. */
    private class FakeLocalDataSource(
        var stored: List<AttendanceEvent> = emptyList(),
    ) : AttendanceLocalDataSource {
        var saveCount = 0
        override fun load(): List<AttendanceEvent> = stored
        override fun save(events: List<AttendanceEvent>) {
            saveCount++
            stored = events
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun repo(local: FakeLocalDataSource = FakeLocalDataSource()) =
        DefaultAttendanceRepository(local = local, ioScope = scope)

    @Test
    fun `recordClockIn exposes the latest clock-in per location`() {
        val repo = repo()
        repo.recordClockIn("site-a", 1_000L)
        repo.recordClockIn("site-a", 3_000L)
        repo.recordClockIn("site-b", 2_000L)

        val attendance = repo.attendance.value
        assertEquals(3_000L, attendance["site-a"]?.lastClockInMillis)
        assertEquals(2_000L, attendance["site-b"]?.lastClockInMillis)
    }

    @Test
    fun `clock-out is tracked with its source`() {
        val repo = repo()
        repo.recordClockOut("site-a", 9_000L, ClockSource.MANUAL)

        val attendance = repo.attendance.value["site-a"]
        assertEquals(9_000L, attendance?.lastClockOutMillis)
        assertTrue(attendance?.lastClockOutManual == true)
    }

    @Test
    fun `automatic clock-out is not flagged as manual`() {
        val repo = repo()
        repo.recordClockOut("site-a", 9_000L, ClockSource.AUTO)

        assertFalse(repo.attendance.value["site-a"]?.lastClockOutManual == true)
    }

    @Test
    fun `events are persisted through the local data source`() {
        val local = FakeLocalDataSource()
        val repo = repo(local)
        repo.recordClockIn("site-a", 1_000L, ClockSource.MANUAL)

        assertEquals(1, local.stored.size)
        assertEquals(
            AttendanceEvent("site-a", ClockType.CLOCK_IN, 1_000L, ClockSource.MANUAL),
            local.stored.first(),
        )
    }

    @Test
    fun `state is seeded from persisted events on construction`() {
        val local = FakeLocalDataSource(
            stored = listOf(AttendanceEvent("site-a", ClockType.CLOCK_IN, 4_000L)),
        )
        val repo = repo(local)

        assertEquals(4_000L, repo.attendance.value["site-a"]?.lastClockInMillis)
    }

    @Test
    fun `undoLast removes the most recent event for a location only`() {
        val repo = repo()
        repo.recordClockIn("site-a", 1_000L)
        repo.recordClockIn("site-b", 2_000L)
        repo.recordClockIn("site-a", 3_000L)

        repo.undoLast("site-a")

        // The 3_000 clock-in is gone; the earlier 1_000 remains as the latest for site-a.
        assertEquals(1_000L, repo.attendance.value["site-a"]?.lastClockInMillis)
        assertEquals(2_000L, repo.attendance.value["site-b"]?.lastClockInMillis)
    }

    @Test
    fun `clearAll deletes all recorded attendance`() {
        val local = FakeLocalDataSource()
        val repo = repo(local)
        repo.recordClockIn("site-a", 1_000L)
        repo.recordClockOut("site-b", 2_000L)

        repo.clearAll()

        assertTrue(repo.attendance.value.isEmpty())
        assertTrue(local.stored.isEmpty())
    }

    @Test
    fun `undoLast is a no-op when there is nothing for the location`() {
        val local = FakeLocalDataSource()
        val repo = repo(local)
        val before = local.saveCount

        repo.undoLast("unknown")

        assertEquals(before, local.saveCount)
        assertTrue(repo.attendance.value.isEmpty())
    }

    @Test
    fun `undoLast on a clock-in falls back to no clock-in when none remain`() {
        val repo = repo()
        repo.recordClockIn("site-a", 1_000L)

        repo.undoLast("site-a")

        assertNull(repo.attendance.value["site-a"]?.lastClockInMillis)
    }
}
