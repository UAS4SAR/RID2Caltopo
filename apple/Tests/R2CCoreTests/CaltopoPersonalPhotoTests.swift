import XCTest
@testable import R2CCore
import Foundation

private final class PhotoTransport: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var paths: [String] = []
    nonisolated(unsafe) private static var cookies: [String?] = []
    nonisolated(unsafe) private static var failNextData = true
    nonisolated(unsafe) private static var mediaExists = false
    nonisolated(unsafe) private static var mediaReady = false
    nonisolated(unsafe) private static var mediaAbsentStatus = 404
    nonisolated(unsafe) private static var markerExists = false
    nonisolated(unsafe) private static var rejectMarkerPost = false
    static func reset(
        mediaAbsentStatus: Int = 404,
        rejectMarkerPost: Bool = false,
        failNextData: Bool = true,
        markerExists: Bool = false
    ) {
        lock.lock(); defer { lock.unlock() }
        paths = []; cookies = []
        Self.failNextData = failNextData
        mediaExists = false
        mediaReady = false
        Self.mediaAbsentStatus = mediaAbsentStatus
        Self.rejectMarkerPost = rejectMarkerPost
        Self.markerExists = markerExists
    }
    static func snapshot() -> ([String], [String?]) { lock.lock(); defer { lock.unlock() }; return (paths, cookies) }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        Self.lock.lock()
        Self.paths.append(path); Self.cookies.append(request.value(forHTTPHeaderField: "Cookie"))
        let fail = path.hasSuffix("/data") && Self.failNextData
        if method == "POST" && path.hasPrefix("/api/v1/media/") && !path.hasSuffix("/data") { Self.mediaExists = true }
        if method == "POST" && path.contains("/Marker/") && !Self.rejectMarkerPost { Self.markerExists = true }
        if fail { Self.failNextData = false; Self.mediaReady = true }
        let exists = Self.mediaExists, ready = Self.mediaReady
        let absent = Self.mediaAbsentStatus
        let markerPresent = Self.markerExists
        let rejectMarker = Self.rejectMarkerPost
        Self.lock.unlock()
        if fail { client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost)); return }
        let mediaRead = method == "GET" && path.hasPrefix("/api/v1/media/")
        let markerRead = method == "GET" && path.contains("/Marker/")
        let markerPost = method == "POST" && path.contains("/Marker/")
        let code: Int
        if markerPost && rejectMarker { code = 400 }
        else if mediaRead && !exists { code = absent }
        else if markerRead && !markerPresent { code = 404 }
        else { code = 200 }
        let body: [String: Any]
        if mediaRead && exists {
            body = ["status":"ok", "result":["id":request.url!.lastPathComponent, "properties":["creator":"WORK01", "mediaIsReady":ready], "metadata":["filesize":3]]]
        } else if code == 200 {
            body = ["status":"ok", "result":[:]]
        } else {
            body = ["status":"error", "result":[:]]
        }
        let responseBody: Data
        if mediaRead && !exists && absent == 400 {
            responseBody = Data("Personal map request failed.".utf8)
        } else {
            responseBody = try! JSONSerialization.data(withJSONObject: body)
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: responseBody)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class CaltopoPersonalPhotoTests: XCTestCase {
    func testLostUploadResponseRetriesSameObjectsWithPersonalCookie() async throws {
        PhotoTransport.reset()
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [PhotoTransport.self]
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "org-must-not-be-used", credentialSecretBase64: "invalid!", personalSessionID: sessionID, personalAccountID: "USER01", personalMediaOwnerID: "WORK01"), session: URLSession(configuration: transport))
        let clue = CaltopoPhotoClue(latitude: 0, longitude: 0, title: "Test", description: "Test", createdMilliseconds: 1, jpegData: Data([1,2,3]), teamID: "personal:USER01")
        let requests = try await client.makePhotoClueRequests(clue, now: Date())
        for request in requests {
            let body = String(decoding: request.httpBody!, as: UTF8.self)
            XCTAssertTrue(body.hasPrefix("json=")); XCTAssertFalse(body.contains("signature=")); XCTAssertFalse(body.contains("&id="))
        }
        let mediaBody = String(decoding: requests[1].httpBody!, as: UTF8.self).removingPercentEncoding!
        XCTAssertTrue(mediaBody.contains("WORK01")); XCTAssertFalse(mediaBody.contains("personal:USER01"))
        do { _ = try await client.publishPhotoClue(clue); XCTFail("Expected simulated lost response") } catch { }
        let result = try await client.publishPhotoClue(clue)
        XCTAssertEqual(result, clue.markerID.uuidString.lowercased())
        let (paths, cookies) = PhotoTransport.snapshot()
        XCTAssertEqual(paths.filter { $0.contains("/Marker/") }.count, 2)
        XCTAssertEqual(paths.filter { $0.hasSuffix("/data") }.count, 1)
        XCTAssertEqual(paths.last, "/api/v1/map/ABC123/MapMediaObject/" + clue.mediaID.uuidString.lowercased())
        XCTAssertTrue(cookies.allSatisfy { $0 == "session=test-only" })
        await CaltopoPersonalSessions.shared.clear()
    }

    func testMediaGet400LeadsToUploadThenAttachThenSuccess() async throws {
        PhotoTransport.reset(mediaAbsentStatus: 400, failNextData: false)
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [PhotoTransport.self]
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "org-must-not-be-used", credentialSecretBase64: "invalid!", personalSessionID: sessionID, personalAccountID: "USER01", personalMediaOwnerID: "WORK01"), session: URLSession(configuration: transport))
        let clue = CaltopoPhotoClue(latitude: 0, longitude: 0, title: "lil dog", description: "clue", createdMilliseconds: 1, jpegData: Data([1,2,3]), teamID: "personal:USER01")
        let result = try await client.publishPhotoClue(clue)
        XCTAssertEqual(result, clue.markerID.uuidString.lowercased())
        let (paths, _) = PhotoTransport.snapshot()
        XCTAssertEqual(paths, [
            "/api/v1/map/ABC123/Marker/" + clue.markerID.uuidString.lowercased(),
            "/api/v1/media/" + clue.mediaID.uuidString.lowercased(),
            "/api/v1/media/" + clue.mediaID.uuidString.lowercased(),
            "/api/v1/media/" + clue.mediaID.uuidString.lowercased() + "/data",
            "/api/v1/map/ABC123/MapMediaObject/" + clue.mediaID.uuidString.lowercased(),
        ])
        await CaltopoPersonalSessions.shared.clear()
    }

    func testAttachToExistingMarkerAfterMarkerPostConflict() async throws {
        PhotoTransport.reset(mediaAbsentStatus: 400, rejectMarkerPost: true, failNextData: false, markerExists: true)
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [PhotoTransport.self]
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "org-must-not-be-used", credentialSecretBase64: "invalid!", personalSessionID: sessionID, personalAccountID: "USER01", personalMediaOwnerID: "WORK01"), session: URLSession(configuration: transport))
        let clue = CaltopoPhotoClue(latitude: 0, longitude: 0, title: "lil dog", description: "clue", createdMilliseconds: 1, jpegData: Data([1,2,3]), teamID: "personal:USER01")
        let result = try await client.publishPhotoClue(clue)
        XCTAssertEqual(result, clue.markerID.uuidString.lowercased())
        let (paths, _) = PhotoTransport.snapshot()
        XCTAssertEqual(paths, [
            "/api/v1/map/ABC123/Marker/" + clue.markerID.uuidString.lowercased(),
            "/api/v1/map/ABC123/Marker/" + clue.markerID.uuidString.lowercased(),
            "/api/v1/media/" + clue.mediaID.uuidString.lowercased(),
            "/api/v1/media/" + clue.mediaID.uuidString.lowercased(),
            "/api/v1/media/" + clue.mediaID.uuidString.lowercased() + "/data",
            "/api/v1/map/ABC123/MapMediaObject/" + clue.mediaID.uuidString.lowercased(),
        ])
        await CaltopoPersonalSessions.shared.clear()
    }

    func testMediaGrantCannotReachAnotherPhotoAndClearRevokesIt() async throws {
        let sessionID = UUID(), media = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let url = URL(string: "https://caltopo.com/api/v1/media/\(media.uuidString.lowercased())/data")!
        do { _ = try await CaltopoPersonalSessions.shared.cookie(sessionID, url: url); XCTFail("Ungrantable media") } catch { }
        try await CaltopoPersonalSessions.shared.authorizeMedia(sessionID, mediaID: media)
        let value = try await CaltopoPersonalSessions.shared.cookie(sessionID, url: url)
        XCTAssertEqual(value, "session=test-only")
        let other = URL(string: "https://caltopo.com/api/v1/media/\(UUID().uuidString.lowercased())/data")!
        do { _ = try await CaltopoPersonalSessions.shared.cookie(sessionID, url: other); XCTFail("Other media") } catch { }
        await CaltopoPersonalSessions.shared.clear()
        do { _ = try await CaltopoPersonalSessions.shared.cookie(sessionID, url: url); XCTFail("Cleared session") } catch { }
    }
    func testPersonalPhotoRejectsDifferentAccount() async throws {
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "", credentialSecretBase64: "", personalSessionID: UUID(), personalAccountID: "USER01", personalMediaOwnerID: "USER01"))
        let clue = CaltopoPhotoClue(latitude: 0, longitude: 0, title: "Test", description: "", createdMilliseconds: 1, jpegData: Data([1]), teamID: "personal:USER02")
        do { _ = try await client.makePhotoClueRequests(clue, now: Date()); XCTFail("Wrong account") } catch { }
    }
}
