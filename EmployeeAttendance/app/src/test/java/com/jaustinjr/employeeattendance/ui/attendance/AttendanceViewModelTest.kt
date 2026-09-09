package com.jaustinjr.employeeattendance.ui.attendance

import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    /** Stands in for UserProfileStore: only its display-name flow is read. */
    private class FakeProfile(name: String = "") {
        private val _displayName = MutableStateFlow(name)
        val displayName: StateFlow<String> = _displayName
        fun set(name: String) { _displayName.value = name }
    }

    private var now = at(hourOfDay = 9)

    private fun viewModel(profile: FakeProfile) =
        AttendanceViewModel(profile.displayName, nowMillis = { now })

    @Test
    fun `greeting follows the hour on the phone clock`() = runTest {
        val vm = viewModel(FakeProfile("Jordan"))
        val collect = launch { vm.uiState.collect {} }
        runCurrent()

        assertEquals(TimeOfDay.MORNING, vm.uiState.value.timeOfDay)
        assertEquals("Jordan", vm.uiState.value.displayName)

        collect.cancel()
    }

    @Test
    fun `greeting updates when the clock crosses into the afternoon`() = runTest {
        val vm = viewModel(FakeProfile())
        val collect = launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals(TimeOfDay.MORNING, vm.uiState.value.timeOfDay)

        now = at(hourOfDay = 13)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(TimeOfDay.AFTERNOON, vm.uiState.value.timeOfDay)

        now = at(hourOfDay = 20)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(TimeOfDay.EVENING, vm.uiState.value.timeOfDay)

        collect.cancel()
    }

    @Test
    fun `a renamed user is greeted by the new name`() = runTest {
        val profile = FakeProfile("Jordan")
        val vm = viewModel(profile)
        val collect = launch { vm.uiState.collect {} }
        runCurrent()

        profile.set("Sam")
        runCurrent()
        assertEquals("Sam", vm.uiState.value.displayName)

        collect.cancel()
    }

    private companion object {
        /** Epoch millis for [hourOfDay] on a fixed day, read in the JVM's default zone. */
        fun at(hourOfDay: Int): Long {
            val calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.US)
            calendar.set(2026, Calendar.MAY, 24, hourOfDay, 30, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
    }
}
