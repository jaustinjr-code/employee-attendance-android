package com.jaustinjr.employeeattendance.location.proximity

import android.location.Location
import com.jaustinjr.employeeattendance.location.tracking.LocationSample

/**
 * Pure geometry for proximity decisions. Separated from Android/service concerns so the rules are
 * trivially unit-testable.
 */
object ProximityCalculator {

    /** Great-circle distance in meters between a fix and a target center (WGS84 ellipsoid). */
    fun distanceMeters(sample: LocationSample, target: GeofenceTarget): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            sample.latitudeDegrees,
            sample.longitudeDegrees,
            target.latitudeDegrees,
            target.longitudeDegrees,
            results,
        )
        return results[0]
    }

    /**
     * Whether a fix is precise enough to say anything at all about a target of [radiusMeters].
     *
     * [accuracyMeters] is a 68% (1σ) horizontal error radius. If that circle is wider than the whole
     * decision band (`radius + exitBuffer`), the fix cannot discriminate "inside" from "outside" for
     * this target — the reported point could be anywhere in the band regardless of where it landed.
     * Such a fix carries no information and must not move the state in *either* direction.
     *
     * This is the situation the field report hit: `LocationPriority.LOW_POWER` fixes (used by
     * [com.jaustinjr.employeeattendance.location.tracking.LocationPowerPolicy] while OUTSIDE, and by
     * the background tracking stream) are cell-tower derived and routinely carry hundreds to
     * thousands of meters of error.
     *
     * A non-finite or negative accuracy is treated as unusable: it means the provider gave us no
     * error estimate, and an unknown error is not evidence of a small one. An accuracy of exactly
     * zero is accepted — that is what mock/test providers report, and treating it as unusable would
     * make the feature untestable.
     */
    fun isUsable(accuracyMeters: Float, radiusMeters: Float, exitBufferMeters: Float): Boolean =
        accuracyMeters.isFinite() &&
            accuracyMeters >= 0f &&
            accuracyMeters <= radiusMeters + exitBufferMeters

    /**
     * Whether a fix reads as inside the radius at all, ignoring how confident it is. Used to track
     * the corroboration streak that lets a merely-marginal fix eventually commit an entry.
     */
    fun readsInside(distanceMeters: Float, radiusMeters: Float): Boolean =
        distanceMeters <= radiusMeters

    /**
     * Decides the next [ProximityState] from a fix, applying both distance hysteresis and an
     * accuracy gate.
     *
     * ### Hysteresis (unchanged)
     * The user must be within [radiusMeters] to be considered INSIDE, but must move beyond
     * `radiusMeters + exitBufferMeters` before flipping back to OUTSIDE. Once INSIDE, distances in
     * the buffer band hold the current state.
     *
     * ### Accuracy gate
     * A false clock-in fabricates hours the user did not work, which is the most trust-breaking
     * failure an attendance app can have; a *late* clock-in merely annoys. The gate is therefore
     * deliberately asymmetric — it is strict about entering and permissive about leaving:
     *
     * 1. **Unusable fixes decide nothing.** If [isUsable] is false the current state is returned
     *    untouched. This is what stops a 500 m cell-tower fix that happens to land 140 m from a
     *    150 m worksite from firing an auto clock-in while the user is 300 m away.
     * 2. **Entering requires confidence, or corroboration.** Committing INSIDE from a non-INSIDE
     *    state needs either
     *    - `distanceMeters + accuracyMeters <= radiusMeters` — the reported point *and its whole 1σ
     *      error circle* fit inside the radius; or
     *    - [corroboratingInsideFixes] `>=` [MIN_CORROBORATING_INSIDE_FIXES] consecutive earlier
     *      fixes that also read inside.
     *
     *    The corroboration path is the escape hatch that keeps auto clock-in working on devices
     *    whose fixes are merely mediocre rather than useless (e.g. a 40 m fix against a 50 m radius,
     *    which can never satisfy the confidence test). Such users still clock in — a couple of fix
     *    intervals later — instead of losing the feature. It is not a free pass: a single noisy fix,
     *    which is the actual false-positive mechanism in this bug, cannot satisfy it.
     * 3. **Leaving is unchanged**, beyond requiring a usable fix. Refusing to clock out on
     *    low-confidence evidence would strand sessions open and over-report hours, which is the same
     *    harm the gate exists to prevent.
     *
     * ### Known residual exposure
     * A device that *only* ever produces fixes coarser than `radius + exitBuffer` will never auto
     * clock in at all. That is intentional: such a device genuinely cannot tell whether the user is
     * at a 50 m worksite, and inventing the answer is worse than not answering. Because the
     * usability bound scales with the radius, the user-facing remedy is to pick a wider radius
     * ([com.jaustinjr.employeeattendance.location.registration.RadiusOption.DISTANT] gives a 650 m
     * budget), or to grant precise location so higher-priority fixes are available.
     *
     * @param current the previous state, used to apply the hysteresis band.
     * @param distanceMeters measured distance from the target center.
     * @param accuracyMeters the fix's 68% horizontal error radius; smaller is better.
     * @param radiusMeters the target radius.
     * @param exitBufferMeters extra distance required to leave, on top of the radius.
     * @param corroboratingInsideFixes consecutive immediately-preceding usable fixes that read
     *   inside the radius, per [readsInside].
     */
    fun evaluate(
        current: ProximityState,
        distanceMeters: Float,
        accuracyMeters: Float,
        radiusMeters: Float,
        exitBufferMeters: Float,
        corroboratingInsideFixes: Int = 0,
    ): ProximityState = when {
        // 1. The fix cannot tell inside from outside at this radius: change nothing.
        !isUsable(accuracyMeters, radiusMeters, exitBufferMeters) -> current

        distanceMeters > radiusMeters + exitBufferMeters -> ProximityState.OUTSIDE

        // In the hysteresis band: keep INSIDE if we were inside, otherwise treat as outside.
        !readsInside(distanceMeters, radiusMeters) ->
            if (current == ProximityState.INSIDE) ProximityState.INSIDE else ProximityState.OUTSIDE

        // Reads inside, and we already were: staying put needs no extra evidence.
        current == ProximityState.INSIDE -> ProximityState.INSIDE

        // 2a. Confidently inside: the whole error circle fits within the radius.
        distanceMeters + accuracyMeters <= radiusMeters -> ProximityState.INSIDE

        // 2b. Only marginally inside, but enough consecutive fixes agree.
        corroboratingInsideFixes >= MIN_CORROBORATING_INSIDE_FIXES -> ProximityState.INSIDE

        // Marginal and uncorroborated: hold, and wait for more evidence rather than clocking in.
        else -> current
    }

    /**
     * Consecutive earlier inside-reading fixes required before a merely-marginal fix may commit an
     * entry. Two (so three agreeing fixes in total) is enough to reject the single-noisy-fix
     * mechanism behind this bug while keeping the delay to a couple of fix intervals.
     */
    const val MIN_CORROBORATING_INSIDE_FIXES = 2
}
