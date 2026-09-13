package com.jaustinjr.employeeattendance.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.jaustinjr.employeeattendance.R

/**
 * The Material 3 navigation bar across the top-level destinations in [AppNavGraph.topLevel].
 *
 * [currentRoute] is derived from the back stack by the caller, like the app bar's title, so the
 * selected tab can never disagree with the destination on screen.
 */
@Composable
fun MainBottomBar(
    currentRoute: String?,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = destinationOrRoot(currentRoute)
    NavigationBar(modifier) {
        AppNavGraph.topLevel.forEach { destination ->
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(destination) },
                icon = { Icon(iconFor(destination, selected), contentDescription = null) },
                label = { Text(stringResource(labelFor(destination))) },
            )
        }
    }
}

private fun iconFor(destination: AppDestination, selected: Boolean): ImageVector = when (destination) {
    AppNavGraph.Reports -> if (selected) Icons.Filled.Insights else Icons.Outlined.Insights
    else -> if (selected) Icons.Filled.Schedule else Icons.Outlined.Schedule
}

private fun labelFor(destination: AppDestination): Int = when (destination) {
    AppNavGraph.Reports -> R.string.nav_reports
    else -> R.string.nav_attendance
}
