package com.jaustinjr.employeeattendance.ui.splash

/**
 * Timing for the launch sequence storyboarded in
 * `docs/employee-attendance-assets/splash/splash_motion_storyboard.png`.
 *
 * The system splash plays steps 2-4 (the animated icon, `drawable-v31/splash_icon_animated.xml`)
 * for [ICON_ANIMATION_MILLIS]; [BrandSplash] then plays steps 5-6 in the activity.
 */
object SplashTiming {

    /** Steps 2-4. Must equal `windowSplashScreenAnimationDuration` in `values/themes.xml`. */
    const val ICON_ANIMATION_MILLIS = 950

    /** Step 5: the icon lifts and the wordmark rises and fades in. */
    const val WORDMARK_MILLIS = 350

    /** Step 6: everything holds before the hand-off. */
    const val HOLD_MILLIS = 200

    /** Step 6: the splash eases inward and cross-fades to the first screen. */
    const val HAND_OFF_MILLIS = 200

    /**
     * How long after launch [BrandSplash] starts even if the system splash's exit listener has not
     * fired — it does not fire for a launch the system shows no splash for. Long enough that a
     * splash that is showing has been removed by then on all but very slow cold starts, where
     * starting early only means the wordmark is already in place when the splash lifts.
     */
    const val SYSTEM_SPLASH_FALLBACK_MILLIS = 2L * ICON_ANIMATION_MILLIS

    /** How far the icon lifts in step 5, as a fraction of the screen height. */
    const val ICON_LIFT_FRACTION = 0.06f

    /** How far the wordmark rises in step 5, in dp. */
    const val WORDMARK_RISE_DP = 24

    /** The splash's scale at the end of the hand-off ("eases inward"). */
    const val HAND_OFF_TARGET_SCALE = 0.92f

    /**
     * How much longer the system splash must stay up for its icon animation to finish, given the
     * values `SplashScreenViewProvider` reports when the app is ready to draw.
     *
     * The start time is wall-clock epoch milliseconds (the compat library converts the platform's
     * `Instant`), so [nowEpochMillis] must be `System.currentTimeMillis()` — not
     * `SystemClock.uptimeMillis()`, which would hold the splash for decades.
     *
     * Below Android 12 the provider reports `0` for both start and duration (nothing animates), so
     * this is `0`. The result is clamped to `0..iconAnimationDurationMillis`: an app slower than the
     * animation removes the splash at once, and a wall-clock change mid-launch can never hold it
     * longer than the animation itself.
     */
    fun remainingIconAnimationMillis(
        iconAnimationStartMillis: Long,
        iconAnimationDurationMillis: Long,
        nowEpochMillis: Long,
    ): Long {
        if (iconAnimationStartMillis <= 0L || iconAnimationDurationMillis <= 0L) return 0L
        return (iconAnimationStartMillis + iconAnimationDurationMillis - nowEpochMillis)
            .coerceIn(0L, iconAnimationDurationMillis)
    }
}
