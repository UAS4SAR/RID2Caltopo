import Foundation
import Testing
@testable import R2CCore

@Test func trackerReconnectLadderClimbsAndCapsAtTenSeconds() {
    var policy = TrackerReconnectPolicy()
    let delays = (0 ..< 5).map { _ in
        policy.takeRetryDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: false)
    }
    #expect(delays == [2_000, 4_000, 8_000, 10_000, 10_000])
}

@Test func trackerAckTimeoutUsesTheSameLadderWithoutImmediateRedial() {
    var policy = TrackerReconnectPolicy()
    let delays = (0 ..< 4).map { _ in
        policy.takeRetryDelayMilliseconds(kind: .ackTimeout, legacyImmediateAckRetry: false)
    }
    #expect(delays == [2_000, 4_000, 8_000, 10_000])
    #expect(policy.peekDelayMilliseconds(kind: .ackTimeout, legacyImmediateAckRetry: false) == 10_000)
}

@Test func trackerSocketOpenDoesNotResetBackoffUntilHelloAck() {
    var policy = TrackerReconnectPolicy()
    #expect(policy.takeRetryDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: false) == 2_000)
    #expect(policy.takeRetryDelayMilliseconds(kind: .ackTimeout, legacyImmediateAckRetry: false) == 4_000)
    policy.onTransportOpened(legacyImmediateAckRetry: false)
    #expect(policy.peekDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: false) == 8_000)

    policy.onHelloAcknowledged()
    #expect(policy.peekDelayMilliseconds(kind: .ackTimeout, legacyImmediateAckRetry: false) == 2_000)
}

@Test func openSocketToleratesTwelveSecondHelloAndClosesAfterThirty() {
    let sentAt: Int64 = 1_000
    #expect(!TrackerReconnectPolicy.helloAckTimedOut(
        sentAtMilliseconds: sentAt,
        nowMilliseconds: 13_000,
        socketOpen: true,
        legacyImmediateAckRetry: false
    ))
    #expect(!TrackerReconnectPolicy.helloAckTimedOut(
        sentAtMilliseconds: sentAt,
        nowMilliseconds: 31_000,
        socketOpen: true,
        legacyImmediateAckRetry: false
    ))
    #expect(TrackerReconnectPolicy.helloAckTimedOut(
        sentAtMilliseconds: sentAt,
        nowMilliseconds: 31_001,
        socketOpen: true,
        legacyImmediateAckRetry: false
    ))
    #expect(TrackerReconnectPolicy.helloAckTimeoutMilliseconds(socketOpen: true, legacyImmediateAckRetry: false) == 30_000)
}

@Test func closedSocketAndLegacyModeKeepTheTenSecondHelloBudget() {
    let sentAt: Int64 = 5_000
    #expect(!TrackerReconnectPolicy.helloAckTimedOut(
        sentAtMilliseconds: sentAt,
        nowMilliseconds: 15_000,
        socketOpen: false,
        legacyImmediateAckRetry: false
    ))
    #expect(TrackerReconnectPolicy.helloAckTimedOut(
        sentAtMilliseconds: sentAt,
        nowMilliseconds: 15_001,
        socketOpen: false,
        legacyImmediateAckRetry: false
    ))
    #expect(TrackerReconnectPolicy.helloAckTimedOut(
        sentAtMilliseconds: sentAt,
        nowMilliseconds: 15_001,
        socketOpen: true,
        legacyImmediateAckRetry: true
    ))
    #expect(TrackerReconnectPolicy.heartbeatAckTimeoutMilliseconds == 10_000)
}

@Test func legacyAckTimeoutRedialsImmediatelyWithoutAdvancingOrSurvivingOpen() {
    var policy = TrackerReconnectPolicy()
    #expect(policy.takeRetryDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: true) == 2_000)
    #expect(policy.peekDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: true) == 4_000)
    // With the legacy switch on, an ack timeout redials at once (peek and take agree, as on Android).
    #expect(policy.peekDelayMilliseconds(kind: .ackTimeout, legacyImmediateAckRetry: true) == 0)
    #expect(policy.takeRetryDelayMilliseconds(kind: .ackTimeout, legacyImmediateAckRetry: true) == 0)
    #expect(policy.peekDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: true) == 4_000)

    policy.onTransportOpened(legacyImmediateAckRetry: true)
    #expect(policy.peekDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: true) == 2_000)
}

@Test func peekDoesNotAdvanceTheReconnectLadder() {
    var policy = TrackerReconnectPolicy()
    #expect(policy.peekDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: false) == 2_000)
    #expect(policy.peekDelayMilliseconds(kind: .ackTimeout, legacyImmediateAckRetry: false) == 2_000)
    #expect(policy.takeRetryDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: false) == 2_000)
    #expect(policy.peekDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: false) == 4_000)
}

@Test func equalJitterStaysInsideHalfTheStepAndTheTenSecondCap() {
    #expect(TrackerReconnectPolicy.equalJitterMilliseconds(2_000, randomUnit: 0) == 1_000)
    #expect(TrackerReconnectPolicy.equalJitterMilliseconds(2_000, randomUnit: 1) == 2_000)
    #expect(TrackerReconnectPolicy.equalJitterMilliseconds(2_000, randomUnit: 0.5) == 1_500)
    #expect(TrackerReconnectPolicy.equalJitterMilliseconds(10_000, randomUnit: 0) == 5_000)
    #expect(TrackerReconnectPolicy.equalJitterMilliseconds(10_000, randomUnit: 1) == 10_000)
    #expect(TrackerReconnectPolicy.equalJitterMilliseconds(20_000, randomUnit: 1) == 10_000)
    #expect(TrackerReconnectPolicy.equalJitterMilliseconds(0, randomUnit: 1) == 0)
}

@Test func jitterChangesTheWaitWithoutChangingTheNextLadderStep() {
    var policy = TrackerReconnectPolicy()
    #expect(policy.takeRetryDelayMilliseconds(
        kind: .connectFailure,
        legacyImmediateAckRetry: false,
        jitterEnabled: true,
        randomUnit: 0
    ) == 1_000)
    #expect(policy.peekDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: false) == 4_000)
    #expect(policy.takeRetryDelayMilliseconds(
        kind: .ackTimeout,
        legacyImmediateAckRetry: false,
        jitterEnabled: true,
        randomUnit: 1
    ) == 4_000)
    #expect(policy.peekDelayMilliseconds(kind: .ackTimeout, legacyImmediateAckRetry: false) == 8_000)
}

@Test func legacyImmediateRetryIsNotJittered() {
    var policy = TrackerReconnectPolicy()
    #expect(policy.takeRetryDelayMilliseconds(
        kind: .ackTimeout,
        legacyImmediateAckRetry: true,
        jitterEnabled: true,
        randomUnit: 0
    ) == 0)
    #expect(policy.peekDelayMilliseconds(kind: .connectFailure, legacyImmediateAckRetry: false) == 2_000)
}

@Test func missingReconnectJitterPreferenceStaysOn() {
    let suite = "tracker-reconnect-jitter-test"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    #expect(TrackerReconnectPolicy.reconnectJitterEnabled(in: defaults))
    defaults.set(false, forKey: TrackerReconnectPolicy.reconnectJitterDefaultsKey)
    #expect(!TrackerReconnectPolicy.reconnectJitterEnabled(in: defaults))
    defaults.set(true, forKey: TrackerReconnectPolicy.reconnectJitterDefaultsKey)
    #expect(TrackerReconnectPolicy.reconnectJitterEnabled(in: defaults))
    defaults.removePersistentDomain(forName: suite)
}

@Test func appleCoordinatorUsesSharedReconnectPolicy() throws {
    let appleRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
    let coordinator = try String(
        contentsOf: appleRoot.appendingPathComponent("App/AppleTrackerCoordinator.swift"),
        encoding: .utf8
    )
    let settings = try String(
        contentsOf: appleRoot.appendingPathComponent("App/CaltopoSettingsView.swift"),
        encoding: .utf8
    )
    #expect(coordinator.contains("reconnectPolicy.takeRetryDelayMilliseconds"))
    #expect(coordinator.contains("reconnectPolicy.onHelloAcknowledged()"))
    #expect(coordinator.contains("reconnectPolicy.onTransportOpened"))
    #expect(coordinator.contains("helloTimeoutMilliseconds:"))
    #expect(coordinator.contains("heartbeatTimeoutMilliseconds: TrackerReconnectPolicy.heartbeatAckTimeoutMilliseconds"))
    #expect(coordinator.contains("scheduleReconnect(kind: .ackTimeout)"))
    #expect(coordinator.contains("scheduleReconnect(kind: .connectFailure)"))
    #expect(coordinator.contains("jitterEnabled: jitterEnabled"))
    #expect(coordinator.contains("TrackerReconnectPolicy.reconnectJitterEnabled"))
    #expect(!coordinator.contains("reconnectDelay"))
    #expect(settings.contains("Immediate ack retry (legacy)"))
    #expect(settings.contains("Reconnect jitter"))
    #expect(settings.contains("TrackerReconnectPolicy.legacyImmediateAckRetryDefaultsKey"))
    #expect(settings.contains("TrackerReconnectPolicy.reconnectJitterDefaultsKey"))
}
