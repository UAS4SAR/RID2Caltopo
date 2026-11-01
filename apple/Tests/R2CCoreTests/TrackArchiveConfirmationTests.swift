import Foundation
import Testing
@testable import R2CCore

@Suite("TrackArchiveConfirmation")
struct TrackArchiveConfirmationTests {
    @Test func confirmedWhenPublishedOrFlagOrArchiveId() {
        #expect(TrackArchiveConfirmation.isConfirmed(decision: "published", archiveConfirmed: false))
        #expect(TrackArchiveConfirmation.isConfirmed(decision: "review", archiveConfirmed: true))
        #expect(TrackArchiveConfirmation.isConfirmed(
            decision: "queued", archiveConfirmed: false, hasArchiveId: true))
        #expect(!TrackArchiveConfirmation.isConfirmed(decision: "review", archiveConfirmed: false))
        #expect(!TrackArchiveConfirmation.isConfirmed(decision: "queued", archiveConfirmed: false))
    }

    @Test func mayAutoArchiveOnlyWhenNeverConfirmed() {
        #expect(TrackArchiveConfirmation.mayAutoArchive(confirmed: false, remotelyDeleted: false))
        #expect(!TrackArchiveConfirmation.mayAutoArchive(confirmed: true, remotelyDeleted: false))
        #expect(!TrackArchiveConfirmation.mayAutoArchive(confirmed: false, remotelyDeleted: true))
    }

    @Test func awaitingInboxExcludesConfirmedAndNonReview() {
        #expect(TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
            decision: "review", finished: true, archiveConfirmed: false))
        #expect(!TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
            decision: "review", finished: true, archiveConfirmed: true))
        #expect(!TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
            decision: "queued", finished: true, archiveConfirmed: false))
        #expect(!TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
            decision: "bound", finished: false, archiveConfirmed: false))
        #expect(!TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
            decision: "published", finished: true, archiveConfirmed: false))
    }
}

@Test @MainActor func notePublicationBindsEmptyMapReviewBeforeArchive() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let journal = await AwaitingMapFlightJournal.loaded(fileURL: root.appendingPathComponent("pending.json"))
    let sample = RidObservation(
        source: .bluetoothLegacy, aircraftId: "RID-1",
        receivedAt: Date(timeIntervalSince1970: 1_000_000),
        latitude: 39, longitude: -121, altitudeMeters: 100)
    try await journal.record(
        remoteID: "RID-1", label: "Flight", observations: [sample],
        mapID: "", teamID: "personal:acct", finished: false)
    #expect((await journal.entries)[0].decision == "review")
    #expect((await journal.entries)[0].mapID.isEmpty)

    try await journal.notePublication(remoteID: "RID-1", mapID: "map-a")
    #expect((await journal.entries)[0].decision == "bound")
    #expect((await journal.entries)[0].mapID == "map-a")
    #expect((await journal.entries)[0].publicationStarted == true)

    try await journal.record(
        remoteID: "RID-1", label: "Flight", observations: [sample],
        mapID: "map-a", teamID: "personal:acct", finished: true)
    // Live-published bound flight is removed from the awaiting journal at finish.
    #expect((await journal.entries).isEmpty)
}

@Test @MainActor func finishedBoundWithoutPublicationPromotesToPublishNotReview() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let journal = await AwaitingMapFlightJournal.loaded(fileURL: root.appendingPathComponent("pending.json"))
    let sample = RidObservation(
        source: .bluetoothLegacy, aircraftId: "RID-2",
        receivedAt: Date(timeIntervalSince1970: 2_000_000),
        latitude: 39, longitude: -121, altitudeMeters: 100)
    try await journal.record(
        remoteID: "RID-2", label: "Flight", observations: [sample],
        mapID: "map-a", teamID: "team-a", finished: false)
    try await journal.record(
        remoteID: "RID-2", label: "Flight", observations: [sample],
        mapID: "map-a", teamID: "team-a", finished: true)
    #expect((await journal.entries).count == 1)
    #expect((await journal.entries)[0].decision == "publish")
    #expect((await journal.entries)[0].mapID == "map-a")
}

@Test @MainActor func confirmArchivePrunesAndBlocksInbox() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let file = root.appendingPathComponent("pending.json")
    let journal = await AwaitingMapFlightJournal.loaded(fileURL: file)
    let sample = RidObservation(
        source: .bluetoothLegacy, aircraftId: "RID-3",
        receivedAt: Date(timeIntervalSince1970: 3_000_000),
        latitude: 39, longitude: -121, altitudeMeters: 100)
    try await journal.record(
        remoteID: "RID-3", label: "Flight", observations: [sample],
        mapID: "", teamID: "personal:acct", finished: false)
    try await journal.record(
        remoteID: "RID-3", label: "Flight", observations: [sample],
        mapID: "", teamID: "personal:acct", finished: true)
    #expect((await journal.entries)[0].decision == "review")

    let confirmed = try await journal.confirmArchive(
        remoteID: "RID-3",
        flightID: (await journal.entries)[0].id,
        archiveId: "shape-guid-1",
        folderTitle: "Drone Tracks 01Nov"
    )
    #expect(confirmed)
    #expect((await journal.entries).isEmpty)

    // Reopen: confirmed archives stay pruned; never reappear as awaiting publication.
    let reopened = await AwaitingMapFlightJournal.loaded(fileURL: file)
    #expect((await reopened.entries).isEmpty)
}

@Test @MainActor func pruneConfirmedLeavesUnpublishedReviewAlone() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let journal = await AwaitingMapFlightJournal.loaded(fileURL: root.appendingPathComponent("pending.json"))
    let sample = RidObservation(
        source: .bluetoothLegacy, aircraftId: "RID-4",
        receivedAt: Date(timeIntervalSince1970: 4_000_000),
        latitude: 39, longitude: -121, altitudeMeters: 100)
    try await journal.record(
        remoteID: "RID-4", label: "Never published", observations: [sample],
        mapID: "", teamID: "old-team", finished: false)
    try await journal.record(
        remoteID: "RID-4", label: "Never published", observations: [sample],
        mapID: "", teamID: "old-team", finished: true)
    try await journal.pruneConfirmedArchives()
    #expect((await journal.entries).count == 1)
    #expect((await journal.entries)[0].decision == "review")
}
