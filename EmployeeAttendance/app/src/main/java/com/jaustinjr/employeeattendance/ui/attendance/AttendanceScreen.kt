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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
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
        onClockIn = locationViewModel::onClockIn,
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
    onClockIn: () -> Unit = {},
) {
    Column(modifier = modifier) {
        Greeting(todayDate = todayDate, modifier = Modifier.padding(20.dp))
        TimeCheck(onClockIn = onClockIn, modifier = Modifier.padding(20.dp))

        // The single location control: once setup is complete (access granted and a location
        // registered) show the location pill, which opens the detail screen. Until then, show the
        // setup chip that drives the permission flow. Nothing else lives here — the map and other
        // details are on the detail screen.
        val location = locationState.activeWorkLocation
        if (locationState.isSetUp && location != null) {
            LocationPill(
                locationName = location.name,
                onClick = onLocationPillClick,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp),
            )
        } else {
            LocationSetupChip(
                accessLevel = locationState.accessLevel,
                onClick = onLocationSetupClick,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp),
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

@Composable
fun TimeCheck(
    modifier: Modifier = Modifier,
    onClockIn: () -> Unit = {},
) {
    var isClockedIn by rememberSaveable { mutableStateOf(false) }
    var clockInTime by rememberSaveable { mutableStateOf("") }
    var clockOutTime by rememberSaveable { mutableStateOf("") }

    ElevatedCard(modifier = modifier.fillMaxWidth().height(300.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text("Current Time")
            LiveClock()
            if (clockInTime.isNotEmpty() || clockOutTime.isNotEmpty())
                Text(if (isClockedIn) "Clock In: $clockInTime" else "Clock Out: $clockOutTime")
            Button(
                shape = RoundedCornerShape(5.dp),
                onClick = {
                    isClockedIn = !isClockedIn
                    val now = Calendar.getInstance().time
                    val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
                    if (isClockedIn) {
                        clockInTime = formattedTime
                        // Record the clock-in against the active work location for the detail screen.
                        onClockIn()
                    } else {
                        clockOutTime = formattedTime
                    }
                },
                modifier = Modifier.fillMaxWidth(0.75f)
            ) {
                Text(text = if (isClockedIn) "Clock out" else "Clock in")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TimeCheckPreview() {
    EmployeeAttendanceTheme {
        TimeCheck(
            modifier = Modifier.padding(20.dp)
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
