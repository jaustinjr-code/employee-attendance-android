package com.jaustinjr.employeeattendance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jaustinjr.employeeattendance.ui.attendance.AttendanceScreen
import com.jaustinjr.employeeattendance.ui.main.MainAppBar
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlinx.serialization.Serializable

@Serializable
object Attendance

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmployeeAttendanceTheme {
                val navController = rememberNavController()
                var appBarTitle by remember { mutableStateOf("Attendance") }

                Scaffold(
                    topBar = { MainAppBar(appBarTitle) }
                ) { padding ->
                    NavHost(navController, startDestination = Attendance, modifier = Modifier.padding(padding)) {
                        composable<Attendance> {
                            appBarTitle = "Attendance"
                            AttendanceScreen()
                        }
                    }
                }
            }
        }
    }
}

