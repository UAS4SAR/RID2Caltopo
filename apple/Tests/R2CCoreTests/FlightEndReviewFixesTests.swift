import Foundation
import Testing
@testable import R2CCore

@Test func knownDatedFolderDoesNotWaitForAnotherMapRead() {
    let today = CaltopoTrackFolderResolver.archiveFolderName(trackFolderName: "Drone Tracks", date: Date(timeIntervalSince1970: 1_794_272_488))
    #expect(CaltopoTrackFolderResolver.immediateArchiveFolderID(
        flightFolderName: today, todayFolderName: today, knownID: "folder-1"
    ) == "folder-1")
    #expect(CaltopoTrackFolderResolver.immediateArchiveFolderID(
        flightFolderName: "Drone Tracks 08Nov", todayFolderName: today, knownID: "folder-1"
    ) == nil)
    #expect(CaltopoTrackFolderResolver.immediateArchiveFolderID(
        flightFolderName: today, todayFolderName: today, knownID: "  "
    ) == nil)
}

@Test func reviewedFlightUploadsOnceEvenIfTheArchiveWasWrittenUnauthorized() throws {
    let configuration = TrackerArchiveUploadConfiguration(
        urlPrefix: "https://tracker.example.test/", apiKey: "token", organization: "NCSSAR"
    )
    let held = Data(#"{"features":[{"properties":{"r2c_flight_id":"flight-1","r2c_prop":{"org":"NCSSAR","rid":"RID-1","tracker_upload_authorized":false,"tracker_claim_pending":true}}}]}"#.utf8)
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: held, configuration: configuration, knownRemoteIDs: ["RID-1"]
    ) == .claimPending)
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: held, configuration: configuration, knownRemoteIDs: ["RID-1"], releasedFlightIDs: ["flight-1"]
    ) == .eligible)
    let unrelated = Data(#"{"features":[{"properties":{"r2c_flight_id":"other","r2c_prop":{"org":"NCSSAR","rid":"RID-1","tracker_upload_authorized":false}}}]}"#.utf8)
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: unrelated, configuration: configuration, knownRemoteIDs: ["RID-1"], releasedFlightIDs: ["flight-1"]
    ) == .notUploadAuthorized)
}

@Test func markerCleanup400ClearsOnlyWhenTheMarkerIsGone() {
    #expect(MarkerCleanupRetry.alreadyRemoved(httpStatus: 404, listedOnMap: false))
    #expect(MarkerCleanupRetry.alreadyRemoved(httpStatus: 400, listedOnMap: false))
    #expect(!MarkerCleanupRetry.alreadyRemoved(httpStatus: 400, listedOnMap: true))
    #expect(!MarkerCleanupRetry.alreadyRemoved(httpStatus: 500, listedOnMap: false))
}

@Test func ownershipLostFlightStaysHeldUntilAnExplicitOverride() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    let journal = await AwaitingMapFlightJournal.loaded(fileURL: root.appendingPathComponent("pending.json"))
    let points = [RidObservation(source: .wifiBeacon, aircraftId: "RID-1", receivedAt: Date(timeIntervalSince1970: 1),
        latitude: 39, longitude: -121, altitudeMeters: 10)]
    try await journal.record(remoteID: "RID-1", label: "Held", observations: points, mapID: "map-a", teamID: "team-a",
        finished: false, flightID: "lost", ownershipReviewPending: true, recordedPilot: "1SAR7",
        claim: FlightOwnershipContext.expired, ownershipContextKnown: true)
    try await journal.record(remoteID: "RID-1", label: "Held", observations: points, mapID: "map-a", teamID: "team-a",
        finished: true, flightID: "lost", ownershipReviewPending: true, recordedPilot: "1SAR7",
        claim: FlightOwnershipContext.expired, ownershipContextKnown: true)
    #expect(try await journal.authorizeOwnershipReview(id: "lost") == false)
    #expect((await journal.entries).first?.ownershipReviewPending == true)
    do {
        try await journal.decide(id: "lost", mapID: "map-a", teamID: "team-a")
        Issue.record("A plain review published a flight another tablet owned")
    } catch AwaitingMapFlightJournalError.ownershipReviewRequired {
    } catch {
        Issue.record("Unexpected error \(error)")
    }
    #expect((await journal.entries).first?.decision == "review")
    #expect(try await journal.authorizeOwnershipReview(id: "lost", peerOverride: true))
    let released = try #require((await journal.entries).first)
    #expect(released.ownershipReviewPending == false)
    #expect(released.recordedPilot == "1SAR7")
    try await journal.decide(id: "lost", mapID: "map-a", teamID: "team-a")
    #expect((await journal.entries).first?.decision == "publish")
}
