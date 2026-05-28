package com.jaustinjr.employeeattendance.ui.attendance

import android.icu.util.Calendar
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.Locale

class AttendanceViewModel : ViewModel() {
    fun getTodayDateName(): String {
        val format = SimpleDateFormat("EEEE, MMM dd", Locale.US)
        val date = Calendar.getInstance().time
        return format.format(date)
    }
}