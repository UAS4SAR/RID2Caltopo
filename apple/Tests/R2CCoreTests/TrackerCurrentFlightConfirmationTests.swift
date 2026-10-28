import Foundation
import Testing
@testable import R2CCore

@Test func trackerCurrentFlightApprovalCanBeRestoredAfterConnectingMap() throws {
    var standalone = TrackerCoordinationProtocolState(localZoneID: "local")
    _ = standalone.confirmLocally(remoteID: "operator-approved")
    #expect(standalone.isConfirmedLocally(remoteID: "operator-approved"))
    // Opening a map creates a fresh transport session; only the explicit current approval is replayed.
    var connected = TrackerCoordinationProtocolState(localZoneID: "local")
    #expect(!connected.isConfirmedLocally(remoteID: "operator-approved"))
    _ = connected.confirmLocally(remoteID: "operator-approved")
    #expect(!connected.isConfirmedLocally(remoteID: "historical-or-peer-only"))
    let owner = Data(#"{"type":"owner_assigned","remoteId":"operator-approved","ownerGuid":"local","leaseSeq":1}"#.utf8)
    _ = try connected.handleIncoming(owner, receivedAtMilliseconds: 1000)
    #expect(connected.isLocalAlertEligible(remoteID: "operator-approved"))
    let peerOwner = Data(#"{"type":"owner_assigned","remoteId":"operator-approved","ownerGuid":"peer","leaseSeq":2}"#.utf8)
    _ = try connected.handleIncoming(peerOwner, receivedAtMilliseconds: 2000)
    #expect(!connected.isLocalAlertEligible(remoteID: "operator-approved"))
}
