package com.jaustinjr.employeeattendance.devtools

import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionOverrideTest {

    @Test
    fun `OFF defers to the real grant`() {
        assertNull(PermissionOverride.OFF.toState())
    }

    @Test
    fun `every other value forces a concrete state`() {
        PermissionOverride.entries
            .filter { it != PermissionOverride.OFF }
            .forEach { override ->
                assertNotNullState(override)
            }
    }

    private fun assertNotNullState(override: PermissionOverride) {
        val state = override.toState()
        assertTrue("$override should force a state", state != null)
    }

    @Test
    fun `access level and precision map as named`() {
        with(PermissionOverride.DENIED.toState()!!) {
            assertEquals(LocationAccessLevel.NONE, accessLevel)
            assertFalse(isPrecise)
        }
        with(PermissionOverride.WHEN_IN_USE_APPROXIMATE.toState()!!) {
            assertEquals(LocationAccessLevel.WHEN_IN_USE, accessLevel)
            assertFalse(isPrecise)
        }
        with(PermissionOverride.WHEN_IN_USE_PRECISE.toState()!!) {
            assertEquals(LocationAccessLevel.WHEN_IN_USE, accessLevel)
            assertTrue(isPrecise)
        }
        with(PermissionOverride.ALWAYS_APPROXIMATE.toState()!!) {
            assertEquals(LocationAccessLevel.ALWAYS, accessLevel)
            assertFalse(isPrecise)
        }
        with(PermissionOverride.ALWAYS_PRECISE.toState()!!) {
            assertEquals(LocationAccessLevel.ALWAYS, accessLevel)
            assertTrue(isPrecise)
        }
    }

    @Test
    fun `the forced states cover every distinction the app makes`() {
        // Guards against adding a LocationAccessLevel and forgetting to expose it here, which would
        // leave a branch of the permission UI unreachable from developer settings.
        val covered = PermissionOverride.entries
            .mapNotNull { it.toState()?.accessLevel }
            .toSet()
        assertEquals(LocationAccessLevel.entries.toSet(), covered)
    }

    @Test
    fun `an unrecognised or missing persisted name degrades to OFF`() {
        assertEquals(PermissionOverride.OFF, PermissionOverride.fromName(null))
        assertEquals(PermissionOverride.OFF, PermissionOverride.fromName(""))
        assertEquals(PermissionOverride.OFF, PermissionOverride.fromName("RENAMED_IN_A_LATER_BUILD"))
    }

    @Test
    fun `a persisted name round-trips`() {
        PermissionOverride.entries.forEach { override ->
            assertEquals(override, PermissionOverride.fromName(override.name))
        }
    }
}
