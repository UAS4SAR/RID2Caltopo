import XCTest
@testable import R2CCore
import Foundation

/// Scripts Marker/MapMediaObject geometry GET/POST without contacting CalTopo.
private final class GeometryTransport: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var methodPaths: [(String, String)] = []
    nonisolated(unsafe) private static var postBodies: [[String: Any]] = []
    nonisolated(unsafe) private static var linkPresent = true
    nonisolated(unsafe) private static var markerLat = 47.601
    nonisolated(unsafe) private static var markerLng = -122.332
    nonisolated(unsafe) private static var linkLat = 47.601
    nonisolated(unsafe) private static var linkLng = -122.332

    static func reset(
        linkPresent: Bool = true,
        markerLat: Double = 47.601,
        markerLng: Double = -122.332,
        linkLat: Double = 47.601,
        linkLng: Double = -122.332
    ) {
        lock.lock(); defer { lock.unlock() }
        methodPaths = []
        postBodies = []
        Self.linkPresent = linkPresent
        Self.markerLat = markerLat
        Self.markerLng = markerLng
        Self.linkLat = linkLat
        Self.linkLng = linkLng
    }

    static func methodPathSnapshot() -> [(String, String)] {
        lock.lock(); defer { lock.unlock() }
        return methodPaths
    }

    static func postBodySnapshot() -> [[String: Any]] {
        lock.lock(); defer { lock.unlock() }
        return postBodies
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        Self.lock.lock()
        Self.methodPaths.append((method, path))
        let linkPresent = Self.linkPresent
        let markerLat = Self.markerLat
        let markerLng = Self.markerLng
        let linkLat = Self.linkLat
        let linkLng = Self.linkLng
        if method == "POST", let body = Self.requestBodyData(request),
           let decoded = Self.decodeJSONBody(body) {
            Self.postBodies.append(decoded)
        }
        Self.lock.unlock()

        let markerGet = method == "GET" && path.contains("/Marker/")
        let linkGet = method == "GET" && path.contains("/MapMediaObject/")
        let code: Int
        if linkGet && !linkPresent {
            code = 404
        } else {
            code = 200
        }
        let body: [String: Any]
        if markerGet {
            body = [
                "status": "ok",
                "result": [
                    "id": path.split(separator: "/").last.map(String.init) ?? "",
                    "type": "Feature",
                    "geometry": ["type": "Point", "coordinates": [markerLng, markerLat]],
                    "properties": ["class": "Marker"],
                ],
            ]
        } else if linkGet && linkPresent {
            body = [
                "status": "ok",
                "result": [
                    "id": path.split(separator: "/").last.map(String.init) ?? "",
                    "type": "Feature",
                    "geometry": ["type": "Point", "coordinates": [linkLng, linkLat]],
                    "properties": [
                        "class": "MapMediaObject",
                        "parentId": "Marker:keep",
                        "backendMediaId": path.split(separator: "/").last.map(String.init) ?? "",
                        "title": "keep",
                        "marker-symbol": "aperture",
                    ],
                ],
            ]
        } else if code == 200 {
            body = ["status": "ok", "result": [:]]
        } else {
            body = ["status": "error", "result": [:]]
        }
        let responseBody = try! JSONSerialization.data(withJSONObject: body)
        client?.urlProtocol(
            self,
            didReceive: HTTPURLResponse(
                url: request.url!,
                statusCode: code,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!,
            cacheStoragePolicy: .notAllowed
        )
        client?.urlProtocol(self, didLoad: responseBody)
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func requestBodyData(_ request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read < 0 { return nil }
            if read == 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }

    private static func decodeJSONBody(_ body: Data) -> [String: Any]? {
        if let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any] {
            return object
        }
        guard let text = String(data: body, encoding: .utf8) else { return nil }
        var components = URLComponents()
        components.percentEncodedQuery = text
        guard let json = components.queryItems?.first(where: { $0.name == "json" })?.value,
              let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return object
    }

    static func coordinates(from body: [String: Any]) -> [Double]? {
        guard let geometry = body["geometry"] as? [String: Any],
              let raw = geometry["coordinates"] as? [Any],
              raw.count >= 2,
              let lng = (raw[0] as? NSNumber)?.doubleValue,
              let lat = (raw[1] as? NSNumber)?.doubleValue
        else { return nil }
        return [lng, lat]
    }
}

final class CluePhotoGeometryUpdateTests: XCTestCase {
    func testRefineAfterPublishPostsIdenticalCoordsToMarkerAndMapMediaObject() async throws {
        GeometryTransport.reset(linkPresent: true, linkLat: 47.6, linkLng: -122.33)
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        defer { Task { await CaltopoPersonalSessions.shared.clear() } }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [GeometryTransport.self]
        let client = try CaltopoLiveClient(
            configuration: .init(
                mapID: "ABC123",
                credentialID: "org-must-not-be-used",
                credentialSecretBase64: "invalid!",
                personalSessionID: sessionID,
                personalAccountID: "USER01",
                personalMediaOwnerID: "WORK01"
            ),
            session: URLSession(configuration: transport)
        )
        let markerID = UUID()
        let mediaID = UUID()
        let lat = 47.601
        let lng = -122.332
        try await client.updatePhotoClueGeometry(
            markerID: markerID.uuidString.lowercased(),
            mediaID: mediaID,
            latitude: lat,
            longitude: lng,
            title: "clue",
            description: "desc",
            folderID: "folder",
            createdMilliseconds: 1
        )
        let calls = GeometryTransport.methodPathSnapshot()
        let markerPath = "/api/v1/map/ABC123/Marker/\(markerID.uuidString.lowercased())"
        let linkPath = "/api/v1/map/ABC123/MapMediaObject/\(mediaID.uuidString.lowercased())"
        XCTAssertEqual(calls.map { "\($0.0) \($0.1)" }, [
            "POST \(markerPath)",
            "GET \(linkPath)",
            "POST \(linkPath)",
        ])
        XCTAssertFalse(calls.contains { $0.1.contains("/media/") })
        let bodies = GeometryTransport.postBodySnapshot()
        XCTAssertEqual(bodies.count, 2, "expected Marker + MapMediaObject geometry POSTs")
        guard bodies.count >= 2 else { return }
        XCTAssertEqual(GeometryTransport.coordinates(from: bodies[0]), [lng, lat])
        XCTAssertEqual(GeometryTransport.coordinates(from: bodies[1]), [lng, lat])
        let props = bodies[1]["properties"] as? [String: Any]
        XCTAssertEqual(props?["title"] as? String, "keep")
        XCTAssertEqual(props?["backendMediaId"] as? String, mediaID.uuidString.lowercased())
        await CaltopoPersonalSessions.shared.clear()
    }

    func testDeferredRefineFailsWhenMediaLinkMissing() async throws {
        GeometryTransport.reset(linkPresent: false)
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [GeometryTransport.self]
        let client = try CaltopoLiveClient(
            configuration: .init(
                mapID: "ABC123",
                credentialID: "org-must-not-be-used",
                credentialSecretBase64: "invalid!",
                personalSessionID: sessionID,
                personalAccountID: "USER01",
                personalMediaOwnerID: "WORK01"
            ),
            session: URLSession(configuration: transport)
        )
        let markerID = UUID()
        let mediaID = UUID()
        do {
            try await client.updatePhotoClueGeometry(
                markerID: markerID.uuidString.lowercased(),
                mediaID: mediaID,
                latitude: 47.0,
                longitude: -122.0,
                title: "clue",
                description: "desc",
                folderID: nil,
                createdMilliseconds: 1
            )
            XCTFail("Expected link-missing failure")
        } catch let CaltopoLiveClientError.httpStatus(code, message) {
            XCTAssertEqual(code, 404)
            XCTAssertTrue(message.contains("link-missing"))
        }
        let calls = GeometryTransport.methodPathSnapshot()
        XCTAssertEqual(calls.filter { $0.0 == "POST" && $0.1.contains("/MapMediaObject/") }.count, 0)
        XCTAssertFalse(calls.contains { $0.1.contains("/media/") })
        await CaltopoPersonalSessions.shared.clear()
    }

    func testReconcileRepairsDriftedPhotoWithoutMediaReupload() async throws {
        GeometryTransport.reset(
            linkPresent: true,
            markerLat: 47.601,
            markerLng: -122.332,
            linkLat: 47.6009,
            linkLng: -122.332
        )
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [GeometryTransport.self]
        let client = try CaltopoLiveClient(
            configuration: .init(
                mapID: "ABC123",
                credentialID: "org-must-not-be-used",
                credentialSecretBase64: "invalid!",
                personalSessionID: sessionID,
                personalAccountID: "USER01",
                personalMediaOwnerID: "WORK01"
            ),
            session: URLSession(configuration: transport)
        )
        let markerID = UUID()
        let mediaID = UUID()
        let repaired = try await client.repairDriftedPhotoClueLink(markerID: markerID, mediaID: mediaID)
        XCTAssertTrue(repaired)
        let calls = GeometryTransport.methodPathSnapshot()
        XCTAssertFalse(calls.contains { $0.1.contains("/media/") })
        XCTAssertEqual(calls.filter { $0.0 == "POST" && $0.1.contains("/MapMediaObject/") }.count, 1)
        let bodies = GeometryTransport.postBodySnapshot()
        XCTAssertEqual(bodies.count, 1, "expected one MapMediaObject geometry POST")
        guard let repaired = bodies.first else { return }
        XCTAssertEqual(GeometryTransport.coordinates(from: repaired), [-122.332, 47.601])
        XCTAssertEqual((repaired["properties"] as? [String: Any])?["title"] as? String, "keep")
        await CaltopoPersonalSessions.shared.clear()
    }

    func testAlignedPhotoSkipsRepairPost() async throws {
        GeometryTransport.reset(linkPresent: true)
        let sessionID = UUID()
        await CaltopoPersonalSessions.shared.register(sessionID) { _ in "session=test-only" }
        let transport = URLSessionConfiguration.ephemeral
        transport.protocolClasses = [GeometryTransport.self]
        let client = try CaltopoLiveClient(
            configuration: .init(
                mapID: "ABC123",
                credentialID: "org-must-not-be-used",
                credentialSecretBase64: "invalid!",
                personalSessionID: sessionID,
                personalAccountID: "USER01",
                personalMediaOwnerID: "WORK01"
            ),
            session: URLSession(configuration: transport)
        )
        let repaired = try await client.repairDriftedPhotoClueLink(markerID: UUID(), mediaID: UUID())
        XCTAssertFalse(repaired)
        let calls = GeometryTransport.methodPathSnapshot()
        XCTAssertEqual(calls.filter { $0.0 == "POST" }.count, 0)
        await CaltopoPersonalSessions.shared.clear()
    }
}
