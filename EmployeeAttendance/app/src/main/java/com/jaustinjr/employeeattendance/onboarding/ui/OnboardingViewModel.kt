package com.jaustinjr.employeeattendance.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import com.jaustinjr.employeeattendance.onboarding.OnboardingStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Decides whether the first-launch onboarding carousel is shown and records its completion.
 *
 * Which page is on screen is deliberately *not* held here: it belongs to the pager, whose state is
 * already saveable and has to drive the swipe gesture anyway.
 */
class OnboardingViewModel(
    private val store: OnboardingStore,
) : ViewModel() {

    /**
     * Whether to show onboarding instead of the app. Seeded synchronously from the store so a
     * returning user never sees a frame of the carousel before the home screen.
     */
    val showOnboarding: StateFlow<Boolean> = store.completed
        .map { !it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = !store.completed.value,
        )

    fun complete() {
        store.markCompleted()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                OnboardingViewModel(store = container.onboardingStore)
            }
        }
    }
}
