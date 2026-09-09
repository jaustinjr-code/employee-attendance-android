package com.jaustinjr.employeeattendance.ui.attendance

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Which greeting the attendance screen shows, derived from the hour on the user's phone — its
 * clock *and* its time zone, so travelling across zones changes the greeting without a restart.
 */
enum class TimeOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    ;

    companion object {
        /**
         * Maps a 24-hour clock hour to a greeting. Midnight through 11:59 is morning, noon through
         * 17:59 afternoon, and 18:00 onwards evening. The small hours are deliberately "morning":
         * a night-shift worker clocking in at 2am is greeted, not told it is still evening.
         */
        fun fromHour(hourOfDay: Int): TimeOfDay = when {
            hourOfDay < AFTERNOON_START_HOUR -> MORNING
            hourOfDay < EVENING_START_HOUR -> AFTERNOON
            else -> EVENING
        }

        /** The greeting for [epochMillis] as read in [timeZone] (the device zone by default). */
        fun fromEpochMillis(
            epochMillis: Long,
            timeZone: TimeZone = TimeZone.getDefault(),
        ): TimeOfDay {
            val calendar = Calendar.getInstance(timeZone, Locale.US)
            calendar.timeInMillis = epochMillis
            return fromHour(calendar.get(Calendar.HOUR_OF_DAY))
        }

        private const val AFTERNOON_START_HOUR = 12
        private const val EVENING_START_HOUR = 18
    }
}
