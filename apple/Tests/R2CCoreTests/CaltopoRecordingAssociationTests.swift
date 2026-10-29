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
