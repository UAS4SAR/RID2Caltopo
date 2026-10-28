import Foundation

public enum TrackerCoordinationWireError: Error, Sendable, Equatable {
    case invalidEndpoint
    case invalidMessage
    case missingMessageType
}

public struct TrackerCoordinationClient: Sendable, Equatable {
    public static let trackerFunctionalityRelease = 148
    public let mapID: String
    public let zoneID: String
    public let name: String
    public let deviceModel: String
    public let platform: String
    public let appVersion: String
    public let appVersionCode: Int

    public init(
        mapID: String,
        zoneID: String,
        name: String,
        deviceModel: String = "",
        platform: String = "",
        appVersion: String,
        appVersionCode: Int
    ) {
        self.mapID = mapID
        self.zoneID = zoneID
        self.name = name
        self.deviceModel = deviceModel
        self.platform = platform
        self.appVersion = appVersion
        self.appVersionCode = appVersionCode
    }
}

public struct TrackerCoordinationPosition: Sendable, Equatable {
    public let latitude: Double?
    public let longitude: Double?
    public let caltopoRTTMilliseconds: Int64

    public init(latitude: Double?, longitude: Double?, caltopoRTTMilliseconds: Int64 = 2_000) {
        if let latitude, let longitude, latitude.isFinite, longitude.isFinite,
           abs(latitude) <= 90, abs(longitude) <= 180 {
            self.latitude = latitude
            self.longitude = longitude
        } else {
            self.latitude = nil
            self.longitude = nil
        }
        self.caltopoRTTMilliseconds = caltopoRTTMilliseconds
    }
}

public struct TrackerCoordinationIdentity: Sendable, Equatable {
    public let remoteID: String
    public let mappedID: String
    public let organization: String
    public let model: String
    public let ownerName: String

    public init(remoteID: String, mappedID: String, organization: String, model: String, ownerName: String) {
        self.remoteID = remoteID
        self.mappedID = mappedID
        self.organization = organization
        self.model = model
        self.ownerName = ownerName
    }
}

public struct TrackerCoordinationSighting: Sendable, Equatable {
    public let identity: TrackerCoordinationIdentity
    public let droneTimestampMilliseconds: Int64
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let distanceFromZoneMeters: Double?
    public let headingDegrees: Double?
    public let groundSpeedKnots: Double?
    public let verticalRateFeetPerMinute: Double?

    public init(
        identity: TrackerCoordinationIdentity,
        droneTimestampMilliseconds: Int64,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double? = nil,
        distanceFromZoneMeters: Double? = nil,
        headingDegrees: Double? = nil,
        groundSpeedKnots: Double? = nil,
        verticalRateFeetPerMinute: Double? = nil
    ) {
        self.identity = identity
        self.droneTimestampMilliseconds = droneTimestampMilliseconds
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.distanceFromZoneMeters = distanceFromZoneMeters
        self.headingDegrees = headingDegrees
        self.groundSpeedKnots = groundSpeedKnots
        self.verticalRateFeetPerMinute = verticalRateFeetPerMinute
    }
}

public struct TrackerTrafficAltitudeCalibration: Sendable, Equatable {
    public let flightEpoch: String
    public let state: String
    public let reportedGroundAltitudeMeters: Double?
    public let correctionMeters: Double?
    public let lockedAtMilliseconds: Int64?
    public let demSource: String?
    public let demResolutionMeters: Double?

    public init(
        flightEpoch: String,
        state: String,
        reportedGroundAltitudeMeters: Double? = nil,
        correctionMeters: Double? = nil,
        lockedAtMilliseconds: Int64? = nil,
        demSource: String? = nil,
        demResolutionMeters: Double? = nil
    ) {
        self.flightEpoch = flightEpoch
        self.state = state
        self.reportedGroundAltitudeMeters = reportedGroundAltitudeMeters
        self.correctionMeters = correctionMeters
        self.lockedAtMilliseconds = lockedAtMilliseconds
        self.demSource = demSource
        self.demResolutionMeters = demResolutionMeters
    }

    public func normalizedMSLMeters(rawAltitudeMeters: Double?) -> Double? {
        guard state == "locked", let rawAltitudeMeters, rawAltitudeMeters.isFinite,
              let correctionMeters, correctionMeters.isFinite
        else { return nil }
        return rawAltitudeMeters + correctionMeters
    }

    public func reportedAltitudeMeters(relativeUpMeters: Double?) -> Double? {
        guard let reportedGroundAltitudeMeters, reportedGroundAltitudeMeters.isFinite,
              let relativeUpMeters, relativeUpMeters.isFinite
        else { return nil }
        return reportedGroundAltitudeMeters + relativeUpMeters
    }
}

public struct TrackerPeerZone: Sendable, Equatable, Identifiable {
    public var id: String { zoneID }
    public let zoneID: String
    public let name: String
    public let latitude: Double
    public let longitude: Double
    public let caltopoRTTMilliseconds: Int64
    public let lastSeenMilliseconds: Int64
    public let online: Bool
}

public struct TrackerRelaySighting: Sendable, Equatable {
    public let sourceZoneID: String
    public let remoteID: String
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let droneTimestampMilliseconds: Int64
    public let headingDegrees: Double?
    public let groundSpeedKnots: Double?
    public let verticalRateFeetPerMinute: Double?
}

public struct TrackerPeerTrafficPosition: Sendable, Equatable {
    public let sourceZoneID: String
    public let remoteID: String
    public let mappedID: String
    public let source: String
    public let sourceEpoch: String
    public let sequence: Int64
    public let sampleTimestampMilliseconds: Int64
    public let trackerReceivedTimestampMilliseconds: Int64
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let altitudeSampleTimestampMilliseconds: Int64?
    public let flightEpoch: String
    public let altitudeCalibrationState: String
    public let mslAltitudeMeters: Double?
    public let mslAltitudeSampleTimestampMilliseconds: Int64?
    public let altitudeCorrectionMeters: Double?
    public let demSource: String
    public let demResolutionMeters: Double?
    public let incidentPadFeet: Double?
    public let shadowNearestDistanceMeters: Double?
    public let shadowSchedulingPadFeet: Double?
    public let shadowIntervalMilliseconds: Int64?
    public let headingDegrees: Double?
    public let groundSpeedKnots: Double?
    public let verticalRateFeetPerMinute: Double?
}

public struct TrackerTrafficSchedule: Sendable, Equatable {
    public let remoteID: String
    public let source: String
    public let sourceEpoch: String
    public let sequence: Int64
    public let intervalMilliseconds: Int64
    public let incidentPadFeet: Double?
    public let nearestDistanceMeters: Double?
    public let schedulingPadFeet: Double?
}

public enum TrackerCoordinationEvent: Sendable, Equatable {
    case helloAcknowledged(recommendedAppVersionCode: Int?, updateURL: String?)
    case heartbeatAcknowledged(sequence: Int64, ownerLeaseExpiresAtMilliseconds: Int64?)
    case peersUpdated([TrackerPeerZone])
    case ownershipChanged(remoteID: String, ownerZoneID: String, localOwner: Bool, alertEligible: Bool)
    case ownerExpired(remoteID: String)
    case relaySighting(TrackerRelaySighting)
    case peerTrafficPosition(TrackerPeerTrafficPosition)
    case trafficSchedule(TrackerTrafficSchedule)
    case droneConfirmed(identity: TrackerCoordinationIdentity, confirmedByZoneID: String, confirmedLocally: Bool)
    case reconnectRequired(reason: String)
    case ignored(messageType: String)
}

public enum TrackerCoordinationEndpoint {
    public static func organizationScopedPrefix(
        from trackerURLPrefix: String,
        organization: String
    ) -> String {
        let trimmed = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed),
              components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).isEmpty
        else { return trimmed }
        let designator = organization
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        guard !designator.isEmpty else { return trimmed }
        components.path = "/\(designator)"
        return components.url?.absoluteString ?? trimmed
    }

    public static func webSocketURL(from trackerURLPrefix: String) throws -> URL {
        var value = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value.removeLast() }
        if value.hasPrefix("https://") {
            value = "wss://" + value.dropFirst("https://".count)
        } else if value.hasPrefix("http://") {
            value = "ws://" + value.dropFirst("http://".count)
        }
        guard var components = URLComponents(string: value),
              components.scheme == "ws" || components.scheme == "wss",
              components.host != nil
        else { throw TrackerCoordinationWireError.invalidEndpoint }
        let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.path = "/" + ([basePath, "ws/r2c"].filter { !$0.isEmpty }.joined(separator: "/"))
        guard let url = components.url else { throw TrackerCoordinationWireError.invalidEndpoint }
        return url
    }
}

public enum TrackerReauthenticationChallenge {
    public static func url(fromEnrollmentResponse data: Data) -> URL? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let credential = root["credential"] as? [String: Any],
              credential["state"] as? String == "reauth_required",
              let rawURL = credential["reauthentication_url"] as? String
        else { return nil }
        return trustedURL(rawURL)
    }

    public static func url(fromHTTPError data: Data, statusCode: Int) -> URL? {
        guard statusCode == 401 || statusCode == 403,
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let detail = root["detail"] as? [String: Any],
              detail["code"] as? String == "reauthentication_required",
              let rawURL = detail["reauthentication_url"] as? String
        else { return nil }
        return trustedURL(rawURL)
    }

    private static func trustedURL(_ rawURL: String) -> URL? {
        guard let url = URL(string: rawURL),
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(),
              host == "r2c-tracker.com" || host.hasSuffix(".r2c-tracker.com")
        else { return nil }
        return url
    }
}

public enum TrackerReauthenticationBrowserReturnPolicy {
    public static func shouldRetryCredential(
        browserWasOpen: Bool,
        challengeURLPresent: Bool,
        callbackPending: Bool
    ) -> Bool {
        browserWasOpen && challengeURLPresent && !callbackPending
    }
}

public enum TrackerCoordinationWire {
    public static func hello(
        client: TrackerCoordinationClient,
        position: TrackerCoordinationPosition
    ) throws -> Data {
        try encode([
            "type": "hello",
            "mapId": client.mapID,
            "incidentId": client.mapID,
            "zoneId": client.zoneID,
            "guid": client.zoneID,
            "name": client.name,
            "deviceModel": client.deviceModel,
            "appPlatform": client.platform,
            "lat": finite(position.latitude),
            "lng": finite(position.longitude),
            "appVersion": client.appVersion,
            "appVersionCode": client.appVersionCode,
            "trackerFunctionalityRelease": TrackerCoordinationClient.trackerFunctionalityRelease,
            "caltopoRttMs": position.caltopoRTTMilliseconds,
        ])
    }

    public static func heartbeat(
        client: TrackerCoordinationClient,
        position: TrackerCoordinationPosition,
        sequence: Int64
    ) throws -> Data {
        try encode([
            "type": "heartbeat",
            "seq": sequence,
            "mapId": client.mapID,
            "zoneId": client.zoneID,
            "guid": client.zoneID,
            "name": client.name,
            "lat": finite(position.latitude),
            "lng": finite(position.longitude),
            "caltopoRttMs": position.caltopoRTTMilliseconds,
        ])
    }

    public static func firstSighting(
        client: TrackerCoordinationClient,
        sighting: TrackerCoordinationSighting
    ) throws -> Data {
        var object = baseSighting(client: client, sighting: sighting)
        object["type"] = "first_sighting"
        object["incidentId"] = client.mapID
        object["name"] = client.name
        object["org"] = sighting.identity.organization
        object["model"] = sighting.identity.model
        object["ownerName"] = sighting.identity.ownerName
        return try encode(object)
    }

    public static func sighting(
        client: TrackerCoordinationClient,
        sighting: TrackerCoordinationSighting
    ) throws -> Data {
        var object = baseSighting(client: client, sighting: sighting)
        object["type"] = "sighting"
        return try encode(object)
    }

    public static func trafficPosition(
        client: TrackerCoordinationClient,
        sighting: TrackerCoordinationSighting,
        source: String,
        sourceEpoch: String,
        sequence: Int64,
        altitudeSampleTimestampMilliseconds: Int64? = nil,
        altitudeCalibration: TrackerTrafficAltitudeCalibration? = nil,
        proximityAlertDistanceFeet: Double? = nil
    ) throws -> Data {
        var object = baseSighting(client: client, sighting: sighting)
        object["type"] = "traffic_position"
        object["sampleTs"] = sighting.droneTimestampMilliseconds
        object.removeValue(forKey: "droneTs")
        object["source"] = source
        object["sourceEpoch"] = sourceEpoch
        object["seq"] = sequence
        if sighting.altitudeMeters != nil {
            object["altSampleTs"] = altitudeSampleTimestampMilliseconds
                ?? sighting.droneTimestampMilliseconds
        }
        if let proximityAlertDistanceFeet,
           proximityAlertDistanceFeet.isFinite,
           proximityAlertDistanceFeet > 0 {
            object["padFt"] = proximityAlertDistanceFeet
        }
        if let altitudeCalibration {
            object["flightEpoch"] = altitudeCalibration.flightEpoch
            object["altCalibrationState"] = altitudeCalibration.state
            if let mslAltitude = altitudeCalibration.normalizedMSLMeters(
                rawAltitudeMeters: sighting.altitudeMeters
            ) {
                object["mslAltM"] = mslAltitude
                object["mslAltSampleTs"] = altitudeSampleTimestampMilliseconds
                    ?? sighting.droneTimestampMilliseconds
                object["altCorrectionM"] = altitudeCalibration.correctionMeters
                object["altCalibrationTs"] = altitudeCalibration.lockedAtMilliseconds
                object["demSource"] = altitudeCalibration.demSource
                object["demResolutionM"] = altitudeCalibration.demResolutionMeters
            }
        } else {
            object["altCalibrationState"] = "unconfirmed"
        }
        if let telemetry = object.removeValue(forKey: "telemetry") as? [String: Any] {
            for (key, value) in telemetry { object[key] = value }
        }
        return try encode(object)
    }

    public static func droneLost(client: TrackerCoordinationClient, remoteID: String) throws -> Data {
        try encode([
            "type": "drone_lost",
            "mapId": client.mapID,
            "zoneId": client.zoneID,
            "remoteId": remoteID,
        ])
    }

    public static func droneConfirmed(
        client: TrackerCoordinationClient,
        identity: TrackerCoordinationIdentity
    ) throws -> Data {
        try encode([
            "type": "drone_confirmed",
            "mapId": client.mapID,
            "zoneId": client.zoneID,
            "guid": client.zoneID,
            "remoteId": identity.remoteID,
            "mappedId": identity.mappedID,
            "trackLabel": identity.mappedID,
            "org": identity.organization,
            "model": identity.model,
            "ownerName": identity.ownerName,
        ])
    }

    private static func baseSighting(
        client: TrackerCoordinationClient,
        sighting: TrackerCoordinationSighting
    ) -> [String: Any] {
        var object: [String: Any] = [
            "mapId": client.mapID,
            "zoneId": client.zoneID,
            "guid": client.zoneID,
            "remoteId": sighting.identity.remoteID,
            "mappedId": sighting.identity.mappedID,
            "trackLabel": sighting.identity.mappedID,
            "droneTs": sighting.droneTimestampMilliseconds,
            "lat": finite(sighting.latitude),
            "lng": finite(sighting.longitude),
        ]
        putFinite(sighting.altitudeMeters, key: "altM", into: &object)
        putFinite(sighting.distanceFromZoneMeters, key: "distanceFromZoneM", into: &object)
        var telemetry: [String: Any] = [:]
        putFinite(sighting.headingDegrees, key: "headingDeg", into: &telemetry)
        putFinite(sighting.groundSpeedKnots, key: "groundSpeedKnots", into: &telemetry)
        putFinite(sighting.verticalRateFeetPerMinute, key: "verticalRateFpm", into: &telemetry)
        if !telemetry.isEmpty { object["telemetry"] = telemetry }
        return object
    }

    private static func putFinite(_ value: Double?, key: String, into object: inout [String: Any]) {
        if let value, value.isFinite { object[key] = value }
    }

    // Null explicitly clears a previously reported position on existing trackers.
    // Omitting the keys would allow the server to retain the old coordinate.
    private static func finite(_ value: Double?) -> Any {
        guard let value, value.isFinite else { return NSNull() }
        return value
    }

    private static func finite(_ value: Double) -> Double { value.isFinite ? value : 0 }

    private static func encode(_ object: [String: Any]) throws -> Data {
        guard JSONSerialization.isValidJSONObject(object) else { throw TrackerCoordinationWireError.invalidMessage }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }
}

/// Stateful validation for tracker messages. Transport and timers remain platform adapters.
/// Publication and local alerts are allowed only when the tracker assigns this zone and the
/// operator has confirmed the drone locally, matching Android's safety contract.
public struct TrackerCoordinationProtocolState: Sendable {
    public let localZoneID: String
    private var ownersByRemoteID: [String: String] = [:]
    private var leaseSequencesByRemoteID: [String: Int64] = [:]
    private var locallyConfirmedRemoteIDs: Set<String> = []
    private var heartbeatSequence: Int64 = 0
    private var lastHeartbeatSequenceSent: Int64 = 0
    private var lastHeartbeatSequenceAcknowledged: Int64 = 0
    private var lastHeartbeatSentAtMilliseconds: Int64 = 0
    private var helloSentAtMilliseconds: Int64 = 0
    private var helloAcknowledgedAtMilliseconds: Int64 = 0

    public init(localZoneID: String) {
        self.localZoneID = localZoneID
    }

    public mutating func transportOpened(helloSentAtMilliseconds: Int64) {
        // A new transport must never inherit the prior socket's heartbeat watchdog state.
        heartbeatSequence = 0
        lastHeartbeatSequenceSent = 0
        lastHeartbeatSequenceAcknowledged = 0
        lastHeartbeatSentAtMilliseconds = 0
        helloAcknowledgedAtMilliseconds = 0
        self.helloSentAtMilliseconds = helloSentAtMilliseconds
    }

    public mutating func nextHeartbeatSequence(sentAtMilliseconds: Int64) -> Int64 {
        heartbeatSequence += 1
        lastHeartbeatSequenceSent = heartbeatSequence
        lastHeartbeatSentAtMilliseconds = sentAtMilliseconds
        return heartbeatSequence
    }

    public mutating func confirmLocally(remoteID: String) -> TrackerCoordinationEvent? {
        guard !remoteID.isEmpty else { return nil }
        locallyConfirmedRemoteIDs.insert(remoteID)
        guard let owner = ownersByRemoteID[remoteID] else { return nil }
        return ownershipEvent(remoteID: remoteID, ownerZoneID: owner)
    }

    public mutating func assignFallbackOwnership(remoteID: String) -> TrackerCoordinationEvent? {
        guard !remoteID.isEmpty else { return nil }
        ownersByRemoteID[remoteID] = localZoneID
        return ownershipEvent(remoteID: remoteID, ownerZoneID: localZoneID)
    }

    public func isLocalOwner(remoteID: String) -> Bool {
        ownersByRemoteID[remoteID] == localZoneID
    }

    public func isConfirmedLocally(remoteID: String) -> Bool {
        locallyConfirmedRemoteIDs.contains(remoteID)
    }

    public func isLocalAlertEligible(remoteID: String) -> Bool {
        isLocalOwner(remoteID: remoteID) && locallyConfirmedRemoteIDs.contains(remoteID)
    }

    public func requiresReconnectForMissingAcknowledgement(
        nowMilliseconds: Int64,
        timeoutMilliseconds: Int64 = 10_000
    ) -> String? {
        if helloSentAtMilliseconds > 0,
           helloAcknowledgedAtMilliseconds < helloSentAtMilliseconds,
           nowMilliseconds - helloSentAtMilliseconds > timeoutMilliseconds {
            return "missed hello_ack"
        }
        if lastHeartbeatSequenceSent > lastHeartbeatSequenceAcknowledged,
           lastHeartbeatSentAtMilliseconds > 0,
           nowMilliseconds - lastHeartbeatSentAtMilliseconds > timeoutMilliseconds {
            return "missed heartbeat_ack seq=\(lastHeartbeatSequenceSent)"
        }
        return nil
    }

    public mutating func handleIncoming(
        _ data: Data,
        receivedAtMilliseconds: Int64
    ) throws -> [TrackerCoordinationEvent] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw TrackerCoordinationWireError.invalidMessage
        }
        let type = string(object["type"])
        guard !type.isEmpty else { throw TrackerCoordinationWireError.missingMessageType }
        switch type {
        case "hello_ack":
            helloAcknowledgedAtMilliseconds = receivedAtMilliseconds
            let version = number(object["recommendedAppVersionCode"])?.intValue
            let updateURL = string(object["updateUrl"])
            return [.helloAcknowledged(
                recommendedAppVersionCode: version,
                updateURL: updateURL.isEmpty ? nil : updateURL
            )]
        case "heartbeat_ack":
            return [heartbeatAcknowledgement(object, receivedAtMilliseconds: receivedAtMilliseconds)]
        case "zone_update":
            return [.peersUpdated(parseZones(object["zones"], receivedAtMilliseconds: receivedAtMilliseconds))]
        case "owner_assigned":
            let remoteID = string(object["remoteId"])
            let owner = firstNonEmpty(string(object["ownerGuid"]), string(object["ownerZoneId"]))
            guard !remoteID.isEmpty, !owner.isEmpty else { throw TrackerCoordinationWireError.invalidMessage }
            let leaseSequence = number(object["leaseSeq"])?.int64Value ?? -1
            if let previous = leaseSequencesByRemoteID[remoteID], leaseSequence >= 0, leaseSequence < previous {
                return [.ignored(messageType: "stale owner_assigned")]
            }
            ownersByRemoteID[remoteID] = owner
            if leaseSequence >= 0 { leaseSequencesByRemoteID[remoteID] = leaseSequence }
            return [ownershipEvent(remoteID: remoteID, ownerZoneID: owner)]
        case "owner_expired":
            let remoteID = string(object["remoteId"])
            guard !remoteID.isEmpty else { throw TrackerCoordinationWireError.invalidMessage }
            let expectedOwner = firstNonEmpty(
                string(object["prevOwnerGuid"]),
                string(object["prevOwnerZoneId"])
            )
            if !expectedOwner.isEmpty,
               let currentOwner = ownersByRemoteID[remoteID],
               expectedOwner != currentOwner {
                return [.ignored(messageType: "stale owner_expired")]
            }
            ownersByRemoteID.removeValue(forKey: remoteID)
            leaseSequencesByRemoteID.removeValue(forKey: remoteID)
            locallyConfirmedRemoteIDs.remove(remoteID)
            return [.ownerExpired(remoteID: remoteID)]
        case "relay_sighting":
            guard let relay = parseRelay(object), isLocalOwner(remoteID: relay.remoteID), relay.sourceZoneID != localZoneID else {
                return [.ignored(messageType: type)]
            }
            return [.relaySighting(relay)]
        case "peer_traffic_position":
            guard let traffic = parsePeerTraffic(object), traffic.sourceZoneID != localZoneID else {
                return [.ignored(messageType: type)]
            }
            return [.peerTrafficPosition(traffic)]
        case "traffic_schedule":
            guard let schedule = parseTrafficSchedule(object) else {
                return [.ignored(messageType: type)]
            }
            return [.trafficSchedule(schedule)]
        case "drone_confirmed":
            return handleDroneConfirmed(object)
        default:
            return [.ignored(messageType: type)]
        }
    }

    private mutating func heartbeatAcknowledgement(
        _ object: [String: Any],
        receivedAtMilliseconds: Int64
    ) -> TrackerCoordinationEvent {
        let sequence = number(object["clientSeq"])?.int64Value ?? -1
        guard sequence > 0 else { return .reconnectRequired(reason: "heartbeat_ack missing clientSeq") }
        if sequence < lastHeartbeatSequenceAcknowledged {
            return .ignored(messageType: "stale heartbeat_ack")
        }
        guard sequence == lastHeartbeatSequenceSent else {
            return .reconnectRequired(
                reason: "heartbeat_ack seq mismatch ack=\(sequence) expected=\(lastHeartbeatSequenceSent)"
            )
        }
        lastHeartbeatSequenceAcknowledged = sequence
        let leaseExpiry = number(object["ownerLeaseExpireTs"])?.int64Value
        _ = receivedAtMilliseconds
        return .heartbeatAcknowledged(
            sequence: sequence,
            ownerLeaseExpiresAtMilliseconds: leaseExpiry == 0 ? nil : leaseExpiry
        )
    }

    private mutating func handleDroneConfirmed(_ object: [String: Any]) -> [TrackerCoordinationEvent] {
        let remoteID = string(object["remoteId"])
        guard !remoteID.isEmpty else { return [.ignored(messageType: "invalid drone_confirmed")] }
        let confirmedBy = firstNonEmpty(
            string(object["confirmedByGuid"]),
            firstNonEmpty(string(object["guid"]), string(object["zoneId"]))
        )
        let confirmedLocally = confirmedBy == localZoneID
        if confirmedLocally {
            locallyConfirmedRemoteIDs.insert(remoteID)
        } else {
            locallyConfirmedRemoteIDs.remove(remoteID)
        }
        let identity = TrackerCoordinationIdentity(
            remoteID: remoteID,
            mappedID: string(object["mappedId"]),
            organization: string(object["org"]),
            model: string(object["model"]),
            ownerName: string(object["ownerName"])
        )
        var events: [TrackerCoordinationEvent] = [
            .droneConfirmed(identity: identity, confirmedByZoneID: confirmedBy, confirmedLocally: confirmedLocally),
        ]
        if !confirmedBy.isEmpty {
            ownersByRemoteID[remoteID] = confirmedBy
            let leaseSequence = number(object["leaseSeq"])?.int64Value ?? -1
            if leaseSequence >= 0 { leaseSequencesByRemoteID[remoteID] = leaseSequence }
            events.append(ownershipEvent(remoteID: remoteID, ownerZoneID: confirmedBy))
        }
        return events
    }

    private func ownershipEvent(remoteID: String, ownerZoneID: String) -> TrackerCoordinationEvent {
        let localOwner = ownerZoneID == localZoneID
        return .ownershipChanged(
            remoteID: remoteID,
            ownerZoneID: ownerZoneID,
            localOwner: localOwner,
            alertEligible: localOwner && locallyConfirmedRemoteIDs.contains(remoteID)
        )
    }

    private func parseZones(_ value: Any?, receivedAtMilliseconds: Int64) -> [TrackerPeerZone] {
        guard let zones = value as? [[String: Any]] else { return [] }
        return zones.compactMap { zone in
            let zoneID = firstNonEmpty(string(zone["guid"]), string(zone["zoneId"]))
            guard !zoneID.isEmpty, zoneID != localZoneID else { return nil }
            return TrackerPeerZone(
                zoneID: zoneID,
                name: firstNonEmpty(string(zone["name"]), zoneID),
                latitude: finiteNumber(zone["lat"]),
                longitude: finiteNumber(zone["lng"]),
                caltopoRTTMilliseconds: number(zone["caltopoRttMs"])?.int64Value ?? 2_000,
                lastSeenMilliseconds: number(zone["lastSeenMs"])?.int64Value ?? receivedAtMilliseconds,
                online: number(zone["online"])?.boolValue ?? true
            )
        }
    }

    private func parseRelay(_ object: [String: Any]) -> TrackerRelaySighting? {
        let remoteID = string(object["remoteId"])
        let source = firstNonEmpty(string(object["fromZoneId"]), string(object["zoneId"]))
        guard !remoteID.isEmpty, !source.isEmpty,
              let latitude = finiteOptionalNumber(object["lat"]),
              let longitude = finiteOptionalNumber(object["lng"])
        else { return nil }
        let telemetry = object["telemetry"] as? [String: Any]
        return TrackerRelaySighting(
            sourceZoneID: source,
            remoteID: remoteID,
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: finiteOptionalNumber(object["altM"]),
            droneTimestampMilliseconds: number(object["droneTs"])?.int64Value ?? 0,
            headingDegrees: finiteOptionalNumber(telemetry?["headingDeg"]),
            groundSpeedKnots: finiteOptionalNumber(telemetry?["groundSpeedKnots"]),
            verticalRateFeetPerMinute: finiteOptionalNumber(telemetry?["verticalRateFpm"])
        )
    }

    private func parsePeerTraffic(_ object: [String: Any]) -> TrackerPeerTrafficPosition? {
        let remoteID = string(object["remoteId"])
        let sourceZoneID = string(object["fromZoneId"])
        let source = string(object["source"]).lowercased()
        let sourceEpoch = string(object["sourceEpoch"])
        guard !remoteID.isEmpty, !sourceZoneID.isEmpty,
              source == "rid" || source == "sei",
              !sourceEpoch.isEmpty,
              let latitude = finiteOptionalNumber(object["lat"]),
              let longitude = finiteOptionalNumber(object["lng"]),
              (-90...90).contains(latitude), (-180...180).contains(longitude)
        else { return nil }
        return TrackerPeerTrafficPosition(
            sourceZoneID: sourceZoneID,
            remoteID: remoteID,
            mappedID: string(object["mappedId"]),
            source: source,
            sourceEpoch: sourceEpoch,
            sequence: number(object["seq"])?.int64Value ?? -1,
            sampleTimestampMilliseconds: number(object["sampleTs"])?.int64Value ?? 0,
            trackerReceivedTimestampMilliseconds: number(object["receivedTs"])?.int64Value ?? 0,
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: finiteOptionalNumber(object["altM"]),
            altitudeSampleTimestampMilliseconds: number(object["altSampleTs"])?.int64Value,
            flightEpoch: string(object["flightEpoch"]),
            altitudeCalibrationState: string(object["altCalibrationState"]),
            mslAltitudeMeters: finiteOptionalNumber(object["mslAltM"]),
            mslAltitudeSampleTimestampMilliseconds: number(object["mslAltSampleTs"])?.int64Value,
            altitudeCorrectionMeters: finiteOptionalNumber(object["altCorrectionM"]),
            demSource: string(object["demSource"]),
            demResolutionMeters: finiteOptionalNumber(object["demResolutionM"]),
            incidentPadFeet: finiteOptionalNumber(object["incidentPadFt"]),
            shadowNearestDistanceMeters: finiteOptionalNumber(object["shadowNearestDistanceM"]),
            shadowSchedulingPadFeet: finiteOptionalNumber(object["shadowSchedulingPadFt"]),
            shadowIntervalMilliseconds: number(object["shadowIntervalMs"])?.int64Value,
            headingDegrees: finiteOptionalNumber(object["headingDeg"]),
            groundSpeedKnots: finiteOptionalNumber(object["groundSpeedKnots"]),
            verticalRateFeetPerMinute: finiteOptionalNumber(object["verticalRateFpm"])
        )
    }

    private func parseTrafficSchedule(_ object: [String: Any]) -> TrackerTrafficSchedule? {
        let remoteID = string(object["remoteId"])
        let source = string(object["source"])
        let sourceEpoch = string(object["sourceEpoch"])
        guard !remoteID.isEmpty,
              source == "rid" || source == "sei",
              !sourceEpoch.isEmpty,
              let rawInterval = number(object["shadowIntervalMs"])?.int64Value
        else { return nil }
        return TrackerTrafficSchedule(
            remoteID: remoteID,
            source: source,
            sourceEpoch: sourceEpoch,
            sequence: number(object["seq"])?.int64Value ?? -1,
            intervalMilliseconds: min(16_000, max(1_000, rawInterval)),
            incidentPadFeet: finiteOptionalNumber(object["incidentPadFt"]),
            nearestDistanceMeters: finiteOptionalNumber(object["shadowNearestDistanceM"]),
            schedulingPadFeet: finiteOptionalNumber(object["shadowSchedulingPadFt"])
        )
    }

    private func string(_ value: Any?) -> String {
        (value as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func number(_ value: Any?) -> NSNumber? { value as? NSNumber }
    private func finiteNumber(_ value: Any?) -> Double { finiteOptionalNumber(value) ?? 0 }
    private func finiteOptionalNumber(_ value: Any?) -> Double? {
        guard let value = number(value)?.doubleValue, value.isFinite else { return nil }
        return value
    }
    private func firstNonEmpty(_ first: String, _ second: String) -> String { first.isEmpty ? second : first }
}
