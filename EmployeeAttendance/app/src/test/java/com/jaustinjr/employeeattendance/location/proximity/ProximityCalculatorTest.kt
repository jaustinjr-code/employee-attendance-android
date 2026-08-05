package com.jaustinjr.employeeattendance.location.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure hysteresis + accuracy-gate logic in [ProximityCalculator.evaluate]. The distance
 * computation itself relies on android.location.Location and is covered by instrumentation tests.
 */
class ProximityCalculatorTest {

    private val radius = 100f
    private val buffer = 50f

    /** A fix good enough that the gate is never the thing under test. */
    private val precise = 1f

    private fun evaluate(
        current: ProximityState,
        distanceMeters: Float,
        accuracyMeters: Float = precise,
        corroboratingInsideFixes: Int = 0,
    ) = ProximityCalculator.evaluate(
        current = current,
        distanceMeters = distanceMeters,
        accuracyMeters = accuracyMeters,
        radiusMeters = radius,
        exitBufferMeters = buffer,
        corroboratingInsideFixes = corroboratingInsideFixes,
    )

    // ---------------------------------------------------------------- hysteresis (pre-existing)

    @Test
    fun `inside when within radius`() {
        assertEquals(ProximityState.INSIDE, evaluate(ProximityState.OUTSIDE, 80f))
    }

    @Test
    fun `exactly at radius counts as inside`() {
        // Needs a zero-error fix: at exactly the radius, any error at all makes it merely marginal.
        assertEquals(
            ProximityState.INSIDE,
            evaluate(ProximityState.OUTSIDE, radius, accuracyMeters = 0f),
        )
    }

    @Test
    fun `outside when beyond radius plus buffer`() {
        assertEquals(
            ProximityState.OUTSIDE,
            evaluate(ProximityState.INSIDE, radius + buffer + 1f),
        )
    }

    @Test
    fun `within hysteresis band holds INSIDE state`() {
        assertEquals(ProximityState.INSIDE, evaluate(ProximityState.INSIDE, radius + 10f))
    }

    @Test
    fun `within hysteresis band stays OUTSIDE when previously outside`() {
        assertEquals(ProximityState.OUTSIDE, evaluate(ProximityState.OUTSIDE, radius + 10f))
    }

    @Test
    fun `unknown resolves to outside in the hysteresis band`() {
        assertEquals(ProximityState.OUTSIDE, evaluate(ProximityState.UNKNOWN, radius + 10f))
    }

    // ------------------------------------------------------------------- accuracy gate (#12)

    @Test
    fun `a fix coarser than the decision band decides nothing`() {
        // The reported case from the issue, scaled to this fixture: the point lands inside the
        // radius but its 1-sigma error circle is wider than the whole band, so it is not evidence.
        assertEquals(
            ProximityState.OUTSIDE,
            evaluate(ProximityState.OUTSIDE, distanceMeters = 90f, accuracyMeters = 500f),
        )
        assertEquals(
            ProximityState.UNKNOWN,
            evaluate(ProximityState.UNKNOWN, distanceMeters = 90f, accuracyMeters = 500f),
        )
    }

    @Test
    fun `the exact scenario from issue 12 does not clock the user in`() {
        // distance 140 m, radius 150 m, accuracy 500 m -> must not be INSIDE.
        val result = ProximityCalculator.evaluate(
            current = ProximityState.OUTSIDE,
            distanceMeters = 140f,
            accuracyMeters = 500f,
            radiusMeters = 150f,
            exitBufferMeters = 50f,
        )
        assertEquals(ProximityState.OUTSIDE, result)
    }

    @Test
    fun `a coarse fix cannot force an exit either`() {
        // The gate is symmetric about *deciding nothing*: an uninformative fix must not eject a
        // user who is legitimately clocked in.
        assertEquals(
            ProximityState.INSIDE,
            evaluate(ProximityState.INSIDE, distanceMeters = 5_000f, accuracyMeters = 500f),
        )
    }

    @Test
    fun `entering requires the error circle to fit inside the radius`() {
        // 80 + 30 > 100: usable, reads inside, but not confidently — and nothing corroborates it.
        assertEquals(
            ProximityState.OUTSIDE,
            evaluate(ProximityState.OUTSIDE, distanceMeters = 80f, accuracyMeters = 30f),
        )
        // 60 + 30 <= 100: the whole circle fits, so this commits immediately.
        assertEquals(
            ProximityState.INSIDE,
            evaluate(ProximityState.OUTSIDE, distanceMeters = 60f, accuracyMeters = 30f),
        )
    }

    @Test
    fun `a marginal fix commits once enough consecutive fixes corroborate it`() {
        // The escape hatch that keeps auto clock-in alive on merely-mediocre devices.
        val oneShort = ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES - 1
        assertEquals(
            ProximityState.OUTSIDE,
            evaluate(
                ProximityState.OUTSIDE,
                distanceMeters = 80f,
                accuracyMeters = 30f,
                corroboratingInsideFixes = oneShort,
            ),
        )
        assertEquals(
            ProximityState.INSIDE,
            evaluate(
                ProximityState.OUTSIDE,
                distanceMeters = 80f,
                accuracyMeters = 30f,
                corroboratingInsideFixes = ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES,
            ),
        )
    }

    @Test
    fun `corroboration cannot rescue an unusable fix`() {
        // Otherwise a device stuck on cell-tower fixes would clock in anyway after a few of them.
        assertEquals(
            ProximityState.OUTSIDE,
            evaluate(
                ProximityState.OUTSIDE,
                distanceMeters = 90f,
                accuracyMeters = 500f,
                corroboratingInsideFixes = 99,
            ),
        )
    }

    @Test
    fun `staying inside needs no confidence`() {
        // Only *entering* is gated; a marginal fix that still reads inside holds the state.
        assertEquals(
            ProximityState.INSIDE,
            evaluate(ProximityState.INSIDE, distanceMeters = 95f, accuracyMeters = 40f),
        )
    }

    @Test
    fun `leaving is still permissive for a usable fix`() {
        // A false clock-out is far less harmful than a false clock-in, so exits keep the old rule.
        assertEquals(
            ProximityState.OUTSIDE,
            evaluate(ProximityState.INSIDE, distanceMeters = 300f, accuracyMeters = 140f),
        )
    }

    // --------------------------------------------------------------------------- isUsable

    @Test
    fun `usability is bounded by the radius plus the exit buffer`() {
        assertTrue(ProximityCalculator.isUsable(radius + buffer, radius, buffer))
        assertFalse(ProximityCalculator.isUsable(radius + buffer + 0.1f, radius, buffer))
    }

    @Test
    fun `a wider radius tolerates a coarser fix`() {
        // The user-facing remedy for a device that only produces coarse fixes: DISTANT is 600 m, so
        // the budget is 650 m and a 500 m fix becomes usable again.
        assertFalse(ProximityCalculator.isUsable(500f, 150f, 50f))
        assertTrue(ProximityCalculator.isUsable(500f, 600f, 50f))
    }

    @Test
    fun `a missing or nonsensical accuracy is treated as unusable`() {
        assertFalse(ProximityCalculator.isUsable(Float.NaN, radius, buffer))
        assertFalse(ProximityCalculator.isUsable(Float.POSITIVE_INFINITY, radius, buffer))
        assertFalse(ProximityCalculator.isUsable(-1f, radius, buffer))
    }

    @Test
    fun `a zero accuracy is accepted so mock providers remain testable`() {
        assertTrue(ProximityCalculator.isUsable(0f, radius, buffer))
    }

    @Test
    fun `a NaN accuracy cannot flip the state`() {
        assertEquals(
            ProximityState.OUTSIDE,
            evaluate(ProximityState.OUTSIDE, distanceMeters = 10f, accuracyMeters = Float.NaN),
        )
    }
}
