package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen detail for the registered work location. Reached by tapping the location pill on the
 * attendance screen. Shows the registered location's name, its map (only under "Allow all the time"
 * access), the current proximity, and the last clock-in recorded for it.
 */
@Composable
fun LocationDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
    onManageWorksites: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LocationDetailContent(
        state = state,
        onManageWorksites = onManageWorksites,
        modifier = modifier,
    )
}

@Composable
fun LocationDetailContent(
    state: LocationUiState,
    modifier: Modifier = Modifier,
    onManageWorksites: () -> Unit = {},
) {
    val location = state.activeWorkLocation
    if (location == null) {
        Column(
            modifier = modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.location_detail_no_location),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onManageWorksites) {
                Text(stringResource(R.string.location_detail_manage_worksites))
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Registered location, labeled by its name.
        Text(text = location.name, style = MaterialTheme.typography.headlineSmall)
        location.address?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ProximityStatusRow(proximity = state.proximity)

        // The map is only available under full "Allow all the time" access.
        if (state.canShowMap) {
            WorkLocationMapCard(locationName = location.name)
        } else {
            MapLockedNotice()
        }

        LastClockInRow(lastClockInEpochMillis = state.lastClockInEpochMillis)
        // Only shown when the last clock-out is more recent than the last clock-in (see
        // LocationUiState.detailClockOutMillis); a newer clock-in resets it.
        state.detailClockOutMillis?.let { LastClockOutRow(lastClockOutEpochMillis = it) }

        if (state.isApproximateOnly) {
            ApproximateLocationNotice()
        }

        if (state.isDegraded) {
            DegradedNotice()
        }

        TextButton(onClick = onManageWorksites) {
            Text(stringResource(R.string.location_detail_manage_worksites))
        }
    }
}

@Composable
private fun MapLockedNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().height(120.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.location_detail_map_locked),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LastClockInRow(
    lastClockInEpochMillis: Long?,
    modifier: Modifier = Modifier,
) {
    val text = if (lastClockInEpochMillis != null) {
        val formatted = remember(lastClockInEpochMillis) { formatTimestamp(lastClockInEpochMillis) }
        stringResource(R.string.location_last_clock_in, formatted)
    } else {
        stringResource(R.string.location_no_clock_in)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}

@Composable
private fun LastClockOutRow(
    lastClockOutEpochMillis: Long,
    modifier: Modifier = Modifier,
) {
    val formatted = remember(lastClockOutEpochMillis) { formatTimestamp(lastClockOutEpochMillis) }
    Text(
        text = stringResource(R.string.location_last_clock_out, formatted),
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.US).format(Date(epochMillis))

private val previewLocation = WorkLocation(
    id = "downtown-office",
    name = "Downtown Office",
    address = "123 Market St",
    latitudeDegrees = 37.7749,
    longitudeDegrees = -122.4194,
    radiusMeters = 150f,
)

@Preview(showBackground = true, name = "Always-on + clocked in")
@Composable
private fun LocationDetailAlwaysPreview() {
    EmployeeAttendanceTheme {
        LocationDetailContent(
            state = LocationUiState(
                activeWorkLocation = previewLocation,
                proximity = ProximityState.INSIDE,
                accessLevel = LocationAccessLevel.ALWAYS,
                lastClockInEpochMillis = 1_716_552_000_000L,
            ),
        )
    }
}

@Preview(showBackground = true, name = "When-in-use (map locked)")
@Composable
private fun LocationDetailDegradedPreview() {
    EmployeeAttendanceTheme {
        LocationDetailContent(
            state = LocationUiState(
                activeWorkLocation = previewLocation,
                proximity = ProximityState.OUTSIDE,
                accessLevel = LocationAccessLevel.WHEN_IN_USE,
                lastClockInEpochMillis = null,
            ),
        )
    }
}
