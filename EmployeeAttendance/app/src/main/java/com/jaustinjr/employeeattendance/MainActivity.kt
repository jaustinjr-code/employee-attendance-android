package com.jaustinjr.employeeattendance

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.jaustinjr.employeeattendance.account.ui.AccountScreen
import com.jaustinjr.employeeattendance.devtools.DevUnlockTapCounter
import com.jaustinjr.employeeattendance.devtools.ui.DeveloperSettingsScreen
import com.jaustinjr.employeeattendance.location.ui.LocationDetailScreen
import com.jaustinjr.employeeattendance.location.ui.LocationPermissionViewModel
import com.jaustinjr.employeeattendance.location.ui.LocationViewModel
import com.jaustinjr.employeeattendance.location.ui.SettingsScreen
import com.jaustinjr.employeeattendance.location.ui.WorksiteRegistrationScreen
import com.jaustinjr.employeeattendance.location.ui.WorksitesScreen
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateIntents
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateRequest
import com.jaustinjr.employeeattendance.statusupdate.history.ui.StatusUpdateDetailScreen
import com.jaustinjr.employeeattendance.statusupdate.history.ui.StatusUpdateEditScreen
import com.jaustinjr.employeeattendance.statusupdate.ui.StatusUpdateOverlayHost
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

/** The user's account: their display name and past status updates. */
@Serializable
object Account

/** One past status update, read-only. [clockOutId] names the shift it belongs to. */
@Serializable
data class StatusUpdateDetail(val clockOutId: String)

/** Editing one past status update. [clockOutId] names the shift it belongs to. */
@Serializable
data class StatusUpdateEdit(val clockOutId: String)

/**
 * Developer settings. Registered as a destination only in debug builds, and reachable only through
 * the hidden five-tap gesture on the attendance app bar title.
 */
@Serializable
object DeveloperSettings

class MainActivity : ComponentActivity() {

    // A notification tap's Status Update request, captured here rather than read from `intent`
    // directly in composition: a Factory can't be constructed before StartupGate opens (see the
    // comment below), so the request has to wait outside Compose until then. Populated only on a
    // fresh intent (`onCreate` with no saved state, or `onNewIntent`) and cleared once
    // StatusUpdateOverlayHost consumes it, so a later configuration change does not reopen it —
    // on a config change, `onCreate` runs again but with `savedInstanceState != null`, so this
    // stays at its default `null`.
    private var pendingStatusUpdateRequest by mutableStateOf<StatusUpdateRequest?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            pendingStatusUpdateRequest = StatusUpdateIntents.consumeRequest(intent)
        }
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
                    //
                    // The countdown toast is not decoration: without it the gesture gives no sign
                    // it is working, so someone tapping deliberately pauses between taps, silently
                    // restarts the run, and concludes the feature is broken. See
                    // DevUnlockTapCounter's note on the window.
                    val devTapCounter = remember { DevUnlockTapCounter() }
                    val context = LocalContext.current
                    // Resolved in composition rather than in the click lambda: reading resources
                    // off LocalContext there survives lint but not a configuration change, so the
                    // strings would go stale after a locale switch.
                    val unlockedMessage = stringResource(R.string.dev_unlock_opened)
                    val progressMessages =
                        (1..DevUnlockTapCounter.FEEDBACK_THRESHOLD_TAPS).map { taps ->
                            pluralStringResource(R.plurals.dev_unlock_progress, taps, taps)
                        }
                    val onTitleClick: (() -> Unit)? =
                        if (BuildConfig.DEBUG && !showUpButton) {
                            {
                                // elapsedRealtime, not wall clock: the run must not be broken (or
                                // spuriously extended) by a clock change mid-gesture.
                                if (devTapCounter.onTap(SystemClock.elapsedRealtime())) {
                                    Toast.makeText(context, unlockedMessage, Toast.LENGTH_SHORT)
                                        .show()
                                    navController.navigate(DeveloperSettings)
                                } else if (devTapCounter.shouldShowProgress) {
                                    val remaining = devTapCounter.remainingTaps
                                    Toast.makeText(
                                        context,
                                        progressMessages[remaining - 1],
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } else {
                            null
                        }

                    // Leaving the attendance screen abandons a half-finished run, so taps from an
                    // earlier visit can't combine with later ones into an accidental unlock.
                    LaunchedEffect(showUpButton) {
                        if (showUpButton) devTapCounter.reset()
                    }

                    // Scoped to the Activity so the attendance and detail destinations share one
                    // instance each — a single foreground collector and consistent permission
                    // state.
                    val locationViewModel: LocationViewModel =
                        viewModel(factory = LocationViewModel.Factory)
                    val locationPermissionViewModel: LocationPermissionViewModel =
                        viewModel(factory = LocationPermissionViewModel.Factory)

                    // The Status Update overlay is a sibling of the Scaffold, not part of the
                    // NavHost: it must render over whatever destination is current without
                    // navigating to or disturbing it. See StatusUpdateOverlayHost's doc.
                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            topBar = {
                                MainAppBar(
                                    title = appBarTitle,
                                    showUpButton = showUpButton,
                                    // Dispatched as a back press rather than a direct pop, so a
                                    // screen with its own BackHandler (the status update editor's
                                    // discard confirmation) intercepts up exactly as it does system
                                    // back. With no such handler, the NavHost's own back handling
                                    // pops one entry, so up still goes to where the user came from.
                                    onNavigateUp = { onBackPressedDispatcher.onBackPressed() },
                                    onOpenAccount = {
                                        navController.navigate(Account) { launchSingleTop = true }
                                    },
                                    // launchSingleTop: the overflow menu is on every destination,
                                    // so picking the one already on screen would otherwise push a
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
                                composable<Account> {
                                    AccountScreen(
                                        onOpenStatusUpdate = { clockOutId ->
                                            navController.navigate(StatusUpdateDetail(clockOutId))
                                        },
                                    )
                                }
                                composable<StatusUpdateDetail> { entry ->
                                    val clockOutId = entry.toRoute<StatusUpdateDetail>().clockOutId
                                    StatusUpdateDetailScreen(
                                        onEdit = { navController.navigate(StatusUpdateEdit(clockOutId)) },
                                    )
                                }
                                composable<StatusUpdateEdit> {
                                    StatusUpdateEditScreen(
                                        onSaved = { navController.popBackStack() },
                                        onExit = { navController.popBackStack() },
                                    )
                                }
                                if (BuildConfig.DEBUG) {
                                    composable<DeveloperSettings> {
                                        DeveloperSettingsScreen()
                                    }
                                }
                            }
                        }

                        StatusUpdateOverlayHost(
                            pendingNotificationRequest = pendingStatusUpdateRequest,
                            onNotificationRequestConsumed = { pendingStatusUpdateRequest = null },
                        )
                    }
                }
            }
        }
    }

    // Delivers a notification tap while the Activity is already running (warm start). Requires
    // launchMode="singleTop" on this Activity's manifest entry, so the tap reaches onNewIntent
    // instead of creating a second instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingStatusUpdateRequest = StatusUpdateIntents.consumeRequest(intent)
    }
}
