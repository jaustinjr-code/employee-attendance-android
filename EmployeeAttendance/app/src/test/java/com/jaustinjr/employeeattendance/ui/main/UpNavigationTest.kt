package com.jaustinjr.employeeattendance.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [updateInterceptor] is the write-side counterpart to [performUp]'s id check. Without it, a
 * departing screen's delayed `onDispose(null)` (composition survives the ~700ms exit transition)
 * can clobber a freshly reopened screen's own registration if the two windows overlap — silently
 * and permanently, since the departing screen's `DisposableEffect` has already run and won't fire
 * again.
 */
class UpNavigationTest {

    @Test
    fun `registering with no prior interceptor stores it`() {
        val result = updateInterceptor(current = null, entryId = "a", onUp = {})

        assertEquals("a", result?.entryId)
    }

    @Test
    fun `the owner clearing its own registration succeeds`() {
        val handler = {}
        val current = updateInterceptor(current = null, entryId = "a", onUp = handler)

        val result = updateInterceptor(current, entryId = "a", onUp = null)

        assertNull(result)
    }

    @Test
    fun `a fresh registration always replaces whatever was there, even a different owner`() {
        val fromA = updateInterceptor(current = null, entryId = "a", onUp = {})

        val fromB = updateInterceptor(fromA, entryId = "b", onUp = {})

        assertEquals("b", fromB?.entryId)
    }

    @Test
    fun `a late clear from a stale owner cannot wipe a newer owner's registration`() {
        // This is the regression case: A registers, B registers over it (A is mid-exit, still
        // composed, and hasn't disposed yet), then A's delayed onDispose finally fires and tries
        // to clear — using A's own id, not knowing B has since taken over the slot.
        val aHandler = {}
        val bHandler = {}
        val afterA = updateInterceptor(current = null, entryId = "a", onUp = aHandler)
        val afterB = updateInterceptor(afterA, entryId = "b", onUp = bHandler)

        val afterStaleClearFromA = updateInterceptor(afterB, entryId = "a", onUp = null)

        assertEquals("b", afterStaleClearFromA?.entryId)
        assertEquals(bHandler, afterStaleClearFromA?.onUp)
    }

    @Test
    fun `clearing with no registration at all is a no-op`() {
        val result = updateInterceptor(current = null, entryId = "a", onUp = null)

        assertNull(result)
    }
}
