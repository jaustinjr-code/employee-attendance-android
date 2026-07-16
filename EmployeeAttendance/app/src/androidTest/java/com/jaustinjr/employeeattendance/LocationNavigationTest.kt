package com.jaustinjr.employeeattendance

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import com.jaustinjr.employeeattendance.location.permission.LocationAccessLevel
import com.jaustinjr.employeeattendance.location.registration.WorkLocation
import com.jaustinjr.employeeattendance.location.ui.LocationUiState
import com.jaustinjr.employeeattendance.ui.attendance.AttendanceScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocationNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val office = WorkLocation(
        id = "downtown-office",
        name = "Downtown Office",
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    @Test
    fun tappingPill_navigatesToLocationDetail() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = TestNavHostController(LocalContext.current)
            navController.navigatorProvider.addNavigator(ComposeNavigator())
            NavHost(navController, startDestination = Attendance) {
                composable<Attendance> {
                    AttendanceScreen(
                        todayDate = "Sunday, May 24",
                        locationState = LocationUiState(
                            activeWorkLocation = office,
                            accessLevel = LocationAccessLevel.ALWAYS,
                        ),
                        onLocationPillClick = { navController.navigate(LocationDetail) },
                    )
                }
                composable<LocationDetail> { Text("Detail") }
            }
        }

        composeRule.onNodeWithText("Downtown Office").performClick()

        composeRule.runOnIdle {
            val route = navController.currentBackStackEntry?.destination?.route
            assertTrue("route was $route", route?.contains("LocationDetail") == true)
        }
    }
}
