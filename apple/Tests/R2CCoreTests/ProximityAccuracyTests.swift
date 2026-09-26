import Foundation
import Testing
@testable import R2CCore

private let proximityNow = Date(timeIntervalSince1970: 10_000)
private func aircraft(_ id: String, feet: Double = 0, displayAltitude: Double? = 100,
                      quality: RidProximityTelemetry = .init(), age: Double = 0) -> RidProximityDrone {
    RidProximityDrone(remoteID: id, mappedID: id, latitude: 39 + feet * 0.3048 / 6_371_000 * 180 / .pi,
        longitude: -121, altitudeMeters: displayAltitude, sampleDate: proximityNow.addingTimeInterval(-age),
        teamDrone: true, localAlertEligible: id == "A", telemetry: quality)
}
private func alert(_ first: RidProximityDrone, _ second: RidProximityDrone, threshold: Double = 100) -> RidProximityAlertState? {
    var engine = RidProximityAlertEngine()
    return engine.update(drones: [first, second], thresholdFeet: threshold, enabled: true, predictiveEnabled: false, now: proximityNow).activeAlert
}
private func geo(_ altitude: Double, error: Double = 1, horizontal: Double = 1) -> RidProximityTelemetry {
    .init(horizontalAccuracyMeters: horizontal, absoluteAltitudeMeters: altitude,
          altitudeReference: .geodetic, verticalAccuracyMeters: error)
}

@Test func proximityUnknownAccuracyAddsFiftyFeetForEachAircraft() {
    let result = alert(aircraft("A"), aircraft("B", feet: 150))
    #expect(result != nil)
    #expect(result?.verticalSeparationKnown == false)
    #expect((result?.horizontalSeparationFeet ?? 0) > 149) // display actual spacing, not lower bound
    #expect(alert(aircraft("A"), aircraft("B", feet: 210)) == nil)
}

@Test func proximityReportedAccuracyAndMinimumSetting() {
    let quality = geo(100, horizontal: 30)
    #expect(alert(aircraft("A", quality: quality), aircraft("B", feet: 250, quality: quality)) != nil)
    #expect(alert(aircraft("A", quality: quality), aircraft("B", feet: 310, quality: quality)) == nil)
    let result = alert(aircraft("A", quality: geo(100)), aircraft("B", feet: 45, quality: geo(100)), threshold: 40)
    #expect(result?.thresholdFeet == 50)
}

@Test func proximityDifferentTakeoffHeightsDoNotCreateVerticalSeparation() {
    let result = alert(aircraft("A", displayAltitude: 200, quality: geo(1200)),
                       aircraft("B", displayAltitude: 50, quality: geo(1200)))
    #expect(result?.verticalSeparationKnown == true)
    #expect(result?.verticalSeparationFeet == 0)
    #expect(alert(aircraft("A", quality: geo(100)), aircraft("B", quality: geo(200))) == nil)
}

@Test func proximityVerticalUncertaintyAndIncompatibleReferences() {
    #expect(alert(aircraft("A", quality: geo(100, error: 25)), aircraft("B", quality: geo(160, error: 25))) != nil)
    let pressure = RidProximityTelemetry(horizontalAccuracyMeters: 1, absoluteAltitudeMeters: 500,
                                        altitudeReference: .pressure, verticalAccuracyMeters: 1)
    #expect(alert(aircraft("A", quality: geo(100)), aircraft("B", quality: pressure))?.verticalSeparationKnown == false)
    let unknownAccuracy = RidProximityTelemetry(absoluteAltitudeMeters: 500, altitudeReference: .geodetic)
    #expect(alert(aircraft("A", quality: geo(100)), aircraft("B", quality: unknownAccuracy))?.verticalSeparationKnown == false)
    #expect(alert(aircraft("A", quality: geo(100)), aircraft("B", displayAltitude: 900))?.verticalSeparationKnown == false)
}

@Test func proximityAgeDoesNotInflateDistanceAndStalePositionsExpire() {
    #expect(alert(aircraft("A", quality: geo(100)), aircraft("B", feet: 250, quality: geo(100))) == nil)
    #expect(alert(aircraft("A", quality: geo(100), age: 1), aircraft("B", feet: 250, quality: geo(100), age: 1)) == nil)
    #expect(alert(aircraft("A", quality: geo(100), age: 6), aircraft("B", quality: geo(900), age: 6)) == nil)
    #expect(alert(aircraft("A", quality: geo(100)), aircraft("B", quality: geo(900), age: -1)) == nil)
}

@Test func proximityPredictionCannotHideReportedConflictAndSourceSwitch() {
    var engine = RidProximityAlertEngine()
    let first = aircraft("A", quality: geo(100), age: 1)
    let initial = aircraft("B", feet: 10, quality: geo(100), age: 1)
    _ = engine.update(drones: [first, initial], thresholdFeet: 100, enabled: true, now: proximityNow.addingTimeInterval(-1))
    let result = engine.update(drones: [aircraft("A", quality: geo(100)), aircraft("B", feet: 90, quality: geo(100))], thresholdFeet: 100, enabled: true, now: proximityNow)
    #expect(result.activeAlert != nil)
    // SEI or a legacy relay has no validated altitude, regardless of the previous RID altitude.
    let switched = engine.update(drones: [aircraft("A", quality: geo(100)), aircraft("B", feet: 90, displayAltitude: 900)], thresholdFeet: 100, enabled: true, now: proximityNow)
    #expect(switched.activeAlert?.verticalSeparationKnown == false)
}

@Test func proximityAccuracyCodesAndAltitudeSelection() {
    #expect(RidProximityTelemetry.horizontalAccuracyMeters(code: 9) == 30)
    #expect(RidProximityTelemetry.horizontalAccuracyMeters(code: 10) == 10)
    #expect(RidProximityTelemetry.horizontalAccuracyMeters(code: 0) == 15.24)
    #expect(RidProximityTelemetry.verticalAccuracyMeters(code: 0) == nil)
    let quality = RidProximityTelemetry.fromRID(horizontalCode: 9, geodetic: 500, pressure: 300, verticalCode: 3, barometerCode: 4)
    #expect(quality.absoluteAltitudeMeters == 500)
    #expect(quality.altitudeReference == .geodetic)
    #expect(quality.verticalAccuracyMeters == 25)
    #expect(RidProximityTelemetry.fromRID(horizontalCode: 9, geodetic: -1000, pressure: 300, verticalCode: 0, barometerCode: 4).altitudeReference == .pressure)
    #expect(!RidProximityTelemetry.fromRID(horizontalCode: 9, geodetic: -1000, pressure: -1000, verticalCode: 0, barometerCode: 0).hasUsableAltitude)
}

@Test func proximityDecoderKeepsIndependentVerticalAndBarometerAccuracy() throws {
    var bytes = Data(repeating: 0, count: 25)
    bytes[0] = 0x12 // location, protocol v2
    bytes[19] = 0x39 // vertical 25m, horizontal 30m
    bytes[20] = 0x42 // barometric 10m, speed accuracy code 2
    let message = try OpenDroneIDParser.parseMessage(bytes)
    guard case let .location(location) = message.payload else { Issue.record("Expected location"); return }
    #expect(location.horizontalAccuracyCode == 9)
    #expect(location.verticalAccuracyCode == 3)
    #expect(location.barometerAccuracyCode == 4)
}

@Test func proximityTrackNormalizationPreservesQualityAndVideoClearsIt() async {
    let store = RidTrackStore()
    let quality = geo(1200, error: 10, horizontal: 30)
    _ = await store.ingest(RidObservation(source: .bluetoothLegacy, aircraftId: " A ",
        receivedAt: proximityNow, latitude: 39, longitude: -121, altitudeMeters: 200,
        horizontalAccuracyCode: 9, proximityTelemetry: quality))
    let first = await store.snapshot()
    #expect(first.first?.lastObservation.proximityTelemetry == quality)
    _ = await store.ingest(RidObservation(source: .djiVideo, aircraftId: "A",
        receivedAt: proximityNow.addingTimeInterval(1), latitude: 39, longitude: -121, altitudeMeters: 900))
    let second = await store.snapshot()
    #expect(second.first?.lastObservation.proximityTelemetry.hasUsableAltitude == false)
    #expect(second.first?.lastObservation.proximityTelemetry.horizontalAccuracyMeters == 15.24)
}

@Test func proximityDoesNotInventVerticalMotion() {
    var engine = RidProximityAlertEngine()
    _ = engine.update(drones: [aircraft("A", quality: geo(100), age: 1),
                               aircraft("B", feet: 100, quality: geo(150), age: 1)],
                      thresholdFeet: 100, enabled: true, now: proximityNow.addingTimeInterval(-1))
    let result = engine.update(drones: [aircraft("A", quality: geo(100)),
                                        aircraft("B", feet: 90, quality: geo(150))],
                              thresholdFeet: 100, enabled: true, now: proximityNow)
    #expect(result.activeAlert == nil)
}

@Test func proximityClearsAfterFreshSeparationBeyond600Feet() {
    var engine = RidProximityAlertEngine()
    #expect(engine.update(drones: [aircraft("A"), aircraft("B", feet: 10)], thresholdFeet: 100, enabled: true, now: proximityNow).activeAlert != nil)
    for second in 1...4 {
        let output = engine.update(drones: [aircraft("A", age: -Double(second)), aircraft("B", feet: 650, age: -Double(second))], thresholdFeet: 100, enabled: true, now: proximityNow.addingTimeInterval(Double(second)))
        if second == 4 { #expect(output.activeAlert == nil) }
    }
}

@Test func proximityExpiresWithoutNewPositionsAndDoesNotResumeStalePair() {
    var engine = RidProximityAlertEngine()
    let drones = [aircraft("A"), aircraft("B", feet: 10)]
    #expect(engine.update(drones: drones, thresholdFeet: 100, enabled: true, now: proximityNow).activeAlert != nil)
    for second in 1...10 {
        let output = engine.update(drones: drones, thresholdFeet: 100, enabled: true, now: proximityNow.addingTimeInterval(Double(second)))
        if second >= 9 { #expect(output.activeAlert == nil) }
    }
    #expect(engine.update(drones: [aircraft("A", age: 6), aircraft("B", feet: 650)], thresholdFeet: 100, enabled: true, now: proximityNow).activeAlert == nil)
    #expect(engine.update(drones: drones, thresholdFeet: 100, enabled: true, now: proximityNow).activeAlert != nil)
    _ = engine.suspend()
    _ = engine.update(drones: drones, thresholdFeet: 100, enabled: true, now: proximityNow.addingTimeInterval(6))
    #expect(engine.resume().activeAlert == nil)
}

@Test func proximityConfigured50And75FootSpacingIsRespected() {
    for threshold in [50.0, 75.0] {
        #expect(alert(aircraft("A", quality: geo(100)), aircraft("B", feet: threshold - 5, quality: geo(100)), threshold: threshold)?.thresholdFeet == threshold)
        #expect(alert(aircraft("A", quality: geo(100)), aircraft("B", feet: threshold + 10, quality: geo(100)), threshold: threshold) == nil)
    }
}

@Test func ridAccuracyIsRadialAndBothErrorsAreAdded() {
    let fiftyFeet = geo(100, horizontal: 15.24)
    #expect(alert(aircraft("A", quality: fiftyFeet), aircraft("B", feet: 199, quality: fiftyFeet)) != nil)
    #expect(alert(aircraft("A", quality: fiftyFeet), aircraft("B", feet: 201, quality: fiftyFeet)) == nil)
    let thirtyMeters = geo(100, horizontal: RidProximityTelemetry.horizontalAccuracyMeters(code: 9))
    #expect(alert(aircraft("A", quality: thirtyMeters), aircraft("B", feet: 290, quality: thirtyMeters)) != nil)
    #expect(alert(aircraft("A", quality: thirtyMeters), aircraft("B", feet: 305, quality: thirtyMeters)) == nil)
}

@Test func reportedAccuracyThresholdDoesNotGrowBetweenFreshPackets() {
    for age in [0.0, 1.0, 2.4, 4.9] {
        #expect(alert(aircraft("A", quality: geo(100, horizontal: 30), age: age), aircraft("B", feet: 220, quality: geo(100, horizontal: 3), age: age)) == nil)
        #expect(alert(aircraft("A", quality: geo(100, horizontal: 30), age: age), aircraft("B", feet: 200, quality: geo(100, horizontal: 3), age: age)) != nil)
    }
}
