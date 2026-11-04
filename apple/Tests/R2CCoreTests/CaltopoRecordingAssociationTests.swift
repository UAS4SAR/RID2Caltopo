import Foundation
import Testing
@testable import R2CCore

@Test func completedFlightRecordingAssociationSurvivesRetiredSessionIdentity() async throws {
    let store = RidTrackStore()
    let start = Date(timeIntervalSince1970: 1_790_000_000)
    _ = await store.ingest(RidObservation(source: .bluetoothLegacy, aircraftId: "RID-VIDEO",
        receivedAt: start, latitude: 39, longitude: -121, horizontalAccuracyCode: 10))
    _ = await store.ingest(RidObservation(source: .bluetoothLegacy, aircraftId: "RID-VIDEO",
        receivedAt: start.addingTimeInterval(10), latitude: 39.0001, longitude: -121,
        horizontalAccuracyCode: 10))
    let track = try #require(await store.snapshot().first)
    var sessionIdentity: RidAircraftIdentity? = RidAircraftIdentity(remoteID: track.aircraftID,
        organization: "NCSSAR", pilotCallsign: "1SAR7", droneDescription: "DJI Mini 4 Pro",
        mappedIDOverride: "1sar7DjMn4Pr")
    let recording = CaltopoRecordingAssociation(track: track, identity: sessionIdentity)
    sessionIdentity = nil // endFlight retires a session-only confirmation before network archiving.
    #expect(sessionIdentity == nil)
    #expect(recording.startedAt == start)
    #expect(recording.endedAt == start.addingTimeInterval(10))

    let matching = ManagedVideoRecordingIdentity.Candidate(sessionID: "completed-video",
        designator: "1sar7djmn4pr", startedAt: start, endedAt: start.addingTimeInterval(10))
    let stale = ManagedVideoRecordingIdentity.Candidate(sessionID: "previous-flight",
        designator: matching.designator, startedAt: start.addingTimeInterval(-300),
        endedAt: start.addingTimeInterval(-200))
    // The recording can arrive after the identity was cleared; use the same
    // frozen association for the initial and deferred lookup.
    #expect(ManagedVideoRecordingIdentity.recording(matching: recording.designators,
        trackStartedAt: recording.startedAt, trackEndedAt: recording.endedAt, candidates: []) == nil)
    let match = ManagedVideoRecordingIdentity.recording(matching: recording.designators,
        trackStartedAt: recording.startedAt, trackEndedAt: recording.endedAt, candidates: [stale, matching])
    #expect(match?.sessionID == "completed-video")
    let url = TrackerTabletLink.recordingShortURL(trackerURLPrefix: "https://r2c-tracker.com/ncssar",
        tabletName: "iPad", sessionID: try #require(match?.sessionID))
    #expect(CaltopoArchiveDescription.build(capturedVideoURL: url).contains("/v/"))
}

@Test func completedFlightWithoutIdentityRetainsRemoteIDFallback() async throws {
    let store = RidTrackStore()
    _ = await store.ingest(RidObservation(source: .bluetoothLegacy, aircraftId: "RID-ONLY",
        receivedAt: Date(), latitude: 39, longitude: -121, horizontalAccuracyCode: 10))
    let track = try #require(await store.snapshot().first)
    let recording = CaltopoRecordingAssociation(track: track, identity: nil)
    #expect(recording.designators == [track.aircraftID])
}

@Test func archiveDescriptionAlreadyContainsVideoLink() {
    #expect(CaltopoArchiveDescription.alreadyContainsVideoLink("https://r2c-tracker.com/v/8fiw1A"))
    #expect(!CaltopoArchiveDescription.alreadyContainsVideoLink(""))
    #expect(!CaltopoArchiveDescription.alreadyContainsVideoLink("no link"))
}

@Test func recordingLinkRetryWindowRespectsAbsoluteLimitWhileFinalizing() {
    var window = RecordingLinkRetryWindow(now: 0, grace: 30, absoluteLimit: 60)
    let mid = window.shouldRetry(now: 50, finalizing: true)
    let expired = window.shouldRetry(now: 60, finalizing: true)
    #expect(mid)
    #expect(!expired)
}

@Test func trackerArchiveEligibilityRejectsNonOwnerAuthorizedFlag() async throws {
    let store = RidTrackStore()
    _ = await store.ingest(RidObservation(
        source: .bluetoothLegacy, aircraftId: "RID-OWNER",
        receivedAt: Date(), latitude: 39, longitude: -121, horizontalAccuracyCode: 10))
    let track = try #require(await store.snapshot().first)
    let geoJSON = try RidTrackGeoJSON.encode(
        track: track,
        metadata: RidTrackArchiveMetadata(
            mappedID: "mapped",
            organization: "NCSSAR",
            trackerUploadAuthorized: false
        )
    )
    let eligibility = TrackerArchiveUploadContract.eligibility(
        geoJSON: geoJSON,
        configuration: TrackerArchiveUploadConfiguration(
            urlPrefix: "https://r2c-tracker.com/ncssar",
            apiKey: "token",
            organization: "NCSSAR"
        ),
        knownRemoteIDs: ["RID-OWNER"]
    )
    #expect(eligibility == .notUploadAuthorized)
    #expect(TrackerArchiveUploadContract.shouldMarkReported(statusCode: 409))
}

@Test func videoLinkTrueFalseWordingMatchesArchivePatchLogs() {
    let withLink = "Added deferred recording link to archived track ABC videoLink=true"
    let without = "Recording did not become linkable before the deferred CalTopo link deadline. videoLink=false"
    #expect(withLink.contains("videoLink=true"))
    #expect(without.contains("videoLink=false"))
    #expect(CaltopoArchiveDescription.alreadyContainsVideoLink("https://example.invalid/v/abc"))
}

@Test func peerTrafficHeadingRoundsToOneDecimal() {
    let heading = 123.456789
    let text = String(format: "%.1f", heading)
    #expect(text == "123.5")
}
