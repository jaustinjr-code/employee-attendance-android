package com.jaustinjr.employeeattendance.devtools.facade

import com.jaustinjr.employeeattendance.devtools.FakeWorkLocationRepository
import com.jaustinjr.employeeattendance.location.registration.RadiusOption
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevWorksiteFacadeTest {

    private val userWorksite = WorkLocation(
        id = "office",
        name = "Downtown Office",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    private val repository = FakeWorkLocationRepository()
    private val facade = RepositoryDevWorksiteFacade(repository, "Dev Sample Worksite")

    @Test
    fun `seeding registers the sample under its own id and activates it`() {
        facade.seedSampleWorksite(51.5, -0.12)

        val active = repository.activeWorkLocation.value
        assertNotNull(active)
        assertEquals(DevWorksiteFacade.SAMPLE_WORKSITE_ID, active!!.id)
        assertEquals("Dev Sample Worksite", active.name)
        assertEquals(51.5, active.latitudeDegrees, 0.0)
        assertEquals(RadiusOption.DEFAULT.meters, active.radiusMeters)
    }

    @Test
    fun `seeding twice replaces the sample rather than accumulating duplicates`() {
        facade.seedSampleWorksite(51.5, -0.12)
        facade.seedSampleWorksite(48.85, 2.35)

        assertEquals(1, repository.workLocations.value.size)
        assertEquals(48.85, repository.activeWorkLocation.value!!.latitudeDegrees, 0.0)
    }

    @Test
    fun `removing the sample cannot reach a worksite the user registered`() {
        repository.registerWorkLocation(userWorksite)
        facade.seedSampleWorksite(51.5, -0.12)

        facade.removeSampleWorksite()

        // This is the narrowing: the caller never names an id, so only the dev sample can go.
        assertEquals(listOf(userWorksite.id), repository.workLocations.value.map { it.id })
    }

    @Test
    fun `removing the sample when none was seeded is a no-op`() {
        repository.registerWorkLocation(userWorksite)

        facade.removeSampleWorksite()

        assertEquals(1, repository.workLocations.value.size)
    }

    @Test
    fun `the wide removal is still available for the explicitly destructive button`() {
        repository.registerWorkLocation(userWorksite)
        facade.seedSampleWorksite(51.5, -0.12)

        facade.removeAllWorksites()

        assertTrue(repository.workLocations.value.isEmpty())
    }

    @Test
    fun `reads are limited to the active worksite and a count`() {
        repository.registerWorkLocation(userWorksite)
        facade.seedSampleWorksite(51.5, -0.12)

        assertEquals(2, facade.registeredCount)
        assertEquals(DevWorksiteFacade.SAMPLE_WORKSITE_ID, facade.activeWorksite.value?.id)
    }
}
