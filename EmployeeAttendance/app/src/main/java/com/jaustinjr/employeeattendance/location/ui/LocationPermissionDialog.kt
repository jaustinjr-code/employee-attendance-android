package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * A Material 3 rationale dialog shown *before* the system permission prompt to explain, in plain
 * language, why the app wants location access and what the user gets in return. This transparency
 * step is required for background location and is good practice for any sensitive permission.
 *
 * The composable is intentionally stateless: it renders the given copy and forwards the two user
 * choices. Callers (see [LocationPermissionPrompt]) decide which copy to show and what each action
 * does — e.g. launching the system prompt or opening app settings.
 *
 * @param title short question-form headline, e.g. "Enable Auto Clock-In?".
 * @param description the plain-language explanation of the data use and benefit.
 * @param confirmLabel label for the primary action (proceed to grant access).
 * @param dismissLabel label for the dismissive action ("Maybe Later").
 * @param onConfirm invoked when the user accepts and wants to proceed to the system prompt.
 * @param onDismiss invoked when the user dismisses (button or scrim/back). Callers should treat any
 *   dismissal as "not now" and avoid re-prompting immediately.
 */
@Composable
fun LocationPermissionRationaleDialog(
    title: String,
    description: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.LocationOn,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.cd_location_icon),
            )
        },
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text = description, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun LocationPermissionRationaleDialogPreview() {
    EmployeeAttendanceTheme {
        LocationPermissionRationaleDialog(
            title = stringResource(R.string.location_permission_enable_title),
            description = stringResource(R.string.location_permission_enable_body),
            confirmLabel = stringResource(R.string.location_permission_enable_confirm),
            dismissLabel = stringResource(R.string.location_permission_dismiss),
            onConfirm = {},
            onDismiss = {},
        )
    }
}
