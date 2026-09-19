package com.jaustinjr.employeeattendance.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destination hierarchy is what the app bar's navigation slot is derived from, so these tests
 * pin the shape of the tree rather than any one screen's chrome.
 *
 * They are written against the parent links, not against the current one-level-deep layout, so
 * they keep holding when a screen is nested under something other than the root.
 */
class AppNavGraphTest {

    @Test
    fun `attendance is the root and the first tab`() {
        assertSame(AppNavGraph.Attendance, AppNavGraph.root)
        assertTrue(AppNavGraph.root.isTopLevel)
        assertSame(AppNavGraph.root, AppNavGraph.topLevel.first())
    }

    @Test
    fun `the top-level destinations are exactly the bottom bar tabs`() {
        // A parentless destination that is not a tab would show the account affordance and the
        // bottom bar while being unreachable from it.
        assertEquals(
            listOf(AppNavGraph.Attendance, AppNavGraph.Reports),
            AppNavGraph.all.filter { it.isTopLevel },
        )
        assertEquals(AppNavGraph.topLevel, AppNavGraph.all.filter { it.isTopLevel })
    }

    @Test
    fun `every destination resolves to the tab it lives under`() {
        AppNavGraph.all.forEach { destination ->
            val tab = destination.topLevelAncestor
            assertTrue("${destination.route} maps to a non-tab", tab in AppNavGraph.topLevel)
        }
        assertSame(AppNavGraph.Attendance, AppNavGraph.Worksites.topLevelAncestor)
        assertSame(AppNavGraph.Attendance, AppNavGraph.StatusUpdateEdit.topLevelAncestor)
        assertSame(AppNavGraph.Reports, AppNavGraph.Reports.topLevelAncestor)
    }

    @Test
    fun `every parent chain terminates at a tab`() {
        // A cycle here would hang `ancestors`, and a chain ending anywhere else would mean a
        // destination the user cannot walk up out of.
        AppNavGraph.all.forEach { destination ->
            val ancestors = destination.ancestors

            assertTrue(
                "${destination.route} has more ancestors than the graph has destinations",
                ancestors.size < AppNavGraph.all.size,
            )
            if (destination.isTopLevel) {
                assertEquals(emptyList<AppDestination>(), ancestors)
            } else {
                assertTrue(ancestors.last() in AppNavGraph.topLevel)
            }
        }
    }

    @Test
    fun `every parent is itself in the graph`() {
        // A destination built outside `all` would be invisible to route lookup while still being
        // reachable as somebody's parent.
        AppNavGraph.all.mapNotNull { it.parent }.forEach { parent ->
            assertTrue(
                "${parent.route} is a parent but not in the graph",
                parent in AppNavGraph.all,
            )
        }
    }

    @Test
    fun `routes are unique`() {
        val routes = AppNavGraph.all.map { it.route }

        // Duplicates would silently shadow each other in route lookup.
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `every destination resolves from its own route`() {
        AppNavGraph.all.forEach { destination ->
            assertSame(destination, destinationOrRoot(destination.route))
        }
    }

    @Test
    fun `an unknown or unresolved route falls back to the root`() {
        // currentBackStackEntryAsState() is null on the first frame, before nav has settled.
        assertNull(AppNavGraph.destinationFor(null))
        assertNull(AppNavGraph.destinationFor("com.example.NotADestination"))
        assertSame(AppNavGraph.root, destinationOrRoot(null))
        assertSame(AppNavGraph.root, destinationOrRoot("com.example.NotADestination"))
    }

    @Test
    fun `every destination below a tab is a child`() {
        // Tabs keep the account affordance; everything beneath them shows an up button instead.
        assertFalse(isChildDestination(AttendanceRoute))
        assertFalse(isChildDestination(ReportsRoute))
        assertTrue(isChildDestination(LocationDetailRoute))
        assertTrue(isChildDestination(WorksitesRoute))
        assertTrue(isChildDestination(WorksiteRegistrationRoute))
        assertTrue(isChildDestination(SettingsRoute))
    }

    @Test
    fun `an unresolved route shows no up button`() {
        // On the first frame the back stack has nothing to pop, so an up button would be a dead
        // control.
        assertFalse(isChildDestination(null))
        assertFalse(isChildDestination("com.example.NotADestination"))
    }

    @Test
    fun `child-ness follows the parent link, not the destination's identity`() {
        // The property the app bar actually depends on, stated once so nesting a screen deeper
        // cannot quietly change the answer for the screens above it.
        AppNavGraph.all.forEach { destination ->
            assertEquals(destination.parent != null, isChildDestination(destination.route))
        }
    }

    @Test
    fun `routes are the fully qualified destination types navigation generates`() {
        // Guards the graph against a rename or package move silently falling through to the root
        // fallback, which would look like the destination simply losing its title and up button.
        assertEquals("com.jaustinjr.employeeattendance.Attendance", AttendanceRoute)
        assertEquals("com.jaustinjr.employeeattendance.Reports", ReportsRoute)
        assertEquals("com.jaustinjr.employeeattendance.LocationDetail", LocationDetailRoute)
        assertEquals("com.jaustinjr.employeeattendance.Worksites", WorksitesRoute)
        assertEquals(
            "com.jaustinjr.employeeattendance.WorksiteRegistration",
            WorksiteRegistrationRoute,
        )
        assertEquals("com.jaustinjr.employeeattendance.Settings", SettingsRoute)
    }

    @Test
    fun `a destination with arguments resolves from its route pattern and from a query form`() {
        // Navigation reports a destination with a required argument as "<serialName>/{arg}".
        assertSame(
            AppNavGraph.StatusUpdateDetail,
            AppNavGraph.destinationFor("${StatusUpdateDetailRoute}/{clockOutId}"),
        )
        assertSame(
            AppNavGraph.StatusUpdateEdit,
            AppNavGraph.destinationFor("${StatusUpdateEditRoute}?clockOutId={clockOutId}"),
        )
    }

    @Test
    fun `status update screens nest under account`() {
        assertEquals(
            listOf(AppNavGraph.StatusUpdateDetail, AppNavGraph.Account, AppNavGraph.Attendance),
            AppNavGraph.StatusUpdateEdit.ancestors,
        )
        assertTrue(isChildDestination("${StatusUpdateEditRoute}/{clockOutId}"))
    }
}
