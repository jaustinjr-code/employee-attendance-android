package com.jaustinjr.employeeattendance.location.registration

/**
 * The "Remote" half of the Local + Remote repository pair behind [WorkLocationRepository]. It is the
 * seam for a future world where an employer/admin provisions worksites centrally and the app syncs
 * them down (and, potentially, pushes user-registered ones up).
 *
 * It is intentionally unused by the current UI — registration is local-only for now — but
 * [DefaultWorkLocationRepository] depends on this interface (not a concrete class) so a real backend
 * client can be dropped in without touching the repository or its consumers.
 */
interface WorkLocationRemoteDataSource {

    /** Fetches admin/server-provisioned worksites. Returns empty when there is no backend yet. */
    suspend fun fetchProvisionedWorkLocations(): List<WorkLocation>

    /** Pushes a locally-registered worksite to the backend. A no-op until a backend exists. */
    suspend fun pushWorkLocation(location: WorkLocation)

    /** Notifies the backend that a worksite was removed. A no-op until a backend exists. */
    suspend fun deleteWorkLocation(id: String)
}

/**
 * No-op [WorkLocationRemoteDataSource] used while the app is local-only. Every call is a benign
 * no-op, so wiring the repository to sync in the future is a matter of swapping this implementation.
 */
class StubWorkLocationRemoteDataSource : WorkLocationRemoteDataSource {
    override suspend fun fetchProvisionedWorkLocations(): List<WorkLocation> = emptyList()
    override suspend fun pushWorkLocation(location: WorkLocation) = Unit
    override suspend fun deleteWorkLocation(id: String) = Unit
}
