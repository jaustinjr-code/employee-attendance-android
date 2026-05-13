package com.jaustinjr.employeeattendance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jaustinjr.employeeattendance.ui.attendance.AttendanceScreen
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlinx.serialization.Serializable

@Serializable
object Attendance

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmployeeAttendanceTheme {
                val navController = rememberNavController()
                // Host your navigation graph here
                NavHost(navController, startDestination = Attendance) {
                    composable<Attendance> { AttendanceScreen() }
                }
            }
        }
    }
}

