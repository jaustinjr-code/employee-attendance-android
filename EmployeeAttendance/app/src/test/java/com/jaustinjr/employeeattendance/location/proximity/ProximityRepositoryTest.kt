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
        repo.clear(emptySet())

        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertEquals(ProximityState.UNKNOWN, store.state)
        assertNull(store.targetId)
    }

    @Test
    fun `clear erases store residue that disagrees with the in-memory state`() = runTest {
        // Note the in-memory state here is OUTSIDE, not UNKNOWN — only the store is forced to
        // UNKNOWN with a target id still beside it. That mismatch is the point: clear() must erase
        // unconditionally rather than deciding from the state it happens to hold. (The genuinely
        // in-memory-UNKNOWN-with-a-target-id case is only reachable at construction, and is covered
        // by `an orphaned target id persisted alongside UNKNOWN is dropped on construction`.)
        val store = FakeStore()
        val repo = ProximityRepository(store)
        repo.onGeofenceTransition("deleted-site", ProximityState.OUTSIDE)
        store.targetId = "deleted-site"
        store.state = ProximityState.UNKNOWN

        repo.clear(emptySet())

        assertNull(store.targetId)
        assertEquals(ProximityState.UNKNOWN, store.state)
    }

    @Test
    fun `an orphaned target id persisted alongside UNKNOWN is dropped on construction`() = runTest {
        // Heals residue left on disk by builds from before this fix, for users who never happen to
        // run "Delete all data".
        val store = FakeStore(state = ProximityState.UNKNOWN, targetId = "stale-site")

        ProximityRepository(store)

        assertNull(store.targetId)
    }

    @Test
    fun `seeded INSIDE state keeps its target id on construction`() = runTest {
        // Guards the healing above against over-reach: the cold-start-to-deliver-a-geofence-EXIT
        // path depends on the seeded target id surviving.
        val store = FakeStore(state = ProximityState.INSIDE, targetId = "office")

        ProximityRepository(store)

        assertEquals("office", store.targetId)
        assertEquals(ProximityState.INSIDE, store.state)
    }

    @Test
    fun `a straggling geofence transition after clear cannot re-persist the deleted id`() = runTest {
        // Delete-all-data removes worksites synchronously, but the OS geofences are torn down
        // asynchronously — a transition already in flight must not write the deleted id back.
        val store = FakeStore()
        val repo = ProximityRepository(store)
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.clear(setOf("deleted-site"))
        repo.onGeofenceTransition("deleted-site", ProximityState.INSIDE)
        runCurrent()

        assertNull(store.targetId)
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the id the repository was tracking is suppressed even if the caller omits it`() = runTest {
        val store = FakeStore()
        val repo = ProximityRepository(store)

        repo.onGeofenceTransition("deleted-site", ProximityState.INSIDE)
        repo.clear(emptySet())
        repo.onGeofenceTransition("deleted-site", ProximityState.INSIDE)

        assertNull(store.targetId)
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
    }

    @Test
    fun `suppression does not block a newly registered worksite`() = runTest {
        // Suppression is by id, so it never blocks a target it does not name.
        val store = FakeStore()
        val repo = ProximityRepository(store)

        repo.clear(setOf("deleted-site"))
        repo.onGeofenceTransition("new-site", ProximityState.INSIDE)

        assertEquals(ProximityState.INSIDE, repo.proximity.value)
        assertEquals("new-site", store.targetId)
    }

    @Test
    fun `a straggler arriving after a new worksite reports in is still suppressed`() = runTest {
        // The gate used to retire the whole suppression list as soon as any unsuppressed target
        // reported in. Only one geofence is registered at a time, so the damaging order is exactly
        // this one: delete A, register D, D reports, and only THEN A's already-dispatched
        // transition lands. Retiring on D's report un-suppressed A just in time for the straggler
        // to wipe D's tracking and write the deleted id back to disk — reopening #21.
        val store = FakeStore()
        val repo = ProximityRepository(store)
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.clear(setOf("A"))
        repo.onGeofenceTransition("D", ProximityState.INSIDE)
        repo.onGeofenceTransition("A", ProximityState.OUTSIDE)
        runCurrent()

        // The deleted id must not be back on disk...
        assertEquals("D", store.targetId)
        // ...and D's tracking must be exactly as it was before the straggler landed.
        assertEquals(ProximityState.INSIDE, repo.proximity.value)
        assertEquals(ProximityState.INSIDE, store.state)
        assertEquals(listOf(ProximityEvent.Arrived("D")), events)
    }

    @Test
    fun `a second delete-all does not un-suppress the first one's ids`() = runTest {
        // Delete-all is repeatable, and the second pass runs with no worksites left to enumerate and
        // an already-erased lastTargetId — so it hands clear() an empty set. If clear() assigned
        // instead of accumulating, that would wipe the suppression list while A's straggler was
        // still in flight, letting it write the deleted id back to disk: #21 all over again.
        val store = FakeStore()
        val repo = ProximityRepository(store)

        repo.clear(setOf("A"))
        // Second confirm of "Delete all data": nothing left to name, nothing left being tracked.
        repo.clear(emptySet())
        // A's transition was dispatched before its geofence came down, and only lands now.
        repo.onGeofenceTransition("A", ProximityState.INSIDE)

        assertNull(store.targetId)
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
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
        repo.clear(emptySet())
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
        repo.clear(emptySet())
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
