import Foundation

public struct MappingLayer: Codable, Identifiable, Sendable, Equatable {
    public let layerId: String
    public let incidentId: String
    public let title: String
    public let tileUrlTemplate: String
    public let minZoom: Int
    public let maxZoom: Int
    public let bounds: [Double]?
    public let profile: String?
    public let cameraProfileId: String?
    public let captureTimesLocal: [String]?
    public let requestedResolutionCm: Double?
    public let photoCount: Int?
    public let processedAt: String?
    public let quality: String?
    public let missionId: String?
    public let assignmentBoundary: [[Double]]?
    public let engine: String?
    public let regionCount: Int?
    public let surfaceModel: SurfaceModel?
    public struct SurfaceModel: Codable, Sendable, Equatable {
        public let type: String
        public let path: String
        public let regionCount: Int?
        public let meshSpacingMeters: Double?
    }
    public var id: String { layerId }

    public var cameraModeLabel: String? {
        switch profile {
        case "thermal": return "IR"
        case "daylight-rgb": return "Visible"
        default: return nil
        }
    }

    public var captureTimeSummary: String? {
        guard let times = captureTimesLocal?.filter({ !$0.isEmpty }).sorted(),
              let first = times.first, let last = times.last else { return nil }
        let start = Self.cameraLocalLabel(first)
        let end = Self.cameraLocalLabel(last)
        return first == last ? start : "\(start) to \(end)"
    }

    public var generatedDate: Date? { Self.timestampDate(processedAt ?? "") }
    public var generatedLabel: String { Self.generatedLabel(processedAt ?? "") }
    public var resultTitle: String {
        guard let capture = captureTimesLocal?.first, !capture.isEmpty, title.hasSuffix(" · " + capture) else { return title }
        return String(title.dropLast(capture.count + 3))
    }
    public static func timestampDate(_ value: String) -> Date? {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = parser.date(from: value) { return date }
        parser.formatOptions = [.withInternetDateTime]
        return parser.date(from: value)
    }
    public static func generatedLabel(_ value: String, timeZone: TimeZone = .current) -> String {
        guard let date = timestampDate(value) else { return "Time unavailable" }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone; formatter.dateFormat = "MMM d, yyyy HH:mm:ss z"
        return formatter.string(from: date)
    }
    public static func capturedLabel(_ value: String) -> String {
        let parser = DateFormatter(); parser.locale = Locale(identifier: "en_US_POSIX")
        parser.timeZone = TimeZone(secondsFromGMT: 0); parser.dateFormat = "yyyy:MM:dd HH:mm:ss"
        guard let date = parser.date(from: value) else { return value.isEmpty ? "Time unavailable" : value }
        parser.dateFormat = "MMM d, yyyy HH:mm:ss"
        return parser.string(from: date)
    }
    public var displayTitle: String {
        [title, cameraModeLabel, captureTimeSummary?
            .replacingOccurrences(of: " to ", with: "–")
            .replacingOccurrences(of: " camera local", with: " local")]
            .compactMap { $0 }.joined(separator: " · ")
    }

    private enum CodingKeys: String, CodingKey {
        case missionId, assignmentBoundary, engine, regionCount, surfaceModel
        case type, version, tileScheme, layerId, incidentId, title, tileUrlTemplate, minZoom, maxZoom, bounds
        case profile, cameraProfileId, captureTimesLocal, captureTimeLocal, requestedResolutionCm, photoCount, processedAt, quality
    }

    // Keep older locally persisted layer records readable, but always write the
    // complete interchange manifest so mutual-aid export/import round-trips.
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        layerId = try values.decode(String.self, forKey: .layerId)
        incidentId = try values.decode(String.self, forKey: .incidentId)
        title = try values.decode(String.self, forKey: .title)
        tileUrlTemplate = try values.decode(String.self, forKey: .tileUrlTemplate)
        minZoom = try values.decode(Int.self, forKey: .minZoom)
        maxZoom = try values.decode(Int.self, forKey: .maxZoom)
        bounds = try values.decodeIfPresent([Double].self, forKey: .bounds)
        profile = try values.decodeIfPresent(String.self, forKey: .profile)
        cameraProfileId = try values.decodeIfPresent(String.self, forKey: .cameraProfileId)
        captureTimesLocal = try values.decodeIfPresent([String].self, forKey: .captureTimesLocal)
            ?? values.decodeIfPresent(String.self, forKey: .captureTimeLocal).map { [$0] }
        requestedResolutionCm = try values.decodeIfPresent(Double.self, forKey: .requestedResolutionCm)
        photoCount = try values.decodeIfPresent(Int.self, forKey: .photoCount)
        processedAt = try values.decodeIfPresent(String.self, forKey: .processedAt)
        quality = try values.decodeIfPresent(String.self, forKey: .quality)
        missionId = try values.decodeIfPresent(String.self, forKey: .missionId)
        assignmentBoundary = try values.decodeIfPresent([[Double]].self, forKey: .assignmentBoundary)
        engine = try values.decodeIfPresent(String.self, forKey: .engine)
        regionCount = try values.decodeIfPresent(Int.self, forKey: .regionCount)
        surfaceModel = try values.decodeIfPresent(SurfaceModel.self, forKey: .surfaceModel)
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode("r2c_mapping_layer", forKey: .type)
        try values.encode(1, forKey: .version)
        try values.encode("xyz", forKey: .tileScheme)
        try values.encode(layerId, forKey: .layerId)
        try values.encode(incidentId, forKey: .incidentId)
        try values.encode(title, forKey: .title)
        try values.encode(tileUrlTemplate, forKey: .tileUrlTemplate)
        try values.encode(minZoom, forKey: .minZoom)
        try values.encode(maxZoom, forKey: .maxZoom)
        try values.encodeIfPresent(bounds, forKey: .bounds)
        try values.encodeIfPresent(profile, forKey: .profile)
        try values.encodeIfPresent(cameraProfileId, forKey: .cameraProfileId)
        try values.encodeIfPresent(captureTimesLocal, forKey: .captureTimesLocal)
        try values.encodeIfPresent(requestedResolutionCm, forKey: .requestedResolutionCm)
        try values.encodeIfPresent(photoCount, forKey: .photoCount)
        try values.encodeIfPresent(processedAt, forKey: .processedAt)
        try values.encodeIfPresent(quality, forKey: .quality)
        try values.encodeIfPresent(missionId, forKey: .missionId)
        try values.encodeIfPresent(assignmentBoundary, forKey: .assignmentBoundary)
        try values.encodeIfPresent(engine, forKey: .engine)
        try values.encodeIfPresent(regionCount, forKey: .regionCount)
        try values.encodeIfPresent(surfaceModel, forKey: .surfaceModel)
    }

    private static func cameraLocalLabel(_ value: String) -> String {
        // EXIF timestamps are camera-local and carry no time-zone offset. Keep
        // their wall-clock components intact when displaying them on other devices.
        let parts = value.split(separator: " ", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return value }
        let date = parts[0].replacingOccurrences(of: ":", with: "-")
        let time = String(parts[1].prefix(5))
        return "\(date) \(time)"
    }

    public static func decode(_ data: Data, incidentID: String) throws -> MappingLayer {
        guard data.count <= 1024*1024,
              let value = try JSONSerialization.jsonObject(with:data) as? [String:Any],
              value["type"] as? String == "r2c_mapping_layer",value["version"] as? Int == 1,
              value["tileScheme"] as? String == "xyz"
        else { throw MappingMission.Failure("Unsupported 2D layer.") }
        let layer = try JSONDecoder().decode(MappingLayer.self,from:data)
        guard UUID(uuidString:layer.id) != nil, layer.incidentId == incidentID,
              (0...23).contains(layer.minZoom), (layer.minZoom...23).contains(layer.maxZoom),
              layer.bounds == nil || (layer.bounds?.count == 4 && layer.bounds!.allSatisfy(\.isFinite)
                && (-180...180).contains(layer.bounds![0]) && (-90...90).contains(layer.bounds![1])
                && (-180...180).contains(layer.bounds![2]) && (-90...90).contains(layer.bounds![3])
                && layer.bounds![0] < layer.bounds![2] && layer.bounds![1] < layer.bounds![3]),
              ["{Z}","{X}","{Y}"].allSatisfy(layer.tileUrlTemplate.contains),
              let url = URLComponents(string:layer.tileUrlTemplate.replacingOccurrences(of:"{Z}",with:"0").replacingOccurrences(of:"{X}",with:"0").replacingOccurrences(of:"{Y}",with:"0")),
              ["http","https"].contains(url.scheme ?? ""),url.host != nil,url.user == nil,url.password == nil
        else { throw MappingMission.Failure("Invalid layer or a layer from another incident map.") }
        return layer
    }

    public func tileURL(zoom: Int, x: Int, y: Int) -> URL? {
        URL(string:tileUrlTemplate.replacingOccurrences(of:"{Z}",with:String(zoom))
            .replacingOccurrences(of:"{X}",with:String(x)).replacingOccurrences(of:"{Y}",with:String(y)))
    }
}
