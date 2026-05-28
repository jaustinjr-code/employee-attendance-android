package com.jaustinjr.employeeattendance.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun AttendanceScreen(
    attendanceViewModel: AttendanceViewModel = viewModel()
) {
    AttendanceScreen(todayDate = attendanceViewModel.getTodayDateName())
}

@Composable
fun AttendanceScreen(
    todayDate: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Greeting(todayDate = todayDate, modifier = Modifier.padding(20.dp))
        TimeCheck(modifier = Modifier.padding(20.dp))
        LocationAssist(Modifier.padding(20.dp).align(alignment = Alignment.CenterHorizontally))
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
    modifier: Modifier = Modifier
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
                    if (isClockedIn)
                        clockInTime = formattedTime
                    else
                        clockOutTime = formattedTime
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

@Composable
fun LocationAssist(modifier: Modifier = Modifier) {
    ElevatedAssistChip(
        onClick = { /* TODO open location permission dialog */ },
        label = { Text("Setup Location") },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.not_listed_location_24px),
                contentDescription = "Localized description",
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun LocationAssistPreview() {
    LocationAssist()
}