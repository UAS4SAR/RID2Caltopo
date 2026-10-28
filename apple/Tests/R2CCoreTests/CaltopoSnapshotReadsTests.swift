import Foundation
import Testing
@testable import R2CCore

private actor SnapshotFixture {
    var cursors: [Int64] = []
    var allowed = true
    func deny() { allowed = false }
    func validate() throws { if !allowed { throw URLError(.userAuthenticationRequired) } }
    func load(_ cursor: Int64) async throws -> Data {
        cursors.append(cursor)
        try await Task.sleep(for: .milliseconds(30))
        if cursor == 0 {
            return Data(#"{"status":"ok","result":{"timestamp":10000,"state":{"features":[{"id":"a","properties":{"class":"Marker","title":"before"},"geometry":{"type":"Point","coordinates":[0,0]}}]}}}"#.utf8)
        }
        return Data(#"{"status":"ok","result":{"timestamp":20000,"state":{"features":[{"id":"a","deleted":true},{"id":"b","properties":{"class":"Folder","title":"new folder"}}]}}}"#.utf8)
    }
}
private func snapshotConfig(_ map: String = "ABC123", token: UUID? = nil) -> CaltopoLiveConfiguration {
    .init(mapID: map, credentialID: "test", credentialSecretBase64: "dGVzdA==", personalSessionID: token)
}

@Test func startupReadersShareOneSnapshotAndRefreshWithDelta() async throws {
    let cache = CaltopoSnapshotReads(), fixture = SnapshotFixture(), config = snapshotConfig()
    async let one = cache.read(configuration: config, forceFull: false, refresh: false,
        validate: { try await fixture.validate() }, load: { try await fixture.load($0) })
    async let two = cache.read(configuration: config, forceFull: false, refresh: false,
        validate: { try await fixture.validate() }, load: { try await fixture.load($0) })
    let initial = try await one
    #expect(try CaltopoArtifactDecoder.decode(data: await two) == CaltopoArtifactDecoder.decode(data: initial))
    #expect(await fixture.cursors == [0])
    #expect(try CaltopoArtifactDecoder.decodeChanges(data: initial).serverTimestampMilliseconds == 10_000)
    let refreshed = try await cache.read(configuration: config, forceFull: false, refresh: true,
        validate: { try await fixture.validate() }, load: { try await fixture.load($0) })
    let snapshot = try CaltopoArtifactDecoder.decode(data: refreshed)
    #expect(snapshot.folders.map(\.id) == ["b"])
    #expect(snapshot.points.isEmpty)
    #expect(await fixture.cursors == [0, 10_000])
    _ = try await cache.read(configuration: config, forceFull: true, refresh: false,
        validate: { try await fixture.validate() }, load: { try await fixture.load($0) })
    #expect(await fixture.cursors == [0, 10_000, 0])
}

@Test func snapshotReuseIsBoundToAccountMapAndCurrentAuthorization() async throws {
    let cache = CaltopoSnapshotReads(), fixture = SnapshotFixture()
    for config in [snapshotConfig(), snapshotConfig("DEF456"), snapshotConfig(token: UUID())] {
        _ = try await cache.read(configuration: config, forceFull: false, refresh: false,
            validate: { try await fixture.validate() }, load: { try await fixture.load($0) })
    }
    #expect(await fixture.cursors == [0, 0, 0])
    await fixture.deny()
    do {
        _ = try await cache.read(configuration: snapshotConfig(), forceFull: false, refresh: false,
            validate: { try await fixture.validate() }, load: { try await fixture.load($0) })
        Issue.record("Revoked authorization returned a cached snapshot")
    } catch { }
    #expect(await fixture.cursors.count == 3)
}

@Test func cancelledSnapshotWaiterDoesNotDispatchAnotherRead() async throws {
    let cache = CaltopoSnapshotReads(), fixture = SnapshotFixture(), config = snapshotConfig()
    let one = Task { try await cache.read(configuration: config, forceFull: false, refresh: false,
        validate: { try await fixture.validate() }, load: { try await fixture.load($0) }) }
    while await fixture.cursors.isEmpty { await Task.yield() }
    let cancelled = Task { try await cache.read(configuration: config, forceFull: true, refresh: false,
        validate: { try await fixture.validate() }, load: { try await fixture.load($0) }) }
    cancelled.cancel()
    do { _ = try await cancelled.value; Issue.record("Cancelled waiter succeeded") } catch is CancellationError { }
    _ = try await one.value
    #expect(await fixture.cursors == [0])
}

@Test func deltaDeletionNeedsExplicitAbsenceEvidence() throws {
    let cases: [(String, Bool)] = [
        (#"{"features":[]}"#, false),
        (#"{"features":[{"id":"m","deleted":true}]}"#, true),
        (#"{"features":[{"id":"m","geometry":{}}]}"#, false),
        (#"{"features":[{"id":"m","deleted":true},{"id":"m","geometry":{}}]}"#, false),
        (#"{"features":[],"ids":{"Marker":[]}}"#, true),
        (#"{"features":[],"ids":{"Marker":["m"]}}"#, false),
        (#"{"features":[],"ids":{"Folder":[]}}"#, false),
        (#"{"features":[],"ids":{"Marker":[10]}}"#, false),
    ]
    for (state, expected) in cases {
        let data = Data(("{\"status\":\"ok\",\"result\":{\"state\":" + state + "}}").utf8)
        #expect(CaltopoMarkerVerification.confirmsAbsent(data: data, markerID: "m") == expected)
    }
}
