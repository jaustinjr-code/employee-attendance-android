package com.jaustinjr.employeeattendance.devtools.facade

import android.util.Log
import com.jaustinjr.employeeattendance.location.registration.RadiusOption
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.registration.WorkLocationRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * The only worksite-registration capability the developer tools are given.
 *
 * ### What this narrows
 * With [WorkLocationRepository] in hand the developer screen could register or remove *any* worksite,
 * including one the user set up by hand. This facade can create exactly one worksite — the sample it
 * owns, at [SAMPLE_WORKSITE_ID] — and can remove exactly that one ([removeSampleWorksite]). The
 * caller no longer chooses the id or the name, so a developer action can never overwrite or delete a
 * user's worksite by accident.
 *
 * The one wide operation, [removeAllWorksites], is kept because the developer screen has an explicit,
 * separately-labelled destructive button for it ("Remove all worksites"). It is named so it cannot be
 * mistaken for the targeted removal.
 */
interface DevWorksiteFacade {

    /** The worksite the app is currently tracking, or null. Read-only. */
    val activeWorksite: StateFlow<WorkLocation?>

    /** How many worksites are registered — used only in the exported log's state header. */
    val registeredCount: Int

    /**
     * Registers (or replaces) the developer sample worksite and makes it active, so the "set up"
     * branch of the UI is reachable in one tap.
     *
     * @param latitudeDegrees centre latitude, normally the latest observed fix.
     * @param longitudeDegrees centre longitude.
     */
    fun seedSampleWorksite(latitudeDegrees: Double, longitudeDegrees: Double)

    /** Removes the developer sample worksite if it is registered, and nothing else. */
    fun removeSampleWorksite()

    /** Removes every worksite, the user's own included. Backs the explicitly destructive button. */
    fun removeAllWorksites()

    companion object {
        /** Stable id so re-seeding replaces the sample rather than piling up duplicates. */
        const val SAMPLE_WORKSITE_ID = "dev-sample-worksite"
    }
}

/**
 * [DevWorksiteFacade] over the app's real [WorkLocationRepository].
 *
 * @param sampleWorksiteName display name for the seeded worksite, resolved from resources by the
 *   container so this class stays free of `Context`.
 */
class RepositoryDevWorksiteFacade(
    private val repository: WorkLocationRepository,
    private val sampleWorksiteName: String,
) : DevWorksiteFacade {

    override val activeWorksite: StateFlow<WorkLocation?> get() = repository.activeWorkLocation

    override val registeredCount: Int get() = repository.workLocations.value.size

    override fun seedSampleWorksite(latitudeDegrees: Double, longitudeDegrees: Double) {
        val worksite = WorkLocation(
            id = DevWorksiteFacade.SAMPLE_WORKSITE_ID,
            name = sampleWorksiteName,
            address = null,
            latitudeDegrees = latitudeDegrees,
            longitudeDegrees = longitudeDegrees,
            radiusMeters = RadiusOption.DEFAULT.meters,
        )
        Log.d(TAG, "seeding sample worksite at $latitudeDegrees,$longitudeDegrees")
        repository.registerWorkLocation(worksite)
        repository.setActiveWorkLocation(worksite.id)
    }

    override fun removeSampleWorksite() {
        Log.d(TAG, "removing the sample worksite only")
        repository.removeWorkLocation(DevWorksiteFacade.SAMPLE_WORKSITE_ID)
    }

    override fun removeAllWorksites() {
        repository.clearAll()
    }

    private companion object {
        const val TAG = "DevWorksite"
    }
}
