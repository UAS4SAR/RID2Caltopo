import Foundation

public enum OperationalAirspaceSeverity: String, Codable, Sendable, Equatable {
    case neutral, normal, caution, danger
}

public struct OperationalAirspaceCoordinate: Codable, Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

public struct OperationalFacilityMapRecord: Codable, Sendable, Equatable, Identifiable {
    public let objectID: Int64
    public let ceilingFeet: Int?
    public let unit: String
    public let primaryAirportFAAID: String
    public let primaryAirportICAO: String
    public let primaryAirportName: String
    public let laancAvailable: Bool
    public let airspaceClasses: [String]
    public let rings: [[OperationalAirspaceCoordinate]]

    public var id: Int64 { objectID }

    public init(
        objectID: Int64, ceilingFeet: Int?, unit: String,
        primaryAirportFAAID: String, primaryAirportICAO: String,
        primaryAirportName: String, laancAvailable: Bool,
        airspaceClasses: [String],
        rings: [[OperationalAirspaceCoordinate]] = []
    ) {
        self.objectID = objectID
        self.ceilingFeet = ceilingFeet
        self.unit = unit
        self.primaryAirportFAAID = primaryAirportFAAID
        self.primaryAirportICAO = primaryAirportICAO
        self.primaryAirportName = primaryAirportName
        self.laancAvailable = laancAvailable
        self.airspaceClasses = airspaceClasses
        self.rings = rings
    }
}

public struct OperationalAirspaceState: Sendable, Equatable {
    public var loading: Bool
    public var severity: OperationalAirspaceSeverity
    public var chipLabel: String
    public var summary: String
    public var detail: String
    public var records: [OperationalFacilityMapRecord]
    public var errorMessage: String?

    public init(
        loading: Bool = false,
        severity: OperationalAirspaceSeverity = .neutral,
        chipLabel: String = "Airspace pending",
        summary: String = "",
        detail: String = "",
        records: [OperationalFacilityMapRecord] = [],
        errorMessage: String? = nil
    ) {
        self.loading = loading
        self.severity = severity
        self.chipLabel = chipLabel
        self.summary = summary
        self.detail = detail
        self.records = records
        self.errorMessage = errorMessage
    }
}

public enum OperationalFacilityMap {
    public static let operatingRadiusNM = 0.868976
    public static let operatingRadiusStatuteMiles = 1.0
    public static let operatingAreaLabel = "1 mi operating area"
    public static let endpoint = "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/FAA_UAS_FacilityMap_Data/FeatureServer/0/query"
    public static let outFields = "OBJECTID,CEILING,UNIT,APT1_FAAID,APT1_ICAO,APT1_NAME,APT1_LAANC,AIRSPACE_1,AIRSPACE_2,AIRSPACE_3,AIRSPACE_4,AIRSPACE_5"

    public static func queryURL(latitude: Double, longitude: Double) -> URL? {
        guard latitude.isFinite, longitude.isFinite,
              (-90 ... 90).contains(latitude), (-180 ... 180).contains(longitude),
              var components = URLComponents(string: endpoint)
        else { return nil }
        components.queryItems = [
            .init(name: "f", value: "json"),
            .init(name: "where", value: "1=1"),
            .init(name: "outFields", value: outFields),
            .init(name: "returnGeometry", value: "true"),
            .init(name: "geometry", value: String(format: "%.6f,%.6f", longitude, latitude)),
            .init(name: "geometryType", value: "esriGeometryPoint"),
            .init(name: "inSR", value: "4326"),
            .init(name: "outSR", value: "4326"),
            .init(name: "distance", value: String(format: "%.6f", operatingRadiusStatuteMiles)),
            .init(name: "units", value: "esriSRUnit_StatuteMile"),
            .init(name: "spatialRel", value: "esriSpatialRelIntersects"),
        ]
        return components.url
    }

    public static func parse(_ data: Data) throws -> [OperationalFacilityMapRecord] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CocoaError(.fileReadCorruptFile)
        }
        if let error = root["error"] as? [String: Any] {
            let message = (error["message"] as? String) ?? "FAA Facility Map returned an error."
            throw OperationalFacilityMapError.service(message)
        }
        return (root["features"] as? [[String: Any]] ?? []).compactMap { feature in
            guard let attributes = feature["attributes"] as? [String: Any] else { return nil }
            let classes = (1 ... 5).compactMap { index -> String? in
                let value = string(attributes["AIRSPACE_\(index)"]).trimmingCharacters(in: .whitespacesAndNewlines)
                return value.isEmpty ? nil : value
            }
            let rings: [[OperationalAirspaceCoordinate]] = ((feature["geometry"] as? [String: Any])?["rings"] as? [[[Any]]])?.compactMap { ring in
                let coordinates = ring.compactMap { pair -> OperationalAirspaceCoordinate? in
                    guard pair.count >= 2,
                          let longitude = number(pair[0])?.doubleValue,
                          let latitude = number(pair[1])?.doubleValue,
                          latitude.isFinite, longitude.isFinite,
                          (-90 ... 90).contains(latitude), (-180 ... 180).contains(longitude)
                    else { return nil }
                    return .init(latitude: latitude, longitude: longitude)
                }
                return coordinates.count >= 3 ? coordinates : nil
            } ?? []
            return OperationalFacilityMapRecord(
                objectID: number(attributes["OBJECTID"])?.int64Value ?? -1,
                ceilingFeet: number(attributes["CEILING"])?.intValue,
                unit: string(attributes["UNIT"]),
                primaryAirportFAAID: string(attributes["APT1_FAAID"]),
                primaryAirportICAO: string(attributes["APT1_ICAO"]),
                primaryAirportName: string(attributes["APT1_NAME"]),
                laancAvailable: number(attributes["APT1_LAANC"])?.intValue == 1,
                airspaceClasses: classes,
                rings: rings
            )
        }
    }

    public static func state(
        records: [OperationalFacilityMapRecord],
        loading: Bool,
        errorMessage: String?,
        pilotCoordinate: OperationalAirspaceCoordinate? = nil
    ) -> OperationalAirspaceState {
        if let errorMessage, !records.isEmpty {
            var cached = state(records: records, loading: false, errorMessage: nil, pilotCoordinate: pilotCoordinate)
            cached.chipLabel = "Airspace stale • review"
            cached.errorMessage = errorMessage
            return cached
        }
        if loading {
            return .init(loading: true, chipLabel: "Airspace updating…", records: records, errorMessage: errorMessage)
        }
        if let errorMessage, records.isEmpty {
            return .init(chipLabel: "Airspace unavailable", detail: errorMessage, errorMessage: errorMessage)
        }
        let controlled = records.filter { !$0.airspaceClasses.isEmpty }
        if !controlled.isEmpty {
            let containing = pilotCoordinate.map { coordinate in
                controlled.filter { contains($0, coordinate: coordinate) }
            } ?? []
            if containing.isEmpty {
                return nearbyState(
                    records: records,
                    controlled: controlled,
                    pilotCoordinate: pilotCoordinate,
                    errorMessage: errorMessage
                )
            }
            // FAA guidance says an operation spanning multiple UASFM grids must use the
            // lowest published altitude. ArcGIS result order is not deterministic.
            let representative = representative(containing)
            let airport = shortAirportName(representative.primaryAirportName)
            let classes = Array(Set(containing.flatMap(\.airspaceClasses)))
                .sorted()
                .map { "Class \($0)" }
                .joined(separator: "/")
            let gridLimit = containing.compactMap(\.ceilingFeet).min()
            let gridLimitText = gridLimit.map { "; FAA grid limit \($0) ft AGL" } ?? ""
            let coordinationText = gridLimit.map {
                " The displayed \($0) ft AGL value is the lowest FAA UAS Facility Map limit across the area, not the top of the controlled-airspace class. Requests above it require further FAA coordination."
            } ?? ""
            return .init(
                severity: .danger,
                chipLabel: "Airspace: Authorization required - \(airport) \(classes)\(gridLimitText)",
                summary: "\(airport) \(classes)",
                detail: "The current location is inside an FAA UAS Facility Map grid identified as \(classes). FAA authorization is required before flight.\(coordinationText)",
                records: records,
                errorMessage: errorMessage
            )
        }
        return .init(
            severity: .normal,
            chipLabel: records.isEmpty ? "No facility grids returned" : "Facility grids: review",
            summary: "No classified FAA facility-map grids returned for the \(operatingAreaLabel). This is not a complete airspace assessment.",
            records: records,
            errorMessage: errorMessage
        )
    }

    private static func nearbyState(
        records: [OperationalFacilityMapRecord],
        controlled: [OperationalFacilityMapRecord],
        pilotCoordinate: OperationalAirspaceCoordinate?,
        errorMessage: String?
    ) -> OperationalAirspaceState {
        let nearest: (OperationalFacilityMapRecord, Double)? = pilotCoordinate.flatMap { coordinate in
            controlled.compactMap { record in
                distanceStatuteMiles(record, coordinate: coordinate).map { (record, $0) }
            }.min { $0.1 < $1.1 }
        }
        let record = nearest?.0 ?? representative(controlled)
        let airport = shortAirportName(record.primaryAirportName)
        let classes = Array(Set(record.airspaceClasses))
            .sorted()
            .map { "Class \($0)" }
            .joined(separator: "/")
        let distanceText = nearest.map { String(format: " %.1f mi", $0.1) } ?? ""
        let proximity = nearest.map {
            " The nearest \(airport) \(classes) facility-map grid is approximately " +
                "\(String(format: "%.1f", $0.1)) statute miles away and intersects the \(operatingAreaLabel)."
        } ?? " A controlled-airspace facility-map grid intersects the \(operatingAreaLabel), but its boundary could not be compared with the current position."
        return .init(
            severity: .caution,
            chipLabel: "Airspace nearby - \(airport) \(classes)\(distanceText)",
            summary: "\(airport) \(classes) nearby",
            detail: "No FAA UAS Facility Map grid covers the current location.\(proximity) " +
                "FAA authorization is required only if the planned operation enters controlled airspace. " +
                "Verify the full planned area in an FAA-approved planning source.",
            records: records,
            errorMessage: errorMessage
        )
    }

    private static func representative(
        _ records: [OperationalFacilityMapRecord]
    ) -> OperationalFacilityMapRecord {
        records.min {
            let leftLimit = $0.ceilingFeet ?? .max
            let rightLimit = $1.ceilingFeet ?? .max
            if leftLimit != rightLimit { return leftLimit < rightLimit }
            if $0.primaryAirportName != $1.primaryAirportName {
                return $0.primaryAirportName < $1.primaryAirportName
            }
            return $0.objectID < $1.objectID
        }!
    }

    private static func contains(
        _ record: OperationalFacilityMapRecord,
        coordinate: OperationalAirspaceCoordinate
    ) -> Bool {
        record.rings.filter { pointInRing($0, point: coordinate) }.count % 2 == 1
    }

    private static func pointInRing(
        _ ring: [OperationalAirspaceCoordinate],
        point: OperationalAirspaceCoordinate
    ) -> Bool {
        guard ring.count >= 3 else { return false }
        var inside = false
        var previous = ring[ring.count - 1]
        for current in ring {
            let crossesLatitude = (current.latitude > point.latitude) !=
                (previous.latitude > point.latitude)
            if crossesLatitude {
                let crossingLongitude = (previous.longitude - current.longitude) *
                    (point.latitude - current.latitude) /
                    (previous.latitude - current.latitude) + current.longitude
                if point.longitude < crossingLongitude { inside.toggle() }
            }
            previous = current
        }
        return inside
    }

    private static func distanceStatuteMiles(
        _ record: OperationalFacilityMapRecord,
        coordinate: OperationalAirspaceCoordinate
    ) -> Double? {
        let longitudeMiles = 69.172 * cos(coordinate.latitude * .pi / 180)
        var minimum: Double?
        for ring in record.rings where ring.count >= 2 {
            for index in ring.indices {
                let start = ring[index]
                let end = ring[(index + 1) % ring.count]
                let ax = (start.longitude - coordinate.longitude) * longitudeMiles
                let ay = (start.latitude - coordinate.latitude) * 69.0
                let bx = (end.longitude - coordinate.longitude) * longitudeMiles
                let by = (end.latitude - coordinate.latitude) * 69.0
                let dx = bx - ax
                let dy = by - ay
                let denominator = dx * dx + dy * dy
                let t = denominator == 0 ? 0 :
                    max(0, min(1, -(ax * dx + ay * dy) / denominator))
                let distance = hypot(ax + t * dx, ay + t * dy)
                minimum = min(minimum ?? distance, distance)
            }
        }
        return minimum
    }

    private static func shortAirportName(_ value: String) -> String {
        let shortened = value.components(separatedBy: " (").first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return shortened.isEmpty ? "Controlled airspace" : shortened
    }

    private static func string(_ value: Any?) -> String {
        if value is NSNull || value == nil { return "" }
        return value as? String ?? String(describing: value!)
    }

    private static func number(_ value: Any?) -> NSNumber? {
        if let value = value as? NSNumber { return value }
        if let value = value as? String, let double = Double(value) { return NSNumber(value: double) }
        return nil
    }
}

public enum OperationalFacilityMapError: LocalizedError {
    case service(String)
    public var errorDescription: String? {
        switch self { case let .service(message): message }
    }
}
