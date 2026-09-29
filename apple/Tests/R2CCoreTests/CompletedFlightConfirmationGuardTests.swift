import XCTest
@testable import R2CCore

final class CompletedFlightConfirmationGuardTests: XCTestCase {
    func testCompletedFlightNeedsFreshEvidenceAndLogsOnlyTransitions() {
        var guardPolicy = CompletedFlightConfirmationGuard()
        var logs: [String] = []
        guardPolicy.updateSessions(["RID": ["stream|old"]])
        XCTAssertTrue(guardPolicy.allows(remoteID: "RID"))
        guardPolicy.end(remoteID: "RID", at: Date(timeIntervalSince1970: 100)) { logs.append($0) }
        for _ in 0..<10 {
            XCTAssertFalse(guardPolicy.allows(remoteID: "RID", receivedAt: Date(timeIntervalSince1970: 100)) { logs.append($0) })
        }
        XCTAssertEqual(logs.count, 2)
        XCTAssertTrue(guardPolicy.allows(remoteID: "RID", receivedAt: Date(timeIntervalSince1970: 101)) { logs.append($0) })
        XCTAssertTrue(guardPolicy.allows(remoteID: "RID", receivedAt: Date(timeIntervalSince1970: 101)) { logs.append($0) })
        XCTAssertEqual(logs.count, 3)
        XCTAssertTrue(logs.last!.contains("fresh_aircraft"))
    }

    func testRepeatedEndAndTemporaryDisappearanceCannotReviveOldPublisher() {
        var guardPolicy = CompletedFlightConfirmationGuard()
        guardPolicy.updateSessions(["RID": ["stream|old"]])
        // The registry can publish removal before the track-end callback.
        guardPolicy.updateSessions([:])
        guardPolicy.end(remoteID: "RID", at: Date(timeIntervalSince1970: 100))
        guardPolicy.updateSessions([:])
        guardPolicy.end(remoteID: "RID", at: Date(timeIntervalSince1970: 110))
        guardPolicy.updateSessions(["RID": ["stream|old"]])
        XCTAssertFalse(guardPolicy.allows(remoteID: "RID"))
        guardPolicy.updateSessions(["RID": ["stream|new"]])
        XCTAssertTrue(guardPolicy.allows(remoteID: "RID"))
    }

    func testLateIdentityResolutionDoesNotProveANewPublisher() {
        var guardPolicy = CompletedFlightConfirmationGuard()
        guardPolicy.updateSessions(["RID": ["stream|unknown"]])
        guardPolicy.end(remoteID: "RID", at: Date(timeIntervalSince1970: 100))
        guardPolicy.updateSessions(["RID": ["stream|now-known"]])
        XCTAssertFalse(guardPolicy.allows(remoteID: "RID"))
        XCTAssertTrue(guardPolicy.allows(remoteID: "RID", receivedAt: Date(timeIntervalSince1970: 101)))
    }

    func testUnknownPublisherAndOtherAircraftCannotRearmRetiredFlight() {
        var guardPolicy = CompletedFlightConfirmationGuard()
        guardPolicy.end(remoteID: "RID", at: Date(timeIntervalSince1970: 100))
        guardPolicy.updateSessions(["RID": ["stream|unknown"], "OTHER": ["stream2|new"]])
        XCTAssertFalse(guardPolicy.allows(remoteID: "RID", receivedAt: Date(timeIntervalSince1970: 99)))
        XCTAssertTrue(guardPolicy.allows(remoteID: "OTHER"))
        XCTAssertFalse(guardPolicy.allows(remoteID: "RID"))
    }
}
