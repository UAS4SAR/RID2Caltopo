import Foundation
import Testing
@testable import R2CCore

@Suite struct FlightOwnershipContextTests {
    @Test func minePendingIsHeldAndKeepsTheRecordedPilot() {
        let snapshot = FlightOwnershipContext.capture(
            confirmed: true, ignored: false, localOnly: false, claim: FlightOwnershipContext.pending,
            pilot: "1SAR7", organization: "NCSSAR", model: "Mini", mappedID: "mid")
        #expect(snapshot.requiresHold)
        #expect(!snapshot.mayAutoPublish)
        #expect(snapshot.recordedPilot == "1SAR7")
    }

    @Test func rejectedConflictAndExpiredDoNotAutoPublish() {
        for claim in [FlightOwnershipContext.rejected, FlightOwnershipContext.conflict, FlightOwnershipContext.expired] {
            let snapshot = FlightOwnershipContext.capture(
                confirmed: true, ignored: false, localOnly: false, claim: claim,
                pilot: "1SAR7", organization: "NCSSAR", model: "Mini", mappedID: "mid")
            #expect(snapshot.requiresHold)
            #expect(!snapshot.mayAutoPublish)
            #expect(snapshot.recordedPilot == "1SAR7")
        }
    }

    @Test func unconfirmedDoesNotInventAPilotAndLegacyStaysUnknown() {
        let open = FlightOwnershipContext.capture(
            confirmed: false, ignored: false, localOnly: false, claim: FlightOwnershipContext.none,
            pilot: "1SAR7", organization: "NCSSAR", model: "Mini", mappedID: "mid")
        #expect(open.blocksOperatorPublish)
        #expect(open.recordedPilot.isEmpty)
        #expect(FlightOwnershipContext.legacy.requiresHold)
        #expect(FlightOwnershipContext.legacy.recordedPilot.isEmpty)
        let offline = FlightOwnershipContext.capture(
            confirmed: true, ignored: false, localOnly: false, claim: FlightOwnershipContext.none,
            pilot: "1SAR7", organization: "NCSSAR", model: "Mini", mappedID: "mid")
        #expect(offline.mayAutoPublish)
    }

    @Test func journalContextRoundTripsAndLegacyRecoveryDoesNotInventAPilot() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ownership-journal-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = WaypointTrackAppendJournal(directory: directory)
        try await journal.ensureOpen(flightID: "legacy", remoteID: "RID-L", label: "Legacy", mapID: "map-a", teamID: "team", startedAtMs: 1_000)
        try await journal.append(observation: RidObservation(
            source: .wifiBeacon, aircraftId: "RID-L", receivedAt: Date(timeIntervalSince1970: 1),
            latitude: 39, longitude: -121, altitudeMeters: 10, droneTimestamp: Date(timeIntervalSince1970: 1)), flightID: "legacy")
        let legacy = try #require(try await journal.recoverUnfinished().first)
        #expect(!legacy.ownership.known)
        #expect(legacy.ownership.recordedPilot.isEmpty)
        #expect(legacy.ownership.requiresHold)

        try await journal.ensureOpen(flightID: "pending", remoteID: "RID-P", label: "Pending", mapID: "map-a", teamID: "team", startedAtMs: 2_000)
        try await journal.noteContext(FlightOwnershipContext.capture(
            confirmed: true, ignored: false, localOnly: false, claim: FlightOwnershipContext.pending,
            pilot: "1SAR7", organization: "NCSSAR", model: "Mini", mappedID: "mid"), flightID: "pending")
        try await journal.append(observation: RidObservation(
            source: .wifiBeacon, aircraftId: "RID-P", receivedAt: Date(timeIntervalSince1970: 2),
            latitude: 39, longitude: -121, altitudeMeters: 10, droneTimestamp: Date(timeIntervalSince1970: 2)), flightID: "pending")
        let pending = try #require(try await journal.recoverUnfinished().first { $0.id == "pending" })
        #expect(pending.ownership.recordedPilot == "1SAR7")
        #expect(pending.ownership.requiresHold)
    }

    @Test func authorizeReleasesOnlyTheHeldFlightAndKeepsAnEmptyPilotEmpty() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let journal = await AwaitingMapFlightJournal.loaded(fileURL: root.appendingPathComponent("pending.json"))
        let points = [RidObservation(source: .wifiBeacon, aircraftId: "RID-1", receivedAt: Date(timeIntervalSince1970: 1),
            latitude: 39, longitude: -121, altitudeMeters: 10)]
        try await journal.record(remoteID: "RID-1", label: "Held", observations: points, mapID: "map-a", teamID: "team-a",
            finished: false, flightID: "held", ownershipReviewPending: true, recordedPilot: "", claim: FlightOwnershipContext.pending, ownershipContextKnown: true)
        try await journal.record(remoteID: "RID-1", label: "Held", observations: points, mapID: "map-a", teamID: "team-a",
            finished: true, flightID: "held", ownershipReviewPending: true, recordedPilot: "", claim: FlightOwnershipContext.pending, ownershipContextKnown: true)
        try await journal.record(remoteID: "RID-2", label: "Other", observations: points, mapID: "map-b", teamID: "team-b",
            finished: false, flightID: "other", ownershipReviewPending: false)
        try await journal.record(remoteID: "RID-2", label: "Other", observations: points, mapID: "map-b", teamID: "team-b",
            finished: true, flightID: "other", ownershipReviewPending: false)
        #expect((await journal.entries).first { $0.id == "other" }?.decision == "publish")
        #expect(try await journal.authorizeOwnershipReview(id: "other") == false)
        #expect(try await journal.authorizeOwnershipReview(id: "held") == false)
        try await journal.decide(id: "held", mapID: "map-a", teamID: "team-a")
        let released = try #require((await journal.entries).first { $0.id == "held" })
        #expect(released.decision == "publish")
        #expect(released.recordedPilot == nil)
        #expect((await journal.entries).first { $0.id == "other" }?.decision == "publish")
    }

    @Test func peerConfirmationIsNotThisTabletsMine() {
        #expect(!FlightOwnershipContext.countsAsLocalMine(sessionConfirmed: false, peerConfirmed: true))
        #expect(FlightOwnershipContext.countsAsLocalMine(sessionConfirmed: true, peerConfirmed: true))
        let peer = FlightOwnershipContext.capture(
            confirmed: false, ignored: false, localOnly: false, claim: FlightOwnershipContext.expired,
            pilot: "1SAR7", organization: "NCSSAR", model: "Mini", mappedID: "mid")
        #expect(!peer.requiresHold)
        #expect(peer.recordedPilot.isEmpty)
    }

    @Test func finishedFlightClaimDoesNotApplyToTheNextFlight() {
        #expect(FlightOwnershipContext.claimApplies(storedFlightStartedAtMs: 1_000, flightStartedAtMs: 1_500))
        #expect(!FlightOwnershipContext.claimApplies(storedFlightStartedAtMs: 1_000, flightStartedAtMs: 20_000))
        #expect(!FlightOwnershipContext.claimApplies(storedFlightStartedAtMs: 0, flightStartedAtMs: 20_000))
    }

    @Test func archivedShapeIsNotReusedOrRewritten() {
        #expect(!ArchivedShapePolicy.mayAdoptLiveTrack(trackID: "shape-1", archivedShapeIDs: ["shape-1"]))
        #expect(ArchivedShapePolicy.mayAdoptLiveTrack(trackID: "live-2", archivedShapeIDs: ["shape-1"]))
        #expect(!ArchivedShapePolicy.mayModifyShape(shapeID: "shape-1", createdByThisFlight: false))
        #expect(ArchivedShapePolicy.mayModifyShape(shapeID: "shape-2", createdByThisFlight: true))
    }
}
