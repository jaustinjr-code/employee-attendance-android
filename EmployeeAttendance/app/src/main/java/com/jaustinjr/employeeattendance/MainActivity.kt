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
import com.jaustinjr.employeeattendance.location.ui.SettingsScreen
import com.jaustinjr.employeeattendance.location.ui.WorksiteRegistrationScreen
import com.jaustinjr.employeeattendance.location.ui.WorksitesScreen
import com.jaustinjr.employeeattendance.ui.attendance.AttendanceScreen
import com.jaustinjr.employeeattendance.ui.main.MainAppBar
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlinx.serialization.Serializable

@Serializable
object Attendance

@Serializable
object LocationDetail

@Serializable
object Worksites

@Serializable
object WorksiteRegistration

@Serializable
object Settings

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
                    topBar = {
                        MainAppBar(
                            title = appBarTitle,
                            onOpenWorksites = { navController.navigate(Worksites) },
                            onOpenSettings = { navController.navigate(Settings) },
                        )
                    }
                ) { padding ->
                    NavHost(navController, startDestination = Attendance, modifier = Modifier.padding(padding)) {
                        composable<Attendance> {
                            // side-effect: state change during composition
                            LaunchedEffect(Unit) {
                                appBarTitle = "Attendance"
                            }
                            AttendanceScreen(
                                onOpenLocationDetail = { navController.navigate(LocationDetail) },
                                onAddWorksite = { navController.navigate(WorksiteRegistration) },
                                locationViewModel = locationViewModel,
                                locationPermissionViewModel = locationPermissionViewModel,
                            )
                        }
                        composable<LocationDetail> {
                            val title = stringResource(R.string.location_detail_title)
                            LaunchedEffect(title) {
                                appBarTitle = title
                            }
                            LocationDetailScreen(
                                viewModel = locationViewModel,
                                onManageWorksites = { navController.navigate(Worksites) },
                            )
                        }
                        composable<Worksites> {
                            val title = stringResource(R.string.worksites_title)
                            LaunchedEffect(title) {
                                appBarTitle = title
                            }
                            WorksitesScreen(
                                onAddWorksite = { navController.navigate(WorksiteRegistration) },
                            )
                        }
                        composable<WorksiteRegistration> {
                            val title = stringResource(R.string.worksite_registration_title)
                            LaunchedEffect(title) {
                                appBarTitle = title
                            }
                            WorksiteRegistrationScreen(
                                onSaved = { navController.popBackStack() },
                            )
                        }
                        composable<Settings> {
                            val title = stringResource(R.string.settings_title)
                            LaunchedEffect(title) {
                                appBarTitle = title
                            }
                            SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
