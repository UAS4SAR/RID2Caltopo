import Testing
@testable import R2CCore

@Test func measurementRepresentationsMatchPhysicalQuantities() {
    #expect(DisplayUnits.imperial.length(30.48) == "100 ft")
    #expect(DisplayUnits.metric.feet(100) == "30 m")
    #expect(DisplayUnits.metric.miles(1, decimals: 2) == "1.61 km")
    #expect(DisplayUnits.metric.area(1) == "2.59 km²")
    #expect(DisplayUnits.imperial.speed(10) == "22.4 mph")
    #expect(DisplayUnits.metric.speed(10) == "10.0 m/s")
    #expect(DisplayUnits.imperial.resolution(2.54) == "1.00 in/px")
    #expect(DisplayUnits.metric.feet(-100) == "-30 m")
    #expect(DisplayUnits.metric.length(-0.01) == "0 m")
}

@Test func editedMeasurementUnitsRoundTripWithoutChangingCanonicalValues() {
    for units in DisplayUnits.allCases {
        #expect(abs(units.lengthMeters(units.lengthValue(30.48)) - 30.48) < 1e-10)
        #expect(abs(units.resolutionCm(units.resolutionValue(2)) - 2) < 1e-10)
        #expect(abs(units.grams(units.gramsValue(1000)) - 1000) < 1e-10)
    }
}

@Test func measurementDistanceUnitBoundaries() {
    #expect(DisplayUnits.metric.distance(999) == "999 m")
    #expect(DisplayUnits.metric.distance(1000) == "1.00 km")
    #expect(DisplayUnits.imperial.distance(1609.344) == "1.00 mi")
}

@Test func metricStatusRetainsMissingStaleCalibrationAndNegativeClearance() {
    #expect(OperationalMeasurementStatus.available.label(-100, suffix: "'", units: .metric) == "-30 m")
    #expect(OperationalMeasurementStatus.pending.label(100, suffix: "'", showPendingValue: true, units: .metric) == "30 m?")
    #expect(OperationalMeasurementStatus.stale.label(100, suffix: "'", units: .metric) == "POS?")
    #expect(OperationalMeasurementStatus.calibrationRequired.label(nil, suffix: "'", units: .metric) == "CAL")
    #expect(OperationalMeasurementStatus.available.label(nil, suffix: "'", units: .metric) == "Unk")
    #expect(OperationalMeasurementStatus.available.label(1001, suffix: "'", maxAbs: 1000, units: .metric) == "Unk")
    #expect(OperationalMeasurementStatus.available.label(90, suffix: "°", units: .metric) == "90°")
}

@Test func settingsHelpUsesPendingMeasurementSystem() {
    #expect(SettingsFieldHelp.description(for: "Min Dist", units: .metric).contains("0.61 m"))
    #expect(SettingsFieldHelp.description(for: "Min Dist", units: .imperial).contains("2.00 ft"))
    #expect(SettingsFieldHelp.description(for: "Proximity spacing", units: .metric).contains("15.24 m"))
}
