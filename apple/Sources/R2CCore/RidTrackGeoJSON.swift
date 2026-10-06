import Foundation

public struct RidTrackArchiveMetadata: Sendable, Equatable {
    public var flightReadiness: FlightReadiness?
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
        localArchiveOnly: Bool = false,
        flightReadiness: FlightReadiness? = nil
    ) {
        self.flightReadiness = flightReadiness
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
        let startDate = track.points.first?.receivedAt ?? track.lastObservation.receivedAt
        let startTime = formattedStartTime(startDate)
        let coordinates: [[String]] = track.points.map { point in
            [
                String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), point.longitude),
                String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), point.latitude),
                String(format: "%.0f", locale: Locale(identifier: "en_US_POSIX"), point.altitudeMeters ?? -1_000),
                // Drone clock (RID timestamp / stream clock) like Android; receive time when unknown.
                String(Int64((point.bindingTime.timeIntervalSince1970 * 1_000).rounded())),
            ]
        }
        // Diagnostic only: the app's receive time per point, so a drone clock offset can be measured.
        let receivedMilliseconds = track.points.map { Int64(($0.receivedAt.timeIntervalSince1970 * 1_000).rounded()) }
        let droneClock = track.points.map { $0.droneTime != nil }
        let miles = track.distanceMeters / 1_609.344
        let r2cProperties: [String: Any] = [
            "flightReadiness": metadata.flightReadiness?.dictionary ?? [:],
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
                "title": archiveTitle(for: track, metadata: metadata),
                "start_time": startTime,
                "r2c_prop": r2cProperties,
                "r2c_point_received_ms": receivedMilliseconds,
                "r2c_point_drone_clock": droneClock,
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

    /// Points, title and aircraft of an archived track, for KMZ rebuilds and late clue binding.
    public struct ArchiveContents: Sendable, Equatable {
        public let title: String
        public let remoteID: String
        public let points: [ClueBindingPoint]
    }

    /// Reads an archive written by either platform. Coordinates are [lng, lat, alt, timeMs] as strings
    /// or numbers; the optional receive-time arrays are absent in older files.
    public static func decodeArchive(_ data: Data) -> ArchiveContents? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let feature = (root["features"] as? [[String: Any]])?.first,
              let geometry = feature["geometry"] as? [String: Any],
              let coordinates = geometry["coordinates"] as? [[Any]]
        else { return nil }
        let properties = feature["properties"] as? [String: Any] ?? [:]
        let r2c = properties["r2c_prop"] as? [String: Any] ?? [:]
        let received = properties["r2c_point_received_ms"] as? [Any]
        let droneClock = properties["r2c_point_drone_clock"] as? [Any]
        func number(_ value: Any?) -> Double? {
            if let value = value as? NSNumber { return value.doubleValue }
            if let value = value as? String { return Double(value) }
            return nil
        }
        var points: [ClueBindingPoint] = []
        for (index, coordinate) in coordinates.enumerated() {
            guard coordinate.count >= 4, let lng = number(coordinate[0]), let lat = number(coordinate[1]),
                  let time = number(coordinate[3]) else { continue }
            let altitude = number(coordinate[2]).flatMap { $0 <= -999 ? nil : $0 }
            let receivedAt = received.flatMap { index < $0.count ? number($0[index]) : nil }.map { Int64($0) }
            let isDrone = droneClock.flatMap { index < $0.count ? ($0[index] as? Bool) : nil } ?? true
            points.append(ClueBindingPoint(timeMs: Int64(time), receivedAtMs: receivedAt, latitude: lat, longitude: lng,
                                           altitudeMeters: altitude, source: "archive", droneClock: isDrone))
        }
        return ArchiveContents(title: properties["title"] as? String ?? "", remoteID: r2c["rid"] as? String ?? "",
                               points: points)
    }

    /// Match live CalTopo publication, including when no incident map is selected.
    /// The pilot callsign remains separate flight metadata.
    public static func archiveTitle(
        for track: RidAircraftTrack,
        metadata: RidTrackArchiveMetadata,
        timeZone: TimeZone = .current
    ) -> String {
        CaltopoTrackLabel.androidCompatible(
            baseLabel: metadata.mappedID.isEmpty ? track.aircraftID : metadata.mappedID,
            firstWaypointAt: track.points.first?.receivedAt ?? track.lastObservation.receivedAt,
            timeZone: timeZone
        )
    }

    public static func suggestedFilename(
        for track: RidAircraftTrack,
        timeZone: TimeZone = .current
    ) -> String {
        let startDate = track.points.first?.receivedAt ?? track.lastObservation.receivedAt
        return suggestedFilenameBase(aircraftID: track.aircraftID, startDate: startDate, timeZone: timeZone) + ".json"
    }

    /// Shared by archive writing and Discard, so both resolve the same filename.
    public static func suggestedFilenameBase(
        aircraftID: String,
        startDate: Date,
        timeZone: TimeZone = .current
    ) -> String {
        "\(aircraftID)-\(OperationalDiagnosticLogFormat.filenameTimestamp(startDate, timeZone: timeZone))"
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
