package com.jaustinjr.employeeattendance.location.proximity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProximityRepositoryTest {

    /** In-memory [ProximityStateStore] so tests can seed and inspect persistence. */
    private class FakeStore(
        var state: ProximityState = ProximityState.UNKNOWN,
        var targetId: String? = null,
    ) : ProximityStateStore {
        override fun load(): ProximityState = state
        override fun loadTargetId(): String? = targetId
        override fun save(state: ProximityState, targetId: String?) {
            this.state = state
            this.targetId = targetId
        }
    }

    @Test
    fun `entering emits Arrived and updates state`() = runTest {
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onGeofenceTransition("t1", ProximityState.INSIDE)
        runCurrent()

        assertEquals(ProximityState.INSIDE, repo.proximity.value)
        assertEquals(listOf(ProximityEvent.Arrived("t1")), events)
    }

    @Test
    fun `inside then outside emits Departed`() = runTest {
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onGeofenceTransition("t1", ProximityState.INSIDE)
        repo.onGeofenceTransition("t1", ProximityState.OUTSIDE)
        runCurrent()

        assertEquals(
            listOf(ProximityEvent.Arrived("t1"), ProximityEvent.Departed("t1")),
            events,
        )
    }

    @Test
    fun `repeated same-state transition does not re-emit`() = runTest {
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onGeofenceTransition("t1", ProximityState.INSIDE)
        repo.onGeofenceTransition("t1", ProximityState.INSIDE)
        runCurrent()

        assertEquals(listOf(ProximityEvent.Arrived("t1")), events)
    }

    @Test
    fun `exit after process death seeded INSIDE still emits Departed`() = runTest {
        // Simulates the cold-start-to-deliver-geofence scenario: state was persisted as INSIDE.
        val repo = ProximityRepository(FakeStore(state = ProximityState.INSIDE, targetId = "t1"))
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onGeofenceTransition("t1", ProximityState.OUTSIDE)
        runCurrent()

        assertEquals(listOf(ProximityEvent.Departed("t1")), events)
    }

    @Test
    fun `reset from INSIDE emits Departed and clears state`() = runTest {
        val store = FakeStore()
        val repo = ProximityRepository(store)
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onGeofenceTransition("t1", ProximityState.INSIDE)
        repo.reset()
        runCurrent()

        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertTrue(events.contains(ProximityEvent.Departed("t1")))
        assertEquals(ProximityState.UNKNOWN, store.state)
    }

    @Test
    fun `committed transition is persisted to the store`() = runTest {
        val store = FakeStore()
        val repo = ProximityRepository(store)

        repo.onGeofenceTransition("t1", ProximityState.INSIDE)

        assertEquals(ProximityState.INSIDE, store.state)
        assertEquals("t1", store.targetId)
    }
}
