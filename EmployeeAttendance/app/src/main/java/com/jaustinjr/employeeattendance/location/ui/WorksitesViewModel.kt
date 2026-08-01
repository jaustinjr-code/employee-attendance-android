package com.jaustinjr.employeeattendance.location.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.attendance.AttendanceRepository
import com.jaustinjr.employeeattendance.attendance.ClockNotifications
import com.jaustinjr.employeeattendance.attendance.ClockSource
import com.jaustinjr.employeeattendance.attendance.ClockType
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** List of registered worksites with the id of the currently active one. */
data class WorksitesUiState(
    val worksites: List<WorkLocation> = emptyList(),
    val activeId: String? = null,
    /** Whether the active worksite is currently clocked in (gates the switch confirmation). */
    val activeClockedIn: Boolean = false,
) {
    val activeWorksite: WorkLocation? get() = worksites.firstOrNull { it.id == activeId }
}

/** Backs the worksites list screen: exposes registered worksites and mediates activate/remove. */
class WorksitesViewModel(
    private val workLocationRepository: WorkLocationRepository,
    private val attendanceRepository: AttendanceRepository,
    private val notifier: ClockNotifications,
) : ViewModel() {

    val uiState: StateFlow<WorksitesUiState> = combine(
        workLocationRepository.workLocations,
        workLocationRepository.activeWorkLocation,
        attendanceRepository.attendance,
    ) { worksites, active, attendanceMap ->
        WorksitesUiState(
            worksites = worksites,
            activeId = active?.id,
            activeClockedIn = active?.let { attendanceMap[it.id]?.isClockedIn } ?: false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = WorksitesUiState(),
    )

    /** Activates [id] directly. Use only when the current active worksite is not clocked in. */
    fun onSetActive(id: String) = workLocationRepository.setActiveWorkLocation(id)

    /**
     * Activates [newId] after confirming a switch away from a clocked-in worksite: clocks the user
     * out of the *previous* worksite first (recording it and posting a notification that names that
     * worksite), then completes the transfer. Invoked from the confirmation modal.
     */
    fun onConfirmSwitchActive(newId: String) {
        val previous = workLocationRepository.activeWorkLocation.value
        if (previous != null && previous.id != newId &&
            attendanceRepository.attendance.value[previous.id]?.isClockedIn == true
        ) {
            Log.d(TAG, "switch away from clocked-in ${previous.id}; clocking out first")
            attendanceRepository.recordClockOut(previous.id, source = ClockSource.MANUAL)
            notifier.notifyRecorded(previous, ClockType.CLOCK_OUT, withUndo = false)
        }
        workLocationRepository.setActiveWorkLocation(newId)
    }

    fun onRemove(id: String) = workLocationRepository.removeWorkLocation(id)

    companion object {
        private const val TAG = "WorksitesVM"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                WorksitesViewModel(
                    workLocationRepository = container.workLocationRepository,
                    attendanceRepository = container.attendanceRepository,
                    notifier = container.clockNotifier,
                )
            }
        }
    }
}
