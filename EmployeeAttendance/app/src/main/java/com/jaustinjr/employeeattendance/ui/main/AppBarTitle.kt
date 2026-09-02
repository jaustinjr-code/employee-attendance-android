package com.jaustinjr.employeeattendance.ui.main

import androidx.annotation.StringRes
import com.jaustinjr.employeeattendance.Attendance
import com.jaustinjr.employeeattendance.LocationDetail
import com.jaustinjr.employeeattendance.R
import com.jaustinjr.employeeattendance.Settings
import com.jaustinjr.employeeattendance.Worksites
import com.jaustinjr.employeeattendance.WorksiteRegistration
import kotlinx.serialization.serializer

/**
 * The app bar title for a rendered navigation destination, keyed by its route.
 *
 * This is deliberately a *pure function of the current destination* rather than state each screen
 * pushes into the app bar from a side effect. The app bar lives outside the `NavHost`, and a
 * predictive-back gesture keeps the outgoing and incoming destinations composed at the same time —
 * so with push-based state the displayed title depends on the relative ordering of two screens'
 * effects, and the screen the user is leaving can write last and win. Deriving the title from the
 * destination removes the ordering question entirely: there is one writer, and it is whatever the
 * back stack currently says is on screen.
 *
 * An unrecognised or not-yet-resolved route (null on the very first frame) falls back to the start
 * destination's title.
 */
@StringRes
fun appBarTitleResFor(route: String?): Int = when (route) {
    AttendanceRoute -> R.string.attendance_title
    LocationDetailRoute -> R.string.location_detail_title
    WorksitesRoute -> R.string.worksites_title
    WorksiteRegistrationRoute -> R.string.worksite_registration_title
    SettingsRoute -> R.string.settings_title
    else -> R.string.attendance_title
}

// Route strings as Navigation generates them from the @Serializable destination types, so the
// mapping above can't drift from the actual routes the way hand-written string literals would.
internal val AttendanceRoute: String = routeOf<Attendance>()
internal val LocationDetailRoute: String = routeOf<LocationDetail>()
internal val WorksitesRoute: String = routeOf<Worksites>()
internal val WorksiteRegistrationRoute: String = routeOf<WorksiteRegistration>()
internal val SettingsRoute: String = routeOf<Settings>()

private inline fun <reified T : Any> routeOf(): String = serializer<T>().descriptor.serialName
