import Foundation
import R2CCore

actor AppleTerrainElevationService {
    private struct PendingPrefetch {
        let cell: String
        let latitude: Double
        let longitude: Double
        let radiusMeters: Double
    }

    private struct CacheEntry: Codable {
        let elevationMeters: Double
        let fetchedAt: Date
    }

    private let session: URLSession
    private let cacheDirectory: URL
    private let localDEM: GeoTiffElevationSource
    private var budgetGeneration = -1
    private let maximumFreshAge: TimeInterval = 365 * 24 * 60 * 60
    private var scheduledPrefetchCells = Set<String>()
    private var pendingPrefetchCoordinates: [PendingPrefetch] = []
    private var prefetchFailureCounts: [String: Int] = [:]
    private var prefetchRetryAfter: [String: Date] = [:]
    private var prefetchWorkerRunning = false

    init(session: URLSession = URLSession(configuration: .ephemeral)) {
        self.session = session
        let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        // Version 2 preserves the dynamic service's best-available resolution instead of
        // reusing legacy EPQS values quantized into approximately 30 m cells.
        cacheDirectory = root.appendingPathComponent("RID2Caltopo/TerrainV2", isDirectory: true)
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        localDEM = GeoTiffElevationSource(directory: support.appendingPathComponent("RID2Caltopo/DEM", isDirectory: true))
    }

    func sample(latitude: Double, longitude: Double) async -> OperationalTerrainSample? {
        guard latitude.isFinite, longitude.isFinite,
              (-90 ... 90).contains(latitude), (-180 ... 180).contains(longitude)
        else { return nil }
        if budgetGeneration != AppleUnifiedMapCache.generation {
            localDEM.invalidateCatalog()
            budgetGeneration = AppleUnifiedMapCache.generation
        }
        let coordinate = OperationalAltitudeCoordinator.Coordinate(latitude: latitude, longitude: longitude)
        let key = OperationalAltitudeCoordinator.terrainCacheKey(coordinate)
        prefetch(latitude: latitude, longitude: longitude)
        let localDEM = self.localDEM
        if let localSample = await Task.detached(priority: .utility, operation: {
            localDEM.sample(latitude: latitude, longitude: longitude)
        }).value {
            return OperationalTerrainSample(
                elevationMeters: localSample.elevationMeters,
                source: "usgs-geotiff-local-\(Int(localSample.horizontalResolutionMeters.rounded()))m",
                horizontalResolutionMeters: localSample.horizontalResolutionMeters
            )
        }
        let cached = load(key: key)
        if let cached, Date().timeIntervalSince(cached.fetchedAt) <= maximumFreshAge {
            return OperationalTerrainSample(elevationMeters: cached.elevationMeters)
        }

        do {
            var components = URLComponents(string: "https://epqs.nationalmap.gov/v1/json")!
            components.queryItems = [
                URLQueryItem(name: "x", value: String(longitude)),
                URLQueryItem(name: "y", value: String(latitude)),
                URLQueryItem(name: "units", value: "Meters"),
                URLQueryItem(name: "wkid", value: "4326"),
            ]
            var request = URLRequest(url: components.url!)
            request.timeoutInterval = 6
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let value = Self.elevation(from: object), value.isFinite,
                  (-500 ... 10_000).contains(value)
            else { throw URLError(.badServerResponse) }
            save(CacheEntry(elevationMeters: value, fetchedAt: Date()), key: key)
            return OperationalTerrainSample(elevationMeters: value)
        } catch {
            if let cached {
                return OperationalTerrainSample(elevationMeters: cached.elevationMeters, stale: true)
            }
            AppleLog.warning("Terrain", "DEM lookup unavailable")
            return nil
        }
    }

    func prefetch(latitude: Double, longitude: Double, radiusMeters: Double = 0) {
        guard latitude.isFinite, longitude.isFinite,
              (-90 ... 90).contains(latitude), (-180 ... 180).contains(longitude)
        else { return }
        schedulePrefetch(latitude: latitude, longitude: longitude, radiusMeters: radiusMeters)
    }

    private func schedulePrefetch(latitude: Double, longitude: Double, radiusMeters: Double) {
        let cell = OperationalTerrainPrefetch.cellKey(latitude: latitude, longitude: longitude) + ":\(radiusMeters)"
        if let retryAfter = prefetchRetryAfter[cell], retryAfter > Date() { return }
        guard scheduledPrefetchCells.insert(cell).inserted else { return }
        pendingPrefetchCoordinates.append(.init(cell: cell, latitude: latitude, longitude: longitude, radiusMeters: radiusMeters))
        guard !prefetchWorkerRunning else { return }
        prefetchWorkerRunning = true
        Task(priority: .utility) { [weak self] in
            await self?.drainPrefetchQueue()
        }
    }

    private func drainPrefetchQueue() async {
        while !pendingPrefetchCoordinates.isEmpty {
            let coordinate = pendingPrefetchCoordinates.removeFirst()
            let localDEM = self.localDEM
            // Warm persisted terrain before waiting for the catalog or a transfer.
            let localSample = await Task.detached(priority: .utility) {
                localDEM.sample(latitude: coordinate.latitude, longitude: coordinate.longitude)
            }.value
            // A ray-sample/aircraft request need not query the catalog when S1M is already local.
            // Device-area requests must still check neighboring tiles for the full radius.
            let localS1mReady = coordinate.radiusMeters == 0 &&
                (localSample?.horizontalResolutionMeters ?? .infinity) <= 1.5
            let complete = localS1mReady ? true : await prefetchBestDEM(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude,
                radiusMeters: coordinate.radiusMeters
            )
            if complete {
                prefetchFailureCounts[coordinate.cell] = nil
                prefetchRetryAfter[coordinate.cell] = nil
            } else {
                scheduledPrefetchCells.remove(coordinate.cell)
                let failures = (prefetchFailureCounts[coordinate.cell] ?? 0) + 1
                prefetchFailureCounts[coordinate.cell] = failures
                prefetchRetryAfter[coordinate.cell] = Date().addingTimeInterval(
                    OperationalCacheRetryPolicy.prefetchDelaySeconds(failureCount: failures)
                )
            }
        }
        prefetchWorkerRunning = false
    }

    private func prefetchBestDEM(latitude: Double, longitude: Double, radiusMeters: Double) async -> Bool {
        let bounds = OperationalTerrainPrefetch.bounds(
            latitude: latitude, longitude: longitude, radiusMeters: radiusMeters
        )
        var complete = true
        do {
            let downloads = try await resolveS1M(latitude: latitude, longitude: longitude, bounds: bounds)
            if radiusMeters > 0 {
                AppleUnifiedMapCache.protect(downloads.map(\.fileName) +
                    OperationalOfflineMapPlanner.demTileNames(bounds: bounds).map { "USGS_1_\($0).tif" })
            }
            AppleLog.info("TerrainPrefetch", "S1M preparation tiles=\(downloads.count) operatingRadiusM=\(radiusMeters)")
            if downloads.isEmpty {
                AppleLog.info("TerrainPrefetch", "S1M unavailable in catalog; using terrain fallback")
            }
            for download in downloads {
                do {
                    try await downloadDEM((download.url, download.fileName, download.expectedBytes), bounds: bounds)
                } catch is CancellationError { return false }
                catch { complete = false }
            }
        } catch is CancellationError { return false }
        catch {
            complete = false
            AppleLog.warning("TerrainPrefetch", "S1M preparation failed; will retry")
        }
        for tile in OperationalOfflineMapPlanner.demTileNames(bounds: bounds) {
            let fileName = "USGS_1_\(tile).tif"
            guard let url = URL(string: "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/1/TIFF/current/\(tile)/\(fileName)") else { return false }
            do { try await downloadDEM((url, fileName, nil)) }
            catch is CancellationError { return false }
            catch { complete = false }
        }
        _ = localDEM.sample(latitude: latitude, longitude: longitude)
        AppleLog.info("TerrainPrefetch", "Terrain preparation complete=\(complete)")
        return complete
    }

    private func resolveS1M(latitude: Double, longitude: Double, bounds: OperationalMapBounds) async throws
        -> [OperationalS1MProduct]
    {
        var downloads: [URL: OperationalS1MProduct] = [:]
        var priority = Set<URL>()
        var offset = 0
        while true {
            var components = URLComponents(string: "https://tnmaccess.nationalmap.gov/api/v1/products")!
            components.queryItems = [
                .init(name: "bbox", value: "\(bounds.west),\(bounds.south),\(bounds.east),\(bounds.north)"),
                .init(name: "prodFormats", value: "GeoTIFF"),
                .init(name: "outputFormat", value: "JSON"),
                .init(name: "datasets", value: OperationalS1MCatalog.datasetName),
                .init(name: "max", value: "100"),
                .init(name: "offset", value: String(offset)),
            ]
            var request = URLRequest(url: components.url!)
            request.timeoutInterval = 15
            let data = try await AppleCacheHTTPClient.data(for: request, session: session)
            guard let page = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let items = page["items"] as? [[String: Any]]
            else { throw URLError(.cannotParseResponse) }
            for product in try OperationalS1MCatalog.products(data: data, intersecting: bounds) {
                downloads[product.url] = product
            }
            priority.formUnion(try OperationalS1MCatalog.products(data: data, containing: (latitude, longitude)).map(\.url))
            offset += items.count
            let total = (page["total"] as? NSNumber)?.intValue ?? offset
            if offset >= total { break }
            guard !items.isEmpty, offset < 2_000 else { throw URLError(.cannotParseResponse) }
        }
        return downloads.values.sorted {
            if priority.contains($0.url) != priority.contains($1.url) { return priority.contains($0.url) }
            return $0.fileName < $1.fileName
        }
    }

    private func downloadDEM(_ download: (url: URL, fileName: String, expectedBytes: Int64?), bounds: OperationalMapBounds? = nil) async throws {
        _ = try await AppleDEMDownloadCoordinator.shared.ensureDEM(
            url: download.url,
            fileName: download.fileName,
            expectedBytes: download.expectedBytes,
            bounds: bounds,
            session: session
        )
        localDEM.invalidateCatalog()
    }

    private static func geographicTileName(latitude: Double, longitude: Double) -> String {
        let north = Int(floor(latitude)) + 1
        let longitudeBlock = Int(floor(longitude))
        let latitudePart = north >= 0 ? String(format: "n%02d", north) : String(format: "s%02d", -north)
        let longitudePart = longitudeBlock < 0
            ? String(format: "w%03d", -longitudeBlock)
            : String(format: "e%03d", longitudeBlock + 1)
        return latitudePart + longitudePart
    }

    private static func elevation(from object: [String: Any]) -> Double? {
        if let value = object["value"] as? NSNumber { return value.doubleValue }
        if let value = object["value"] as? String { return Double(value) }
        if let usgs = object["USGS_Elevation_Point_Query_Service"] as? [String: Any],
           let query = usgs["Elevation_Query"] as? [String: Any] {
            if let value = query["Elevation"] as? NSNumber { return value.doubleValue }
            if let value = query["Elevation"] as? String { return Double(value) }
        }
        return nil
    }

    private func url(for key: String) -> URL {
        cacheDirectory.appendingPathComponent(key.replacingOccurrences(of: "|", with: "_") + ".json")
    }

    private func load(key: String) -> CacheEntry? {
        guard let data = try? Data(contentsOf: url(for: key)) else { return nil }
        return try? JSONDecoder().decode(CacheEntry.self, from: data)
    }

    private func save(_ entry: CacheEntry, key: String) {
        do {
            try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
            _ = try AppleMapCacheAccess.write(JSONEncoder().encode(entry), to: url(for: key))
        } catch {
            AppleLog.warning("Terrain", "DEM cache write failed: \(error.localizedDescription)")
        }
    }
}
