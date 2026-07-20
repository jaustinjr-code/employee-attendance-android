package com.jaustinjr.employeeattendance.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.ui.AddWorksiteChip
import com.jaustinjr.employeeattendance.location.ui.ApproximateLocationNotice
import com.jaustinjr.employeeattendance.location.ui.LocationPermissionHost
import com.jaustinjr.employeeattendance.location.ui.LocationPermissionViewModel
import com.jaustinjr.employeeattendance.location.ui.LocationPill
import com.jaustinjr.employeeattendance.location.ui.LocationSetupChip
import com.jaustinjr.employeeattendance.location.ui.LocationUiState
import com.jaustinjr.employeeattendance.location.ui.LocationViewModel
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import android.icu.util.Calendar
import java.util.Locale

@Composable
fun AttendanceScreen(
    onOpenLocationDetail: () -> Unit = {},
    onAddWorksite: () -> Unit = {},
    attendanceViewModel: AttendanceViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
    locationPermissionViewModel: LocationPermissionViewModel =
        viewModel(factory = LocationPermissionViewModel.Factory),
) {
    val locationState by locationViewModel.uiState.collectAsStateWithLifecycle()
    AttendanceScreen(
        todayDate = attendanceViewModel.getTodayDateName(),
        locationState = locationState,
        onLocationSetupClick = locationPermissionViewModel::onSetupRequested,
        onLocationPillClick = onOpenLocationDetail,
        onAddWorksite = onAddWorksite,
        onClockIn = locationViewModel::onClockIn,
        onClockOut = locationViewModel::onClockOut,
    )
    // Shares the same ViewModel instance as the setup chip, so tapping it surfaces the rationale
    // dialog that this host renders.
    LocationPermissionHost(viewModel = locationPermissionViewModel)
}

@Composable
fun AttendanceScreen(
    todayDate: String,
    locationState: LocationUiState,
    modifier: Modifier = Modifier,
    onLocationSetupClick: () -> Unit = {},
    onLocationPillClick: () -> Unit = {},
    onAddWorksite: () -> Unit = {},
    onClockIn: () -> Unit = {},
    onClockOut: () -> Unit = {},
) {
    Column(modifier = modifier) {
        Greeting(todayDate = todayDate, modifier = Modifier.padding(20.dp))
        TimeCheck(
            isClockedIn = locationState.isClockedIn,
            clockInMillis = locationState.attendanceClockInMillis,
            clockOutMillis = locationState.attendanceClockOutMillis,
            onClockIn = onClockIn,
            onClockOut = onClockOut,
            modifier = Modifier.padding(20.dp),
        )

        // The single location control, in three stages:
        //  - setup complete (access granted AND a worksite registered) -> pill to the detail screen;
        //  - access granted but no worksite yet -> "Add worksite" chip into registration;
        //  - no access -> setup chip that drives the permission flow.
        val location = locationState.activeWorkLocation
        val controlModifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(horizontal = 20.dp)
        when {
            locationState.isSetUp && location != null -> LocationPill(
                locationName = location.name,
                onClick = onLocationPillClick,
                modifier = controlModifier,
            )
            locationState.isGranted -> AddWorksiteChip(
                onClick = onAddWorksite,
                modifier = controlModifier,
            )
            else -> LocationSetupChip(
                accessLevel = locationState.accessLevel,
                onClick = onLocationSetupClick,
                modifier = controlModifier,
            )
        }

        // Heads-up when only Approximate location is granted: auto clock-in is unreliable, manual
        // still works.
        if (locationState.isApproximateOnly) {
            ApproximateLocationNotice(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun Greeting(todayDate: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
    ) {
        Text(text = todayDate)
        Text(text = "Good morning, Superstar", fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 1.em)
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EmployeeAttendanceTheme {
        Greeting(
            todayDate = "Sunday, May 24",
            modifier = Modifier.padding(20.dp)
        )
    }
}

/**
 * The clock card. Its clocked-in/out status and button reflect the *actual* persisted attendance for
 * the active worksite (not local UI state), so an automatic clock-in shows as "Clocked in at …" when
 * the app is opened. [clockInMillis] is shown while clocked in; [clockOutMillis] is only ever a
 * manual clock-out (automatic ones are surfaced via notification and the worksite detail instead).
 */
@Composable
fun TimeCheck(
    isClockedIn: Boolean,
    clockInMillis: Long?,
    clockOutMillis: Long?,
    modifier: Modifier = Modifier,
    onClockIn: () -> Unit = {},
    onClockOut: () -> Unit = {},
) {
    ElevatedCard(modifier = modifier.fillMaxWidth().height(300.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text("Current Time")
            LiveClock()
            val statusText = when {
                isClockedIn && clockInMillis != null ->
                    stringResource(R.string.attendance_clocked_in_status, formatClockTime(clockInMillis))
                clockOutMillis != null ->
                    stringResource(R.string.attendance_clocked_out_status, formatClockTime(clockOutMillis))
                else -> null
            }
            statusText?.let { Text(it) }
            Button(
                shape = RoundedCornerShape(5.dp),
                onClick = { if (isClockedIn) onClockOut() else onClockIn() },
                modifier = Modifier.fillMaxWidth(0.75f)
            ) {
                Text(text = if (isClockedIn) "Clock out" else "Clock in")
            }
        }
    }
}

private fun formatClockTime(epochMillis: Long): String =
    SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(epochMillis))

@Preview(showBackground = true)
@Composable
fun TimeCheckPreview() {
    EmployeeAttendanceTheme {
        TimeCheck(
            isClockedIn = true,
            clockInMillis = 1_716_552_000_000L,
            clockOutMillis = null,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
fun LiveClock(modifier: Modifier = Modifier) {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    var timeText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Calendar.getInstance().time
            timeText = formatter.format(now)
            delay(1000) // Update every secondary
        }
    }

    Text(text = timeText, fontWeight = FontWeight.Bold, fontSize = 10.em, modifier = modifier)
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
fun AttendanceScreenPreview() {
    EmployeeAttendanceTheme {
        AttendanceScreen(
            todayDate = "Sunday, May 24",
            locationState = LocationUiState(
                activeWorkLocation = WorkLocation(
                    id = "downtown-office",
                    name = "Downtown Office",
                    address = "123 Market St",
                    latitudeDegrees = 37.7749,
                    longitudeDegrees = -122.4194,
                    radiusMeters = 150f,
                ),
                proximity = ProximityState.INSIDE,
                accessLevel = LocationAccessLevel.ALWAYS,
            ),
        )
    }
}
