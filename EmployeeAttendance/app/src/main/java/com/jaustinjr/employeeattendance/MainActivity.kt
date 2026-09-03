package com.jaustinjr.employeeattendance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jaustinjr.employeeattendance.location.ui.LocationDetailScreen
import com.jaustinjr.employeeattendance.location.ui.LocationPermissionViewModel
import com.jaustinjr.employeeattendance.location.ui.LocationViewModel
import com.jaustinjr.employeeattendance.location.ui.SettingsScreen
import com.jaustinjr.employeeattendance.location.ui.WorksiteRegistrationScreen
import com.jaustinjr.employeeattendance.location.ui.WorksitesScreen
import com.jaustinjr.employeeattendance.ui.attendance.AttendanceScreen
import com.jaustinjr.employeeattendance.ui.main.MainAppBar
import com.jaustinjr.employeeattendance.ui.main.StartupGate
import com.jaustinjr.employeeattendance.ui.main.appBarTitleResFor
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
        val application = application as EmployeeAttendanceApplication
        setContent {
            EmployeeAttendanceTheme {
                // Gate every ViewModel construction on startup wiring being finished (issue #58).
                //
                // The factories below read EncryptedSharedPreferences-backed repositories out of
                // the container. On a cold start those are still being built on an IO worker, and
                // because the container's fields are SYNCHRONIZED `by lazy`, touching one here
                // would block the main thread on a monitor held by that worker — main is
                // priority-boosted and the IO worker is not, so the stall can outlast simply doing
                // the work inline. A Factory cannot suspend, so the wait happens above them, where
                // it costs a rendered frame rather than a blocked one.
                //
                // startupComplete flips on any terminal state of the startup job, failure included,
                // so this cannot strand the user on a loading screen.
                val started by application.startupComplete.collectAsStateWithLifecycle()
                StartupGate(started) {
                    val navController = rememberNavController()

                    // The title is derived from the back stack, not pushed by each screen. See
                    // appBarTitleResFor: the app bar sits outside the NavHost, and predictive back
                    // keeps two destinations composed at once, so a push-based title depends on the
                    // ordering of two screens' side effects and can end up showing the screen the
                    // user just left.
                    val currentEntry by navController.currentBackStackEntryAsState()
                    val appBarTitle =
                        stringResource(appBarTitleResFor(currentEntry?.destination?.route))

                    // Scoped to the Activity so the attendance and detail destinations share one
                    // instance each — a single foreground collector and consistent permission
                    // state.
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
                        NavHost(
                            navController,
                            startDestination = Attendance,
                            modifier = Modifier.padding(padding),
                        ) {
                            composable<Attendance> {
                                AttendanceScreen(
                                    onOpenLocationDetail = { navController.navigate(LocationDetail) },
                                    onAddWorksite = { navController.navigate(WorksiteRegistration) },
                                    locationViewModel = locationViewModel,
                                    locationPermissionViewModel = locationPermissionViewModel,
                                )
                            }
                            composable<LocationDetail> {
                                LocationDetailScreen(
                                    viewModel = locationViewModel,
                                    onManageWorksites = { navController.navigate(Worksites) },
                                )
                            }
                            composable<Worksites> {
                                WorksitesScreen(
                                    onAddWorksite = { navController.navigate(WorksiteRegistration) },
                                )
                            }
                            composable<WorksiteRegistration> {
                                WorksiteRegistrationScreen(
                                    onSaved = { navController.popBackStack() },
                                )
                            }
                            composable<Settings> {
                                SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
