package com.jaustinjr.employeeattendance.ui.splash

import com.jaustinjr.employeeattendance.testutil.findAppModuleDir
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SplashTimingTest {

    @Test
    fun readyMidAnimation_holdsForTheRest() {
        assertEquals(
            650L,
            SplashTiming.remainingIconAnimationMillis(
                iconAnimationStartMillis = 10_000L,
                iconAnimationDurationMillis = 950L,
                nowEpochMillis = 10_300L,
            ),
        )
    }

    @Test
    fun readyAfterTheAnimation_removesAtOnce() {
        assertEquals(
            0L,
            SplashTiming.remainingIconAnimationMillis(
                iconAnimationStartMillis = 10_000L,
                iconAnimationDurationMillis = 950L,
                nowEpochMillis = 12_000L,
            ),
        )
    }

    @Test
    fun belowAndroid12_nothingAnimates_soNothingIsHeld() {
        // The compat provider reports 0 for both below API 31.
        assertEquals(
            0L,
            SplashTiming.remainingIconAnimationMillis(
                iconAnimationStartMillis = 0L,
                iconAnimationDurationMillis = 0L,
                nowEpochMillis = 12_000L,
            ),
        )
    }

    @Test
    fun noAnimationDuration_isNotHeld() {
        assertEquals(
            0L,
            SplashTiming.remainingIconAnimationMillis(
                iconAnimationStartMillis = 10_000L,
                iconAnimationDurationMillis = 0L,
                nowEpochMillis = 10_001L,
            ),
        )
    }

    @Test
    fun aClockSetBackMidLaunch_holdsNoLongerThanTheAnimation() {
        assertEquals(
            950L,
            SplashTiming.remainingIconAnimationMillis(
                iconAnimationStartMillis = 1_791_483_756_684L,
                iconAnimationDurationMillis = 950L,
                nowEpochMillis = 1_791_483_000_000L,
            ),
        )
    }

    @Test
    fun theSystemSplashAndTheThemeAgreeOnTheIconDuration() {
        val theme = File(findAppModuleDir(), "src/main/res/values/themes.xml").readText()
        val declared = Regex("""windowSplashScreenAnimationDuration">(\d+)<""")
            .find(theme)!!.groupValues[1].toInt()
        assertEquals(SplashTiming.ICON_ANIMATION_MILLIS, declared)
    }
}
