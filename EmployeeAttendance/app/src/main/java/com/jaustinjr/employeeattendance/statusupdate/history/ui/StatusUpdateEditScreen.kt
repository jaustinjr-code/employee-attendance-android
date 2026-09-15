package com.jaustinjr.employeeattendance.statusupdate.history.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateEditUiState
import com.jaustinjr.employeeattendance.statusupdate.history.StatusUpdateEditViewModel
import com.jaustinjr.employeeattendance.statusupdate.ui.StatusUpdateQuestion
import com.jaustinjr.employeeattendance.ui.theme.EmployeeAttendanceTheme

/**
 * Editing one past status update. Opening this screen counts as having made changes: Cancel,
 * system back and the app bar's up button all ask before discarding (up reaches the [BackHandler]
 * because `MainActivity` dispatches it as a back press). Saving writes the answers and calls
 * [onSaved], which returns to the read-only screen.
 */
@Composable
fun StatusUpdateEditScreen(
    onSaved: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusUpdateEditViewModel = viewModel(factory = StatusUpdateEditViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Nothing to lose when the update no longer exists, so back leaves directly.
    BackHandler(enabled = state.found, onBack = viewModel::onExitRequested)

    StatusUpdateEditContent(
        state = state,
        onDraftChanged = viewModel::onDraftChanged,
        onSave = { if (viewModel.save()) onSaved() },
        onCancel = viewModel::onExitRequested,
        modifier = modifier,
    )

    if (state.showDiscardDialog) {
        DiscardChangesDialog(
            onDiscard = {
                viewModel.onDiscardDialogDismissed()
                onExit()
            },
            onKeepEditing = viewModel::onDiscardDialogDismissed,
        )
    }
}

@Composable
fun StatusUpdateEditContent(
    state: StatusUpdateEditUiState,
    onDraftChanged: (index: Int, value: String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.found) {
            Text(
                text = stringResource(R.string.status_update_detail_missing),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusUpdateQuestion.entries.forEachIndexed { index, question ->
                OutlinedTextField(
                    value = state.drafts[index],
                    onValueChange = { onDraftChanged(index, it) },
                    label = { Text(stringResource(question.questionRes)) },
                    placeholder = { Text(stringResource(question.hintRes)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Next to the buttons rather than under the fields, so the keyboard can't hide why Save is off.
        if (!state.canSave) {
            Text(
                text = stringResource(R.string.status_update_edit_needs_answer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.status_update_edit_cancel))
            }
            Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.status_update_edit_save))
            }
        }
    }
}

@Composable
private fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.status_update_discard_title)) },
        text = { Text(stringResource(R.string.status_update_discard_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.status_update_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.status_update_discard_keep_editing))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun StatusUpdateEditPreview() {
    EmployeeAttendanceTheme {
        StatusUpdateEditContent(
            state = StatusUpdateEditUiState(
                drafts = listOf("Finished the inventory count", "", ""),
                found = true,
                showDiscardDialog = false,
            ),
            onDraftChanged = { _, _ -> },
            onSave = {},
            onCancel = {},
        )
    }
}
