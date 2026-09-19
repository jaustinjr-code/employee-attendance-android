package com.jaustinjr.employeeattendance.statusupdate.history

import com.jaustinjr.employeeattendance.statusupdate.StatusUpdate
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateQuestion
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusUpdateHistoryTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun update(
        id: String,
        clockOutAt: Long,
        answers: Map<StatusUpdateQuestion, String> = mapOf(StatusUpdateQuestion.DID_TODAY to "did"),
    ) = StatusUpdate(
        clockOutId = id,
        answers = answers,
        completedAtMillis = clockOutAt + 60_000L,
        clockOutAtMillis = clockOutAt,
    )

    @Test
    fun `groups by the clock-out's day, newest day and newest shift first`() {
        val morning = update("morning", clockOutAt = 10 * day + 11 * hour)
        val evening = update("evening", clockOutAt = 10 * day + 20 * hour)
        val nextDay = update("next", clockOutAt = 11 * day + 9 * hour)

        val days = groupStatusUpdatesByDay(listOf(morning, nextDay, evening), utc)

        assertEquals(listOf(11 * day, 10 * day), days.map { it.dayStartMillis })
        assertEquals(listOf("next"), days[0].shifts.map { it.clockOutId })
        assertEquals(listOf("evening", "morning"), days[1].shifts.map { it.clockOutId })
    }

    @Test
    fun `the day boundary follows the time zone`() {
        // 23:30 UTC on day 10 is already day 11 in UTC+2.
        val lateNight = update("late", clockOutAt = 10 * day + 23 * hour + 30 * 60_000L)

        val utcDay = groupStatusUpdatesByDay(listOf(lateNight), utc).single().dayStartMillis
        val plusTwoDay = groupStatusUpdatesByDay(
            listOf(lateNight),
            TimeZone.getTimeZone("GMT+02:00"),
        ).single().dayStartMillis

        assertEquals(10 * day, utcDay)
        assertEquals(11 * day - 2 * hour, plusTwoDay)
    }

    @Test
    fun `updates with no answer are left out`() {
        val blank = update("blank", clockOutAt = day, answers = mapOf(StatusUpdateQuestion.DID_TODAY to " "))

        assertEquals(emptyList<StatusUpdateDay>(), groupStatusUpdatesByDay(listOf(blank), utc))
    }

    @Test
    fun `a shift keeps only answered questions, trimmed, in question order`() {
        val shift = update(
            "a",
            clockOutAt = day,
            answers = mapOf(
                StatusUpdateQuestion.PLANNED_TOMORROW to "  Restock shelves ",
                StatusUpdateQuestion.COULD_NOT_DO to "Deliveries",
            ),
        ).toShift()

        assertEquals(
            listOf(
                AnsweredQuestion(StatusUpdateQuestion.PLANNED_TOMORROW, "Restock shelves"),
                AnsweredQuestion(StatusUpdateQuestion.COULD_NOT_DO, "Deliveries"),
            ),
            shift.answers,
        )
        assertEquals("Restock shelves", shift.previewText)
    }

    @Test
    fun `history follows every question in the enum, in declaration order`() {
        val all = StatusUpdateQuestion.entries.associateWith { "answer ${it.id}" }
        val shift = StatusUpdate(
            clockOutId = "a",
            answers = all,
            completedAtMillis = 1L,
        ).toShift()

        assertEquals(
            StatusUpdateQuestion.entries.map { AnsweredQuestion(it, "answer ${it.id}") },
            shift.answers,
        )
    }
}
