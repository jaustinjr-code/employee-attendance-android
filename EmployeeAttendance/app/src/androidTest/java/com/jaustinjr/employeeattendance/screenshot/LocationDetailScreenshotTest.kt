package com.jaustinjr.employeeattendance.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.proximity.ProximityState
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.ui.LocationDetailContent
import com.jaustinjr.employeeattendance.location.ui.LocationUiState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Golden-image coverage for the location detail screen in each of the states that change its layout:
 * full access (map card shown), when-in-use (map replaced by the locked notice plus the degraded
 * banner), and no registered location (empty state).
 *
 * The screen renders a formatted clock-in timestamp, so the default timezone and locale are pinned
 * for the duration of the test. Without that the golden would encode whatever timezone the recording
 * machine happened to use and fail on any CI runner set to another one.
 */
class LocationDetailScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun pinTimeFormatting() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreTimeFormatting() {
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    private val office = WorkLocation(
        id = "downtown-office",
        name = "Downtown Office",
        address = "123 Market St",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    /** Fixed instant so the rendered "last clocked in" line is identical on every run. */
    private val fixedClockIn = 1_716_552_000_000L

    @Test
    fun alwaysAccess_showsMapAndClockIn() {
        composeRule.captureAndAssert("location-detail-always") {
            LocationDetailContent(
                state = LocationUiState(
                    activeWorkLocation = office,
                    proximity = ProximityState.INSIDE,
                    accessLevel = LocationAccessLevel.ALWAYS,
                    isPrecise = true,
                    lastClockInEpochMillis = fixedClockIn,
                ),
            )
        }
    }

    @Test
    fun whenInUse_showsLockedMapAndDegradedNotice() {
        composeRule.captureAndAssert("location-detail-when-in-use") {
            LocationDetailContent(
                state = LocationUiState(
                    activeWorkLocation = office,
                    proximity = ProximityState.OUTSIDE,
                    accessLevel = LocationAccessLevel.WHEN_IN_USE,
                    isPrecise = true,
                    lastClockInEpochMillis = null,
                ),
            )
        }
    }

    /**
     * Granted but coarse-only. Auto clock-in is unreliable at ~1-3 km accuracy, so the screen warns
     * without blocking — a state distinguished from the others purely by what it renders.
     */
    @Test
    fun approximateOnly_showsApproximateNotice() {
        composeRule.captureAndAssert("location-detail-approximate") {
            LocationDetailContent(
                state = LocationUiState(
                    activeWorkLocation = office,
                    proximity = ProximityState.INSIDE,
                    accessLevel = LocationAccessLevel.ALWAYS,
                    isPrecise = false,
                    lastClockInEpochMillis = fixedClockIn,
                ),
            )
        }
    }

    @Test
    fun noRegisteredLocation_showsEmptyState() {
        composeRule.captureAndAssert("location-detail-empty") {
            LocationDetailContent(state = LocationUiState())
        }
    }
}
