package com.jaustinjr.employeeattendance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.location.ui.LocationPermissionHost
import com.jaustinjr.employeeattendance.location.ui.LocationSection
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmployeeAttendanceTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeContent(modifier = Modifier.padding(innerPadding))
                    // Hosts the location permission rationale flow and system prompt. Renders
                    // nothing until a rationale dialog is due.
                    LocationPermissionHost()
                }
            }
        }
    }
}

/**
 * Minimal home layout that hosts the retrofitted [LocationSection]. This intentionally stays small:
 * the feature under development is the location logic and its displays, not the broader attendance
 * UI.
 */
@Composable
private fun HomeContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        LocationSection()
    }
}
