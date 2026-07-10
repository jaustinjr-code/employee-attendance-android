package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * The location feature's on-screen summary, intended to be dropped into the existing home/dashboard
 * layout (it does not introduce a screen of its own). Shows the active work-location pill, a
 * proximity status line, the map preview card, and — under When-In-Use access — the degraded-mode
 * notice.
 */
@Composable
fun LocationSection(
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.location_section_title),
            style = MaterialTheme.typography.titleMedium,
        )

        val location = uiState.activeWorkLocation
        if (location != null) {
            LocationPill(locationName = location.name)
            ProximityStatusRow(proximity = uiState.proximity)
            WorkLocationMapCard(locationName = location.name)
        } else {
            Text(
                text = stringResource(R.string.location_status_no_location),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiState.isDegraded) {
            DegradedNotice()
        }
    }
}

@Composable
private fun ProximityStatusRow(
    proximity: ProximityState,
    modifier: Modifier = Modifier,
) {
    val (icon, textRes, tint) = when (proximity) {
        ProximityState.INSIDE -> Triple(
            Icons.Filled.CheckCircle,
            R.string.location_status_inside,
            MaterialTheme.colorScheme.primary,
        )
        ProximityState.OUTSIDE -> Triple(
            Icons.Outlined.LocationSearching,
            R.string.location_status_outside,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProximityState.UNKNOWN -> Triple(
            Icons.Outlined.LocationSearching,
            R.string.location_status_unknown,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // status conveyed by the adjacent text
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Persistent notice shown when only When-In-Use location is granted. */
@Composable
private fun DegradedNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.location_permission_degraded_notice),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProximityStatusRowPreview() {
    EmployeeAttendanceTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProximityStatusRow(proximity = ProximityState.INSIDE)
            ProximityStatusRow(proximity = ProximityState.OUTSIDE)
            ProximityStatusRow(proximity = ProximityState.UNKNOWN)
        }
    }
}
