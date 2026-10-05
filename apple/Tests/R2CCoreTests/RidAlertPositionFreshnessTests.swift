import Foundation
import Testing
@testable import R2CCore

struct RidAlertPositionFreshnessTests {
    @Test func foregroundAndBackgroundAges() {
        #expect(RidAlertPositionFreshness.foregroundMaximumAgeSeconds == 5)
        #expect(RidAlertPositionFreshness.backgroundMaximumAgeSeconds == 15)
        #expect(RidAlertPositionFreshness.maximumAgeSeconds(inBackground: false) == 5)
        #expect(RidAlertPositionFreshness.maximumAgeSeconds(inBackground: true) == 15)
        #expect(RidProximityTelemetry.maximumPositionAgeSeconds == 5)
        #expect(OperationalAltitudeAlertNotifier.maximumSampleAge == 5)
    }

    @Test func isFreshRespectsWindow() {
        let now = Date(timeIntervalSince1970: 5_000)
        #expect(RidAlertPositionFreshness.isFresh(
            sampleAt: now.addingTimeInterval(-5),
            now: now,
            maximumAgeSeconds: 5
        ))
        #expect(!RidAlertPositionFreshness.isFresh(
            sampleAt: now.addingTimeInterval(-5.1),
            now: now,
            maximumAgeSeconds: 5
        ))
        #expect(RidAlertPositionFreshness.isFresh(
            sampleAt: now.addingTimeInterval(-12),
            now: now,
            maximumAgeSeconds: 15
        ))
        #expect(!RidAlertPositionFreshness.isFresh(
            sampleAt: now.addingTimeInterval(-15.1),
            now: now,
            maximumAgeSeconds: 15
        ))
    }

    @Test func proximityEngineAcceptsBackgroundAgeWindow() {
        var engine = RidProximityAlertEngine()
        let now = Date(timeIntervalSince1970: 6_000)
        let drones = [
            RidProximityDrone(
                remoteID: "A",
                mappedID: "A",
                latitude: 37.0,
                longitude: -122.0,
                altitudeMeters: 100,
                sampleDate: now.addingTimeInterval(-12),
                distanceToOperatorMeters: 10,
                teamDrone: true,
                localAlertEligible: true,
                telemetry: RidProximityTelemetry()
            ),
            RidProximityDrone(
                remoteID: "B",
                mappedID: "B",
                latitude: 37.0001,
                longitude: -122.0,
                altitudeMeters: 100,
                sampleDate: now.addingTimeInterval(-12),
                distanceToOperatorMeters: 10,
                teamDrone: true,
                localAlertEligible: true,
                telemetry: RidProximityTelemetry()
            ),
        ]
        let foreground = engine.update(
            drones: drones,
            thresholdFeet: 500,
            enabled: true,
            alertAllAircraft: true,
            predictiveEnabled: false,
            maximumPositionAgeSeconds: 5,
            now: now
        )
        #expect(foreground.activeAlert == nil)

        var engine2 = RidProximityAlertEngine()
        let background = engine2.update(
            drones: drones,
            thresholdFeet: 500,
            enabled: true,
            alertAllAircraft: true,
            predictiveEnabled: false,
            maximumPositionAgeSeconds: 15,
            now: now
        )
        #expect(background.activeAlert != nil)
    }
}
