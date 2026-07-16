package com.jaustinjr.employeeattendance.location.ui

import androidx.lifecycle.SavedStateHandle
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import com.jaustinjr.employeeattendance.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationPermissionViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakePermissionRepository(
        initial: LocationPermissionState,
    ) : LocationPermissionRepository {
        private val _state = MutableStateFlow(initial)
        override val permissionState: StateFlow<LocationPermissionState> = _state
        override fun refresh(): LocationPermissionState = _state.value
        fun set(state: LocationPermissionState) { _state.value = state }
    }

    private fun state(level: LocationAccessLevel) =
        LocationPermissionState(level, isPrecise = level != LocationAccessLevel.NONE)

    @Test
    fun `no permission shows the enable prompt`() = runTest {
        val vm = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.NONE)),
            SavedStateHandle(),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        assertEquals(LocationPermissionPrompt.EnableForeground, vm.uiState.value.visiblePrompt)
    }

    @Test
    fun `full access shows no prompt`() = runTest {
        val vm = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.ALWAYS)),
            SavedStateHandle(),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        assertNull(vm.uiState.value.visiblePrompt)
    }

    @Test
    fun `dismissing the enable prompt hides it`() = runTest {
        val vm = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.NONE)),
            SavedStateHandle(),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.onPromptDismissed(LocationPermissionPrompt.EnableForeground)
        runCurrent()

        assertNull(vm.uiState.value.visiblePrompt)
    }

    @Test
    fun `onSetupRequested re-surfaces a dismissed prompt`() = runTest {
        val vm = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.NONE)),
            SavedStateHandle(),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.onPromptDismissed(LocationPermissionPrompt.EnableForeground)
        runCurrent()

        vm.onSetupRequested()
        runCurrent()

        assertEquals(LocationPermissionPrompt.EnableForeground, vm.uiState.value.visiblePrompt)
    }

    @Test
    fun `permanent denial requires settings and clears when granted`() = runTest {
        val repo = FakePermissionRepository(state(LocationAccessLevel.NONE))
        val vm = LocationPermissionViewModel(repo, SavedStateHandle())
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.onForegroundDenied(canRetryWithRationale = false)
        runCurrent()
        assertTrue(vm.uiState.value.requiresSettingsForForeground)

        // User relented in settings: permission becomes granted, flag clears.
        repo.set(state(LocationAccessLevel.WHEN_IN_USE))
        vm.onPermissionResult()
        runCurrent()
        assertFalse(vm.uiState.value.requiresSettingsForForeground)
    }

    @Test
    fun `retryable denial does not require settings`() = runTest {
        val vm = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.NONE)),
            SavedStateHandle(),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.onForegroundDenied(canRetryWithRationale = true)
        runCurrent()

        assertFalse(vm.uiState.value.requiresSettingsForForeground)
    }

    @Test
    fun `dismissal persists across recreation via SavedStateHandle`() = runTest {
        val saved = SavedStateHandle()
        val first = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.NONE)),
            saved,
        )
        backgroundScope.launch { first.uiState.collect {} }
        runCurrent()
        first.onPromptDismissed(LocationPermissionPrompt.EnableForeground)
        runCurrent()

        // A new instance restored from the same handle keeps the dismissal.
        val second = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.NONE)),
            saved,
        )
        backgroundScope.launch { second.uiState.collect {} }
        runCurrent()

        assertNull(second.uiState.value.visiblePrompt)
    }

    @Test
    fun `permanent denial flag persists across recreation`() = runTest {
        val saved = SavedStateHandle()
        val first = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.NONE)),
            saved,
        )
        backgroundScope.launch { first.uiState.collect {} }
        runCurrent()
        first.onForegroundDenied(canRetryWithRationale = false)
        runCurrent()

        val second = LocationPermissionViewModel(
            FakePermissionRepository(state(LocationAccessLevel.NONE)),
            saved,
        )
        backgroundScope.launch { second.uiState.collect {} }
        runCurrent()

        assertTrue(second.uiState.value.requiresSettingsForForeground)
    }
}
