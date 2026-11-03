import Foundation
import Testing
@testable import R2CCore

/// Don't Publish drones must not reopen confirmation from a new local video session;
/// only Aircraft and Reception selection reopens the panel.
@Test func declinedDroneDoesNotRepromptOnNewLocalVideo() {
    var lifecycle = CurrentFlightConfirmationLifecycle()
    let ignored: Set<String> = ["drone"]

    #expect(lifecycle.reconcile(
        orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID == "drone")
    #expect(lifecycle.reconcile(
        orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored).candidateRemoteID == nil)
    // New video / allowPrompt alone must not bypass Don't Publish for the session.
    #expect(lifecycle.reconcile(
        orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored,
        allowPrompt: true).candidateRemoteID == nil)
    #expect(lifecycle.reconcile(
        orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored,
        allowPrompt: false).candidateRemoteID == nil)

    // Clearing ignore (Aircraft and Reception → Confirm path) allows a new prompt after flight end.
    #expect(lifecycle.reconcile(
        orderedRemoteIDs: [], confirmedRemoteIDs: [], ignoredRemoteIDs: ignored).candidateRemoteID == nil)
    lifecycle.endFlight(remoteID: "drone")
    #expect(lifecycle.reconcile(
        orderedRemoteIDs: ["drone"], confirmedRemoteIDs: [], ignoredRemoteIDs: []).candidateRemoteID == "drone")
}

@Test func declinedLocalArchiveCarriesVideoDescriptionAndBlocksTracker() async throws {
    let store = RidTrackStore()
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    _ = await store.ingest(RidObservation(
        source: .bluetoothLegacy, aircraftId: "RIDLOCAL1", receivedAt: start,
        latitude: 39.1, longitude: -121.2, altitudeMeters: 100, horizontalAccuracyCode: 11
    ))
    _ = await store.ingest(RidObservation(
        source: .bluetoothLegacy, aircraftId: "RIDLOCAL1",
        receivedAt: start.addingTimeInterval(30),
        latitude: 39.11, longitude: -121.21, altitudeMeters: 110, horizontalAccuracyCode: 11
    ))
    let track = try #require(await store.snapshot().first)
    let videoURL = try #require(URL(string: "https://r2c-tracker.com/s/LocalOnly"))
    let metadata = RidTrackArchiveMetadata(
        mappedID: "mini4pro",
        organization: "NCSSAR",
        localArchiveOnly: true,
        flightID: "flight-local-1",
        capturedVideoDescription: CaltopoArchiveDescription.build(capturedVideoURL: videoURL)
    )
    let data = try RidTrackGeoJSON.encode(track: track, metadata: metadata)
    let root = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    let feature = try #require((root["features"] as? [[String: Any]])?.first)
    let properties = try #require(feature["properties"] as? [String: Any])
    let r2c = try #require(properties["r2c_prop"] as? [String: Any])
    #expect(r2c["local_archive_only"] as? Bool == true)
    #expect(properties["description"] as? String == "https://r2c-tracker.com/s/LocalOnly")
    #expect(TrackerArchiveUploadContract.eligibility(
        geoJSON: data,
        configuration: TrackerArchiveUploadConfiguration(
            urlPrefix: "https://r2c-tracker.com/ncssar",
            apiKey: "token",
            organization: "NCSSAR"
        ),
        knownRemoteIDs: ["RIDLOCAL1"]
    ) == .localArchiveOnly)
}
