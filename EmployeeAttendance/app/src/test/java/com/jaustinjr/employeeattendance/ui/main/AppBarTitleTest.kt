package com.jaustinjr.employeeattendance.ui.main

import com.jaustinjr.employeeattendance.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app bar title must be a total function of the current destination. Anything the title depends
 * on beyond the route is an ordering hazard, because predictive back composes the outgoing and
 * incoming destinations at the same time.
 */
class AppBarTitleTest {

    @Test
    fun `every destination maps to its own title`() {
        assertEquals(R.string.attendance_title, appBarTitleResFor(AttendanceRoute))
        assertEquals(R.string.location_detail_title, appBarTitleResFor(LocationDetailRoute))
        assertEquals(R.string.worksites_title, appBarTitleResFor(WorksitesRoute))
        assertEquals(
            R.string.worksite_registration_title,
            appBarTitleResFor(WorksiteRegistrationRoute),
        )
        assertEquals(R.string.settings_title, appBarTitleResFor(SettingsRoute))
    }

    @Test
    fun `destination titles are distinct`() {
        val titles = listOf(
            AttendanceRoute,
            LocationDetailRoute,
            WorksitesRoute,
            WorksiteRegistrationRoute,
            SettingsRoute,
        ).map { appBarTitleResFor(it) }

        // A copy-paste in the when would otherwise silently give two screens the same header.
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `routes are the fully qualified destination types navigation generates`() {
        // Guards the mapping against a rename or package move silently falling through to the
        // start-destination default, which would look exactly like this bug.
        assertEquals("com.jaustinjr.employeeattendance.Attendance", AttendanceRoute)
        assertEquals("com.jaustinjr.employeeattendance.LocationDetail", LocationDetailRoute)
        assertEquals("com.jaustinjr.employeeattendance.Worksites", WorksitesRoute)
        assertEquals(
            "com.jaustinjr.employeeattendance.WorksiteRegistration",
            WorksiteRegistrationRoute,
        )
        assertEquals("com.jaustinjr.employeeattendance.Settings", SettingsRoute)
    }

    @Test
    fun `an unresolved route falls back to the start destination title`() {
        // currentBackStackEntryAsState() is null on the first frame, before nav has settled.
        assertEquals(R.string.attendance_title, appBarTitleResFor(null))
        assertEquals(R.string.attendance_title, appBarTitleResFor("com.example.NotADestination"))
    }

    @Test
    fun `the title depends on nothing but the route`() {
        // Same input, same output, regardless of what happened before — the property that push-based
        // per-screen title state did not have. Interleaving the calls the way a predictive-back
        // gesture interleaves two composed destinations must not change any answer.
        val worksites = appBarTitleResFor(WorksitesRoute)
        val attendance = appBarTitleResFor(AttendanceRoute)

        assertEquals(worksites, appBarTitleResFor(WorksitesRoute))
        assertEquals(attendance, appBarTitleResFor(AttendanceRoute))
        assertEquals(worksites, appBarTitleResFor(WorksitesRoute))
        assertEquals(attendance, appBarTitleResFor(AttendanceRoute))
    }
}
