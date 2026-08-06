package com.jaustinjr.employeeattendance.location.proximity

import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-level regression for #12: the false auto clock-in caused by low-accuracy fixes.
 *
 * Lives in androidTest rather than the JVM suite because it drives [ProximityRepository.onLocation],
 * which computes real great-circle distances via `android.location.Location`. The gate's decision
 * table itself is unit-tested in `ProximityCalculatorTest`; this covers the wiring — that the fix's
 * `accuracyMeters` actually reaches the calculator, and that the corroboration streak is tracked,
 * reset, and not persisted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProximityAccuracyGateTest {

    private val target = GeofenceTarget(
        id = "office",
        latitudeDegrees = 37.0,
        longitudeDegrees = -122.0,
        radiusMeters = 150f,
    )

    /** In-memory store so the repository can be built without touching device prefs. */
    private class FakeStore : ProximityStateStore {
        private var state: ProximityState = ProximityState.UNKNOWN
        private var targetId: String? = null
        override fun load(): ProximityState = state
        override fun loadTargetId(): String? = targetId
        override fun save(state: ProximityState, targetId: String?) {
            this.state = state
            this.targetId = targetId
        }
    }

    private companion object {
        /**
         * Approximate meters per degree of latitude. Off by ~0.3% against the WGS84 ellipsoid that
         * `Location.distanceBetween` uses, which is immaterial here — every assertion below sits at
         * least several meters clear of a decision boundary.
         */
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    }

    /** A fix [northMeters] due north of the target centre, reporting [accuracyMeters] of error. */
    private fun sample(northMeters: Double, accuracyMeters: Float) = LocationSample(
        latitudeDegrees = target.latitudeDegrees + northMeters / METERS_PER_DEGREE_LATITUDE,
        longitudeDegrees = target.longitudeDegrees,
        accuracyMeters = accuracyMeters,
        timestampEpochMillis = 0L,
    )

    @Test
    fun aSingleCoarseFixInsideTheRadiusDoesNotClockTheUserIn() = runTest {
        // The reported bug: a LOW_POWER / cell-tower fix whose reported centre lands 140 m from a
        // 150 m worksite, while the true position is far outside. Accuracy 500 m is wider than the
        // whole 200 m decision band, so it is not evidence of anything.
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 500f), target)
        runCurrent()

        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun repeatedCoarseFixesStillDoNotClockTheUserIn() = runTest {
        // Corroboration must not launder an unusable fix into a clock-in.
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repeat(10) { repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 500f), target) }
        runCurrent()

        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aConfidentFixClocksTheUserInImmediately() = runTest {
        // 50 m out with 20 m accuracy: 50 + 20 <= 150, the whole error circle fits inside.
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onLocation(sample(northMeters = 50.0, accuracyMeters = 20f), target)
        runCurrent()

        assertEquals(ProximityState.INSIDE, repo.proximity.value)
        assertEquals(listOf(ProximityEvent.Arrived("office")), events)
    }

    @Test
    fun aMarginalFixClocksTheUserInOnlyAfterEnoughCorroboration() = runTest {
        // 140 m out with 60 m accuracy: usable (60 <= 200) and reads inside, but 140 + 60 > 150, so
        // it is only marginal. This is the mediocre-device escape hatch — it must still work, just
        // later. Assert nothing fires until the corroboration threshold is met.
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repeat(ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES) {
            repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 60f), target)
        }
        runCurrent()
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertTrue(events.isEmpty())

        repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 60f), target)
        runCurrent()
        assertEquals(ProximityState.INSIDE, repo.proximity.value)
        assertEquals(listOf(ProximityEvent.Arrived("office")), events)
    }

    @Test
    fun aFixThatReadsOutsideBreaksTheCorroborationStreak() = runTest {
        val repo = ProximityRepository(FakeStore())

        repeat(ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES) {
            repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 60f), target)
        }
        // A usable fix well outside the band resets the run of evidence...
        repo.onLocation(sample(northMeters = 400.0, accuracyMeters = 20f), target)
        assertEquals(ProximityState.OUTSIDE, repo.proximity.value)

        // ...so the next marginal fix starts over and must not commit an entry.
        repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 60f), target)
        assertEquals(ProximityState.OUTSIDE, repo.proximity.value)
    }

    @Test
    fun anUnusableFixDoesNotBreakTheCorroborationStreak() = runTest {
        // An uninformative fix is not evidence of being outside; it should neither advance nor
        // reset the run.
        val repo = ProximityRepository(FakeStore())

        repeat(ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES) {
            repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 60f), target)
        }
        repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 5_000f), target)
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)

        repo.onLocation(sample(northMeters = 140.0, accuracyMeters = 60f), target)
        assertEquals(ProximityState.INSIDE, repo.proximity.value)
    }

    @Test
    fun aCoarseFixCannotClockAnInsideUserOut() = runTest {
        val repo = ProximityRepository(FakeStore())
        repo.onGeofenceTransition("office", ProximityState.INSIDE)
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        // Reported 5 km away, but with 5 km of error — no basis to end the shift.
        repo.onLocation(sample(northMeters = 5_000.0, accuracyMeters = 5_000f), target)
        runCurrent()

        assertEquals(ProximityState.INSIDE, repo.proximity.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aCoarseFixThatIsOutsideEvenAtItsErrorBoundStillClocksTheUserOut() = runTest {
        // The anti-stranding rule: 5 km away with 500 m of error is unusable for *entering* a 150 m
        // radius, but leaves no doubt the user has left.
        val repo = ProximityRepository(FakeStore())
        repo.onGeofenceTransition("office", ProximityState.INSIDE)
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onLocation(sample(northMeters = 5_000.0, accuracyMeters = 500f), target)
        runCurrent()

        assertEquals(ProximityState.OUTSIDE, repo.proximity.value)
        assertEquals(listOf(ProximityEvent.Departed("office")), events)
    }

    @Test
    fun aUsableFixWellOutsideStillClocksTheUserOut() = runTest {
        val repo = ProximityRepository(FakeStore())
        repo.onGeofenceTransition("office", ProximityState.INSIDE)
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        repo.onLocation(sample(northMeters = 1_000.0, accuracyMeters = 100f), target)
        runCurrent()

        assertEquals(ProximityState.OUTSIDE, repo.proximity.value)
        assertEquals(listOf(ProximityEvent.Departed("office")), events)
    }
}
