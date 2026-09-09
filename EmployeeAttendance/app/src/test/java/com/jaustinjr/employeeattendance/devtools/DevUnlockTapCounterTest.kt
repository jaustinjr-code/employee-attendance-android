package com.jaustinjr.employeeattendance.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevUnlockTapCounterTest {

    private val counter = DevUnlockTapCounter(requiredTaps = 5, windowMillis = 3_000L)

    @Test
    fun `five taps within the window unlock`() {
        repeat(4) { index ->
            assertFalse("tap ${index + 1} should not unlock", counter.onTap(index * 100L))
        }
        assertTrue(counter.onTap(400L))
    }

    @Test
    fun `only the fifth tap unlocks, not later ones in the same run`() {
        repeat(4) { counter.onTap(it * 100L) }
        assertTrue(counter.onTap(400L))
        // The run resets on unlock, so the sixth tap starts counting again rather than re-firing.
        assertFalse(counter.onTap(500L))
    }

    @Test
    fun `a gap longer than the window restarts the run`() {
        repeat(4) { counter.onTap(it * 100L) }
        // Would have been the fifth tap, but it arrives too late to continue the run.
        assertFalse(counter.onTap(300L + 3_001L))
        // ...and is instead the first of a new one, so four more are still needed.
        repeat(3) { assertFalse(counter.onTap(3_400L + it * 100L)) }
        assertTrue(counter.onTap(3_700L))
    }

    @Test
    fun `a tap exactly at the window boundary still continues the run`() {
        counter.onTap(0L)
        repeat(3) { assertFalse(counter.onTap(3_000L * (it + 1))) }
        assertTrue(counter.onTap(12_000L))
    }

    @Test
    fun `remaining taps count down and reset on unlock`() {
        assertEquals(5, counter.remainingTaps)
        counter.onTap(0L)
        assertEquals(4, counter.remainingTaps)
        repeat(3) { counter.onTap((it + 1) * 100L) }
        assertEquals(1, counter.remainingTaps)
        assertTrue(counter.onTap(400L))
        assertEquals(5, counter.remainingTaps)
    }

    @Test
    fun `reset abandons an in-progress run`() {
        repeat(4) { counter.onTap(it * 100L) }
        counter.reset()
        assertEquals(5, counter.remainingTaps)
        repeat(4) { assertFalse(counter.onTap(500L + it * 100L)) }
        assertTrue(counter.onTap(900L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive tap requirement`() {
        DevUnlockTapCounter(requiredTaps = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive window`() {
        DevUnlockTapCounter(windowMillis = 0L)
    }
}
