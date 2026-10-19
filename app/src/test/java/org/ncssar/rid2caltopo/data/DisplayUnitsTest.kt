package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class DisplayUnitsTest {
    @Test fun physicalQuantitiesMatchAcrossSystems() {
        assertEquals("100 ft", DisplayUnits.IMPERIAL.length(30.48))
        assertEquals("30 m", DisplayUnits.METRIC.feet(100.0))
        assertEquals("1.61 km", DisplayUnits.METRIC.miles(1.0, 2))
        assertEquals("2.59 km²", DisplayUnits.METRIC.area(1.0))
        assertEquals("22.4 mph", DisplayUnits.IMPERIAL.speed(10.0))
        assertEquals("10.0 m/s", DisplayUnits.METRIC.speed(10.0))
        assertEquals("1.00 in/px", DisplayUnits.IMPERIAL.resolution(2.54))
        assertEquals("-30 m", DisplayUnits.METRIC.feet(-100.0))
        assertEquals("0 m", DisplayUnits.METRIC.length(-0.01))
    }
    @Test fun editedValuesReturnToCanonicalMeasurements() {
        DisplayUnits.entries.forEach { units ->
            assertEquals(30.48, units.lengthMeters(units.lengthValue(30.48)), 1e-10)
            assertEquals(2.0, units.resolutionCm(units.resolutionValue(2.0)), 1e-10)
            assertEquals(1000.0, units.grams(units.gramsValue(1000.0)), 1e-10)
        }
    }
    @Test fun distancesChooseUnitsAtTheCorrectBoundary() {
        assertEquals("999 m", DisplayUnits.METRIC.distance(999.0))
        assertEquals("1.00 km", DisplayUnits.METRIC.distance(1000.0))
        assertEquals("1.00 mi", DisplayUnits.IMPERIAL.distance(1609.344))
    }
    @Test fun metricStatusPreservesUnknownStaleAndCalibrationStates() {
        val units = DisplayUnits.METRIC
        fun label(value: Double?, status: org.ncssar.rid2caltopo.video.surface.MeasurementStatus) =
            org.ncssar.rid2caltopo.video.surface.measurementLabel(value, status, "'", units = units)
        assertEquals("-30 m", label(-100.0, org.ncssar.rid2caltopo.video.surface.MeasurementStatus.Available))
        assertEquals("Unk", label(null, org.ncssar.rid2caltopo.video.surface.MeasurementStatus.Available))
        assertEquals("POS?", label(100.0, org.ncssar.rid2caltopo.video.surface.MeasurementStatus.Stale))
        assertEquals("CAL", label(null, org.ncssar.rid2caltopo.video.surface.MeasurementStatus.CalibrationRequired))
    }
    @Test fun settingsLabelsAndHelpUseThePendingSystem() {
        assertEquals("30.0 m", RidLocationAccuracyPrefs.labelForCode(9, DisplayUnits.METRIC))
        assertEquals("98.4 ft", RidLocationAccuracyPrefs.labelForCode(9, DisplayUnits.IMPERIAL))
        assertTrue(org.ncssar.rid2caltopo.ui.SettingsFieldHelp.description("Min Dist", DisplayUnits.METRIC).contains("0.61 m"))
        assertTrue(org.ncssar.rid2caltopo.ui.SettingsFieldHelp.description("Proximity spacing", DisplayUnits.METRIC).contains("15.24 m"))
    }
}
