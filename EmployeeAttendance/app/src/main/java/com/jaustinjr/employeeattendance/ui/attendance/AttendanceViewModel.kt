package com.jaustinjr.employeeattendance.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jaustinjr.employeeattendance.EmployeeAttendanceApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the attendance greeting shows: the date, which greeting to use, and who to greet. */
data class GreetingUiState(
    val todayDate: String,
    val timeOfDay: TimeOfDay,
    /** The user's chosen name, or empty when unset — the UI substitutes a friendly default. */
    val displayName: String = "",
)

/**
 * Backs the attendance greeting. The date and time of day are re-read from the phone's clock on a
 * ticker rather than computed once at construction, so a screen left open across noon (or a device
 * whose clock or time zone changes) shows the right greeting without a restart.
 *
 * The ticker lives inside the exposed flow, so it only runs while the screen is actually
 * collecting. [displayName] comes from the account settings store — the greeting picks up a rename
 * without a restart.
 */
class AttendanceViewModel(
    private val displayName: StateFlow<String>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val clockTicks: Flow<Long> = flow {
        while (true) {
            emit(nowMillis())
            delay(TICK_INTERVAL_MILLIS)
        }
    }

    val uiState: StateFlow<GreetingUiState> =
        combine(clockTicks, displayName) { millis, name ->
            greetingState(millis, name)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = greetingState(nowMillis(), displayName.value),
        )

    private fun greetingState(epochMillis: Long, displayName: String) = GreetingUiState(
        // A fresh formatter each tick: SimpleDateFormat is not thread-safe, and this runs once a
        // minute at most.
        todayDate = SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date(epochMillis)),
        timeOfDay = TimeOfDay.fromEpochMillis(epochMillis),
        displayName = displayName,
    )

    companion object {
        // A minute is fine: the greeting only ever changes on an hour boundary.
        private const val TICK_INTERVAL_MILLIS = 60_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private const val DATE_PATTERN = "EEEE, MMM dd"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as EmployeeAttendanceApplication).container
                AttendanceViewModel(displayName = container.userProfileStore.displayName)
            }
        }
    }
}
