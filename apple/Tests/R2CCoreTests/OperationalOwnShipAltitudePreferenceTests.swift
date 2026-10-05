import Foundation
import Testing
@testable import R2CCore

struct OperationalOwnShipAltitudePreferenceTests {
    private let now = Date(timeIntervalSince1970: 7_000)

    @Test func prefersFreshRIDOverSEI() {
        let sample = OperationalOwnShipAltitudePreference.resolve(
            ridAglFeet: 220,
            ridTelemetryAt: now.addingTimeInterval(-2),
            seiRelativeUpMeters: 100,
            seiTelemetryAt: now,
            now: now,
            maximumAgeSeconds: 5
        )
        #expect(sample?.aglFeet == 220)
        #expect(sample?.usedSEI == false)
    }

    @Test func fallsBackToSEIWhenRIDStale() {
        let sample = OperationalOwnShipAltitudePreference.resolve(
            ridAglFeet: 220,
            ridTelemetryAt: now.addingTimeInterval(-8),
            seiRelativeUpMeters: 80,
            seiTelemetryAt: now.addingTimeInterval(-1),
            now: now,
            maximumAgeSeconds: 5
        )
        #expect(sample?.usedSEI == true)
        #expect(abs((sample?.aglFeet ?? 0) - 80 * OperationalOwnShipAltitudePreference.metersToFeet) < 0.01)
    }

    @Test func backgroundWindowKeepsOlderRID() {
        let sample = OperationalOwnShipAltitudePreference.resolve(
            ridAglFeet: 210,
            ridTelemetryAt: now.addingTimeInterval(-12),
            seiRelativeUpMeters: nil,
            seiTelemetryAt: nil,
            now: now,
            maximumAgeSeconds: 15
        )
        #expect(sample?.aglFeet == 210)
        #expect(sample?.usedSEI == false)
    }

    @Test func returnsNilWhenBothStale() {
        let sample = OperationalOwnShipAltitudePreference.resolve(
            ridAglFeet: 220,
            ridTelemetryAt: now.addingTimeInterval(-20),
            seiRelativeUpMeters: 90,
            seiTelemetryAt: now.addingTimeInterval(-20),
            now: now,
            maximumAgeSeconds: 15
        )
        #expect(sample == nil)
    }
}
