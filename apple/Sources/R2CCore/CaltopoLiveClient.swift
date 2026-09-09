import CryptoKit
import Foundation

public struct CaltopoCameraMetadata: Equatable, Sendable {
    public let externalURL: URL
    public let thumbnailURL: URL?
    public let azimuthDegrees: Double?
    public let tiltDegrees: Double?
    public let horizontalFovDegrees: Double?
    public let verticalFovDegrees: Double?

    public init(
        externalURL: URL,
        thumbnailURL: URL? = nil,
        azimuthDegrees: Double? = nil,
        tiltDegrees: Double? = nil,
        horizontalFovDegrees: Double? = nil,
        verticalFovDegrees: Double? = nil
    ) {
        self.externalURL = externalURL
        self.thumbnailURL = thumbnailURL
        self.azimuthDegrees = azimuthDegrees
        self.tiltDegrees = tiltDegrees
        self.horizontalFovDegrees = horizontalFovDegrees
        self.verticalFovDegrees = verticalFovDegrees
    }
}

public struct CaltopoLiveConfiguration: Sendable, Equatable {
    public let domainAndPort: String
    public let mapID: String
    public let credentialID: String
    public let credentialSecretBase64: String
    public let connectKey: String

    public init(
        domainAndPort: String = "caltopo.com",
        mapID: String,
        credentialID: String,
        credentialSecretBase64: String,
        connectKey: String = ""
    ) {
        self.domainAndPort = domainAndPort
        self.mapID = mapID
        self.credentialID = credentialID
        self.credentialSecretBase64 = credentialSecretBase64
        self.connectKey = connectKey
    }
}

public enum CaltopoLiveClientError: Error, Sendable, Equatable {
    case invalidConfiguration
    case invalidCredentialSecret
    case invalidURL
    case httpStatus(Int, String)
    case missingResult
    case missingLiveTrackID
}

extension CaltopoLiveClientError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .invalidConfiguration:
            return "The CalTopo domain, team, map, or credential configuration is incomplete."
        case .invalidCredentialSecret:
            return "The CalTopo credential secret is not valid Base64."
        case .invalidURL:
            return "The CalTopo server address is invalid."
        case let .httpStatus(code, message):
            let detail = message.trimmingCharacters(in: .whitespacesAndNewlines)
            return detail.isEmpty
                ? "CalTopo returned HTTP \(code)."
                : "CalTopo returned HTTP \(code): \(detail)"
        case .missingResult:
            return "CalTopo returned a response without the expected result."
        case .missingLiveTrackID:
            return "CalTopo did not return a live-track identifier."
        }
    }
}

public struct CaltopoDeviceMarker: Sendable, Equatable {
    public let id: String
    public let title: String
    public let deviceName: String
    public let latitude: Double
    public let longitude: Double
    public let description: String
    public let color: String

    public init(
        id: String,
        title: String,
        deviceName: String,
        latitude: Double,
        longitude: Double,
        description: String,
        color: String
    ) {
        self.id = id
        self.title = title
        self.deviceName = deviceName
        self.latitude = latitude
        self.longitude = longitude
        self.description = description
        self.color = color
    }
}

public enum CaltopoRequestSigner {
    // Match Android CaltopoSession.DEFAULT_TIMEOUT_MS. CalTopo validates this
    // expiry as part of the HMAC-authenticated request.
    public static let validityMilliseconds: Int64 = 2 * 60 * 1_000

    public static func signature(
        method: String,
        path: String,
        expiresMilliseconds: Int64,
        payload: String,
        credentialSecretBase64: String
    ) throws -> String {
        guard let keyData = Data(
            base64Encoded: credentialSecretBase64,
            options: [.ignoreUnknownCharacters]
        ), !keyData.isEmpty else {
            throw CaltopoLiveClientError.invalidCredentialSecret
        }
        let message = "\(method) \(path)\n\(expiresMilliseconds)\n\(payload)"
        let code = HMAC<SHA256>.authenticationCode(
            for: Data(message.utf8),
            using: SymmetricKey(data: keyData)
        )
        return Data(code).base64EncodedString()
    }

    static func percentEncodedQuery(_ fields: [(String, String)]) -> String {
        // URLComponents.queryItems leaves "+" and "/" unescaped. CalTopo
        // parses query parameters as form data, where a literal "+" becomes a
        // space and intermittently corrupts Base64 HMAC signatures. OkHttp's
        // addQueryParameter(), used by Android, escapes both characters.
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        return fields.map { key, value in
            let encodedKey = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let encodedValue = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(encodedKey)=\(encodedValue)"
        }.joined(separator: "&")
    }
}

public actor CaltopoLiveClient {
    private let configuration: CaltopoLiveConfiguration
    private let session: URLSession

    public init(
        configuration: CaltopoLiveConfiguration,
        session: URLSession = .shared
    ) throws {
        guard !configuration.domainAndPort.isEmpty,
              !configuration.mapID.isEmpty,
              !configuration.credentialID.isEmpty,
              !configuration.credentialSecretBase64.isEmpty
        else {
            throw CaltopoLiveClientError.invalidConfiguration
        }
        self.configuration = configuration
        self.session = session
    }

    public func startLiveTrack(
        remoteID: String,
        label: String,
        folderID: String? = nil,
        now: Date = Date()
    ) async throws -> String {
        let request = try makeStartLiveTrackRequest(
            remoteID: remoteID,
            label: label,
            folderID: folderID,
            now: now
        )
        let data = try await perform(request)
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let result = root?["result"] as? [String: Any] else {
            throw CaltopoLiveClientError.missingResult
        }
        guard let id = result["id"] as? String, !id.isEmpty else {
            throw CaltopoLiveClientError.missingLiveTrackID
        }
        return id
    }

    public func publishPoint(
        remoteID: String,
        observation: RidObservation,
        cameraMetadata: CaltopoCameraMetadata? = nil
    ) async throws {
        _ = try await perform(makePointRequest(
            remoteID: remoteID,
            observation: observation,
            cameraMetadata: cameraMetadata
        ))
    }

    public func stopLiveTrack(liveTrackID: String, now: Date = Date()) async throws {
        _ = try await perform(
            makeStopLiveTrackRequest(liveTrackID: liveTrackID, now: now),
            acceptedStatusCodes: [400, 404]
        )
    }

    public func fetchMapArtifacts(now: Date = Date()) async throws -> CaltopoArtifactSnapshot {
        let request = try makeMapSnapshotRequest(now: now)
        let data = try await perform(request)
        return try CaltopoArtifactDecoder.decode(data: data)
    }

    public func fetchMapArtifactChanges(
        sinceMilliseconds: Int64,
        now: Date = Date()
    ) async throws -> CaltopoArtifactChanges {
        let request = try makeMapChangesRequest(sinceMilliseconds: sinceMilliseconds, now: now)
        let data = try await perform(request)
        return try CaltopoArtifactDecoder.decodeChanges(data: data)
    }

    public func createFolder(
        title: String,
        visible: Bool,
        labelVisible: Bool,
        now: Date = Date()
    ) async throws -> String {
        let data = try await perform(makeCreateFolderRequest(
            title: title,
            visible: visible,
            labelVisible: labelVisible,
            now: now
        ))
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let result = root?["result"] as? [String: Any]
        if let id = result?["id"] as? String, !id.isEmpty { return id }
        if let id = root?["id"] as? String, !id.isEmpty { return id }
        throw CaltopoLiveClientError.missingResult
    }

    public func deleteFolder(folderID: String, now: Date = Date()) async throws {
        _ = try await perform(
            makeDeleteFolderRequest(folderID: folderID, now: now),
            acceptedStatusCodes: [400, 404]
        )
    }

    public func archiveLiveTrack(
        liveTrackID: String,
        label: String,
        observations: [RidObservation],
        folderID: String,
        description: String = "",
        now: Date = Date()
    ) async throws {
        guard !observations.isEmpty else {
            try await stopLiveTrack(liveTrackID: liveTrackID, now: now)
            return
        }
        _ = try await perform(makeArchiveLiveTrackRequest(
            liveTrackID: liveTrackID,
            label: label,
            observations: observations,
            folderID: folderID,
            description: description,
            now: now
        ))
        try await stopLiveTrack(liveTrackID: liveTrackID, now: now)
    }

    public func updateArchivedTrack(
        liveTrackID: String,
        label: String,
        observations: [RidObservation],
        folderID: String,
        description: String,
        now: Date = Date()
    ) async throws {
        guard !observations.isEmpty else { return }
        _ = try await perform(makeArchiveLiveTrackRequest(
            liveTrackID: liveTrackID,
            label: label,
            observations: observations,
            folderID: folderID,
            description: description,
            now: now
        ))
    }

    public func publishPhotoClue(_ clue: CaltopoPhotoClue, now: Date = Date()) async throws -> String {
        let requests = try makePhotoClueRequests(clue, now: now)
        for request in requests { _ = try await perform(request) }
        return clue.markerID.uuidString.lowercased()
    }

    public func publishDeviceMarker(
        _ marker: CaltopoDeviceMarker,
        folderID: String?,
        now: Date = Date()
    ) async throws {
        _ = try await perform(makeDeviceMarkerRequest(marker, folderID: folderID, now: now))
    }

    public func deleteMarker(markerID: String, now: Date = Date()) async throws {
        _ = try await perform(
            makeDeleteMarkerRequest(markerID: markerID, now: now),
            acceptedStatusCodes: [400, 404]
        )
    }

    func makeDeviceMarkerRequest(
        _ marker: CaltopoDeviceMarker,
        folderID: String?,
        now: Date
    ) throws -> URLRequest {
        let markerID = marker.id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !markerID.isEmpty,
              !marker.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              marker.latitude.isFinite,
              marker.longitude.isFinite
        else { throw CaltopoLiveClientError.invalidConfiguration }
        let updated = Int64(now.timeIntervalSince1970 * 1_000)
        var properties: [String: Any] = [
            "class": "Marker",
            "updated": updated,
            "title": marker.title,
            "description": marker.description,
            "marker-color": marker.color,
            "marker-symbol": "radiotower",
            "marker-size": 1,
            "marker-visibility": "visible",
            "r2c-name": marker.deviceName,
            "r2c-guid": markerID,
            "r2c-last-seen-epoch-ms": updated,
        ]
        if let folderID, !folderID.isEmpty {
            properties["folderId"] = folderID
        }
        return try makeSignedPostRequest(
            path: "/api/v1/map/\(configuration.mapID)/Marker/\(markerID)",
            object: [
                "id": markerID,
                "type": "Feature",
                "geometry": [
                    "type": "Point",
                    "coordinates": [marker.longitude, marker.latitude],
                ],
                "properties": properties,
            ],
            now: now
        )
    }

    func makeDeleteMarkerRequest(markerID: String, now: Date) throws -> URLRequest {
        let normalizedID = markerID.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalizedID.isEmpty else { throw CaltopoLiveClientError.invalidConfiguration }
        let path = "/api/v1/map/\(configuration.mapID)/Marker/\(normalizedID)"
        let expires = Int64(now.timeIntervalSince1970 * 1_000)
            + CaltopoRequestSigner.validityMilliseconds
        let signature = try CaltopoRequestSigner.signature(
            method: "DELETE",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.percentEncodedQuery = CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ])
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    func makePhotoClueRequests(_ clue: CaltopoPhotoClue, now: Date) throws -> [URLRequest] {
        guard !clue.teamID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              clue.latitude.isFinite, clue.longitude.isFinite,
              !clue.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !clue.jpegData.isEmpty
        else { throw CaltopoLiveClientError.invalidConfiguration }
        let markerID = clue.markerID.uuidString.lowercased()
        let mediaID = clue.mediaID.uuidString.lowercased()
        let markerPath = "/api/v1/map/\(configuration.mapID)/Marker/\(markerID)"
        var markerProperties: [String: Any] = [
            "class": "Marker",
            "updated": Int64(now.timeIntervalSince1970 * 1_000),
            "created": clue.createdMilliseconds,
            "title": clue.title,
            "description": clue.description,
            "marker-color": "#FF0000",
            "marker-symbol": "Drone",
            "marker-size": "1",
            "marker-visibility": "visible",
        ]
        if let folderID = clue.folderID?.trimmingCharacters(in: .whitespacesAndNewlines),
           !folderID.isEmpty {
            markerProperties["folderId"] = folderID
        }
        let markerPayload: [String: Any] = [
            // Android AddMarker() sends the UUID in both the endpoint and the
            // Feature payload. CalTopo uses the payload id as the parent
            // identity resolved by MapMediaObject.parentId.
            "id": markerID,
            "type": "Feature",
            "geometry": [
                "type": "Point",
                "coordinates": [clue.longitude, clue.latitude],
            ],
            "properties": markerProperties,
        ]
        let mediaPath = "/api/v1/media/\(mediaID)"
        let mediaPayload: [String: Any] = ["properties": ["creator": clue.teamID]]
        let dataPath = mediaPath + "/data"
        let dataPayload: [String: Any] = [
            "creator": clue.teamID,
            "data": clue.jpegData.base64EncodedString(),
        ]
        let linkPath = "/api/v1/map/\(configuration.mapID)/MapMediaObject"
        let linkPayload: [String: Any] = [
            "type": "Feature",
            "geometry": [
                "type": "Point",
                "coordinates": [clue.longitude, clue.latitude],
            ],
            "properties": [
                "title": clue.title,
                "description": clue.description,
                "parentId": "Marker:\(markerID)",
                "backendMediaId": mediaID,
                "heading": NSNull(),
                "class": "MapMediaObject",
                "marker-symbol": "aperture",
                "marker-color": "#FF00FF",
                "marker-size": 1,
                "created": Int64(now.timeIntervalSince1970 * 1_000),
            ],
        ]
        return try [
            makeSignedPostRequest(path: markerPath, object: markerPayload, now: now),
            makeSignedPostRequest(path: mediaPath, object: mediaPayload, now: now),
            makeSignedPostRequest(path: dataPath, object: dataPayload, now: now),
            makeSignedPostRequest(path: linkPath, object: linkPayload, now: now),
        ]
    }

    func makeMapSnapshotRequest(now: Date) throws -> URLRequest {
        try makeMapChangesRequest(sinceMilliseconds: 0, now: now)
    }

    func makeMapChangesRequest(sinceMilliseconds: Int64, now: Date) throws -> URLRequest {
        let cursor = max(0, sinceMilliseconds - 500)
        let path = "/api/v1/map/\(configuration.mapID)/since/\(cursor)"
        let expires = Int64(now.timeIntervalSince1970 * 1_000)
            + CaltopoRequestSigner.validityMilliseconds
        let signature = try CaltopoRequestSigner.signature(
            method: "GET",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.percentEncodedQuery = CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ])
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    func makeStartLiveTrackRequest(
        remoteID: String,
        label: String,
        folderID: String?,
        now: Date
    ) throws -> URLRequest {
        let path = "/api/v1/map/\(configuration.mapID)/LiveTrack"
        var properties: [String: Any] = [
            "title": label,
            "stroke-width": 2,
            "stroke-opacity": 1,
            "stroke": "#0000ff",
            "pattern": "solid",
            "marker-symbol": "icon-8T781R60-12-0.5-0.5-tf",
            "marker-size": 2,
            "class": "LiveTrack",
            "deviceId": "FLEET:\(effectiveConnectKey)-\(remoteID)",
        ]
        if let folderID, !folderID.isEmpty {
            properties["folderId"] = folderID
        }
        let payloadData = try JSONSerialization.data(
            withJSONObject: ["type": "Feature", "properties": properties],
            options: [.sortedKeys]
        )
        let payload = String(decoding: payloadData, as: UTF8.self)
        let expires = Int64(now.timeIntervalSince1970 * 1_000)
            + CaltopoRequestSigner.validityMilliseconds
        let signature = try CaltopoRequestSigner.signature(
            method: "POST",
            path: path,
            expiresMilliseconds: expires,
            payload: payload,
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard let url = httpsURL(path: path) else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody([
            "id": configuration.credentialID,
            "expires": String(expires),
            "signature": signature,
            "json": payload,
        ])
        return request
    }

    func makeCreateFolderRequest(
        title: String,
        visible: Bool,
        labelVisible: Bool,
        now: Date
    ) throws -> URLRequest {
        let path = "/api/v1/map/\(configuration.mapID)/Folder"
        return try makeSignedPostRequest(
            path: path,
            object: [
                "type": "Feature",
                "properties": [
                    "class": "Folder",
                    "title": title,
                    "visible": visible,
                    "labelVisible": labelVisible,
                ],
            ],
            now: now
        )
    }

    func makeArchiveLiveTrackRequest(
        liveTrackID: String,
        label: String,
        observations: [RidObservation],
        folderID: String,
        description: String = "",
        now: Date
    ) throws -> URLRequest {
        guard !liveTrackID.isEmpty else { throw CaltopoLiveClientError.missingLiveTrackID }
        let updated = Int64(now.timeIntervalSince1970 * 1_000)
        let coordinates: [[Double]] = observations.map {
            [$0.longitude, $0.latitude, $0.altitudeMeters ?? -1_000]
        }
        var properties: [String: Any] = [
            "class": "Shape",
            "title": label,
            "folderId": folderID,
            "stroke": "#ff00ff",
            "stroke-width": 2,
            "stroke-opacity": 0.5,
            "pattern": "solid",
            "updated": String(updated),
            "-updated-on": String(updated),
        ]
        if !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            properties["description"] = description
        }
        return try makeSignedPostRequest(
            path: "/api/v1/map/\(configuration.mapID)/Shape/\(liveTrackID)",
            object: [
                "id": liveTrackID,
                "type": "Feature",
                "properties": properties,
                "geometry": [
                    "type": "LineString",
                    "coordinates": coordinates,
                    "size": coordinates.count,
                ],
            ],
            now: now
        )
    }

    private func makeSignedPostRequest(path: String, object: [String: Any], now: Date) throws -> URLRequest {
        let payloadData = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let payload = String(decoding: payloadData, as: UTF8.self)
        let expires = Int64(now.timeIntervalSince1970 * 1_000)
            + CaltopoRequestSigner.validityMilliseconds
        let signature = try CaltopoRequestSigner.signature(
            method: "POST",
            path: path,
            expiresMilliseconds: expires,
            payload: payload,
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard let url = httpsURL(path: path) else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody([
            "id": configuration.credentialID,
            "expires": String(expires),
            "signature": signature,
            "json": payload,
        ])
        return request
    }

    func makePointRequest(
        remoteID: String,
        observation: RidObservation,
        cameraMetadata: CaltopoCameraMetadata? = nil
    ) throws -> URLRequest {
        var components = URLComponents()
        components.scheme = "https"
        components.host = hostAndPort.host
        components.port = hostAndPort.port
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        guard let encodedConnectKey = effectiveConnectKey.addingPercentEncoding(withAllowedCharacters: allowed) else {
            throw CaltopoLiveClientError.invalidURL
        }
        components.percentEncodedPath = "/api/v1/position/report/\(encodedConnectKey)"
        var queryItems = [
            URLQueryItem(name: "id", value: remoteID),
            URLQueryItem(name: "lat", value: String(format: "%.7f", observation.latitude)),
            URLQueryItem(name: "lng", value: String(format: "%.7f", observation.longitude)),
            URLQueryItem(name: "elevation", value: String(Int64(observation.altitudeMeters ?? -1_000))),
        ]
        if let altitudeMeters = observation.altitudeMeters,
           altitudeMeters.isFinite,
           altitudeMeters != -1_000 {
            let altitudeFeet = Int64((altitudeMeters * 3.280_839_895_013_123).rounded())
            queryItems.append(URLQueryItem(
                name: "aircraft:altitude",
                value: String(altitudeFeet)
            ))
        }
        if let speed = observation.speedMetersPerSecond, speed.isFinite {
            queryItems.append(URLQueryItem(
                name: "aircraft:gs",
                value: String(speed * 1.943_844_49)
            ))
        }
        if let heading = observation.headingDegrees, heading.isFinite {
            queryItems.append(URLQueryItem(name: "aircraft:track", value: String(heading)))
        }
        if let cameraMetadata {
            queryItems.append(URLQueryItem(
                name: "camera:external_url",
                value: cameraMetadata.externalURL.absoluteString
            ))
            if let thumbnailURL = cameraMetadata.thumbnailURL {
                queryItems.append(URLQueryItem(
                    name: "camera:thumbnail_url",
                    value: thumbnailURL.absoluteString
                ))
            }
            if let azimuth = cameraMetadata.azimuthDegrees, azimuth.isFinite {
                queryItems.append(URLQueryItem(name: "camera:azimuth", value: String(azimuth)))
            }
            if let tilt = cameraMetadata.tiltDegrees, tilt.isFinite {
                queryItems.append(URLQueryItem(name: "camera:tilt", value: String(tilt)))
            }
            if let width = cameraMetadata.horizontalFovDegrees, width.isFinite {
                queryItems.append(URLQueryItem(name: "camera:fov_width", value: String(width)))
            }
            if let height = cameraMetadata.verticalFovDegrees, height.isFinite {
                queryItems.append(URLQueryItem(name: "camera:fov_height", value: String(height)))
            }
        }
        components.queryItems = queryItems
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    private var effectiveConnectKey: String {
        let connectKey = configuration.connectKey.trimmingCharacters(in: .whitespacesAndNewlines)
        return connectKey.isEmpty ? "DRONE" : connectKey
    }

    func makeStopLiveTrackRequest(liveTrackID: String, now: Date) throws -> URLRequest {
        guard !liveTrackID.isEmpty else { throw CaltopoLiveClientError.missingLiveTrackID }
        let path = "/api/v1/map/\(configuration.mapID)/LiveTrack/\(liveTrackID)"
        let expires = Int64(now.timeIntervalSince1970 * 1_000)
            + CaltopoRequestSigner.validityMilliseconds
        let signature = try CaltopoRequestSigner.signature(
            method: "DELETE",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.percentEncodedQuery = CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ])
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    func makeDeleteFolderRequest(folderID: String, now: Date) throws -> URLRequest {
        guard !folderID.isEmpty else { throw CaltopoLiveClientError.missingResult }
        let path = "/api/v1/map/\(configuration.mapID)/Folder/\(folderID)"
        let expires = Int64(now.timeIntervalSince1970 * 1_000)
            + CaltopoRequestSigner.validityMilliseconds
        let signature = try CaltopoRequestSigner.signature(
            method: "DELETE",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.percentEncodedQuery = CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ])
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    private func perform(
        _ request: URLRequest,
        acceptedStatusCodes: Set<Int> = []
    ) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw CaltopoLiveClientError.httpStatus(0, "Non-HTTP response")
        }
        guard (200 ..< 300).contains(httpResponse.statusCode)
                || acceptedStatusCodes.contains(httpResponse.statusCode)
        else {
            throw CaltopoLiveClientError.httpStatus(
                httpResponse.statusCode,
                String(decoding: data.prefix(1_024), as: UTF8.self)
            )
        }
        return data
    }

    private func httpsURL(path: String) -> URL? {
        var components = URLComponents()
        components.scheme = "https"
        components.host = hostAndPort.host
        components.port = hostAndPort.port
        components.path = path
        return components.url
    }

    private var hostAndPort: (host: String, port: Int?) {
        let parts = configuration.domainAndPort.split(separator: ":", maxSplits: 1)
        return (String(parts[0]), parts.count == 2 ? Int(parts[1]) : nil)
    }

    private static func formBody(_ fields: [String: String]) -> Data {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._*"))
        let body = fields.keys.sorted().map { key in
            let value = fields[key] ?? ""
            let encodedKey = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let encodedValue = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(encodedKey)=\(encodedValue)"
        }.joined(separator: "&")
        return Data(body.utf8)
    }
}
