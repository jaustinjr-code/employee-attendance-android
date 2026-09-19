package com.jaustinjr.employeeattendance.statusupdate.history.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateShift
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Fresh formatters per call: SimpleDateFormat is not thread-safe. Locale.US matches the rest of the app.
private fun format(pattern: String, epochMillis: Long): String =
    SimpleDateFormat(pattern, Locale.US).format(Date(epochMillis))

/** "Today", "Yesterday", or a date such as "Monday, Sep 14, 2026". */
@Composable
fun dayLabel(dayStartMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val day = Calendar.getInstance().apply { timeInMillis = dayStartMillis }
    val today = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        day.isSameDay(today) -> stringResource(R.string.status_update_history_today)
        day.isSameDay(yesterday) -> stringResource(R.string.status_update_history_yesterday)
        else -> format("EEEE, MMM d, yyyy", dayStartMillis)
    }
}

private fun Calendar.isSameDay(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

/** "9:02 AM – 5:31 PM", or "Clocked out 5:31 PM" when the clock-in is unknown. */
@Composable
fun shiftTimeRange(shift: StatusUpdateShift): String {
    val clockOut = format("h:mm a", shift.clockOutAtMillis)
    val clockIn = shift.clockInAtMillis
        ?: return stringResource(R.string.status_update_history_clocked_out_at, clockOut)
    return stringResource(R.string.status_update_history_time_range, format("h:mm a", clockIn), clockOut)
}

/** The worksite name recorded with the shift, or "General timeclock". */
@Composable
fun shiftWorksite(shift: StatusUpdateShift): String =
    shift.worksiteName ?: stringResource(R.string.status_update_history_general_timeclock)

/** "Edited Sep 14 at 6:10 PM". */
@Composable
fun editedLabel(editedAtMillis: Long): String =
    stringResource(R.string.status_update_edited_at, format("MMM d 'at' h:mm a", editedAtMillis))
