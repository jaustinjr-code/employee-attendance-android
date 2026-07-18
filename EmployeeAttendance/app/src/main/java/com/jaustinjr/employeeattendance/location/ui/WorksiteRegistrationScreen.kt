package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * "Add worksite" form. Reached from the worksites list. On successful save it invokes [onSaved] so
 * the caller can navigate back.
 */
@Composable
fun WorksiteRegistrationScreen(
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorksiteRegistrationViewModel = viewModel(factory = WorksiteRegistrationViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    WorksiteRegistrationContent(
        state = state,
        onNameChange = viewModel::onNameChange,
        onRadiusChange = viewModel::onRadiusChange,
        onAddressChange = viewModel::onAddressChange,
        onSuggestionSelected = viewModel::onSuggestionSelected,
        onCaptureModeChange = viewModel::onCaptureModeChange,
        onCaptureCurrent = viewModel::captureCurrentLocation,
        onGeocode = viewModel::geocodeAddress,
        onSave = viewModel::save,
        modifier = modifier,
    )
}

@Composable
fun WorksiteRegistrationContent(
    state: WorksiteRegistrationUiState,
    onNameChange: (String) -> Unit,
    onRadiusChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onSuggestionSelected: (com.jaustinjr.employeeattendance.location.registration.AddressSuggestion) -> Unit,
    onCaptureModeChange: (CaptureMode) -> Unit,
    onCaptureCurrent: () -> Unit,
    onGeocode: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val working = state.status is CaptureStatus.Working
    Column(
        // verticalScroll + imePadding keep the focused text field visible above the on-screen
        // keyboard: imePadding adds bottom inset equal to the keyboard height, and the scroll lets
        // the focused field move up into the remaining space. navigationBarsPadding avoids the
        // gesture bar when the keyboard is hidden.
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.worksite_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.radiusMetersText,
            onValueChange = onRadiusChange,
            label = { Text(stringResource(R.string.worksite_radius_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.worksite_capture_mode),
            style = MaterialTheme.typography.titleSmall,
        )
        Column(Modifier.selectableGroup()) {
            CaptureModeRow(
                labelRes = R.string.worksite_capture_current,
                selected = state.captureMode == CaptureMode.CURRENT,
                onSelect = { onCaptureModeChange(CaptureMode.CURRENT) },
            )
            CaptureModeRow(
                labelRes = R.string.worksite_capture_address,
                selected = state.captureMode == CaptureMode.ADDRESS,
                onSelect = { onCaptureModeChange(CaptureMode.ADDRESS) },
            )
        }

        when (state.captureMode) {
            CaptureMode.CURRENT -> {
                OutlinedButton(
                    onClick = onCaptureCurrent,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.worksite_capture_current_action))
                }
            }
            CaptureMode.ADDRESS -> {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = onAddressChange,
                    label = { Text(stringResource(R.string.worksite_address_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.suggestions.forEach { suggestion ->
                    ListItem(
                        headlineContent = { Text(suggestion.label) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSuggestionSelected(suggestion) },
                    )
                }
                OutlinedButton(
                    onClick = onGeocode,
                    enabled = !working && state.address.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.worksite_geocode_action))
                }
            }
        }

        StatusArea(state = state)

        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.worksite_save))
        }
    }
}

@Composable
private fun StatusArea(state: WorksiteRegistrationUiState) {
    when (val status = state.status) {
        is CaptureStatus.Working -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(
                    if (state.captureMode == CaptureMode.CURRENT) R.string.worksite_capturing
                    else R.string.worksite_geocoding,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is CaptureStatus.Error -> Text(
            text = stringResource(status.messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        CaptureStatus.Idle -> if (state.hasCoordinates) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        R.string.worksite_coordinates,
                        state.latitude ?: 0.0,
                        state.longitude ?: 0.0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.resolvedAddress?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureModeRow(
    labelRes: Int,
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

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun WorksiteRegistrationPreview() {
    EmployeeAttendanceTheme {
        WorksiteRegistrationContent(
            state = WorksiteRegistrationUiState(
                name = "Downtown Office",
                latitude = 37.7749,
                longitude = -122.4194,
            ),
            onNameChange = {},
            onRadiusChange = {},
            onAddressChange = {},
            onSuggestionSelected = {},
            onCaptureModeChange = {},
            onCaptureCurrent = {},
            onGeocode = {},
            onSave = {},
        )
    }
}
