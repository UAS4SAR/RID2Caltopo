import Foundation

public struct OperationalLandCoordinate: Codable, Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

public enum OperationalLandAgency: String, Codable, Sendable, Equatable, CaseIterable {
    case nationalParkService
    case fishAndWildlifeService
    case forestService
    case coloradoParksAndWildlife

    public var name: String {
        switch self {
        case .nationalParkService: "National Park Service"
        case .fishAndWildlifeService: "U.S. Fish and Wildlife Service"
        case .forestService: "U.S. Forest Service"
        case .coloradoParksAndWildlife: "Colorado Parks and Wildlife"
        }
    }

    public var rulesURL: URL {
        switch self {
        case .nationalParkService:
            URL(string: "https://www.nps.gov/orgs/aviationprogram/uncrewed-aircraft-systems.htm")!
        case .fishAndWildlifeService:
            URL(string: "https://www.fws.gov/law/uncrewed-aircraft-systems")!
        case .forestService:
            URL(string: "https://www.fs.usda.gov/Internet/FSE_DOCUMENTS/stelprd3847000.pdf")!
        case .coloradoParksAndWildlife:
            URL(string: "https://cpw.state.co.us/rules-and-regulations")!
        }
    }
}

public enum OperationalLandRule: String, Codable, Sendable, Equatable {
    case launchLandOperateRestricted
    case wildlifeDisturbanceRestricted
    case propertySpecificRules

    public var label: String {
        switch self {
        case .launchLandOperateRestricted: "Launch, landing, or operation restricted"
        case .wildlifeDisturbanceRestricted: "Launch, landing, and wildlife disturbance restricted"
        case .propertySpecificRules: "Property-specific rules—verify authorization"
        }
    }
}

public enum OperationalLandSeverity: String, Codable, Sendable, Equatable {
    case neutral
    case normal
    case caution
    case danger
}

public struct OperationalLandArea: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let name: String
    public let agency: OperationalLandAgency
    public let rule: OperationalLandRule
    public let polygons: [[[OperationalLandCoordinate]]]
    public let intersectsOperatingArea: Bool
    public let containsOperator: Bool
    public let distanceNM: Double
    public let detailsURL: URL?

    public init(
        id: String,
        name: String,
        agency: OperationalLandAgency,
        rule: OperationalLandRule,
        polygons: [[[OperationalLandCoordinate]]],
        intersectsOperatingArea: Bool,
        containsOperator: Bool,
        distanceNM: Double,
        detailsURL: URL? = nil
    ) {
        self.id = id
        self.name = name
        self.agency = agency
        self.rule = rule
        self.polygons = polygons
        self.intersectsOperatingArea = intersectsOperatingArea
        self.containsOperator = containsOperator
        self.distanceNM = distanceNM
        self.detailsURL = detailsURL
    }
}

public struct OperationalLandSource: Sendable, Equatable {
    public let id: String
    public let queryEndpoint: URL
    public let agency: OperationalLandAgency
    public let rule: OperationalLandRule
    public let nameFields: [String]
    public let identifierFields: [String]
    public let detailsURLFields: [String]
    public let whereClause: String

    public init(
        id: String,
        queryEndpoint: URL,
        agency: OperationalLandAgency,
        rule: OperationalLandRule,
        nameFields: [String],
        identifierFields: [String],
        detailsURLFields: [String] = [],
        whereClause: String = "1=1"
    ) {
        self.id = id
        self.queryEndpoint = queryEndpoint
        self.agency = agency
        self.rule = rule
        self.nameFields = nameFields
        self.identifierFields = identifierFields
        self.detailsURLFields = detailsURLFields
        self.whereClause = whereClause
    }
}

public enum OperationalLandRestriction {
    public static let sources: [OperationalLandSource] = [
        .init(
            id: "nps",
            queryEndpoint: URL(string: "https://services.arcgis.com/xOi1kZaI0eWDREZv/ArcGIS/rest/services/NPS_Regional_and_Park_Boundary/FeatureServer/1/query")!,
            agency: .nationalParkService,
            rule: .launchLandOperateRestricted,
            nameFields: ["UNIT_NAME", "PARKNAME"],
            identifierFields: ["UNIT_CODE", "FID"]
        ),
        .init(
            id: "fws-refuge",
            queryEndpoint: URL(string: "https://services.arcgis.com/QVENGdaPbd4LUkLV/arcgis/rest/services/National_Wildlife_Refuge_System_Boundaries/FeatureServer/0/query")!,
            agency: .fishAndWildlifeService,
            rule: .wildlifeDisturbanceRestricted,
            nameFields: ["ORGNAME"],
            identifierFields: ["ORGCODE", "OBJECTID"],
            whereClause: "RSL_TYPE='NWR'"
        ),
        .init(
            id: "usfs-wilderness",
            queryEndpoint: URL(string: "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_Wilderness_01/MapServer/0/query")!,
            agency: .forestService,
            rule: .launchLandOperateRestricted,
            nameFields: ["wildernessname", "WILDERNESSNAME"],
            identifierFields: ["areaid", "AREAID", "objectid", "OBJECTID"]
        ),
        .init(
            id: "cpw-properties",
            queryEndpoint: URL(string: "https://services5.arcgis.com/ttNGmDvKQA7oeDQ3/ArcGIS/rest/services/CPWAdminData/FeatureServer/5/query")!,
            agency: .coloradoParksAndWildlife,
            rule: .propertySpecificRules,
            nameFields: ["PropName"],
            identifierFields: ["GlobalID", "FID"],
            detailsURLFields: ["CPW_URL"],
            whereClause: "PropType IN ('SP','SWA')"
        ),
    ]

    public static func queryURL(
        source: OperationalLandSource,
        center: OperationalLandCoordinate,
        radiusStatuteMiles: Double
    ) -> URL? {
        let radiusNM = radiusStatuteMiles * 0.868976
        let latitudeDelta = radiusNM / 60
        let longitudeDelta = radiusNM / max(1, 60 * cos(center.latitude * .pi / 180))
        let envelope = [
            center.longitude - longitudeDelta,
            center.latitude - latitudeDelta,
            center.longitude + longitudeDelta,
            center.latitude + latitudeDelta,
        ].map { String(format: "%.7f", $0) }.joined(separator: ",")
        var components = URLComponents(url: source.queryEndpoint, resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "where", value: source.whereClause),
            URLQueryItem(name: "geometry", value: envelope),
            URLQueryItem(name: "geometryType", value: "esriGeometryEnvelope"),
            URLQueryItem(name: "inSR", value: "4326"),
            URLQueryItem(name: "spatialRel", value: "esriSpatialRelIntersects"),
            URLQueryItem(name: "outFields", value: "*"),
            URLQueryItem(name: "returnGeometry", value: "true"),
            URLQueryItem(name: "outSR", value: "4326"),
            URLQueryItem(name: "f", value: "geojson"),
        ]
        return components?.url
    }

    public static func parse(
        _ data: Data,
        source: OperationalLandSource,
        center: OperationalLandCoordinate,
        operatingRadiusNM: Double
    ) throws -> [OperationalLandArea] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CocoaError(.coderReadCorrupt)
        }
        if let error = root["error"] as? [String: Any] {
            let message = error["message"] as? String ?? "Boundary service returned an error"
            throw NSError(domain: "OperationalLandRestriction", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
        let features = root["features"] as? [[String: Any]] ?? []
        return features.compactMap { feature in
            guard let geometry = feature["geometry"] as? [String: Any],
                  let polygons = polygons(from: geometry), !polygons.isEmpty
            else { return nil }
            let properties = feature["properties"] as? [String: Any] ?? [:]
            let name = firstString(source.nameFields, in: properties) ?? source.agency.name
            let identifier = firstString(source.identifierFields, in: properties) ?? name
            let contains = polygons.contains { polygon in
                guard let outer = polygon.first else { return false }
                return pointInPolygon(center, ring: outer)
            }
            let distance = contains ? 0 : minimumBoundaryDistanceNM(center, polygons: polygons)
            return OperationalLandArea(
                id: "\(source.id):\(identifier)",
                name: name,
                agency: source.agency,
                rule: source.rule,
                polygons: polygons,
                intersectsOperatingArea: contains || distance <= operatingRadiusNM,
                containsOperator: contains,
                distanceNM: distance,
                detailsURL: firstString(source.detailsURLFields, in: properties).flatMap(URL.init(string:))
            )
        }
        .sorted {
            if $0.containsOperator != $1.containsOperator { return $0.containsOperator }
            if $0.intersectsOperatingArea != $1.intersectsOperatingArea { return $0.intersectsOperatingArea }
            if $0.distanceNM != $1.distanceNM { return $0.distanceNM < $1.distanceNM }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    public static func severity(
        _ areas: [OperationalLandArea],
        hasError: Bool,
        waitingForLocation: Bool = false
    ) -> OperationalLandSeverity {
        if waitingForLocation { return .neutral }
        if areas.contains(where: { $0.containsOperator && $0.rule != .propertySpecificRules }) { return .danger }
        if areas.contains(where: { $0.intersectsOperatingArea }) { return .caution }
        if hasError { return .neutral }
        return .normal
    }

    public static func chipLabel(
        _ areas: [OperationalLandArea],
        loading: Bool,
        hasError: Bool,
        waitingForLocation: Bool = false
    ) -> String {
        if waitingForLocation { return "Land rules pending" }
        if loading { return "Land rules updating…" }
        if areas.contains(where: { $0.containsOperator && $0.rule != .propertySpecificRules }) {
            return "Land rules: RESTRICTED"
        }
        let nearby = areas.filter(\.intersectsOperatingArea).count
        if nearby > 0 { return "Land rules: \(nearby) nearby" }
        if hasError { return "Land rules unavailable" }
        return "No mapped restrictions"
    }

    private static func firstString(_ keys: [String], in properties: [String: Any]) -> String? {
        for key in keys {
            guard let value = properties[key], !(value is NSNull) else { continue }
            let text = String(describing: value).trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { return text }
        }
        return nil
    }

    private static func polygons(from geometry: [String: Any]) -> [[[OperationalLandCoordinate]]]? {
        switch geometry["type"] as? String {
        case "Polygon":
            guard let rings = rings(from: geometry["coordinates"]) else { return nil }
            return [rings]
        case "MultiPolygon":
            return (geometry["coordinates"] as? [Any])?.compactMap(rings(from:))
        default:
            return nil
        }
    }

    private static func rings(from value: Any?) -> [[OperationalLandCoordinate]]? {
        (value as? [Any])?.compactMap { rawRing in
            guard let rawPoints = rawRing as? [Any] else { return nil }
            let points = rawPoints.compactMap { raw -> OperationalLandCoordinate? in
                guard let values = raw as? [Any], values.count >= 2,
                      let longitude = number(values[0]), let latitude = number(values[1])
                else { return nil }
                return .init(latitude: latitude, longitude: longitude)
            }
            return points.count >= 3 ? points : nil
        }
    }

    private static func number(_ value: Any) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue }
        return Double(String(describing: value))
    }

    private static func pointInPolygon(_ point: OperationalLandCoordinate, ring: [OperationalLandCoordinate]) -> Bool {
        guard ring.count >= 3 else { return false }
        var inside = false
        var j = ring.count - 1
        for i in ring.indices {
            let left = ring[i]
            let right = ring[j]
            let crosses = (left.latitude > point.latitude) != (right.latitude > point.latitude)
                && point.longitude < (right.longitude - left.longitude) * (point.latitude - left.latitude)
                    / ((right.latitude - left.latitude) == 0 ? .leastNonzeroMagnitude : (right.latitude - left.latitude))
                    + left.longitude
            if crosses { inside.toggle() }
            j = i
        }
        return inside
    }

    private static func minimumBoundaryDistanceNM(
        _ center: OperationalLandCoordinate,
        polygons: [[[OperationalLandCoordinate]]]
    ) -> Double {
        let latitudeScale = 60.0405
        let longitudeScale = latitudeScale * cos(center.latitude * .pi / 180)
        func localPoint(_ value: OperationalLandCoordinate) -> (x: Double, y: Double) {
            (
                (value.longitude - center.longitude) * longitudeScale,
                (value.latitude - center.latitude) * latitudeScale
            )
        }
        var closest = Double.infinity
        for ring in polygons.flatMap({ $0 }) where !ring.isEmpty {
            for index in ring.indices {
                let left = localPoint(ring[index])
                let right = localPoint(ring[(index + 1) % ring.count])
                let dx = right.x - left.x
                let dy = right.y - left.y
                let denominator = dx * dx + dy * dy
                let fraction = denominator == 0 ? 0 : max(0, min(1, -(left.x * dx + left.y * dy) / denominator))
                let x = left.x + fraction * dx
                let y = left.y + fraction * dy
                closest = min(closest, hypot(x, y))
            }
        }
        return closest
    }

    private static func distanceNM(_ left: OperationalLandCoordinate, _ right: OperationalLandCoordinate) -> Double {
        let earthRadiusNM = 3_440.065
        let lat1 = left.latitude * .pi / 180
        let lat2 = right.latitude * .pi / 180
        let deltaLat = (right.latitude - left.latitude) * .pi / 180
        let deltaLon = (right.longitude - left.longitude) * .pi / 180
        let a = sin(deltaLat / 2) * sin(deltaLat / 2)
            + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return earthRadiusNM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
