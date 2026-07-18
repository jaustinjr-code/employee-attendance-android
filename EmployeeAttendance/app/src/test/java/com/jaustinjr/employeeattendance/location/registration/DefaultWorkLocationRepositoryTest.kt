package com.jaustinjr.employeeattendance.location.registration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultWorkLocationRepositoryTest {

    /** In-memory [WorkLocationLocalDataSource] so persistence can be asserted without Android. */
    private class FakeLocalDataSource(
        var snapshot: StoredWorkLocations = StoredWorkLocations(emptyList(), null),
    ) : WorkLocationLocalDataSource {
        override fun load(): StoredWorkLocations = snapshot
        override fun save(locations: List<WorkLocation>, activeId: String?) {
            snapshot = StoredWorkLocations(locations, activeId)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun office(id: String, name: String = id) =
        WorkLocation(id, name, null, 37.0, -122.0, 100f)

    private fun repo(local: FakeLocalDataSource = FakeLocalDataSource()) =
        DefaultWorkLocationRepository(local = local, ioScope = scope)

    @Test
    fun `register persists and becomes active when none was active`() {
        val local = FakeLocalDataSource()
        val repo = repo(local)

        repo.registerWorkLocation(office("a"))

        assertEquals("a", repo.activeWorkLocation.value?.id)
        assertEquals(listOf("a"), local.snapshot.locations.map { it.id })
        assertEquals("a", local.snapshot.activeId)
    }

    @Test
    fun `state is seeded from the local store on construction`() {
        val a = office("a")
        val b = office("b")
        val local = FakeLocalDataSource(StoredWorkLocations(listOf(a, b), "b"))

        val repo = repo(local)

        assertEquals(listOf("a", "b"), repo.workLocations.value.map { it.id })
        assertEquals("b", repo.activeWorkLocation.value?.id)
    }

    @Test
    fun `setActive updates the active selection and persists it`() {
        val local = FakeLocalDataSource(StoredWorkLocations(listOf(office("a"), office("b")), "a"))
        val repo = repo(local)

        repo.setActiveWorkLocation("b")

        assertEquals("b", repo.activeWorkLocation.value?.id)
        assertEquals("b", local.snapshot.activeId)
    }

    @Test
    fun `setActive with an unknown id is a no-op`() {
        val local = FakeLocalDataSource(StoredWorkLocations(listOf(office("a")), "a"))
        val repo = repo(local)

        repo.setActiveWorkLocation("nope")

        assertEquals("a", repo.activeWorkLocation.value?.id)
    }

    @Test
    fun `removing the active location promotes another`() {
        val local = FakeLocalDataSource(StoredWorkLocations(listOf(office("a"), office("b")), "a"))
        val repo = repo(local)

        repo.removeWorkLocation("a")

        assertEquals(listOf("b"), repo.workLocations.value.map { it.id })
        assertEquals("b", repo.activeWorkLocation.value?.id)
    }

    @Test
    fun `removing the last location clears the active selection`() {
        val local = FakeLocalDataSource(StoredWorkLocations(listOf(office("a")), "a"))
        val repo = repo(local)

        repo.removeWorkLocation("a")

        assertTrue(repo.workLocations.value.isEmpty())
        assertNull(repo.activeWorkLocation.value)
        assertNull(local.snapshot.activeId)
    }

    @Test
    fun `re-registering the same id replaces it in place`() {
        val local = FakeLocalDataSource()
        val repo = repo(local)
        repo.registerWorkLocation(office("a", name = "Old"))

        repo.registerWorkLocation(office("a", name = "New"))

        assertEquals(1, repo.workLocations.value.size)
        assertEquals("New", repo.activeWorkLocation.value?.name)
    }
}
