import XCTest
@testable import R2CCore
import Foundation

private final class PhotoTransport: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var paths: [String] = []
    nonisolated(unsafe) private static var methods: [String] = []
    nonisolated(unsafe) private static var cookies: [String?] = []
    nonisolated(unsafe) private static var failNextData = true
    nonisolated(unsafe) private static var mediaExists = false
    nonisolated(unsafe) private static var mediaReady = false
    nonisolated(unsafe) private static var mediaAbsentStatus = 404
    nonisolated(unsafe) private static var markerExists = false
    nonisolated(unsafe) private static var linkExists = false
    nonisolated(unsafe) private static var rejectMarkerPost = false
    nonisolated(unsafe) private static var rejectMediaCreate = false
    nonisolated(unsafe) private static var rejectMediaDataAsExisting = false
    static func reset(
        mediaAbsentStatus: Int = 404,
        rejectMarkerPost: Bool = false,
        failNextData: Bool = true,
        markerExists: Bool = false,
        rejectMediaCreate: Bool = false,
        rejectMediaDataAsExisting: Bool = false,
        mediaExists: Bool = false,
        mediaReady: Bool = false
    ) {
        lock.lock(); defer { lock.unlock() }
        paths = []; methods = []; cookies = []
        Self.failNextData = failNextData
        Self.mediaExists = mediaExists
        Self.mediaReady = mediaReady
        linkExists = false
        Self.mediaAbsentStatus = mediaAbsentStatus
        Self.rejectMarkerPost = rejectMarkerPost
        Self.markerExists = markerExists
        Self.rejectMediaCreate = rejectMediaCreate
        Self.rejectMediaDataAsExisting = rejectMediaDataAsExisting
    }
    static func snapshot() -> ([String], [String?]) { lock.lock(); defer { lock.unlock() }; return (paths, cookies) }
    static func methodPathSnapshot() -> [(String, String)] {
        lock.lock(); defer { lock.unlock() }
        return zip(methods, paths).map { ($0, $1) }
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        Self.lock.lock()
        Self.paths.append(path); Self.methods.append(method); Self.cookies.append(request.value(forHTTPHeaderField: "Cookie"))
        let fail = path.hasSuffix("/data") && Self.failNextData
        let rejectCreate = Self.rejectMediaCreate
        let rejectDataExisting = Self.rejectMediaDataAsExisting
        // Create POST (accepted or 400-existing) means the media object is present for recovery GET.
        if method == "POST" && path.hasPrefix("/api/v1/media/") && !path.hasSuffix("/data") {
            Self.mediaExists = true
            if rejectCreate { Self.mediaReady = true }
        }
        if method == "POST" && path.contains("/Marker/") && !Self.rejectMarkerPost { Self.markerExists = true }
        if method == "POST" && path.contains("/MapMediaObject/") { Self.linkExists = true }
        if fail { Self.failNextData = false; Self.mediaReady = true }
        if method == "POST" && path.hasSuffix("/data") && rejectDataExisting {
            Self.mediaReady = true
        }
        let exists = Self.mediaExists, ready = Self.mediaReady
        let absent = Self.mediaAbsentStatus
        let markerPresent = Self.markerExists
        let linkPresent = Self.linkExists
        let rejectMarker = Self.rejectMarkerPost
        Self.lock.unlock()
        if fail { client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost)); return }
        let mediaRead = method == "GET" && path.hasPrefix("/api/v1/media/")
        let markerRead = method == "GET" && path.contains("/Marker/")
        let linkRead = method == "GET" && path.contains("/MapMediaObject/")
        let markerPost = method == "POST" && path.contains("/Marker/")
        let mediaCreate = method == "POST" && path.hasPrefix("/api/v1/media/") && !path.hasSuffix("/data")
        let mediaData = method == "POST" && path.hasSuffix("/data")
        let code: Int
        if markerPost && rejectMarker { code = 400 }
        else if mediaCreate && rejectCreate { code = 400 }
        else if mediaData && rejectDataExisting { code = 400 }
        else if mediaRead && !exists { code = absent }
        else if markerRead && !markerPresent { code = 404 }
        else if linkRead && !linkPresent { code = 404 }
        else { code = 200 }
        let body: [String: Any]
        if (mediaCreate && rejectCreate) || (mediaData && rejectDataExisting) {
            body = ["message": "Cannot update existing backendMedia"]
        } else if mediaRead && exists {
            body = ["status":"ok", "result":["id":request.url!.lastPathComponent, "properties":["creator":"WORK01", "mediaIsReady":ready], "metadata":["filesize": ready ? 3 : 0]]]
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
        let media = clue.mediaID.uuidString.lowercased()
        let marker = clue.markerID.uuidString.lowercased()
        XCTAssertEqual(paths, [
            "/api/v1/map/ABC123/Marker/" + marker,
            "/api/v1/media/" + media,
            "/api/v1/media/" + media,
            "/api/v1/media/" + media + "/data",
            "/api/v1/map/ABC123/MapMediaObject/" + media,
            "/api/v1/map/ABC123/MapMediaObject/" + media,
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
        let media = clue.mediaID.uuidString.lowercased()
        let marker = clue.markerID.uuidString.lowercased()
        XCTAssertEqual(paths, [
            "/api/v1/map/ABC123/Marker/" + marker,
            "/api/v1/map/ABC123/Marker/" + marker,
            "/api/v1/media/" + media,
            "/api/v1/media/" + media,
            "/api/v1/media/" + media + "/data",
            "/api/v1/map/ABC123/MapMediaObject/" + media,
            "/api/v1/map/ABC123/MapMediaObject/" + media,
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

    func testExistingBackendMediaCreate400ContinuesToLink() async throws {
        PhotoTransport.reset(
            mediaAbsentStatus: 400,
            failNextData: false,
            rejectMediaCreate: true
        )
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [PhotoTransport.self]
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "org-must-not-be-used", credentialSecretBase64: "invalid!", personalSessionID: sessionID, personalAccountID: "USER01", personalMediaOwnerID: "WORK01"), session: URLSession(configuration: transport))
        let clue = CaltopoPhotoClue(latitude: 0, longitude: 0, title: "stuck", description: "clue", createdMilliseconds: 1, jpegData: Data([1,2,3]), teamID: "personal:USER01")
        let result = try await client.publishPhotoClue(clue)
        XCTAssertEqual(result, clue.markerID.uuidString.lowercased())
        let (paths, _) = PhotoTransport.snapshot()
        XCTAssertFalse(paths.contains { $0.hasSuffix("/data") })
        XCTAssertTrue(paths.contains { $0.contains("/MapMediaObject/") && ($0.split(separator: "/").last.map(String.init) == clue.mediaID.uuidString.lowercased()) })
        await CaltopoPersonalSessions.shared.clear()
    }

    func testExistingBackendMediaData400ContinuesWhenReady() async throws {
        PhotoTransport.reset(
            failNextData: false,
            rejectMediaDataAsExisting: true,
            mediaExists: true,
            mediaReady: false
        )
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [PhotoTransport.self]
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "org-must-not-be-used", credentialSecretBase64: "invalid!", personalSessionID: sessionID, personalAccountID: "USER01", personalMediaOwnerID: "WORK01"), session: URLSession(configuration: transport))
        let clue = CaltopoPhotoClue(latitude: 0, longitude: 0, title: "stuck", description: "clue", createdMilliseconds: 1, jpegData: Data(repeating: 1, count: 900_000), teamID: "personal:USER01")
        let result = try await client.publishPhotoClue(clue)
        XCTAssertEqual(result, clue.markerID.uuidString.lowercased())
        let (paths, _) = PhotoTransport.snapshot()
        XCTAssertEqual(paths.filter { $0.hasSuffix("/data") }.count, 1)
        XCTAssertTrue(paths.contains { $0.contains("/MapMediaObject/") })
        await CaltopoPersonalSessions.shared.clear()
    }

    func testPublishSkipsDataWhenMediaAlreadyReadyAndCreatesLink() async throws {
        // Same completion path reconcile uses when marker+media exist and only the link is missing.
        PhotoTransport.reset(
            failNextData: false,
            markerExists: true,
            mediaExists: true,
            mediaReady: true
        )
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [PhotoTransport.self]
        let client = try CaltopoLiveClient(configuration: .init(mapID: "ABC123", credentialID: "org-must-not-be-used", credentialSecretBase64: "invalid!", personalSessionID: sessionID, personalAccountID: "USER01", personalMediaOwnerID: "WORK01"), session: URLSession(configuration: transport))
        let clue = CaltopoPhotoClue(latitude: 0, longitude: 0, title: "stuck", description: "clue", createdMilliseconds: 1, jpegData: Data([9]), teamID: "personal:USER01")
        let result = try await client.publishPhotoClue(clue)
        XCTAssertEqual(result, clue.markerID.uuidString.lowercased())
        let calls = PhotoTransport.methodPathSnapshot()
        XCTAssertFalse(calls.contains { $0.1.hasSuffix("/data") })
        let media = clue.mediaID.uuidString.lowercased()
        XCTAssertFalse(calls.contains { $0.0 == "POST" && $0.1 == "/api/v1/media/\(media)" })
        XCTAssertTrue(calls.contains { $0.0 == "POST" && $0.1.contains("/MapMediaObject/") })
        await CaltopoPersonalSessions.shared.clear()
    }
}
