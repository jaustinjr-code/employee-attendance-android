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

    /**
     * A fix [northMeters] due north of the target centre, reporting [accuracyMeters] of error and
     * taken at [atMillis]. Fix times matter: corroborating fixes must be properly spaced, so tests
     * that build a streak advance the clock between fixes.
     */
    private fun sample(northMeters: Double, accuracyMeters: Float, atMillis: Long = 0L) =
        LocationSample(
            latitudeDegrees = target.latitudeDegrees + northMeters / METERS_PER_DEGREE_LATITUDE,
            longitudeDegrees = target.longitudeDegrees,
            accuracyMeters = accuracyMeters,
            timestampEpochMillis = atMillis,
        )

    /** A well-spaced sequence of fix times, comfortably above the minimum corroboration spacing. */
    private fun fixTime(index: Int) = index * 30_000L

    /** Feeds [count] properly spaced marginal-but-inside fixes, starting at fix index [from]. */
    private fun ProximityRepository.feedMarginalInsideFixes(count: Int, from: Int = 0) {
        repeat(count) { i ->
            onLocation(sample(140.0, accuracyMeters = 60f, atMillis = fixTime(from + i)), target)
        }
    }

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

        repeat(10) { i ->
            repo.onLocation(sample(140.0, accuracyMeters = 500f, atMillis = fixTime(i)), target)
        }
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

        repo.feedMarginalInsideFixes(ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES)
        runCurrent()
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertTrue(events.isEmpty())

        repo.feedMarginalInsideFixes(1, from = ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES)
        runCurrent()
        assertEquals(ProximityState.INSIDE, repo.proximity.value)
        assertEquals(listOf(ProximityEvent.Arrived("office")), events)
    }

    @Test
    fun resetDiscardsTheCorroborationStreakEvenFromUNKNOWN() = runTest {
        // reset() short-circuits on an already-UNKNOWN state — and UNKNOWN is exactly the state a
        // streak accumulates in, since marginal fixes hold rather than commit. If the streak
        // survived, the first inside-reading fix after tracking resumed (another day, another
        // place) would clock the user in on its own: the very defect this gate exists to prevent.
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        val n = ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES
        repo.feedMarginalInsideFixes(n)
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)

        repo.reset()

        repo.feedMarginalInsideFixes(1, from = n + 1)
        runCurrent()
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aStreakGatheredForOneWorksiteDoesNotCorroborateAnother() = runTest {
        // Evidence is only evidence about the target it was gathered for.
        val other = target.copy(id = "warehouse", latitudeDegrees = 40.0)
        val repo = ProximityRepository(FakeStore())
        val events = mutableListOf<ProximityEvent>()
        backgroundScope.launch { repo.events.collect(events::add) }
        runCurrent()

        val n = ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES
        repo.feedMarginalInsideFixes(n)

        // One marginal fix for a different worksite must not inherit the streak and commit.
        repo.onLocation(
            LocationSample(
                latitudeDegrees = other.latitudeDegrees + 140.0 / METERS_PER_DEGREE_LATITUDE,
                longitudeDegrees = other.longitudeDegrees,
                accuracyMeters = 60f,
                timestampEpochMillis = fixTime(n),
            ),
            other,
        )
        runCurrent()

        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun batchedFixesFromTheSameMomentDoNotCorroborateEachOther() = runTest {
        // The OUTSIDE power profile batches up to 120 s of fixes into one delivery. Counting a batch
        // as several independent observations would be self-deception, so fixes closer together than
        // MIN_CORROBORATION_SPACING_MILLIS are ignored rather than counted.
        val repo = ProximityRepository(FakeStore())

        repeat(20) { i ->
            repo.onLocation(sample(140.0, accuracyMeters = 60f, atMillis = i * 100L), target)
        }

        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
    }

    @Test
    fun aStaleInsideFixDoesNotCorroborateAMuchLaterOne() = runTest {
        val repo = ProximityRepository(FakeStore())

        repo.onLocation(sample(140.0, accuracyMeters = 60f, atMillis = 0L), target)
        // Hours later — far beyond MAX_CORROBORATION_GAP_MILLIS — the run restarts from scratch.
        val muchLater = ProximityRepository.MAX_CORROBORATION_GAP_MILLIS * 10
        repo.onLocation(sample(140.0, accuracyMeters = 60f, atMillis = muchLater), target)
        repo.onLocation(
            sample(140.0, accuracyMeters = 60f, atMillis = muchLater + 30_000L),
            target,
        )

        // Only two corroborations have accrued since the restart, so nothing commits yet.
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)
    }

    @Test
    fun aFixThatReadsOutsideBreaksTheCorroborationStreak() = runTest {
        val repo = ProximityRepository(FakeStore())

        val n = ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES
        repo.feedMarginalInsideFixes(n)
        // A usable fix well outside the band resets the run of evidence...
        repo.onLocation(sample(400.0, accuracyMeters = 20f, atMillis = fixTime(n)), target)
        assertEquals(ProximityState.OUTSIDE, repo.proximity.value)

        // ...so the next marginal fix starts over and must not commit an entry.
        repo.feedMarginalInsideFixes(1, from = n + 1)
        assertEquals(ProximityState.OUTSIDE, repo.proximity.value)
    }

    @Test
    fun anUnusableFixDoesNotBreakTheCorroborationStreak() = runTest {
        // An uninformative fix is not evidence of being outside; it should neither advance nor
        // reset the run.
        val repo = ProximityRepository(FakeStore())

        val n = ProximityCalculator.MIN_CORROBORATING_INSIDE_FIXES
        repo.feedMarginalInsideFixes(n)
        repo.onLocation(sample(140.0, accuracyMeters = 5_000f, atMillis = fixTime(n)), target)
        assertEquals(ProximityState.UNKNOWN, repo.proximity.value)

        repo.feedMarginalInsideFixes(1, from = n + 1)
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
