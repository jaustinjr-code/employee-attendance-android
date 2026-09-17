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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
 * system back and the app bar's up button all ask before discarding. System back is caught by the
 * [BackHandler] below; the up button is caught differently, since it lives in `MainActivity`'s app
 * bar outside this screen's composition — this screen registers a handler for it via
 * [onInterceptUpChanged] as soon as it composes (not gated on any lifecycle state), tagging the
 * registration with [upEntryId] (this screen's own `NavBackStackEntry.id`, passed in as a plain
 * string rather than the navigation type itself — this screen has no other reason to depend on
 * `androidx.navigation`), and clears the registration on dispose. The host
 * (`MainActivity`/`ui/main/UpNavigation.kt`) resolves registrations id-first on both the read side
 * (`performUp` ignores a registration that no longer matches the current back stack entry) and the
 * write side (`updateInterceptor` refuses to let a clear from a stale id overwrite a newer
 * registration) — this screen only needs to report its own id and intent, not reason about either.
 * Saving writes the answers and calls [onSaved], which returns to the read-only screen.
 */
@Composable
fun StatusUpdateEditScreen(
    onSaved: () -> Unit,
    onExit: () -> Unit,
    onInterceptUpChanged: (entryId: String, onUp: (() -> Unit)?) -> Unit,
    upEntryId: String,
    modifier: Modifier = Modifier,
    viewModel: StatusUpdateEditViewModel = viewModel(factory = StatusUpdateEditViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exitConfirmed by viewModel.exitConfirmed.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Clears focus and hides the IME before actually leaving, so every exit path — Save, and
    // Discard via the dialog — behaves the same instead of only the one path that happened to be
    // fixed first.
    fun leave(exit: () -> Unit) {
        focusManager.clearFocus()
        keyboardController?.hide()
        exit()
    }

    // Nothing to lose when the update no longer exists, so back leaves directly.
    BackHandler(enabled = state.found, onBack = viewModel::onExitRequested)

    // Registers as soon as this screen composes, not gated on RESUMED (which only arrives once
    // NavHost's *enter* transition finishes, ~700ms by default) — otherwise up would silently skip
    // the confirmation for that whole window right after opening the editor, while BackHandler
    // above (registered against STARTED) would already be live. This screen reports only its own
    // id and intent; the host resolves registrations id-first (see updateInterceptor's doc), which
    // is what makes both early registration and this effect's delayed onDispose (it doesn't run
    // until the ~700ms exit transition finishes) safe: an id-tagged clear can neither be acted on
    // by performUp nor overwrite a newer registration once it's no longer the current owner.
    DisposableEffect(state.found) {
        onInterceptUpChanged(upEntryId, if (state.found) viewModel::onExitRequested else null)
        onDispose { onInterceptUpChanged(upEntryId, null) }
    }

    // The dialog must be gone from composition before navigating away, not after: flipping
    // exitConfirmed closes the dialog (state change applies during composition, before this effect
    // runs), and only once that has happened does this effect run and actually pop the back stack.
    // Calling onExit() synchronously from the dialog's own click handler would navigate while the
    // dialog was still on screen. Beyond visual ordering, this is also the only way the dialog
    // actually gets closed at all once the pop is underway: collectAsStateWithLifecycle stops
    // collecting the moment this entry drops below STARTED, so a showDiscardDialog = false emitted
    // after that point would never be delivered — popping first would leave the flag permanently
    // stuck at true for this (about-to-be-destroyed) instance. onExitHandled() resets exitConfirmed
    // afterward so a later Discard is not silently dropped by MutableStateFlow's conflation of
    // repeated `true` values.
    LaunchedEffect(exitConfirmed) {
        if (exitConfirmed) {
            leave {
                onExit()
                viewModel.onExitHandled()
            }
        }
    }

    StatusUpdateEditContent(
        state = state,
        onDraftChanged = viewModel::onDraftChanged,
        onSave = {
            if (viewModel.save()) leave { onSaved() }
        },
        onCancel = viewModel::onExitRequested,
        modifier = modifier,
    )

    if (state.showDiscardDialog) {
        DiscardChangesDialog(
            onDiscard = viewModel::onDiscardConfirmed,
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
        HorizontalDivider()
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
