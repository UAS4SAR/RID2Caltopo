import Foundation

public struct OperationalMapBounds: Sendable, Equatable {
    public let north: Double
    public let south: Double
    public let west: Double
    public let east: Double

    public init(north: Double, south: Double, west: Double, east: Double) {
        self.north = min(85.05112878, max(-85.05112878, max(north, south)))
        self.south = min(85.05112878, max(-85.05112878, min(north, south)))
        self.west = min(180, max(-180, min(west, east)))
        self.east = min(180, max(-180, max(west, east)))
    }

    public init(coordinates: [MapCoordinate]) {
        self.init(
            north: coordinates.map(\.latitude).max() ?? 0,
            south: coordinates.map(\.latitude).min() ?? 0,
            west: coordinates.map(\.longitude).min() ?? 0,
            east: coordinates.map(\.longitude).max() ?? 0
        )
    }
}

public struct OperationalOfflineTile: Sendable, Hashable {
    public let zoom: Int
    public let x: Int
    public let y: Int

    public init(zoom: Int, x: Int, y: Int) {
        self.zoom = zoom
        self.x = x
        self.y = y
    }
}

public struct OperationalOfflineCapacity: Sendable, Equatable {
    public let currentTileCacheBytes: Int64
    public let currentDEMCacheBytes: Int64
    public let estimatedTileBytes: Int64
    public let estimatedDEMBytes: Int64
    public let maximumTileCacheBytes: Int64
    public let availableVolumeBytes: Int64?

    public init(
        currentTileCacheBytes: Int64,
        currentDEMCacheBytes: Int64 = 0,
        estimatedTileBytes: Int64,
        estimatedDEMBytes: Int64,
        maximumTileCacheBytes: Int64,
        availableVolumeBytes: Int64?
    ) {
        self.currentTileCacheBytes = max(0, currentTileCacheBytes)
        self.currentDEMCacheBytes = max(0, currentDEMCacheBytes)
        self.estimatedTileBytes = max(0, estimatedTileBytes)
        self.estimatedDEMBytes = max(0, estimatedDEMBytes)
        self.maximumTileCacheBytes = max(0, maximumTileCacheBytes)
        self.availableVolumeBytes = availableVolumeBytes.map { max(0, $0) }
    }

    public var projectedTileCacheBytes: Int64 {
        Self.saturatedAdd(currentTileCacheBytes, estimatedTileBytes)
    }

    public var estimatedDownloadBytes: Int64 {
        Self.saturatedAdd(estimatedTileBytes, estimatedDEMBytes)
    }

    public var currentOfflineStorageBytes: Int64 {
        Self.saturatedAdd(currentTileCacheBytes, currentDEMCacheBytes)
    }

    public var projectedOfflineStorageBytes: Int64 {
        Self.saturatedAdd(currentOfflineStorageBytes, estimatedDownloadBytes)
    }

    public var exceedsCacheLimit: Bool { projectedTileCacheBytes > maximumTileCacheBytes }

    public var exceedsAvailableVolume: Bool {
        guard let availableVolumeBytes else { return false }
        return estimatedDownloadBytes > availableVolumeBytes * 95 / 100
    }

    public var recommendedMaximumBytes: Int64 {
        let decimalGB: Int64 = 1_000_000_000
        let headroom = max(100_000_000, projectedTileCacheBytes / 10)
        let desired = Self.saturatedAdd(projectedTileCacheBytes, headroom)
        let rounded = Self.saturatedAdd(desired, decimalGB - 1) / decimalGB * decimalGB
        return min(64_000_000_000, max(100_000_000, rounded))
    }

    private static func saturatedAdd(_ left: Int64, _ right: Int64) -> Int64 {
        left > Int64.max - right ? Int64.max : left + right
    }
}

public enum OperationalOfflineProgress {
    public static func weightedCompletedBytes(
        completedBytes: Int64,
        activeEstimatedBytes: Int64,
        activeTransferredBytes: Int64,
        activeExpectedBytes: Int64?
    ) -> Int64 {
        let completed = max(0, completedBytes)
        let estimated = max(0, activeEstimatedBytes)
        guard estimated > 0, activeTransferredBytes > 0 else { return completed }
        let expected = max(1, activeExpectedBytes ?? estimated)
        let active = min(
            estimated,
            Int64((Double(estimated) * min(1, Double(activeTransferredBytes) / Double(expected))).rounded(.down))
        )
        return completed > Int64.max - active ? Int64.max : completed + active
    }

    public static func fraction(completedBytes: Int64, totalBytes: Int64) -> Double {
        guard totalBytes > 0 else { return 0 }
        return min(1, max(0, Double(completedBytes) / Double(totalBytes)))
    }
}

public enum OperationalCacheRetryPolicy {
    public static func shouldRetry(statusCode: Int) -> Bool {
        statusCode == 408 || statusCode == 429 || (500 ... 599).contains(statusCode)
    }

    public static func delaySeconds(
        attempt: Int,
        retryAfterHeader: String? = nil
    ) -> TimeInterval? {
        guard attempt >= 0 && attempt < 3 else { return nil }
        if let retryAfterHeader,
           let seconds = TimeInterval(retryAfterHeader.trimmingCharacters(in: .whitespacesAndNewlines)),
           seconds >= 0 {
            return min(30, seconds)
        }
        return [0.5, 1, 2][attempt]
    }

    public static func prefetchDelaySeconds(failureCount: Int) -> TimeInterval {
        let exponent = min(7, max(0, failureCount - 1))
        return min(3_600, 30 * pow(2, Double(exponent)))
    }
}

public enum OperationalCacheFreshness {
    public static func isFresh(modifiedAt: Date?, cutoff: Date) -> Bool {
        guard let modifiedAt else { return false }
        return modifiedAt >= cutoff
    }
}

public enum OperationalMapTileLayerState {
    public static func fingerprint(
        baseLayer: OperationalMapBaseLayer,
        contours: Bool,
        offlineOnly: Bool,
        revision: Int
    ) -> String {
        "\(baseLayer.rawValue)|\(contours)|\(offlineOnly)|\(revision)"
    }
}

public enum OperationalVisibleMapTile {
    public static func zoomLevel(
        worldMapWidth: Double,
        visibleMapWidth: Double,
        viewportWidth: Double,
        tileSize: Double = 256
    ) -> Int {
        guard worldMapWidth > 0, visibleMapWidth > 0, viewportWidth > 0, tileSize > 0 else { return 0 }
        let tilesAcross = worldMapWidth / visibleMapWidth * viewportWidth / tileSize
        return min(20, max(0, Int(floor(log2(tilesAcross)))))
    }

    public static func tile(latitude: Double, longitude: Double, zoom: Int) -> OperationalOfflineTile {
        let zoom = min(20, max(0, zoom))
        let count = pow(2, Double(zoom))
        let longitude = min(180, max(-180, longitude))
        let latitude = min(85.051_128_78, max(-85.051_128_78, latitude)) * .pi / 180
        let x = min(Int(count) - 1, max(0, Int(floor((longitude + 180) / 360 * count))))
        let yValue = (1 - log(tan(latitude) + 1 / cos(latitude)) / .pi) / 2 * count
        let y = min(Int(count) - 1, max(0, Int(floor(yValue))))
        return OperationalOfflineTile(zoom: zoom, x: x, y: y)
    }
}

public struct OperationalOfflinePreset: Sendable, Hashable, Identifiable {
    public let id: String
    public let label: String
    public let minimumZoom: Int
    public let maximumZoom: Int

    public init(id: String, label: String, minimumZoom: Int, maximumZoom: Int) {
        self.id = id
        self.label = label
        self.minimumZoom = minimumZoom
        self.maximumZoom = maximumZoom
    }

    public static let overview = Self(id: "overview", label: "Overview (z8-z12)", minimumZoom: 8, maximumZoom: 12)
    public static let operations = Self(id: "operations", label: "Ops (z12-z16)", minimumZoom: 12, maximumZoom: 16)
    public static let fullDetail = Self(id: "full", label: "Full detail (z8-z19)", minimumZoom: 8, maximumZoom: 19)
    public static let all = [overview, operations, fullDetail]
}

public enum OperationalDEMResolution: Int, Sendable, Hashable, Identifiable, CaseIterable {
    case maximum1m = 1
    case standard30m = 30
    case enhanced10m = 10

    public var id: Int { rawValue }
    public var label: String {
        switch self {
        case .standard30m: "Standard (30 m)"
        case .enhanced10m: "Enhanced (10 m)"
        case .maximum1m: "USGS S1M (1 m)"
        }
    }

    public var explanation: String {
        switch self {
        case .standard30m: "Broad coverage and smallest download."
        case .enhanced10m: "About 9x as many terrain samples as 30 m."
        case .maximum1m: "Default; downloads available USGS seamless 1 m tiles with 10 m fallback; may be very large."
        }
    }
}

public struct OperationalS1MProduct: Sendable, Hashable {
    public let url: URL
    public let fileName: String
    public let expectedBytes: Int64?
}

public enum OperationalS1MCatalog {
    public static let datasetName = "Seamless 1-m DEM (S1M)"

    public static func products(
        data: Data,
        containing coordinate: (latitude: Double, longitude: Double)? = nil
    ) throws -> [OperationalS1MProduct] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = object["items"] as? [[String: Any]]
        else { throw CocoaError(.fileReadCorruptFile) }
        return items.compactMap { item in
            guard let text = item["downloadURL"] as? String,
                  let url = URL(string: text), url.scheme?.lowercased() == "https",
                  ["tif", "tiff"].contains(url.pathExtension.lowercased())
            else { return nil }
            let box = item["boundingBox"] as? [String: Any]
            let minX = (box?["minX"] as? NSNumber)?.doubleValue
            let maxX = (box?["maxX"] as? NSNumber)?.doubleValue
            let minY = (box?["minY"] as? NSNumber)?.doubleValue
            let maxY = (box?["maxY"] as? NSNumber)?.doubleValue
            if let coordinate {
                guard let minX, let maxX, let minY, let maxY,
                      (minY ... maxY).contains(coordinate.latitude),
                      (minX ... maxX).contains(coordinate.longitude)
                else { return nil }
            }
            let fileName: String
            if let minX, let maxX, let minY, let maxY {
                fileName = "R2C_S1M_\(Int64((minY * 100_000).rounded()))_\(Int64((maxY * 100_000).rounded()))_"
                    + "\(Int64((minX * 100_000).rounded()))_\(Int64((maxX * 100_000).rounded()))_\(url.lastPathComponent)"
            } else {
                fileName = url.lastPathComponent
            }
            let expectedBytes = (item["sizeInBytes"] as? NSNumber)?.int64Value
                ?? (item["sizeInBytes"] as? String).flatMap(Int64.init)
            return OperationalS1MProduct(url: url, fileName: fileName, expectedBytes: expectedBytes)
        }
    }
}

public enum OperationalOfflineMapPlanner {
    public static func tileCount(
        bounds: OperationalMapBounds,
        minimumZoom: Int,
        maximumZoom: Int
    ) -> Int {
        guard minimumZoom >= 0, maximumZoom >= minimumZoom, maximumZoom <= 22 else { return 0 }
        var count: Int64 = 0
        for zoom in minimumZoom ... maximumZoom {
            let range = tileRange(bounds: bounds, zoom: zoom)
            count += Int64(range.x.count) * Int64(range.y.count)
        }
        return Int(min(Int64(Int.max), count))
    }

    public static func tiles(
        bounds: OperationalMapBounds,
        minimumZoom: Int,
        maximumZoom: Int,
        maximumCount: Int = 250_000
    ) -> [OperationalOfflineTile]? {
        guard minimumZoom >= 0, maximumZoom >= minimumZoom, maximumZoom <= 22 else { return [] }
        let count = tileCount(bounds: bounds, minimumZoom: minimumZoom, maximumZoom: maximumZoom)
        guard count <= maximumCount else { return nil }
        var result: [OperationalOfflineTile] = []
        result.reserveCapacity(count)
        for zoom in minimumZoom ... maximumZoom {
            let range = tileRange(bounds: bounds, zoom: zoom)
            for x in range.x {
                for y in range.y {
                    result.append(.init(zoom: zoom, x: x, y: y))
                }
            }
        }
        return result
    }

    public static func demTileNames(bounds: OperationalMapBounds) -> [String] {
        let southBlock = Int(floor(bounds.south))
        let northBlock = Int(ceil(bounds.north)) - 1
        let westBlock = Int(floor(bounds.west))
        let eastBlock = Int(ceil(bounds.east)) - 1
        guard southBlock <= northBlock, westBlock <= eastBlock else { return [] }
        var names: [String] = []
        for latitudeBlock in southBlock ... northBlock {
            let northEdge = latitudeBlock + 1
            let latitude = northEdge >= 0
                ? String(format: "n%02d", northEdge)
                : String(format: "s%02d", -northEdge)
            for longitudeBlock in westBlock ... eastBlock {
                let longitude = longitudeBlock < 0
                    ? String(format: "w%03d", -longitudeBlock)
                    : String(format: "e%03d", longitudeBlock + 1)
                names.append(latitude + longitude)
            }
        }
        return names
    }

    public static func estimatedDEMTileCount(
        bounds: OperationalMapBounds,
        resolution: OperationalDEMResolution
    ) -> Int {
        guard resolution == .maximum1m else { return demTileNames(bounds: bounds).count }
        let centerLatitude = (bounds.north + bounds.south) / 2 * .pi / 180
        let widthMeters = abs(bounds.east - bounds.west) * 111_320 * max(0.1, cos(centerLatitude))
        let heightMeters = abs(bounds.north - bounds.south) * 111_320
        // Project-based USGS 1 m products are commonly distributed as 10 km square tiles.
        let oneMeterTiles = max(1, Int(ceil(widthMeters / 10_000)) * Int(ceil(heightMeters / 10_000)))
        return demTileNames(bounds: bounds).count + oneMeterTiles
    }

    public static func estimatedBytes(
        tileCount: Int,
        includeContours: Bool,
        demTileCount: Int,
        demResolution: OperationalDEMResolution = .maximum1m
    ) -> Int64 {
        let mapOperations = Int64(tileCount) * Int64(includeContours ? 2 : 1)
        let demBytesPerPlanningTile: Int64 = switch demResolution {
        case .standard30m: 54_000_000
        case .enhanced10m: 486_000_000
        // Maximum detail includes 10 m fallback coverage under S1M tiles.
        case .maximum1m: 486_000_000
        }
        return mapOperations * 32_000 + Int64(demTileCount) * demBytesPerPlanningTile
    }

    public static func estimatedDEMBytes(
        bounds: OperationalMapBounds,
        resolution: OperationalDEMResolution
    ) -> Int64 {
        let geographicTiles = Int64(demTileNames(bounds: bounds).count)
        switch resolution {
        case .standard30m: return geographicTiles * 54_000_000
        case .enhanced10m: return geographicTiles * 486_000_000
        case .maximum1m:
            let total = Int64(estimatedDEMTileCount(bounds: bounds, resolution: resolution))
            let oneMeterTiles = max(1, total - geographicTiles)
            return geographicTiles * 486_000_000 + oneMeterTiles * 400_000_000
        }
    }

    private static func tileRange(bounds: OperationalMapBounds, zoom: Int) -> (x: ClosedRange<Int>, y: ClosedRange<Int>) {
        let maximum = (1 << zoom) - 1
        let minX = longitudeToTileX(bounds.west, zoom: zoom).clamped(to: 0 ... maximum)
        let maxX = longitudeToTileX(bounds.east, zoom: zoom).clamped(to: 0 ... maximum)
        let minY = latitudeToTileY(bounds.north, zoom: zoom).clamped(to: 0 ... maximum)
        let maxY = latitudeToTileY(bounds.south, zoom: zoom).clamped(to: 0 ... maximum)
        return (minX ... maxX, minY ... maxY)
    }

    private static func longitudeToTileX(_ longitude: Double, zoom: Int) -> Int {
        Int(floor((longitude + 180) / 360 * Double(1 << zoom)))
    }

    private static func latitudeToTileY(_ latitude: Double, zoom: Int) -> Int {
        let radians = latitude * .pi / 180
        return Int(floor((1 - asinh(tan(radians)) / .pi) / 2 * Double(1 << zoom)))
    }
}

public struct OperationalSignalLossInput: Sendable, Equatable {
    public let signalIdleSeconds: Double
    public let trackTelemetryIdleSeconds: Double
    public let pairedSEIIdleSeconds: Double?
    public let learnedIntervalSeconds: Double?
    public let learnedSamples: Int
    public let distanceFromDeviceFeet: Double
    public let distanceFromTakeoffFeet: Double?
    public let bridgeCheckDistanceFeet: Double
    public let maximumTrackDelaySeconds: Double
    public let hasPreviouslyExceededBridgeDistance: Bool

    public init(
        signalIdleSeconds: Double,
        trackTelemetryIdleSeconds: Double? = nil,
        pairedSEIIdleSeconds: Double? = nil,
        learnedIntervalSeconds: Double?,
        learnedSamples: Int,
        distanceFromDeviceFeet: Double,
        distanceFromTakeoffFeet: Double?,
        bridgeCheckDistanceFeet: Double,
        maximumTrackDelaySeconds: Double,
        hasPreviouslyExceededBridgeDistance: Bool
    ) {
        self.signalIdleSeconds = signalIdleSeconds
        self.trackTelemetryIdleSeconds = trackTelemetryIdleSeconds ?? signalIdleSeconds
        self.pairedSEIIdleSeconds = pairedSEIIdleSeconds
        self.learnedIntervalSeconds = learnedIntervalSeconds
        self.learnedSamples = learnedSamples
        self.distanceFromDeviceFeet = distanceFromDeviceFeet
        self.distanceFromTakeoffFeet = distanceFromTakeoffFeet
        self.bridgeCheckDistanceFeet = bridgeCheckDistanceFeet
        self.maximumTrackDelaySeconds = maximumTrackDelaySeconds
        self.hasPreviouslyExceededBridgeDistance = hasPreviouslyExceededBridgeDistance
    }
}

public struct OperationalSignalLossDecision: Sendable, Equatable {
    public let alert: Bool
    public let hasExceededBridgeDistance: Bool
    public let idleThresholdSeconds: Double
}

public enum OperationalSignalLossPolicy {
    public static func evaluate(_ input: OperationalSignalLossInput) -> OperationalSignalLossDecision {
        let dynamic = input.learnedSamples >= 2 && (input.learnedIntervalSeconds ?? 0) > 0
            ? (input.learnedIntervalSeconds ?? 0) * 2.5
            : 10
        let maximum = max(2, input.maximumTrackDelaySeconds - 1)
        let threshold = min(maximum, max(10, dynamic))
        let exceeded = input.hasPreviouslyExceededBridgeDistance
            || input.distanceFromDeviceFeet > input.bridgeCheckDistanceFeet
            || (input.distanceFromTakeoffFeet ?? 0) > input.bridgeCheckDistanceFeet
        let returnedToDevice = input.distanceFromDeviceFeet <= input.bridgeCheckDistanceFeet
        let returnedToTakeoff = input.distanceFromTakeoffFeet.map { $0 <= 30 } ?? false
        let redundantTelemetryIdleSeconds = min(
            input.trackTelemetryIdleSeconds,
            input.pairedSEIIdleSeconds ?? .infinity
        )
        return OperationalSignalLossDecision(
            alert: exceeded
                && !returnedToDevice
                && !returnedToTakeoff
                && input.signalIdleSeconds > threshold
                && redundantTelemetryIdleSeconds > threshold,
            hasExceededBridgeDistance: exceeded,
            idleThresholdSeconds: threshold
        )
    }
}

public enum OperationalBridgeAlertPolicy {
    public static func shouldMonitor(
        scannerRunning: Bool,
        activeFlightCount: Int,
        allActiveFlightsCoveredByFreshPairedSEI: Bool
    ) -> Bool {
        scannerRunning
            && activeFlightCount > 0
            && !allActiveFlightsCoveredByFreshPairedSEI
    }
}

public enum OperationalAltitudeSeverity: Int, Sendable, Equatable, Comparable {
    case normal
    case caution
    case overLimit

    public static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }
}

public enum OperationalAltitudeAlertPolicy {
    public static func severity(aglFeet: Double?) -> OperationalAltitudeSeverity {
        guard let aglFeet, aglFeet.isFinite else { return .normal }
        if aglFeet >= 200 { return .overLimit }
        if aglFeet >= 180 { return .caution }
        return .normal
    }
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(range.upperBound, Swift.max(range.lowerBound, self))
    }
}
