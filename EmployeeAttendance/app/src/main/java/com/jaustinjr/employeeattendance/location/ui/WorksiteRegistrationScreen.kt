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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.registration.AddressSuggestion
import com.jaustinjr.employeeattendance.location.registration.RadiusOption
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import com.jaustinjr.employeeattendance.units.DistanceFormatter

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
        onRadiusOptionChange = viewModel::onRadiusOptionChange,
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
    onRadiusOptionChange: (RadiusOption) -> Unit,
    onAddressChange: (String) -> Unit,
    onSuggestionSelected: (AddressSuggestion) -> Unit,
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
            isError = state.nameError,
            supportingText = if (state.nameError) {
                { Text(stringResource(R.string.worksite_error_name_required)) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        RadiusDropdown(
            selected = state.radiusOption,
            onSelect = onRadiusOptionChange,
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
                // Guide the user: typed text is not a registered location until "Find address" is
                // tapped (or a suggestion picked). Show the required-field error first if present,
                // otherwise the "tap Find address" hint while the address is unresolved.
                val addressSupportRes: Int? = when {
                    state.addressError -> R.string.worksite_error_address_required
                    state.address.isNotBlank() && !state.hasCoordinates ->
                        R.string.worksite_address_hint
                    else -> null
                }
                OutlinedTextField(
                    value = state.address,
                    onValueChange = onAddressChange,
                    label = { Text(stringResource(R.string.worksite_address_label)) },
                    singleLine = true,
                    isError = state.addressError,
                    supportingText = addressSupportRes?.let { res -> { Text(stringResource(res)) } },
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
                // Disclosure: geocoding an address is a network call.
                Text(
                    text = stringResource(R.string.worksite_address_network_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        StatusArea(state = state)

        if (state.locationError) {
            Text(
                text = stringResource(R.string.worksite_error_location_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            // Enabled (except mid-capture) so tapping it with an incomplete form reveals the
            // per-field validation errors rather than appearing inert.
            onClick = onSave,
            enabled = state.status !is CaptureStatus.Working,
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
            LocationConfirmationOverlay(
                address = state.resolvedAddress,
                accuracyMeters = state.capturedAccuracyMeters,
            )
        }
    }
}

/**
 * Non-interactive confirmation of the resolved worksite location. Rendered as a Material 3 tonal
 * surface (an elevation overlay over a secondary container) so it clearly stands out as important,
 * while carrying no click behavior. It shows the human-readable address — not raw coordinates — and,
 * for a current-location capture, the fix accuracy so the user can decide whether to retry.
 */
@Composable
private fun LocationConfirmationOverlay(
    address: String?,
    accuracyMeters: Float?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = Icons.Filled.LocationOn, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.worksite_location_set_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = address ?: stringResource(R.string.worksite_location_captured),
                    style = MaterialTheme.typography.bodyMedium,
                )
                accuracyMeters?.let {
                    Text(
                        text = stringResource(
                            R.string.worksite_location_accuracy,
                            DistanceFormatter.format(it),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadiusDropdown(
    selected: RadiusOption,
    onSelect: (RadiusOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = stringResource(selected.labelRes()),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.worksite_radius_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                RadiusOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes())) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
        // Vivid helper text clarifying the selected radius as a real distance in the user's units.
        Text(
            text = stringResource(selected.helperRes(), DistanceFormatter.format(selected.meters)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
    }
}

private fun RadiusOption.labelRes(): Int = when (this) {
    RadiusOption.NEAR -> R.string.worksite_radius_near
    RadiusOption.DEFAULT -> R.string.worksite_radius_default
    RadiusOption.DISTANT -> R.string.worksite_radius_distant
}

private fun RadiusOption.helperRes(): Int = when (this) {
    RadiusOption.NEAR -> R.string.worksite_radius_helper_near
    RadiusOption.DEFAULT -> R.string.worksite_radius_helper_default
    RadiusOption.DISTANT -> R.string.worksite_radius_helper_distant
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
            onRadiusOptionChange = {},
            onAddressChange = {},
            onSuggestionSelected = {},
            onCaptureModeChange = {},
            onCaptureCurrent = {},
            onGeocode = {},
            onSave = {},
        )
    }
}
