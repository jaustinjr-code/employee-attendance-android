package com.jaustinjr.employeeattendance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jaustinjr.employeeattendance.ui.home.AttendanceFragment
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmployeeAttendanceTheme {
                val navController = rememberNavController()
                // Host your navigation graph here
                NavHost(navController, startDestination = "home") {
                    composable("home") { AttendanceFragment() }
                }
            }
        }
    }
}

