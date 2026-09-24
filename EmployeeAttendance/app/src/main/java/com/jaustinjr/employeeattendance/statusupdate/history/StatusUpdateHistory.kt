package com.jaustinjr.employeeattendance.statusupdate.history

import com.jaustinjr.employeeattendance.statusupdate.StatusUpdate
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateQuestion
import java.util.Calendar
import java.util.TimeZone

/** One answered question of a status update; unanswered questions are left out of history. */
data class AnsweredQuestion(val question: StatusUpdateQuestion, val answer: String)

/** A work shift in status update history: when and where it was, and what was written about it. */
data class StatusUpdateShift(
    val clockOutId: String,
    val worksiteName: String?,
    val clockInAtMillis: Long?,
    val clockOutAtMillis: Long,
    val answers: List<AnsweredQuestion>,
    val editedAtMillis: Long?,
) {
    /** A short look at the update for list rows: the first answer written. */
    val previewText: String get() = answers.firstOrNull()?.answer.orEmpty()
}

/** The shifts that ended on one calendar day, newest first. */
data class StatusUpdateDay(val dayStartMillis: Long, val shifts: List<StatusUpdateShift>)

/** This update as a history shift, keeping only the questions that were answered. */
fun StatusUpdate.toShift(): StatusUpdateShift = StatusUpdateShift(
    clockOutId = clockOutId,
    worksiteName = worksiteName,
    clockInAtMillis = clockInAtMillis,
    clockOutAtMillis = clockOutAtMillis,
    answers = StatusUpdateQuestion.entries.mapNotNull { question ->
        answers[question]?.takeIf { it.isNotBlank() }?.let { AnsweredQuestion(question, it.trim()) }
    },
    editedAtMillis = editedAtMillis,
)

/**
 * Groups [updates] into days by the local date of each shift's clock-out in [timeZone], newest day
 * and newest shift first. Updates with no answer are dropped.
 */
fun groupStatusUpdatesByDay(updates: List<StatusUpdate>, timeZone: TimeZone): List<StatusUpdateDay> =
    updates
        .filter { it.hasAnyAnswer }
        .sortedByDescending { it.clockOutAtMillis }
        .groupBy { startOfDayMillis(it.clockOutAtMillis, timeZone) }
        .map { (dayStart, dayUpdates) -> StatusUpdateDay(dayStart, dayUpdates.map { it.toShift() }) }

private fun startOfDayMillis(epochMillis: Long, timeZone: TimeZone): Long =
    Calendar.getInstance(timeZone).apply {
        timeInMillis = epochMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
