import Foundation
import R2CCore

actor AppleTerrainElevationService {
    private struct PendingPrefetch {
        let cell: String
        let latitude: Double
        let longitude: Double
    }

    private struct CacheEntry: Codable {
        let elevationMeters: Double
        let fetchedAt: Date
    }

    private let session: URLSession
    private let cacheDirectory: URL
    private let localDEM: GeoTiffElevationSource
    private let maximumFreshAge: TimeInterval = 365 * 24 * 60 * 60
    private var scheduledPrefetchCells = Set<String>()
    private var pendingPrefetchCoordinates: [PendingPrefetch] = []
    private var prefetchFailureCounts: [String: Int] = [:]
    private var prefetchRetryAfter: [String: Date] = [:]
    private var prefetchWorkerRunning = false

    init(session: URLSession = .shared) {
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

    func prefetch(latitude: Double, longitude: Double) {
        guard latitude.isFinite, longitude.isFinite,
              (-90 ... 90).contains(latitude), (-180 ... 180).contains(longitude)
        else { return }
        schedulePrefetch(latitude: latitude, longitude: longitude)
    }

    private func schedulePrefetch(latitude: Double, longitude: Double) {
        let cell = "\(Int(floor(latitude * 20))):\(Int(floor(longitude * 20)))"
        if let retryAfter = prefetchRetryAfter[cell], retryAfter > Date() { return }
        guard scheduledPrefetchCells.insert(cell).inserted else { return }
        pendingPrefetchCoordinates.append(.init(cell: cell, latitude: latitude, longitude: longitude))
        guard !prefetchWorkerRunning else { return }
        prefetchWorkerRunning = true
        Task(priority: .utility) { [weak self] in
            await self?.drainPrefetchQueue()
        }
    }

    private func drainPrefetchQueue() async {
        while !pendingPrefetchCoordinates.isEmpty {
            let coordinate = pendingPrefetchCoordinates.removeFirst()
            let complete = await prefetchBestDEM(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude
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

    private func prefetchBestDEM(latitude: Double, longitude: Double) async -> Bool {
        var s1mReady = true
        do {
            if let download = try await resolveS1M(latitude: latitude, longitude: longitude) {
                try await downloadDEM(download)
            }
        } catch is CancellationError {
            return false
        } catch {
            s1mReady = false
            AppleLog.warning("Terrain", "S1M prefetch unavailable; retaining terrain fallback")
        }
        do {
            let tile = Self.geographicTileName(latitude: latitude, longitude: longitude)
            let fileName = "USGS_1_\(tile).tif"
            guard let url = URL(string: "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/1/TIFF/current/\(tile)/\(fileName)") else { return false }
            try await downloadDEM((url, fileName, nil))
            return s1mReady
        } catch is CancellationError {
            return false
        } catch {
            AppleLog.warning("Terrain", "DEM fallback prefetch unavailable")
            return false
        }
    }

    private func resolveS1M(latitude: Double, longitude: Double) async throws
        -> (url: URL, fileName: String, expectedBytes: Int64?)?
    {
        let delta = 0.0001
        var components = URLComponents(string: "https://tnmaccess.nationalmap.gov/api/v1/products")!
        components.queryItems = [
            .init(name: "bbox", value: "\(longitude - delta),\(latitude - delta),\(longitude + delta),\(latitude + delta)"),
            .init(name: "prodFormats", value: "GeoTIFF"),
            .init(name: "outputFormat", value: "JSON"),
            .init(name: "datasets", value: OperationalS1MCatalog.datasetName),
            .init(name: "max", value: "20"),
        ]
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 15
        let data = try await AppleCacheHTTPClient.data(for: request, session: session)
        return try OperationalS1MCatalog.products(
            data: data,
            containing: (latitude, longitude)
        ).first.map { ($0.url, $0.fileName, $0.expectedBytes) }
    }

    private func downloadDEM(_ download: (url: URL, fileName: String, expectedBytes: Int64?)) async throws {
        _ = try await AppleDEMDownloadCoordinator.shared.ensureDEM(
            url: download.url,
            fileName: download.fileName,
            expectedBytes: download.expectedBytes,
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
            try JSONEncoder().encode(entry).write(to: url(for: key), options: .atomic)
        } catch {
            AppleLog.warning("Terrain", "DEM cache write failed: \(error.localizedDescription)")
        }
    }
}
