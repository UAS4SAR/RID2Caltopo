import Foundation

public struct RidTrackArchiveMetadata: Sendable, Equatable {
    public var mappedID: String
    public var owner: String
    public var model: String
    public var organization: String
    public var incident: String
    public var operationalPeriod: String
    public var mapID: String
    public var deviceName: String
    public var buildVersion: String
    public var buildTime: String
    public var localArchiveOnly: Bool

    public init(
        mappedID: String = "",
        owner: String = "",
        model: String = "",
        organization: String = "",
        incident: String = "",
        operationalPeriod: String = "",
        mapID: String = "",
        deviceName: String = "",
        buildVersion: String = "",
        buildTime: String = "",
        localArchiveOnly: Bool = false
    ) {
        self.mappedID = mappedID
        self.owner = owner
        self.model = model
        self.organization = organization
        self.incident = incident
        self.operationalPeriod = operationalPeriod
        self.mapID = mapID
        self.deviceName = deviceName
        self.buildVersion = buildVersion
        self.buildTime = buildTime
        self.localArchiveOnly = localArchiveOnly
    }
}

public enum RidTrackGeoJSON {
    /// Produces the same archive envelope and coordinate ordering as Android's
    /// `WaypointTrack.getGeoJson()` for cross-platform replay and upload tools.
    public static func encode(
        track: RidAircraftTrack,
        metadata: RidTrackArchiveMetadata = RidTrackArchiveMetadata()
    ) throws -> Data {
        let mappedID = metadata.mappedID.isEmpty ? track.aircraftID : metadata.mappedID
        let owner = metadata.owner.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayLabel = owner.isEmpty ? mappedID : owner
        let startDate = track.points.first?.receivedAt ?? track.lastObservation.receivedAt
        let startTime = formattedStartTime(startDate)
        let coordinates: [[String]] = track.points.map { point in
            [
                String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), point.longitude),
                String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), point.latitude),
                String(format: "%.0f", locale: Locale(identifier: "en_US_POSIX"), point.altitudeMeters ?? -1_000),
                String(Int64(point.receivedAt.timeIntervalSince1970 * 1_000)),
            ]
        }
        let miles = track.distanceMeters / 1_609.344
        let r2cProperties: [String: Any] = [
            "owner": metadata.owner,
            "model": metadata.model,
            "org": metadata.organization,
            "rid": track.aircraftID,
            "mid": mappedID,
            "local_archive_only": metadata.localArchiveOnly,
            "incident": metadata.incident,
            "op_period": metadata.operationalPeriod,
            "map_id": metadata.mapID,
            "tz_str": TimeZone.current.identifier,
            "device_name": metadata.deviceName,
            "BUILD_VERSION": metadata.buildVersion,
            "BUILD_TIME": metadata.buildTime,
            "distance_mi": String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), miles),
        ]
        let feature: [String: Any] = [
            "type": "Feature",
            "properties": [
                "title": displayLabel,
                "start_time": startTime,
                "r2c_prop": r2cProperties,
            ],
            "geometry": [
                "type": "LineString",
                "coordinates": coordinates,
            ],
        ]
        return try JSONSerialization.data(
            withJSONObject: ["type": "FeatureCollection", "features": [feature]],
            options: [.prettyPrinted, .sortedKeys]
        )
    }

    public static func suggestedFilename(
        for track: RidAircraftTrack,
        timeZone: TimeZone = .current
    ) -> String {
        let startDate = track.points.first?.receivedAt ?? track.lastObservation.receivedAt
        let timestamp = OperationalDiagnosticLogFormat.filenameTimestamp(
            startDate,
            timeZone: timeZone
        )
        return "\(track.aircraftID)-\(timestamp).json"
    }

    public static func suggestedClueReportFilename(
        for track: RidAircraftTrack,
        timeZone: TimeZone = .current
    ) -> String {
        URL(fileURLWithPath: suggestedFilename(for: track, timeZone: timeZone))
            .deletingPathExtension()
            .appendingPathExtension("kmz")
            .lastPathComponent
    }

    private static func formattedStartTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "ddMMMyyyy-HHmmss"
        return formatter.string(from: date)
    }
}
