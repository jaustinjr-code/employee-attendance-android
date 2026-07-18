package com.jaustinjr.employeeattendance.location.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme
import com.jaustinjr.employeeattendance.units.DistanceFormatter

/**
 * Lists registered worksites, lets the user mark one active or remove it, and offers an "add" action
 * (FAB) into the registration flow. This is the management surface for the Worksite feature.
 */
@Composable
fun WorksitesScreen(
    onAddWorksite: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorksitesViewModel = viewModel(factory = WorksitesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WorksitesContent(
        state = state,
        onAddWorksite = onAddWorksite,
        onSetActive = viewModel::onSetActive,
        onConfirmSwitchActive = viewModel::onConfirmSwitchActive,
        onRemove = viewModel::onRemove,
        modifier = modifier,
    )
}

@Composable
fun WorksitesContent(
    state: WorksitesUiState,
    onAddWorksite: () -> Unit,
    onSetActive: (String) -> Unit,
    onConfirmSwitchActive: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The worksite the user asked to activate while already clocked in elsewhere; triggers the
    // confirmation modal so they're aware the switch clocks them out of the current worksite.
    var pendingActivation by remember { mutableStateOf<WorkLocation?>(null) }

    pendingActivation?.let { target ->
        SwitchActiveConfirmationDialog(
            currentName = state.activeWorksite?.name.orEmpty(),
            targetName = target.name,
            onConfirm = {
                onConfirmSwitchActive(target.id)
                pendingActivation = null
            },
            onDismiss = { pendingActivation = null },
        )
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = onAddWorksite) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_worksite_add),
                )
            }
        },
    ) { padding ->
        if (state.worksites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.worksites_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.worksites, key = { it.id }) { worksite ->
                    WorksiteRow(
                        worksite = worksite,
                        isActive = worksite.id == state.activeId,
                        onSetActive = {
                            // Confirm first when the current active worksite is clocked in, since
                            // switching will clock the user out of it.
                            if (state.activeClockedIn) pendingActivation = worksite
                            else onSetActive(worksite.id)
                        },
                        onRemove = { onRemove(worksite.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorksiteRow(
    worksite: WorkLocation,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = worksite.name, style = MaterialTheme.typography.titleMedium)
                if (isActive) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.worksite_active_label)) },
                    )
                }
            }
            worksite.address?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    R.string.worksite_radius_summary,
                    DistanceFormatter.format(worksite.radiusMeters),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) {
                    TextButton(onClick = onSetActive) {
                        Text(stringResource(R.string.worksite_set_active))
                    }
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.worksite_remove))
                }
            }
        }
    }
}

/**
 * Warns the user that switching the active worksite while clocked in will immediately clock them out
 * of the current one (with a notification) before completing the transfer.
 */
@Composable
private fun SwitchActiveConfirmationDialog(
    currentName: String,
    targetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.worksite_switch_confirm_title)) },
        text = {
            Text(stringResource(R.string.worksite_switch_confirm_message, currentName, targetName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.worksite_switch_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.worksite_switch_cancel))
            }
        },
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun WorksitesPreview() {
    EmployeeAttendanceTheme {
        WorksitesContent(
            state = WorksitesUiState(
                worksites = listOf(
                    WorkLocation("a", "Downtown Office", "123 Market St", 37.7749, -122.4194, 150f),
                    WorkLocation("b", "Warehouse", null, 37.80, -122.27, 200f),
                ),
                activeId = "a",
            ),
            onAddWorksite = {},
            onSetActive = {},
            onConfirmSwitchActive = {},
            onRemove = {},
        )
    }
}
