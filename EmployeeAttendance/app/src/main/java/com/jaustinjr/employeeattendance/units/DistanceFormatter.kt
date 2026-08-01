package com.jaustinjr.employeeattendance.units

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formats a distance in meters into a display string for a [MeasurementSystem]. Small distances read
 * in meters/feet; large ones roll up to kilometers/miles. The stored value stays in meters — only
 * the rendering changes.
 */
object DistanceFormatter {

    private const val FEET_PER_METER = 3.28084f
    private const val FEET_PER_MILE = 5280f
    private const val METERS_PER_KM = 1000f

    fun format(
        meters: Float,
        system: MeasurementSystem = MeasurementSystem.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = when (system) {
        MeasurementSystem.METRIC ->
            if (meters >= METERS_PER_KM) {
                String.format(locale, "%.1f km", meters / METERS_PER_KM)
            } else {
                "${meters.roundToInt()} m"
            }

        MeasurementSystem.IMPERIAL -> {
            val feet = meters * FEET_PER_METER
            if (feet >= FEET_PER_MILE) {
                String.format(locale, "%.1f mi", feet / FEET_PER_MILE)
            } else {
                "${feet.roundToInt()} ft"
            }
        }
    }
}
