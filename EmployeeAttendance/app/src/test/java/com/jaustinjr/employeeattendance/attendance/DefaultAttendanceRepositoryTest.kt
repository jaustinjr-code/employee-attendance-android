package com.jaustinjr.employeeattendance.attendance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAttendanceRepositoryTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun repo(local: FakeAttendanceLocalDataSource = FakeAttendanceLocalDataSource()) =
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
        val local = FakeAttendanceLocalDataSource()
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
        val local = FakeAttendanceLocalDataSource(
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
        val local = FakeAttendanceLocalDataSource()
        val repo = repo(local)
        repo.recordClockIn("site-a", 1_000L)
        repo.recordClockOut("site-b", 2_000L)

        repo.clearAll()

        assertTrue(repo.attendance.value.isEmpty())
        assertTrue(local.stored.isEmpty())
    }

    @Test
    fun `undoLast is a no-op when there is nothing for the location`() {
        val local = FakeAttendanceLocalDataSource()
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

    @Test
    fun `undoing a clock-in leaves the location not clocked in`() {
        val repo = repo()
        repo.recordClockIn("site-a", 1_000L)
        assertTrue(repo.attendance.value["site-a"]?.isClockedIn == true)

        repo.undoLast("site-a")

        assertFalse(repo.attendance.value["site-a"]?.isClockedIn == true)
    }

    @Test
    fun `recordIfStateChanges records a clock-in only when clocked out`() {
        val repo = repo()

        assertTrue(repo.recordIfStateChanges("site-a", ClockType.CLOCK_IN, 1_000L))
        // Second arrival while the session is still open must be a no-op.
        assertFalse(repo.recordIfStateChanges("site-a", ClockType.CLOCK_IN, 2_000L))

        assertEquals(1_000L, repo.attendance.value["site-a"]?.lastClockInMillis)
    }

    @Test
    fun `recordIfStateChanges records a clock-out only when clocked in`() {
        val repo = repo()

        // Nothing is open, so there is nothing to close.
        assertFalse(repo.recordIfStateChanges("site-a", ClockType.CLOCK_OUT, 1_000L))
        assertTrue(repo.attendance.value.isEmpty())

        repo.recordClockIn("site-a", 2_000L)
        assertTrue(repo.recordIfStateChanges("site-a", ClockType.CLOCK_OUT, 3_000L))
        assertFalse(repo.recordIfStateChanges("site-a", ClockType.CLOCK_OUT, 4_000L))
        assertEquals(3_000L, repo.attendance.value["site-a"]?.lastClockOutMillis)
    }

    @Test
    fun `derived attendance is visible immediately even when the io scope never runs`() {
        // A dispatcher whose queued work is never executed: if `attendance` were derived via
        // stateIn(ioScope) the guards would read stale state right after recording.
        val idleScope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))
        val repo = DefaultAttendanceRepository(
            local = FakeAttendanceLocalDataSource(),
            ioScope = idleScope,
        )

        repo.recordClockIn("site-a", 1_000L)

        assertTrue(repo.isClockedIn("site-a"))
        assertEquals(1_000L, repo.attendance.value["site-a"]?.lastClockInMillis)
    }

    @Test
    fun `concurrent recordIfStateChanges opens exactly one session`() {
        // The auto-clock path runs on a background dispatcher while the manual button and the
        // notification receiver run on the main thread, so the check and the append have to be
        // atomic. Without the @Synchronized override several threads pass the check together.
        val local = FakeAttendanceLocalDataSource()
        val repo = repo(local)
        val threadCount = 8
        val start = CountDownLatch(1)
        val recorded = AtomicInteger(0)

        val threads = (0 until threadCount).map {
            Thread {
                start.await()
                if (repo.recordIfStateChanges("site-a", ClockType.CLOCK_IN, 1_000L)) {
                    recorded.incrementAndGet()
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(10_000L) }

        assertEquals(1, recorded.get())
        assertEquals(1, local.stored.count { it.type == ClockType.CLOCK_IN })
    }
}
