package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.settings.ClockNotificationPreference
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * Settings screen. Currently hosts the auto clock-in behavior chooser — the user-switchable
 * selection between the silent / notify-with-undo / confirm strategies.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val preference by viewModel.preference.collectAsStateWithLifecycle()
    SettingsContent(
        selected = preference,
        onSelect = viewModel::onPreferenceSelected,
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        )
    }
}
