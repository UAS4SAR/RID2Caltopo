import Foundation
import Testing
@testable import R2CCore

// Separate gate instances: the shared gate stays `.notRequired` for every other test.

@Test func trackerGateHoldsTeamAccessUntilTheTrackerAuthorizesTheDevice() async throws {
    let gate = TrackerStartupGate()
    gate.beginStartupCheckIn()
    #expect(gate.teamAccessBlockReason == TrackerStartupGate.waitingMessage)
    #expect(!gate.teamAccessRefused)
    let waiter = Task { try await gate.awaitTeamAccess(maximumWait: 30) }
    try await Task.sleep(nanoseconds: 50_000_000)
    gate.set(.checkedIn)
    try await waiter.value
    #expect(gate.teamAccessBlockReason == nil)
}

@Test func unreachableTrackerRefusesTeamAccessOnEveryServer() async {
    // No CalTopo Desktop exception: the Tracker regulates org team access everywhere.
    let gate = TrackerStartupGate()
    gate.beginStartupCheckIn()
    let waiter = Task { try await gate.awaitTeamAccess(maximumWait: 30) }
    try? await Task.sleep(nanoseconds: 50_000_000)
    gate.set(.unreachable)
    await #expect(throws: TrackerStartupGate.Refused(message: TrackerStartupGate.unreachableMessage)) { try await waiter.value }
    #expect(gate.teamAccessRefused)
    #expect(TrackerStartupGate.unreachableMessage.contains("personal CalTopo account"))
}

@Test func rejectedDeviceIsHandledLikeADownTracker() async {
    let gate = TrackerStartupGate()
    gate.set(.rejected)
    await #expect(throws: TrackerStartupGate.Refused(message: TrackerStartupGate.rejectedMessage)) {
        try await gate.awaitTeamAccess()
    }
    #expect(gate.teamAccessRefused)
}

@Test func nonOrganizationDevicesAreNeverGated() async throws {
    let gate = TrackerStartupGate()
    try await gate.awaitTeamAccess(maximumWait: 0)
    #expect(gate.state == .notRequired)
    #expect(!gate.teamAccessRefused)
}

@Test func gateWaitHasAnUpperBoundAndThenRefusesTeamAccess() async {
    let gate = TrackerStartupGate()
    gate.beginStartupCheckIn()
    await #expect(throws: TrackerStartupGate.Refused(message: TrackerStartupGate.waitingMessage)) {
        try await gate.awaitTeamAccess(maximumWait: 0.05)
    }
}

@Test func waitForDecisionReturnsOnceTheCheckInDecides() async throws {
    let gate = TrackerStartupGate()
    gate.beginStartupCheckIn()
    let waiter = Task { await gate.waitForDecision(maximumWait: 30) }
    try await Task.sleep(nanoseconds: 50_000_000)
    gate.set(.unreachable)
    await waiter.value
    #expect(gate.teamAccessBlockReason == TrackerStartupGate.unreachableMessage)
}

@Test func personalAccountRequestsAreNeverGated() {
    // Only the team credential kind reaches the gate; personal and session logins do not.
    let personal = CaltopoLiveConfiguration(mapID: "M", credentialID: "id", credentialSecretBase64: "c2VjcmV0",
                                            personalAccountID: "U", personalSignedAccount: true)
    let session = CaltopoLiveConfiguration(mapID: "M", credentialID: "", credentialSecretBase64: "",
                                           personalSessionID: UUID(), personalAccountID: "U")
    let team = CaltopoLiveConfiguration(mapID: "M", credentialID: "id", credentialSecretBase64: "c2VjcmV0")
    #expect(personal.credentialKind == "personal")
    #expect(session.credentialKind == "session")
    #expect(team.credentialKind == "team")
}

@Test func teamAccessReturnsOnlyAfterASuccessfulAuthorization() {
    var state = TrackerStartupCheckIn.nextGateState(previous: .pending, outcome: .unreachable("timeout"))
    #expect(state == .unreachable)
    state = TrackerStartupCheckIn.nextGateState(previous: state, outcome: .unreachable("timeout"))
    #expect(state == .unreachable)
    state = TrackerStartupCheckIn.nextGateState(previous: state, outcome: .checkedIn)
    #expect(state == .checkedIn)
    // The Tracker withdrawing authorization revokes team access mid-session.
    state = TrackerStartupCheckIn.nextGateState(previous: state, outcome: .rejected)
    #expect(state == .rejected)
    state = TrackerStartupCheckIn.nextGateState(previous: state, outcome: .unreachable("x"))
    #expect(state == .unreachable)
}

@Test func coldTrackerIsRetriedWithBackoffBeforeFallingBack() async {
    var sleeps: [TimeInterval] = []
    var attempts = 0
    let outcome = await TrackerStartupCheckIn.checkInWithRetries(
        sleep: { sleeps.append($0) },
        fetch: { _ in attempts += 1; return .unreachable("timeout") })
    #expect(attempts == 3)
    #expect(sleeps == [3, 8])
    #expect(TrackerStartupCheckIn.gateState(for: outcome) == .unreachable)
}

@Test func slowTrackerThatAnswersOnTheSecondAttemptChecksIn() async {
    let outcome = await TrackerStartupCheckIn.checkInWithRetries(
        sleep: { _ in },
        fetch: { attempt in attempt == 1 ? .unreachable("cold start") : .checkedIn })
    #expect(outcome == .checkedIn)
}

@Test func rejectionStopsRetryingAtOnce() async {
    var attempts = 0
    let outcome = await TrackerStartupCheckIn.checkInWithRetries(
        sleep: { _ in }, fetch: { _ in attempts += 1; return .rejected })
    #expect(attempts == 1)
    #expect(outcome == .rejected)
}

@Test func laterUnreachableRefreshKeepsAnEarlierCheckIn() {
    #expect(TrackerStartupCheckIn.nextGateState(previous: .checkedIn, outcome: .unreachable("x")) == .checkedIn)
    #expect(TrackerStartupCheckIn.nextGateState(previous: .unreachable, outcome: .checkedIn) == .checkedIn)
    #expect(TrackerStartupCheckIn.nextGateState(previous: .pending, outcome: .unreachable("x")) == .unreachable)
    #expect(TrackerStartupCheckIn.nextGateState(previous: .checkedIn, outcome: .rejected) == .rejected)
}

@Test func backgroundRetryBacksOffToFiveMinutes() {
    var delay: TimeInterval? = nil
    var seen: [TimeInterval] = []
    for _ in 0 ..< 6 { delay = TrackerStartupCheckIn.nextBackgroundRetry(after: delay); seen.append(delay!) }
    #expect(seen == [30, 60, 120, 240, 300, 300])
}

@Test func upgradedAppReappliesAnUnchangedOrganizationConfiguration() {
    // S25U, November 2026: version 1794325364660 stored by 2.5.7 (which ignored Drone Ownership).
    #expect(ManagedConfigurationRefreshPolicy.shouldApply(
        remoteVersion: 1794325364660, localVersion: 1794325364660, hasCredentials: true,
        appliedByAppVersion: nil, currentAppVersion: "2.5.8(444)"))
    #expect(ManagedConfigurationRefreshPolicy.shouldApply(
        remoteVersion: 1794325364660, localVersion: 1794325364660, hasCredentials: true,
        appliedByAppVersion: "2.5.8(444)", currentAppVersion: "2.5.9(445)"))
    #expect(!ManagedConfigurationRefreshPolicy.shouldApply(
        remoteVersion: 1794325364660, localVersion: 1794325364660, hasCredentials: true,
        appliedByAppVersion: "2.5.8(444)", currentAppVersion: "2.5.8(444)"))
    #expect(ManagedConfigurationRefreshPolicy.appVersionLabel(shortVersion: "2.5.8", build: "444") == "2.5.8(444)")
}

@Test func reappliedConfigurationAdoptsTheTeamDroneOwnership() {
    let record = DroneOwnershipPolicy.receive(.initial, published: DroneOwnershipPolicy.streamingDevice)
    #expect(record.mode == DroneOwnershipPolicy.streamingDevice)
}

@Test func personalLoginSupportDefaultsToCredentials() {
    #expect(CaltopoPersonalLoginMode.resolve(stored: nil) == .credentials)
    #expect(CaltopoPersonalLoginMode.resolve(stored: "credentials") == .credentials)
    #expect(CaltopoPersonalLoginMode.resolve(stored: "sessionCookies") == .sessionCookies)
    // Before policy 2 an automatic Session Cookies value was indistinguishable from a choice.
    #expect(CaltopoPersonalLoginMode.migrate(stored: "sessionCookies", storedPolicyVersion: 0) == .credentials)
    #expect(CaltopoPersonalLoginMode.migrate(stored: nil, storedPolicyVersion: 0) == .credentials)
    // A Session Cookies choice made after the migration is kept.
    #expect(CaltopoPersonalLoginMode.migrate(stored: "sessionCookies", storedPolicyVersion: 2) == .sessionCookies)
}
