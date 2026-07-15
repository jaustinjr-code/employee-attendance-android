package com.jaustinjr.employeeattendance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jaustinjr.employeeattendance.location.ui.LocationDetailScreen
import com.jaustinjr.employeeattendance.location.ui.LocationPermissionViewModel
import com.jaustinjr.employeeattendance.location.ui.LocationViewModel
import com.jaustinjr.employeeattendance.ui.attendance.AttendanceScreen
import com.jaustinjr.employeeattendance.ui.main.MainAppBar
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlinx.serialization.Serializable

@Serializable
object Attendance

@Serializable
object LocationDetail

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmployeeAttendanceTheme {
                val navController = rememberNavController()
                var appBarTitle by remember { mutableStateOf("Attendance") }

                // Scoped to the Activity so the attendance and detail destinations share one
                // instance each — a single foreground collector and consistent permission state.
                val locationViewModel: LocationViewModel =
                    viewModel(factory = LocationViewModel.Factory)
                val locationPermissionViewModel: LocationPermissionViewModel =
                    viewModel(factory = LocationPermissionViewModel.Factory)

                Scaffold(
                    topBar = { MainAppBar(appBarTitle) }
                ) { padding ->
                    NavHost(navController, startDestination = Attendance, modifier = Modifier.padding(padding)) {
                        composable<Attendance> {
                            // side-effect: state change during composition
                            LaunchedEffect(Unit) {
                                appBarTitle = "Attendance"
                            }
                            AttendanceScreen(
                                onOpenLocationDetail = { navController.navigate(LocationDetail) },
                                locationViewModel = locationViewModel,
                                locationPermissionViewModel = locationPermissionViewModel,
                            )
                        }
                        composable<LocationDetail> {
                            val title = stringResource(R.string.location_detail_title)
                            LaunchedEffect(title) {
                                appBarTitle = title
                            }
                            LocationDetailScreen(viewModel = locationViewModel)
                        }
                    }
                }
            }
        }
    }
}
