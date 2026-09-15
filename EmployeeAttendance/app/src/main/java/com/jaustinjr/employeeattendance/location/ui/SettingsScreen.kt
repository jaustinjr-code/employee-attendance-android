package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/** Test tags for the androidTest layer; not user-visible. */
object SettingsTestTags {
    const val STATUS_UPDATE_SWITCH = "settings_status_update_switch"
}

/**
 * Settings screen. Hosts the account display name (temporarily — see
 * [SettingsViewModel.displayName]), the auto clock-in behavior chooser — the user-switchable
 * selection between the silent / notify-with-undo / confirm strategies — and the privacy and data
 * controls.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val preference by viewModel.preference.collectAsStateWithLifecycle()
    val reverseGeocodeEnabled by viewModel.reverseGeocodeEnabled.collectAsStateWithLifecycle()
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val statusUpdateEnabled by viewModel.statusUpdateEnabled.collectAsStateWithLifecycle()
    SettingsContent(
        selected = preference,
        onSelect = viewModel::onPreferenceSelected,
        displayName = displayName,
        onDisplayNameChanged = viewModel::onDisplayNameChanged,
        reverseGeocodeEnabled = reverseGeocodeEnabled,
        onReverseGeocodeChanged = viewModel::onReverseGeocodeEnabledChanged,
        statusUpdateEnabled = statusUpdateEnabled,
        onStatusUpdateEnabledChanged = viewModel::onStatusUpdateEnabledChanged,
        onDeleteAllData = viewModel::onDeleteAllData,
        modifier = modifier,
    )
}

/** A behavior option's display strings, paired with the preference it selects. */
private data class BehaviorOption(
    val preference: ClockNotificationPreference,
    val titleRes: Int,
    val descriptionRes: Int,
)

private val behaviorOptions = listOf(
    BehaviorOption(
        ClockNotificationPreference.SILENT,
        R.string.settings_behavior_silent,
        R.string.settings_behavior_silent_desc,
    ),
    BehaviorOption(
        ClockNotificationPreference.NOTIFY_UNDO,
        R.string.settings_behavior_notify_undo,
        R.string.settings_behavior_notify_undo_desc,
    ),
    BehaviorOption(
        ClockNotificationPreference.CONFIRM,
        R.string.settings_behavior_confirm,
        R.string.settings_behavior_confirm_desc,
    ),
)

@Composable
fun SettingsContent(
    selected: ClockNotificationPreference,
    onSelect: (ClockNotificationPreference) -> Unit,
    displayName: String,
    onDisplayNameChanged: (String) -> Unit,
    reverseGeocodeEnabled: Boolean,
    onReverseGeocodeChanged: (Boolean) -> Unit,
    statusUpdateEnabled: Boolean,
    onStatusUpdateEnabledChanged: (Boolean) -> Unit,
    onDeleteAllData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        DeleteAllConfirmationDialog(
            onConfirm = {
                onDeleteAllData()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    Column(
        // The sections outgrow short screens; without scrolling, the last rows are squeezed into
        // the remaining height and draw over each other. Insets as in WorksiteRegistrationScreen.
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AccountSection(
            displayName = displayName,
            onDisplayNameChanged = onDisplayNameChanged,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.settings_clock_behavior_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_clock_behavior_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.selectableGroup()) {
            behaviorOptions.forEach { option ->
                BehaviorRow(
                    option = option,
                    selected = option.preference == selected,
                    onSelect = { onSelect(option.preference) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.settings_privacy_title),
            style = MaterialTheme.typography.titleMedium,
        )
        SwitchRow(
            titleRes = R.string.settings_reverse_geocode_title,
            descriptionRes = R.string.settings_reverse_geocode_desc,
            checked = reverseGeocodeEnabled,
            onCheckedChange = onReverseGeocodeChanged,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.settings_status_update_title),
            style = MaterialTheme.typography.titleMedium,
        )
        SwitchRow(
            titleRes = R.string.settings_status_update_switch_title,
            descriptionRes = R.string.settings_status_update_desc,
            checked = statusUpdateEnabled,
            onCheckedChange = onStatusUpdateEnabledChanged,
            switchTestTag = SettingsTestTags.STATUS_UPDATE_SWITCH,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.settings_data_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_data_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { showDeleteConfirm = true },
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.settings_data_delete_all))
        }
    }
}

/**
 * The account display name. Writes straight through on every keystroke — the store updates its
 * StateFlow synchronously, so the field never lags behind what was typed, and there is no draft
 * state to lose when the screen leaves composition. The text is stored as typed; the greeting
 * trims it and falls back to a default when it is blank.
 */
@Composable
private fun AccountSection(
    displayName: String,
    onDisplayNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_account_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.settings_account_name_desc,
                stringResource(R.string.greeting_default_name),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChanged,
            label = { Text(stringResource(R.string.settings_account_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeleteAllConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_data_delete_confirm_title)) },
        text = { Text(stringResource(R.string.settings_data_delete_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.settings_data_delete_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_data_delete_cancel))
            }
        },
    )
}

@Composable
private fun SwitchRow(
    titleRes: Int,
    descriptionRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchTestTag: String? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = switchTestTag?.let { Modifier.testTag(it) } ?: Modifier,
        )
    }
}

@Composable
private fun BehaviorRow(
    option: BehaviorOption,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(option.titleRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(option.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    EmployeeAttendanceTheme {
        SettingsContent(
            selected = ClockNotificationPreference.NOTIFY_UNDO,
            onSelect = {},
            displayName = "Jordan",
            onDisplayNameChanged = {},
            reverseGeocodeEnabled = true,
            onReverseGeocodeChanged = {},
            statusUpdateEnabled = true,
            onStatusUpdateEnabledChanged = {},
            onDeleteAllData = {},
        )
    }
}
