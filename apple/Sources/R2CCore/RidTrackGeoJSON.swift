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
    /// Stable flight id (RidFlightID), written as `properties.r2c_flight_id` like Android.
    public var flightID: String
    /// Local captured-video short URL (same matching as CalTopo); empty when none linked.
    public var capturedVideoDescription: String
    /// True only when this tablet owned the flight and publication was allowed. Written as
    /// `r2c_prop.tracker_upload_authorized`; `false` makes the archive permanently ineligible
    /// for tracker upload (non-owners must not upload).
    public var trackerUploadAuthorized: Bool
    public var trackerClaimPending: Bool

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
        flightReadiness: FlightReadiness? = nil,
        flightID: String = "",
        capturedVideoDescription: String = "",
        trackerUploadAuthorized: Bool = false,
        trackerClaimPending: Bool = false
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
        self.flightID = flightID
        self.capturedVideoDescription = capturedVideoDescription
        self.trackerUploadAuthorized = trackerUploadAuthorized
        self.trackerClaimPending = trackerClaimPending
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
        // Compact receive-time / drone-clock encoding (see FlightArchivePointTiming).
        let receivedMilliseconds: [Int64?] = track.points.map {
            Int64(($0.receivedAt.timeIntervalSince1970 * 1_000).rounded())
        }
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
            "tracker_upload_authorized": metadata.trackerUploadAuthorized,
            "tracker_claim_pending": metadata.trackerClaimPending,
            "incident": metadata.incident,
            "op_period": metadata.operationalPeriod,
            "map_id": metadata.mapID,
            "tz_str": TimeZone.current.identifier,
            "device_name": metadata.deviceName,
            "BUILD_VERSION": metadata.buildVersion,
            "BUILD_TIME": metadata.buildTime,
            "distance_mi": String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), miles),
        ]
        var properties: [String: Any] = [
            "title": archiveTitle(for: track, metadata: metadata),
            "start_time": startTime,
            "r2c_prop": r2cProperties,
        ]
        let videoDescription = metadata.capturedVideoDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        if !videoDescription.isEmpty {
            properties["description"] = videoDescription
        }
        FlightArchivePointTiming.write(into: &properties, receivedAtMs: receivedMilliseconds, droneClock: droneClock)
        if !metadata.flightID.isEmpty { properties["r2c_flight_id"] = metadata.flightID }
        let feature: [String: Any] = [
            "type": "Feature",
            "properties": properties,
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

    /// Flight file for a journal recovered after the process died. Uses every observation
    /// and only the pilot stored on that journal.
    public static func encodeRecovered(
        remoteID: String,
        observations: [RidObservation],
        title: String,
        metadata: RidTrackArchiveMetadata
    ) throws -> Data {
        let startDate = observations.first?.droneTimestamp ?? observations.first?.receivedAt ?? Date()
        let coordinates: [[String]] = observations.map { observation in
            let stamped = observation.droneTimestamp ?? observation.receivedAt
            return [
                String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), observation.longitude),
                String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), observation.latitude),
                String(format: "%.0f", locale: Locale(identifier: "en_US_POSIX"), observation.altitudeMeters ?? -1_000),
                String(Int64((stamped.timeIntervalSince1970 * 1_000).rounded())),
            ]
        }
        let receivedMilliseconds: [Int64?] = observations.map {
            Int64(($0.receivedAt.timeIntervalSince1970 * 1_000).rounded())
        }
        let droneClock = observations.map { $0.droneTimestamp != nil }
        var properties: [String: Any] = [
            "title": title,
            "start_time": formattedStartTime(startDate),
            "r2c_prop": [
                "owner": metadata.owner,
                "model": metadata.model,
                "org": metadata.organization,
                "rid": remoteID,
                "mid": metadata.mappedID,
                "local_archive_only": metadata.localArchiveOnly,
                "tracker_upload_authorized": metadata.trackerUploadAuthorized,
                "tracker_claim_pending": metadata.trackerClaimPending,
                "map_id": metadata.mapID,
            ] as [String: Any],
        ]
        FlightArchivePointTiming.write(into: &properties, receivedAtMs: receivedMilliseconds, droneClock: droneClock)
        if !metadata.flightID.isEmpty { properties["r2c_flight_id"] = metadata.flightID }
        let feature: [String: Any] = [
            "type": "Feature",
            "properties": properties,
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
        /// `properties.r2c_flight_id` (both platforms); nil in files written before it existed.
        public let flightID: String?

        public init(title: String, remoteID: String, points: [ClueBindingPoint], flightID: String? = nil) {
            self.title = title
            self.remoteID = remoteID
            self.points = points
            self.flightID = flightID
        }
    }

    /// Reads an archive written by either platform. Coordinates are [lng, lat, alt, timeMs] as strings
    /// or numbers. Per-point timing uses [FlightArchivePointTiming] (compact writer format; legacy
    /// absolute arrays still accepted).
    public static func decodeArchive(_ data: Data) -> ArchiveContents? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let feature = (root["features"] as? [[String: Any]])?.first,
              let geometry = feature["geometry"] as? [String: Any],
              let coordinates = geometry["coordinates"] as? [[Any]]
        else { return nil }
        let properties = feature["properties"] as? [String: Any] ?? [:]
        let r2c = properties["r2c_prop"] as? [String: Any] ?? [:]
        let received = FlightArchivePointTiming.readReceivedAtMs(properties: properties, pointCount: coordinates.count)
        let droneClock = FlightArchivePointTiming.readDroneClock(properties: properties, pointCount: coordinates.count)
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
            points.append(ClueBindingPoint(timeMs: Int64(time), receivedAtMs: received[index], latitude: lat, longitude: lng,
                                           altitudeMeters: altitude, source: "archive", droneClock: droneClock[index]))
        }
        let flightID = (properties["r2c_flight_id"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return ArchiveContents(title: properties["title"] as? String ?? "", remoteID: r2c["rid"] as? String ?? "",
                               points: points, flightID: flightID)
    }

    /// Match the confirmed pilot label used for live CalTopo publication.
    public static func archiveTitle(
        for track: RidAircraftTrack,
        metadata: RidTrackArchiveMetadata,
        timeZone: TimeZone = .current
    ) -> String {
        let mappedID = metadata.mappedID.isEmpty ? track.aircraftID : metadata.mappedID
        let pilot = (metadata.flightReadiness?.dictionary["pilot"] as? [String: Any])?["callsign"] as? String ?? ""
        return CaltopoTrackLabel.androidCompatible(
            baseLabel: RidAircraftIdentity.flightLabel(mappedID: mappedID, pilotCallsign: pilot),
            firstWaypointAt: track.points.first?.receivedAt ?? track.lastObservation.receivedAt,
            timeZone: timeZone
        )
    }

    public static func suggestedFilename(
        for track: RidAircraftTrack,
        ownershipGrantedAt grant: Date? = nil,
        timeZone: TimeZone = .current
    ) -> String {
        let first = track.points.first?.receivedAt ?? track.lastObservation.receivedAt
        let startDate = OwnedFlightFileName.stamp(grant: grant, firstStored: first)
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
