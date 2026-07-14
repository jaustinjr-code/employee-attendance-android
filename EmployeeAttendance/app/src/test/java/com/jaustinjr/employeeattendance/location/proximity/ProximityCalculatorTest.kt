package com.jaustinjr.employeeattendance.location.proximity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises the pure hysteresis logic in [ProximityCalculator.evaluate]. The distance computation
 * itself relies on android.location.Location and is covered by instrumentation tests instead.
 */
class ProximityCalculatorTest {

    private val radius = 100f
    private val buffer = 50f

    @Test
    fun `inside when within radius`() {
        val result = ProximityCalculator.evaluate(
            current = ProximityState.OUTSIDE,
            distanceMeters = 80f,
            radiusMeters = radius,
            exitBufferMeters = buffer,
        )
        assertEquals(ProximityState.INSIDE, result)
    }

    @Test
    fun `exactly at radius counts as inside`() {
        val result = ProximityCalculator.evaluate(
            current = ProximityState.OUTSIDE,
            distanceMeters = radius,
            radiusMeters = radius,
            exitBufferMeters = buffer,
        )
        assertEquals(ProximityState.INSIDE, result)
    }

    @Test
    fun `outside when beyond radius plus buffer`() {
        val result = ProximityCalculator.evaluate(
            current = ProximityState.INSIDE,
            distanceMeters = radius + buffer + 1f,
            radiusMeters = radius,
            exitBufferMeters = buffer,
        )
        assertEquals(ProximityState.OUTSIDE, result)
    }

    @Test
    fun `within hysteresis band holds INSIDE state`() {
        val result = ProximityCalculator.evaluate(
            current = ProximityState.INSIDE,
            distanceMeters = radius + 10f, // in the (radius, radius+buffer] band
            radiusMeters = radius,
            exitBufferMeters = buffer,
        )
        assertEquals(ProximityState.INSIDE, result)
    }

    @Test
    fun `within hysteresis band stays OUTSIDE when previously outside`() {
        val result = ProximityCalculator.evaluate(
            current = ProximityState.OUTSIDE,
            distanceMeters = radius + 10f,
            radiusMeters = radius,
            exitBufferMeters = buffer,
        )
        assertEquals(ProximityState.OUTSIDE, result)
    }

    @Test
    fun `unknown resolves to outside in the hysteresis band`() {
        val result = ProximityCalculator.evaluate(
            current = ProximityState.UNKNOWN,
            distanceMeters = radius + 10f,
            radiusMeters = radius,
            exitBufferMeters = buffer,
        )
        assertEquals(ProximityState.OUTSIDE, result)
    }
}
