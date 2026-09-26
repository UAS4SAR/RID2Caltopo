import Foundation
import Testing
@testable import R2CCore

@Test func proximityConsentDefaultsOffAndIsBoundToDeviceAndNoticeVersion() {
    #expect(!RidProximityConsent().enabled)
    #expect(!RidProximityConsent(acceptedVersion: 0, acceptedDeviceID: "A", currentDeviceID: "A").enabled)
    #expect(!RidProximityConsent(acceptedVersion: 99, acceptedDeviceID: "A", currentDeviceID: "A").enabled)
    #expect(!RidProximityConsent(acceptedVersion: 1, acceptedDeviceID: "A", currentDeviceID: "B").enabled)
    #expect(!RidProximityConsent(acceptedVersion: 1).enabled)
    #expect(RidProximityConsent(acceptedVersion: 1, acceptedDeviceID: "A", currentDeviceID: "A").enabled)
}

@Test func proximityConsentRequiresAcknowledgmentEveryOffToOnTransition() {
    var state = RidProximityConsent()
    state.confirmEnable()
    #expect(!state.enabled)
    state.requestEnable()
    #expect(state.noticePending && !state.enabled)
    state.cancel()
    state.confirmEnable()
    #expect(!state.enabled)
    state.requestEnable()
    state.confirmEnable()
    #expect(state.enabled && !state.noticePending)
    state.disable()
    state.confirmEnable()
    #expect(!state.enabled)
    state.requestEnable()
    #expect(state.noticePending)
    #expect(RidProximityConsent.notice.contains("No alert does not mean the airspace is clear."))
    #expect(RidProximityConsent.notice.contains("DJI video telemetry accuracy has not been verified"))
}

@Test func proximityEngineDefaultsOffAndDisablingClearsActiveAndSuspendedState() {
    let now = Date()
    let a = RidProximityDrone(remoteID: "A", mappedID: "A", latitude: 39, longitude: -121,
        altitudeMeters: 100, sampleDate: now, teamDrone: true, localAlertEligible: true)
    let b = RidProximityDrone(remoteID: "B", mappedID: "B", latitude: 39.00001, longitude: -121,
        altitudeMeters: 100, sampleDate: now, teamDrone: true, localAlertEligible: false)
    var engine = RidProximityAlertEngine()
    #expect(engine.update(drones: [a, b], thresholdFeet: 100, now: now).activeAlert == nil)
    #expect(engine.update(drones: [a, b], thresholdFeet: 100, enabled: true, now: now).activeAlert != nil)
    #expect(engine.suspend().canResume)
    let disabled = engine.update(drones: [a, b], thresholdFeet: 100, enabled: false, now: now)
    #expect(disabled.activeAlert == nil && disabled.suspendedAlert == nil && !disabled.canResume)
    #expect(engine.resume().activeAlert == nil)
    #expect(engine.update(drones: [a, b], thresholdFeet: 100, enabled: true, now: now).activeAlert != nil)
}

@Test func proximitySuspendedStatusSurvivesClearedPairsAndCanResume() {
    var engine = RidProximityAlertEngine()
    #expect(engine.suspend().isSuspended)
    #expect(engine.update(drones: [], thresholdFeet: 100, enabled: true).isSuspended)
    #expect(!engine.resume().isSuspended)
    #expect(!engine.update(drones: [], thresholdFeet: 100, enabled: false).isSuspended)
}
