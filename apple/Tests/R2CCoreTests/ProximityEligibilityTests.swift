import Foundation
import Testing
@testable import R2CCore

@Test func standaloneProximityNeedsConfirmationButNoTrackerLease() {
    #expect(RidProximityEligibility.allows(locallyConfirmed: true, coordinationRequired: false, coordinatorEligible: false))
    #expect(!RidProximityEligibility.allows(locallyConfirmed: false, coordinationRequired: false, coordinatorEligible: true))
    #expect(!RidProximityEligibility.allows(locallyConfirmed: true, coordinationRequired: true, coordinatorEligible: false))
    #expect(RidProximityEligibility.allows(locallyConfirmed: true, coordinationRequired: true, coordinatorEligible: true))
}

@Test func standaloneConfirmedMiniAlertsForNearbyUnconfirmedTraffic() {
    let now = Date()
    let eligible = RidProximityEligibility.allows(locallyConfirmed: true, coordinationRequired: false, coordinatorEligible: false)
    let mini = RidProximityDrone(remoteID: "1581F6Z9C24BH0036EJL", mappedID: "1sar7DjMn4Pr", latitude: 39,
        longitude: -121, altitudeMeters: 523, sampleDate: now, teamDrone: true, localAlertEligible: eligible)
    let traffic = RidProximityDrone(remoteID: "1865F10X000000001155", mappedID: "traffic", latitude: 39.00001,
        longitude: -121, altitudeMeters: 100, sampleDate: now, teamDrone: false, localAlertEligible: false)
    var engine = RidProximityAlertEngine()
    let output = engine.update(drones: [mini, traffic], thresholdFeet: 100, enabled: true, now: now)
    #expect(output.activeAlert != nil)
    #expect(output.activeAlert?.verticalSeparationKnown == false)
    #expect(engine.update(drones: [mini, traffic], thresholdFeet: 100, enabled: false, now: now).activeAlert == nil)
}

@Test func allAircraftScopeIncludesUnclaimedTrafficAndSwitchesImmediately() {
    let now = Date()
    let a = RidProximityDrone(remoteID: "A", mappedID: "A", latitude: 39,
        longitude: -121, altitudeMeters: 523, sampleDate: now, teamDrone: false, localAlertEligible: false)
    let b = RidProximityDrone(remoteID: "B", mappedID: "B", latitude: 39.00001,
        longitude: -121, altitudeMeters: 100, sampleDate: now, teamDrone: false, localAlertEligible: false)
    var engine = RidProximityAlertEngine()
    #expect(engine.update(drones: [a,b], thresholdFeet: 100, enabled: true, now: now).activeAlert == nil)
    #expect(engine.update(drones: [a,b], thresholdFeet: 100, enabled: true, alertAllAircraft: true, now: now).activeAlert != nil)
    #expect(engine.update(drones: [a,b], thresholdFeet: 100, enabled: true, now: now).activeAlert == nil)
    #expect(engine.update(drones: [a,b], thresholdFeet: 100, enabled: false, alertAllAircraft: true, now: now).activeAlert == nil)
    _ = engine.update(drones: [a,b], thresholdFeet: 100, enabled: true, alertAllAircraft: true, now: now)
    _ = engine.suspend()
    let narrowed = engine.update(drones: [a,b], thresholdFeet: 100, enabled: true, now: now)
    #expect(narrowed.isSuspended)
    #expect(narrowed.activeAlert == nil && narrowed.suspendedAlert == nil)
    let widened = engine.update(drones: [a,b], thresholdFeet: 100, enabled: true, alertAllAircraft: true, now: now)
    #expect(widened.isSuspended && widened.suspendedAlert != nil && widened.activeAlert == nil)
}
