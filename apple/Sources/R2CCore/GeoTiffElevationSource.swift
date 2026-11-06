import Compression
import Foundation

/// Random-access elevation sampler for the USGS 3DEP GeoTIFFs downloaded by
/// both RID2Caltopo platforms. Supports geographic 30/10 m products and
/// NAD83/WGS84 UTM project tiles and EPSG:6350 S1M tiles, plus raw, LZW,
/// Deflate, PackBits, and predictors 2/3.
public final class GeoTiffElevationSource: @unchecked Sendable {
    public struct Sample: Sendable, Equatable {
        public let elevationMeters: Double
        public let horizontalResolutionMeters: Double
    }
    private struct Bounds {
        let minLatitude: Double, maxLatitude: Double, minLongitude: Double, maxLongitude: Double
        func contains(latitude: Double, longitude: Double) -> Bool {
            latitude >= minLatitude && latitude < maxLatitude && longitude >= minLongitude && longitude < maxLongitude
        }
    }

    private struct Tile {
        let url: URL
        let bounds: Bounds?
    }

    fileprivate enum Endian: Equatable { case little, big }

    private struct Metadata {
        let endian: Endian
        let width: Int, height: Int
        let compression: Int, predictor: Int
        let samplesPerPixel: Int, planarConfiguration: Int
        let bitsPerSample: Int, sampleFormat: Int
        let tieI: Double, tieJ: Double, tieX: Double, tieY: Double
        let scaleX: Double, scaleY: Double
        let tiled: Bool, tileWidth: Int?, tileHeight: Int?, rowsPerStrip: Int?
        let offsets: [Int], byteCounts: [Int]
        let noData: Double?
        let verticalUnitCode: Int
        let projectedCSTypeCode: Int?

        func modelCoordinate(latitude: Double, longitude: Double) -> (x: Double, y: Double)? {
            guard let epsg = projectedCSTypeCode else { return (longitude, latitude) }
            if epsg == 6350 {
                return GeoTiffElevationSource.latLonToConusAlbers(latitude: latitude, longitude: longitude)
            }
            return GeoTiffElevationSource.latLonToUTM(latitude: latitude, longitude: longitude, epsg: epsg)
        }

        func horizontalResolutionMeters(latitude: Double) -> Double {
            if projectedCSTypeCode != nil { return max(abs(scaleX), abs(scaleY)) }
            let x = abs(scaleX) * 111_320 * max(0.1, cos(latitude * .pi / 180))
            return max(x, abs(scaleY) * 111_320)
        }
    }

    private let directory: URL
    private let decodedBlockCacheDirectory: URL?
    private let onDecodedBlockPersisted: (@Sendable (URL) -> Void)?
    private let lock = NSLock()
    /// Serializes operating-area warms. A newer request supersedes the one in progress.
    private let warmGate = NSLock()
    /// Block files are written off the sample caller so a clue ray is not stuck on disk.
    private let persistQueue = DispatchQueue(label: "RID2Caltopo.dem-block-persist", qos: .utility)
    private var warmGeneration: UInt64 = 0
    private var blockDecodeCount = 0
    private var tileFileSizes: [URL: Int] = [:]
    private var tiles: [Tile] = []
    private var catalogDate = Date.distantPast
    private var catalogReady = false
    private var catalogWarmInFlight = false
    private var catalogInvalidations: UInt64 = 0
    private var catalogInvalidationLoaded: UInt64 = 0
    private var metadataCache: [URL: Metadata] = [:]
    private var fileCache: [URL: Data] = [:]
    private var blockCache: [String: Data] = [:]
    private var blockOrder: [String] = []
    /// Tiles already known to be coarser than a footprint's S1M limit. Avoids remapping them on every ray step.
    private var coarseTileURLs: Set<URL> = []
    /// Shared decoded-block budget. Clue ray-marches raise this so a 2-tile walk stays hot.
    private var maxCachedFiles = 2
    private var maxCachedBlocks = 8
    private var clueSessionDepth = 0
    /// Flight-start warm keeps elevated limits so form/post-save walks stay hot.
    private var flightWarmRetained = false
    private var timingCatalogNs: Int64 = 0
    private var timingTileOpenDecodeNs: Int64 = 0
    private var timingSampleCount = 0

    /// `decodedBlockCacheDirectory` stores predictor-applied blocks so the next launch
    /// does not repeat LZW. Nil disables persistence (unit tests). The handler runs
    /// off this type's lock after a block is durably written.
    public init(
        directory: URL,
        decodedBlockCacheDirectory: URL? = nil,
        onDecodedBlockPersisted: (@Sendable (URL) -> Void)? = nil
    ) {
        self.directory = directory
        self.decodedBlockCacheDirectory = decodedBlockCacheDirectory
        self.onDecodedBlockPersisted = onDecodedBlockPersisted
    }

    func testingBlockDecodeCount() -> Int { lock.r2cWithLock { blockDecodeCount } }

    /// Test hook: block until queued decoded-block writes have finished.
    func testingWaitForPersistedBlocks() { persistQueue.sync {} }

    /// Keep decoded S1M tiles/blocks in memory for a clue walk. Nested; thread-safe.
    public func beginClueSamplingSession() {
        lock.r2cWithLock {
            clueSessionDepth += 1
            if clueSessionDepth == 1 {
                maxCachedFiles = 8
                maxCachedBlocks = 64
                timingCatalogNs = 0
                timingTileOpenDecodeNs = 0
                timingSampleCount = 0
            }
        }
    }

    public func endClueSamplingSession() {
        lock.r2cWithLock {
            guard clueSessionDepth > 0 else { return }
            clueSessionDepth -= 1
            if clueSessionDepth == 0, !flightWarmRetained {
                maxCachedFiles = 2
                maxCachedBlocks = 8
                while fileCache.count > maxCachedFiles, let key = fileCache.keys.first {
                    fileCache.removeValue(forKey: key)
                }
                while blockOrder.count > maxCachedBlocks {
                    blockCache.removeValue(forKey: blockOrder.removeFirst())
                }
            }
        }
    }

    /// Raise decode-cache limits for the flight operating area (does not reset timing counters).
    public func retainFlightWarmCache() {
        lock.r2cWithLock {
            flightWarmRetained = true
            maxCachedFiles = max(maxCachedFiles, 8)
            maxCachedBlocks = max(maxCachedBlocks, 64)
        }
    }

    public func releaseFlightWarmCache() {
        lock.r2cWithLock {
            flightWarmRetained = false
            if clueSessionDepth == 0 {
                maxCachedFiles = 2
                maxCachedBlocks = 8
                while fileCache.count > maxCachedFiles, let key = fileCache.keys.first {
                    fileCache.removeValue(forKey: key)
                }
                while blockOrder.count > maxCachedBlocks {
                    blockCache.removeValue(forKey: blockOrder.removeFirst())
                }
            }
        }
    }

    /// Pre-decode S1M tiles covering [latitude]/[longitude] ± radius into the elevated cache.
    /// Call from a background worker after TerrainPrefetch. One warm runs at a time; a newer
    /// call supersedes the older grid. Decode work yields so a large LZW pass cannot sit at
    /// full core for minutes. Returns how many grid points were sampled.
    @discardableResult
    public func warmDecodedTiles(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        isCancelled: @escaping () -> Bool = { false }
    ) -> Int {
        guard latitude.isFinite, longitude.isFinite, radiusMeters.isFinite, radiusMeters > 0 else { return 0 }
        let token = lock.r2cWithLock { () -> UInt64 in
            warmGeneration &+= 1
            return warmGeneration
        }
        let stop = { isCancelled() || self.currentWarmGeneration() != token }
        warmGate.lock()
        defer { warmGate.unlock() }
        if stop() { return 0 }
        retainFlightWarmCache()
        beginClueSamplingSession()
        defer { endClueSamplingSession() }
        ensureCatalogCurrent()
        let pacer = DemDecodePacer()
        // ~400 m grid over the operating circle (~1 mi) opens the blocks a clue walk will touch.
        let stepMeters = min(400.0, max(100.0, radiusMeters / 4.0))
        let metersPerDegreeLat = 111_195.0
        let metersPerDegreeLon = max(1.0, 111_195.0 * cos(latitude * .pi / 180))
        var lat = latitude - radiusMeters / metersPerDegreeLat
        let latMax = latitude + radiusMeters / metersPerDegreeLat
        var visited = 0
        while lat <= latMax + 1e-9 {
            if stop() { return visited }
            var lon = longitude - radiusMeters / metersPerDegreeLon
            let lonMax = longitude + radiusMeters / metersPerDegreeLon
            while lon <= lonMax + 1e-9 {
                if stop() { return visited }
                let dLat = (lat - latitude) * metersPerDegreeLat
                let dLon = (lon - longitude) * metersPerDegreeLon
                if (dLat * dLat + dLon * dLon).squareRoot() <= radiusMeters + 1.0 {
                    _ = pacedSample(
                        latitude: lat, longitude: lon, isCancelled: stop, pace: pacer
                    )
                    visited += 1
                }
                lon += stepMeters / metersPerDegreeLon
            }
            lat += stepMeters / metersPerDegreeLat
        }
        if stop() { return visited }
        // Always touch the center last so the aircraft cell is resident.
        _ = pacedSample(latitude: latitude, longitude: longitude, isCancelled: stop, pace: pacer)
        return visited + 1
    }

    private func currentWarmGeneration() -> UInt64 { lock.r2cWithLock { warmGeneration } }

    /// Snapshot and reset session counters (catalog lookup + tile open/decode + samples).
    public func takeClueSamplingTiming() -> (catalogMs: Double, tileOpenDecodeMs: Double, sampleCount: Int) {
        lock.r2cWithLock {
            let catalogMs = Double(timingCatalogNs) / 1_000_000
            let tileMs = Double(timingTileOpenDecodeNs) / 1_000_000
            let samples = timingSampleCount
            timingCatalogNs = 0
            timingTileOpenDecodeNs = 0
            timingSampleCount = 0
            return (catalogMs, tileMs, samples)
        }
    }

    /// Drop prior warm/nested-session accumulation so the next take is per-march only.
    public func resetClueSamplingTiming() {
        lock.r2cWithLock {
            timingCatalogNs = 0
            timingTileOpenDecodeNs = 0
            timingSampleCount = 0
        }
    }

    public var isCatalogReady: Bool { lock.r2cWithLock { catalogReady } }

    public var catalogDirectoryPath: String { directory.path }

    public var catalogTileCount: Int { lock.r2cWithLock { tiles.count } }

    /// True when a TerrainPrefetch / download invalidate has not yet been scanned into `tiles`.
    public var needsCatalogRefresh: Bool {
        lock.r2cWithLock {
            !catalogReady
                || catalogInvalidationLoaded != catalogInvalidations
                || Date().timeIntervalSince(catalogDate) >= 60
                || tiles.isEmpty
        }
    }

    /// Scan the DEM directory without waiting for a map view. Catalog I/O never holds [lock].
    public func warmCatalog() {
        refreshCatalogFromDisk()
    }

    /// Synchronous catalog rebuild when clue sampling must see tiles TerrainPrefetch just wrote.
    public func ensureCatalogCurrent() {
        if needsCatalogRefresh { refreshCatalogFromDisk() }
    }

    public func invalidateCatalog() {
        lock.r2cWithLock {
            catalogInvalidations &+= 1
            catalogDate = .distantPast
            coarseTileURLs.removeAll()
            tileFileSizes.removeAll()
        }
        scheduleWarmCatalog()
    }

    public func sampleElevationMeters(latitude: Double, longitude: Double) -> Double? {
        sample(latitude: latitude, longitude: longitude)?.elevationMeters
    }

    /// Paths considered for a local sample (bounds hit or nil-bounds wildcard). For miss diagnostics.
    public func candidateTilePaths(latitude: Double, longitude: Double) -> [String] {
        let snapshot = lock.r2cWithLock { tiles }
        return snapshot
            .filter { $0.bounds?.contains(latitude: latitude, longitude: longitude) != false }
            .map(\.url.path)
    }

    /// Concise nearest-tile summary for miss diagnostics: `name south..north/west..east` or `none`.
    public func nearestTileBoundsSummary(latitude: Double, longitude: Double) -> String {
        let snapshot = lock.r2cWithLock { tiles }
        guard !snapshot.isEmpty else { return "none" }
        var bestName = ""
        var bestBounds: Bounds?
        var bestDistance = Double.infinity
        for tile in snapshot {
            let name = tile.url.lastPathComponent
            guard let bounds = tile.bounds else {
                if bestBounds == nil {
                    bestName = name
                    bestDistance = 0
                }
                continue
            }
            if bounds.contains(latitude: latitude, longitude: longitude) {
                return String(
                    format: "%@ %.5f..%.5f/%.5f..%.5f",
                    locale: Locale(identifier: "en_US_POSIX"),
                    name, bounds.minLatitude, bounds.maxLatitude, bounds.minLongitude, bounds.maxLongitude
                )
            }
            let clampedLat = min(max(latitude, bounds.minLatitude), bounds.maxLatitude)
            let clampedLon = min(max(longitude, bounds.minLongitude), bounds.maxLongitude)
            let dLat = (latitude - clampedLat) * 111_195
            let dLon = (longitude - clampedLon) * 111_195 * max(0.1, cos(latitude * .pi / 180))
            let distance = (dLat * dLat + dLon * dLon).squareRoot()
            if distance < bestDistance {
                bestDistance = distance
                bestName = name
                bestBounds = bounds
            }
        }
        guard let bounds = bestBounds else {
            return bestName.isEmpty ? "none" : bestName
        }
        return String(
            format: "%@ %.5f..%.5f/%.5f..%.5f",
            locale: Locale(identifier: "en_US_POSIX"),
            bestName, bounds.minLatitude, bounds.maxLatitude, bounds.minLongitude, bounds.maxLongitude
        )
    }

    /// `maximumHorizontalResolutionMeters` drops coarser rasters before any block is decoded.
    /// The footprint asks for S1M (1.5 m) and must not spend the pass inflating 10 m / 30 m tiles.
    /// `isCancelled` is checked between tiles and during a long LZW block so a timed-out
    /// clue ray-march can stop mid-decode.
    public func sample(
        latitude: Double,
        longitude: Double,
        maximumHorizontalResolutionMeters: Double = .infinity,
        isCancelled: @escaping () -> Bool = { false }
    ) -> Sample? {
        pacedSample(
            latitude: latitude,
            longitude: longitude,
            maximumHorizontalResolutionMeters: maximumHorizontalResolutionMeters,
            isCancelled: isCancelled,
            pace: nil
        )
    }

    private func pacedSample(
        latitude: Double,
        longitude: Double,
        maximumHorizontalResolutionMeters: Double = .infinity,
        isCancelled: @escaping () -> Bool,
        pace: DemDecodePacer?
    ) -> Sample? {
        guard latitude.isFinite, longitude.isFinite else { return nil }
        if isCancelled() { return nil }
        let catalogStarted = DispatchTime.now().uptimeNanoseconds
        let snapshot: [Tile]
        let ready: Bool
        let needsRefresh: Bool
        lock.lock()
        ready = catalogReady
        needsRefresh = catalogInvalidationLoaded != catalogInvalidations
            || Date().timeIntervalSince(catalogDate) >= 60
            || tiles.isEmpty
        snapshot = tiles
        lock.unlock()
        let catalogElapsed = Int64(DispatchTime.now().uptimeNanoseconds &- catalogStarted)
        lock.r2cWithLock {
            timingCatalogNs += catalogElapsed
            timingSampleCount += 1
        }
        if !ready {
            scheduleWarmCatalog()
            return nil
        }
        if needsRefresh { scheduleWarmCatalog() }

        var best: Sample?
        var newlyCoarse: [URL] = []
        let decodeStarted = DispatchTime.now().uptimeNanoseconds
        for tile in snapshot where tile.bounds?.contains(latitude: latitude, longitude: longitude) != false {
            if isCancelled() { break }
            let skipCoarse = lock.r2cWithLock { coarseTileURLs.contains(tile.url) }
            if maximumHorizontalResolutionMeters.isFinite, skipCoarse { continue }
            guard let data = loadFileUnlocked(tile.url),
                  let metadata = loadMetadataUnlocked(tile.url, data: data)
            else { continue }
            let resolution = metadata.horizontalResolutionMeters(latitude: latitude)
            if resolution > maximumHorizontalResolutionMeters {
                if maximumHorizontalResolutionMeters.isFinite {
                    newlyCoarse.append(tile.url)
                }
                continue
            }
            if isCancelled() { break }
            guard let value = sample(
                tile: tile, data: data, metadata: metadata,
                latitude: latitude, longitude: longitude,
                isCancelled: isCancelled, pace: pace
            ), value.isFinite, (-500 ... 10_000).contains(value)
            else { continue }
            let candidate = Sample(elevationMeters: value, horizontalResolutionMeters: resolution)
            if best == nil || candidate.horizontalResolutionMeters < best!.horizontalResolutionMeters {
                best = candidate
            }
        }
        let decodeElapsed = Int64(DispatchTime.now().uptimeNanoseconds &- decodeStarted)
        lock.r2cWithLock { timingTileOpenDecodeNs += decodeElapsed }
        if !newlyCoarse.isEmpty {
            lock.r2cWithLock {
                for url in newlyCoarse {
                    coarseTileURLs.insert(url)
                    fileCache.removeValue(forKey: url)
                }
            }
        }
        return best
    }

    public static func tileBounds(fileName: String) -> (south: Double, north: Double, west: Double, east: Double)? {
        let plannedPattern = #"(?i)^R2C_(?:S1M|1M)_(-?[0-9]+)_(-?[0-9]+)_(-?[0-9]+)_(-?[0-9]+)_.*\.tiff?$"#
        if let regex = try? NSRegularExpression(pattern: plannedPattern),
           let match = regex.firstMatch(in: fileName, range: NSRange(fileName.startIndex..., in: fileName)),
           let southRange = Range(match.range(at: 1), in: fileName),
           let northRange = Range(match.range(at: 2), in: fileName),
           let westRange = Range(match.range(at: 3), in: fileName),
           let eastRange = Range(match.range(at: 4), in: fileName),
           let south = Double(fileName[southRange]), let north = Double(fileName[northRange]),
           let west = Double(fileName[westRange]), let east = Double(fileName[eastRange]) {
            return (south / 100_000, north / 100_000, west / 100_000, east / 100_000)
        }
        let pattern = #"(?i).*([ns])([0-9]{2})([ew])([0-9]{3}).*"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: fileName, range: NSRange(fileName.startIndex..., in: fileName)),
              let northSouthRange = Range(match.range(at: 1), in: fileName),
              let latitudeRange = Range(match.range(at: 2), in: fileName),
              let eastWestRange = Range(match.range(at: 3), in: fileName),
              let longitudeRange = Range(match.range(at: 4), in: fileName),
              let latitudeDegrees = Double(fileName[latitudeRange]),
              let longitudeDegrees = Double(fileName[longitudeRange])
        else { return nil }
        let ns = fileName[northSouthRange].lowercased().first!
        let ew = fileName[eastWestRange].lowercased().first!
        let north = ns == "n" ? latitudeDegrees : -latitudeDegrees
        let west = ew == "e" ? longitudeDegrees : -longitudeDegrees
        return (north - 1, north, west, west + 1)
    }

    /// WGS84/NAD83 latitude-longitude to UTM for USGS project-based one-metre DEMs.
    public static func latLonToUTM(
        latitude: Double,
        longitude: Double,
        epsg: Int
    ) -> (x: Double, y: Double)? {
        let zone: Int, northern: Bool, inverseFlattening: Double
        switch epsg {
        case 26901 ... 26923: (zone, northern, inverseFlattening) = (epsg - 26900, true, 298.257222101)
        case 32601 ... 32660: (zone, northern, inverseFlattening) = (epsg - 32600, true, 298.257223563)
        case 32701 ... 32760: (zone, northern, inverseFlattening) = (epsg - 32700, false, 298.257223563)
        default: return nil
        }
        let a = 6_378_137.0, f = 1 / inverseFlattening
        let e2 = f * (2 - f), ep2 = e2 / (1 - e2)
        let phi = latitude * .pi / 180, lambda = longitude * .pi / 180
        let lambda0 = Double(zone * 6 - 183) * .pi / 180
        let n = a / sqrt(1 - e2 * pow(sin(phi), 2))
        let t = pow(tan(phi), 2), c = ep2 * pow(cos(phi), 2), aa = cos(phi) * (lambda - lambda0)
        let m = a * ((1 - e2 / 4 - 3 * pow(e2, 2) / 64 - 5 * pow(e2, 3) / 256) * phi
            - (3 * e2 / 8 + 3 * pow(e2, 2) / 32 + 45 * pow(e2, 3) / 1024) * sin(2 * phi)
            + (15 * pow(e2, 2) / 256 + 45 * pow(e2, 3) / 1024) * sin(4 * phi)
            - (35 * pow(e2, 3) / 3072) * sin(6 * phi))
        let k0 = 0.9996
        let x = 500_000 + k0 * n * (aa + (1 - t + c) * pow(aa, 3) / 6
            + (5 - 18 * t + pow(t, 2) + 72 * c - 58 * ep2) * pow(aa, 5) / 120)
        var y = k0 * (m + n * tan(phi) * (pow(aa, 2) / 2
            + (5 - t + 9 * c + 4 * pow(c, 2)) * pow(aa, 4) / 24
            + (61 - 58 * t + pow(t, 2) + 600 * c - 330 * ep2) * pow(aa, 6) / 720))
        if !northern { y += 10_000_000 }
        return (x, y)
    }

    /// WGS84/NAD83 latitude-longitude to EPSG:6350 NAD83(2011) / Conus Albers.
    public static func latLonToConusAlbers(
        latitude: Double,
        longitude: Double
    ) -> (x: Double, y: Double) {
        let a = 6_378_137.0, f = 1 / 298.257222101
        let e2 = f * (2 - f), e = sqrt(e2)
        func q(_ phi: Double) -> Double {
            let sine = sin(phi)
            return (1 - e2) * (sine / (1 - e2 * sine * sine)
                - log((1 - e * sine) / (1 + e * sine)) / (2 * e))
        }
        func m(_ phi: Double) -> Double {
            cos(phi) / sqrt(1 - e2 * pow(sin(phi), 2))
        }
        let phi0 = 23.0 * .pi / 180, lambda0 = -96.0 * .pi / 180
        let phi1 = 29.5 * .pi / 180, phi2 = 45.5 * .pi / 180
        let m1 = m(phi1), m2 = m(phi2), q1 = q(phi1), q2 = q(phi2)
        let n = (m1 * m1 - m2 * m2) / (q2 - q1)
        let c = m1 * m1 + n * q1
        func rho(_ phi: Double) -> Double { a * sqrt(c - n * q(phi)) / n }
        let radius = rho(latitude * .pi / 180)
        let theta = n * (longitude * .pi / 180 - lambda0)
        return (radius * sin(theta), rho(phi0) - radius * cos(theta))
    }

    private func scheduleWarmCatalog() {
        let shouldStart = lock.r2cWithLock { () -> Bool in
            guard !catalogWarmInFlight else { return false }
            catalogWarmInFlight = true
            return true
        }
        guard shouldStart else { return }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            self?.refreshCatalogFromDisk()
            guard let self else { return }
            let again = self.lock.r2cWithLock { () -> Bool in
                self.catalogWarmInFlight = false
                return !self.catalogReady
                    || self.catalogInvalidationLoaded != self.catalogInvalidations
                    || Date().timeIntervalSince(self.catalogDate) >= 60
            }
            if again { self.scheduleWarmCatalog() }
        }
    }

    /// Directory listing happens with no lock held.
    private func refreshCatalogFromDisk() {
        let invalidation = lock.r2cWithLock { catalogInvalidations }
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]
        )) ?? []
        let built = urls.filter { ["tif", "tiff"].contains($0.pathExtension.lowercased()) }.map { url in
            let value = Self.tileBounds(fileName: url.lastPathComponent)
            return Tile(url: url, bounds: value.map { Bounds(
                minLatitude: $0.south, maxLatitude: $0.north,
                minLongitude: $0.west, maxLongitude: $0.east
            ) })
        }
        lock.r2cWithLock {
            tiles = built
            catalogDate = Date()
            catalogInvalidationLoaded = invalidation
            catalogReady = true
        }
    }

    private func loadFileUnlocked(_ url: URL) -> Data? {
        if let value = lock.r2cWithLock({ fileCache[url] }) { return value }
        guard let value = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        lock.r2cWithLock {
            let limit = maxCachedFiles
            while fileCache.count >= limit, let key = fileCache.keys.first {
                fileCache.removeValue(forKey: key)
            }
            fileCache[url] = value
        }
        return value
    }

    private func loadMetadataUnlocked(_ url: URL, data: Data) -> Metadata? {
        if let value = lock.r2cWithLock({ metadataCache[url] }) { return value }
        guard data.count >= 8 else { return nil }
        let endian: Endian
        if data[0] == 0x49, data[1] == 0x49 { endian = .little }
        else if data[0] == 0x4d, data[1] == 0x4d { endian = .big }
        else { return nil }
        guard u16(data, 2, endian) == 42 else { return nil }
        let ifd = Int(u32(data, 4, endian))
        guard ifd >= 0, ifd + 2 <= data.count else { return nil }
        let count = Int(u16(data, ifd, endian))
        var tags: [Int: TagValue] = [:]
        for index in 0 ..< count {
            let entry = ifd + 2 + index * 12
            guard entry + 12 <= data.count else { break }
            let tag = Int(u16(data, entry, endian))
            let type = Int(u16(data, entry + 2, endian))
            let valueCount = Int(u32(data, entry + 4, endian))
            guard let typeSize = Self.typeSize(type), valueCount >= 0,
                  valueCount <= Int.max / max(1, typeSize) else { continue }
            let byteCount = valueCount * typeSize
            let start = byteCount <= 4 ? entry + 8 : Int(u32(data, entry + 8, endian))
            guard start >= 0, start <= data.count, byteCount <= data.count - start else { continue }
            tags[tag] = decodeTag(data, start: start, type: type, count: valueCount, endian: endian)
        }
        let width = tags[256]?.firstInt ?? 0, height = tags[257]?.firstInt ?? 0
        let tileOffsets = tags[324]?.integers ?? [], tileCounts = tags[325]?.integers ?? []
        let stripOffsets = tags[273]?.integers ?? [], stripCounts = tags[279]?.integers ?? []
        let tileWidth = tags[322]?.firstInt, tileHeight = tags[323]?.firstInt
        let tiled = !tileOffsets.isEmpty && !tileCounts.isEmpty && tileWidth != nil && tileHeight != nil
        let tie = tags[33922]?.doubles ?? [], scale = tags[33550]?.doubles ?? []
        let geoKeys = tags[34735]?.integers ?? []
        var verticalUnit = 9001
        var projectedCSType: Int?
        if geoKeys.count >= 4 {
            for index in 0 ..< geoKeys[3] {
                let base = 4 + index * 4
                guard base + 3 < geoKeys.count else { break }
                if geoKeys[base] == 4099, geoKeys[base + 1] == 0 { verticalUnit = geoKeys[base + 3] }
                if geoKeys[base] == 3072, geoKeys[base + 1] == 0 { projectedCSType = geoKeys[base + 3] }
            }
        }
        let metadata = Metadata(
            endian: endian, width: width, height: height,
            compression: tags[259]?.firstInt ?? 1, predictor: tags[317]?.firstInt ?? 1,
            samplesPerPixel: tags[277]?.firstInt ?? 1, planarConfiguration: tags[284]?.firstInt ?? 1,
            bitsPerSample: tags[258]?.firstInt ?? 16, sampleFormat: tags[339]?.firstInt ?? 1,
            tieI: tie[safe: 0] ?? 0, tieJ: tie[safe: 1] ?? 0,
            tieX: tie[safe: 3] ?? 0, tieY: tie[safe: 4] ?? 0,
            scaleX: scale[safe: 0] ?? 0, scaleY: scale[safe: 1] ?? 0,
            tiled: tiled, tileWidth: tileWidth, tileHeight: tileHeight,
            rowsPerStrip: tags[278]?.firstInt,
            offsets: tiled ? tileOffsets : stripOffsets,
            byteCounts: tiled ? tileCounts : stripCounts,
            noData: tags[42113]?.string.flatMap(Double.init), verticalUnitCode: verticalUnit,
            projectedCSTypeCode: projectedCSType
        )
        guard width > 0, height > 0, metadata.scaleX != 0, metadata.scaleY != 0,
              !metadata.offsets.isEmpty, metadata.offsets.count == metadata.byteCounts.count
        else { return nil }
        lock.r2cWithLock { metadataCache[url] = metadata }
        return metadata
    }

    private func sample(
        tile: Tile, data: Data, metadata: Metadata,
        latitude: Double, longitude: Double,
        isCancelled: @escaping () -> Bool, pace: DemDecodePacer?
    ) -> Double? {
        guard metadata.planarConfiguration == 1 else { return nil }
        guard let model = metadata.modelCoordinate(latitude: latitude, longitude: longitude) else { return nil }
        let column = metadata.tieI + (model.x - metadata.tieX) / metadata.scaleX
        let row = metadata.tieJ + (metadata.tieY - model.y) / metadata.scaleY
        guard column >= 0, row >= 0, column <= Double(metadata.width - 1), row <= Double(metadata.height - 1) else { return nil }
        let c0 = min(metadata.width - 1, Int(floor(column))), r0 = min(metadata.height - 1, Int(floor(row)))
        let c1 = min(metadata.width - 1, c0 + 1), r1 = min(metadata.height - 1, r0 + 1)
        guard let s00 = samplePixel(tile: tile, data: data, metadata: metadata, column: c0, row: r0, isCancelled: isCancelled, pace: pace),
              let s10 = samplePixel(tile: tile, data: data, metadata: metadata, column: c1, row: r0, isCancelled: isCancelled, pace: pace),
              let s01 = samplePixel(tile: tile, data: data, metadata: metadata, column: c0, row: r1, isCancelled: isCancelled, pace: pace),
              let s11 = samplePixel(tile: tile, data: data, metadata: metadata, column: c1, row: r1, isCancelled: isCancelled, pace: pace),
              ![s00, s10, s01, s11].contains(where: { isNoData($0, metadata.noData) })
        else { return nil }
        let dx = column - Double(c0), dy = row - Double(r0)
        let top = s00 + (s10 - s00) * dx, bottom = s01 + (s11 - s01) * dx
        return top + (bottom - top) * dy
    }

    private func samplePixel(
        tile: Tile, data: Data, metadata: Metadata, column: Int, row: Int,
        isCancelled: @escaping () -> Bool, pace: DemDecodePacer?
    ) -> Double? {
        let blockIndex: Int, blockWidth: Int, blockHeight: Int, localColumn: Int, localRow: Int
        if metadata.tiled {
            guard let width = metadata.tileWidth, let height = metadata.tileHeight else { return nil }
            let columns = Int(ceil(Double(metadata.width) / Double(width)))
            blockIndex = (row / height) * columns + column / width
            blockWidth = width; blockHeight = height; localColumn = column % width; localRow = row % height
        } else {
            guard let rows = metadata.rowsPerStrip, rows > 0 else { return nil }
            blockIndex = row / rows; blockWidth = metadata.width
            blockHeight = min(rows, metadata.height - blockIndex * rows)
            localColumn = column; localRow = row % rows
        }
        guard metadata.offsets.indices.contains(blockIndex), metadata.byteCounts.indices.contains(blockIndex) else { return nil }
        let cacheKey = "\(tile.url.path)|\(blockIndex)"
        if let cached = lock.r2cWithLock({ blockCache[cacheKey] }) {
            return extractSample(block: cached, metadata: metadata, blockWidth: blockWidth,
                                 localColumn: localColumn, localRow: localRow)
        }
        let expected = blockWidth * blockHeight * metadata.samplesPerPixel * max(1, metadata.bitsPerSample / 8)
        if let persisted = readPersistedBlock(tile: tile.url, blockIndex: blockIndex, expectedSize: expected) {
            storeDecodedBlock(persisted, for: cacheKey)
            return extractSample(block: persisted, metadata: metadata, blockWidth: blockWidth,
                                 localColumn: localColumn, localRow: localRow)
        }
        if isCancelled() { return nil }
        let offset = metadata.offsets[blockIndex], count = metadata.byteCounts[blockIndex]
        guard offset >= 0, count > 0, offset <= data.count, count <= data.count - offset else { return nil }
        let raw = data.subdata(in: offset ..< offset + count)
        guard let decoded = decodeBlock(
            raw, metadata: metadata, width: blockWidth, height: blockHeight,
            isCancelled: isCancelled, pace: pace
        ) else { return nil }
        storeDecodedBlock(decoded, for: cacheKey)
        persistDecodedBlock(decoded, tile: tile.url, blockIndex: blockIndex)
        return extractSample(block: decoded, metadata: metadata, blockWidth: blockWidth,
                             localColumn: localColumn, localRow: localRow)
    }

    private func storeDecodedBlock(_ decoded: Data, for cacheKey: String) {
        lock.r2cWithLock {
            blockCache[cacheKey] = decoded
            blockOrder.append(cacheKey)
            let limit = maxCachedBlocks
            while blockOrder.count > limit {
                blockCache.removeValue(forKey: blockOrder.removeFirst())
            }
        }
    }

    private func extractSample(
        block: Data, metadata: Metadata, blockWidth: Int, localColumn: Int, localRow: Int
    ) -> Double? {
        let bytesPerSample = metadata.bitsPerSample / 8
        let offset = ((localRow * blockWidth) + localColumn) * bytesPerSample * metadata.samplesPerPixel
        guard bytesPerSample > 0, offset + bytesPerSample <= block.count else { return nil }
        let value: Double?
        switch (metadata.sampleFormat, metadata.bitsPerSample) {
        case (3, 32): value = Double(Float(bitPattern: u32(block, offset, metadata.endian)))
        case (3, 64): value = Double(bitPattern: u64(block, offset, metadata.endian))
        case (2, 16): value = Double(Int16(bitPattern: u16(block, offset, metadata.endian)))
        case (2, 32): value = Double(Int32(bitPattern: u32(block, offset, metadata.endian)))
        case (_, 16): value = Double(u16(block, offset, metadata.endian))
        case (_, 32): value = Double(u32(block, offset, metadata.endian))
        default: value = nil
        }
        return (metadata.verticalUnitCode == 9002 || metadata.verticalUnitCode == 9003) ? value.map { $0 * 0.3048 } : value
    }

    private func decodeBlock(
        _ raw: Data, metadata: Metadata, width: Int, height: Int,
        isCancelled: @escaping () -> Bool, pace: DemDecodePacer?
    ) -> Data? {
        if isCancelled() { return nil }
        pace?.beginSlice()
        lock.r2cWithLock { blockDecodeCount += 1 }
        let expected = width * height * metadata.samplesPerPixel * max(1, metadata.bitsPerSample / 8)
        var decoded: Data?
        switch metadata.compression {
        case 1: decoded = raw
        case 5: decoded = lzwDecode(raw, expectedSize: expected, isCancelled: isCancelled, pace: pace)
        case 8, 32946: decoded = compressionDecode(raw, expectedSize: expected, algorithm: COMPRESSION_ZLIB)
        case 32773: decoded = packBitsDecode(raw, expectedSize: expected)
        default: return nil
        }
        guard !isCancelled(), var bytes = decoded.map({ Array($0) }), bytes.count >= expected else { return nil }
        if metadata.predictor == 2 {
            guard applyHorizontalPredictor2(&bytes, metadata: metadata, width: width, height: height) else { return nil }
        } else if metadata.predictor == 3 {
            guard applyFloatPredictor3(&bytes, metadata: metadata, width: width, height: height) else { return nil }
        } else if metadata.predictor > 1 { return nil }
        if isCancelled() { return nil }
        pace?.pauseForWorkSinceLastTick()
        return Data(bytes)
    }

    /// Predictor-applied block files. Names include the source length so a replaced download
    /// does not reuse the previous decode. Blocks larger than 8 MB stay memory-only.
    private func persistedBlockURL(tile: URL, blockIndex: Int) -> URL? {
        guard let root = decodedBlockCacheDirectory else { return nil }
        let size = tileByteCount(tile)
        guard size > 0 else { return nil }
        let safe = String(tile.lastPathComponent.map { character -> Character in
            (character.isLetter || character.isNumber || character == "." || character == "_" || character == "-")
                ? character : "_"
        })
        return root
            .appendingPathComponent(safe, isDirectory: true)
            .appendingPathComponent(String(size), isDirectory: true)
            .appendingPathComponent("\(blockIndex).bin")
    }

    private func tileByteCount(_ tile: URL) -> Int {
        if let known = lock.r2cWithLock({ tileFileSizes[tile] }) { return known }
        let size = (try? tile.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0 else { return 0 }
        lock.r2cWithLock { tileFileSizes[tile] = size }
        return size
    }

    private func readPersistedBlock(tile: URL, blockIndex: Int, expectedSize: Int) -> Data? {
        guard expectedSize > 0, expectedSize <= 8_388_608,
              let url = persistedBlockURL(tile: tile, blockIndex: blockIndex),
              let data = try? Data(contentsOf: url), data.count == expectedSize
        else { return nil }
        return data
    }

    private func persistDecodedBlock(_ decoded: Data, tile: URL, blockIndex: Int) {
        guard decoded.count > 0, decoded.count <= 8_388_608,
              let url = persistedBlockURL(tile: tile, blockIndex: blockIndex)
        else { return }
        let handler = onDecodedBlockPersisted
        let bytes = decoded
        persistQueue.async {
            do {
                try FileManager.default.createDirectory(
                    at: url.deletingLastPathComponent(), withIntermediateDirectories: true
                )
                try bytes.write(to: url, options: .atomic)
                handler?(url)
            } catch {
                // A failed cache write still leaves the in-memory block. The next sample can decode again.
            }
        }
    }

    private func compressionDecode(_ input: Data, expectedSize: Int, algorithm: compression_algorithm) -> Data? {
        var output = [UInt8](repeating: 0, count: expectedSize)
        let count = input.withUnsafeBytes { source in
            output.withUnsafeMutableBytes { destination in
                compression_decode_buffer(
                    destination.bindMemory(to: UInt8.self).baseAddress!, expectedSize,
                    source.bindMemory(to: UInt8.self).baseAddress!, input.count,
                    nil, algorithm
                )
            }
        }
        return count > 0 ? Data(output.prefix(count)) : nil
    }

    private func packBitsDecode(_ input: Data, expectedSize: Int) -> Data? {
        let bytes = Array(input); var output: [UInt8] = []; output.reserveCapacity(expectedSize); var index = 0
        while index < bytes.count, output.count < expectedSize {
            let header = Int(Int8(bitPattern: bytes[index])); index += 1
            if header >= 0 {
                let count = header + 1; guard index + count <= bytes.count else { return nil }
                output.append(contentsOf: bytes[index ..< index + count]); index += count
            } else if header >= -127 {
                let count = 1 - header; guard index < bytes.count else { return nil }
                output.append(contentsOf: repeatElement(bytes[index], count: count)); index += 1
            }
        }
        return output.count >= expectedSize ? Data(output.prefix(expectedSize)) : nil
    }

    /// TIFF LZW, MSB-first, Clear=256, EOI=257. Code width grows when the next code
    /// reaches 511, 1023, or 2047 — the same schedule as the previous bit-at-a-time decoder.
    /// Codes are a prefix index plus one suffix byte, read through a byte buffer, so a
    /// 512×512 S1M block does not execute one Swift loop per compressed bit.
    private func lzwDecode(
        _ input: Data, expectedSize: Int, isCancelled: @escaping () -> Bool, pace: DemDecodePacer?
    ) -> Data? {
        guard expectedSize > 0, !input.isEmpty else { return nil }
        return input.withUnsafeBytes { raw -> Data? in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return nil }
            let byteCount = raw.count
            var byteIndex = 0
            var accumulator: UInt64 = 0
            var accumulatorBits = 0
            func readCode(_ width: Int) -> Int? {
                while accumulatorBits < width {
                    if byteIndex >= byteCount { return nil }
                    accumulator = (accumulator << 8) | UInt64(base[byteIndex])
                    byteIndex += 1
                    accumulatorBits += 8
                }
                accumulatorBits -= width
                let codeMask = UInt64((1 << width) - 1)
                let code = Int((accumulator >> UInt64(accumulatorBits)) & codeMask)
                if accumulatorBits == 0 {
                    accumulator = 0
                } else {
                    accumulator &= (1 << UInt64(accumulatorBits)) - 1
                }
                return code
            }

            var prefix = [Int](repeating: -1, count: 4096)
            var suffix = [UInt8](repeating: 0, count: 4096)
            var firstByte = [UInt8](repeating: 0, count: 4096)
            var length = [Int](repeating: 0, count: 4096)
            for index in 0 ..< 256 {
                suffix[index] = UInt8(index)
                firstByte[index] = UInt8(index)
                length[index] = 1
            }
            var output = [UInt8](repeating: 0, count: expectedSize)
            var outCount = 0
            var scratch = [UInt8]()
            let paceEvery = 262_144
            var nextPaceAt = paceEvery
            func emit(_ code: Int) -> Bool {
                let codeLength = length[code]
                guard code >= 0, code < 4096, codeLength > 0 else { return false }
                if scratch.count < codeLength {
                    scratch = [UInt8](repeating: 0, count: codeLength)
                }
                var current = code
                for index in stride(from: codeLength - 1, through: 0, by: -1) {
                    scratch[index] = suffix[current]
                    current = prefix[current]
                    if current < 0, index != 0 { return false }
                }
                let room = expectedSize - outCount
                guard room > 0 else { return true }
                let count = min(codeLength, room)
                for index in 0 ..< count { output[outCount + index] = scratch[index] }
                outCount += count
                if outCount >= nextPaceAt {
                    nextPaceAt = outCount + paceEvery
                    if isCancelled() { return false }
                    pace?.pauseForWorkSinceLastTick()
                    if isCancelled() { return false }
                }
                return true
            }

            var codeWidth = 9
            var nextCode = 258
            var previous = -1
            guard var code = readCode(codeWidth) else {
                return nil
            }
            while true {
                if code == 256 {
                    codeWidth = 9
                    nextCode = 258
                    previous = -1
                    guard let next = readCode(codeWidth) else { break }
                    code = next
                    continue
                }
                if code == 257 { break }
                let resolved: Int
                let entryFirst: UInt8
                if code < 256 || (code < nextCode && code < 4096 && length[code] > 0) {
                    resolved = code
                    entryFirst = firstByte[code]
                } else if code == nextCode, previous >= 0, nextCode < 4096 {
                    resolved = -1
                    entryFirst = firstByte[previous]
                } else {
                    return nil
                }
                if resolved >= 0 {
                    guard emit(resolved) else { return nil }
                } else {
                    guard emit(previous) else { return nil }
                    if outCount < expectedSize {
                        output[outCount] = entryFirst
                        outCount += 1
                    }
                }
                if previous >= 0, nextCode < 4096 {
                    prefix[nextCode] = previous
                    suffix[nextCode] = entryFirst
                    firstByte[nextCode] = firstByte[previous]
                    length[nextCode] = length[previous] + 1
                    nextCode += 1
                    if codeWidth < 12, nextCode == (1 << codeWidth) - 1 { codeWidth += 1 }
                    previous = resolved >= 0 ? resolved : nextCode - 1
                } else {
                    previous = resolved
                }
                if outCount >= expectedSize { break }
                guard let next = readCode(codeWidth) else { break }
                code = next
            }
            guard outCount >= expectedSize else { return nil }
            return Data(output)
        }
    }

    private func applyHorizontalPredictor2(_ bytes: inout [UInt8], metadata: Metadata, width: Int, height: Int) -> Bool {
        let sampleBytes = metadata.bitsPerSample / 8
        guard sampleBytes == 1 || sampleBytes == 2 || sampleBytes == 4 else { return false }
        let pixelBytes = sampleBytes * metadata.samplesPerPixel, rowBytes = width * pixelBytes
        guard bytes.count >= rowBytes * height else { return false }
        for row in 0 ..< height {
            for column in 1 ..< width {
                for band in 0 ..< metadata.samplesPerPixel {
                    let current = row * rowBytes + column * pixelBytes + band * sampleBytes
                    let prior = current - pixelBytes
                    var carry = 0
                    if metadata.endian == .little {
                        for byte in 0 ..< sampleBytes {
                            let sum = Int(bytes[current + byte]) + Int(bytes[prior + byte]) + carry
                            bytes[current + byte] = UInt8(truncatingIfNeeded: sum); carry = sum >> 8
                        }
                    } else {
                        for byte in (0 ..< sampleBytes).reversed() {
                            let sum = Int(bytes[current + byte]) + Int(bytes[prior + byte]) + carry
                            bytes[current + byte] = UInt8(truncatingIfNeeded: sum); carry = sum >> 8
                        }
                    }
                }
            }
        }
        return true
    }

    private func applyFloatPredictor3(_ bytes: inout [UInt8], metadata: Metadata, width: Int, height: Int) -> Bool {
        let sampleBytes = metadata.bitsPerSample / 8
        guard sampleBytes == 4 || sampleBytes == 8 else { return false }
        let samplesPerRow = width * metadata.samplesPerPixel, rowBytes = samplesPerRow * sampleBytes
        guard bytes.count >= rowBytes * height else { return false }
        for row in 0 ..< height {
            let rowBase = row * rowBytes
            for index in 1 ..< rowBytes { bytes[rowBase + index] &+= bytes[rowBase + index - 1] }
            let source = Array(bytes[rowBase ..< rowBase + rowBytes])
            for sample in 0 ..< samplesPerRow {
                for plane in 0 ..< sampleBytes {
                    let bytePosition = metadata.endian == .big ? plane : sampleBytes - 1 - plane
                    bytes[rowBase + sample * sampleBytes + bytePosition] = source[plane * samplesPerRow + sample]
                }
            }
        }
        return true
    }

    private enum TagValue {
        case integers([Int]), doubles([Double]), string(String)
        var integers: [Int] { if case let .integers(value) = self { value } else { [] } }
        var doubles: [Double] {
            switch self { case let .doubles(value): value; case let .integers(value): value.map(Double.init); case .string: [] }
        }
        var string: String? { if case let .string(value) = self { value } else { nil } }
        var firstInt: Int? { integers.first }
    }

    private func decodeTag(_ data: Data, start: Int, type: Int, count: Int, endian: Endian) -> TagValue {
        switch type {
        case 2:
            return .string(String(decoding: data[start ..< start + count], as: UTF8.self).trimmingCharacters(in: CharacterSet(charactersIn: "\0 ")))
        case 3: return .integers((0 ..< count).map { Int(u16(data, start + $0 * 2, endian)) })
        case 4: return .integers((0 ..< count).map { Int(u32(data, start + $0 * 4, endian)) })
        case 8: return .integers((0 ..< count).map { Int(Int16(bitPattern: u16(data, start + $0 * 2, endian))) })
        case 9: return .integers((0 ..< count).map { Int(Int32(bitPattern: u32(data, start + $0 * 4, endian))) })
        case 11: return .doubles((0 ..< count).map { Double(Float(bitPattern: u32(data, start + $0 * 4, endian))) })
        case 12: return .doubles((0 ..< count).map { Double(bitPattern: u64(data, start + $0 * 8, endian)) })
        default: return .integers([])
        }
    }

    private static func typeSize(_ type: Int) -> Int? {
        switch type { case 1, 2, 6, 7: 1; case 3, 8: 2; case 4, 9, 11: 4; case 5, 10, 12: 8; default: nil }
    }

    private func isNoData(_ value: Double, _ noData: Double?) -> Bool { noData.map { abs(value - $0) < 0.001 } ?? false }
}

private func u16(_ data: Data, _ offset: Int, _ endian: GeoTiffElevationSource.Endian) -> UInt16 {
    let a = UInt16(data[offset]), b = UInt16(data[offset + 1])
    return endian == .little ? a | b << 8 : a << 8 | b
}

private func u32(_ data: Data, _ offset: Int, _ endian: GeoTiffElevationSource.Endian) -> UInt32 {
    let values = (0 ..< 4).map { UInt32(data[offset + $0]) }
    return endian == .little
        ? values[0] | values[1] << 8 | values[2] << 16 | values[3] << 24
        : values[0] << 24 | values[1] << 16 | values[2] << 8 | values[3]
}

private func u64(_ data: Data, _ offset: Int, _ endian: GeoTiffElevationSource.Endian) -> UInt64 {
    if endian == .little { return UInt64(u32(data, offset, endian)) | UInt64(u32(data, offset + 4, endian)) << 32 }
    return UInt64(u32(data, offset, endian)) << 32 | UInt64(u32(data, offset + 4, endian))
}

private extension NSLock {
    func r2cWithLock<T>(_ work: () -> T) -> T { lock(); defer { unlock() }; return work() }
}

/// Spreads DEM block decode so a warm cannot hold a core without a break.
/// Sleeps 1.5× the work just done (about a 40% duty cycle), capped per slice.
private final class DemDecodePacer: @unchecked Sendable {
    private var sliceStart = DispatchTime.now().uptimeNanoseconds

    func beginSlice() {
        sliceStart = DispatchTime.now().uptimeNanoseconds
    }

    func pauseForWorkSinceLastTick() {
        let now = DispatchTime.now().uptimeNanoseconds
        let elapsed = now > sliceStart ? Double(now &- sliceStart) / 1_000_000_000 : 0
        sliceStart = now
        let pause = min(2.0, elapsed * 1.5)
        if pause >= 0.004 {
            Thread.sleep(forTimeInterval: pause)
        }
        sliceStart = DispatchTime.now().uptimeNanoseconds
    }
}

private extension Array {
    subscript(safe index: Index) -> Element? { indices.contains(index) ? self[index] : nil }
}
