package com.jaustinjr.employeeattendance.reporting

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLogTest {

    @Test
    fun `a clock-in and the clock-out after it form one shift`() {
        val log = SessionLog.from(
            listOf(clockIn("a", "2026-09-08T09:00"), clockOut("a", "2026-09-08T17:00")),
        )

        assertEquals(
            listOf(WorkSession("a", millisAt("2026-09-08T09:00"), millisAt("2026-09-08T17:00"))),
            log.completed,
        )
        assertEquals(emptyList<ActiveShift>(), log.active)
    }

    @Test
    fun `an open clock-in is active and never completed`() {
        val log = SessionLog.from(listOf(clockIn("a", "2026-09-08T09:00")))

        assertEquals(emptyList<WorkSession>(), log.completed)
        assertEquals(listOf(ActiveShift("a", millisAt("2026-09-08T09:00"))), log.active)
    }

    @Test
    fun `events are paired per worksite, not across worksites`() {
        val log = SessionLog.from(
            listOf(
                clockIn("a", "2026-09-08T09:00"),
                clockIn("b", "2026-09-08T10:00"),
                clockOut("a", "2026-09-08T12:00"),
                clockOut("b", "2026-09-08T15:00"),
            ),
        )

        assertEquals(
            listOf(
                WorkSession("a", millisAt("2026-09-08T09:00"), millisAt("2026-09-08T12:00")),
                WorkSession("b", millisAt("2026-09-08T10:00"), millisAt("2026-09-08T15:00")),
            ),
            log.completed,
        )
    }

    @Test
    fun `a repeated clock-in keeps the earlier start`() {
        val log = SessionLog.from(
            listOf(
                clockIn("a", "2026-09-08T09:00"),
                clockIn("a", "2026-09-08T09:30"),
                clockOut("a", "2026-09-08T17:00"),
            ),
        )

        assertEquals(millisAt("2026-09-08T09:00"), log.completed.single().startMillis)
    }

    @Test
    fun `a clock-out with no open shift is ignored`() {
        val log = SessionLog.from(
            listOf(
                clockOut("a", "2026-09-08T08:00"),
                clockIn("a", "2026-09-08T09:00"),
                clockOut("a", "2026-09-08T17:00"),
                clockOut("a", "2026-09-08T18:00"),
            ),
        )

        assertEquals(1, log.completed.size)
        assertEquals(8 * HOUR, log.completed.single().durationMillis)
    }

    @Test
    fun `events out of order in the log are paired by time`() {
        val log = SessionLog.from(
            listOf(clockOut("a", "2026-09-08T17:00"), clockIn("a", "2026-09-08T09:00")),
        )

        assertEquals(8 * HOUR, log.completed.single().durationMillis)
    }

    @Test
    fun `a zero-length shift is dropped`() {
        val log = SessionLog.from(
            listOf(clockIn("a", "2026-09-08T09:00"), clockOut("a", "2026-09-08T09:00")),
        )

        assertEquals(emptyList<WorkSession>(), log.completed)
    }
}
