package com.jaustinjr.employeeattendance.location.tracking

import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPowerPolicyTest {

    @Test
    fun `unknown uses high-accuracy low-latency acquisition`() {
        val c = LocationPowerPolicy.foregroundConfig(ProximityState.UNKNOWN)
        assertEquals(LocationPriority.HIGH_ACCURACY, c.priority)
        assertEquals(0L, c.maxUpdateDelayMillis) // low latency: no batching
    }

    @Test
    fun `inside uses balanced moderate cadence`() {
        val c = LocationPowerPolicy.foregroundConfig(ProximityState.INSIDE)
        assertEquals(LocationPriority.BALANCED, c.priority)
    }

    @Test
    fun `outside uses low power with batching`() {
        val c = LocationPowerPolicy.foregroundConfig(ProximityState.OUTSIDE)
        assertEquals(LocationPriority.LOW_POWER, c.priority)
        assertTrue(c.maxUpdateDelayMillis > 0) // batches to save power while away
    }

    @Test
    fun `away polls less often than when present`() {
        val inside = LocationPowerPolicy.foregroundConfig(ProximityState.INSIDE)
        val outside = LocationPowerPolicy.foregroundConfig(ProximityState.OUTSIDE)
        assertTrue(outside.intervalMillis >= inside.intervalMillis)
    }
}
