package com.jaustinjr.employeeattendance

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jaustinjr.employeeattendance.devtools.DevUnlockTapCounter
import com.jaustinjr.employeeattendance.devtools.ui.DeveloperSettingsScreen
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
import com.jaustinjr.employeeattendance.ui.main.isChildDestination
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

/**
 * Developer settings. Registered as a destination only in debug builds, and reachable only through
 * the hidden five-tap gesture on the attendance app bar title.
 */
@Serializable
object DeveloperSettings

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
                    val currentRoute = currentEntry?.destination?.route
                    val appBarTitle = stringResource(appBarTitleResFor(currentRoute))

                    // Attendance is the root; every other destination is a child of it. The app bar
                    // shows an up button on children and the account affordance on the root, from
                    // the same single source of truth as the title.
                    val showUpButton = isChildDestination(currentRoute)

                    // The developer-settings unlock. Offered only on the attendance destination —
                    // which is exactly the root, so it reuses the same derivation as the up button
                    // rather than testing the route a second way — and only in a debug build. In
                    // release `onTitleClick` stays null and the title is an ordinary,
                    // non-interactive label.
                    val devTapCounter = remember { DevUnlockTapCounter() }
                    val onTitleClick: (() -> Unit)? =
                        if (BuildConfig.DEBUG && !showUpButton) {
                            {
                                // elapsedRealtime, not wall clock: the run must not be broken (or
                                // spuriously extended) by a clock change mid-gesture.
                                if (devTapCounter.onTap(SystemClock.elapsedRealtime())) {
                                    navController.navigate(DeveloperSettings)
                                }
                            }
                        } else {
                            null
                        }

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
                                showUpButton = showUpButton,
                                // A plain pop, so up goes to the destination the user came from
                                // rather than jumping to the root.
                                onNavigateUp = { navController.popBackStack() },
                                // launchSingleTop: the overflow menu is on every destination, so
                                // picking the one already on screen would otherwise push a
                                // duplicate that up has to be pressed twice to get past.
                                onOpenWorksites = {
                                    navController.navigate(Worksites) { launchSingleTop = true }
                                },
                                onOpenSettings = {
                                    navController.navigate(Settings) { launchSingleTop = true }
                                },
                                onTitleClick = onTitleClick,
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
                            if (BuildConfig.DEBUG) {
                                composable<DeveloperSettings> {
                                    DeveloperSettingsScreen()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
