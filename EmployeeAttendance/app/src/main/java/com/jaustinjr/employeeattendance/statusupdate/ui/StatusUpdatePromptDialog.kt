package com.jaustinjr.employeeattendance.statusupdate.ui

import android.content.res.Configuration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * The in-app "Start status update?" confirmation shown right after a clock-out while the app is in
 * the foreground. "Begin" opens the card stack; "Not now" (or any dismissal) skips this status
 * update entirely — see [StatusUpdateOverlayViewModel], there is no resurfacing.
 *
 * Follows the shape of [LocationPermissionRationaleDialog] and `SwitchActiveConfirmationDialog`
 * (`location/ui/WorksitesScreen.kt`): a stateless `AlertDialog` wrapper, all copy and both choices
 * forwarded by the caller.
 */
@Composable
fun StatusUpdatePromptDialog(
    onBegin: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onNotNow,
        modifier = modifier,
        title = { Text(stringResource(R.string.status_update_prompt_title)) },
        confirmButton = {
            TextButton(onClick = onBegin) {
                Text(stringResource(R.string.status_update_prompt_begin))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text(stringResource(R.string.status_update_prompt_not_now))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun StatusUpdatePromptDialogPreview() {
    EmployeeAttendanceTheme {
        StatusUpdatePromptDialog(onBegin = {}, onNotNow = {})
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusUpdatePromptDialogPreviewDark() {
    EmployeeAttendanceTheme {
        StatusUpdatePromptDialog(onBegin = {}, onNotNow = {})
    }
}
