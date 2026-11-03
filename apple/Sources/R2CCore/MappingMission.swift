import Foundation

public struct MappingLaunchLocation: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let horizontalAccuracyMeters: Double
    public let capturedAt: String

    public init(latitude: Double, longitude: Double, horizontalAccuracyMeters: Double, capturedAt: String) {
        self.latitude = latitude
        self.longitude = longitude
        self.horizontalAccuracyMeters = horizontalAccuracyMeters
        self.capturedAt = capturedAt
    }
}

/// Shared mission-plan knobs. Saved missions that predate them read as 10 m / 0.2 m / fill on.
public enum MappingMissionDefaults {
    public static let standardCellMeters = 0.2
    /// Standard, Fine, Coarse.
    public static let surfaceCellOptions: [Double] = [0.2, 0.1, 0.4]
    public static let minimumMarginMeters = 10.0
    public static let maximumMarginMeters = 100.0
    /// Half a photo width at plan height, at least 10 m, never above the 100 m planner limit.
    public static func defaultMarginMeters(widthPixels: Double, gsdCmPerPixel: Double) -> Double {
        let width = widthPixels * (gsdCmPerPixel / 100)
        return width.isFinite ? min(maximumMarginMeters, max(minimumMarginMeters, 0.5 * width)) : minimumMarginMeters
    }
    public static func isValidCell(_ value: Double) -> Bool { surfaceCellOptions.contains { abs($0 - value) < 1e-9 } }
    public static func cell(_ parameters: [String: Any]?) -> Double {
        guard let value = (parameters?["surfaceCellMeters"] as? NSNumber)?.doubleValue else { return standardCellMeters }
        return surfaceCellOptions.first { abs($0 - value) < 1e-9 } ?? standardCellMeters
    }
    public static func fillUnknown2D(_ parameters: [String: Any]?) -> Bool {
        guard let value = parameters?["fillUnknown2DAreas"] as? NSNumber else { return true }
        return value.boolValue
    }
    public static func margin(_ parameters: [String: Any]?) -> Double {
        let value = (parameters?["marginMeters"] as? NSNumber)?.doubleValue ?? minimumMarginMeters
        return min(maximumMarginMeters, max(0, value.isFinite ? value : minimumMarginMeters))
    }
    public static func cellLabel(_ cell: Double) -> String { cell < 0.15 ? "Fine" : cell > 0.3 ? "Coarse" : "Standard" }
}

/// A versioned transfer record. The station validates polygon topology before generating a flight template.
public struct MappingMission: Identifiable, Sendable {
    public let id: UUID
    public let title: String
    private let feature: Data
    public let incidentID: String
    private let createdAt: String
    public let launchLocation: MappingLaunchLocation?

    public init(feature: Data, incidentID: String, launchLocation: MappingLaunchLocation? = nil) throws {
        guard !incidentID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw Failure("Connect an incident map first.") }
        guard let object = try JSONSerialization.jsonObject(with: feature) as? [String: Any],
              let assignmentID = object["id"] as? String, !assignmentID.isEmpty,
              let properties = object["properties"] as? [String: Any], properties["class"] as? String == "Assignment",
              let geometry = object["geometry"] as? [String: Any], geometry["type"] as? String == "Polygon",
              let rings = geometry["coordinates"] as? [[[Double]]], rings.count == 1,
              (3...2000).contains(rings[0].count)
        else { throw Failure("Select a single assignment polygon without holes. Split multipart areas in CalTopo first.") }
        guard rings[0].allSatisfy({ $0.count >= 2 && $0[0].isFinite && $0[1].isFinite && (-180...180).contains($0[0]) && (-85...85).contains($0[1]) })
        else { throw Failure("Assignment contains unsupported coordinates.") }
        self.id = UUID(); self.feature = feature; self.incidentID = incidentID
        self.title = (properties["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Assignment"
        self.createdAt = ISO8601DateFormatter().string(from: Date())
        if let launchLocation,
           launchLocation.latitude.isFinite, (-90...90).contains(launchLocation.latitude),
           launchLocation.longitude.isFinite, (-180...180).contains(launchLocation.longitude),
           launchLocation.horizontalAccuracyMeters.isFinite, launchLocation.horizontalAccuracyMeters >= 0 {
            self.launchLocation = launchLocation
        } else { self.launchLocation = nil }
    }

    public func json(thermal: Bool = false, desiredGsdCmPerPixel: Double? = nil) throws -> Data {
        let profile = MappingResolution.profiles.first { $0.id == (thermal ? "m4td.thermal" : "m4td.visible.wide") }!
        return try json(cameraProfile: profile, desiredGsdCmPerPixel: desiredGsdCmPerPixel)
    }

    public func json(cameraProfile: MappingResolution.Profile, desiredGsdCmPerPixel: Double? = nil, captureProfile: MappingProcessingPolicy.CaptureProfile? = nil, canopyHeightMeters: Double = 20, maximumObstacleHeightMeters: Double? = nil, clearanceMeters: Double = 15, maximumCaptureSpeedMetersPerSecond: Double? = nil, transitSpeedMetersPerSecond: Double? = nil, marginMeters: Double? = nil, surfaceCellMeters: Double = MappingMissionDefaults.standardCellMeters, fillUnknown2DAreas: Bool = true) throws -> Data {
        if let speed=transitSpeedMetersPerSecond {
            guard speed.isFinite,(1...15).contains(speed) else { throw Failure("Enter a transit speed from \(DisplayUnits.current.length(1,decimals:2))/s to \(DisplayUnits.current.length(15,decimals:2))/s.") }
        }
        if let speed=maximumCaptureSpeedMetersPerSecond {
            guard speed.isFinite,(1...15).contains(speed) else { throw Failure("Enter a maximum capture speed from \(DisplayUnits.current.length(1,decimals:2))/s to \(DisplayUnits.current.length(15,decimals:2))/s.") }
        }
        let object = try JSONSerialization.jsonObject(with: feature) as! [String: Any]
        let camera = cameraProfile.sensor
        let targetGsd = desiredGsdCmPerPixel ?? MappingResolution.gsdCmPerPixel(heightMeters:60,camera:camera)!
        guard (0.2...100).contains(targetGsd),
              let targetHeight = MappingResolution.heightMeters(gsdCmPerPixel:targetGsd,camera:camera),
              (5...500).contains(targetHeight) else { throw Failure("Ground resolution must produce a mission height from \(DisplayUnits.current.length(5)) to \(DisplayUnits.current.length(500)).") }
        let thermal = cameraProfile.mode == .thermal
        var value: [String: Any] = ["type":"r2c_mapping_mission", "version":1, "missionId":id.uuidString.lowercased(),
            "incidentId":incidentID, "assignmentId":object["id"]!, "title":title, "createdAt":createdAt,
            "photoIntake":["missionId":id.uuidString.lowercased(), "collection":"originals",
                "preserveOriginalBytes":true, "captureMode":thermal ? "thermal" : "visible",
                "transport":"r2c_active_lan_session"],
            "feature":object, "profile":thermal ? "thermal" : "daylight-rgb",
            "aircraftModel":cameraProfile.aircraft, "cameraProfileId":cameraProfile.id, "cameraName":cameraProfile.camera,
            "cameraSpecifications":["widthPixels":camera.widthPixels, "heightPixels":camera.heightPixels,
                "diagonalFOVDegrees":camera.diagonalFOVDegrees],
            "parameters":["heightMeters":targetHeight, "desiredGroundResolutionCmPerPixel":targetGsd, "speedMetersPerSecond":thermal ? 2 : 4,
                "forwardOverlapPercent":thermal ? 85 : 80, "sideOverlapPercent":thermal ? 80 : 70,
                "directionDegrees":0, "orthophotoResolutionCm":thermal ? 15 : min(5,targetGsd),
                "fastOrthophoto":!thermal]]
        if !thermal { var spec=value["cameraSpecifications"] as! [String:Any]; spec["captureResolutionMode"]=cameraProfile.captureResolutionMode; value["cameraSpecifications"]=spec }
        let capture = captureProfile ?? (cameraProfile.isVisibleWide ? MappingProcessingPolicy.brushTrees : MappingProcessingPolicy.openGround)
        if cameraProfile.isVisibleWide {
            value["captureProfile"] = try JSONSerialization.jsonObject(with:JSONEncoder().encode(capture))
            var specifications = value["cameraSpecifications"] as! [String:Any]
            specifications["captureResolutionMode"] = cameraProfile.captureResolutionMode
            value["cameraSpecifications"] = specifications
        } else if capture != MappingProcessingPolicy.openGround { throw Failure("Crossed surveys require the visible-wide camera.") }
        var parameters = value["parameters"] as! [String:Any]
        let margin = marginMeters ?? MappingMissionDefaults.defaultMarginMeters(widthPixels: camera.widthPixels, gsdCmPerPixel: targetGsd)
        guard margin.isFinite, (0...MappingMissionDefaults.maximumMarginMeters).contains(margin) else { throw Failure("Edge overrun must be from \(DisplayUnits.current.length(0)) to \(DisplayUnits.current.length(MappingMissionDefaults.maximumMarginMeters)).") }
        guard MappingMissionDefaults.isValidCell(surfaceCellMeters) else { throw Failure("Choose Fine, Standard or Coarse 3D surface detail.") }
        parameters["marginMeters"] = margin; parameters["surfaceCellMeters"] = surfaceCellMeters; parameters["fillUnknown2DAreas"] = fillUnknown2DAreas
        if let speed=transitSpeedMetersPerSecond { parameters["transitSpeedMetersPerSecond"]=speed }
        if let speed=maximumCaptureSpeedMetersPerSecond { parameters["maximumCaptureSpeedMetersPerSecond"]=speed }
        guard clearanceMeters.isFinite, clearanceMeters > 0, clearanceMeters <= 500 else { throw Failure("Enter a positive clearance margin no greater than \(DisplayUnits.current.length(500)).") }
        parameters["clearanceMeters"] = clearanceMeters
        if let obstacle = maximumObstacleHeightMeters {
            guard obstacle.isFinite, obstacle >= 0, obstacle <= targetHeight-clearanceMeters else { throw Failure("Requested detail gives \(DisplayUnits.current.length(targetHeight)) flight height. Required height is \(DisplayUnits.current.length(obstacle+clearanceMeters)): obstacle top \(DisplayUnits.current.length(obstacle)) + clearance \(DisplayUnits.current.length(clearanceMeters)). Select a longer lens to preserve the requested detail, or choose a larger distance per pixel (coarser resolution).") }
            parameters["maximumObstacleHeightMeters"] = obstacle
        }
        parameters["captureTrigger"] = "distance-v1"
        parameters["cameraHeadingPolicy"] = "aircraft-first-v1"
        if capture == MappingProcessingPolicy.brushTrees {
            guard canopyHeightMeters.isFinite, canopyHeightMeters >= 0, canopyHeightMeters <= targetHeight-clearanceMeters else { throw Failure("Allow at least \(DisplayUnits.current.length(clearanceMeters)) above the planned canopy/terrain height.") }
            parameters["planningSurfaceHeightMeters"] = canopyHeightMeters
        }
        value["parameters"] = parameters
        if let launchLocation {
            value["launchLocation"] = ["latitude":launchLocation.latitude,"longitude":launchLocation.longitude,
                "horizontalAccuracyMeters":launchLocation.horizontalAccuracyMeters,"capturedAt":launchLocation.capturedAt]
        }
        return try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys])
    }

    public func boundaryKML() throws -> Data {
        let object = try JSONSerialization.jsonObject(with: feature) as! [String: Any]
        let geometry = object["geometry"] as! [String: Any]
        var ring = (geometry["coordinates"] as! [[[Double]]])[0].map { [$0[0],$0[1]] }
        if ring.first != ring.last { ring.append(ring[0]) }
        let coordinates = ring.map { "\($0[0]),\($0[1]),0" }.joined(separator: " ")
        let escaped = title.replacingOccurrences(of:"&",with:"&amp;").replacingOccurrences(of:"<",with:"&lt;").replacingOccurrences(of:">",with:"&gt;")
        return Data("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><Placemark><name>\(escaped)</name><Polygon><outerBoundaryIs><LinearRing><coordinates>\(coordinates)</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Document></kml>".utf8)
    }

    public static func stationURL(_ text: String) throws -> URL {
        guard let components = URLComponents(string:text.trimmingCharacters(in:.whitespacesAndNewlines)),
              ["http","https"].contains(components.scheme ?? ""), let host = components.host?.lowercased(),
              components.user == nil, components.password == nil, components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/"
        else { throw Failure("Enter the station's base HTTP(S) URL.") }
        let parts = host.split(separator:".").compactMap { Int($0) }
        let privateIP = parts.count == 4 && parts.allSatisfy { (0...255).contains($0) } &&
            (parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && (16...31).contains(parts[1])))
        guard host == "localhost" || host.hasSuffix(".local") || privateIP else { throw Failure("Use the Mac's LAN address or .local hostname.") }
        guard let url = components.url else { throw Failure("Invalid station URL.") }
        return url
    }

    public struct Failure: LocalizedError, Sendable {
        public let message: String
        public init(_ message: String) { self.message = message }
        public var errorDescription: String? { message }
    }
}
