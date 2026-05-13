package com.jaustinjr.employeeattendance.ui.attendance

import android.util.Log
import androidx.lifecycle.ViewModel

class AttendanceViewModel : ViewModel() {
    val message = "Hello"

    // Business logic
    fun sendMessage(message: String) { Log.d("Attendance", message)}
}