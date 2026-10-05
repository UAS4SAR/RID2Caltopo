import Foundation
import Testing
@testable import R2CCore

private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

private func candidate(_ id: String = "DRONE1", agl: Double, at telemetryAt: Date) -> OperationalAltitudeAlertCandidate {
    OperationalAltitudeAlertCandidate(remoteID: id, aglFeet: agl, telemetryAt: telemetryAt)
}

/// Feeds one sample per second and returns the decision for each.
private func run(_ notifier: inout OperationalAltitudeAlertNotifier, agl: Double, seconds: [Int]) -> [OperationalAltitudeAlertNotifier.Decision?] {
    seconds.map { second in
        let now = t0.addingTimeInterval(TimeInterval(second))
        return notifier.update(candidates: [candidate(agl: agl, at: now)], now: now)
    }
}

@Test func altitudeAlertMatchesAndroidConstantsAndPhrase() {
    #expect(OperationalAltitudeAlertNotifier.limitFeet == 200)
    #expect(OperationalAltitudeAlertNotifier.nearLimitRatio == 0.90)
    #expect(OperationalAltitudeAlertNotifier.overRepeatInterval == 15)
    #expect(OperationalAltitudeAlertNotifier.nearRepeatInterval == 30)
    #expect(OperationalAltitudeAlertNotifier.maximumSampleAge == 5)
    #expect(OperationalAltitudeAlertNotifier.spokenPhrase == "Altitude")
    #expect(OperationalAltitudeAlertNotifier.spokenCooldown == 15)
}

@Test func altitudeAlertRepeatsEveryFifteenSecondsWhileOverLimit() {
    var notifier = OperationalAltitudeAlertNotifier()
    let decisions = run(&notifier, agl: 215, seconds: Array(0...45))
    let notifiedAt = decisions.enumerated().filter { $0.element?.shouldNotify == true }.map(\.offset)
    #expect(notifiedAt == [0, 15, 30, 45])
    #expect(decisions.allSatisfy { $0?.severity == .overLimit })
}

@Test func altitudeAlertNearTierUsesThirtySecondsAndSeverityChangeNotifies() {
    var notifier = OperationalAltitudeAlertNotifier()
    let near = run(&notifier, agl: 185, seconds: [0, 20])
    #expect(near[0]?.severity == .caution)
    #expect(near[0]?.shouldNotify == true)
    #expect(near[1]?.shouldNotify == false)
    let over = run(&notifier, agl: 201, seconds: [21])
    #expect(over[0]?.severity == .overLimit)
    #expect(over[0]?.shouldNotify == true)
}

@Test func altitudeAlertResetsWhenNoCandidateAndIgnoresStaleSamples() {
    var notifier = OperationalAltitudeAlertNotifier()
    _ = run(&notifier, agl: 215, seconds: [0])
    let cleared = notifier.update(candidates: [], now: t0.addingTimeInterval(2))
    #expect(cleared == nil)
    let again = run(&notifier, agl: 215, seconds: [3])
    #expect(again[0]?.shouldNotify == true)
    let at10 = t0.addingTimeInterval(10)
    let stale = notifier.update(candidates: [candidate(agl: 215, at: at10.addingTimeInterval(-5.1))], now: at10)
    let notANumber = notifier.update(candidates: [candidate(agl: .nan, at: at10)], now: at10)
    let low = notifier.update(candidates: [candidate(agl: 150, at: at10)], now: at10)
    #expect(stale == nil)
    #expect(notANumber == nil)
    #expect(low == nil)
}

@Test func altitudeAlertPicksHighestSeverityThenHighestAltitude() {
    var notifier = OperationalAltitudeAlertNotifier()
    let decision = notifier.update(candidates: [
        candidate("A", agl: 199, at: t0),
        candidate("B", agl: 205, at: t0),
        candidate("C", agl: 240, at: t0),
    ], now: t0)
    #expect(decision?.remoteID == "C")
}

@Test func standaloneAltitudeAlertsUseProximityEligibilityRule() {
    // Coordinated altitude still uses this gate. Standalone spoken altitude no longer
    // requires confirmation (Android parity); identityProvider scopes team aircraft.
    #expect(RidProximityEligibility.allows(locallyConfirmed: true, coordinationRequired: false, coordinatorEligible: false))
    #expect(!RidProximityEligibility.allows(locallyConfirmed: false, coordinationRequired: false, coordinatorEligible: false))
    #expect(!RidProximityEligibility.allows(locallyConfirmed: true, coordinationRequired: true, coordinatorEligible: false))
}
