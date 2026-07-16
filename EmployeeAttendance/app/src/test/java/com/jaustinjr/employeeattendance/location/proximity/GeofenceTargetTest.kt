package com.jaustinjr.employeeattendance.location.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeofenceTargetTest {

    private fun target() = GeofenceTarget(
        id = "t1",
        latitudeDegrees = 37.0,
        longitudeDegrees = -122.0,
        radiusMeters = 100f,
    )

    @Test
    fun `valid target constructs`() {
        val t = target()
        assertEquals("t1", t.id)
        assertEquals(100f, t.radiusMeters, 0f)
    }

    @Test
    fun `blank id rejected`() {
        assertThrows(IllegalArgumentException::class.java) { target().copy(id = "  ") }
    }

    @Test
    fun `latitude out of range rejected`() {
        assertThrows(IllegalArgumentException::class.java) { target().copy(latitudeDegrees = 90.1) }
    }

    @Test
    fun `longitude out of range rejected`() {
        assertThrows(IllegalArgumentException::class.java) { target().copy(longitudeDegrees = 180.1) }
    }

    @Test
    fun `non-positive radius rejected`() {
        assertThrows(IllegalArgumentException::class.java) { target().copy(radiusMeters = 0f) }
    }

    @Test
    fun `infinite radius rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            target().copy(radiusMeters = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `NaN radius rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            target().copy(radiusMeters = Float.NaN)
        }
    }

    @Test
    fun `radius above max rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            target().copy(radiusMeters = GeofenceTarget.MAX_RADIUS_METERS + 1f)
        }
    }

    @Test
    fun `radius at max accepted`() {
        val t = target().copy(radiusMeters = GeofenceTarget.MAX_RADIUS_METERS)
        assertEquals(GeofenceTarget.MAX_RADIUS_METERS, t.radiusMeters, 0f)
    }
}
