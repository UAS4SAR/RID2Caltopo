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

public struct CaltopoLiveConfiguration: Sendable, Equatable, Hashable {
    public let domainAndPort: String
    public let mapID: String
    public let credentialID: String
    public let credentialSecretBase64: String
    public let personalSessionID: UUID?
    public let personalAccountID: String
    public let personalMediaOwnerID: String
    public let connectKey: String
    /// HMAC credential for this pilot's CalTopo account. Cookie login stays on personalSessionID.
    public let personalSignedAccount: Bool

    public var authorizesAsPersonalAccount: Bool { personalSessionID != nil || personalSignedAccount }

    public var credentialKind: String {
        if personalSignedAccount { return "personal" }
        if personalSessionID != nil { return "session" }
        return "team"
    }

    public init(
        domainAndPort: String = "caltopo.com",
        mapID: String,
        credentialID: String,
        credentialSecretBase64: String,
        connectKey: String = "",
        personalSessionID: UUID? = nil,
        personalAccountID: String = "",
        personalMediaOwnerID: String = "",
        personalSignedAccount: Bool = false
    ) {
        self.domainAndPort = domainAndPort
        self.mapID = mapID
        self.credentialID = credentialID
        self.credentialSecretBase64 = credentialSecretBase64
        self.personalSessionID = personalSessionID
        self.personalAccountID = personalAccountID
        self.personalMediaOwnerID = personalMediaOwnerID
        self.connectKey = connectKey
        self.personalSignedAccount = personalSignedAccount
    }
}

public enum CaltopoServerAvailability {
    public static func isUnavailable(status: Int, timedOut: Bool) -> Bool {
        timedOut || status == 408 || status >= 500
    }

    public static func message(status: Int, personal: Bool, timedOut: Bool) -> String {
        if status == 401 || status == 403 {
            return personal
                ? "Personal map request failed; check your login and map permissions. Team credentials were not used."
                : "CalTopo request failed; check your login and map permissions."
        }
        if isUnavailable(status: status, timedOut: timedOut) {
            let detail = timedOut && status <= 0 ? "timed out" : "HTTP \(status)"
            return "CalTopo server unavailable (\(detail)), retrying..."
        }
        return personal ? "Personal map request failed." : "CalTopo request failed."
    }

    public static func retryMessage(_ error: Error) -> String? {
        if let urlError = error as? URLError, urlError.code == .timedOut {
            return message(status: 0, personal: false, timedOut: true)
        }
        guard case let CaltopoLiveClientError.httpStatus(code, text) = error else { return nil }
        if text.hasPrefix("CalTopo server unavailable") { return text }
        if isUnavailable(status: code, timedOut: false) { return message(status: code, personal: false, timedOut: false) }
        return nil
    }
}

public enum CaltopoLiveClientError: Error, Sendable, Equatable {
    case invalidConfiguration
    case invalidCredentialSecret
    case invalidURL
    case httpStatus(Int, String)
    case missingResult
    case missingLiveTrackID
    case mismatchedLiveTrackID
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
        case let .httpStatus(_, message):
            let detail = message.trimmingCharacters(in: .whitespacesAndNewlines)
            if detail.hasPrefix("CalTopo server unavailable")
                || detail.hasPrefix("Personal map request failed")
                || detail.hasPrefix("CalTopo request failed") {
                return detail
            }
            return detail.isEmpty ? "CalTopo returned an unexpected response." : detail
        case .missingResult:
            return "CalTopo returned a response without the expected result."
        case .mismatchedLiveTrackID:
            return "CalTopo did not confirm the requested LiveTrack ID; publication paused."
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
    private let personalTransport: URLSession?
    private let fullMapReads: CaltopoFullMapReads
    private let snapshotReads: CaltopoSnapshotReads
    /// Separate lanes so Shape archive / DELETE and clue media POSTs cannot
    /// occupy the same serial executor as LiveTrack create and position reports.
    private let startLane: CaltopoNetworkLane
    private let archiveLane: CaltopoNetworkLane
    private let clueLane: CaltopoNetworkLane
    private static let positionReports = LatestPositionReports(intervalProvider: {
        let stored = UserDefaults.standard.object(forKey: OperationalThumbnailRefreshInterval.storageKey) as? Double
        return .seconds(OperationalThumbnailRefreshInterval.normalized(stored))
    })
    private var positionReportKeys: Set<String> = []
    private var stoppedLiveTrackIDs: Set<String> = []
    /// Bound archive/clue/LiveTrack JSON attempts so a dead path cannot stall for minutes.
    /// Photo `/data` uploads use `CluePhotoUploadPolicy.photoUploadTimeoutSeconds`.
    public static let requestTimeoutSeconds: TimeInterval = CluePhotoUploadPolicy.jsonTimeoutSeconds
    private let ownsSession: Bool

    public init(
        configuration: CaltopoLiveConfiguration,
        session: URLSession? = nil,
        fullMapReads: CaltopoFullMapReads = .shared,
        snapshotReads: CaltopoSnapshotReads? = nil
    ) throws {
        guard !configuration.domainAndPort.isEmpty,
              !configuration.mapID.isEmpty,
              (configuration.personalSessionID != nil || (!configuration.credentialID.isEmpty &&
              !configuration.credentialSecretBase64.isEmpty)),
              !configuration.personalSignedAccount || (configuration.personalSessionID == nil
                && !configuration.personalAccountID.isEmpty
                && CaltopoPersonalAccountConnection.allowsPersonalHost(configuration.domainAndPort))
        else {
            throw CaltopoLiveClientError.invalidConfiguration
        }
        self.configuration = configuration
        let resolvedSession: URLSession
        if let session {
            resolvedSession = session
            ownsSession = false
        } else {
            let transport = URLSessionConfiguration.ephemeral
            transport.timeoutIntervalForRequest = Self.requestTimeoutSeconds
            transport.timeoutIntervalForResource = Self.requestTimeoutSeconds
            transport.waitsForConnectivity = false
            resolvedSession = URLSession(configuration: transport)
            ownsSession = true
        }
        self.session = resolvedSession
        self.fullMapReads = fullMapReads
        self.snapshotReads = snapshotReads ?? (resolvedSession === URLSession.shared ? .shared : CaltopoSnapshotReads())
        self.startLane = CaltopoNetworkLane(session: resolvedSession)
        self.archiveLane = CaltopoNetworkLane(session: resolvedSession)
        self.clueLane = CaltopoNetworkLane(session: resolvedSession)
        if configuration.personalSessionID != nil, session == nil {
            // Production: cookie-isolated ephemeral session. Injected test sessions
            // must keep their protocolClasses, so personalTransport stays nil and
            // perform() uses the caller-provided session (personalTransport ?? session).
            let transport = URLSessionConfiguration.ephemeral
            transport.timeoutIntervalForRequest = Self.requestTimeoutSeconds
            transport.timeoutIntervalForResource = Self.requestTimeoutSeconds
            transport.waitsForConnectivity = false
            transport.httpCookieStorage = nil
            transport.httpShouldSetCookies = false
            transport.urlCache = nil
            personalTransport = URLSession(configuration: transport)
        } else { personalTransport = nil }
    }

    deinit {
        personalTransport?.invalidateAndCancel()
        if ownsSession { session.invalidateAndCancel() }
    }

    /// Connectivity restored: cancel hung archive/clue/LiveTrack tasks started on a dead path.
    public func cancelInFlightForConnectivityRestore() async {
        await startLane.cancelOutstanding()
        await archiveLane.cancelOutstanding()
        await clueLane.cancelOutstanding()
        if let personalTransport {
            let tasks = await personalTransport.allTasks
            tasks.forEach { $0.cancel() }
        }
        let tasks = await session.allTasks
        tasks.forEach { $0.cancel() }
    }

    public func startLiveTrack(
        liveTrackID: String,
        remoteID: String,
        label: String,
        folderID: String? = nil,
        now: Date = Date()
    ) async throws -> String {
        let request = try makeStartLiveTrackRequest(
            liveTrackID: liveTrackID,
            remoteID: remoteID,
            label: label,
            folderID: folderID,
            now: now
        )
        let data = try await perform(request, lane: startLane)
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let result = root?["result"] as? [String: Any] else {
            throw CaltopoLiveClientError.missingResult
        }
        guard let id = result["id"] as? String, id.lowercased() == liveTrackID.lowercased() else {
            throw CaltopoLiveClientError.mismatchedLiveTrackID
        }
        return liveTrackID
    }

    @discardableResult
    public func publishPoint(
        remoteID: String,
        observation: RidObservation,
        cameraMetadata: CaltopoCameraMetadata? = nil
    ) async throws -> Bool {
        let request = try makePointRequest(remoteID: remoteID, observation: observation,
                                           cameraMetadata: cameraMetadata)
        let key = positionReportKey(remoteID)
        positionReportKeys.insert(key)
        return try await Self.positionReports.submit(key: key) {
            _ = try await self.perform(request, lane: self.startLane)
        }
    }

    private func positionReportKey(_ remoteID: String) -> String {
        "\(configuration.domainAndPort):\(effectiveConnectKey):\(remoteID)"
    }

    public func stopPositionReports(remoteID: String) async {
        await Self.positionReports.cancel(key: positionReportKey(remoteID))
    }

    public func stopAllPositionReports() async {
        for key in positionReportKeys { await Self.positionReports.cancel(key: key) }
        positionReportKeys.removeAll()
    }

    public func stopLiveTrack(liveTrackID: String, now: Date = Date()) async throws {
        guard !liveTrackID.isEmpty else { throw CaltopoLiveClientError.missingLiveTrackID }
        // Archive and interrupted-track recovery both stop the same LiveTrack.
        // A second DELETE is the 400 that follows a successful 200.
        guard reserveLiveTrackStop(liveTrackID: liveTrackID) else {
            CaltopoPersonalAccountConnection.logState("live track delete already sent id=\(liveTrackID)")
            return
        }
        do {
            _ = try await perform(
                makeStopLiveTrackRequest(liveTrackID: liveTrackID, now: now),
                acceptedStatusCodes: [400, 404],
                lane: archiveLane
            )
        } catch {
            releaseLiveTrackStop(liveTrackID: liveTrackID)
            throw error
        }
    }

    /// Returns false when this LiveTrack DELETE was already sent or is in flight.
    func reserveLiveTrackStop(liveTrackID: String) -> Bool {
        let key = liveTrackID.lowercased()
        if key.isEmpty || stoppedLiveTrackIDs.contains(key) { return false }
        stoppedLiveTrackIDs.insert(key)
        return true
    }

    /// Lets a failed DELETE be retried. Success and already-gone stay reserved.
    func releaseLiveTrackStop(liveTrackID: String) {
        stoppedLiveTrackIDs.remove(liveTrackID.lowercased())
    }

    public func fetchMapArtifacts(now: Date = Date(), refresh: Bool = false) async throws -> CaltopoArtifactSnapshot {
        let data = try await mapData(sinceMilliseconds: 0, now: now, refresh: refresh)
        return try CaltopoArtifactDecoder.decode(data: data)
    }

    public func fetchMapArtifactChanges(
        sinceMilliseconds: Int64,
        now: Date = Date(),
        forceFullRead: Bool = false
    ) async throws -> CaltopoArtifactChanges {
        let data = try await mapData(sinceMilliseconds: sinceMilliseconds, now: now, forceFullRead: forceFullRead)
        return try CaltopoArtifactDecoder.decodeChanges(data: data)
    }

    private func mapData(sinceMilliseconds: Int64, now: Date, forceFullRead: Bool = false, refresh: Bool = false) async throws -> Data {
        if sinceMilliseconds <= 500 {
            return try await snapshotReads.read(configuration: configuration, forceFull: forceFullRead, refresh: refresh,
                validate: { try await self.validateSnapshotAccess() }, load: { cursor in
                    if cursor <= 500 {
                        let mapRequest = try await self.makeMapChangesRequest(sinceMilliseconds: 0, now: Date())
                        let mapURL = mapRequest.url!
                        CaltopoPersonalAccountConnection.logState("map open dispatch GET \(mapURL.path) credential=\(self.configuration.credentialKind)")
                        return try await self.fullMapReads.perform(mapURL: mapURL) {
                            try Task.checkCancellation()
                            return try await self.sendMapRead(sinceMilliseconds: 0, now: Date())
                        }
                    }
                    return try await self.sendMapRead(sinceMilliseconds: cursor, now: Date())
                })
        }
        return try await sendMapRead(sinceMilliseconds: sinceMilliseconds, now: now)
    }
    private func validateSnapshotAccess() async throws {
        try Task.checkCancellation()
        // A signed personal account is authorized by HMAC. The cookie session is not consulted.
        guard CaltopoPersonalAccountConnection.requiresCookieValidation(
            personalSessionID: configuration.personalSessionID,
            personalSignedAccount: configuration.personalSignedAccount
        ), let token = configuration.personalSessionID else { return }
        let url = try makeMapChangesRequest(sinceMilliseconds: 0, now: Date()).url!
        _ = try await CaltopoPersonalSessions.shared.cookie(token, url: url)
    }
    public func markerPresence(markerID: String, sinceMilliseconds: Int64) async throws -> (absent: Bool, present: Bool) {
        guard sinceMilliseconds > 500 else { throw CaltopoLiveClientError.invalidConfiguration }
        let data = try await sendMapRead(sinceMilliseconds: sinceMilliseconds, now: Date())
        return (CaltopoMarkerVerification.confirmsAbsent(data: data, markerID: markerID),
                CaltopoMarkerVerification.confirmsPresent(data: data, markerID: markerID))
    }
    private func sendMapRead(sinceMilliseconds: Int64, now: Date) async throws -> Data {
        // Build signatures and resolve personal authorization after any cooldown.
        try await perform(makeMapChangesRequest(sinceMilliseconds: sinceMilliseconds, now: now))
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
        ), lane: archiveLane)
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
        ), lane: archiveLane)
    }

    /// Edit an already-published photo-clue marker position (same marker id; never duplicates).
    public func updatePhotoClueMarker(
        markerID: String,
        latitude: Double,
        longitude: Double,
        title: String,
        description: String,
        folderID: String?,
        createdMilliseconds: Int64,
        now: Date = Date()
    ) async throws {
        let normalizedID = markerID.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalizedID.isEmpty,
              latitude.isFinite, longitude.isFinite,
              !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { throw CaltopoLiveClientError.invalidConfiguration }
        let markerPath = "/api/v1/map/\(configuration.mapID)/Marker/\(normalizedID)"
        var markerProperties: [String: Any] = [
            "class": "Marker",
            "updated": Int64(now.timeIntervalSince1970 * 1_000),
            "created": createdMilliseconds,
            "title": title,
            "description": description,
            "marker-color": "#FF0000",
            "marker-symbol": "Drone",
            "marker-size": "1",
            "marker-visibility": "visible",
        ]
        if let folderID = folderID?.trimmingCharacters(in: .whitespacesAndNewlines), !folderID.isEmpty {
            markerProperties["folderId"] = folderID
        }
        let markerPayload: [String: Any] = [
            "id": normalizedID,
            "type": "Feature",
            "geometry": [
                "type": "Point",
                "coordinates": [longitude, latitude],
            ],
            "properties": markerProperties,
        ]
        let data = try await perform(makeSignedPostRequest(path: markerPath, object: markerPayload, now: now), lane: clueLane)
        guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              envelope["status"] as? String == "ok", envelope["error"] == nil
        else { throw CaltopoLiveClientError.httpStatus(502, "Marker position edit was not acknowledged.") }
    }

    public func publishPhotoClue(_ clue: CaltopoPhotoClue, now: Date = Date()) async throws -> String {
        let requests = try makePhotoClueRequests(clue, now: now)
        if let id = configuration.personalSessionID {
            try await CaltopoPersonalSessions.shared.authorizeMedia(id, mediaID: clue.mediaID)
        }
        let markerID = clue.markerID.uuidString.lowercased()
        let mediaID = clue.mediaID.uuidString.lowercased()
        let photoTimeout = CluePhotoUploadPolicy.photoUploadTimeoutSeconds(jpegBytes: clue.jpegData.count)
        // Clue media must not share startLane: 502 retries would HOL-block LiveTrack create/points.
        func acknowledged(
            _ request: URLRequest,
            timeout: TimeInterval = Self.requestTimeoutSeconds
        ) async throws -> Data {
            let data = try await perform(request, lane: clueLane, timeout: timeout)
            guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  envelope["status"] as? String == "ok", envelope["error"] == nil
            else { throw CaltopoLiveClientError.httpStatus(502, "Photo upload was not acknowledged; retained for retry.") }
            return data
        }
        do {
            _ = try await acknowledged(requests[0])
        } catch let markerPostError {
            // Prior attempt may have created the Marker then failed media. Attach to it.
            do {
                _ = try await acknowledged(makeMarkerSnapshotRequest(markerID: markerID, now: now))
            } catch {
                throw markerPostError
            }
        }
        var mediaExists = false
        var mediaReady = false
        let mediaOwner = configuration.authorizesAsPersonalAccount ? configuration.personalMediaOwnerID : clue.teamID
        do {
            // Probe with perform — not acknowledged — so a CalTopo 400/404 "absent"
            // stays 400/404 instead of being rewritten into a false 502 envelope failure.
            // Probe 400/404 is not a store-level permanent stop; media is simply absent.
            let data = try await perform(makeMediaSnapshotRequest(mediaID: clue.mediaID, now: now), lane: clueLane)
            guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  envelope["status"] as? String == "ok", envelope["error"] == nil
            else { throw CaltopoLiveClientError.httpStatus(502, "Photo upload was not acknowledged; retained for retry.") }
            mediaReady = try CaltopoPhotoMediaState.isReady(data, mediaID: clue.mediaID, ownerID: mediaOwner)
            mediaExists = true
        } catch CaltopoLiveClientError.httpStatus(let code, _) where code == 400 || code == 404 {
            // Media has not been created yet. Other errors must retain the clue for retry.
        }
        if !mediaExists {
            do {
                _ = try await acknowledged(requests[1])
                mediaExists = true
            } catch let CaltopoLiveClientError.httpStatus(code, message)
                where CluePhotoUploadPolicy.isExistingBackendMediaError(statusCode: code, message: message)
            {
                // Timed-out create may have landed, or CalTopo retained a bad id.
                // Verify; if GET 404 / not ready, force a new media id (never loop on 400+404).
                do {
                    let data = try await perform(makeMediaSnapshotRequest(mediaID: clue.mediaID, now: now), lane: clueLane)
                    mediaReady = try CaltopoPhotoMediaState.isReady(data, mediaID: clue.mediaID, ownerID: mediaOwner)
                    mediaExists = true
                    if !mediaReady {
                        throw CaltopoLiveClientError.httpStatus(
                            409,
                            "\(CluePhotoUploadPolicy.existingBackendMediaToken); \(CluePhotoUploadPolicy.ambiguousMediaStateToken)"
                        )
                    }
                } catch let CaltopoLiveClientError.httpStatus(verifyCode, _)
                    where verifyCode == 400 || verifyCode == 404
                {
                    throw CaltopoLiveClientError.httpStatus(
                        409,
                        "\(CluePhotoUploadPolicy.existingBackendMediaToken); \(CluePhotoUploadPolicy.ambiguousMediaStateToken)"
                    )
                }
            }
        }
        if mediaExists && !mediaReady {
            do {
                _ = try await acknowledged(requests[2], timeout: photoTimeout)
                mediaReady = true
            } catch let CaltopoLiveClientError.httpStatus(code, message)
                where CluePhotoUploadPolicy.isExistingBackendMediaError(statusCode: code, message: message)
            {
                do {
                    let data = try await perform(makeMediaSnapshotRequest(mediaID: clue.mediaID, now: now), lane: clueLane)
                    if try CaltopoPhotoMediaState.isReady(data, mediaID: clue.mediaID, ownerID: mediaOwner) {
                        mediaReady = true
                    } else {
                        throw CaltopoLiveClientError.httpStatus(
                            409,
                            "\(CluePhotoUploadPolicy.existingBackendMediaToken); \(CluePhotoUploadPolicy.ambiguousMediaStateToken)"
                        )
                    }
                } catch let CaltopoLiveClientError.httpStatus(verifyCode, _)
                    where verifyCode == 400 || verifyCode == 404
                {
                    throw CaltopoLiveClientError.httpStatus(
                        409,
                        "\(CluePhotoUploadPolicy.existingBackendMediaToken); \(CluePhotoUploadPolicy.ambiguousMediaStateToken)"
                    )
                }
            }
        }
        // MapMediaObject POST is not always idempotent; probe / recover so retries do not 400.
        var linkPresent = false
        do {
            let data = try await perform(
                makeMapMediaObjectSnapshotRequest(mediaID: mediaID, now: now),
                lane: clueLane
            )
            if let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
               envelope["status"] as? String == "ok", envelope["error"] == nil {
                linkPresent = true
            }
        } catch CaltopoLiveClientError.httpStatus(let code, _) where code == 400 || code == 404 {
            linkPresent = false
        }
        if !linkPresent {
            do {
                _ = try await acknowledged(requests[3])
            } catch let linkError {
                do {
                    let data = try await perform(
                        makeMapMediaObjectSnapshotRequest(mediaID: mediaID, now: now),
                        lane: clueLane
                    )
                    guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                          envelope["status"] as? String == "ok", envelope["error"] == nil
                    else { throw linkError }
                } catch {
                    throw linkError
                }
            }
        }
        return markerID
    }

    public struct PhotoClueParts: Sendable, Equatable {
        public var markerPresent: Bool
        public var mediaReady: Bool
        public var linkPresent: Bool

        public var allPresent: Bool { markerPresent && mediaReady && linkPresent }
        public var anyPresent: Bool { markerPresent || mediaReady || linkPresent }
    }

    /// Probe Marker + media readiness + MapMediaObject link without mutating the map.
    public func probePhotoClueParts(
        markerID: UUID,
        mediaID: UUID,
        teamID: String,
        now: Date = Date()
    ) async throws -> PhotoClueParts {
        let featureID = markerID.uuidString.lowercased()
        let mediaKey = mediaID.uuidString.lowercased()
        var markerPresent = false
        do {
            _ = try await perform(makeMarkerSnapshotRequest(markerID: featureID, now: now), lane: clueLane)
            markerPresent = true
        } catch CaltopoLiveClientError.httpStatus(let code, _) where code == 400 || code == 404 {
            // Single-entity GET only — never fall back to a gated full-map since/0 read.
            markerPresent = false
        }
        var mediaReady = false
        let mediaOwner = configuration.authorizesAsPersonalAccount ? configuration.personalMediaOwnerID : teamID
        do {
            let data = try await perform(makeMediaSnapshotRequest(mediaID: mediaID, now: now), lane: clueLane)
            mediaReady = try CaltopoPhotoMediaState.isReady(data, mediaID: mediaID, ownerID: mediaOwner)
        } catch CaltopoLiveClientError.httpStatus(let code, _) where code == 400 || code == 404 {
            mediaReady = false
        }
        var linkPresent = false
        do {
            _ = try await perform(
                makeMapMediaObjectSnapshotRequest(mediaID: mediaKey, now: now),
                lane: clueLane
            )
            linkPresent = true
        } catch CaltopoLiveClientError.httpStatus(let code, _) where code == 400 || code == 404 {
            linkPresent = false
        }
        return PhotoClueParts(markerPresent: markerPresent, mediaReady: mediaReady, linkPresent: linkPresent)
    }

    /// Verify every part; when anything is already on the map, complete missing parts idempotently.
    /// Returns nil only when nothing of this clue exists yet (caller should publish from scratch).
    public func reconcilePhotoClue(
        _ clue: CaltopoPhotoClue,
        now: Date = Date()
    ) async throws -> (featureID: String, partsLog: String)? {
        let before = try await probePhotoClueParts(
            markerID: clue.markerID,
            mediaID: clue.mediaID,
            teamID: clue.teamID,
            now: now
        )
        if !before.anyPresent { return nil }
        if !before.allPresent {
            _ = try await publishPhotoClue(clue, now: now)
        }
        let after = try await probePhotoClueParts(
            markerID: clue.markerID,
            mediaID: clue.mediaID,
            teamID: clue.teamID,
            now: now
        )
        guard after.markerPresent else { return nil }
        let mediaToken = partTransition(before: before.mediaReady, after: after.mediaReady, uploadedLabel: "uploaded")
        let linkToken = partTransition(before: before.linkPresent, after: after.linkPresent, uploadedLabel: "linked")
        let markerToken = after.markerPresent ? (before.markerPresent ? "ok" : "created") : "missing"
        let partsLog =
            "Clue reconcile parts marker=\(markerToken) media=\(mediaToken) link=\(linkToken)"
        guard after.mediaReady, after.linkPresent else {
            // Marker alone must not clear pending — photo/link still missing.
            return nil
        }
        return (clue.markerID.uuidString.lowercased(), partsLog)
    }

    private func partTransition(before: Bool, after: Bool, uploadedLabel: String) -> String {
        switch (before, after) {
        case (true, true): return "ok"
        case (false, true): return "missing->\(uploadedLabel)"
        case (true, false): return "ok->missing"
        case (false, false): return "missing"
        }
    }

    /// Compatibility wrapper used by older call sites that only knew the ids.
    public func reconcilePhotoClue(
        markerID: UUID,
        mediaID: UUID,
        now: Date = Date()
    ) async throws -> String? {
        let parts = try await probePhotoClueParts(
            markerID: markerID,
            mediaID: mediaID,
            teamID: configuration.authorizesAsPersonalAccount
                ? "personal:" + configuration.personalAccountID
                : "",
            now: now
        )
        // Marker-only is not success — media/link must also be present.
        guard parts.allPresent else { return nil }
        return markerID.uuidString.lowercased()
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
        let signature = try configuration.personalSessionID == nil ? CaltopoRequestSigner.signature(
            method: "DELETE",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        ) : ""
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.percentEncodedQuery = configuration.personalSessionID == nil ? CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ]) : nil
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    func makeMediaSnapshotRequest(mediaID: UUID, now: Date) throws -> URLRequest {
        try makeSignedGetRequest(path: "/api/v1/media/\(mediaID.uuidString.lowercased())", now: now)
    }

    func makeMarkerSnapshotRequest(markerID: String, now: Date) throws -> URLRequest {
        try makeSignedGetRequest(path: "/api/v1/map/\(configuration.mapID)/Marker/\(markerID)", now: now)
    }

    func makeMapMediaObjectSnapshotRequest(mediaID: String, now: Date) throws -> URLRequest {
        try makeSignedGetRequest(
            path: "/api/v1/map/\(configuration.mapID)/MapMediaObject/\(mediaID)",
            now: now
        )
    }

    private func makeSignedGetRequest(path: String, now: Date) throws -> URLRequest {
        guard let url = httpsURL(path: path), var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        if configuration.personalSessionID == nil {
            let expires = Int64(now.timeIntervalSince1970 * 1000) + CaltopoRequestSigner.validityMilliseconds
            let signature = try CaltopoRequestSigner.signature(
                method: "GET", path: path, expiresMilliseconds: expires, payload: "",
                credentialSecretBase64: configuration.credentialSecretBase64)
            components.percentEncodedQuery = CaltopoRequestSigner.percentEncodedQuery([
                ("id", configuration.credentialID),
                ("expires", String(expires)),
                ("signature", signature),
            ])
        }
        return URLRequest(url: components.url!)
    }

    func makePhotoClueRequests(_ clue: CaltopoPhotoClue, now: Date) throws -> [URLRequest] {
        if configuration.authorizesAsPersonalAccount {
            guard !configuration.personalAccountID.isEmpty, !configuration.personalMediaOwnerID.isEmpty,
                  clue.teamID == "personal:" + configuration.personalAccountID else { throw CaltopoLiveClientError.invalidConfiguration }
        }
        let mediaOwner = configuration.authorizesAsPersonalAccount ? configuration.personalMediaOwnerID : clue.teamID
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
            // Stable client id for retry reconciliation (same Feature id as the path).
            "r2c-clue-id": markerID,
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
        let mediaPayload: [String: Any] = ["properties": ["creator": mediaOwner]]
        let dataPath = mediaPath + "/data"
        let dataPayload: [String: Any] = [
            "creator": mediaOwner,
            "data": clue.jpegData.base64EncodedString(),
        ]
        let linkPath = "/api/v1/map/\(configuration.mapID)/MapMediaObject/\(mediaID)"
        let linkPayload: [String: Any] = [
            "id": mediaID,
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
        let signature = try configuration.personalSessionID == nil ? CaltopoRequestSigner.signature(
            method: "GET",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        ) : ""
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.percentEncodedQuery = configuration.personalSessionID == nil ? CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ]) : nil
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    func makeStartLiveTrackRequest(
        liveTrackID: String,
        remoteID: String,
        label: String,
        folderID: String?,
        now: Date
    ) throws -> URLRequest {
        guard UUID(uuidString: liveTrackID) != nil else { throw CaltopoLiveClientError.missingLiveTrackID }
        let path = "/api/v1/map/\(configuration.mapID)/LiveTrack/\(liveTrackID)"
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
            withJSONObject: ["id": liveTrackID, "type": "Feature", "properties": properties],
            options: [.sortedKeys]
        )
        let payload = String(decoding: payloadData, as: UTF8.self)
        let expires = Int64(now.timeIntervalSince1970 * 1_000)
            + CaltopoRequestSigner.validityMilliseconds
        let signature = try configuration.personalSessionID == nil ? CaltopoRequestSigner.signature(
            method: "POST",
            path: path,
            expiresMilliseconds: expires,
            payload: payload,
            credentialSecretBase64: configuration.credentialSecretBase64
        ) : ""
        guard let url = httpsURL(path: path) else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody(configuration.personalSessionID != nil ? ["json": payload] : [
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
        let signature = try configuration.personalSessionID == nil ? CaltopoRequestSigner.signature(
            method: "POST",
            path: path,
            expiresMilliseconds: expires,
            payload: payload,
            credentialSecretBase64: configuration.credentialSecretBase64
        ) : ""
        guard let url = httpsURL(path: path) else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody(configuration.personalSessionID != nil ? ["json": payload] : [
            "id": configuration.credentialID,
            "expires": String(expires),
            "signature": signature,
            "json": payload,
        ])
        return request
    }

    /// The connect key in the path authorizes this GET for team and personal credentials.
    /// Query `id` is the device id, so the request stays unsigned.
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
        let signature = try configuration.personalSessionID == nil ? CaltopoRequestSigner.signature(
            method: "DELETE",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        ) : ""
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.percentEncodedQuery = configuration.personalSessionID == nil ? CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ]) : nil
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
        let signature = try configuration.personalSessionID == nil ? CaltopoRequestSigner.signature(
            method: "DELETE",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        ) : ""
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.percentEncodedQuery = configuration.personalSessionID == nil ? CaltopoRequestSigner.percentEncodedQuery([
            ("id", configuration.credentialID),
            ("expires", String(expires)),
            ("signature", signature),
        ]) : nil
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    private func perform(
        _ request: URLRequest,
        acceptedStatusCodes: Set<Int> = [],
        lane: CaltopoNetworkLane? = nil,
        timeout: TimeInterval = requestTimeoutSeconds
    ) async throws -> Data {
        var request = request
        request.timeoutInterval = timeout
        var personalCookieURL: URL?
        var personalSendCookie = false
        // A signed personal account has no browser cookie. Position reports stay
        // unsigned connect-key GETs. Only a cookie session consults that cookie.
        if configuration.personalSessionID != nil {
            guard let url = request.url, url.scheme == "https", url.host == "caltopo.com", url.port == nil,
                  url.user == nil, url.password == nil,
                  url.path.hasPrefix("/api/v1/map/\(configuration.mapID)/") || url.path.hasPrefix("/api/v1/position/report/") || CaltopoPersonalProbe.mediaID(url.path) != nil
            else { throw CaltopoLiveClientError.invalidURL }
            let isPosition = url.path.hasPrefix("/api/v1/position/report/")
            let cookieURL = isPosition ? CaltopoPersonalProbe.endpoint(configuration.mapID)! : url
            personalCookieURL = cookieURL
            personalSendCookie = !isPosition
            request.httpShouldHandleCookies = false
            request.cachePolicy = .reloadIgnoringLocalCacheData
            if !isPosition {
                request.setValue("https://caltopo.com", forHTTPHeaderField: "Origin")
                request.setValue("https://caltopo.com/m/\(configuration.mapID)", forHTTPHeaderField: "Referer")
            }

        }
        let data: Data
        let response: URLResponse
        let credential = configuration.credentialKind
        let method = request.httpMethod ?? "GET"
        let path = request.url?.path ?? "/"
        let logTransport = credential != "team" || !path.hasPrefix("/api/v1/position/report/")
        let networkLane = lane ?? startLane
        do {
            if let personalID = configuration.personalSessionID, let cookieURL = personalCookieURL {
                (data, response) = try await CaltopoPersonalSessions.shared.perform(personalID, cookieURL: cookieURL,
                    request: request, session: personalTransport ?? session, sendCookie: personalSendCookie)
            } else {
                (data, response) = try await networkLane.data(for: request)
            }
        } catch {
            if logTransport {
                CaltopoPersonalAccountConnection.logRequest(method: method, path: path, status: 0, credential: credential)
            }
            if let urlError = error as? URLError, urlError.code == .timedOut {
                throw CaltopoLiveClientError.httpStatus(0, CaltopoServerAvailability.message(
                    status: 0, personal: configuration.authorizesAsPersonalAccount, timedOut: true))
            }
            throw error
        }
        guard let httpResponse = response as? HTTPURLResponse else {
            if logTransport {
                CaltopoPersonalAccountConnection.logRequest(method: method, path: path, status: 0, credential: credential)
            }
            throw CaltopoLiveClientError.httpStatus(0, "Non-HTTP response")
        }
        if logTransport {
            CaltopoPersonalAccountConnection.logRequest(
                method: method, path: path, status: httpResponse.statusCode, credential: credential)
        }
        guard (200 ..< 300).contains(httpResponse.statusCode)
                || acceptedStatusCodes.contains(httpResponse.statusCode)
        else {
            let base = CaltopoServerAvailability.message(
                status: httpResponse.statusCode,
                personal: configuration.authorizesAsPersonalAccount,
                timedOut: false)
            let preview = Self.responseBodyPreview(data)
            let detail = preview.isEmpty ? base : "\(base) body=\(preview)"
            CaltopoPersonalAccountConnection.logFailedRequest(
                method: method,
                path: path,
                status: httpResponse.statusCode,
                credential: credential
            )
            throw CaltopoLiveClientError.httpStatus(httpResponse.statusCode, detail)
        }
        return data
    }

    /// Truncated, secret-scrubbed body for 4xx/5xx diagnosis. Never logs activation codes or signing keys.
    static func responseBodyPreview(_ data: Data, limit: Int = 300) -> String {
        guard !data.isEmpty else { return "" }
        var text = String(decoding: data, as: UTF8.self)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let redactions: [(String, String)] = [
            (#"(?i)(signature=)[^&\s"]+"#, "$1[redacted]"),
            (#"(?i)("(?:signature|secret|activation(?:Code)?|credentialSecret)"\s*:\s*")[^"]*"#, "$1[redacted]\""),
            (#"(?i)(activation(?:Code)?=)[^&\s"]+"#, "$1[redacted]"),
            (#"(?i)(session=)[^;\s"]+"#, "$1[redacted]"),
        ]
        for (pattern, template) in redactions {
            text = text.replacingOccurrences(
                of: pattern,
                with: template,
                options: .regularExpression
            )
        }
        if text.count > limit {
            return String(text.prefix(limit)) + "…"
        }
        return text
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

/// Dedicated network executor so LiveTrack start/point POSTs never wait behind archive/DELETE.
actor CaltopoNetworkLane {
    private let session: URLSession
    private var generation = 0

    init(session: URLSession) {
        self.session = session
    }

    func cancelOutstanding() {
        generation &+= 1
        // URLSession tasks for this lane's session are cancelled by the client.
    }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        let startGeneration = generation
        let result = try await session.data(for: request)
        if startGeneration != generation {
            throw CancellationError()
        }
        return result
    }
}
