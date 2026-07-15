package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * State-aware chip shown on the attendance screen while location setup is incomplete. It both
 * communicates the current access and, on tap, opens the permission flow. Once setup is complete
 * (access granted and a location registered) the caller shows a [LocationPill] instead.
 */
@Composable
fun LocationSetupChip(
    accessLevel: LocationAccessLevel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelRes = when (accessLevel) {
        LocationAccessLevel.NONE -> R.string.location_setup_action
        LocationAccessLevel.WHEN_IN_USE -> R.string.location_access_limited
        LocationAccessLevel.ALWAYS -> R.string.location_access_full
    }
    ElevatedAssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(stringResource(labelRes)) },
        leadingIcon = {
            val iconModifier = Modifier.size(AssistChipDefaults.IconSize)
            when (accessLevel) {
                LocationAccessLevel.NONE -> Icon(
                    painter = painterResource(R.drawable.not_listed_location_24px),
                    contentDescription = null,
                    modifier = iconModifier,
                )
                LocationAccessLevel.WHEN_IN_USE -> Icon(
                    imageVector = Icons.Outlined.LocationSearching,
                    contentDescription = null,
                    modifier = iconModifier,
                )
                LocationAccessLevel.ALWAYS -> Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    modifier = iconModifier,
                )
            }
        },
    )
}

/** A single line describing whether the user is at their work location. */
@Composable
fun ProximityStatusRow(
    proximity: ProximityState,
    modifier: Modifier = Modifier,
) {
    val icon = when (proximity) {
        ProximityState.INSIDE -> Icons.Filled.CheckCircle
        ProximityState.OUTSIDE, ProximityState.UNKNOWN -> Icons.Outlined.LocationSearching
    }
    val textRes = when (proximity) {
        ProximityState.INSIDE -> R.string.location_status_inside
        ProximityState.OUTSIDE -> R.string.location_status_outside
        ProximityState.UNKNOWN -> R.string.location_status_unknown
    }
    val tint = when (proximity) {
        ProximityState.INSIDE -> MaterialTheme.colorScheme.primary
        ProximityState.OUTSIDE, ProximityState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
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
fun DegradedNotice(modifier: Modifier = Modifier) {
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

@Preview(showBackground = true, name = "Setup chip states")
@Composable
private fun LocationSetupChipPreview() {
    EmployeeAttendanceTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocationSetupChip(accessLevel = LocationAccessLevel.NONE, onClick = {})
            LocationSetupChip(accessLevel = LocationAccessLevel.WHEN_IN_USE, onClick = {})
        }
    }
}
