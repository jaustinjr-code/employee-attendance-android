package com.jaustinjr.employeeattendance.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevUnlockTapCounterTest {

    private val counter = DevUnlockTapCounter(requiredTaps = 5, windowMillis = 3_000L)

    /** The production configuration, which is what the field bug was about. */
    private val defaultCounter = DevUnlockTapCounter()

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

    // ---------------------------------------------------------------- field regression

    /**
     * The bug this guards: the window was 3 s, and someone tapping deliberately — pausing to see
     * whether anything happened, because nothing was shown until this fix — exceeded it between
     * every pair of taps. The run silently restarted forever, so the gesture never fired however
     * many times they tapped. Measured on device before the fix: taps 3.5 s apart never unlocked.
     */
    @Test
    fun `a deliberate, unhurried tap run still unlocks at the default window`() {
        var now = 0L
        repeat(4) {
            now += 3_500L
            assertFalse("tap at ${now}ms should not unlock yet", defaultCounter.onTap(now))
        }
        now += 3_500L
        assertTrue("five deliberate taps must unlock", defaultCounter.onTap(now))
    }

    @Test
    fun `the default window tolerates a pause long enough to read the progress toast`() {
        // Toast.LENGTH_SHORT is ~2 s; a user who reads it and then taps again must not lose the run.
        var now = 0L
        repeat(4) {
            now += DevUnlockTapCounter.DEFAULT_WINDOW_MILLIS - 500L
            assertFalse(defaultCounter.onTap(now))
        }
        assertTrue(defaultCounter.onTap(now + DevUnlockTapCounter.DEFAULT_WINDOW_MILLIS - 500L))
    }

    @Test
    fun `an abandoned run still expires rather than accumulating across a session`() {
        var now = 0L
        repeat(4) { now += 1_000L; defaultCounter.onTap(now) }
        // Come back much later: the stale run must not combine with new taps into a surprise unlock.
        assertFalse(defaultCounter.onTap(now + DevUnlockTapCounter.DEFAULT_WINDOW_MILLIS + 1))
    }

    // ---------------------------------------------------------------- progress feedback

    @Test
    fun `progress stays hidden until the run is nearly complete`() {
        // One stray tap on the title must not reveal that a hidden gesture exists.
        defaultCounter.onTap(0L)
        assertFalse(defaultCounter.shouldShowProgress)
    }

    @Test
    fun `progress is offered for the last few taps and counts down`() {
        var now = 0L
        defaultCounter.onTap(now)
        now += 500L
        defaultCounter.onTap(now)
        assertTrue(defaultCounter.shouldShowProgress)
        assertEquals(3, defaultCounter.remainingTaps)

        now += 500L
        defaultCounter.onTap(now)
        assertEquals(2, defaultCounter.remainingTaps)
        assertTrue(defaultCounter.shouldShowProgress)
    }

    @Test
    fun `progress is not offered once the run has unlocked and reset`() {
        var now = 0L
        repeat(4) { now += 500L; defaultCounter.onTap(now) }
        assertTrue(defaultCounter.onTap(now + 500L))
        assertFalse("a completed run must not keep advertising progress", defaultCounter.shouldShowProgress)
    }
}
