package com.jaustinjr.employeeattendance.location.proximity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `reset does not leave the target id behind in the store`() = runTest {
        // Regression for #21: reset() used to persist (UNKNOWN, oldTargetId), so a worksite id
        // outlived the state it labelled — including after the worksite itself was deleted.
        val store = FakeStore()
        val repo = ProximityRepository(store)

        repo.onGeofenceTransition("deleted-site", ProximityState.INSIDE)
        repo.reset()

        assertEquals(ProximityState.UNKNOWN, store.state)
        assertNull(store.targetId)
    }

    @Test
    fun `clear erases state and target id`() = runTest {
        val store = FakeStore()
        val repo = ProximityRepository(store)

        repo.onGeofenceTransition("deleted-site", ProximityState.INSIDE)
        repo.clear()

        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertEquals(ProximityState.UNKNOWN, store.state)
        assertNull(store.targetId)
    }

    @Test
    fun `clear erases a target id left over from an already-UNKNOWN state`() = runTest {
        // reset() short-circuits on UNKNOWN, so a target id persisted by an older build (or by a
        // reset that ran before this fix) can still be sitting on disk at delete-all-data time.
        // clear() must be unconditional.
        val store = FakeStore(state = ProximityState.UNKNOWN, targetId = "deleted-site")
        val repo = ProximityRepository(store)

        repo.clear()

        assertNull(store.targetId)
        assertEquals(ProximityState.UNKNOWN, store.state)
    }

    @Test
    fun `clear emits no event even when INSIDE`() = runTest {
        // "Delete all data" is not a departure: the worksite the event would name is being deleted,
        // and the attendance log it would write to is wiped in the same action.
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onGeofenceTransition("t1", ProximityState.INSIDE)
        repo.clear()
        runCurrent()

        assertEquals(listOf(ProximityEvent.Arrived("t1")), events)
    }

    @Test
    fun `reset after clear is a no-op and cannot resurrect the target id`() = runTest {
        // Mirrors the real delete-all-data ordering: SettingsViewModel clears proximity first, then
        // clearing the work locations makes LocationFeatureCoordinator call reset() asynchronously.
        val store = FakeStore()
        val repo = ProximityRepository(store)
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onGeofenceTransition("deleted-site", ProximityState.INSIDE)
        repo.clear()
        repo.reset()
        runCurrent()

        assertNull(store.targetId)
        assertFalse(events.contains(ProximityEvent.Departed("deleted-site")))
    }

    @Test
    fun `switching target while inside does not emit a departed for the new target`() = runTest {
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        // Clocked in at worksite A.
        repo.onGeofenceTransition("A", ProximityState.INSIDE)
        // The active worksite switches to B; the engine next evaluates against B while the stale
        // state is still INSIDE (for A). Without the target-change guard this would emit
        // Departed("B") — the reported bug. (Exercised via the geofence path so it stays a pure JVM
        // test; onLocation shares the same guard but needs android.location.Location.)
        repo.onGeofenceTransition("B", ProximityState.OUTSIDE)
        runCurrent()

        // The stale INSIDE (for A) must not produce a Departed tagged with B — the switch flow
        // handles clocking out of A explicitly.
        assertEquals(listOf(ProximityEvent.Arrived("A")), events)
        assertFalse(events.contains(ProximityEvent.Departed("B")))
        assertEquals(ProximityState.OUTSIDE, repo.proximity.value)
    }

    @Test
    fun `committed transition is persisted to the store`() = runTest {
        val store = FakeStore()
        val repo = ProximityRepository(store)

        repo.onGeofenceTransition("t1", ProximityState.INSIDE)

        assertEquals(ProximityState.INSIDE, store.state)
        assertEquals("t1", store.targetId)
    }

    @Test
    fun `reset from a non-inside state emits nothing`() = runTest {
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onGeofenceTransition("t1", ProximityState.OUTSIDE) // UNKNOWN -> OUTSIDE, no Departed
        repo.reset()
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
    }

    @Test
    fun `concurrent identical transitions settle on that state`() {
        val store = FakeStore()
        val repo = ProximityRepository(store)

        val threads = (1..50).map {
            Thread { repeat(100) { repo.onGeofenceTransition("t1", ProximityState.INSIDE) } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(ProximityState.INSIDE, repo.proximity.value)
        // State and persisted store are written together under the lock, so they must agree.
        assertEquals(repo.proximity.value, store.state)
    }

    @Test
    fun `concurrent mixed transitions never corrupt state`() {
        val store = FakeStore()
        val repo = ProximityRepository(store)

        val threads = (1..20).map {
            Thread {
                repeat(200) { i ->
                    val state = if (i % 2 == 0) ProximityState.INSIDE else ProximityState.OUTSIDE
                    repo.onGeofenceTransition("t1", state)
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertTrue(repo.proximity.value in listOf(ProximityState.INSIDE, ProximityState.OUTSIDE))
        assertEquals(repo.proximity.value, store.state)
    }
}
