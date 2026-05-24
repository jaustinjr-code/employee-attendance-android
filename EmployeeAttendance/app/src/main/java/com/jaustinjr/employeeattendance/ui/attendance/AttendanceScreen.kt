package com.jaustinjr.employeeattendance.ui.attendance

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

@Composable
fun AttendanceScreen(
    attendanceViewModel: AttendanceViewModel = viewModel()
) {
    Greeting(attendanceViewModel.message)
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EmployeeAttendanceTheme {
        Greeting("Android")
    }
}