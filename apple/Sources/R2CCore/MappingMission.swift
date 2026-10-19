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

    public func json(cameraProfile: MappingResolution.Profile, desiredGsdCmPerPixel: Double? = nil, captureProfile: MappingProcessingPolicy.CaptureProfile? = nil, canopyHeightMeters: Double = 20, maximumObstacleHeightMeters: Double? = nil, clearanceMeters: Double = 15, maximumCaptureSpeedMetersPerSecond: Double? = nil, transitSpeedMetersPerSecond: Double? = nil) throws -> Data {
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
                "directionDegrees":0, "marginMeters":10, "orthophotoResolutionCm":thermal ? 15 : min(5,targetGsd),
                "fastOrthophoto":!thermal]]
        if !thermal { var spec=value["cameraSpecifications"] as! [String:Any]; spec["captureResolutionMode"]=cameraProfile.focalLength35mm == nil ? "observed-timed-12mp":"planned-12mp"; value["cameraSpecifications"]=spec }
        let capture = captureProfile ?? (cameraProfile.id == "m4td.visible.wide" ? MappingProcessingPolicy.brushTrees : MappingProcessingPolicy.openGround)
        if cameraProfile.id == "m4td.visible.wide" {
            value["captureProfile"] = try JSONSerialization.jsonObject(with:JSONEncoder().encode(capture))
            var specifications = value["cameraSpecifications"] as! [String:Any]
            specifications["captureResolutionMode"] = "observed-timed-12mp"
            value["cameraSpecifications"] = specifications
        } else if capture != MappingProcessingPolicy.openGround { throw Failure("Crossed surveys require the visible-wide camera.") }
        var parameters = value["parameters"] as! [String:Any]
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
