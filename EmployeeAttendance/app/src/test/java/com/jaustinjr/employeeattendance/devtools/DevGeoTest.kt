package com.jaustinjr.employeeattendance.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * `ProximityCalculator.distanceMeters` returns 0 for every input under the JVM framework stubs, so
 * the offset math the developer tools use to place a simulated fix has to be verified on its own.
 */
class DevGeoTest {

    @Test
    fun `a zero offset returns the point unchanged`() {
        assertEquals(37.0 to -122.0, DevGeo.offsetNorth(37.0, -122.0, 0.0))
    }

    @Test
    fun `an offset moves north by roughly the requested distance`() {
        val (latitude, longitude) = DevGeo.offsetNorth(37.0, -122.0, 1_000.0)

        assertEquals(-122.0, longitude, 0.0)
        val movedMeters = (latitude - 37.0) * METERS_PER_DEGREE_LAT
        assertEquals(1_000.0, movedMeters, 1.0)
    }

    @Test
    fun `longitude is untouched, so the offset is independent of convergence`() {
        listOf(-179.9, 0.0, 179.9).forEach { longitude ->
            assertEquals(longitude, DevGeo.offsetNorth(60.0, longitude, 500.0).second, 0.0)
        }
    }

    @Test
    fun `near the north pole the offset flips south rather than producing an invalid latitude`() {
        val (latitude, _) = DevGeo.offsetNorth(89.999, 10.0, 1_000.0)

        assertTrue("latitude $latitude out of range", latitude in -90.0..90.0)
        assertTrue("expected a southward move, got $latitude", latitude < 89.999)
    }

    @Test
    fun `near the south pole the northward offset is still used`() {
        val (latitude, _) = DevGeo.offsetNorth(-89.999, 10.0, 1_000.0)

        assertTrue("latitude $latitude out of range", latitude in -90.0..90.0)
        assertTrue("expected a northward move, got $latitude", latitude > -89.999)
    }

    @Test
    fun `an offset larger than the planet leaves the point where it is`() {
        val (latitude, longitude) = DevGeo.offsetNorth(0.0, 0.0, 40_000_000.0)

        assertEquals(0.0, latitude, 0.0)
        assertEquals(0.0, longitude, 0.0)
    }

    @Test
    fun `every produced latitude stays valid across the whole range`() {
        var latitude = -90.0
        while (latitude <= 90.0) {
            val (result, _) = DevGeo.offsetNorth(latitude, 0.0, 5_000.0)
            assertTrue("latitude $latitude produced $result", abs(result) <= 90.0)
            latitude += 0.5
        }
    }

    private companion object {
        const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}
