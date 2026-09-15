package com.jaustinjr.employeeattendance.statusupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultStatusUpdateRepositoryTest {

    private fun update(id: String, didToday: String = "did") = StatusUpdate(
        clockOutId = id,
        didToday = didToday,
        plannedTomorrow = "",
        couldNotDo = "",
        completedAtMillis = 1_000L,
    )

    @Test
    fun `saved updates are written to the data source and reloaded by a new repository`() {
        val local = InMemoryStatusUpdateLocalDataSource()
        DefaultStatusUpdateRepository(local).save(update("a@1"))

        val reloaded = DefaultStatusUpdateRepository(local)

        assertEquals(listOf(update("a@1")), reloaded.statusUpdates.value)
    }

    @Test
    fun `updateAnswers replaces the answers, stamps the edit time and persists`() {
        val local = InMemoryStatusUpdateLocalDataSource()
        val repository = DefaultStatusUpdateRepository(local)
        repository.save(update("a@1"))
        repository.save(update("b@2"))

        val updated = repository.updateAnswers("a@1", "new did", "new plan", "new blocked", 9_000L)

        assertTrue(updated)
        val edited = repository.statusUpdates.value.first()
        assertEquals(listOf("new did", "new plan", "new blocked"), edited.answers)
        assertEquals(9_000L, edited.editedAtMillis)
        assertEquals(1_000L, edited.completedAtMillis)
        assertEquals(update("b@2"), repository.statusUpdates.value[1])
        assertEquals(repository.statusUpdates.value, local.load())
    }

    @Test
    fun `updateAnswers for an unknown shift changes nothing`() {
        val repository = DefaultStatusUpdateRepository()
        repository.save(update("a@1"))

        assertFalse(repository.updateAnswers("missing@9", "x", "y", "z", 9_000L))
        assertEquals(listOf(update("a@1")), repository.statusUpdates.value)
    }

    @Test
    fun `clearAll empties the repository and the data source`() {
        val local = InMemoryStatusUpdateLocalDataSource()
        val repository = DefaultStatusUpdateRepository(local)
        repository.save(update("a@1"))

        repository.clearAll()

        assertTrue(repository.statusUpdates.value.isEmpty())
        assertTrue(local.load().isEmpty())
    }
}
