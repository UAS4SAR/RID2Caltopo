import Foundation

/// One clue or local marker placed in a flight KMZ.
public struct OperationalFlightKMZClue: Sendable, Equatable {
    public let title: String
    public let description: String
    public let capturedAt: Date
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let jpegData: Data?
    public let localOnly: Bool
    public let binding: ClueBinding?

    public init(title: String, description: String, capturedAt: Date, latitude: Double, longitude: Double,
                altitudeMeters: Double?, jpegData: Data?, localOnly: Bool, binding: ClueBinding?) {
        self.title = title
        self.description = description
        self.capturedAt = capturedAt
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.jpegData = jpegData
        self.localOnly = localOnly
        self.binding = binding
    }

    public init(record: OperationalClueRecord, jpegData: Data?) {
        self.init(title: record.title, description: record.publishedDescription, capturedAt: record.capturedAt,
                  latitude: record.clueLatitude, longitude: record.clueLongitude,
                  altitudeMeters: record.clueAltitudeMeters, jpegData: jpegData,
                  localOnly: record.uploadState == .localOnly, binding: record.binding)
    }
}

public struct OperationalFlightKMZPoint: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public init(latitude: Double, longitude: Double, altitudeMeters: Double?) {
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
    }
}

/// Local backup KMZ for a flight: its track plus every clue and local marker it owns. Written for
/// every recorded flight, with or without clues. Android writes the same layout (FlightKmz.kt).
public enum OperationalFlightKMZ {
    public static func kml(title: String, points: [OperationalFlightKMZPoint], clues: [OperationalFlightKMZClue]) -> String {
        let posix = Locale(identifier: "en_US_POSIX")
        var kml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
          <Document>
            <name>\(escapeXML(title))</name>
            <Style id="trackStyle"><LineStyle><color>ffff0000</color><width>3</width></LineStyle></Style>

        """
        let coordinates = points.map {
            String(format: "%.6f,%.6f,%.1f", locale: posix, $0.longitude, $0.latitude, $0.altitudeMeters ?? 0)
        }
        if coordinates.count >= 2 {
            kml += "    <Placemark>\n      <name>\(escapeXML(title))</name>\n      <styleUrl>#trackStyle</styleUrl>\n"
            kml += "      <LineString><tessellate>1</tessellate><coordinates>\n"
            for coordinate in coordinates { kml += "        \(coordinate)\n" }
            kml += "      </coordinates></LineString>\n    </Placemark>\n"
        } else if let only = coordinates.first {
            kml += "    <Placemark>\n      <name>\(escapeXML(title))</name>\n"
            kml += "      <Point><coordinates>\(only)</coordinates></Point>\n    </Placemark>\n"
        }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        for (index, clue) in clues.enumerated() {
            kml += "    <Placemark>\n"
            kml += "      <name>\(escapeXML(clue.title))</name>\n"
            kml += "      <TimeStamp><when>\(iso.string(from: clue.capturedAt))</when></TimeStamp>\n"
            if clue.jpegData != nil {
                let description = clue.description.replacingOccurrences(of: "]]>", with: "]]&gt;")
                kml += "      <description><![CDATA[\(description)<br/><img src=\"files/clue_\(index).jpg\"/>]]></description>\n"
            } else if !clue.description.isEmpty {
                kml += "      <description>\(escapeXML(clue.description))</description>\n"
            }
            var data: [(String, String)] = [("r2c_local_only", clue.localOnly ? "true" : "false")]
            if let binding = clue.binding { data += ClueBindingText.extendedData(binding) }
            kml += "      <ExtendedData>\n"
            for (name, value) in data {
                kml += "        <Data name=\"\(escapeXML(name))\"><value>\(escapeXML(value))</value></Data>\n"
            }
            kml += "      </ExtendedData>\n"
            kml += String(format: "      <Point><coordinates>%.6f,%.6f,%.1f</coordinates></Point>\n", locale: posix,
                          clue.longitude, clue.latitude, clue.altitudeMeters ?? 0)
            kml += "    </Placemark>\n"
        }
        kml += "  </Document>\n</kml>\n"
        return kml
    }

    public static func archive(title: String, points: [OperationalFlightKMZPoint],
                               clues: [OperationalFlightKMZClue]) throws -> Data {
        var entries = [OperationalZipArchive.Entry(path: "doc.kml", data: Data(kml(title: title, points: points, clues: clues).utf8))]
        for (index, clue) in clues.enumerated() {
            if let jpeg = clue.jpegData { entries.append(.init(path: "files/clue_\(index).jpg", data: jpeg)) }
        }
        return try OperationalZipArchive.encode(entries, compress: true)
    }

    /// True when `data` is a complete KMZ (readable zip with doc.kml).
    public static func isValidArchive(_ data: Data) -> Bool {
        guard let entries = try? OperationalZipArchive.decode(data) else { return false }
        return entries.contains { $0.path == "doc.kml" && !$0.data.isEmpty }
    }

    public static func escapeXML(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}

/// Rebuilding a flight KMZ from archived track files (late clues, interrupted writes).
public extension OperationalFlightKMZ {
    static func points(_ contents: RidTrackGeoJSON.ArchiveContents) -> [OperationalFlightKMZPoint] {
        contents.points.map { OperationalFlightKMZPoint(latitude: $0.latitude, longitude: $0.longitude,
                                                        altitudeMeters: $0.altitudeMeters) }
    }

    static func candidate(id: String, contents: RidTrackGeoJSON.ArchiveContents) -> AwaitingMapClueMatch.Candidate {
        AwaitingMapClueMatch.Candidate(id: id, remoteID: contents.remoteID,
                                       times: contents.points.map { Date(timeIntervalSince1970: Double($0.timeMs) / 1_000) })
    }

    /// Clues owned by the archive `archiveID` among `archives` (same rule as live flights), oldest first.
    static func ownedClues(archiveID: String, archives: [String: RidTrackGeoJSON.ArchiveContents],
                           clues: [OperationalClueRecord]) -> [OperationalClueRecord] {
        let candidates = archives.map { candidate(id: $0.key, contents: $0.value) }
        return clues.filter { AwaitingMapClueMatch.ownerID($0, candidates: candidates) == archiveID }
            .sorted { $0.capturedAt < $1.capturedAt }
    }

    /// Binds clues saved without a usable binding to the archived flight's nearest stored point.
    static func bindingFallback(_ record: OperationalClueRecord,
                                contents: RidTrackGeoJSON.ArchiveContents) -> OperationalClueRecord {
        guard record.binding?.nearest == nil else { return record }
        var updated = record
        let captureMs = record.binding?.captureTimeMs ?? ClueBindingPoint.milliseconds(record.capturedAt)
        updated.binding = ClueBinder.bind(
            aircraftID: record.aircraftID, flightID: record.binding?.flightID, captureTimeMs: captureMs,
            captureTimeSource: record.binding?.captureTimeSource ?? "app-receive",
            captureReceivedAtMs: record.binding?.captureReceivedAtMs ?? ClueBindingPoint.milliseconds(record.capturedAt),
            points: contents.points, framePosition: record.binding?.framePosition,
            originIsWaypoint: false)
        return updated
    }
}
