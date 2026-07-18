package com.jaustinjr.employeeattendance.units

import java.util.Locale

/**
 * How distances are displayed. Geofencing math is always in meters internally; this only governs
 * presentation (radius options, labels, and — later — map overlays).
 */
enum class MeasurementSystem {
    METRIC,
    IMPERIAL,
    ;

    companion object {
        /** Countries that use the imperial system for everyday distances. */
        private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

        /**
         * The measurement system implied by the device [locale]'s country.
         *
         * TODO(#5): This is the "System default" behavior. Once the Imperial/International/System
         *   setting from issue #5 exists, resolve the chosen [MeasurementSystem] from that
         *   preference and fall back to this only when "System default" is selected.
         */
        fun systemDefault(locale: Locale = Locale.getDefault()): MeasurementSystem =
            if (locale.country.uppercase(Locale.ROOT) in IMPERIAL_COUNTRIES) IMPERIAL else METRIC
    }
}
