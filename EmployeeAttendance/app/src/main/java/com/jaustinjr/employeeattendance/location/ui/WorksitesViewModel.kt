package com.jaustinjr.employeeattendance.location.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
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
)

/** Backs the worksites list screen: exposes registered worksites and mediates activate/remove. */
class WorksitesViewModel(
    private val workLocationRepository: WorkLocationRepository,
) : ViewModel() {

    val uiState: StateFlow<WorksitesUiState> = combine(
        workLocationRepository.workLocations,
        workLocationRepository.activeWorkLocation,
    ) { worksites, active ->
        WorksitesUiState(worksites = worksites, activeId = active?.id)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = WorksitesUiState(),
    )

    fun onSetActive(id: String) = workLocationRepository.setActiveWorkLocation(id)

    fun onRemove(id: String) = workLocationRepository.removeWorkLocation(id)

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                WorksitesViewModel(workLocationRepository = container.workLocationRepository)
            }
        }
    }
}
