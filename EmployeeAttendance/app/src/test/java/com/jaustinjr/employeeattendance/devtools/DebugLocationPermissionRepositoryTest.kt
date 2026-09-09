package com.jaustinjr.employeeattendance.devtools

import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionRepository
import com.jaustinjr.employeeattendance.location.permission.LocationPermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugLocationPermissionRepositoryTest {

    private val real = LocationPermissionState(LocationAccessLevel.WHEN_IN_USE, isPrecise = true)

    private class FakePermissionRepository(initial: LocationPermissionState) :
        LocationPermissionRepository {
        private val _state = MutableStateFlow(initial)
        override val permissionState: StateFlow<LocationPermissionState> = _state
        var refreshCount = 0
            private set

        override fun refresh(): LocationPermissionState {
            refreshCount++
            return _state.value
        }

        fun set(state: LocationPermissionState) {
            _state.value = state
        }
    }

    private val scheduler = UnconfinedTestDispatcher()
    private val scope = TestScope(scheduler)
    private val delegate = FakePermissionRepository(real)
    private val override = MutableStateFlow(PermissionOverride.OFF)
    private val repository = DebugLocationPermissionRepository(delegate, override, scope)

    @Test
    fun `with no override the real state passes through`() {
        assertEquals(real, repository.permissionState.value)
        assertEquals(real, repository.refresh())
    }

    @Test
    fun `an override replaces the observed state`() {
        override.value = PermissionOverride.ALWAYS_PRECISE
        scope.runCurrent()

        val observed = repository.permissionState.value
        assertEquals(LocationAccessLevel.ALWAYS, observed.accessLevel)
        assertTrue(observed.isPrecise)
        assertTrue(observed.supportsBackgroundTracking)
    }

    @Test
    fun `refresh returns the overridden state, not the real one`() {
        override.value = PermissionOverride.DENIED
        scope.runCurrent()

        assertEquals(LocationPermissionState.Denied, repository.refresh())
    }

    @Test
    fun `refresh still re-reads the delegate so clearing the override sees reality`() {
        override.value = PermissionOverride.DENIED
        scope.runCurrent()
        repository.refresh()
        assertEquals(1, delegate.refreshCount)

        delegate.set(LocationPermissionState(LocationAccessLevel.ALWAYS, isPrecise = true))
        override.value = PermissionOverride.OFF
        scope.runCurrent()

        assertEquals(LocationAccessLevel.ALWAYS, repository.refresh().accessLevel)
    }

    @Test
    fun `clearing the override restores the real state`() {
        override.value = PermissionOverride.DENIED
        scope.runCurrent()
        assertEquals(LocationAccessLevel.NONE, repository.permissionState.value.accessLevel)

        override.value = PermissionOverride.OFF
        scope.runCurrent()
        assertEquals(real, repository.permissionState.value)
    }

    @Test
    fun `a real permission change is still observed while an override is off`() {
        val upgraded = LocationPermissionState(LocationAccessLevel.ALWAYS, isPrecise = true)
        delegate.set(upgraded)
        scope.runCurrent()

        assertEquals(upgraded, repository.permissionState.value)
    }

    @Test
    fun `an override wins over a concurrent real permission change`() {
        override.value = PermissionOverride.WHEN_IN_USE_APPROXIMATE
        delegate.set(LocationPermissionState(LocationAccessLevel.ALWAYS, isPrecise = true))
        scope.runCurrent()

        val observed = repository.permissionState.value
        assertEquals(LocationAccessLevel.WHEN_IN_USE, observed.accessLevel)
        assertEquals(false, observed.isPrecise)
    }

    @Test
    fun `an override set before anyone subscribes is already applied to the initial value`() {
        val preSet = MutableStateFlow(PermissionOverride.DENIED)
        val eager = DebugLocationPermissionRepository(delegate, preSet, scope)

        // No runCurrent: seeding the StateFlow must not depend on the combine having emitted, or a
        // consumer reading .value during startup (the coordinator does) would see the real grant.
        assertEquals(LocationPermissionState.Denied, eager.permissionState.value)
    }
}
