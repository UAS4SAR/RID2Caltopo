import Foundation
import Testing
@testable import R2CCore

private struct ConfirmationSession {
    var state: TrackerCoordinationProtocolState
    var pending: Set<String>
    var outcomes: [String: String]
    let localZoneID: String

    init(localZoneID: String = "ipad") {
        self.state = TrackerCoordinationProtocolState(localZoneID: localZoneID)
        self.pending = []
        self.outcomes = [:]
        self.localZoneID = localZoneID
    }

    mutating func confirmAndSend(_ remoteID: String) {
        pending.insert(remoteID)
    }

    mutating func receiveDroneConfirmed(remoteID: String, confirmedBy: String, lease: Int) throws {
        let json = Data("""
        {"type":"drone_confirmed","remoteId":"\(remoteID)","confirmedByGuid":"\(confirmedBy)","mappedId":"1sar7","leaseSeq":\(lease)}
        """.utf8)
        let events = try state.handleIncoming(json, receivedAtMilliseconds: 1_000)
        let verifiedLocally = events.contains { event in
            if case let .droneConfirmed(_, zone, confirmedLocally) = event {
                return confirmedLocally && zone == localZoneID
            }
            return false
        }
        if verifiedLocally {
            pending.remove(remoteID)
            outcomes[remoteID] = nil
        }
    }

    mutating func receiveOwnerExpired(_ remoteID: String) throws {
        let json = Data("""
        {"type":"owner_expired","remoteId":"\(remoteID)","prevOwnerGuid":"\(localZoneID)"}
        """.utf8)
        _ = try state.handleIncoming(json, receivedAtMilliseconds: 2_000)
        outcomes[remoteID] = FlightOwnershipContext.expired
    }

    /// The next RID fix. Reopens a pending claim only when the policy says to resend.
    mutating func nextRidFix(_ remoteID: String) -> RememberedConfirmationReplay {
        let action = RememberedConfirmationReplay.decide(
            alertEligible: state.isLocalAlertEligible(remoteID: remoteID),
            ownerZoneID: state.ownerZoneID(remoteID: remoteID),
            localZoneID: localZoneID,
            claimOutcome: outcomes[remoteID] ?? ""
        )
        if action == .resend { pending.insert(remoteID) }
        return action
    }

    func claimPending(_ remoteID: String) -> Bool { pending.contains(remoteID) }

    func flightEnd(_ remoteID: String) -> FlightEndPublication {
        FlightEndPublication.decide(
            allowsPublication: true,
            coordinatorAllowsPublication: state.isLocalAlertEligible(remoteID: remoteID),
            claimPending: claimPending(remoteID),
            blockedByLedger: false
        )
    }
}

@Test func verifiedMineIsNotReplayedAndFlightEndArchives() throws {
    var session = ConfirmationSession()
    session.confirmAndSend("RID")
    try session.receiveDroneConfirmed(remoteID: "RID", confirmedBy: "ipad", lease: 1)
    #expect(session.state.isLocalAlertEligible(remoteID: "RID"))
    #expect(session.nextRidFix("RID") == .alreadyVerified)
    #expect(!session.claimPending("RID"))
    #expect(session.flightEnd("RID") == .archiveShapeAndUpload)
    let ownership = FlightOwnershipContext.capture(
        confirmed: true, ignored: false, localOnly: false,
        claim: FlightOwnershipContext.verified,
        pilot: "1SAR7", organization: "NCSSAR", model: "DJI Mini 4 Pro", mappedID: "1sar7"
    )
    #expect(!ownership.requiresHold)
    #expect(ownership.mayAutoPublish)
    let configuration = TrackerArchiveUploadConfiguration(
        urlPrefix: "https://tracker.example.test/", apiKey: "token", organization: "NCSSAR"
    )
    let archived = Data(#"{"features":[{"properties":{"r2c_flight_id":"flight-1","r2c_prop":{"org":"NCSSAR","rid":"RID","tracker_upload_authorized":true,"tracker_claim_pending":false}}}]}"#.utf8)
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: archived, configuration: configuration, knownRemoteIDs: ["RID"]
    ) == .eligible)
}

@Test func unverifiedMineAtFlightEndStillHolds() throws {
    var session = ConfirmationSession()
    session.confirmAndSend("RID")
    #expect(session.nextRidFix("RID") == .resend)
    #expect(session.claimPending("RID"))
    #expect(session.flightEnd("RID") == .hold)
    let ownership = FlightOwnershipContext.capture(
        confirmed: true, ignored: false, localOnly: false,
        claim: FlightOwnershipContext.pending,
        pilot: "1SAR7", organization: "NCSSAR", model: "DJI", mappedID: "1sar7"
    )
    #expect(ownership.requiresHold)
    let configuration = TrackerArchiveUploadConfiguration(
        urlPrefix: "https://tracker.example.test/", apiKey: "token", organization: "NCSSAR"
    )
    let held = Data(#"{"features":[{"properties":{"r2c_flight_id":"flight-1","r2c_prop":{"org":"NCSSAR","rid":"RID","tracker_upload_authorized":true,"tracker_claim_pending":true}}}]}"#.utf8)
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: held, configuration: configuration, knownRemoteIDs: ["RID"]
    ) == .claimPending)
}

@Test func ownershipMovedToAPeerStillHolds() throws {
    var session = ConfirmationSession()
    session.confirmAndSend("RID")
    try session.receiveDroneConfirmed(remoteID: "RID", confirmedBy: "ipad", lease: 1)
    try session.receiveDroneConfirmed(remoteID: "RID", confirmedBy: "a5pro", lease: 2)
    #expect(!session.state.isLocalOwner(remoteID: "RID"))
    #expect(session.nextRidFix("RID") == .ownershipLost)
    #expect(!session.claimPending("RID"))
    #expect(session.flightEnd("RID") == .hold)
}

@Test func restartBeforeAcknowledgementReplaysThenClears() throws {
    var session = ConfirmationSession()
    session.confirmAndSend("RID")
    session.state = TrackerCoordinationProtocolState(localZoneID: "ipad")
    session.pending.removeAll()
    #expect(session.nextRidFix("RID") == .resend)
    #expect(session.claimPending("RID"))
    try session.receiveDroneConfirmed(remoteID: "RID", confirmedBy: "ipad", lease: 1)
    #expect(!session.claimPending("RID"))
    #expect(session.nextRidFix("RID") == .alreadyVerified)
    #expect(session.flightEnd("RID") == .archiveShapeAndUpload)
}

@Test func priorOwnerStillArchivesAfterAnotherTabletTakesTheAircraft() {
    let published = FlightEndPublication.decide(
        allowsPublication: true,
        coordinatorAllowsPublication: false,
        claimPending: false,
        blockedByLedger: false,
        ownedDuringFlight: true
    )
    #expect(published == .archiveShapeAndUpload)
    #expect(!published.discardsLiveTrack)

    let neverOwned = FlightEndPublication.decide(
        allowsPublication: true,
        coordinatorAllowsPublication: false,
        claimPending: false,
        blockedByLedger: false,
        ownedDuringFlight: false
    )
    #expect(neverOwned == .hold)
    #expect(neverOwned.discardsLiveTrack)

    let unverified = FlightEndPublication.decide(
        allowsPublication: true,
        coordinatorAllowsPublication: false,
        claimPending: true,
        blockedByLedger: false,
        ownedDuringFlight: false
    )
    #expect(unverified == .hold)
}

@Test func expiredLeaseIsNotReplayed() throws {
    var session = ConfirmationSession()
    session.confirmAndSend("RID")
    try session.receiveDroneConfirmed(remoteID: "RID", confirmedBy: "ipad", lease: 1)
    try session.receiveOwnerExpired("RID")
    #expect(session.nextRidFix("RID") == .ownershipLost)
    #expect(session.flightEnd("RID") == .hold)
}
