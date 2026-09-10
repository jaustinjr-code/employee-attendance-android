package com.jaustinjr.employeeattendance.devtools

/**
 * Counts the hidden "tap the app bar title N times" gesture that reveals developer settings.
 *
 * Pure and clock-injected (the caller passes the timestamp) so the unlock policy is JVM-testable
 * without a framework clock. Taps must arrive within [windowMillis] of each other; a longer gap
 * restarts the run, so an idle user who taps the title twice a day never trips it.
 *
 * ### Why the window is generous
 * It was originally 3 s, and the gesture was unusable in the field: someone tapping deliberately —
 * pausing to check whether anything happened, because the gesture gives no feedback until
 * [FEEDBACK_THRESHOLD_TAPS] — exceeds 3 s between taps and silently restarts the run every time, no
 * matter how many times they tap. Measured on device: taps 3.5 s apart never unlock however long
 * you keep going, while the same taps 0.6 s apart unlock on the fifth. The window exists only to
 * stop unrelated taps accumulating across a session, which a much longer one still does.
 *
 * Instances are cheap and hold only the run state; keep one per app bar.
 */
class DevUnlockTapCounter(
    private val requiredTaps: Int = DEFAULT_REQUIRED_TAPS,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    init {
        require(requiredTaps > 0) { "requiredTaps must be positive: $requiredTaps" }
        require(windowMillis > 0) { "windowMillis must be positive: $windowMillis" }
    }

    private var tapCount = 0
    private var lastTapMillis = 0L

    /** Taps still needed to unlock, after the most recent [onTap]. */
    var remainingTaps: Int = requiredTaps
        private set

    /**
     * Whether the caller should surface how many taps remain. False until the run is nearly
     * complete, so the gesture stays hidden from someone who taps the title once by accident.
     */
    val shouldShowProgress: Boolean
        get() = tapCount > 0 && remainingTaps in 1..FEEDBACK_THRESHOLD_TAPS

    /**
     * Records a tap at [nowMillis] (a monotonic clock such as `SystemClock.elapsedRealtime`).
     *
     * @return true exactly once per completed run, when this tap is the [requiredTaps]-th within
     *   the window. The run resets on unlock, so a further tap starts counting again.
     */
    fun onTap(nowMillis: Long): Boolean {
        val continuesRun = tapCount > 0 && nowMillis - lastTapMillis <= windowMillis
        tapCount = if (continuesRun) tapCount + 1 else 1
        lastTapMillis = nowMillis
        if (tapCount >= requiredTaps) {
            reset()
            return true
        }
        remainingTaps = requiredTaps - tapCount
        return false
    }

    /** Abandons the current run, e.g. when the user navigates away from the host screen. */
    fun reset() {
        tapCount = 0
        lastTapMillis = 0L
        remainingTaps = requiredTaps
    }

    companion object {
        /** Matches the long-established Android "tap Build number 5 times" gesture. */
        const val DEFAULT_REQUIRED_TAPS = 5

        /**
         * Long enough for a deliberate, unhurried tap run — including pausing to read the progress
         * toast — while still expiring so unrelated taps never accumulate. See the note on the
         * class about why this is not 3 s.
         */
        const val DEFAULT_WINDOW_MILLIS = 10_000L

        /** Start telling the user how many taps remain once they are this close. */
        const val FEEDBACK_THRESHOLD_TAPS = 3
    }
}
