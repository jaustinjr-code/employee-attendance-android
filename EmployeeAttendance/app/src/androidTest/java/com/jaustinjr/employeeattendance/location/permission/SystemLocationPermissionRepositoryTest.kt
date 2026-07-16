package com.jaustinjr.employeeattendance.location.permission

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the repository reads real granted foreground permissions. Background ("Allow all the
 * time") cannot be granted by GrantPermissionRule, so on API 29+ the level is WHEN_IN_USE and on
 * older versions ALWAYS — both are "granted", which is what we assert.
 */
class SystemLocationPermissionRepositoryTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun grantedForegroundReportsGrantedAndPrecise() {
        val repo = SystemLocationPermissionRepository(context)

        val state = repo.refresh()

        assertTrue(state.isGranted)
        assertTrue(state.isPrecise)
        assertTrue(
            state.accessLevel == LocationAccessLevel.WHEN_IN_USE ||
                state.accessLevel == LocationAccessLevel.ALWAYS,
        )
    }

    @Test
    fun refreshUpdatesTheStateFlow() {
        val repo = SystemLocationPermissionRepository(context)
        repo.refresh()
        assertTrue(repo.permissionState.value.isGranted)
    }
}
