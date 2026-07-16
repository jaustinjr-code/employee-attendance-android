package com.jaustinjr.employeeattendance.location.registration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationClockInRepositoryTest {

    @Test
    fun `record then read returns the timestamp`() {
        val repo = LocationClockInRepository()

        repo.recordClockIn("office", 1_000L)

        assertEquals(1_000L, repo.lastClockIns.value["office"])
    }

    @Test
    fun `latest record wins for the same id`() {
        val repo = LocationClockInRepository()

        repo.recordClockIn("office", 1_000L)
        repo.recordClockIn("office", 2_000L)

        assertEquals(2_000L, repo.lastClockIns.value["office"])
    }

    @Test
    fun `records for different ids are independent`() {
        val repo = LocationClockInRepository()

        repo.recordClockIn("a", 1_000L)
        repo.recordClockIn("b", 2_000L)

        assertEquals(1_000L, repo.lastClockIns.value["a"])
        assertEquals(2_000L, repo.lastClockIns.value["b"])
    }

    @Test
    fun `unknown id has no timestamp`() {
        val repo = LocationClockInRepository()
        assertNull(repo.lastClockIns.value["missing"])
    }

    @Test
    fun `default timestamp is near now`() {
        val repo = LocationClockInRepository()
        val before = System.currentTimeMillis()

        repo.recordClockIn("office")

        val recorded = repo.lastClockIns.value["office"]!!
        assertTrue(recorded in before..(System.currentTimeMillis()))
    }
}
