import Foundation

// Review data for the "Flights awaiting a map" panel. Android mirrors these rules in
// data/AwaitingMapFlightReview.kt; keep wording and thresholds identical.

extension RidGeometry {
    /// 16-point compass name for a bearing. The 8-point `cardinalDirection` used by
    /// aircraft displays is intentionally separate and unchanged.
    public static func cardinalDirection16(for bearing: Double) -> String {
        let names = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                     "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
        guard bearing.isFinite else { return "N" }
        let normalized = (bearing.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        return names[Int((normalized + 11.25) / 22.5) % names.count]
    }
}

/// Latitude/longitude bounding box of a recorded track.
public struct AwaitingMapBoundingBox: Equatable, Sendable {
    public let minLatitude: Double
    public let maxLatitude: Double
    public let minLongitude: Double
    public let maxLongitude: Double

    public init?(coordinates: [(latitude: Double, longitude: Double)]) {
        let valid = coordinates.filter {
            $0.latitude.isFinite && $0.longitude.isFinite
                && (-90 ... 90).contains($0.latitude) && (-180 ... 180).contains($0.longitude)
        }
        guard let first = valid.first else { return nil }
        var box = (first.latitude, first.latitude, first.longitude, first.longitude)
        for point in valid.dropFirst() {
            box.0 = min(box.0, point.latitude); box.1 = max(box.1, point.latitude)
            box.2 = min(box.2, point.longitude); box.3 = max(box.3, point.longitude)
        }
        (minLatitude, maxLatitude, minLongitude, maxLongitude) = box
    }

    public func contains(latitude: Double, longitude: Double) -> Bool {
        (minLatitude ... maxLatitude).contains(latitude) && (minLongitude ... maxLongitude).contains(longitude)
    }

    /// Nearest point of the box (edge or corner) by clamping each coordinate.
    public func nearestPoint(latitude: Double, longitude: Double) -> (latitude: Double, longitude: Double) {
        (min(max(latitude, minLatitude), maxLatitude), min(max(longitude, minLongitude), maxLongitude))
    }
}

/// Where the operator is relative to a flight's bounding box.
public enum AwaitingMapFlightProximity: Equatable, Sendable {
    case inside
    case away(distanceMeters: Double, bearingDegrees: Int, cardinal: String)

    public static func evaluate(box: AwaitingMapBoundingBox, latitude: Double, longitude: Double) -> AwaitingMapFlightProximity? {
        guard latitude.isFinite, longitude.isFinite else { return nil }
        if box.contains(latitude: latitude, longitude: longitude) { return .inside }
        let nearest = box.nearestPoint(latitude: latitude, longitude: longitude)
        guard let position = RidGeometry.relativePosition(fromLatitude: latitude, longitude: longitude,
                                                          toLatitude: nearest.latitude, longitude: nearest.longitude)
        else { return nil }
        let degrees = Int(position.bearingDegrees.rounded()) % 360
        return .away(distanceMeters: position.distanceMeters, bearingDegrees: degrees,
                     cardinal: RidGeometry.cardinalDirection16(for: Double(degrees)))
    }
}

/// Operator-facing wording for the panel; Android uses the same strings.
public enum AwaitingMapFlightText {
    public static let title = "Flights awaiting a map"
    public static let noMapBanner = "Select an incident map to publish. Flights and clues remain saved locally."
    public static let insideArea = "You are within this flight's area"
    public static let distanceUnavailable = "Distance unavailable"
    public static let locationUnavailable = "Location unavailable · distances not shown"
    public static let discardTitle = "Discard flight log?"

    /// Feet rounded to 10 below one mile; miles to one decimal from one mile up.
    public static func distance(meters: Double) -> String {
        let feet = meters / 0.3048
        if feet < 5_280 { return "\(Int((feet / 10).rounded()) * 10) ft" }
        return String(format: "%.1f mi", locale: Locale(identifier: "en_US_POSIX"), feet / 5_280)
    }

    public static func duration(seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.isFinite ? seconds : 0))
        let hours = total / 3_600, minutes = (total % 3_600) / 60, secs = total % 60
        if hours > 0 { return String(format: "Duration %dh %02dm %02ds", hours, minutes, secs) }
        return String(format: "Duration %dm %02ds", minutes, secs)
    }

    public static func cluePhotos(_ count: Int) -> String {
        "\(count) clue photo\(count == 1 ? "" : "s")"
    }

    public static func location(_ proximity: AwaitingMapFlightProximity?) -> String {
        switch proximity {
        case .inside: return insideArea
        case let .away(meters, degrees, cardinal):
            return "\(distance(meters: meters)) from current location at bearing \(degrees)° \(cardinal)"
        case nil: return distanceUnavailable
        }
    }

    public static func flightCountHeader(_ count: Int) -> String {
        "\(count) FLIGHT\(count == 1 ? "" : "S") ON THIS DEVICE"
    }

    /// `fixTime` is already formatted for the device locale.
    public static func locationSummary(accuracyMeters: Double?, fixTime: String) -> String {
        var parts = ["Distances from your current location"]
        if let accuracyMeters, accuracyMeters.isFinite, accuracyMeters >= 0 {
            parts.append("GPS ±\(Int((accuracyMeters / 0.3048).rounded())) ft")
        }
        parts.append(fixTime)
        return parts.joined(separator: " · ")
    }

    public static func discardMessage(cluePhotoCount: Int) -> String {
        cluePhotoCount == 0
            ? "This permanently deletes the track from this device."
            : "This permanently deletes the track and \(cluePhotos(cluePhotoCount)) from this device."
    }
}

extension AwaitingMapFlight {
    public var boundingBox: AwaitingMapBoundingBox? {
        AwaitingMapBoundingBox(coordinates: points.map { ($0.latitude, $0.longitude) })
    }

    /// Last minus first recorded point.
    public var durationSeconds: TimeInterval {
        guard points.count > 1 else { return 0 }
        return max(0, lastTime.timeIntervalSince(firstTime))
    }

    public func proximity(latitude: Double, longitude: Double) -> AwaitingMapFlightProximity? {
        boundingBox.flatMap { AwaitingMapFlightProximity.evaluate(box: $0, latitude: latitude, longitude: longitude) }
    }
}

/// The single rule that associates clue photos with an awaiting-map flight. Used for
/// clue routing, binding on publish, the panel's photo count, and Discard.
public enum AwaitingMapClueMatch {
    /// Clues captured shortly after the last RID point still belong to the flight.
    public static let trailingSeconds: TimeInterval = 30

    public static func matches(clueAircraftID: String, capturedAt: Date,
                               flightRemoteID: String, firstTime: Date, lastTime: Date) -> Bool {
        RidTrackStore.canonicalAircraftID(clueAircraftID) == RidTrackStore.canonicalAircraftID(flightRemoteID)
            && capturedAt >= firstTime
            && capturedAt.timeIntervalSince(lastTime) <= trailingSeconds
    }

    public static func matches(_ clue: OperationalClueRecord, flight: AwaitingMapFlight) -> Bool {
        matches(clueAircraftID: clue.aircraftID, capturedAt: clue.capturedAt,
                flightRemoteID: flight.remoteID, firstTime: flight.firstTime, lastTime: flight.lastTime)
    }

    /// Clues that belong to `flight` and to no other known flight. A clue in this flight's
    /// trailing window that falls inside another flight of the same aircraft stays with that one.
    public static func ownedClues(_ clues: [OperationalClueRecord], flight: AwaitingMapFlight,
                                  otherFlights: [AwaitingMapFlight]) -> [OperationalClueRecord] {
        let others = otherFlights.filter {
            $0.id != flight.id
                && RidTrackStore.canonicalAircraftID($0.remoteID) == RidTrackStore.canonicalAircraftID(flight.remoteID)
        }
        return clues.filter { clue in
            matches(clue, flight: flight) && !(clue.capturedAt > flight.lastTime && others.contains {
                clue.capturedAt >= $0.firstTime && clue.capturedAt <= $0.lastTime
            })
        }
    }
}

/// Local track archive files written for one flight (GeoJSON plus optional clue KMZ).
public struct AwaitingMapArchiveDeletion: Equatable, Sendable {
    public var deleted: [String] = []
    public var failures: [String] = []
    public init(deleted: [String] = [], failures: [String] = []) {
        self.deleted = deleted; self.failures = failures
    }
}

public enum AwaitingMapFlightArchive {
    /// Exact filenames AppleTrackArchiveStore writes for a flight.
    public static func filenames(remoteID: String, startedAt: Date, timeZone: TimeZone = .current) -> [String] {
        let base = RidTrackGeoJSON.suggestedFilenameBase(aircraftID: remoteID, startDate: startedAt, timeZone: timeZone)
        return [base + ".json", base + ".kmz"]
    }

    /// Day folders the archive could be in: the start day, the end day, and the day after
    /// the end (archiving runs when the flight ends and can cross midnight).
    public static func candidateDayNames(firstTime: Date, lastTime: Date) -> [String] {
        var names: [String] = []
        for date in [firstTime, lastTime, lastTime.addingTimeInterval(86_400)] {
            let name = AppleFlightStorage.dayName(date)
            if !names.contains(name) { names.append(name) }
        }
        return names
    }

    /// Deletes only this flight's exact archive files under `root`. Returns "day/file" paths.
    public static func deleteFiles(for flight: AwaitingMapFlight, root: URL, timeZone: TimeZone = .current,
                                   fileManager: FileManager = .default) -> AwaitingMapArchiveDeletion {
        var result = AwaitingMapArchiveDeletion()
        let names = filenames(remoteID: flight.remoteID, startedAt: flight.firstTime, timeZone: timeZone)
        for day in candidateDayNames(firstTime: flight.firstTime, lastTime: flight.lastTime) {
            let directory = root.appendingPathComponent(day, isDirectory: true)
            for name in names {
                let file = directory.appendingPathComponent(name)
                guard file.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL,
                      fileManager.fileExists(atPath: file.path) else { continue }
                do {
                    try fileManager.removeItem(at: file)
                    result.deleted.append("\(day)/\(name)")
                } catch {
                    result.failures.append("\(day)/\(name): \(error.localizedDescription)")
                }
            }
        }
        return result
    }
}

public struct AwaitingMapFlightDiscardResult: Equatable, Sendable {
    public var entryRemoved = false
    public var deletedClueIDs: [UUID] = []
    public var archive = AwaitingMapArchiveDeletion()
    public var failures: [String] = []
    public var succeeded: Bool { entryRemoved && failures.isEmpty && archive.failures.isEmpty }

    public func logSummary(for flight: AwaitingMapFlight) -> String {
        "Discarded awaiting-map flight id=\(flight.id) remoteId=\(flight.remoteID) label=\(flight.label) "
            + "start=\(ISO8601DateFormatter().string(from: flight.firstTime)) entryRemoved=\(entryRemoved) "
            + "clues=\(deletedClueIDs.count)[\(deletedClueIDs.map { $0.uuidString.lowercased() }.joined(separator: ","))] "
            + "archive=[\(archive.deleted.joined(separator: ","))]"
            + (failures.isEmpty && archive.failures.isEmpty ? "" : " failures=[\((failures + archive.failures).joined(separator: "; "))]")
    }
}

@MainActor
public enum AwaitingMapFlightDiscarder {
    /// Deletes the flight's clue photos and archive files first, then its journal entry, so a
    /// failure leaves the entry in place for another attempt. Nothing outside the flight is touched.
    public static func discard(
        flight: AwaitingMapFlight,
        journal: AwaitingMapFlightJournal,
        clueIDs: [UUID],
        deleteClue: (UUID) -> Bool,
        deleteArchive: () async -> AwaitingMapArchiveDeletion
    ) async -> AwaitingMapFlightDiscardResult {
        var result = AwaitingMapFlightDiscardResult()
        for id in clueIDs {
            if deleteClue(id) { result.deletedClueIDs.append(id) } else { result.failures.append("clue \(id.uuidString.lowercased())") }
        }
        result.archive = await deleteArchive()
        guard result.failures.isEmpty, result.archive.failures.isEmpty else { return result }
        do {
            result.entryRemoved = try journal.discard(id: flight.id)
            if !result.entryRemoved { result.failures.append("journal entry not found") }
        } catch {
            result.failures.append("journal: \(error.localizedDescription)")
        }
        return result
    }
}
