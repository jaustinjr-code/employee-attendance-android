package com.jaustinjr.employeeattendance.statusupdate.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.statusupdate.StatusUpdateRepository
import com.jaustinjr.employeeattendance.statusupdate.ui.StatusUpdateQuestion
import java.util.TimeZone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Navigation argument naming the status update a detail or edit screen shows. */
const val CLOCK_OUT_ID_ARG = "clockOutId"

private val sharing = SharingStarted.WhileSubscribed(5_000)

/** Backs the status update history section: every kept status update, grouped by day. */
class StatusUpdateHistoryViewModel(
    repository: StatusUpdateRepository,
    timeZone: () -> TimeZone = TimeZone::getDefault,
) : ViewModel() {

    val days: StateFlow<List<StatusUpdateDay>> = repository.statusUpdates
        .map { groupStatusUpdatesByDay(it, timeZone()) }
        .stateIn(
            viewModelScope,
            sharing,
            groupStatusUpdatesByDay(repository.statusUpdates.value, timeZone()),
        )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                StatusUpdateHistoryViewModel(container.statusUpdateRepository)
            }
        }
    }
}

/** Backs the read-only status update screen for the shift named by [CLOCK_OUT_ID_ARG]. */
class StatusUpdateDetailViewModel(
    savedStateHandle: SavedStateHandle,
    repository: StatusUpdateRepository,
) : ViewModel() {

    private val clockOutId: String = checkNotNull(savedStateHandle[CLOCK_OUT_ID_ARG])

    /** The shift, or null once no status update exists for it (for example after deleting all data). */
    val shift: StateFlow<StatusUpdateShift?> = repository.statusUpdates
        .map { updates -> updates.firstOrNull { it.clockOutId == clockOutId }?.toShift() }
        .stateIn(
            viewModelScope,
            sharing,
            repository.statusUpdates.value.firstOrNull { it.clockOutId == clockOutId }?.toShift(),
        )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                StatusUpdateDetailViewModel(createSavedStateHandle(), container.statusUpdateRepository)
            }
        }
    }
}

/**
 * UI state for editing a status update.
 *
 * @param drafts the three answers being edited, in [StatusUpdateQuestion] order.
 * @param found false when no status update exists for the requested shift.
 */
data class StatusUpdateEditUiState(
    val drafts: List<String>,
    val found: Boolean,
    val showDiscardDialog: Boolean,
) {
    /** At least one answer must stay filled in, since history keeps only filled-in updates. */
    val canSave: Boolean get() = found && drafts.any { it.isNotBlank() }
}

/**
 * Backs editing the status update named by [CLOCK_OUT_ID_ARG]. Drafts and the discard dialog live in
 * [SavedStateHandle], so a configuration change or process death keeps unsaved edits.
 *
 * Entering edit mode counts as having changes: leaving any way other than [save] asks first.
 */
class StatusUpdateEditViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: StatusUpdateRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val clockOutId: String = checkNotNull(savedStateHandle[CLOCK_OUT_ID_ARG])
    private val original = repository.statusUpdates.value.firstOrNull { it.clockOutId == clockOutId }

    private val draftsFlow: StateFlow<List<String>> =
        savedStateHandle.getStateFlow(KEY_DRAFTS, ArrayList(original?.answers ?: List(3) { "" }))
    private val discardDialogFlow: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_DISCARD_DIALOG, false)

    val uiState: StateFlow<StatusUpdateEditUiState> =
        combine(draftsFlow, discardDialogFlow) { drafts, dialog ->
            StatusUpdateEditUiState(drafts, found = original != null, showDiscardDialog = dialog)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            StatusUpdateEditUiState(draftsFlow.value, original != null, discardDialogFlow.value),
        )

    fun onDraftChanged(index: Int, value: String) {
        savedStateHandle[KEY_DRAFTS] = ArrayList(draftsFlow.value).also { it[index] = value }
    }

    /** Cancel, system back or up: always confirm, since edit mode assumes there are changes. */
    fun onExitRequested() {
        savedStateHandle[KEY_DISCARD_DIALOG] = true
    }

    /** "Keep editing". */
    fun onDiscardDialogDismissed() {
        savedStateHandle[KEY_DISCARD_DIALOG] = false
    }

    /** Writes the drafts back. Returns true when saved, so the caller can return to the read-only view. */
    fun save(): Boolean {
        val state = uiState.value
        if (!state.canSave) return false
        val (didToday, plannedTomorrow, couldNotDo) = state.drafts
        return repository.updateAnswers(clockOutId, didToday, plannedTomorrow, couldNotDo, clock())
    }

    companion object {
        private const val KEY_DRAFTS = "drafts"
        private const val KEY_DISCARD_DIALOG = "showDiscardDialog"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                StatusUpdateEditViewModel(createSavedStateHandle(), container.statusUpdateRepository)
            }
        }
    }
}
