package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * A card that previews the active work location on a map, mirroring the dashboard design: a map
 * surface with a centered pin and the location name on a chip.
 *
 * The map surface itself is a lightweight Material placeholder rather than a live, pannable map:
 * embedding Google Maps requires a Maps SDK API key and build wiring that is out of scope for the
 * location-logic feature. Swapping this Box for a `GoogleMap` composable is the intended follow-up
 * once a key is provisioned; the surrounding card and data flow stay the same.
 *
 * @param locationName label shown on the map chip.
 * @param modifier layout modifier.
 */
@Composable
fun WorkLocationMapCard(
    locationName: String,
    modifier: Modifier = Modifier,
) {
    val mapDescription = stringResource(R.string.cd_map)
    Card(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(mapPlaceholderBrush())
                .semantics { contentDescription = mapDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null, // described by the parent Box semantics
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    text = locationName,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun mapPlaceholderBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.secondaryContainer,
    ),
)

@Preview(showBackground = true)
@Composable
private fun WorkLocationMapCardPreview() {
    EmployeeAttendanceTheme {
        WorkLocationMapCard(
            locationName = "Downtown Office",
            modifier = Modifier.padding(16.dp),
        )
    }
}
