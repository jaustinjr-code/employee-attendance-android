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
 * One destination in the app's navigation hierarchy, and everything the chrome outside the
 * `NavHost` needs to know about it.
 *
 * [parent] is the destination's place in the *hierarchy* — the screen it sits beneath
 * conceptually — which is not the same thing as the entry beneath it on the back stack. The back
 * stack is history and can reach the same destination by several paths (Worksites is reachable
 * from Attendance and from the location detail screen); the hierarchy is fixed. The app bar's up
 * button still pops history, so it returns the user to where they actually came from; the
 * hierarchy only answers whether that button should be there at all.
 *
 * The type is recursive rather than a flat "is this home?" flag so that nesting a screen under
 * something other than the root is a one-line change here instead of a new special case at each
 * call site.
 */
data class AppDestination(
    val route: String,
    @StringRes val titleRes: Int,
    val parent: AppDestination?,
) {
    /** The root has no parent; every other destination shows an up button. */
    val isRoot: Boolean get() = parent == null

    /**
     * This destination's ancestors, nearest first, ending at the root.
     *
     * Empty for the root itself. The size is the destination's depth, which is what an up-button
     * policy other than "pop one" (a breadcrumb, or Material's "up to parent" behaviour) would be
     * written against.
     */
    val ancestors: List<AppDestination>
        get() = generateSequence(parent) { it.parent }.toList()
}

/**
 * The app's destination tree.
 *
 * Attendance is the root and every other screen currently hangs directly off it, so the tree is
 * one level deep today. It is expressed as parent links rather than as a flat set of "child
 * routes" so that it stays the single description of the hierarchy as the graph grows: to nest a
 * screen, give it a different [AppDestination.parent] and nothing else in the app bar changes.
 *
 * Declaration order matters — a parent must be declared before its children, since the children
 * reference it.
 */
object AppNavGraph {

    val Attendance = AppDestination(
        route = AttendanceRoute,
        titleRes = R.string.attendance_title,
        parent = null,
    )

    val LocationDetail = AppDestination(
        route = LocationDetailRoute,
        titleRes = R.string.location_detail_title,
        parent = Attendance,
    )

    val Worksites = AppDestination(
        route = WorksitesRoute,
        titleRes = R.string.worksites_title,
        parent = Attendance,
    )

    val WorksiteRegistration = AppDestination(
        route = WorksiteRegistrationRoute,
        titleRes = R.string.worksite_registration_title,
        parent = Attendance,
    )

    val Settings = AppDestination(
        route = SettingsRoute,
        titleRes = R.string.settings_title,
        parent = Attendance,
    )

    /** Where the `NavHost` starts, and what an unrecognised route falls back to. */
    val root: AppDestination = Attendance

    /** Every destination in the graph, in declaration order. */
    val all: List<AppDestination> = listOf(
        Attendance,
        LocationDetail,
        Worksites,
        WorksiteRegistration,
        Settings,
    )

    private val byRoute: Map<String, AppDestination> = all.associateBy { it.route }

    /** The destination for [route], or null if the graph does not know it. */
    fun destinationFor(route: String?): AppDestination? = byRoute[route]
}

/**
 * The destination for [route], falling back to the root.
 *
 * The fallback covers two cases that behave identically: `currentBackStackEntryAsState()` is null
 * on the very first frame, before navigation has settled, and a route the graph has not been told
 * about (a destination added to the `NavHost` without a matching entry here) must still render
 * something rather than crash.
 */
fun destinationOrRoot(route: String?): AppDestination =
    AppNavGraph.destinationFor(route) ?: AppNavGraph.root

/**
 * Whether the destination at [route] sits below the root, and so shows an up button in place of
 * the account affordance.
 *
 * This is a pure function of the current destination rather than state a screen pushes into the
 * app bar, for the same reason [appBarTitleResFor] is: during a predictive-back gesture two
 * destinations are composed at once, and anything the app bar derives from per-screen state can be
 * written last by the screen the user is leaving.
 *
 * An unrecognised or not-yet-resolved route resolves to the root, so the up button never appears
 * on a frame where the back stack has nothing to pop.
 */
fun isChildDestination(route: String?): Boolean = !destinationOrRoot(route).isRoot

// Route strings as Navigation generates them from the @Serializable destination types, so the
// graph above can't drift from the actual routes the way hand-written string literals would.
internal val AttendanceRoute: String = routeOf<Attendance>()
internal val LocationDetailRoute: String = routeOf<LocationDetail>()
internal val WorksitesRoute: String = routeOf<Worksites>()
internal val WorksiteRegistrationRoute: String = routeOf<WorksiteRegistration>()
internal val SettingsRoute: String = routeOf<Settings>()

private inline fun <reified T : Any> routeOf(): String = serializer<T>().descriptor.serialName
