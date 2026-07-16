package com.jaustinjr.employeeattendance.location.tracking

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRequestConfigTest {

    @Test
    fun `foreground preset satisfies invariants`() {
        val c = LocationRequestConfig.Foreground
        assertTrue(c.intervalMillis > 0)
        assertTrue(c.minUpdateIntervalMillis in 0..c.intervalMillis)
        assertTrue(c.maxUpdateDelayMillis >= 0)
    }

    @Test
    fun `background preset is lower power than foreground`() {
        assertTrue(
            LocationRequestConfig.Background.intervalMillis >
                LocationRequestConfig.Foreground.intervalMillis,
        )
    }

    @Test
    fun `non-positive interval rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationRequestConfig(LocationPriority.BALANCED, 0L, 0L, 0L)
        }
    }

    @Test
    fun `min interval greater than interval rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationRequestConfig(LocationPriority.BALANCED, 1_000L, 2_000L, 0L)
        }
    }

    @Test
    fun `negative max delay rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationRequestConfig(LocationPriority.BALANCED, 1_000L, 500L, -1L)
        }
    }
}
