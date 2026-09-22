import Foundation

public struct RidTrackPolicy: Sendable, Equatable {
    /// Matches Android's 293.33 ft/s telemetry sanity ceiling (200 mph). This is
    /// intentionally above the 100 mph operating limit to tolerate GPS error between points.
    public var maximumSpeedMetersPerSecond: Double
    /// Matches Android's minimum accepted waypoint spacing (2 ft by default).
    public var minimumDistanceMeters: Double
    /// Minimum time between archived waypoints. Live telemetry still refreshes at
    /// the source rate; this only limits redundant archive/publish points.
    public var minimumWaypointInterval: TimeInterval
    public var duplicateKeepaliveInterval: TimeInterval
    public var activeTimeout: TimeInterval
    public var maximumPointsPerTrack: Int
    /// Minimum accepted F3411 NACp horizontal-accuracy code. Code 10 is 10 m.
    public var minimumHorizontalAccuracyCode: UInt8

    public init(
        maximumSpeedMetersPerSecond: Double = 89.408,
        minimumDistanceMeters: Double = 0.6096,
        minimumWaypointInterval: TimeInterval = 1,
        duplicateKeepaliveInterval: TimeInterval = 3,
        activeTimeout: TimeInterval = 30,
        // Keep all locally recorded points. CalTopo LiveTrack applies its own
        // first-3,000-point limit; the client must not truncate the archive.
        maximumPointsPerTrack: Int = Int.max,
        minimumHorizontalAccuracyCode: UInt8 = 9
    ) {
        self.maximumSpeedMetersPerSecond = maximumSpeedMetersPerSecond
        self.minimumDistanceMeters = minimumDistanceMeters
        self.minimumWaypointInterval = max(0, minimumWaypointInterval)
        self.duplicateKeepaliveInterval = duplicateKeepaliveInterval
        self.activeTimeout = activeTimeout
        self.maximumPointsPerTrack = maximumPointsPerTrack
        self.minimumHorizontalAccuracyCode = min(max(minimumHorizontalAccuracyCode, 9), 12)
    }
}

public struct RidTrackPoint: Sendable, Equatable, Identifiable {
    public let id: UUID
    public let receivedAt: Date
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let headingDegrees: Double?
    public let speedMetersPerSecond: Double?

    init(observation: RidObservation) {
        id = UUID()
        receivedAt = observation.receivedAt
        latitude = observation.latitude
        longitude = observation.longitude
        altitudeMeters = observation.altitudeMeters
        headingDegrees = observation.headingDegrees
        speedMetersPerSecond = observation.speedMetersPerSecond
    }
}

public struct RidAircraftTrack: Sendable, Equatable, Identifiable {
    public var id: String { aircraftID }
    public let aircraftID: String
    public fileprivate(set) var points: [RidTrackPoint]
    public fileprivate(set) var lastObservation: RidObservation
    public fileprivate(set) var lastAircraftMessageAt: Date
    public fileprivate(set) var lastSignalAt: Date
    public fileprivate(set) var lastSignalStrengthDbm: Int?
    public fileprivate(set) var lastSignalSource: RidObservation.Source
    public fileprivate(set) var lastDirectSignalStrengthDbm: Int?
    public fileprivate(set) var lastDirectSignalSource: RidObservation.Source?
    public fileprivate(set) var lastDroneToBridgeSignalStrengthDbm: Int?
    public fileprivate(set) var distanceMeters: Double
    public fileprivate(set) var acceptedCountBySource: [RidObservation.Source: Int]

    public func isActive(at date: Date, timeout: TimeInterval = 30) -> Bool {
        date.timeIntervalSince(lastAircraftMessageAt) <= timeout
    }
}

public enum RidTrackSignalOnlyReason: Sendable, Equatable {
    case duplicatePosition
    case tooFrequentWaypoint(interval: TimeInterval)
    case belowMinimumDistance(meters: Double)
    case implausibleSpeed(metersPerSecond: Double)
}

public enum RidTrackIngestOutcome: Sendable, Equatable {
    case accepted(RidAircraftTrack)
    /// Valid live telemetry that does not add an archived/published waypoint.
    case telemetryOnly(RidAircraftTrack, reason: RidTrackSignalOnlyReason)
    case signalOnly(RidAircraftTrack, reason: RidTrackSignalOnlyReason)
    case rejectedHorizontalAccuracy(code: UInt8, track: RidAircraftTrack?)
    case rejectedInvalidObservation
}

public actor RidTrackStore {
    public private(set) var policy: RidTrackPolicy
    private var tracksByAircraftID: [String: RidAircraftTrack] = [:]

    public init(policy: RidTrackPolicy = RidTrackPolicy()) {
        self.policy = policy
    }

    public func updatePolicy(_ policy: RidTrackPolicy) {
        self.policy = policy
    }

    public func ingest(_ rawObservation: RidObservation) -> RidTrackIngestOutcome {
        let aircraftID = Self.canonicalAircraftID(rawObservation.aircraftId)
        guard !aircraftID.isEmpty,
              rawObservation.latitude.isFinite,
              rawObservation.longitude.isFinite,
              rawObservation.latitude != 0,
              rawObservation.longitude != 0,
              (-90 ... 90).contains(rawObservation.latitude),
              (-180 ... 180).contains(rawObservation.longitude)
        else {
            return .rejectedInvalidObservation
        }

        let observation = rawObservation.withAircraftID(aircraftID)
        if let code = observation.horizontalAccuracyCode,
           !(policy.minimumHorizontalAccuracyCode ... 12).contains(code) {
            guard var track = tracksByAircraftID[aircraftID] else {
                return .rejectedHorizontalAccuracy(code: code, track: nil)
            }
            track.lastSignalAt = max(track.lastSignalAt, observation.receivedAt)
            track.lastAircraftMessageAt = max(track.lastAircraftMessageAt, observation.receivedAt)
            track.lastSignalStrengthDbm = observation.signalStrengthDbm
            track.lastSignalSource = observation.source
            updateRSSIMeasurements(&track, from: observation)
            tracksByAircraftID[aircraftID] = track
            return .rejectedHorizontalAccuracy(code: code, track: track)
        }
        guard var track = tracksByAircraftID[aircraftID] else {
            let point = RidTrackPoint(observation: observation)
            let track = RidAircraftTrack(
                aircraftID: aircraftID,
                points: [point],
                lastObservation: observation,
                lastAircraftMessageAt: observation.receivedAt,
                lastSignalAt: observation.receivedAt,
                lastSignalStrengthDbm: observation.signalStrengthDbm,
                lastSignalSource: observation.source,
                lastDirectSignalStrengthDbm: observation.droneScoutRelay == nil
                    ? observation.signalStrengthDbm : nil,
                lastDirectSignalSource: observation.droneScoutRelay == nil
                    ? observation.source : nil,
                lastDroneToBridgeSignalStrengthDbm: observation.droneScoutRelay?.droneToBridgeRssiDbm,
                distanceMeters: 0,
                acceptedCountBySource: [observation.source: 1]
            )
            tracksByAircraftID[aircraftID] = track
            return .accepted(track)
        }

        track.lastSignalAt = max(track.lastSignalAt, observation.receivedAt)
        track.lastAircraftMessageAt = max(track.lastAircraftMessageAt, observation.receivedAt)
        track.lastSignalStrengthDbm = observation.signalStrengthDbm
        track.lastSignalSource = observation.source
        updateRSSIMeasurements(&track, from: observation)
        guard let previous = track.points.last else {
            tracksByAircraftID.removeValue(forKey: aircraftID)
            return ingest(observation)
        }

        let distance = Self.distanceMeters(
            fromLatitude: previous.latitude,
            longitude: previous.longitude,
            toLatitude: observation.latitude,
            longitude: observation.longitude
        )
        let elapsed = observation.receivedAt.timeIntervalSince(previous.receivedAt)

        let liveDistance = Self.distanceMeters(
            fromLatitude: track.lastObservation.latitude,
            longitude: track.lastObservation.longitude,
            toLatitude: observation.latitude,
            longitude: observation.longitude
        )
        let liveElapsed = observation.receivedAt.timeIntervalSince(track.lastObservation.receivedAt)
        if liveDistance > 0, liveElapsed > 0 {
            let impliedSpeed = liveDistance / liveElapsed
            if impliedSpeed > policy.maximumSpeedMetersPerSecond {
                tracksByAircraftID[aircraftID] = track
                return .signalOnly(track, reason: .implausibleSpeed(metersPerSecond: impliedSpeed))
            }
        }

        // Live telemetry freshness is independent of the last recorded waypoint.
        track.lastObservation = observation
        track.acceptedCountBySource[observation.source, default: 0] += 1
        if elapsed >= 0, elapsed < policy.duplicateKeepaliveInterval {
            let reason: RidTrackSignalOnlyReason?
            if distance == 0 {
                reason = .duplicatePosition
            } else if elapsed < policy.minimumWaypointInterval {
                reason = .tooFrequentWaypoint(interval: elapsed)
            } else if observation.source != .djiVideo, distance < policy.minimumDistanceMeters {
                reason = .belowMinimumDistance(meters: distance)
            } else {
                reason = nil
            }
            if let reason {
                tracksByAircraftID[aircraftID] = track
                return .telemetryOnly(track, reason: reason)
            }
        }

        track.points.append(RidTrackPoint(observation: observation))
        if track.points.count > policy.maximumPointsPerTrack {
            track.points.removeFirst(track.points.count - policy.maximumPointsPerTrack)
        }
        track.distanceMeters += distance
        tracksByAircraftID[aircraftID] = track
        return .accepted(track)
    }

    private func updateRSSIMeasurements(
        _ track: inout RidAircraftTrack,
        from observation: RidObservation
    ) {
        if let relay = observation.droneScoutRelay {
            track.lastDroneToBridgeSignalStrengthDbm = relay.droneToBridgeRssiDbm
        } else if observation.source != .trackerRelay {
            track.lastDirectSignalStrengthDbm = observation.signalStrengthDbm
            track.lastDirectSignalSource = observation.source
        }
    }

    public func snapshot() -> [RidAircraftTrack] {
        tracksByAircraftID.values.sorted {
            if $0.lastAircraftMessageAt != $1.lastAircraftMessageAt {
                return $0.lastAircraftMessageAt > $1.lastAircraftMessageAt
            }
            return $0.aircraftID < $1.aircraftID
        }
    }

    /// Refresh flight lifecycle presence without changing the last known position or the
    /// location-only signal clock used by stale-location alerts.
    @discardableResult
    public func noteAircraftMessage(_ rawMessage: RidAircraftMessage) -> Bool {
        let aircraftID = Self.canonicalAircraftID(rawMessage.aircraftID)
        guard !aircraftID.isEmpty, var track = tracksByAircraftID[aircraftID] else {
            return false
        }
        track.lastAircraftMessageAt = max(track.lastAircraftMessageAt, rawMessage.receivedAt)
        tracksByAircraftID[aircraftID] = track
        return true
    }

    public func activeSnapshot(
        at date: Date = Date(),
        pairedVideoLastActivityAt: [String: Date] = [:]
    ) -> [RidAircraftTrack] {
        snapshot().filter {
            isActive($0, at: date, pairedVideoLastActivityAt: pairedVideoLastActivityAt)
        }
    }

    public func removeInactive(
        at date: Date = Date(),
        pairedVideoLastActivityAt: [String: Date] = [:]
    ) -> [RidAircraftTrack] {
        let removed = tracksByAircraftID.values.filter {
            !isActive($0, at: date, pairedVideoLastActivityAt: pairedVideoLastActivityAt)
        }
        for track in removed {
            tracksByAircraftID.removeValue(forKey: track.aircraftID)
        }
        return removed.sorted { $0.aircraftID < $1.aircraftID }
    }

    public func removeAll() {
        tracksByAircraftID.removeAll()
    }

    public static func canonicalAircraftID(_ value: String) -> String {
        value.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    private func isActive(
        _ track: RidAircraftTrack,
        at date: Date,
        pairedVideoLastActivityAt: [String: Date]
    ) -> Bool {
        let lastPresence = max(
            track.lastAircraftMessageAt,
            pairedVideoLastActivityAt[track.aircraftID] ?? .distantPast
        )
        return date.timeIntervalSince(lastPresence) < policy.activeTimeout
    }

    private static func distanceMeters(
        fromLatitude: Double,
        longitude fromLongitude: Double,
        toLatitude: Double,
        longitude toLongitude: Double
    ) -> Double {
        let radius = 6_371_008.8
        let lat1 = fromLatitude * .pi / 180
        let lat2 = toLatitude * .pi / 180
        let deltaLat = (toLatitude - fromLatitude) * .pi / 180
        let deltaLon = (toLongitude - fromLongitude) * .pi / 180
        let a = sin(deltaLat / 2) * sin(deltaLat / 2)
            + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

private extension RidObservation {
    func withAircraftID(_ aircraftID: String) -> RidObservation {
        RidObservation(
            source: source,
            aircraftId: aircraftID,
            receivedAt: receivedAt,
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: altitudeMeters,
            heightMeters: heightMeters,
            heightReference: heightReference,
            grounded: grounded,
            horizontalAccuracyCode: horizontalAccuracyCode,
            headingDegrees: headingDegrees,
            speedMetersPerSecond: speedMetersPerSecond,
            operatorLatitude: operatorLatitude,
            operatorLongitude: operatorLongitude,
            signalStrengthDbm: signalStrengthDbm,
            droneScoutRelay: droneScoutRelay,
            videoReferenceLatitude: videoReferenceLatitude,
            videoReferenceLongitude: videoReferenceLongitude
        )
    }
}
