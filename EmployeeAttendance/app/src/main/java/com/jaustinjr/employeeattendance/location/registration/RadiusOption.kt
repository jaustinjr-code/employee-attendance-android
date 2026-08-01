package com.jaustinjr.employeeattendance.location.registration

/**
 * The preset geofence radii a user can choose when registering a worksite. Values are fixed in
 * meters (geofencing is metric internally); the UI renders them in the user's measurement system.
 *
 * - [NEAR]: tight radius for a single building/entrance.
 * - [DEFAULT]: a balanced radius suitable for most sites.
 * - [DISTANT]: a wide radius for large campuses or lots.
 */
enum class RadiusOption(val meters: Float) {
    NEAR(50f),
    DEFAULT(150f),
    DISTANT(600f),
}
