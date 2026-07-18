package com.jaustinjr.employeeattendance.units

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DistanceFormatterTest {

    @Test
    fun `metric renders meters below a kilometer`() {
        assertEquals("150 m", DistanceFormatter.format(150f, MeasurementSystem.METRIC, Locale.US))
    }

    @Test
    fun `metric rolls up to kilometers`() {
        assertEquals("1.5 km", DistanceFormatter.format(1_500f, MeasurementSystem.METRIC, Locale.US))
    }

    @Test
    fun `imperial renders feet below a mile`() {
        assertEquals("164 ft", DistanceFormatter.format(50f, MeasurementSystem.IMPERIAL, Locale.US))
    }

    @Test
    fun `imperial rolls up to miles`() {
        assertEquals("1.2 mi", DistanceFormatter.format(2_000f, MeasurementSystem.IMPERIAL, Locale.US))
    }

    @Test
    fun `system default is imperial for US and metric elsewhere`() {
        assertEquals(MeasurementSystem.IMPERIAL, MeasurementSystem.systemDefault(Locale.US))
        assertEquals(MeasurementSystem.METRIC, MeasurementSystem.systemDefault(Locale.FRANCE))
    }
}
