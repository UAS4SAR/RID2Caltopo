import CryptoKit
import Foundation
import MapKit
import R2CCore
import SwiftUI
import UIKit

enum AppleMapCachePaths {
    private static let durableRoot = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("RID2Caltopo/MapTiles", isDirectory: true)
    }()

    private static let legacyRoot = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("RID2Caltopo/MapTiles", isDirectory: true)
    }()

    private static let prepareStorage: Void = {
        let fileManager = FileManager.default
        let destination = durableRoot
        do {
            try fileManager.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            if fileManager.fileExists(atPath: legacyRoot.path),
               !fileManager.fileExists(atPath: destination.path) {
                try fileManager.moveItem(at: legacyRoot, to: destination)
            } else {
                try fileManager.createDirectory(at: destination, withIntermediateDirectories: true)
                if fileManager.fileExists(atPath: legacyRoot.path),
                   UserDefaults.standard.bool(forKey: "map.durableTileCacheMigrationV1") == false,
                   let enumerator = fileManager.enumerator(
                       at: legacyRoot,
                       includingPropertiesForKeys: [.isRegularFileKey]
                   ) {
                    for case let source as URL in enumerator {
                        guard (try? source.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { continue }
                        let relativePath = String(source.path.dropFirst(legacyRoot.path.count + 1))
                        let target = destination.appendingPathComponent(relativePath)
                        guard !fileManager.fileExists(atPath: target.path) else { continue }
                        try fileManager.createDirectory(
                            at: target.deletingLastPathComponent(),
                            withIntermediateDirectories: true
                        )
                        try fileManager.copyItem(at: source, to: target)
                    }
                    UserDefaults.standard.set(true, forKey: "map.durableTileCacheMigrationV1")
                }
            }
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            var mutableDestination = destination
            try mutableDestination.setResourceValues(resourceValues)
        } catch {
            AppleLog.warning("MapTiles", "Durable tile-cache preparation failed: \(error.localizedDescription)")
        }
    }()

    static var root: URL {
        _ = prepareStorage
        return durableRoot
    }

    static var demRoot: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("RID2Caltopo/DEM", isDirectory: true)
    }

    static func tile(_ tile: OperationalOfflineTile, layerKey: String, fileExtension: String) -> URL {
        root.appendingPathComponent(layerKey, isDirectory: true)
            .appendingPathComponent(String(tile.zoom), isDirectory: true)
            .appendingPathComponent(String(tile.x), isDirectory: true)
            .appendingPathComponent("\(tile.y).\(fileExtension)")
    }
}

enum AppleMapCacheAccess {
    private static let lock = NSLock()

    static func synchronized<T>(_ operation: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try operation()
    }

    static func write(_ data: Data, to destination: URL) throws -> Int64 {
        try synchronized {
            let oldSize = Int64((try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: destination, options: .atomic)
            return Int64(data.count) - oldSize
        }
    }

    static func remove(_ url: URL) throws {
        try synchronized { try FileManager.default.removeItem(at: url) }
    }

    static func removeIfUnchanged(
        _ url: URL,
        expectedBytes: Int64,
        expectedDate: Date
    ) -> Int64? {
        synchronized {
            guard let values = try? url.resourceValues(
                forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
            ), values.isRegularFile == true,
               Int64(values.fileSize ?? 0) == expectedBytes,
               values.contentModificationDate == expectedDate,
               (try? FileManager.default.removeItem(at: url)) != nil
            else { return nil }
            return expectedBytes
        }
    }
}

enum AppleCacheHTTPClient {
    static func data(for request: URLRequest, session: URLSession = .shared) async throws -> Data {
        var attempt = 0
        while true {
            do {
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
                if http.statusCode == 200 { return data }
                guard OperationalCacheRetryPolicy.shouldRetry(statusCode: http.statusCode),
                      let delay = OperationalCacheRetryPolicy.delaySeconds(
                          attempt: attempt,
                          retryAfterHeader: http.value(forHTTPHeaderField: "Retry-After")
                      ) else { throw URLError(.badServerResponse) }
                attempt += 1
                try await sleep(seconds: delay)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                guard isTransient(error),
                      let delay = OperationalCacheRetryPolicy.delaySeconds(attempt: attempt)
                else { throw error }
                attempt += 1
                try await sleep(seconds: delay)
            }
        }
    }

    static func download(
        from url: URL,
        session: URLSession = .shared,
        onProgress: @escaping @Sendable (Int64, Int64?) -> Void = { _, _ in }
    ) async throws -> URL {
        var attempt = 0
        while true {
            do {
                onProgress(0, nil)
                let result = try await AppleProgressiveDownload(
                    configuration: session.configuration,
                    onProgress: onProgress
                ).start(from: url)
                if result.statusCode == 200 { return result.temporaryURL }
                try? FileManager.default.removeItem(at: result.temporaryURL)
                guard OperationalCacheRetryPolicy.shouldRetry(statusCode: result.statusCode),
                      let delay = OperationalCacheRetryPolicy.delaySeconds(
                          attempt: attempt,
                          retryAfterHeader: result.retryAfter
                      ) else { throw URLError(.badServerResponse) }
                attempt += 1
                try await sleep(seconds: delay)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                guard isTransient(error),
                      let delay = OperationalCacheRetryPolicy.delaySeconds(attempt: attempt)
                else { throw error }
                attempt += 1
                try await sleep(seconds: delay)
            }
        }
    }

    private static func sleep(seconds: TimeInterval) async throws {
        try await Task.sleep(nanoseconds: UInt64(max(0, seconds) * 1_000_000_000))
    }

    private static func isTransient(_ error: Error) -> Bool {
        guard let error = error as? URLError else { return false }
        return [
            .timedOut, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed,
            .networkConnectionLost, .notConnectedToInternet, .resourceUnavailable,
        ].contains(error.code)
    }
}

private final class AppleProgressiveDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    struct Result: Sendable {
        let temporaryURL: URL
        let statusCode: Int
        let retryAfter: String?
    }

    private let configuration: URLSessionConfiguration
    private let onProgress: @Sendable (Int64, Int64?) -> Void
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Result, Error>?
    private var session: URLSession?
    private var task: URLSessionDownloadTask?
    private var stagedURL: URL?
    private var cancellationRequested = false
    private var finished = false

    init(
        configuration: URLSessionConfiguration,
        onProgress: @escaping @Sendable (Int64, Int64?) -> Void
    ) {
        self.configuration = configuration
        self.onProgress = onProgress
    }

    func start(from url: URL) async throws -> Result {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if cancellationRequested {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                self.continuation = continuation
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                let task = session.downloadTask(with: url)
                self.session = session
                self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: {
            lock.lock()
            cancellationRequested = true
            let task = task
            lock.unlock()
            task?.cancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let expected = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : nil
        onProgress(totalBytesWritten, expected)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("RID2Caltopo-\(UUID().uuidString).download")
        do {
            try FileManager.default.moveItem(at: location, to: destination)
            lock.lock()
            stagedURL = destination
            lock.unlock()
        } catch {
            finish(.failure(error))
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        if let error {
            lock.lock()
            let cancelled = cancellationRequested
            lock.unlock()
            finish(.failure(cancelled ? CancellationError() : error))
            return
        }
        lock.lock()
        let stagedURL = stagedURL
        lock.unlock()
        guard let stagedURL, let response = task.response as? HTTPURLResponse else {
            finish(.failure(URLError(.badServerResponse)))
            return
        }
        finish(.success(Result(
            temporaryURL: stagedURL,
            statusCode: response.statusCode,
            retryAfter: response.value(forHTTPHeaderField: "Retry-After")
        )))
    }

    private func finish(_ result: Swift.Result<Result, Error>) {
        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }
        finished = true
        let continuation = continuation
        self.continuation = nil
        let session = session
        self.session = nil
        let stagedURL = stagedURL
        lock.unlock()
        session?.finishTasksAndInvalidate()
        if case .failure = result, let stagedURL {
            try? FileManager.default.removeItem(at: stagedURL)
        }
        continuation?.resume(with: result)
    }
}

actor AppleMapTileDownloadCoordinator {
    static let shared = AppleMapTileDownloadCoordinator()
    private var inFlight: [String: Task<(Data, Int64), Error>] = [:]

    func download(
        request: URLRequest,
        destination: URL,
        blockedHashes: Set<String>
    ) async throws -> (data: Data, bytesAdded: Int64) {
        let key = destination.path
        if let existing = inFlight[key] {
            let (data, _) = try await existing.value
            return (data, 0)
        }
        let task = Task<(Data, Int64), Error> {
            let data = try await AppleCacheHTTPClient.data(for: request)
            guard AppleMapOfflineManager.dataIsUsableTile(data) else {
                AppleBadTilePolicy.record(data)
                throw CocoaError(.fileReadCorruptFile)
            }
            guard !blockedHashes.contains(AppleMapOfflineManager.hash(data)) else {
                throw CocoaError(.fileReadCorruptFile)
            }
            let bytesAdded = try AppleMapCacheAccess.write(data, to: destination)
            return (data, bytesAdded)
        }
        inFlight[key] = task
        defer { inFlight[key] = nil }
        return try await task.value
    }
}

actor AppleDEMDownloadCoordinator {
    enum Result: Sendable { case cacheHit, downloaded }

    static let shared = AppleDEMDownloadCoordinator()
    private var inFlight: [String: Task<Void, Error>] = [:]

    func ensureDEM(
        url: URL,
        fileName: String,
        expectedBytes: Int64?,
        session: URLSession = .shared,
        onProgress: @escaping @Sendable (Int64, Int64?) -> Void = { _, _ in }
    ) async throws -> Result {
        let destination = AppleMapCachePaths.demRoot.appendingPathComponent(fileName)
        let minimum = expectedBytes.map { max(100_000, $0 * 95 / 100) } ?? 5_000_000
        let cached = AppleMapCacheAccess.synchronized {
            Int64((try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) >= minimum
        }
        if cached { return .cacheHit }
        if let existing = inFlight[fileName] {
            try await existing.value
            return .cacheHit
        }
        let task = Task<Void, Error> {
            let required = (expectedBytes ?? 400_000_000) + 250_000_000
            let capacityURL = destination.deletingLastPathComponent().deletingLastPathComponent()
            if let available = try? capacityURL.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage,
               available < required {
                throw CocoaError(.fileWriteOutOfSpace)
            }
            let temporary = try await AppleCacheHTTPClient.download(
                from: url,
                session: session,
                onProgress: onProgress
            )
            let downloadedSize = Int64((try? temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            guard downloadedSize >= minimum else { throw CocoaError(.fileReadCorruptFile) }
            try AppleMapCacheAccess.synchronized {
                let fileManager = FileManager.default
                try fileManager.createDirectory(
                    at: destination.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                let staged = destination.deletingLastPathComponent()
                    .appendingPathComponent(".\(fileName).\(UUID().uuidString).partial")
                try fileManager.moveItem(at: temporary, to: staged)
                if fileManager.fileExists(atPath: destination.path) {
                    _ = try fileManager.replaceItemAt(destination, withItemAt: staged)
                } else {
                    try fileManager.moveItem(at: staged, to: destination)
                }
            }
        }
        inFlight[fileName] = task
        defer { inFlight[fileName] = nil }
        try await task.value
        return .downloaded
    }
}

enum AppleMapTileRequest {
    static func contourURL(zoom: Int, x: Int, y: Int) -> URL {
        let halfWorld = 20_037_508.342789244
        let span = halfWorld * 2 / pow(2, Double(zoom))
        let minX = -halfWorld + Double(x) * span
        let maxX = minX + span
        let maxY = halfWorld - Double(y) * span
        let minY = maxY - span
        var components = URLComponents(string: "https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export")!
        components.queryItems = [
            .init(name: "bbox", value: String(format: "%.6f,%.6f,%.6f,%.6f", minX, minY, maxX, maxY)),
            .init(name: "bboxSR", value: "3857"), .init(name: "imageSR", value: "3857"),
            .init(name: "size", value: "256,256"), .init(name: "format", value: "png32"),
            .init(name: "transparent", value: "true"), .init(name: "f", value: "image"),
        ]
        return components.url!
    }
}

enum AppleBadTilePolicy {
    private static let hashesKey = "map.badTileHashes"

    static func hashes(defaults: UserDefaults = .standard) -> Set<String> {
        Set(defaults.stringArray(forKey: hashesKey) ?? [])
    }

    static func isBlocked(_ data: Data, defaults: UserDefaults = .standard) -> Bool {
        hashes(defaults: defaults).contains(AppleMapOfflineManager.hash(data))
    }

    static func record(_ data: Data, defaults: UserDefaults = .standard) {
        guard !data.isEmpty else { return }
        record(hash: AppleMapOfflineManager.hash(data), defaults: defaults)
    }

    static func record(hash: String, defaults: UserDefaults = .standard) {
        guard !hash.isEmpty else { return }
        var values = hashes(defaults: defaults)
        values.insert(hash)
        defaults.set(values.sorted(), forKey: hashesKey)
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: hashesKey)
    }
}

struct AppleCachedMapTileSelection: Identifiable {
    let zoom: Int
    let x: Int
    let y: Int
    let hash: String
    let url: URL

    var id: String { "\(zoom)/\(x)/\(y)" }
}

@MainActor
final class AppleMapOfflineManager: ObservableObject {
    static let shared = AppleMapOfflineManager()

    private struct DEMDownload: Hashable {
        let url: URL
        let fileName: String
        let expectedBytes: Int64?
        let estimatedBytes: Int64
    }
    struct ProgressState: Equatable {
        var phase = "Idle"
        var completed = 0
        var total = 0
        var tileCompleted = 0
        var tileTotal = 0
        var tileCacheHits = 0
        var tileDownloaded = 0
        var tileFailed = 0
        var demCompleted = 0
        var demTotal = 0
        var demCacheHits = 0
        var demDownloaded = 0
        var demFailed = 0
        var estimatedBytesCompleted: Int64 = 0
        var estimatedBytesTotal: Int64 = 0
        var activeEstimatedBytes: Int64 = 0
        var activeTransferredBytes: Int64 = 0
        var activeExpectedBytes: Int64?
        var startedAt = Date()

        var weightedCompletedBytes: Int64 {
            OperationalOfflineProgress.weightedCompletedBytes(
                completedBytes: estimatedBytesCompleted,
                activeEstimatedBytes: activeEstimatedBytes,
                activeTransferredBytes: activeTransferredBytes,
                activeExpectedBytes: activeExpectedBytes
            )
        }
        var fraction: Double {
            OperationalOfflineProgress.fraction(
                completedBytes: weightedCompletedBytes,
                totalBytes: estimatedBytesTotal
            )
        }
        var cacheHits: Int { tileCacheHits + demCacheHits }
        var downloaded: Int { tileDownloaded + demDownloaded }
        var failed: Int { tileFailed + demFailed }
        var bytesPerSecond: Double {
            guard weightedCompletedBytes > 0 else { return 0 }
            return Double(weightedCompletedBytes) / max(0.001, Date().timeIntervalSince(startedAt))
        }
        var etaSeconds: Int? {
            let rate = bytesPerSecond
            guard rate > 1 else { return nil }
            return Int(ceil(Double(max(0, estimatedBytesTotal - weightedCompletedBytes)) / rate))
        }
    }

    struct CacheStats: Equatable {
        var bytes: Int64 = 0
        var tileBytes: Int64 = 0
        var demBytes: Int64 = 0
        var files = 0
        var oldest: Date?
        var availableVolumeBytes: Int64?
    }

    @Published private(set) var progress = ProgressState()
    @Published private(set) var cacheStats = CacheStats()
    @Published private(set) var cacheStatsReady = false
    @Published private(set) var status = "Ready"
    @Published private(set) var isRunning = false
    @Published private(set) var activeSelectionDescription = ""
    @Published var maximumCacheGB: Double
    @Published var maximumTileAgeDays: Int
    @Published var autoRemoveBadTiles: Bool

    private let defaults: UserDefaults
    private var downloadTask: Task<Void, Never>?
    private var maintenanceTask: Task<(removedFiles: Int, removedBytes: Int64), Never>?
    private var maintenanceCompletionTask: Task<Void, Never>?
    private var maintenanceRequested = false
    private var activeDEMFileName: String?

    nonisolated static func noteTileCached(bytes: Int) {
        guard bytes > 0 else { return }
        Task { @MainActor in
            shared.noteTileCached(bytes: Int64(bytes))
        }
    }

    var downloadMenuStatus: String? {
        Self.downloadMenuStatus(isRunning: isRunning, progress: progress)
    }

    nonisolated static func downloadMenuStatus(isRunning: Bool, progress: ProgressState) -> String? {
        guard isRunning else { return nil }
        guard progress.total > 0 else { return progress.phase }
        let percent = Int((progress.fraction * 100).rounded(.down))
        return "\(percent)%"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        maximumCacheGB = max(0.1, min(64, defaults.object(forKey: "map.maximumCacheGB") as? Double ?? 1))
        maximumTileAgeDays = max(1, min(3_650, defaults.object(forKey: "map.maximumTileAgeDays") as? Int ?? 365))
        autoRemoveBadTiles = defaults.object(forKey: "map.autoRemoveBadTiles") as? Bool ?? true
        refreshStats()
    }

    var badTileCount: Int { badHashes.count }

    func estimate(
        bounds: OperationalMapBounds,
        preset: OperationalOfflinePreset,
        includeContours: Bool,
        includeDEM: Bool,
        demResolution: OperationalDEMResolution = .maximum1m
    ) -> (tiles: Int, dem: Int, tileBytes: Int64, demBytes: Int64, bytes: Int64) {
        let tiles = OperationalOfflineMapPlanner.tileCount(
            bounds: bounds,
            minimumZoom: preset.minimumZoom,
            maximumZoom: preset.maximumZoom
        )
        let dem = includeDEM ? OperationalOfflineMapPlanner.estimatedDEMTileCount(bounds: bounds, resolution: demResolution) : 0
        let mapBytes = OperationalOfflineMapPlanner.estimatedBytes(
            tileCount: tiles,
            includeContours: includeContours,
            demTileCount: 0
        )
        let demBytes = includeDEM
            ? OperationalOfflineMapPlanner.estimatedDEMBytes(bounds: bounds, resolution: demResolution)
            : 0
        return (tiles, dem, mapBytes, demBytes, mapBytes + demBytes)
    }

    func start(
        bounds: OperationalMapBounds,
        preset: OperationalOfflinePreset,
        baseLayer: OperationalMapBaseLayer,
        includeContours: Bool,
        includeDEM: Bool,
        demResolution: OperationalDEMResolution = .maximum1m,
        selectionDescription: String = "Selected map area"
    ) {
        guard !isRunning else { return }
        maintenanceTask?.cancel()
        guard let tiles = OperationalOfflineMapPlanner.tiles(
            bounds: bounds,
            minimumZoom: preset.minimumZoom,
            maximumZoom: preset.maximumZoom
        ) else {
            status = "Selection exceeds the 250,000-tile safety limit. Choose a smaller area or preset."
            return
        }
        let estimatedDEMCount = includeDEM
            ? OperationalOfflineMapPlanner.estimatedDEMTileCount(bounds: bounds, resolution: demResolution)
            : 0
        isRunning = true
        AppleApplicationCleanupCenter.shared.setIdleShutdownDeferral(active: true)
        activeSelectionDescription = selectionDescription
        let tileOperationCount = tiles.count * (includeContours ? 2 : 1)
        let operationCount = tileOperationCount + estimatedDEMCount
        let estimatedTileBytes = Int64(tileOperationCount) * 32_000
        let estimatedDEMBytes = includeDEM
            ? OperationalOfflineMapPlanner.estimatedDEMBytes(bounds: bounds, resolution: demResolution)
            : 0
        progress = ProgressState(
            phase: "Preparing map tiles",
            completed: 0,
            total: operationCount,
            tileCompleted: 0,
            tileTotal: tileOperationCount,
            demCompleted: 0,
            demTotal: estimatedDEMCount,
            estimatedBytesTotal: Self.saturatedAdd(estimatedTileBytes, estimatedDEMBytes),
            startedAt: Date()
        )
        status = "Preparing \(operationCount) offline items"
        downloadTask = Task { [weak self] in
            guard let self else { return }
            let demDownloads: [DEMDownload]
            do {
                demDownloads = includeDEM
                    ? try await self.resolveDEMDownloads(bounds: bounds, resolution: demResolution)
                    : []
            } catch {
                self.isRunning = false
                AppleApplicationCleanupCenter.shared.setIdleShutdownDeferral(active: false)
                self.progress.phase = "Failed"
                self.status = "DEM planning failed: \(error.localizedDescription)"
                self.downloadTask = nil
                return
            }
            self.progress.demTotal = demDownloads.count
            self.progress.total = tileOperationCount + demDownloads.count
            self.progress.estimatedBytesTotal = demDownloads.reduce(estimatedTileBytes) {
                Self.saturatedAdd($0, $1.estimatedBytes)
            }
            for tile in tiles {
                guard !Task.isCancelled else { break }
                await self.fetchTile(tile, baseLayer: baseLayer)
                if includeContours, !Task.isCancelled { await self.fetchContour(tile) }
            }
            if !demDownloads.isEmpty, !Task.isCancelled {
                self.progress.phase = "Preparing DEM tiles"
            }
            for download in demDownloads where !Task.isCancelled {
                await self.fetchDEM(download)
            }
            let cancelled = Task.isCancelled
            self.isRunning = false
            AppleApplicationCleanupCenter.shared.setIdleShutdownDeferral(active: false)
            self.progress.phase = cancelled
                ? "Cancelled"
                : (self.progress.failed > 0 ? "Complete with failures" : "Complete")
            self.status = cancelled
                ? "Offline preparation cancelled"
                : "Offline preparation complete: \(self.progress.downloaded) downloaded, \(self.progress.cacheHits) cached, \(self.progress.failed) failed"
            self.downloadTask = nil
            self.refreshStats()
            if !cancelled { self.runMaintenance() }
        }
    }

    func cancel() {
        guard isRunning, progress.phase != "Cancelling" else { return }
        progress.phase = "Cancelling"
        status = "Cancellation requested; finishing the current operation…"
        downloadTask?.cancel()
    }

    func saveSettings() {
        maximumCacheGB = max(0.1, min(64, maximumCacheGB))
        maximumTileAgeDays = max(1, min(3_650, maximumTileAgeDays))
        defaults.set(maximumCacheGB, forKey: "map.maximumCacheGB")
        defaults.set(maximumTileAgeDays, forKey: "map.maximumTileAgeDays")
        defaults.set(autoRemoveBadTiles, forKey: "map.autoRemoveBadTiles")
        status = "Map cache settings saved"
    }

    func clearBadTileFlags() {
        AppleBadTilePolicy.clear(defaults: defaults)
        objectWillChange.send()
        status = "Bad tile flags cleared"
    }

    func cachedTileSelection(
        zoom: Int,
        x: Int,
        y: Int,
        baseLayer: OperationalMapBaseLayer
    ) async -> AppleCachedMapTileSelection? {
        let tile = OperationalOfflineTile(zoom: zoom, x: x, y: y)
        let url = AppleMapCachePaths.tile(
            tile,
            layerKey: baseLayer.cacheKey,
            fileExtension: baseLayer.fileExtension
        )
        guard let data = await Task.detached(priority: .userInitiated, operation: {
            AppleMapCacheAccess.synchronized { try? Data(contentsOf: url) }
        }).value else { return nil }
        return AppleCachedMapTileSelection(
            zoom: zoom,
            x: x,
            y: y,
            hash: Self.hash(data),
            url: url
        )
    }

    func removeCachedTile(_ selection: AppleCachedMapTileSelection, quarantineMatchingHash: Bool) {
        do {
            try AppleMapCacheAccess.remove(selection.url)
            if quarantineMatchingHash {
                AppleBadTilePolicy.record(hash: selection.hash, defaults: defaults)
                status = "Tile removed and hash quarantined"
            } else {
                status = "Tile removed from cache"
            }
            refreshStats()
        } catch {
            status = "Bad tile removal failed: \(error.localizedDescription)"
        }
    }

    func runMaintenance() {
        saveSettings()
        maintenanceRequested = true
        startMaintenanceIfEligible()
    }

    private func startMaintenanceIfEligible() {
        guard !isRunning, maintenanceTask == nil, maintenanceRequested else { return }
        maintenanceRequested = false
        let maximumBytes = Int64(maximumCacheGB * 1_000_000_000)
        let cutoff = Date().addingTimeInterval(-Double(maximumTileAgeDays) * 86_400)
        status = "Maintaining map cache…"
        let worker = Task.detached(priority: .utility) {
            Self.maintainCache(maximumBytes: maximumBytes, cutoff: cutoff)
        }
        maintenanceTask = worker
        maintenanceCompletionTask = Task { [weak self] in
            let result = await worker.value
            guard let self else { return }
            if !worker.isCancelled {
                self.status = "Cache maintenance removed \(result.removedFiles) files (\(Self.formatBytes(result.removedBytes)))"
            }
            self.maintenanceTask = nil
            self.maintenanceCompletionTask = nil
            self.refreshStats()
            self.startMaintenanceIfEligible()
        }
    }

    private func noteTileCached(bytes: Int64) {
        cacheStats.tileBytes = Self.saturatedAdd(cacheStats.tileBytes, bytes)
        cacheStats.bytes = Self.saturatedAdd(cacheStats.bytes, bytes)
        requestMaintenanceIfOverLimit()
    }

    private func requestMaintenanceIfOverLimit() {
        let maximumBytes = Int64(maximumCacheGB * 1_000_000_000)
        guard cacheStatsReady, cacheStats.tileBytes > maximumBytes else { return }
        maintenanceRequested = true
        startMaintenanceIfEligible()
    }

    func refreshStats() {
        Task { [weak self] in
            let stats = await Task.detached(priority: .utility) { Self.scanCache() }.value
            self?.cacheStats = stats
            self?.cacheStatsReady = true
            self?.requestMaintenanceIfOverLimit()
        }
    }

    func exportBadTileHashes() -> URL? {
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent("RID2Caltopo-bad-tile-hashes.txt")
        let lines = ["# RID2Caltopo bad tile hashes", "# count=\(badHashes.count)"] + badHashes.sorted()
        do {
            try (lines.joined(separator: "\n") + "\n").write(to: destination, atomically: true, encoding: .utf8)
            return destination
        } catch {
            status = "Bad tile hash export failed: \(error.localizedDescription)"
            return nil
        }
    }

    static func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    static func formatDuration(_ seconds: Int?) -> String {
        guard let seconds else { return "--:--" }
        let clamped = max(0, seconds)
        let hours = clamped / 3_600
        let minutes = (clamped % 3_600) / 60
        let remainder = clamped % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, remainder)
            : String(format: "%02d:%02d", minutes, remainder)
    }

    private nonisolated static func saturatedAdd(_ left: Int64, _ right: Int64) -> Int64 {
        left > Int64.max - right ? Int64.max : left + right
    }

    nonisolated static func dataIsUsableTile(_ data: Data) -> Bool {
        data.count > 100 && UIImage(data: data) != nil
    }

    nonisolated static func cachedTileIsUsable(
        at url: URL,
        blockedHashes: Set<String>,
        cutoff: Date
    ) -> Bool {
        AppleMapCacheAccess.synchronized {
            guard let values = try? url.resourceValues(
                forKeys: [.fileSizeKey, .contentModificationDateKey]
            ), let size = values.fileSize, size > 100,
               OperationalCacheFreshness.isFresh(modifiedAt: values.contentModificationDate, cutoff: cutoff)
            else { return false }
            // Both MapPane and the offline downloader validate image data before
            // committing it to this app-owned cache. Avoid re-reading and decoding
            // every known-good file during each preparation pass. Hashing remains
            // necessary when the operator has quarantined matching tile payloads.
            guard !blockedHashes.isEmpty else { return true }
            guard let data = try? Data(contentsOf: url) else { return false }
            return !blockedHashes.contains(hash(data))
        }
    }

    nonisolated static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private var badHashes: Set<String> {
        AppleBadTilePolicy.hashes(defaults: defaults)
    }

    private func fetchTile(_ tile: OperationalOfflineTile, baseLayer: OperationalMapBaseLayer) async {
        let destination = AppleMapCachePaths.tile(tile, layerKey: baseLayer.cacheKey, fileExtension: baseLayer.fileExtension)
        guard let url = baseLayer.tileURL(zoom: tile.zoom, x: tile.x, y: tile.y) else {
            recordFailure(kind: .tile)
            return
        }
        await fetchImage(url: url, destination: destination)
    }

    private func fetchContour(_ tile: OperationalOfflineTile) async {
        await fetchImage(
            url: AppleMapTileRequest.contourURL(zoom: tile.zoom, x: tile.x, y: tile.y),
            destination: AppleMapCachePaths.tile(tile, layerKey: "usgsContours", fileExtension: "png")
        )
    }

    private func fetchImage(url: URL, destination: URL) async {
        let blockedHashes = badHashes
        let cutoff = Date().addingTimeInterval(-Double(maximumTileAgeDays) * 86_400)
        let cached = await Task.detached(priority: .utility) {
            Self.cachedTileIsUsable(at: destination, blockedHashes: blockedHashes, cutoff: cutoff)
        }.value
        if cached {
            recordCacheHit(kind: .tile)
            return
        }
        progress.phase = "Downloading map tiles"
        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 20
            request.setValue("RID2Caltopo/Apple (contact: kjt@uas4sar.com)", forHTTPHeaderField: "User-Agent")
            let result = try await AppleMapTileDownloadCoordinator.shared.download(
                request: request,
                destination: destination,
                blockedHashes: blockedHashes
            )
            noteTileCached(bytes: result.bytesAdded)
            recordDownload(kind: .tile)
        } catch is CancellationError {
            return
        } catch {
            AppleLog.warning("MapOffline", "Tile download failed \(url.absoluteString): \(error.localizedDescription)")
            recordFailure(kind: .tile)
        }
    }

    private func resolveDEMDownloads(
        bounds: OperationalMapBounds,
        resolution: OperationalDEMResolution
    ) async throws -> [DEMDownload] {
        if resolution != .maximum1m {
            let product = resolution == .enhanced10m ? "13" : "1"
            let estimatedBytes: Int64 = resolution == .enhanced10m ? 486_000_000 : 54_000_000
            return OperationalOfflineMapPlanner.demTileNames(bounds: bounds).compactMap { name in
                let fileName = "USGS_\(product)_\(name).tif"
                guard let url = URL(string: "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/\(product)/TIFF/current/\(name)/\(fileName)") else { return nil }
                return DEMDownload(
                    url: url,
                    fileName: fileName,
                    expectedBytes: nil,
                    estimatedBytes: estimatedBytes
                )
            }
        }

        // Preserve complete 10 m coverage underneath S1M tiles. The terrain
        // sampler selects the finest valid overlap and falls through on 1 m NoData cells.
        var downloads: [DEMDownload] = OperationalOfflineMapPlanner.demTileNames(bounds: bounds).compactMap { name in
            let fileName = "USGS_13_\(name).tif"
            guard let url = URL(string: "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/\(name)/\(fileName)") else { return nil }
            return DEMDownload(
                url: url,
                fileName: fileName,
                expectedBytes: nil,
                estimatedBytes: 486_000_000
            )
        }
        var offset = 0
        repeat {
            var components = URLComponents(string: "https://tnmaccess.nationalmap.gov/api/v1/products")!
            components.queryItems = [
                .init(name: "bbox", value: "\(bounds.west),\(bounds.south),\(bounds.east),\(bounds.north)"),
                .init(name: "prodFormats", value: "GeoTIFF"),
                .init(name: "outputFormat", value: "JSON"),
                .init(name: "datasets", value: OperationalS1MCatalog.datasetName),
                .init(name: "max", value: "100"), .init(name: "offset", value: String(offset)),
            ]
            var request = URLRequest(url: components.url!)
            request.timeoutInterval = 20
            request.setValue("RID2Caltopo/Apple (contact: kjt@uas4sar.com)", forHTTPHeaderField: "User-Agent")
            let data = try await AppleCacheHTTPClient.data(for: request)
            let products = try OperationalS1MCatalog.products(data: data)
            downloads.append(contentsOf: products.map {
                DEMDownload(
                    url: $0.url,
                    fileName: $0.fileName,
                    expectedBytes: $0.expectedBytes,
                    estimatedBytes: $0.expectedBytes ?? 400_000_000
                )
            })
            let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let items = object?["items"] as? [[String: Any]] ?? []
            offset += items.count
            let total = (object?["total"] as? NSNumber)?.intValue ?? downloads.count
            if items.isEmpty || offset >= total { break }
        } while offset < 2_000
        return Array(Set(downloads)).sorted { $0.fileName < $1.fileName }
    }

    private func fetchDEM(_ download: DEMDownload) async {
        progress.phase = "Downloading DEM tiles"
        activeDEMFileName = download.fileName
        progress.activeEstimatedBytes = download.estimatedBytes
        progress.activeTransferredBytes = 0
        progress.activeExpectedBytes = download.expectedBytes
        do {
            let result = try await AppleDEMDownloadCoordinator.shared.ensureDEM(
                url: download.url,
                fileName: download.fileName,
                expectedBytes: download.expectedBytes,
                onProgress: { [weak self] transferred, expected in
                    Task { @MainActor [weak self] in
                        guard let self, self.activeDEMFileName == download.fileName else { return }
                        self.progress.activeTransferredBytes = max(0, transferred)
                        if let expected { self.progress.activeExpectedBytes = expected }
                    }
                }
            )
            if result == .cacheHit {
                recordCacheHit(kind: .dem, estimatedBytes: download.estimatedBytes)
            } else {
                recordDownload(kind: .dem, estimatedBytes: download.estimatedBytes)
            }
        } catch is CancellationError {
            return
        } catch {
            AppleLog.warning("MapOffline", "DEM download failed \(download.fileName): \(error.localizedDescription)")
            recordFailure(kind: .dem, estimatedBytes: download.estimatedBytes)
        }
    }

    private func recordBadTile(_ data: Data) {
        AppleBadTilePolicy.record(data, defaults: defaults)
    }

    private enum OperationKind {
        case tile
        case dem
    }

    private func recordCacheHit(kind: OperationKind, estimatedBytes: Int64 = 32_000) {
        switch kind {
        case .tile:
            progress.tileCacheHits += 1
            progress.tileCompleted += 1
        case .dem:
            progress.demCacheHits += 1
            progress.demCompleted += 1
        }
        progress.completed += 1
        completeEstimatedBytes(estimatedBytes)
    }

    private func recordDownload(kind: OperationKind, estimatedBytes: Int64 = 32_000) {
        switch kind {
        case .tile:
            progress.tileDownloaded += 1
            progress.tileCompleted += 1
        case .dem:
            progress.demDownloaded += 1
            progress.demCompleted += 1
        }
        progress.completed += 1
        completeEstimatedBytes(estimatedBytes)
    }

    private func recordFailure(kind: OperationKind, estimatedBytes: Int64 = 32_000) {
        switch kind {
        case .tile:
            progress.tileFailed += 1
            progress.tileCompleted += 1
        case .dem:
            progress.demFailed += 1
            progress.demCompleted += 1
        }
        progress.completed += 1
        completeEstimatedBytes(estimatedBytes)
    }

    private func completeEstimatedBytes(_ bytes: Int64) {
        progress.estimatedBytesCompleted = Self.saturatedAdd(
            progress.estimatedBytesCompleted,
            max(0, bytes)
        )
        progress.activeEstimatedBytes = 0
        progress.activeTransferredBytes = 0
        progress.activeExpectedBytes = nil
        activeDEMFileName = nil
    }

    private nonisolated static func scanCache() -> CacheStats {
        var result = CacheStats()
        for (root, isDEM) in [(AppleMapCachePaths.root, false), (AppleMapCachePaths.demRoot, true)] {
            guard let enumerator = FileManager.default.enumerator(
                at: root,
                includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
            ) else { continue }
            for case let url as URL in enumerator {
                guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]),
                      values.isRegularFile == true else { continue }
                result.files += 1
                let bytes = Int64(values.fileSize ?? 0)
                result.bytes += bytes
                if isDEM { result.demBytes += bytes } else { result.tileBytes += bytes }
                if let date = values.contentModificationDate, result.oldest == nil || date < result.oldest! { result.oldest = date }
            }
        }
        result.availableVolumeBytes = try? AppleMapCachePaths.root.resourceValues(
            forKeys: [.volumeAvailableCapacityForImportantUsageKey]
        ).volumeAvailableCapacityForImportantUsage
        return result
    }

    private nonisolated static func maintainCache(maximumBytes: Int64, cutoff: Date) -> (removedFiles: Int, removedBytes: Int64) {
        var files: [(url: URL, bytes: Int64, date: Date)] = []
        guard let enumerator = FileManager.default.enumerator(
            at: AppleMapCachePaths.root,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        ) else { return (0, 0) }
        for case let url as URL in enumerator {
            guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]),
                  values.isRegularFile == true else { continue }
            files.append((url, Int64(values.fileSize ?? 0), values.contentModificationDate ?? .distantPast))
        }
        var removedFiles = 0
        var removedBytes: Int64 = 0
        for file in files where file.date < cutoff {
            guard !Task.isCancelled else { return (removedFiles, removedBytes) }
            if let bytes = AppleMapCacheAccess.removeIfUnchanged(
                file.url,
                expectedBytes: file.bytes,
                expectedDate: file.date
            ) {
                removedFiles += 1
                removedBytes += bytes
            }
        }
        var remaining = files.filter { $0.date >= cutoff }.sorted { $0.date < $1.date }
        var bytes = remaining.reduce(Int64(0)) { $0 + $1.bytes }
        let trimTarget = maximumBytes * 9 / 10
        while bytes > maximumBytes, !remaining.isEmpty {
            guard !Task.isCancelled else { break }
            let file = remaining.removeFirst()
            if let removed = AppleMapCacheAccess.removeIfUnchanged(
                file.url,
                expectedBytes: file.bytes,
                expectedDate: file.date
            ) {
                removedFiles += 1
                removedBytes += removed
                bytes -= removed
            }
            if bytes <= trimTarget { break }
        }
        return (removedFiles, removedBytes)
    }
}

struct AppleOfflineMapPreparationView: View {
    struct BoundaryOption: Identifiable {
        let id: String
        let title: String
        let coordinates: [MapCoordinate]
    }

    @ObservedObject var manager: AppleMapOfflineManager
    let viewportBounds: OperationalMapBounds
    let boundaries: [BoundaryOption]
    let baseLayer: OperationalMapBaseLayer
    let contoursInitiallyEnabled: Bool

    @Environment(\.dismiss) private var dismiss
    @State private var preset = OperationalOfflinePreset.operations
    @State private var selectedBoundaryID = ""
    @State private var includeContours: Bool
    @State private var includeDEM = true
    @State private var demResolution = OperationalDEMResolution.maximum1m
    @State private var cacheLimitInput: String
    @State private var cacheLimitFeedback: String?
    @State private var cacheLimitFeedbackIsError = false
    @FocusState private var cacheLimitFieldFocused: Bool

    private let progressSectionID = "offline-map-progress"

    init(
        manager: AppleMapOfflineManager,
        viewportBounds: OperationalMapBounds,
        boundaries: [BoundaryOption],
        baseLayer: OperationalMapBaseLayer,
        contoursInitiallyEnabled: Bool
    ) {
        self.manager = manager
        self.viewportBounds = viewportBounds
        self.boundaries = boundaries
        self.baseLayer = baseLayer
        self.contoursInitiallyEnabled = contoursInitiallyEnabled
        _includeContours = State(initialValue: contoursInitiallyEnabled)
        _cacheLimitInput = State(initialValue: String(format: "%.1f", manager.maximumCacheGB))
    }

    private var bounds: OperationalMapBounds {
        guard let polygon = boundaries.first(where: { $0.id == selectedBoundaryID }) else { return viewportBounds }
        return OperationalMapBounds(coordinates: polygon.coordinates)
    }

    private var estimate: (tiles: Int, dem: Int, tileBytes: Int64, demBytes: Int64, bytes: Int64) {
        manager.estimate(
            bounds: bounds, preset: preset, includeContours: includeContours,
            includeDEM: includeDEM, demResolution: demResolution
        )
    }

    private var capacity: OperationalOfflineCapacity {
        OperationalOfflineCapacity(
            currentTileCacheBytes: manager.cacheStats.tileBytes,
            currentDEMCacheBytes: manager.cacheStats.demBytes,
            estimatedTileBytes: estimate.tileBytes,
            estimatedDEMBytes: estimate.demBytes,
            maximumTileCacheBytes: Int64((parsedCacheLimitGB ?? manager.maximumCacheGB) * 1_000_000_000),
            availableVolumeBytes: manager.cacheStats.availableVolumeBytes
        )
    }

    private var parsedCacheLimitGB: Double? {
        let decimalSeparator = Locale.current.decimalSeparator ?? "."
        let normalized = cacheLimitInput
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: decimalSeparator, with: ".")
        guard let value = Double(normalized), value.isFinite, (0.1 ... 64).contains(value) else { return nil }
        return value
    }

    private var cacheLimitStepperBinding: Binding<Double> {
        Binding(
            get: { parsedCacheLimitGB ?? manager.maximumCacheGB },
            set: { value in
                manager.maximumCacheGB = value
                cacheLimitInput = String(format: "%.1f", value)
                cacheLimitFeedback = nil
            }
        )
    }

    @discardableResult
    private func saveCacheLimit(showConfirmation: Bool = true) -> Bool {
        cacheLimitFieldFocused = false
        guard let value = parsedCacheLimitGB else {
            cacheLimitFeedback = "Enter a cache limit from 0.1 to 64 GB."
            cacheLimitFeedbackIsError = true
            return false
        }
        manager.maximumCacheGB = value
        manager.saveSettings()
        cacheLimitInput = String(format: "%.1f", manager.maximumCacheGB)
        cacheLimitFeedback = showConfirmation
            ? "Saved \(cacheLimitInput) GB map-tile cache limit."
            : nil
        cacheLimitFeedbackIsError = false
        return true
    }

    private var selectionDescription: String {
        let area = boundaries.first(where: { $0.id == selectedBoundaryID })?.title ?? "Current visible map"
        let contents = [
            includeContours ? "contours" : nil,
            includeDEM ? "DEM \(demResolution.label)" : nil,
        ].compactMap { $0 }.joined(separator: ", ")
        return "\(area) · \(preset.label)" + (contents.isEmpty ? "" : " · \(contents)")
    }

    private var tileProgressText: String {
        let progress = manager.progress
        return "Tiles: \(progress.tileCompleted)/\(progress.tileTotal) "
            + "(hit=\(progress.tileCacheHits) fetched=\(progress.tileDownloaded) failed=\(progress.tileFailed))"
    }

    private var demProgressText: String {
        let progress = manager.progress
        return "DEM: \(progress.demCompleted)/\(progress.demTotal) "
            + "(hit=\(progress.demCacheHits) fetched=\(progress.demDownloaded) failed=\(progress.demFailed))"
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                Form {
                Section("Area") {
                    Picker("Boundary", selection: $selectedBoundaryID) {
                        Text("Current visible map").tag("")
                        ForEach(boundaries) { Text($0.title).tag($0.id) }
                    }
                    Picker("Detail", selection: $preset) {
                        ForEach(OperationalOfflinePreset.all) { Text($0.label).tag($0) }
                    }
                }
                .disabled(manager.isRunning)
                Section("Contents") {
                    Toggle("Include contour tiles", isOn: $includeContours)
                    Toggle("Include DEM tiles", isOn: $includeDEM)
                    if includeDEM {
                        Picker("DEM detail", selection: $demResolution) {
                            ForEach(OperationalDEMResolution.allCases) { Text($0.label).tag($0) }
                        }
                        Text(demResolution.explanation)
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    LabeledContent("Map tiles", value: estimate.tiles.formatted())
                    LabeledContent("DEM tiles", value: estimate.dem.formatted())
                    LabeledContent("Map-tile download", value: AppleMapOfflineManager.formatBytes(estimate.tileBytes))
                    if includeDEM {
                        LabeledContent("DEM download", value: AppleMapOfflineManager.formatBytes(estimate.demBytes))
                    }
                    LabeledContent("Conservative total", value: AppleMapOfflineManager.formatBytes(estimate.bytes))
                    Text("The estimate is conservative. One-metre availability is resolved from the USGS catalog when preparation starts.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .disabled(manager.isRunning)
                Section("Capacity") {
                    if manager.cacheStatsReady {
                        LabeledContent("Current map-tile cache", value: AppleMapOfflineManager.formatBytes(capacity.currentTileCacheBytes))
                        LabeledContent("Projected map-tile cache", value: AppleMapOfflineManager.formatBytes(capacity.projectedTileCacheBytes))
                        LabeledContent("Current DEM storage", value: AppleMapOfflineManager.formatBytes(capacity.currentDEMCacheBytes))
                        LabeledContent("Conservative projected storage", value: AppleMapOfflineManager.formatBytes(capacity.projectedOfflineStorageBytes))
                        LabeledContent("Map-tile cache limit", value: AppleMapOfflineManager.formatBytes(capacity.maximumTileCacheBytes))
                        if let available = capacity.availableVolumeBytes {
                            LabeledContent("Available on volume", value: AppleMapOfflineManager.formatBytes(available))
                        }
                        Text("DEM files are stored separately and do not count toward the map-tile cache limit.")
                            .font(.footnote).foregroundStyle(.secondary)
                        if capacity.exceedsCacheLimit {
                            Text("This download is expected to exceed the tile-cache limit. Increase the limit or reduce the selection before starting.")
                                .font(.footnote).foregroundStyle(.red)
                            Button("Use recommended \(AppleMapOfflineManager.formatBytes(capacity.recommendedMaximumBytes)) limit") {
                                manager.maximumCacheGB = Double(capacity.recommendedMaximumBytes) / 1_000_000_000
                                cacheLimitInput = String(format: "%.1f", manager.maximumCacheGB)
                                saveCacheLimit()
                            }
                        }
                        if capacity.exceedsAvailableVolume {
                            Text("The estimated download may exceed available storage.")
                                .font(.footnote).foregroundStyle(.red)
                        }
                    } else {
                        HStack {
                            ProgressView()
                            Text("Checking current cache and available space…")
                        }
                    }
                    HStack {
                        Text("Max cache size")
                        Spacer()
                        TextField("GB", text: $cacheLimitInput)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 72)
                            .focused($cacheLimitFieldFocused)
                            .onChange(of: cacheLimitInput) { _, _ in cacheLimitFeedback = nil }
                        Text("GB").foregroundStyle(.secondary)
                        Stepper(
                            "Adjust cache size",
                            value: cacheLimitStepperBinding,
                            in: 0.1 ... 64,
                            step: 0.1
                        )
                        .labelsHidden()
                    }
                    .disabled(manager.isRunning)
                    Button("Save cache limit") { saveCacheLimit() }
                        .disabled(manager.isRunning)
                    if let cacheLimitFeedback {
                        Label(
                            cacheLimitFeedback,
                            systemImage: cacheLimitFeedbackIsError
                                ? "exclamationmark.triangle.fill"
                                : "checkmark.circle.fill"
                        )
                        .font(.footnote)
                        .foregroundStyle(cacheLimitFeedbackIsError ? Color.red : Color.green)
                    }
                }
                if manager.isRunning || manager.progress.phase != "Idle" {
                    Section("Progress") {
                        if manager.progress.phase == "Cancelling" {
                            Label("Cancelling download…", systemImage: "hourglass")
                                .font(.headline)
                                .foregroundStyle(.orange)
                        } else if manager.progress.phase == "Cancelled" {
                            Label("Download cancelled", systemImage: "xmark.circle.fill")
                                .font(.headline)
                                .foregroundStyle(.orange)
                        }
                        if !manager.activeSelectionDescription.isEmpty {
                            Text(manager.activeSelectionDescription)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        let percent = manager.progress.fraction * 100
                        Text("\(percent, specifier: "%.0f")% complete")
                            .font(.title3)
                        ProgressView(value: manager.progress.fraction)
                        Text(String(
                            format: "Progress: %@ %d/%d (%.2f%%) rate=%@/s ETA=%@",
                            manager.progress.phase,
                            manager.progress.completed,
                            manager.progress.total,
                            percent,
                            AppleMapOfflineManager.formatBytes(Int64(manager.progress.bytesPerSecond)),
                            AppleMapOfflineManager.formatDuration(manager.progress.etaSeconds)
                        ))
                        .font(.caption.monospaced())
                        Text(tileProgressText)
                        .font(.caption.monospaced())
                        if manager.progress.demTotal > 0 {
                            Text(demProgressText)
                            .font(.caption.monospaced())
                        }
                        if manager.progress.failed > 0 {
                            Text("Total failures: \(manager.progress.failed)")
                                .font(.caption.monospaced())
                                .foregroundStyle(.red)
                        }
                        Text(manager.status).font(.footnote).foregroundStyle(.secondary)
                    }
                    .id(progressSectionID)
                }
                }
                .navigationTitle("Download Map")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if manager.isRunning {
                            if manager.progress.phase == "Cancelling" {
                                HStack(spacing: 6) {
                                    ProgressView()
                                    Text("Cancelling…")
                                }
                            } else {
                                Button("Cancel", role: .destructive) {
                                    manager.cancel()
                                    DispatchQueue.main.async {
                                        withAnimation { proxy.scrollTo(progressSectionID, anchor: .top) }
                                    }
                                }
                            }
                        } else {
                            Button("Start") {
                                guard saveCacheLimit(showConfirmation: false) else { return }
                                manager.start(
                                    bounds: bounds,
                                    preset: preset,
                                    baseLayer: baseLayer,
                                    includeContours: includeContours,
                                    includeDEM: includeDEM,
                                    demResolution: demResolution,
                                    selectionDescription: selectionDescription
                                )
                                DispatchQueue.main.async {
                                    withAnimation { proxy.scrollTo(progressSectionID, anchor: .top) }
                                }
                            }
                            .disabled(!manager.cacheStatsReady
                                || parsedCacheLimitGB == nil
                                || estimate.tiles > 250_000
                                || capacity.exceedsCacheLimit
                                || capacity.exceedsAvailableVolume)
                        }
                    }
                }
            }
        }
        .task { manager.refreshStats() }
    }
}

struct AppleMapCacheManagementView: View {
    @ObservedObject var manager: AppleMapOfflineManager
    @Binding var offlineOnly: Bool
    @Binding var followFocusedDrone: Bool
    let canReloadMap: Bool
    let mapReloadInFlight: Bool
    let mapReloadStatus: String
    let onReloadMap: () -> Void
    let onExportMutualAid: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                // Keep these controls in the same order as Android's Map Management menu.
                Section("Map Management") {
                    Toggle(
                        followFocusedDrone ? "Follow Focused Drone: On" : "Follow Focused Drone: Off",
                        isOn: $followFocusedDrone
                    )
                    Button(action: onReloadMap) {
                        HStack {
                            Text(mapReloadInFlight ? "Reloading Map…" : "Reload Map")
                            if mapReloadInFlight { ProgressView() }
                        }
                    }
                    .disabled(!canReloadMap || mapReloadInFlight)
                    Text(mapReloadStatus)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    NavigationLink("Bad Tiles…") {
                        AppleBadTileManagementView(manager: manager)
                    }
                    Stepper(
                        "Max Cache Size: \(manager.maximumCacheGB, specifier: "%.1f") GB",
                        value: $manager.maximumCacheGB,
                        in: 0.1 ... 64,
                        step: 0.1
                    )
                    Stepper(
                        "Maximum Tile Age: \(manager.maximumTileAgeDays) days",
                        value: $manager.maximumTileAgeDays,
                        in: 1 ... 3_650
                    )
                    Button("Export MA Package…", systemImage: "shippingbox") {
                        dismiss()
                        onExportMutualAid()
                    }
                }
                Section("Additional Cache Controls") {
                    Toggle("Offline Tiles Only", isOn: $offlineOnly)
                    LabeledContent("Cached files", value: manager.cacheStats.files.formatted())
                    LabeledContent("Cache size", value: AppleMapOfflineManager.formatBytes(manager.cacheStats.bytes))
                    Button("Run Cache Maintenance") { manager.runMaintenance() }
                    Text(manager.status).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Map Management")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { manager.saveSettings(); dismiss() }
                }
            }
            .onAppear { manager.refreshStats() }
        }
    }
}

private struct AppleBadTileManagementView: View {
    @ObservedObject var manager: AppleMapOfflineManager
    @State private var badHashExport: URL?

    var body: some View {
        Form {
            // Keep these controls in the same order as Android's Bad Tiles menu.
            Section("Bad Tiles") {
                NavigationLink("How To") {
                    AppleBadTileHowToView()
                }
                Toggle(
                    manager.autoRemoveBadTiles ? "Auto Remove Bad Tiles: On" : "Auto Remove Bad Tiles: Off",
                    isOn: $manager.autoRemoveBadTiles
                )
                Button("Clear Bad Tile Flags (\(manager.badTileCount))") {
                    manager.clearBadTileFlags()
                }
                if let export = badHashExport {
                    ShareLink(item: export) {
                        Label("Export Bad Tile Hashes", systemImage: "square.and.arrow.up")
                    }
                } else {
                    Button("Export Bad Tile Hashes") {
                        badHashExport = manager.exportBadTileHashes()
                    }
                }
            }
        }
        .navigationTitle("Bad Tiles")
    }
}

private struct AppleBadTileHowToView: View {
    var body: some View {
        Form {
            Section {
                Text("Use this when map tiles show a cached error page such as OpenStreetMap's ‘Access blocked’ tile.")
                Text("1. Turn on Auto Remove Bad Tiles if you want quarantined tiles removed automatically when encountered.")
                Text("2. Long-press a bad tile on the map.")
                Text("3. In the Remove Bad Tile dialog, leave ‘Also quarantine same-hash tiles’ checked and press Remove.")
                Text("4. The selected tile is removed from cache, and matching bad tiles can be suppressed across the map.")
                Text("Clear Bad Tile Flags removes the quarantine list only. It does not remove tiles already cached.")
                Text("Export Bad Tile Hashes saves the quarantined hashes for troubleshooting or sharing.")
            }
        }
        .navigationTitle("Bad Tiles How To")
    }
}
