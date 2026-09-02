package com.jaustinjr.employeeattendance.location.proximity

import android.util.Log
import com.jaustinjr.employeeattendance.location.tracking.LocationSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped source of truth for the user's proximity to their work location. It is fed by two
 * producers depending on permission level:
 *
 * - Background ([android.location.Location] geofences via [GeofenceBroadcastReceiver]) call
 *   [onGeofenceTransition] when the OS reports an enter/exit.
 * - Foreground-only tracking calls [onLocation] with each fix, and this class computes the
 *   transition itself using [ProximityCalculator].
 *
 * Both paths converge here so consumers see a single [proximity] state and a single [events] stream
 * regardless of which producer is active. Transitions emit [ProximityEvent]s — the seam an
 * attendance/clock-in system consumes.
 *
 * ### Single active target
 * This class holds ONE global [ProximityState], not per-target state, even though the API threads a
 * `targetId` through every event. That is safe only because exactly one target is ever registered at
 * a time (enforced by [com.jaustinjr.employeeattendance.location.LocationFeatureCoordinator], which
 * registers a single active work location). If multiple concurrent targets were ever registered, an
 * EXIT for target B while still INSIDE target A would incorrectly flip the global state to OUTSIDE
 * and fire a spurious Departed(B).
 *
 * TODO: If multi-target tracking is introduced, replace the single global state with per-target
 *   membership (e.g. the set of target ids currently INSIDE) and derive aggregate proximity/events
 *   from it.
 *
 * @param store persistence for the proximity state so it survives process death (see below).
 * @param exitBufferMeters hysteresis band for the foreground evaluator (see [ProximityCalculator]).
 */
class ProximityRepository(
    private val store: ProximityStateStore,
    private val exitBufferMeters: Float = DEFAULT_EXIT_BUFFER_METERS,
) : ProximityUpdater {

    // Seed from persisted state so that when Android cold-starts the process purely to deliver a
    // geofence EXIT, the previous state is restored (e.g. INSIDE) and Departed is emitted rather
    // than swallowed.
    private val _proximity = MutableStateFlow(store.load())
    val proximity: StateFlow<ProximityState> = _proximity.asStateFlow()

    private var lastTargetId: String? = store.loadTargetId()

    /**
     * Ids of targets erased by [clear] whose transitions must be ignored.
     *
     * "Delete all data" removes the worksites synchronously, but the OS geofences registered for them
     * are torn down later and asynchronously (by `LocationFeatureCoordinator` reacting to the active
     * work location going null), and a fix already in flight can still be delivered. Without this
     * gate, such a straggler would call [setState] and write the just-deleted worksite id straight
     * back into the store — undoing the erasure this class just performed.
     *
     * Deliberately in-memory only and never persisted: persisting it would re-create exactly the
     * on-disk worksite id the erasure exists to remove.
     *
     * Entries live for the rest of the process, and [clear] only ever adds to them. Both properties
     * are load-bearing:
     *
     * - They are NOT dropped when some other target reports in. The straggler this gate exists to
     *   catch can arrive *after* a newly registered worksite has already reported, and un-suppressing
     *   on that report would let the straggler both re-persist the deleted id and clobber the new
     *   worksite's freshly committed tracking.
     * - [clear] accumulates rather than assigns. A second delete-all lands with no worksites left to
     *   enumerate and an already-erased [lastTargetId], so assigning would compute an *empty* set and
     *   un-suppress the first delete-all's ids — while its straggler may still be in flight.
     *
     * Growth is bounded in practice: ids are only ever added by an explicit delete-all, and each adds
     * at most the worksites that existed at that moment.
     *
     * Safe because work location ids are random UUIDs (see `WorksiteRegistrationViewModel`), so a
     * re-registered worksite never reuses a suppressed id and can never be wrongly ignored. That
     * argument holds only while every id reaching [clear] belongs to a worksite being deleted — see
     * the precondition on [ProximityUpdater.clear].
     */
    private var suppressedTargetIds: Set<String> = emptySet()

    /**
     * Consecutive foreground fixes that read inside the current target's radius, feeding
     * [ProximityCalculator]'s corroboration path.
     *
     * Deliberately not persisted: after a process death there is no run of fixes to corroborate, and
     * a stale streak would let the first fix of a new session commit an entry it hasn't earned. For
     * the same reason it is tagged with [insideStreakTargetId] and timestamped with
     * [lastCountedInsideFixMillis] — evidence is only evidence about the target it was gathered for,
     * and only while it is fresh and properly spaced. See [countInsideFix].
     */
    private var consecutiveInsideFixes = 0

    /** The target [consecutiveInsideFixes] was gathered for; a different target invalidates it. */
    private var insideStreakTargetId: String? = null

    /** Fix time of the most recent fix counted into [consecutiveInsideFixes]. */
    private var lastCountedInsideFixMillis = 0L

    init {
        Log.d(TAG, "seeded from store: state=${_proximity.value} target=$lastTargetId")
        // Heal residue written by builds before the #21 fix (and by any reset() that short-circuited
        // on an already-UNKNOWN state): a target id with no live state to label is orphaned data.
        if (_proximity.value == ProximityState.UNKNOWN && lastTargetId != null) {
            Log.d(TAG, "dropping orphaned target id persisted alongside an UNKNOWN state")
            lastTargetId = null
            store.save(ProximityState.UNKNOWN, null)
        }
    }

    private val _events = MutableSharedFlow<ProximityEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
    )
    val events: SharedFlow<ProximityEvent> = _events.asSharedFlow()

    /** Feed from OS geofence transitions (background path). */
    @Synchronized
    fun onGeofenceTransition(targetId: String, state: ProximityState) {
        if (isSuppressed(targetId)) return
        clearStaleStateIfTargetChanged(targetId)
        setState(state, targetId)
    }

    /** Feed from the foreground location stream; computes the transition with hysteresis. */
    @Synchronized
    override fun onLocation(sample: LocationSample, target: GeofenceTarget) {
        // Read current state, compute, and commit all under the monitor so a concurrent
        // geofence-driven commit can't slip in between the read and the write and get clobbered by
        // a decision made from stale state.
        if (isSuppressed(target.id)) return
        clearStaleStateIfTargetChanged(target.id)
        // Evidence gathered for another worksite says nothing about this one. This is checked here
        // rather than relying on clearStaleStateIfTargetChanged, which bails out early when nothing
        // has committed yet — precisely the situation in which a streak is being built.
        if (target.id != insideStreakTargetId) {
            clearInsideStreak()
            insideStreakTargetId = target.id
        }
        val distance = ProximityCalculator.distanceMeters(sample, target)
        val next = ProximityCalculator.evaluate(
            current = _proximity.value,
            distanceMeters = distance,
            accuracyMeters = sample.accuracyMeters,
            radiusMeters = target.radiusMeters,
            exitBufferMeters = exitBufferMeters,
            corroboratingInsideFixes = consecutiveInsideFixes,
        )
        Log.v(
            TAG,
            "onLocation: distance=${distance}m accuracy=${sample.accuracyMeters}m " +
                "radius=${target.radiusMeters}m insideStreak=$consecutiveInsideFixes -> $next",
        )
        // Update the streak *after* evaluating, so a fix corroborates only its predecessors. An
        // unusable fix leaves the streak untouched rather than resetting it: it is not evidence of
        // being outside, it is no evidence at all.
        if (ProximityCalculator.isUsable(sample.accuracyMeters, target.radiusMeters, exitBufferMeters)) {
            if (ProximityCalculator.readsInside(distance, target.radiusMeters)) {
                countInsideFix(sample.timestampEpochMillis)
            } else {
                clearInsideStreak()
            }
        }
        setState(next, target.id)
    }

    /**
     * Clears proximity, e.g. when tracking stops or no target is registered. Goes through the same
     * monitor as [setState] so it can't race an in-flight commit, and emits Departed if the state
     * being cleared was INSIDE (leaving a work location by de-registering it is still a departure).
     */
    @Synchronized
    override fun reset() {
        // Unconditionally, ahead of the short-circuit below: tracking stopping ends any run of
        // evidence, and the state is *typically* already UNKNOWN while a streak is accumulating
        // (marginal fixes hold the state rather than committing). Leaving the streak alive here
        // would let the first inside-reading fix after tracking resumes — a different day, a
        // different place — commit an entry on its own, which is the very defect this gate exists
        // to prevent.
        clearInsideStreak()
        insideStreakTargetId = null

        val previous = _proximity.value
        if (previous == ProximityState.UNKNOWN) return
        // Read the departed-from id before erase() nulls it.
        val departedFrom = lastTargetId
        erase()
        Log.d(TAG, "reset: $previous -> UNKNOWN")
        if (previous == ProximityState.INSIDE && departedFrom != null) {
            Log.d(TAG, "emit Departed($departedFrom) on reset")
            _events.tryEmit(ProximityEvent.Departed(departedFrom))
        }
    }

    /**
     * Erases every trace of proximity tracking: state and target id, in memory and in the store.
     *
     * Unlike [reset] this is unconditional (it does not short-circuit on an already-UNKNOWN state,
     * because a stale target id can outlive the state) and emits no events. See [ProximityUpdater.clear].
     */
    @Synchronized
    override fun clear(deletedTargetIds: Set<String>) {
        Log.d(TAG, "clear: erasing proximity state and target id")
        // Also suppress whatever this repository itself was tracking: the caller's list comes from
        // the work location registry, but a geofence can outlive its registry entry.
        //
        // Accumulate rather than assign. A second delete-all finds no worksites left to enumerate
        // and an already-erased lastTargetId, so assigning would compute an empty set and un-
        // suppress the first delete-all's ids while its straggler may still be in flight.
        suppressedTargetIds = suppressedTargetIds + deletedTargetIds + setOfNotNull(lastTargetId)
        erase()
    }

    /**
     * Whether [targetId] names a target erased by [clear] and must therefore be ignored.
     *
     * A report from another target does NOT retire the list. Only one geofence is registered at a
     * time, so the ordering that matters is: delete worksite A, register worksite D, D reports in,
     * and only then A's already-dispatched transition lands. Retiring the list on D's report would
     * un-suppress A just in time for that straggler to wipe D's tracking and write A back to disk.
     */
    private fun isSuppressed(targetId: String): Boolean {
        if (targetId !in suppressedTargetIds) return false
        Log.d(TAG, "ignoring transition for a target erased by delete-all-data")
        return true
    }

    /**
     * Drops the state and the target id together, in memory and in the store.
     *
     * The target id is only meaningful as the label on a live INSIDE/OUTSIDE state; once the state is
     * UNKNOWN there is nothing left for it to label, so persisting it would just leave a (possibly
     * deleted) worksite id sitting in the encrypted store indefinitely. Callers own the logging and
     * any event emission. Always invoked from a `@Synchronized` member, so it holds the monitor.
     */
    private fun erase() {
        _proximity.value = ProximityState.UNKNOWN
        lastTargetId = null
        clearInsideStreak()
        store.save(ProximityState.UNKNOWN, null)
    }

    /**
     * Counts a fix that read inside the radius toward the corroboration streak, if it is independent
     * enough of the previously counted one to be worth anything.
     *
     * Two guards, both keyed on the fix's own [LocationSample.timestampEpochMillis] rather than
     * arrival time — batched delivery (`maxUpdateDelayMillis` is 120 s while OUTSIDE) hands several
     * fixes over at once, and counting them as separate corroborations would be self-deception:
     *
     * - Fixes closer together than [MIN_CORROBORATION_SPACING_MILLIS] are ignored — not reset, just
     *   not counted. They are re-reports of the same moment, not a second observation of it.
     * - A gap longer than [MAX_CORROBORATION_GAP_MILLIS] (or a backwards one, from a wall-clock
     *   change) restarts the streak: stale evidence must not corroborate a fix taken hours later,
     *   possibly kilometres away.
     */
    private fun countInsideFix(fixTimeMillis: Long) {
        val elapsed = fixTimeMillis - lastCountedInsideFixMillis
        when {
            consecutiveInsideFixes == 0 || elapsed < 0L || elapsed > MAX_CORROBORATION_GAP_MILLIS -> {
                consecutiveInsideFixes = 1
                lastCountedInsideFixMillis = fixTimeMillis
            }
            elapsed >= MIN_CORROBORATION_SPACING_MILLIS -> {
                consecutiveInsideFixes++
                lastCountedInsideFixMillis = fixTimeMillis
            }
            else -> Log.v(TAG, "inside fix too close to the last to corroborate it; not counted")
        }
    }

    /** Discards the corroboration streak. Callers hold the monitor. */
    private fun clearInsideStreak() {
        consecutiveInsideFixes = 0
        lastCountedInsideFixMillis = 0L
    }

    /**
     * When the watched target changes — the user switched their active worksite — any prior INSIDE/
     * OUTSIDE state belonged to the OLD target and must not carry over. If it did, evaluating the new
     * target from a stale INSIDE would emit a transition tagged with the *new* target id (e.g. a
     * Departed for the worksite you just switched TO, rather than the one you left). Clearing to
     * UNKNOWN lets the new target evaluate from a clean slate.
     *
     * No event is emitted here: clocking out of the previous worksite on an active-worksite switch is
     * handled explicitly by that switch flow (with a notification naming the correct worksite), so
     * emitting a Departed here too would double-count.
     */
    private fun clearStaleStateIfTargetChanged(newTargetId: String) {
        val oldTarget = lastTargetId ?: return
        if (newTargetId == oldTarget) return
        if (_proximity.value != ProximityState.UNKNOWN) {
            Log.d(TAG, "target changed $oldTarget -> $newTargetId; clearing stale proximity")
            // erase() also drops the old target id: it labelled the state we are discarding, and it
            // may name a worksite the user just deleted. The caller's setState() re-stamps the new
            // target id if the evaluation actually commits a transition.
            erase()
        }
    }

    // setState is a read-modify-write over _proximity plus event emission, and it is reached from
    // two threads: OS geofence callbacks (main) and the foreground location pipeline (background).
    // Guard it so transitions can't interleave and double-emit or drop events.
    @Synchronized
    private fun setState(next: ProximityState, targetId: String) {
        val previous = _proximity.value
        if (next == previous) return
        _proximity.value = next
        lastTargetId = targetId
        // A committed transition (including one the OS geofence forced) starts a fresh run of
        // evidence; the old streak described a state we have now left.
        consecutiveInsideFixes = 0
        store.save(next, targetId)
        Log.d(TAG, "state: $previous -> $next (target=$targetId)")
        when (next) {
            ProximityState.INSIDE -> {
                Log.d(TAG, "emit Arrived($targetId)")
                _events.tryEmit(ProximityEvent.Arrived(targetId))
            }
            // Only a genuine inside -> outside move is a "departure"; leaving UNKNOWN is not.
            ProximityState.OUTSIDE ->
                if (previous == ProximityState.INSIDE) {
                    Log.d(TAG, "emit Departed($targetId)")
                    _events.tryEmit(ProximityEvent.Departed(targetId))
                }
            ProximityState.UNKNOWN -> Unit
        }
    }

    companion object {
        /**
         * Minimum spacing between two fixes for the second to corroborate the first. Sits below the
         * 30 s `minUpdateIntervalMillis` that `LocationPowerPolicy` uses while OUTSIDE, so genuinely
         * separate fixes always qualify, while a batch delivered in one callback does not.
         */
        const val MIN_CORROBORATION_SPACING_MILLIS = 20_000L

        /**
         * Maximum spacing before the streak is considered stale and restarts. Generous against the
         * 60 s foreground / 120 s background OUTSIDE cadences, but far short of the hours over which
         * a user could travel somewhere entirely different.
         */
        const val MAX_CORROBORATION_GAP_MILLIS = 10 * 60_000L

        private const val TAG = "ProxRepo"
        private const val DEFAULT_EXIT_BUFFER_METERS = 50f
        private const val EVENT_BUFFER = 8
    }
}
