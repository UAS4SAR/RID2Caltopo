import XCTest
@testable import R2CCore
import Foundation

final class CaltopoPersonalIntegrationTests: XCTestCase {
    private func client() throws -> CaltopoLiveClient {
        try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "", credentialSecretBase64: "invalid!", personalSessionID: UUID()))
    }
    func testPersonalReadsAndDeletesNeverCarryTeamSignature() async throws {
        let client = try client()
        let read = try await client.makeMapSnapshotRequest(now: Date())
        let delete = try await client.makeDeleteMarkerRequest(markerID: UUID().uuidString, now: Date())
        for request in [read, delete] {
            XCTAssertNil(request.url?.query)
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie")) // Supplied just before sending.
            XCTAssertTrue(request.url!.path.hasPrefix("/api/v1/map/ABC123/"))
        }
    }
    func testPersonalTrackCreationUsesOnlyJsonWithoutTeamCredentials() async throws {
        let client = try client()
        let request = try await client.makeStartLiveTrackRequest(liveTrackID: UUID().uuidString, remoteID: "test", label: "test", folderID: nil, now: Date())
        let body = String(decoding: request.httpBody!, as: UTF8.self)
        XCTAssertTrue(body.hasPrefix("json="))
        XCTAssertFalse(body.contains("signature="))
        XCTAssertFalse(body.contains("expires="))
        XCTAssertFalse(body.contains("&id="))
    }
    func testMissingSessionFailsBeforeAnyNetworkRequest() async throws {
        let client = try client()
        do {
            _ = try await client.fetchMapArtifacts()
            XCTFail("Missing personal session must fail closed")
        } catch let error as CaltopoLiveClientError {
            guard case .httpStatus(401, _) = error else { return XCTFail("Unexpected error: \(error)") }
        }
    }
    func testClearInvalidatesRegisteredSession() async throws {
        let id = UUID()
        await CaltopoPersonalSessions.shared.register(id) { _ in "session=test-only" }
        let url = CaltopoPersonalProbe.endpoint("ABC123")!
        let cookie = try await CaltopoPersonalSessions.shared.cookie(id, url: url)
        XCTAssertEqual(cookie, "session=test-only")
        await CaltopoPersonalSessions.shared.clear()
        do {
            _ = try await CaltopoPersonalSessions.shared.cookie(id, url: url)
            XCTFail("Clear must invalidate the old authorization")
        } catch { }
    }
}
