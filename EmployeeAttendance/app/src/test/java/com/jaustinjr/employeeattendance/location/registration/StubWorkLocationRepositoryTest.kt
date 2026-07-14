package com.jaustinjr.employeeattendance.location.registration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StubWorkLocationRepositoryTest {

    private fun location(id: String) = WorkLocation(
        id = id,
        name = "Loc $id",
        latitudeDegrees = 10.0,
        longitudeDegrees = 20.0,
        radiusMeters = 50f,
    )

    @Test
    fun `seeds with the default office as the active location`() {
        val repo = StubWorkLocationRepository()

        assertEquals(listOf(StubWorkLocationRepository.DEFAULT_OFFICE), repo.workLocations.value)
        assertEquals(StubWorkLocationRepository.DEFAULT_OFFICE, repo.activeWorkLocation.value)
    }

    @Test
    fun `register adds a new location`() {
        val repo = StubWorkLocationRepository()

        repo.registerWorkLocation(location("b"))

        assertTrue(repo.workLocations.value.any { it.id == "b" })
    }

    @Test
    fun `register replaces an existing location with the same id`() {
        val repo = StubWorkLocationRepository()
        repo.registerWorkLocation(location("b"))

        val renamed = location("b").copy(name = "Renamed")
        repo.registerWorkLocation(renamed)

        assertEquals(1, repo.workLocations.value.count { it.id == "b" })
        assertEquals("Renamed", repo.workLocations.value.first { it.id == "b" }.name)
    }

    @Test
    fun `setActive selects a known location`() {
        val repo = StubWorkLocationRepository()
        repo.registerWorkLocation(location("b"))

        repo.setActiveWorkLocation("b")

        assertEquals("b", repo.activeWorkLocation.value?.id)
    }

    @Test
    fun `setActive ignores an unknown id`() {
        val repo = StubWorkLocationRepository()
        val before = repo.activeWorkLocation.value

        repo.setActiveWorkLocation("does-not-exist")

        assertEquals(before, repo.activeWorkLocation.value)
    }

    @Test
    fun `removing the active location falls back to another`() {
        val repo = StubWorkLocationRepository()
        repo.registerWorkLocation(location("b"))
        repo.setActiveWorkLocation("b")

        repo.removeWorkLocation("b")

        assertTrue(repo.workLocations.value.none { it.id == "b" })
        assertEquals(StubWorkLocationRepository.DEFAULT_OFFICE, repo.activeWorkLocation.value)
    }

    @Test
    fun `removing the last location clears the active selection`() {
        val repo = StubWorkLocationRepository()

        repo.removeWorkLocation(StubWorkLocationRepository.DEFAULT_OFFICE.id)

        assertTrue(repo.workLocations.value.isEmpty())
        assertNull(repo.activeWorkLocation.value)
    }
}
