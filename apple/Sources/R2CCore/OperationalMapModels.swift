import Foundation

public enum OperationalMapTrackFreshness {
    /// Peer traffic replaces a local track only when the peer's source sample
    /// is strictly newer. Equal timestamps retain the local source.
    public static func prefersPeer(localSampleAt: Date?, peerSampleAt: Date) -> Bool {
        guard let localSampleAt else { return true }
        return peerSampleAt > localSampleAt
    }
}

public enum OperationalMapFocusPolicy {
    public static func shouldReleaseFocus(
        hasFocusedAircraft: Bool,
        isOperatorGesture: Bool
    ) -> Bool {
        hasFocusedAircraft && isOperatorGesture
    }
}

public enum OperationalMapBaseLayer: String, CaseIterable, Codable, Sendable, Equatable {
    case openStreetMap
    case imagery

    public var label: String {
        switch self {
        case .openStreetMap: "OpenStreetMap"
        case .imagery: "Imagery"
        }
    }

    public var cacheKey: String { rawValue }
    public var fileExtension: String { self == .imagery ? "jpg" : "png" }

    public func tileURL(zoom: Int, x: Int, y: Int) -> URL? {
        switch self {
        case .openStreetMap:
            URL(string: "https://tile.openstreetmap.org/\(zoom)/\(x)/\(y).png")
        case .imagery:
            URL(string: "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/\(zoom)/\(y)/\(x).jpg")
        }
    }

}

public struct OperationalOverzoomTile: Sendable, Equatable {
    public let sourceZoom: Int
    public let sourceX: Int
    public let sourceY: Int
    public let zoomDelta: Int
    public let childX: Int
    public let childY: Int

    public static func resolve(
        requestedZoom: Int,
        requestedX: Int,
        requestedY: Int,
        sourceMaximumZoom: Int
    ) -> Self? {
        guard requestedZoom > sourceMaximumZoom,
              sourceMaximumZoom >= 0,
              requestedX >= 0,
              requestedY >= 0
        else { return nil }
        let zoomDelta = requestedZoom - sourceMaximumZoom
        guard zoomDelta < Int.bitWidth - 1 else { return nil }
        let scale = 1 << zoomDelta
        return Self(
            sourceZoom: sourceMaximumZoom,
            sourceX: requestedX / scale,
            sourceY: requestedY / scale,
            zoomDelta: zoomDelta,
            childX: requestedX % scale,
            childY: requestedY % scale
        )
    }
}

public enum OperationalMapVideoLayout: String, CaseIterable, Codable, Sendable, Equatable {
    case map
    case video
    case split
    case mapPrimary
    case videoPrimary

    public var label: String {
        switch self {
        case .map: "Map"
        case .video: "Video"
        case .split: "Split"
        case .mapPrimary: "Map + video inset"
        case .videoPrimary: "Video + map inset"
        }
    }

    public func withPictureInPicture(_ enabled: Bool) -> Self {
        switch self {
        case .map, .mapPrimary:
            enabled ? .mapPrimary : .map
        case .video, .videoPrimary:
            enabled ? .videoPrimary : .video
        case .split:
            .split
        }
    }

    public func fullScreenPresentation(pictureInPictureEnabled: Bool) -> Self {
        guard pictureInPictureEnabled else { return .video }
        switch self {
        case .map, .mapPrimary:
            return .mapPrimary
        case .video, .videoPrimary, .split:
            return .videoPrimary
        }
    }
}

public enum OperationalSplitSizing {
    public static let minimumFraction = 0.0
    public static let maximumFraction = 1.0
    public static let edgeSnapHandleWidths = 2.0

    public static func adjustedFraction(
        current: Double,
        dragDelta: Double,
        available: Double
    ) -> Double {
        let clampedCurrent = min(maximumFraction, max(minimumFraction, current))
        guard available > 0 else { return clampedCurrent }
        return min(
            maximumFraction,
            max(minimumFraction, clampedCurrent + dragDelta / available)
        )
    }

    public static func snappedFraction(
        _ fraction: Double,
        available: Double,
        handleWidth: Double
    ) -> Double {
        let clamped = min(maximumFraction, max(minimumFraction, fraction))
        guard available > 0, handleWidth > 0 else { return clamped }
        let position = clamped * available
        let snapDistance = edgeSnapHandleWidths * handleWidth
        if position <= snapDistance, position <= available / 2 {
            return minimumFraction
        }
        if available - position <= snapDistance, position >= available / 2 {
            return maximumFraction
        }
        return clamped
    }
}

public struct OperationalPipInsetSize: Sendable, Equatable {
    public let width: Double
    public let height: Double

    public init(width: Double, height: Double) {
        self.width = width
        self.height = height
    }
}

public enum OperationalPipSizing {
    public static let aspectRatio = 16.0 / 9.0
    public static let framePadding = 24.0
    public static let minimumInsetFraction = 0.22
    public static let defaultInsetFraction = 0.33
    public static let maximumInsetFraction = 0.55

    public static func clampInsetFraction(_ value: Double) -> Double {
        guard value.isFinite else { return defaultInsetFraction }
        return min(maximumInsetFraction, max(minimumInsetFraction, value))
    }

    public static func insetSize(
        containerWidth: Double,
        containerHeight: Double,
        insetFraction: Double,
        aspectRatio: Double = aspectRatio,
        padding: Double = framePadding
    ) -> OperationalPipInsetSize {
        let safeAspectRatio = aspectRatio.isFinite && aspectRatio > 0 ? aspectRatio : 1
        let maximumWidth = max(1, containerWidth - padding)
        let maximumHeight = max(1, containerHeight - padding)
        let desiredWidth = maximumWidth * clampInsetFraction(insetFraction)
        let width = min(desiredWidth, maximumHeight * safeAspectRatio)
        return OperationalPipInsetSize(width: width, height: width / safeAspectRatio)
    }
}

public struct MapCoordinate: Codable, Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }

    public var isValid: Bool {
        latitude.isFinite && longitude.isFinite
            && (-90 ... 90).contains(latitude)
            && (-180 ... 180).contains(longitude)
    }
}

public struct CaltopoArtifactFolder: Identifiable, Codable, Sendable, Equatable {
    public let id: String
    public let title: String
    public let initiallyVisible: Bool
    public let parentID: String?

    public init(id: String, title: String, initiallyVisible: Bool, parentID: String? = nil) {
        self.id = id
        self.title = title
        self.initiallyVisible = initiallyVisible
        self.parentID = parentID
    }
}

public struct CaltopoArtifactItem: Identifiable, Codable, Sendable, Equatable {
    public let id: String
    public let title: String
    public let folderID: String
    public let className: String
    public let r2cGUID: String?
    public let r2cLastSeenEpochMilliseconds: Int64?

    public init(
        id: String,
        title: String,
        folderID: String,
        className: String,
        r2cGUID: String? = nil,
        r2cLastSeenEpochMilliseconds: Int64? = nil
    ) {
        self.id = id
        self.title = title
        self.folderID = folderID
        self.className = className
        self.r2cGUID = r2cGUID
        self.r2cLastSeenEpochMilliseconds = r2cLastSeenEpochMilliseconds
    }
}

public struct CaltopoPointArtifact: Identifiable, Codable, Sendable, Equatable {
    public let id: String
    public let coordinate: MapCoordinate
    public let title: String
    public let symbol: String
    public let colorHex: String?
    public let folderID: String
    public let parentItemID: String?
    public let description: String?

    public init(
        id: String,
        coordinate: MapCoordinate,
        title: String,
        symbol: String,
        colorHex: String?,
        folderID: String,
        parentItemID: String?,
        description: String? = nil
    ) {
        self.id = id
        self.coordinate = coordinate
        self.title = title
        self.symbol = symbol
        self.colorHex = colorHex
        self.folderID = folderID
        self.parentItemID = parentItemID
        self.description = description
    }
}

public struct CaltopoLineArtifact: Identifiable, Codable, Sendable, Equatable {
    public let id: String
    public let itemID: String
    public let coordinates: [MapCoordinate]
    public let title: String
    public let colorHex: String
    public let width: Double
    public let folderID: String
    public let description: String?

    public init(
        id: String,
        itemID: String,
        coordinates: [MapCoordinate],
        title: String,
        colorHex: String,
        width: Double,
        folderID: String,
        description: String? = nil
    ) {
        self.id = id
        self.itemID = itemID
        self.coordinates = coordinates
        self.title = title
        self.colorHex = colorHex
        self.width = width
        self.folderID = folderID
        self.description = description
    }
}

public struct CaltopoPolygonArtifact: Identifiable, Codable, Sendable, Equatable {
    public let id: String
    public let itemID: String
    public let coordinates: [MapCoordinate]
    public let title: String
    public let strokeHex: String
    public let fillHex: String
    public let width: Double
    public let folderID: String
    public let description: String?

    public init(
        id: String,
        itemID: String,
        coordinates: [MapCoordinate],
        title: String,
        strokeHex: String,
        fillHex: String,
        width: Double,
        folderID: String,
        description: String? = nil
    ) {
        self.id = id
        self.itemID = itemID
        self.coordinates = coordinates
        self.title = title
        self.strokeHex = strokeHex
        self.fillHex = fillHex
        self.width = width
        self.folderID = folderID
        self.description = description
    }
}

public struct CaltopoArtifactSnapshot: Codable, Sendable, Equatable {
    public let folders: [CaltopoArtifactFolder]
    public let points: [CaltopoPointArtifact]
    public let lines: [CaltopoLineArtifact]
    public let polygons: [CaltopoPolygonArtifact]
    public let items: [CaltopoArtifactItem]
    public let totalFeatureCount: Int
    public let ignoredTrackCount: Int

    public init(
        folders: [CaltopoArtifactFolder] = [],
        points: [CaltopoPointArtifact] = [],
        lines: [CaltopoLineArtifact] = [],
        polygons: [CaltopoPolygonArtifact] = [],
        items: [CaltopoArtifactItem] = [],
        totalFeatureCount: Int = 0,
        ignoredTrackCount: Int = 0
    ) {
        self.folders = folders
        self.points = points
        self.lines = lines
        self.polygons = polygons
        self.items = items
        self.totalFeatureCount = totalFeatureCount
        self.ignoredTrackCount = ignoredTrackCount
    }

    public func occurrenceCount(ofItemID itemID: String) -> Int {
        let normalizedID = itemID.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalizedID.isEmpty else { return 0 }
        return items.filter { $0.id.lowercased() == normalizedID }.count
    }

    /// Only markers carrying the explicit R2C ownership and heartbeat properties are eligible.
    /// This deliberately excludes manually named `R2C:` markers and older title-only markers.
    public func staleR2CDeviceMarkerIDs(
        now: Date,
        staleAfter: TimeInterval
    ) -> [String] {
        let cutoffMilliseconds = Int64((now.timeIntervalSince1970 - max(0, staleAfter)) * 1_000)
        return items.compactMap { item in
            guard item.className == "Marker",
                  item.title.hasPrefix("R2C: "),
                  !(item.r2cGUID?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true),
                  let lastSeen = item.r2cLastSeenEpochMilliseconds,
                  lastSeen > 0,
                  lastSeen < cutoffMilliseconds
            else { return nil }
            let normalizedID = item.id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            return normalizedID.isEmpty ? nil : normalizedID
        }
    }

    public func hiding(folderIDs: Set<String>, itemIDs: Set<String> = []) -> CaltopoArtifactSnapshot {
        var hiddenFolders = folderIDs
        var changed = true
        while changed {
            changed = false
            for folder in folders where folder.parentID.map(hiddenFolders.contains) == true {
                if hiddenFolders.insert(folder.id).inserted { changed = true }
            }
        }
        return CaltopoArtifactSnapshot(
            folders: folders,
            points: points.filter {
                !hiddenFolders.contains($0.folderID)
                    && !itemIDs.contains($0.id)
                    && !($0.parentItemID.map(itemIDs.contains) ?? false)
            },
            lines: lines.filter { !hiddenFolders.contains($0.folderID) && !itemIDs.contains($0.itemID) },
            polygons: polygons.filter { !hiddenFolders.contains($0.folderID) && !itemIDs.contains($0.itemID) },
            items: items,
            totalFeatureCount: totalFeatureCount,
            ignoredTrackCount: ignoredTrackCount
        )
    }

    public func coordinates(forItemID itemID: String) -> [MapCoordinate] {
        points.filter { $0.id == itemID || $0.parentItemID == itemID }.map(\.coordinate)
            + lines.filter { $0.itemID == itemID }.flatMap(\.coordinates)
            + polygons.filter { $0.itemID == itemID }.flatMap(\.coordinates)
    }

    /// CalTopo can retain a geometry-less Assignment record after replacing it
    /// with a new same-named Assignment. Keep the renderable record in Map
    /// Folders without collapsing genuinely distinct, renderable assignments.
    public var visibilityItems: [CaltopoArtifactItem] {
        let renderableAssignmentKeys = Set(items.compactMap { item -> String? in
            guard item.className == "Assignment",
                  !coordinates(forItemID: item.id).isEmpty
            else { return nil }
            return Self.assignmentDisplayKey(item)
        })
        return items.filter { item in
            guard item.className == "Assignment",
                  coordinates(forItemID: item.id).isEmpty
            else { return true }
            return !renderableAssignmentKeys.contains(Self.assignmentDisplayKey(item))
        }
    }

    private static func assignmentDisplayKey(_ item: CaltopoArtifactItem) -> String {
        let title = item.title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return "\(item.folderID.lowercased())\u{0}\(title)"
    }

    /// Keeps an item available in Map Folders while suppressing its point from
    /// the rendered overlay. Native device and Clue markers must win over their
    /// CalTopo copies; attached media points are matched through parentItemID.
    public func excludingRenderedPointIDs(_ pointIDs: Set<String>) -> CaltopoArtifactSnapshot {
        let normalized = Set(pointIDs.map { $0.lowercased() })
        guard !normalized.isEmpty else { return self }
        return CaltopoArtifactSnapshot(
            folders: folders,
            points: points.filter { point in
                !normalized.contains(point.id.lowercased())
                    && point.parentItemID.map { normalized.contains($0.lowercased()) } != true
            },
            lines: lines,
            polygons: polygons,
            items: items,
            totalFeatureCount: totalFeatureCount,
            ignoredTrackCount: ignoredTrackCount
        )
    }
}

public enum CaltopoArtifactVisibilityPolicy {
    public static func legacyPersistedSelectionKeys(_ keys: [String]) -> [String] {
        keys.filter { $0.hasPrefix("map.visibility.") }.sorted()
    }

    /// Combines local/server hidden state while keeping explicit operator choices
    /// authoritative for the active map session.
    public static func hiddenFolderIDs(
        localHidden: Set<String>,
        defaultHidden: Set<String>,
        operatorVisibilityOverrides: [String: Bool]
    ) -> Set<String> {
        var resolved = localHidden.union(defaultHidden)
        for (folderID, visible) in operatorVisibilityOverrides {
            if visible {
                resolved.remove(folderID)
            } else {
                resolved.insert(folderID)
            }
        }
        return resolved
    }
}

public struct CaltopoArtifactChanges: Sendable, Equatable {
    public let featuresByID: [String: Data]
    public let deletedFeatureIDs: Set<String>
    public let receivedFeatureCount: Int

    public init(
        featuresByID: [String: Data] = [:],
        deletedFeatureIDs: Set<String> = [],
        receivedFeatureCount: Int = 0
    ) {
        self.featuresByID = featuresByID
        self.deletedFeatureIDs = deletedFeatureIDs
        self.receivedFeatureCount = receivedFeatureCount
    }
}

public struct CaltopoArtifactFeatureStore: Sendable {
    public private(set) var featuresByID: [String: Data]

    public init(featuresByID: [String: Data] = [:]) {
        self.featuresByID = featuresByID
    }

    public mutating func apply(_ changes: CaltopoArtifactChanges, replacing: Bool) {
        if replacing { featuresByID.removeAll(keepingCapacity: true) }
        for featureID in changes.deletedFeatureIDs {
            featuresByID.removeValue(forKey: featureID)
        }
        featuresByID.merge(changes.featuresByID) { _, incoming in incoming }
    }

    public func snapshot() throws -> CaltopoArtifactSnapshot {
        try CaltopoArtifactDecoder.decode(featuresByID: featuresByID)
    }
}

public enum CaltopoArtifactDecoder {
    public static func decode(data: Data) throws -> CaltopoArtifactSnapshot {
        let changes = try decodeChanges(data: data)
        return try decode(featuresByID: changes.featuresByID)
    }

    public static func decodeChanges(data: Data) throws -> CaltopoArtifactChanges {
        let root = try JSONSerialization.jsonObject(with: data)
        guard let dictionary = root as? [String: Any] else { return CaltopoArtifactChanges() }
        let features = featureArray(in: dictionary) ?? []
        var featuresByID: [String: Data] = [:]
        var deletedFeatureIDs: Set<String> = []
        for feature in features {
            let id = string(feature["id"])
            guard !id.isEmpty else { continue }
            if isDeleted(feature) {
                deletedFeatureIDs.insert(id)
                continue
            }
            featuresByID[id] = try JSONSerialization.data(withJSONObject: feature, options: [.sortedKeys])
        }
        return CaltopoArtifactChanges(
            featuresByID: featuresByID,
            deletedFeatureIDs: deletedFeatureIDs,
            receivedFeatureCount: features.count
        )
    }

    public static func decode(featuresByID: [String: Data]) throws -> CaltopoArtifactSnapshot {
        let features = try featuresByID.values.compactMap { data -> [String: Any]? in
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        }
        return decode(features: features)
    }

    private static func featureArray(in dictionary: [String: Any], depth: Int = 0) -> [[String: Any]]? {
        guard depth < 5 else { return nil }
        for key in ["state", "result", "data", "response"] {
            if let nested = dictionary[key] as? [String: Any],
               let features = featureArray(in: nested, depth: depth + 1) {
                return features
            }
        }
        if let features = dictionary["features"] as? [[String: Any]] { return features }
        for value in dictionary.values {
            if let nested = value as? [String: Any],
               let features = featureArray(in: nested, depth: depth + 1) {
                return features
            }
        }
        return nil
    }

    private static func decode(features: [[String: Any]]) -> CaltopoArtifactSnapshot {
        var featuresByID: [String: [String: Any]] = [:]
        for feature in features {
            let id = string(feature["id"])
            if !id.isEmpty { featuresByID[id] = feature }
        }
        var folderInfo: [String: CaltopoArtifactFolder] = [:]
        var points: [CaltopoPointArtifact] = []
        var lines: [CaltopoLineArtifact] = []
        var polygons: [CaltopoPolygonArtifact] = []
        var items: [CaltopoArtifactItem] = []
        var ignoredTracks = 0

        for feature in features {
            guard let properties = feature["properties"] as? [String: Any] else { continue }
            let id = string(feature["id"])
            let className = string(properties["class"])
            if className == "Folder" {
                folderInfo[id] = CaltopoArtifactFolder(
                    id: id,
                    title: string(properties["title"], fallback: id),
                    initiallyVisible: bool(properties["visible"], fallback: true),
                    parentID: string(properties["folderId"]).nilIfEmpty
                )
                continue
            }
            let folder = effectiveFolderID(
                properties: properties,
                className: className,
                featuresByID: featuresByID
            )
            if folderInfo[folder] == nil, let synthetic = syntheticFolder(folder) {
                folderInfo[folder] = synthetic
            }
        }

        for feature in features {
            guard let properties = feature["properties"] as? [String: Any] else { continue }
            let className = string(properties["class"])
            if className == "Folder" { continue }
            let folderID = effectiveFolderID(properties: properties, className: className, featuresByID: featuresByID)
            guard !folderID.isEmpty else { continue }
            if folderInfo[folderID] == nil {
                let suffix = String(folderID.prefix(8)).nilIfEmpty ?? "unknown"
                folderInfo[folderID] = CaltopoArtifactFolder(
                    id: folderID,
                    title: "Unlisted Folder \(suffix)",
                    initiallyVisible: true
                )
            }
            let id = string(feature["id"])
            guard !id.isEmpty else { continue }
            items.append(CaltopoArtifactItem(
                id: id,
                title: string(properties["title"], fallback: className.isEmpty ? "Map item" : className),
                folderID: folderID,
                className: className,
                r2cGUID: string(properties["r2c-guid"]).nilIfEmpty,
                r2cLastSeenEpochMilliseconds: int64(properties["r2c-last-seen-epoch-ms"])
            ))
        }

        let representedFolders = Set(folderInfo.keys)
        for feature in features {
            guard let properties = feature["properties"] as? [String: Any],
                  let geometry = feature["geometry"] as? [String: Any]
            else { continue }
            let className = string(properties["class"])
            if className == "Folder" { continue }
            if className == "LiveTrack" {
                ignoredTracks += 1
                continue
            }
            let folderID = effectiveFolderID(
                properties: properties,
                className: className,
                featuresByID: featuresByID
            )
            guard representedFolders.contains(folderID) else { continue }
            let id = string(feature["id"], fallback: UUID().uuidString)
            let title = string(properties["title"], fallback: className.isEmpty ? "Map item" : className)
            let description = string(properties["description"])
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
            let geometryType = string(geometry["type"])
            switch geometryType {
            case "Point":
                if let coordinate = coordinate(geometry["coordinates"]) {
                    points.append(CaltopoPointArtifact(
                        id: id,
                        coordinate: coordinate,
                        title: title,
                        symbol: string(properties["marker-symbol"], fallback: "point"),
                        colorHex: properties["marker-color"] as? String,
                        folderID: folderID,
                        parentItemID: parentMarkerID(properties),
                        description: description
                    ))
                }
            case "LineString":
                appendLine(id: id, itemID: id, raw: geometry["coordinates"], title: title, properties: properties, folderID: folderID, into: &lines)
            case "MultiLineString":
                for (index, raw) in (geometry["coordinates"] as? [Any] ?? []).enumerated() {
                    appendLine(id: "\(id)-\(index)", itemID: id, raw: raw, title: title, properties: properties, folderID: folderID, into: &lines)
                }
            case "Polygon":
                if let ring = (geometry["coordinates"] as? [Any])?.first {
                    appendPolygon(id: id, itemID: id, raw: ring, title: title, properties: properties, folderID: folderID, into: &polygons)
                }
            case "MultiPolygon":
                for (index, polygon) in (geometry["coordinates"] as? [[Any]] ?? []).enumerated() {
                    if let ring = polygon.first {
                        appendPolygon(id: "\(id)-\(index)", itemID: id, raw: ring, title: title, properties: properties, folderID: folderID, into: &polygons)
                    }
                }
            default:
                break
            }
        }
        let stableArtifactOrder: (String, String, String, String) -> Bool = { lhsTitle, lhsID, rhsTitle, rhsID in
            let titleOrder = lhsTitle.localizedCaseInsensitiveCompare(rhsTitle)
            if titleOrder != .orderedSame { return titleOrder == .orderedAscending }
            return lhsID.localizedCaseInsensitiveCompare(rhsID) == .orderedAscending
        }
        points.sort { stableArtifactOrder($0.title, $0.id, $1.title, $1.id) }
        lines.sort { stableArtifactOrder($0.title, $0.id, $1.title, $1.id) }
        polygons.sort { stableArtifactOrder($0.title, $0.id, $1.title, $1.id) }
        return CaltopoArtifactSnapshot(
            folders: folderInfo.values.sorted { $0.title < $1.title },
            points: points,
            lines: lines,
            polygons: polygons,
            items: items.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending },
            totalFeatureCount: features.count,
            ignoredTrackCount: ignoredTracks
        )
    }

    private static func isDeleted(_ feature: [String: Any]) -> Bool {
        if bool(feature["deleted"], fallback: false) { return true }
        guard let properties = feature["properties"] as? [String: Any] else {
            return feature["id"] != nil && feature["geometry"] == nil
        }
        if bool(properties["deleted"], fallback: false) { return true }
        let action = string(properties["action"]).lowercased()
        return action == "delete" || action == "removed"
    }

    private static func appendLine(
        id: String, itemID: String, raw: Any?, title: String, properties: [String: Any], folderID: String,
        into output: inout [CaltopoLineArtifact]
    ) {
        let coordinates = coordinateList(raw)
        guard coordinates.count >= 2 else { return }
        output.append(CaltopoLineArtifact(
            id: id,
            itemID: itemID,
            coordinates: coordinates,
            title: title,
            colorHex: colorHex(
                string(properties["stroke"], fallback: "#FF5A1F"),
                opacity: number(properties["stroke-opacity"], fallback: 1)
            ),
            width: number(properties["stroke-width"], fallback: 3),
            folderID: folderID,
            description: string(properties["description"])
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
        ))
    }

    private static func appendPolygon(
        id: String, itemID: String, raw: Any?, title: String, properties: [String: Any], folderID: String,
        into output: inout [CaltopoPolygonArtifact]
    ) {
        let coordinates = coordinateList(raw)
        guard coordinates.count >= 3 else { return }
        output.append(CaltopoPolygonArtifact(
            id: id,
            itemID: itemID,
            coordinates: coordinates,
            title: title,
            strokeHex: colorHex(
                string(properties["stroke"], fallback: "#FF5A1F"),
                opacity: number(properties["stroke-opacity"], fallback: 1)
            ),
            fillHex: colorHex(
                string(properties["fill"], fallback: "#FF5A1F"),
                opacity: number(properties["fill-opacity"], fallback: 0.20)
            ),
            width: number(properties["stroke-width"], fallback: 3),
            folderID: folderID,
            description: string(properties["description"])
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
        ))
    }

    /// CalTopo supplies RGB colors and opacity as separate properties. Keep the
    /// normalized value in Android/CalTopo AARRGGBB order so every renderer
    /// applies the opacity instead of accidentally producing an opaque fill.
    private static func colorHex(_ value: String, opacity: Double) -> String {
        let cleaned = value.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
        let rgb: String
        switch cleaned.count {
        case 8:
            rgb = String(cleaned.suffix(6))
        case 6:
            rgb = cleaned
        default:
            rgb = "FF5A1F"
        }
        let alpha = Int((min(1, max(0, opacity)) * 255).rounded(.down))
        return String(format: "#%02X%@", alpha, rgb.uppercased())
    }

    private static func coordinateList(_ raw: Any?) -> [MapCoordinate] {
        (raw as? [Any] ?? []).compactMap(coordinate)
    }

    private static func coordinate(_ raw: Any?) -> MapCoordinate? {
        guard let pair = raw as? [Any], pair.count >= 2,
              let longitude = (pair[0] as? NSNumber)?.doubleValue,
              let latitude = (pair[1] as? NSNumber)?.doubleValue
        else { return nil }
        let value = MapCoordinate(latitude: latitude, longitude: longitude)
        return value.isValid ? value : nil
    }

    private static func effectiveFolderID(
        properties: [String: Any],
        className: String,
        featuresByID: [String: [String: Any]]
    ) -> String {
        let folderID = string(properties["folderId"])
        if className == "MapMediaObject",
           string(properties["parentId"]).hasPrefix("Marker:") {
            let parentID = String(string(properties["parentId"]).dropFirst("Marker:".count))
            if let parentProperties = featuresByID[parentID]?["properties"] as? [String: Any] {
                return effectiveFolderID(
                    properties: parentProperties,
                    className: string(parentProperties["class"]),
                    featuresByID: featuresByID
                )
            }
        }
        switch className {
        case "Assignment": return "__caltopo_assignments__"
        case "RangeRing": return "__caltopo_range_rings__"
        case "Marker" where folderID.isEmpty: return "__caltopo_markers__"
        case "Shape" where folderID.isEmpty: return "__caltopo_lines_polygons__"
        case "AppTrack" where folderID.isEmpty: return "__caltopo_app_tracks__"
        default: return folderID.isEmpty ? "__caltopo_other_map_items__" : folderID
        }
    }

    private static func syntheticFolder(_ id: String) -> CaltopoArtifactFolder? {
        switch id {
        case "__caltopo_assignments__": .init(id: id, title: "Assignments", initiallyVisible: true)
        case "__caltopo_range_rings__": .init(id: id, title: "Range Rings", initiallyVisible: true)
        case "__caltopo_markers__": .init(id: id, title: "Markers", initiallyVisible: true)
        case "__caltopo_lines_polygons__": .init(id: id, title: "Lines & Polygons", initiallyVisible: true)
        case "__caltopo_app_tracks__": .init(id: id, title: "App Tracks", initiallyVisible: true)
        case "__caltopo_other_map_items__": .init(id: id, title: "Other Map Items", initiallyVisible: true)
        default: nil
        }
    }

    private static func parentMarkerID(_ properties: [String: Any]) -> String? {
        let parentID = string(properties["parentId"])
        guard parentID.hasPrefix("Marker:") else { return nil }
        return String(parentID.dropFirst("Marker:".count)).nilIfEmpty
    }

    private static func string(_ value: Any?, fallback: String = "") -> String {
        (value as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallback
    }
    private static func int64(_ value: Any?) -> Int64? {
        if let number = value as? NSNumber { return number.int64Value }
        if let string = value as? String { return Int64(string) }
        return nil
    }
    private static func bool(_ value: Any?, fallback: Bool) -> Bool { (value as? Bool) ?? fallback }
    private static func number(_ value: Any?, fallback: Double) -> Double { (value as? NSNumber)?.doubleValue ?? fallback }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
