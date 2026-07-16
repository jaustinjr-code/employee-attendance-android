package com.jaustinjr.employeeattendance.location.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationAccessLevelTest {

    @Test
    fun `none is not granted`() {
        assertFalse(LocationAccessLevel.NONE.isGranted)
        assertFalse(LocationAccessLevel.NONE.supportsBackgroundTracking)
        assertFalse(LocationAccessLevel.NONE.isDegraded)
    }

    @Test
    fun `when-in-use is granted but degraded with no background`() {
        assertTrue(LocationAccessLevel.WHEN_IN_USE.isGranted)
        assertTrue(LocationAccessLevel.WHEN_IN_USE.isDegraded)
        assertFalse(LocationAccessLevel.WHEN_IN_USE.supportsBackgroundTracking)
    }

    @Test
    fun `always is granted with background and not degraded`() {
        assertTrue(LocationAccessLevel.ALWAYS.isGranted)
        assertTrue(LocationAccessLevel.ALWAYS.supportsBackgroundTracking)
        assertFalse(LocationAccessLevel.ALWAYS.isDegraded)
    }

    @Test
    fun `permission state mirrors access level flags`() {
        val state = LocationPermissionState(LocationAccessLevel.WHEN_IN_USE, isPrecise = true)
        assertTrue(state.isGranted)
        assertTrue(state.isDegraded)
        assertFalse(state.supportsBackgroundTracking)
        assertEquals(LocationAccessLevel.WHEN_IN_USE, state.accessLevel)
    }

    @Test
    fun `denied constant is none`() {
        assertEquals(LocationAccessLevel.NONE, LocationPermissionState.Denied.accessLevel)
        assertFalse(LocationPermissionState.Denied.isGranted)
    }
}
