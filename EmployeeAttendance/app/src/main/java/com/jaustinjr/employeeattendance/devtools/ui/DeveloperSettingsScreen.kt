package com.jaustinjr.employeeattendance.devtools.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.devtools.PermissionOverride
import com.jaustinjr.employeeattendance.location.tracking.TrackingStatus
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * Developer settings. Reachable only in debug builds, by tapping the attendance app bar title five
 * times — see [com.jaustinjr.employeeattendance.devtools.DevUnlockTapCounter].
 *
 * Every control here drives the app's real repositories rather than a mock layer, so the states it
 * produces are indistinguishable from ones reached naturally. That is the point, and also the
 * caveat: the destructive actions really do delete data.
 */
@Composable
fun DeveloperSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: DeveloperSettingsViewModel = viewModel(factory = DeveloperSettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Resolve the message inside composition (stringResource needs it) and hand the text to the
    // snackbar, so the ViewModel keeps dealing in resource ids rather than strings.
    val messageText = message?.let { current ->
        current.detail?.let { stringResource(current.textRes, it) }
            ?: stringResource(current.textRes)
    }
    LaunchedEffect(message, messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.onMessageShown()
        }
    }

    Column(modifier) {
        DeveloperSettingsContent(
            state = state,
            actions = DeveloperSettingsActions(
                onPermissionOverrideSelected = viewModel::onPermissionOverrideSelected,
                onSimulateArrival = viewModel::onSimulateArrival,
                onSimulateDeparture = viewModel::onSimulateDeparture,
                onClearProximity = viewModel::onClearProximity,
                onSimulateLocationAtWorksite = viewModel::onSimulateLocationAtWorksite,
                onSimulateLocationAway = viewModel::onSimulateLocationAway,
                onTrackingStatusSelected = viewModel::onTrackingStatusSelected,
                onSeedSampleWorksite = viewModel::onSeedSampleWorksite,
                onClearWorksites = viewModel::onClearWorksites,
                onForceClockIn = viewModel::onForceClockIn,
                onForceClockOut = viewModel::onForceClockOut,
                onClearAttendance = viewModel::onClearAttendance,
                onPostNotification = viewModel::onPostNotification,
                onLogRecipientChanged = viewModel::onLogRecipientChanged,
                onExportLog = viewModel::onExportLog,
                onResetDeveloperConfiguration = viewModel::onResetDeveloperConfiguration,
            ),
            modifier = Modifier.weight(1f),
        )
        SnackbarHost(snackbarHostState)
    }
}

/**
 * The screen's callbacks, bundled so the stateless content and its preview don't need a
 * seventeen-parameter signature. Every field defaults to a no-op for previews and tests.
 */
data class DeveloperSettingsActions(
    val onPermissionOverrideSelected: (PermissionOverride) -> Unit = {},
    val onSimulateArrival: () -> Unit = {},
    val onSimulateDeparture: () -> Unit = {},
    val onClearProximity: () -> Unit = {},
    val onSimulateLocationAtWorksite: () -> Unit = {},
    val onSimulateLocationAway: () -> Unit = {},
    val onTrackingStatusSelected: (TrackingStatus) -> Unit = {},
    val onSeedSampleWorksite: () -> Unit = {},
    val onClearWorksites: () -> Unit = {},
    val onForceClockIn: () -> Unit = {},
    val onForceClockOut: () -> Unit = {},
    val onClearAttendance: () -> Unit = {},
    val onPostNotification: (ClockType, Boolean, Boolean) -> Unit = { _, _, _ -> },
    val onLogRecipientChanged: (String) -> Unit = {},
    val onExportLog: () -> Unit = {},
    val onResetDeveloperConfiguration: () -> Unit = {},
)

@Composable
fun DeveloperSettingsContent(
    state: DeveloperSettingsUiState,
    actions: DeveloperSettingsActions,
    modifier: Modifier = Modifier,
) {
    var showResetConfirm by remember { mutableStateOf(false) }
    if (showResetConfirm) {
        ConfirmDialog(
            titleRes = R.string.dev_reset_confirm_title,
            messageRes = R.string.dev_reset_confirm_message,
            confirmRes = R.string.dev_reset_confirm_action,
            onConfirm = {
                actions.onResetDeveloperConfiguration()
                showResetConfirm = false
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.dev_settings_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        DevSection(R.string.dev_section_state)
        CurrentStateSummary(state)

        DevSection(R.string.dev_section_permission)
        DevSectionHint(R.string.dev_permission_hint)
        Column(Modifier.selectableGroup()) {
            PermissionOverride.entries.forEach { option ->
                OverrideRow(
                    labelRes = option.labelRes(),
                    selected = option == state.permissionOverride,
                    onSelect = { actions.onPermissionOverrideSelected(option) },
                )
            }
        }

        DevSection(R.string.dev_section_proximity)
        DevSectionHint(R.string.dev_proximity_hint)
        DevActionRow {
            DevButton(R.string.dev_simulate_arrival, state.canSimulateWorksiteEvents, onClick = actions.onSimulateArrival)
            DevButton(R.string.dev_simulate_departure, state.canSimulateWorksiteEvents, onClick = actions.onSimulateDeparture)
            DevButton(R.string.dev_clear_proximity, onClick = actions.onClearProximity)
        }
        DevActionRow {
            DevButton(
                R.string.dev_fix_at_worksite,
                state.canSimulateWorksiteEvents,
                onClick = actions.onSimulateLocationAtWorksite,
            )
            DevButton(
                R.string.dev_fix_away,
                state.canSimulateWorksiteEvents,
                onClick = actions.onSimulateLocationAway,
            )
        }

        DevSection(R.string.dev_section_tracking)
        DevActionRow {
            TrackingStatus.entries.forEach { status ->
                DevButton(
                    labelRes = status.labelRes(),
                    onClick = { actions.onTrackingStatusSelected(status) },
                )
            }
        }

        DevSection(R.string.dev_section_worksites)
        DevActionRow {
            DevButton(R.string.dev_seed_worksite, onClick = actions.onSeedSampleWorksite)
            DevButton(R.string.dev_clear_worksites, destructive = true, onClick = actions.onClearWorksites)
        }

        DevSection(R.string.dev_section_attendance)
        DevActionRow {
            DevButton(R.string.dev_force_clock_in, onClick = actions.onForceClockIn)
            DevButton(R.string.dev_force_clock_out, onClick = actions.onForceClockOut)
            DevButton(R.string.dev_clear_attendance, destructive = true, onClick = actions.onClearAttendance)
        }

        DevSection(R.string.dev_section_notifications)
        DevSectionHint(R.string.dev_notifications_hint)
        DevActionRow {
            DevButton(R.string.dev_notify_clocked_in, state.canSimulateWorksiteEvents) {
                actions.onPostNotification(ClockType.CLOCK_IN, true, false)
            }
            DevButton(R.string.dev_notify_clocked_out, state.canSimulateWorksiteEvents) {
                actions.onPostNotification(ClockType.CLOCK_OUT, true, false)
            }
            DevButton(R.string.dev_notify_confirm_in, state.canSimulateWorksiteEvents) {
                actions.onPostNotification(ClockType.CLOCK_IN, false, true)
            }
            DevButton(R.string.dev_notify_confirm_out, state.canSimulateWorksiteEvents) {
                actions.onPostNotification(ClockType.CLOCK_OUT, false, true)
            }
        }

        DevSection(R.string.dev_section_log)
        DevSectionHint(R.string.dev_log_hint)
        OutlinedTextField(
            value = state.logRecipient,
            onValueChange = actions.onLogRecipientChanged,
            label = { Text(stringResource(R.string.dev_log_recipient_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = actions.onExportLog, enabled = !state.isExportingLog) {
                Text(stringResource(R.string.dev_log_export_action))
            }
            if (state.isExportingLog) {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        DevSection(R.string.dev_section_reset)
        DevSectionHint(R.string.dev_reset_hint)
        TextButton(
            onClick = { showResetConfirm = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.dev_reset_action))
        }
    }
}

/** Read-only mirror of the state the controls above steer, so an action's effect is visible here. */
@Composable
private fun CurrentStateSummary(state: DeveloperSettingsUiState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SummaryLine(R.string.dev_state_permission, "${state.accessLevel} precise=${state.isPrecise}")
        SummaryLine(R.string.dev_state_tracking, state.trackingStatus.name)
        SummaryLine(R.string.dev_state_proximity, state.proximity.name)
        SummaryLine(
            R.string.dev_state_worksite,
            state.activeWorksiteName ?: stringResource(R.string.dev_state_none),
        )
        SummaryLine(R.string.dev_state_clocked_in, state.isClockedIn.toString())
        if (state.hasActiveOverrides) {
            Text(
                text = stringResource(R.string.dev_state_overridden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SummaryLine(@StringRes labelRes: Int, value: String) {
    Text(
        text = stringResource(R.string.dev_state_line, stringResource(labelRes), value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DevSection(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun DevSectionHint(@StringRes textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevActionRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun DevButton(
    @StringRes labelRes: Int,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(stringResource(labelRes))
    }
}

@Composable
private fun OverrideRow(
    @StringRes labelRes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConfirmDialog(
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    @StringRes confirmRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dev_reset_cancel))
            }
        },
    )
}

@StringRes
private fun PermissionOverride.labelRes(): Int = when (this) {
    PermissionOverride.OFF -> R.string.dev_permission_off
    PermissionOverride.DENIED -> R.string.dev_permission_denied
    PermissionOverride.WHEN_IN_USE_APPROXIMATE -> R.string.dev_permission_when_in_use_approximate
    PermissionOverride.WHEN_IN_USE_PRECISE -> R.string.dev_permission_when_in_use_precise
    PermissionOverride.ALWAYS_APPROXIMATE -> R.string.dev_permission_always_approximate
    PermissionOverride.ALWAYS_PRECISE -> R.string.dev_permission_always_precise
}

@StringRes
private fun TrackingStatus.labelRes(): Int = when (this) {
    TrackingStatus.STOPPED -> R.string.dev_tracking_stopped
    TrackingStatus.FOREGROUND_ONLY -> R.string.dev_tracking_foreground
    TrackingStatus.BACKGROUND_ACTIVE -> R.string.dev_tracking_background
}

@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun DeveloperSettingsPreview() {
    EmployeeAttendanceTheme {
        DeveloperSettingsContent(
            state = DeveloperSettingsUiState(
                permissionOverride = PermissionOverride.ALWAYS_PRECISE,
                logRecipient = "dev@example.com",
                trackingStatus = TrackingStatus.BACKGROUND_ACTIVE,
                activeWorksiteName = "Downtown Office",
                isClockedIn = true,
            ),
            actions = DeveloperSettingsActions(),
        )
    }
}
