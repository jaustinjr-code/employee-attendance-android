package com.jaustinjr.employeeattendance.ui.attendance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

@Composable
fun AttendanceScreen(
    attendanceViewModel: AttendanceViewModel = viewModel()
) {
    Greeting(todayDate = attendanceViewModel.getTodayDateName(), modifier = Modifier.padding(20.dp))
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