package com.jaustinjr.employeeattendance.location.registration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkLocationTest {

    private fun validLocation() = WorkLocation(
        id = "office",
        name = "Office",
        latitudeDegrees = 37.0,
        longitudeDegrees = -122.0,
        radiusMeters = 100f,
    )

    @Test
    fun `valid location constructs and projects to matching geofence target`() {
        val location = validLocation()
        val target = location.toGeofenceTarget()

        assertEquals(location.id, target.id)
        assertEquals(location.latitudeDegrees, target.latitudeDegrees, 0.0)
        assertEquals(location.longitudeDegrees, target.longitudeDegrees, 0.0)
        assertEquals(location.radiusMeters, target.radiusMeters, 0f)
    }

    @Test
    fun `blank id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validLocation().copy(id = " ")
        }
    }

    @Test
    fun `blank name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validLocation().copy(name = "")
        }
    }

    @Test
    fun `out of range latitude is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validLocation().copy(latitudeDegrees = 91.0)
        }
    }

    @Test
    fun `out of range longitude is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validLocation().copy(longitudeDegrees = -181.0)
        }
    }

    @Test
    fun `non-positive radius is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validLocation().copy(radiusMeters = 0f)
        }
    }
}
