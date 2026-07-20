package com.jaustinjr.employeeattendance.location.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the clock-in/out display rules encoded in [LocationUiState]. */
class LocationUiStateTest {

    @Test
    fun `clocked in when there is a clock-in and no later clock-out`() {
        val state = LocationUiState(lastClockInEpochMillis = 1_000L)

        assertTrue(state.isClockedIn)
        assertEquals(1_000L, state.attendanceClockInMillis)
        assertNull(state.attendanceClockOutMillis)
    }

    @Test
    fun `manual clock-out later than clock-in shows on the attendance screen`() {
        val state = LocationUiState(
            lastClockInEpochMillis = 1_000L,
            lastClockOutEpochMillis = 2_000L,
            lastClockOutWasManual = true,
        )

        assertFalse(state.isClockedIn)
        assertNull(state.attendanceClockInMillis)
        assertEquals(2_000L, state.attendanceClockOutMillis)
        // Detail shows it too (any source, more recent than clock-in).
        assertEquals(2_000L, state.detailClockOutMillis)
    }

    @Test
    fun `automatic clock-out is hidden on the attendance screen but shown on detail`() {
        val state = LocationUiState(
            lastClockInEpochMillis = 1_000L,
            lastClockOutEpochMillis = 2_000L,
            lastClockOutWasManual = false,
        )

        assertFalse(state.isClockedIn)
        // Not shown on the attendance screen because it wasn't a manual clock-out...
        assertNull(state.attendanceClockOutMillis)
        // ...but it is shown on the worksite detail.
        assertEquals(2_000L, state.detailClockOutMillis)
    }

    @Test
    fun `a newer clock-in resets the displayed clock-out`() {
        val state = LocationUiState(
            lastClockInEpochMillis = 3_000L,
            lastClockOutEpochMillis = 2_000L,
            lastClockOutWasManual = true,
        )

        assertTrue(state.isClockedIn)
        assertEquals(3_000L, state.attendanceClockInMillis)
        assertNull(state.attendanceClockOutMillis)
        // Detail also hides the stale clock-out once a newer clock-in exists.
        assertNull(state.detailClockOutMillis)
    }

    @Test
    fun `granted but not precise is approximate-only`() {
        val state = LocationUiState(
            accessLevel = com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel.WHEN_IN_USE,
            isPrecise = false,
        )
        assertTrue(state.isApproximateOnly)
    }

    @Test
    fun `precise grant is not approximate-only`() {
        val state = LocationUiState(
            accessLevel = com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel.ALWAYS,
            isPrecise = true,
        )
        assertFalse(state.isApproximateOnly)
    }

    @Test
    fun `no grant is not approximate-only`() {
        val state = LocationUiState(
            accessLevel = com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel.NONE,
            isPrecise = false,
        )
        assertFalse(state.isApproximateOnly)
    }

    @Test
    fun `no clock activity yields no status`() {
        val state = LocationUiState()

        assertFalse(state.isClockedIn)
        assertNull(state.attendanceClockInMillis)
        assertNull(state.attendanceClockOutMillis)
        assertNull(state.detailClockOutMillis)
    }
}
