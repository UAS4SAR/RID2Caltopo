import Foundation

public struct OperationalNotamCoordinate: Codable, Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

public enum OperationalNotamGeometry: Codable, Sendable, Equatable {
    case point(OperationalNotamCoordinate)
    case line([OperationalNotamCoordinate])
    case polygon([[OperationalNotamCoordinate]])
    case collection([OperationalNotamGeometry])
}

public enum OperationalNotamSeverity: String, Codable, Sendable, Equatable, CaseIterable {
    case neutral
    case normal
    case caution
    case danger
}

public struct OperationalNotamAltitudeBand: Codable, Sendable, Equatable {
    public let floorFeetMSL: Double?
    public let ceilingFeetMSL: Double?
    public let floorLabel: String
    public let ceilingLabel: String
    public let reference: String?

    public init(
        floorFeetMSL: Double?,
        ceilingFeetMSL: Double?,
        floorLabel: String,
        ceilingLabel: String,
        reference: String?
    ) {
        self.floorFeetMSL = floorFeetMSL
        self.ceilingFeetMSL = ceilingFeetMSL
        self.floorLabel = floorLabel
        self.ceilingLabel = ceilingLabel
        self.reference = reference
    }
}

public struct OperationalNotam: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let title: String
    public let summary: String
    public let distanceNM: Double?
    public let bearingDegrees: Double?
    public let intersectsPilotArea: Bool
    public let effectiveText: String
    public let details: String
    public let rawText: String
    public let reference: String
    public let lastUpdated: String
    public let severity: OperationalNotamSeverity
    public let altitudeBand: OperationalNotamAltitudeBand?
    public let geometries: [OperationalNotamGeometry]

    public init(
        id: String,
        title: String,
        summary: String,
        distanceNM: Double?,
        bearingDegrees: Double? = nil,
        intersectsPilotArea: Bool = false,
        effectiveText: String = "",
        details: String = "",
        rawText: String = "",
        reference: String = "",
        lastUpdated: String = "",
        severity: OperationalNotamSeverity = .normal,
        altitudeBand: OperationalNotamAltitudeBand? = nil,
        geometries: [OperationalNotamGeometry] = []
    ) {
        self.id = id
        self.title = title
        self.summary = summary
        self.distanceNM = distanceNM
        self.bearingDegrees = bearingDegrees
        self.intersectsPilotArea = intersectsPilotArea
        self.effectiveText = effectiveText
        self.details = details
        self.rawText = rawText
        self.reference = reference
        self.lastUpdated = lastUpdated
        self.severity = severity
        self.altitudeBand = altitudeBand
        self.geometries = geometries
    }
}

public enum OperationalNotamPolicy {
    public static let nauticalMilesPerStatuteMile = 0.868976

    public static func sorted(_ notices: [OperationalNotam]) -> [OperationalNotam] {
        notices.sorted {
            let left = ($0.intersectsPilotArea ? 0 : 1, severityRank($0.severity), $0.distanceNM ?? .greatestFiniteMagnitude, $0.title)
            let right = ($1.intersectsPilotArea ? 0 : 1, severityRank($1.severity), $1.distanceNM ?? .greatestFiniteMagnitude, $1.title)
            return left < right
        }
    }

    public static func filtered(
        _ notices: [OperationalNotam],
        radiusStatuteMiles: Int
    ) -> (visible: [OperationalNotam], suppressed: Int) {
        let radiusNM = Double(radiusStatuteMiles) * nauticalMilesPerStatuteMile
        let visible = notices.filter {
            $0.intersectsPilotArea || $0.distanceNM == nil || $0.distanceNM! <= radiusNM
        }
        return (visible, notices.count - visible.count)
    }

    public static func faaQueryRadiusNM(radiusStatuteMiles: Int) -> Int {
        max(1, Int(ceil(Double(radiusStatuteMiles) * nauticalMilesPerStatuteMile)))
    }

    public static func chipSeverity(
        notices: [OperationalNotam],
        configured: Bool,
        hasError: Bool
    ) -> OperationalNotamSeverity {
        if hasError, notices.isEmpty { return .neutral }
        if notices.contains(where: { $0.severity == .danger }) { return .danger }
        if notices.contains(where: { $0.intersectsPilotArea || $0.severity == .caution }) { return .caution }
        return configured ? .normal : .neutral
    }

    public static func chipLabel(
        notices: [OperationalNotam],
        configured: Bool,
        loading: Bool,
        hasError: Bool
    ) -> String {
        if loading { return "NOTAMs updating…" }
        if !configured { return "NOTAMs not configured" }
        if hasError, notices.isEmpty { return "NOTAMs unavailable" }
        if hasError { return "NOTAMs stale • review" }
        if let notice = notices.first(where: { $0.intersectsPilotArea && $0.severity == .danger }) {
            return "NOTAMs: RESTRICTED \(distanceLabel(notice))"
        }
        if let notice = notices.first(where: { $0.intersectsPilotArea }) {
            return "NOTAMs: NOTICE \(distanceLabel(notice))"
        }
        if !notices.isEmpty { return "NOTAMs: \(notices.count) nearby" }
        return configured ? "No notices returned" : "NOTAMs not configured"
    }

    public static func inferSeverity(text: String, intersectsPilotArea: Bool) -> OperationalNotamSeverity {
        let value = text.uppercased()
        let restrictive = value.contains("TFR") || value.contains("TEMPORARY FLIGHT RESTRICTION")
            || value.contains("RESTRICT") || value.contains("PROHIBITED") || value.contains(" UAS")
        if restrictive, intersectsPilotArea { return .danger }
        if restrictive || value.contains("HAZARD") { return .caution }
        return .normal
    }

    private static func severityRank(_ severity: OperationalNotamSeverity) -> Int {
        switch severity {
        case .danger: 0
        case .caution: 1
        case .normal: 2
        case .neutral: 3
        }
    }

    private static func distanceLabel(_ notice: OperationalNotam) -> String {
        notice.distanceNM.map {
            String(format: "%.1f mi", $0 / nauticalMilesPerStatuteMile)
        } ?? "pilot area"
    }
}

public enum OperationalNotamParser {
    private static let earthRadiusNM = 3_440.065

    public static func parseResponse(
        _ data: Data,
        pilot: OperationalNotamCoordinate,
        operatingRadiusNM: Double = 2
    ) throws -> [OperationalNotam] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let response = root["data"] as? [String: Any]
        else { throw CocoaError(.fileReadCorruptFile) }
        let features = response["geojson"] as? [[String: Any]] ?? []
        return OperationalNotamPolicy.sorted(features.compactMap {
            parseFeature($0, pilot: pilot, operatingRadiusNM: operatingRadiusNM)
        })
    }

    public static func parseFeature(
        _ feature: [String: Any],
        pilot: OperationalNotamCoordinate,
        operatingRadiusNM: Double
    ) -> OperationalNotam? {
        let properties = feature["properties"] as? [String: Any]
        let core = properties?["coreNOTAMData"] as? [String: Any]
        let notam = core?["notam"] as? [String: Any] ?? [:]
        let rawText = rawTranslation(core?["notamTranslation"])
        let text = string(notam["text"])
        let location = string(notam["icaoLocation"]).isEmpty ? string(notam["location"]) : string(notam["icaoLocation"])
        let number = [string(notam["series"]), string(notam["number"]), string(notam["year"])]
            .filter { !$0.isEmpty }.joined(separator: "/")
        let reference = [location, number].filter { !$0.isEmpty }.joined(separator: " ")
        let title = [reference, text].filter { !$0.isEmpty }.joined(separator: reference.isEmpty ? "" : " - ")
        let geometryObject = feature["geometry"] as? [String: Any]
        var geometries = geometryObject.flatMap(parseGeometry).map { [$0] } ?? []
        if let radius = radiusGeometry(from: rawText.isEmpty ? text : rawText),
           isRestrictiveRadiusText("\(reference) \(text) \(rawText)") {
            geometries = [.polygon([circle(center: radius.center, radiusNM: radius.radiusNM)])]
        }
        let proximity = nearestProximity(from: pilot, geometries: geometries)
        let intersects = (proximity?.distanceNM ?? .greatestFiniteMagnitude) <= operatingRadiusNM
        let combined = "\(title) \(rawText)"
        let severity = OperationalNotamPolicy.inferSeverity(text: combined, intersectsPilotArea: intersects)
        let start = string(notam["effectiveStart"])
        let end = string(notam["effectiveEnd"])
        let effective = start.isEmpty ? (end.isEmpty ? "" : "Active until \(end)")
            : (end.isEmpty ? "Active from \(start)" : "Active \(start) to \(end)")
        let summary = humanizedSummary(text: combined, intersects: intersects, distanceNM: proximity?.distanceNM)
        let id = string(notam["id"])
        return OperationalNotam(
            id: id.isEmpty ? (reference.isEmpty ? UUID().uuidString : reference) : id,
            title: humanizedTitle(text: combined, fallback: title.isEmpty ? "Nearby NOTAM" : title),
            summary: summary,
            distanceNM: proximity?.distanceNM,
            bearingDegrees: proximity?.bearingDegrees,
            intersectsPilotArea: intersects,
            effectiveText: effective,
            details: [summary, effective, rawText].filter { !$0.isEmpty }.joined(separator: "\n\n"),
            rawText: rawText,
            reference: reference,
            lastUpdated: string(notam["lastUpdated"]),
            severity: severity,
            altitudeBand: parseAltitudeBand(rawText.isEmpty ? text : rawText),
            geometries: geometries
        )
    }

    public static func parseGeometry(_ object: [String: Any]) -> OperationalNotamGeometry? {
        switch string(object["type"]) {
        case "Point":
            guard let raw = object["coordinates"], let coordinate = coordinate(raw) else { return nil }
            return .point(coordinate)
        case "LineString":
            let values = (object["coordinates"] as? [Any] ?? []).compactMap(coordinate)
            return values.count >= 2 ? .line(values) : nil
        case "Polygon":
            let rings = (object["coordinates"] as? [Any] ?? []).compactMap { value -> [OperationalNotamCoordinate]? in
                let ring = (value as? [Any] ?? []).compactMap(coordinate)
                return ring.count >= 3 ? ring : nil
            }
            return rings.isEmpty ? nil : .polygon(rings)
        case "MultiPolygon":
            let polygons = (object["coordinates"] as? [Any] ?? []).compactMap { value -> OperationalNotamGeometry? in
                let rings = (value as? [Any] ?? []).compactMap { raw -> [OperationalNotamCoordinate]? in
                    let ring = (raw as? [Any] ?? []).compactMap(coordinate)
                    return ring.count >= 3 ? ring : nil
                }
                return rings.isEmpty ? nil : .polygon(rings)
            }
            return polygons.isEmpty ? nil : .collection(polygons)
        case "GeometryCollection":
            let values = (object["geometries"] as? [[String: Any]] ?? []).compactMap(parseGeometry)
            return values.isEmpty ? nil : .collection(values)
        default: return nil
        }
    }

    public static func parseAltitudeBand(_ text: String) -> OperationalNotamAltitudeBand? {
        let upper = text.uppercased().replacingOccurrences(of: ",", with: "")
        let pattern = #"(?:SFC|([0-9]+)\s*FT)\s*(?:-|TO|THRU)\s*([0-9]+)\s*FT\s*(AGL|MSL)?"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: upper, range: NSRange(upper.startIndex..., in: upper))
        else { return nil }
        func group(_ index: Int) -> String? {
            guard match.range(at: index).location != NSNotFound,
                  let range = Range(match.range(at: index), in: upper) else { return nil }
            return String(upper[range])
        }
        let floor = group(1).flatMap(Double.init) ?? 0
        let ceiling = group(2).flatMap(Double.init)
        let reference = group(3)
        return OperationalNotamAltitudeBand(
            floorFeetMSL: reference == "AGL" ? nil : floor,
            ceilingFeetMSL: reference == "AGL" ? nil : ceiling,
            floorLabel: floor == 0 ? "SFC" : "\(Int(floor)) FT",
            ceilingLabel: ceiling.map { "\(Int($0)) FT" } ?? "UNL",
            reference: reference
        )
    }

    private struct Proximity { let distanceNM: Double; let bearingDegrees: Double? }

    private static func nearestProximity(
        from origin: OperationalNotamCoordinate,
        geometries: [OperationalNotamGeometry]
    ) -> Proximity? {
        geometries.compactMap { proximity(from: origin, geometry: $0) }.min { $0.distanceNM < $1.distanceNM }
    }

    private static func proximity(from origin: OperationalNotamCoordinate, geometry: OperationalNotamGeometry) -> Proximity? {
        switch geometry {
        case let .point(value): return pointProximity(origin, value)
        case let .line(values): return values.map { pointProximity(origin, $0) }.min { $0.distanceNM < $1.distanceNM }
        case let .polygon(rings):
            if let outer = rings.first, pointInPolygon(origin, outer) { return Proximity(distanceNM: 0, bearingDegrees: nil) }
            return rings.flatMap { $0 }.map { pointProximity(origin, $0) }.min { $0.distanceNM < $1.distanceNM }
        case let .collection(values):
            return values.compactMap { proximity(from: origin, geometry: $0) }.min { $0.distanceNM < $1.distanceNM }
        }
    }

    private static func pointProximity(_ origin: OperationalNotamCoordinate, _ target: OperationalNotamCoordinate) -> Proximity {
        let lat1 = origin.latitude * .pi / 180
        let lat2 = target.latitude * .pi / 180
        let dLat = (target.latitude - origin.latitude) * .pi / 180
        let dLon = (target.longitude - origin.longitude) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        let distance = earthRadiusNM * 2 * atan2(sqrt(a), sqrt(max(0, 1 - a)))
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return Proximity(distanceNM: distance, bearingDegrees: (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360))
    }

    private static func pointInPolygon(_ point: OperationalNotamCoordinate, _ ring: [OperationalNotamCoordinate]) -> Bool {
        guard ring.count >= 3 else { return false }
        var inside = false
        var previous = ring.last!
        for current in ring {
            if (current.latitude > point.latitude) != (previous.latitude > point.latitude) {
                let longitude = (previous.longitude - current.longitude) * (point.latitude - current.latitude)
                    / ((previous.latitude - current.latitude) == 0 ? 1e-12 : previous.latitude - current.latitude)
                    + current.longitude
                if point.longitude < longitude { inside.toggle() }
            }
            previous = current
        }
        return inside
    }

    private static func coordinate(_ value: Any) -> OperationalNotamCoordinate? {
        guard let numbers = value as? [Any], numbers.count >= 2,
              let longitude = number(numbers[0]), let latitude = number(numbers[1]),
              (-90 ... 90).contains(latitude), (-180 ... 180).contains(longitude)
        else { return nil }
        return OperationalNotamCoordinate(latitude: latitude, longitude: longitude)
    }

    private static func rawTranslation(_ value: Any?) -> String {
        for item in value as? [[String: Any]] ?? [] {
            let domestic = string(item["domestic_message"])
            if !domestic.isEmpty { return domestic }
            let icao = string(item["icao_message"])
            if !icao.isEmpty { return icao.replacingOccurrences(of: "\n", with: " ") }
        }
        return ""
    }

    private static func humanizedTitle(text: String, fallback: String) -> String {
        let upper = text.uppercased()
        if upper.contains("TEMPORARY FLIGHT RESTRICTION") || upper.contains(" TFR") { return "Temporary flight restriction" }
        if upper.contains("UAS") && upper.contains("RESTRICT") { return "UAS restriction" }
        if upper.contains("HAZARD") { return "Aviation hazard" }
        return fallback
    }

    private static func humanizedSummary(text: String, intersects: Bool, distanceNM: Double?) -> String {
        let kind = humanizedTitle(text: text, fallback: "Aviation notice")
        if intersects { return "\(kind) intersects the pilot operating area." }
        if let distanceNM {
            return "\(kind) is \(String(format: "%.1f", distanceNM / OperationalNotamPolicy.nauticalMilesPerStatuteMile)) mi from the pilot."
        }
        return kind
    }

    private static func radiusGeometry(from text: String) -> (center: OperationalNotamCoordinate, radiusNM: Double)? {
        let upper = text.uppercased()
        guard let radiusRegex = try? NSRegularExpression(pattern: #"([0-9]+(?:\.[0-9]+)?)NM RADIUS"#),
              let radiusMatch = radiusRegex.firstMatch(in: upper, range: NSRange(upper.startIndex..., in: upper)),
              let radiusRange = Range(radiusMatch.range(at: 1), in: upper),
              let radius = Double(upper[radiusRange]),
              let coordinateRegex = try? NSRegularExpression(pattern: #"([0-9]{6}[NS])([0-9]{7}[EW])"#),
              let coordinateMatch = coordinateRegex.firstMatch(in: upper, range: NSRange(upper.startIndex..., in: upper)),
              let latRange = Range(coordinateMatch.range(at: 1), in: upper),
              let lonRange = Range(coordinateMatch.range(at: 2), in: upper),
              let latitude = compactDMS(String(upper[latRange]), degreeDigits: 2),
              let longitude = compactDMS(String(upper[lonRange]), degreeDigits: 3)
        else { return nil }
        return (OperationalNotamCoordinate(latitude: latitude, longitude: longitude), radius)
    }

    private static func compactDMS(_ value: String, degreeDigits: Int) -> Double? {
        guard value.count == degreeDigits + 5, let hemisphere = value.last else { return nil }
        let digits = String(value.dropLast())
        guard let degrees = Double(digits.prefix(degreeDigits)),
              let minutes = Double(digits.dropFirst(degreeDigits).prefix(2)),
              let seconds = Double(digits.suffix(2)) else { return nil }
        let result = degrees + minutes / 60 + seconds / 3600
        return hemisphere == "S" || hemisphere == "W" ? -result : result
    }

    private static func circle(center: OperationalNotamCoordinate, radiusNM: Double, steps: Int = 48) -> [OperationalNotamCoordinate] {
        let latitude = center.latitude * .pi / 180
        let longitude = center.longitude * .pi / 180
        let angular = radiusNM / earthRadiusNM
        return (0 ... steps).map { index in
            let bearing = 2 * Double.pi * Double(index) / Double(steps)
            let lat = asin(sin(latitude) * cos(angular) + cos(latitude) * sin(angular) * cos(bearing))
            let lon = longitude + atan2(sin(bearing) * sin(angular) * cos(latitude), cos(angular) - sin(latitude) * sin(lat))
            return OperationalNotamCoordinate(latitude: lat * 180 / .pi, longitude: lon * 180 / .pi)
        }
    }

    private static func isRestrictiveRadiusText(_ value: String) -> Bool {
        let upper = value.uppercased()
        return upper.contains("TFR") || upper.contains("TEMPORARY FLIGHT RESTRICTION")
            || upper.contains("NTL DEFENSE AIRSPACE")
    }

    private static func string(_ value: Any?) -> String {
        (value as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func number(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue }
        if let text = value as? String { return Double(text) }
        return nil
    }
}
