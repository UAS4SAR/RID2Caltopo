import Foundation
import Testing
@testable import R2CCore

@Test func proximityConsentDefaultsOffAndIsBoundToDeviceAndNoticeVersion() {
    #expect(!RidProximityConsent().enabled)
    #expect(!RidProximityConsent(acceptedVersion: 0, acceptedDeviceID: "A", currentDeviceID: "A").enabled)
    #expect(!RidProximityConsent(acceptedVersion: 99, acceptedDeviceID: "A", currentDeviceID: "A").enabled)
    #expect(!RidProximityConsent(acceptedVersion: 2, acceptedDeviceID: "A", currentDeviceID: "B").enabled)
    #expect(!RidProximityConsent(acceptedVersion: 2).enabled)
    #expect(RidProximityConsent(acceptedVersion: 2, acceptedDeviceID: "A", currentDeviceID: "A").enabled)
}

@Test func proximityConsentVersion1AcceptanceRestoresOffAndNeedsOneReacknowledgment() {
    #expect(RidProximityConsent.noticeVersion == 2)
    let v1 = RidProximityConsent(acceptedVersion: 1, acceptedDeviceID: "A", currentDeviceID: "A")
    #expect(!v1.enabled && !v1.noticePending)
    #expect(RidProximityConsent.needsReacknowledgment(acceptedVersion: 1, acceptedDeviceID: "A", currentDeviceID: "A"))
    // Never enabled, another device's restored backup, or a newer notice: no prompt.
    #expect(!RidProximityConsent.needsReacknowledgment(acceptedVersion: 0, acceptedDeviceID: nil, currentDeviceID: "A"))
    #expect(!RidProximityConsent.needsReacknowledgment(acceptedVersion: 1, acceptedDeviceID: "B", currentDeviceID: "A"))
    #expect(!RidProximityConsent.needsReacknowledgment(acceptedVersion: 99, acceptedDeviceID: "A", currentDeviceID: "A"))
}

@Test func proximityConsentCurrentAcceptancePersistsWithoutFurtherPromptsAcrossRestarts() {
    // Launch after update: stored v1 -> prompt; user checks all four and enables.
    var storedVersion = 1
    var storedDevice: String? = "A"
    #expect(RidProximityConsent.needsReacknowledgment(acceptedVersion: storedVersion, acceptedDeviceID: storedDevice, currentDeviceID: "A"))
    storedVersion = 0; storedDevice = nil // beginReacknowledgment clears the stale acceptance first
    var state = RidProximityConsent(acceptedVersion: storedVersion, acceptedDeviceID: storedDevice, currentDeviceID: "A")
    state.requestEnable()
    for index in 0..<RidProximityConsent.noticeParagraphCount { state.toggleAcknowledgment(index) }
    state.confirmEnable()
    #expect(state.enabled)
    storedVersion = RidProximityConsent.noticeVersion; storedDevice = "A" // what confirmEnable persists
    for _ in 0..<3 {
        let restored = RidProximityConsent(acceptedVersion: storedVersion, acceptedDeviceID: storedDevice, currentDeviceID: "A")
        #expect(restored.enabled && !restored.noticePending)
        #expect(!RidProximityConsent.needsReacknowledgment(acceptedVersion: storedVersion, acceptedDeviceID: storedDevice, currentDeviceID: "A"))
    }
    // Declining the one-time prompt leaves nothing stored, so later launches stay quiet and off.
    #expect(!RidProximityConsent.needsReacknowledgment(acceptedVersion: 0, acceptedDeviceID: nil, currentDeviceID: "A"))
    #expect(!RidProximityConsent(acceptedVersion: 0, acceptedDeviceID: nil, currentDeviceID: "A").enabled)
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
    for index in 0..<RidProximityConsent.noticeParagraphCount { state.toggleAcknowledgment(index) }
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

@Test func proximityConsentRequiresEveryParagraphCheckedAndResetsChecksOnEachOpen() {
    let count = RidProximityConsent.noticeParagraphCount
    var state = RidProximityConsent()
    state.toggleAcknowledgment(0)
    #expect(state.acknowledged.isEmpty)
    state.requestEnable()
    #expect(state.acknowledged.isEmpty && !state.canConfirm)
    #expect(state.confirmLabel == "Confirm each paragraph")
    for index in 0..<(count - 1) {
        state.toggleAcknowledgment(index)
        #expect(!state.canConfirm && state.confirmLabel == "Confirm each paragraph")
    }
    var partial = state
    partial.confirmEnable()
    #expect(!partial.enabled && partial.noticePending)
    state.toggleAcknowledgment(count - 1)
    #expect(state.canConfirm && state.confirmLabel == "Enable alerts")
    var unchecked = state
    unchecked.toggleAcknowledgment(0)
    #expect(!unchecked.canConfirm)
    let before = state
    state.toggleAcknowledgment(-1)
    state.toggleAcknowledgment(count)
    #expect(state == before)
    var reopened = state
    reopened.cancel()
    #expect(reopened.acknowledged.isEmpty)
    reopened.requestEnable()
    #expect(reopened.acknowledged.isEmpty && !reopened.canConfirm)
    state.confirmEnable()
    #expect(state.enabled && state.acknowledged.isEmpty)
    state.disable()
    state.requestEnable()
    #expect(state.acknowledged.isEmpty && !state.canConfirm)
}

@Test func proximityConsentHasOneCheckboxParagraphPerStatementWithUnchangedText() {
    let paragraphs = RidProximityConsent.noticeParagraphs
    #expect(paragraphs.count == RidProximityConsent.noticeParagraphCount)
    #expect(paragraphs.joined(separator: "\n\n") == RidProximityConsent.notice)
    #expect(paragraphs[0].hasPrefix("Proximity alerts provide supplemental situational awareness."))
    #expect(paragraphs[3].hasSuffix("Do not rely on these alerts to avoid a collision."))
    #expect(paragraphs.allSatisfy { $0 == $0.trimmingCharacters(in: .whitespacesAndNewlines) })
}

@Test func proximityConsentNoticeMatchesAndroid() throws {
    let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent(
        "../../../app/src/main/java/org/ncssar/rid2caltopo/data/ProximityAlertConsent.kt").standardized
    let source = try String(contentsOf: url, encoding: .utf8)
    #expect(source.contains(RidProximityConsent.notice))
    #expect(source.contains("NOTICE_PARAGRAPH_COUNT = \(RidProximityConsent.noticeParagraphCount)"))
    #expect(source.contains("NOTICE_VERSION = \(RidProximityConsent.noticeVersion)"))
    #expect(source.contains("ENABLE_LABEL = \"\(RidProximityConsent.enableLabel)\""))
    #expect(source.contains("CONFIRM_EACH_LABEL = \"\(RidProximityConsent.confirmEachLabel)\""))
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
