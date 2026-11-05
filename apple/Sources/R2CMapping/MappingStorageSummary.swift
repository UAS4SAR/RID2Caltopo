import Darwin
import Foundation

/// Persisted Manage Storage → Mapping pane sizes. Survives restarts.
///
/// Change detection stamps real artifacts only (per-mission / per-layer child
/// mtime+count, plus processing). Stats/cache writes under mapping-storage-stats/
/// never stale the stamp. Archive sizes are on-card allocated bytes (block-rounded).
/// The operational-work projection is a separate "Working copy" line and is never
/// part of the archive total. Worker-only — never call from Main.
public enum MappingStorageSummary {
    public static let fileName = "pane-summary.json"
    /// Test hook. Runs on the leader of a cold or stale scan, before the walk.
    nonisolated(unsafe) public static var beforeScan: (() -> Void)?
    public static let defaultBlockSize: Int64 = 4096
    private static let version = 2
    private static let lock = NSLock()
    // Locked by [lock]; Swift 6 requires an explicit unsafe annotation for mutable statics.
    nonisolated(unsafe) private static var memory: Report?

    public struct DirStamp: Codable, Equatable, Sendable {
        public var modified: Double
        public var count: Int
        public var signature: String

        public init(modified: Double, count: Int, signature: String) {
            self.modified = modified
            self.count = count
            self.signature = signature
        }

        public static func of(_ root: URL, skip: (String) -> Bool = { _ in false }) -> DirStamp {
            let fm = FileManager.default
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: root.path, isDirectory: &isDir) else {
                return DirStamp(modified: 0, count: 0, signature: "")
            }
            let children = ((try? fm.contentsOfDirectory(
                at: root,
                includingPropertiesForKeys: [.contentModificationDateKey],
                options: [.skipsHiddenFiles]
            )) ?? []).filter { !skip($0.lastPathComponent) }
            let sorted = children.sorted { $0.lastPathComponent < $1.lastPathComponent }
            let signature = sorted.map { url -> String in
                let m = (try? url.resourceValues(forKeys: [.contentModificationDateKey])
                    .contentModificationDate?.timeIntervalSince1970) ?? 0
                return "\(url.lastPathComponent):\(m)"
            }.joined(separator: "|")
            let modified = sorted.compactMap {
                try? $0.resourceValues(forKeys: [.contentModificationDateKey])
                    .contentModificationDate?.timeIntervalSince1970
            }.max() ?? 0
            return DirStamp(modified: modified, count: sorted.count, signature: signature)
        }
    }

    public struct Stamp: Codable, Equatable, Sendable {
        public var projects: [String: DirStamp]
        public var processing: DirStamp

        public init(projects: [String: DirStamp], processing: DirStamp) {
            self.projects = projects
            self.processing = processing
        }
    }

    public struct ProjectLine: Codable, Equatable, Sendable, Identifiable {
        public var id: String
        public var name: String
        public var timeLabel: String
        public var photoCount: Int
        public var bytes: Int64
        public var layersBytes: Int64
        public var workingBytes: Int64
        public var complete: Bool
        public var sized: Bool
        public var flightId: String?
        public var layerId: String?

        public init(
            id: String,
            name: String,
            timeLabel: String,
            photoCount: Int,
            bytes: Int64,
            layersBytes: Int64,
            workingBytes: Int64,
            complete: Bool,
            sized: Bool = true,
            flightId: String? = nil,
            layerId: String? = nil
        ) {
            self.id = id
            self.name = name
            self.timeLabel = timeLabel
            self.photoCount = photoCount
            self.bytes = bytes
            self.layersBytes = layersBytes
            self.workingBytes = workingBytes
            self.complete = complete
            self.sized = sized
            self.flightId = flightId
            self.layerId = layerId
        }
    }

    public struct Report: Equatable, Sendable {
        public var version: Int
        public var projects: [ProjectLine]
        public var sharedLayersBytes: Int64
        public var statsBytes: Int64
        public var processingBytes: Int64
        public var otherBytes: Int64
        public var totalBytes: Int64
        public var logicalBytes: Int64
        public var workingCopyBytes: Int64
        public var complete: Bool
        public var stamp: Stamp
        /// Transient — not persisted.
        public var scanReason: String
        /// Transient — not persisted.
        public var scanElapsedMs: Int64
        /// Transient — not persisted.
        public var rescannedProjects: Int

        public init(
            version: Int = 2,
            projects: [ProjectLine],
            sharedLayersBytes: Int64,
            statsBytes: Int64,
            processingBytes: Int64,
            otherBytes: Int64,
            totalBytes: Int64,
            logicalBytes: Int64 = 0,
            workingCopyBytes: Int64 = 0,
            complete: Bool,
            stamp: Stamp,
            scanReason: String = "",
            scanElapsedMs: Int64 = 0,
            rescannedProjects: Int = 0
        ) {
            self.version = version
            self.projects = projects
            self.sharedLayersBytes = sharedLayersBytes
            self.statsBytes = statsBytes
            self.processingBytes = processingBytes
            self.otherBytes = otherBytes
            self.totalBytes = totalBytes
            self.logicalBytes = logicalBytes
            self.workingCopyBytes = workingCopyBytes
            self.complete = complete
            self.stamp = stamp
            self.scanReason = scanReason
            self.scanElapsedMs = scanElapsedMs
            self.rescannedProjects = rescannedProjects
        }

        /// Archive lines only — working copy is reported separately.
        public func lineSum() -> Int64 {
            projects.reduce(0) { $0 + $1.bytes } + sharedLayersBytes + statsBytes + processingBytes + otherBytes
        }
    }

    public struct Roots: Sendable {
        public var missions: URL
        public var layers: URL
        public var processing: URL
        public var stats: URL

        public init(missions: URL, layers: URL, processing: URL, stats: URL) {
            self.missions = missions
            self.layers = layers
            self.processing = processing
            self.stats = stats
        }

        /// Artifact stamp only — never includes mapping-storage-stats/.
        public func stamp() -> Stamp {
            var projects: [String: DirStamp] = [:]
            let fm = FileManager.default
            let missionChildren = ((try? fm.contentsOfDirectory(
                at: missions,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]
            )) ?? []).sorted { $0.lastPathComponent < $1.lastPathComponent }
            for child in missionChildren {
                var isDir: ObjCBool = false
                guard fm.fileExists(atPath: child.path, isDirectory: &isDir), isDir.boolValue else { continue }
                projects["mission:\(child.lastPathComponent)"] = .of(child)
            }
            let layerChildren = ((try? fm.contentsOfDirectory(
                at: layers,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]
            )) ?? []).sorted { $0.lastPathComponent < $1.lastPathComponent }
            for child in layerChildren {
                var isDir: ObjCBool = false
                guard fm.fileExists(atPath: child.path, isDirectory: &isDir), isDir.boolValue else { continue }
                projects["layer:\(child.lastPathComponent)"] = .of(child)
            }
            return Stamp(projects: projects, processing: .of(processing))
        }

        public var cacheFile: URL {
            stats.appendingPathComponent(MappingStorageSummary.fileName)
        }
    }

    private struct WalkTotals {
        var allocated: Int64
        var logical: Int64
        var complete: Bool
    }

    public static func allocatedBytes(logical: Int64, blockSize: Int64 = defaultBlockSize) -> Int64 {
        let block = max(blockSize, 1)
        if logical <= 0 { return 0 }
        return ((logical + block - 1) / block) * block
    }

    public static func blockSize(for url: URL) -> Int64 {
        precondition(!Thread.isMainThread)
        let fm = FileManager.default
        var probe = url.standardizedFileURL
        while !fm.fileExists(atPath: probe.path) {
            let parent = probe.deletingLastPathComponent()
            if parent.path == probe.path { return defaultBlockSize }
            probe = parent
        }
        var status = statfs()
        let ok = probe.path.withCString { statfs($0, &status) == 0 }
        guard ok else { return defaultBlockSize }
        let size = Int64(status.f_bsize)
        return size > 0 ? size : defaultBlockSize
    }

    public static func isFresh(_ cached: Report, current: Stamp) -> Bool {
        cached.stamp == current
    }

    public static func loadCached(roots: Roots) -> Report? {
        precondition(!Thread.isMainThread)
        lock.lock(); defer { lock.unlock() }
        if let memory { return memory }
        guard let data = try? Data(contentsOf: roots.cacheFile),
              let report = try? JSONDecoder().decode(PersistedReport.self, from: data),
              report.version == version else { return nil }
        let loaded = report.asReport()
        memory = loaded
        return loaded
    }

    public static func invalidate() {
        lock.lock(); memory = nil; lock.unlock()
    }

    public static func invalidate(roots: Roots) {
        invalidate()
        try? FileManager.default.removeItem(at: roots.cacheFile)
    }

    public static func save(roots: Roots, report: Report) throws {
        precondition(!Thread.isMainThread)
        try FileManager.default.createDirectory(at: roots.stats, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(PersistedReport(report))
        let temporary = roots.cacheFile.appendingPathExtension("tmp")
        try data.write(to: temporary, options: .atomic)
        _ = try FileManager.default.replaceItemAt(roots.cacheFile, withItemAt: temporary)
        lock.lock(); memory = report; lock.unlock()
    }

    public static func peekMemory() -> Report? {
        lock.lock(); defer { lock.unlock() }
        return memory
    }

    /// Cheap project rows for first paint before sizes are known.
    public static func projectPlaceholders(roots: Roots, projects: [ProjectLine] = []) -> Report {
        precondition(!Thread.isMainThread)
        let stamp = roots.stamp()
        var lines = projects.map { line -> ProjectLine in
            var copy = line
            copy.bytes = 0
            copy.complete = false
            copy.sized = false
            return copy
        }
        if lines.isEmpty {
            let missionChildren = ((try? FileManager.default.contentsOfDirectory(
                at: roots.missions,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]
            )) ?? []).sorted { $0.lastPathComponent < $1.lastPathComponent }
            for child in missionChildren {
                var isDir: ObjCBool = false
                guard FileManager.default.fileExists(atPath: child.path, isDirectory: &isDir), isDir.boolValue else { continue }
                let id = child.lastPathComponent
                lines.append(ProjectLine(
                    id: "flight:\(id)",
                    name: id,
                    timeLabel: "",
                    photoCount: 0,
                    bytes: 0,
                    layersBytes: 0,
                    workingBytes: 0,
                    complete: false,
                    sized: false,
                    flightId: id
                ))
            }
        }
        return Report(
            projects: lines,
            sharedLayersBytes: 0,
            statsBytes: 0,
            processingBytes: 0,
            otherBytes: 0,
            totalBytes: 0,
            logicalBytes: 0,
            workingCopyBytes: 0,
            complete: false,
            stamp: stamp,
            scanReason: "placeholders"
        )
    }

    public static func fileArchiveRoots(archiveRoot: URL) -> Roots? {
        let current = archiveRoot.appendingPathComponent("mapping", isDirectory: true)
        let missions = current.appendingPathComponent("missions", isDirectory: true)
        let layers = current.appendingPathComponent("layers", isDirectory: true)
        var isDir: ObjCBool = false
        let fm = FileManager.default
        if fm.fileExists(atPath: missions.path) || fm.fileExists(atPath: layers.path)
            || (fm.fileExists(atPath: current.path, isDirectory: &isDir) && isDir.boolValue) {
            return Roots(
                missions: missions,
                layers: layers,
                processing: current.appendingPathComponent("processing", isDirectory: true),
                stats: current.appendingPathComponent("stats", isDirectory: true)
            )
        }
        let legacy = archiveRoot.appendingPathComponent("cache/local-maps", isDirectory: true)
        if fm.fileExists(atPath: legacy.path) {
            return Roots(
                missions: legacy.appendingPathComponent("mapping-missions", isDirectory: true),
                layers: legacy.appendingPathComponent("mapping-layers", isDirectory: true),
                processing: legacy.appendingPathComponent("mapping-processing", isDirectory: true),
                stats: legacy.appendingPathComponent("mapping-storage-stats", isDirectory: true)
            )
        }
        return nil
    }

    public static func scanLogLine(_ report: Report) -> String {
        let total = formatGb(report.totalBytes)
        let working = formatGb(report.workingCopyBytes)
        let seconds = String(format: "%.1fs", locale: .enUSPOSIX, Double(report.scanElapsedMs) / 1000.0)
        let reason = report.scanReason.isEmpty ? "full" : report.scanReason
        return "Mapping storage scan: projects=\(report.projects.count) rescanned=\(report.rescannedProjects) total=\(total) working=\(working) \(seconds) reason=\(reason)"
    }

    /// Physical walk of mapping roots. Project bytes are mission / layer folders;
    /// Shared layers / Processing / Stats / Other lines sum with projects to the archive total.
    public static func measure(
        roots: Roots,
        projects: [ProjectLine] = [],
        previous: Report? = nil,
        blockSize: Int64? = nil,
        archiveRoots: Roots? = nil,
        workingCopyRoot: URL? = nil,
        reason: String = "full",
        log: (String) -> Void = { _ in }
    ) throws -> Report {
        precondition(!Thread.isMainThread)
        let started = DispatchTime.now().uptimeNanoseconds
        // Explicit blockSize (tests) forces block-rounding; nil uses on-disk allocated size.
        let preferDiskAllocated = blockSize == nil
        let resolvedBlock = blockSize ?? MappingStorageSummary.blockSize(for: roots.missions)
        let stamp = roots.stamp()
        let measureRoots = archiveRoots ?? roots
        let previousById = Dictionary(uniqueKeysWithValues: (previous?.projects ?? []).map { ($0.id, $0) })
        let previousStamp = previous?.stamp
        var rescanned = 0
        var complete = true
        var logicalAcc: Int64 = 0

        func sizeOfFile(_ file: URL) -> (allocated: Int64, logical: Int64) {
            let values = try? file.resourceValues(forKeys: [.fileSizeKey, .totalFileAllocatedSizeKey])
            let logical = Int64(values?.fileSize ?? 0)
            if preferDiskAllocated, let allocated = values?.totalFileAllocatedSize, allocated > 0 {
                return (Int64(allocated), logical)
            }
            return (allocatedBytes(logical: logical, blockSize: resolvedBlock), logical)
        }

        func fileAlloc(_ file: URL) -> Int64 {
            let sized = sizeOfFile(file)
            logicalAcc += sized.logical
            return sized.allocated
        }

        func isSymlink(_ url: URL) -> Bool {
            (try? url.resourceValues(forKeys: [.isSymbolicLinkKey]))?.isSymbolicLink == true
        }

        func walkFileTree(_ root: URL, skipNames: Set<String> = []) -> WalkTotals {
            let fm = FileManager.default
            let start = isSymlink(root) ? root.resolvingSymlinksInPath() : root
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: start.path, isDirectory: &isDir) else {
                return WalkTotals(allocated: 0, logical: 0, complete: true)
            }
            if !isDir.boolValue {
                let sized = sizeOfFile(start)
                logicalAcc += sized.logical
                return WalkTotals(allocated: sized.allocated, logical: sized.logical, complete: true)
            }
            var allocated: Int64 = 0
            var logical: Int64 = 0
            var ok = true
            var stack = [start]
            while let next = stack.popLast() {
                guard let children = try? fm.contentsOfDirectory(
                    at: next,
                    includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .isDirectoryKey, .isSymbolicLinkKey, .totalFileAllocatedSizeKey],
                    options: []
                ) else {
                    ok = false
                    continue
                }
                for child in children {
                    if skipNames.contains(child.lastPathComponent) { continue }
                    if isSymlink(child) { continue }
                    var childDir: ObjCBool = false
                    if fm.fileExists(atPath: child.path, isDirectory: &childDir), childDir.boolValue {
                        stack.append(child)
                    } else {
                        let sized = sizeOfFile(child)
                        logical += sized.logical
                        logicalAcc += sized.logical
                        allocated += sized.allocated
                    }
                }
            }
            return WalkTotals(allocated: allocated, logical: logical, complete: ok)
        }

        func measureDir(_ dir: URL, stampKey: String, lineId: String?) -> Int64 {
            let current = stamp.projects[stampKey]
            let priorStamp = previousStamp?.projects[stampKey]
            let cached = lineId.flatMap { previousById[$0] }
            if let current, priorStamp == current, let cached, cached.sized {
                return cached.bytes
            }
            rescanned += 1
            let walked = walkFileTree(dir)
            if !walked.complete { complete = false }
            return walked.allocated
        }

        let missionChildren = ((try? FileManager.default.contentsOfDirectory(
            at: measureRoots.missions,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )) ?? []).sorted { $0.lastPathComponent < $1.lastPathComponent }
        var missionBytes: [String: Int64] = [:]
        var looseMission: Int64 = 0
        for child in missionChildren {
            var isDir: ObjCBool = false
            FileManager.default.fileExists(atPath: child.path, isDirectory: &isDir)
            if isDir.boolValue {
                let name = child.lastPathComponent
                missionBytes[name] = measureDir(child, stampKey: "mission:\(name)", lineId: "flight:\(name)")
            } else {
                looseMission += fileAlloc(child)
            }
        }

        var projectLines: [ProjectLine]
        if projects.isEmpty {
            projectLines = missionBytes.keys.sorted().map { id in
                ProjectLine(
                    id: "flight:\(id)",
                    name: id,
                    timeLabel: "",
                    photoCount: 0,
                    bytes: missionBytes[id] ?? 0,
                    layersBytes: 0,
                    workingBytes: 0,
                    complete: true,
                    sized: true,
                    flightId: id
                )
            }
        } else {
            projectLines = projects.map { line in
                let flightId = line.flightId
                    ?? (line.id.hasPrefix("flight:") ? String(line.id.dropFirst(7)) : nil)
                let layerId = line.layerId
                let bytes: Int64
                if let flightId, let measured = missionBytes[flightId] {
                    bytes = measured
                } else if let layerId {
                    bytes = measureDir(
                        measureRoots.layers.appendingPathComponent(layerId, isDirectory: true),
                        stampKey: "layer:\(layerId)",
                        lineId: line.id
                    )
                } else {
                    bytes = 0
                }
                let prior = previousById[line.id]
                return ProjectLine(
                    id: line.id,
                    name: line.name,
                    timeLabel: line.timeLabel,
                    photoCount: line.photoCount,
                    bytes: bytes,
                    layersBytes: prior?.layersBytes ?? line.layersBytes,
                    workingBytes: prior?.workingBytes ?? line.workingBytes,
                    complete: true,
                    sized: true,
                    flightId: flightId,
                    layerId: layerId
                )
            }
        }

        let importedLayerIds = Set(projectLines.compactMap { line -> String? in
            guard let layerId = line.layerId, line.flightId == nil else { return nil }
            return layerId
        })
        var sharedLayersBytes: Int64 = 0
        let layerChildren = ((try? FileManager.default.contentsOfDirectory(
            at: measureRoots.layers,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )) ?? [])
        for child in layerChildren {
            var isDir: ObjCBool = false
            FileManager.default.fileExists(atPath: child.path, isDirectory: &isDir)
            if isDir.boolValue {
                if importedLayerIds.contains(child.lastPathComponent) { continue }
                let walked = walkFileTree(child)
                if !walked.complete { complete = false }
                sharedLayersBytes += walked.allocated
            } else {
                sharedLayersBytes += fileAlloc(child)
            }
        }

        let processingWalk = walkFileTree(measureRoots.processing)
        if !processingWalk.complete { complete = false }
        let processingBytes = processingWalk.allocated

        var statsBytes: Int64 = 0
        let statsChildren = ((try? FileManager.default.contentsOfDirectory(
            at: measureRoots.stats,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: []
        )) ?? [])
        for child in statsChildren {
            let name = child.lastPathComponent
            if name == fileName || name.hasPrefix("\(fileName).") || name.hasSuffix(".tmp") { continue }
            let walked = walkFileTree(child)
            if !walked.complete { complete = false }
            statsBytes += walked.allocated
        }

        let listedMissionIds = Set(projectLines.compactMap(\.flightId))
        let orphanMissionBytes = missionBytes.filter { !listedMissionIds.contains($0.key) }.values.reduce(Int64(0), +)
        let otherBytes = looseMission + orphanMissionBytes
        let projectSum = projectLines.reduce(Int64(0)) { $0 + $1.bytes }
        let totalBytes = projectSum + sharedLayersBytes + processingBytes + statsBytes + otherBytes

        // iOS typically has no operational-work dual store. Only measure when a distinct
        // workingCopyRoot is supplied; never mutate that tree.
        let workingCopyBytes: Int64
        if let workingCopyRoot {
            let walked = walkFileTree(workingCopyRoot)
            workingCopyBytes = walked.allocated
        } else {
            workingCopyBytes = 0
        }

        let logicalBytes = logicalAcc > 0 ? logicalAcc : totalBytes
        let elapsed = Int64((DispatchTime.now().uptimeNanoseconds - started) / 1_000_000)
        let report = Report(
            projects: projectLines,
            sharedLayersBytes: sharedLayersBytes,
            statsBytes: statsBytes,
            processingBytes: processingBytes,
            otherBytes: otherBytes,
            totalBytes: totalBytes,
            logicalBytes: logicalBytes,
            workingCopyBytes: workingCopyBytes,
            complete: complete,
            stamp: stamp,
            scanReason: reason,
            scanElapsedMs: max(0, elapsed),
            rescannedProjects: rescanned
        )
        log(scanLogLine(report))
        return report
    }

    public static func cachedOrRefresh(
        roots: Roots,
        projects: [ProjectLine] = [],
        force: Bool = false,
        previous: Report? = nil,
        blockSize: Int64? = nil,
        archiveRoots: Roots? = nil,
        workingCopyRoot: URL? = nil,
        log: (String) -> Void = { _ in }
    ) throws -> Report {
        precondition(!Thread.isMainThread)
        let current = roots.stamp()
        let cached = loadCached(roots: roots)
        if !force, let cached, isFresh(cached, current: current) {
            var hit = cached
            hit.scanReason = "cache"
            hit.scanElapsedMs = 0
            log(scanLogLine(hit))
            return hit
        }
        return try MappingStorageScanFlight.shared.join(key: roots.missions.path) {
            beforeScan?()
            let reason: String
            if force { reason = "forced" }
            else if cached == nil { reason = "cold" }
            else { reason = "stale" }
            let report = try measure(
                roots: roots,
                projects: projects,
                previous: previous ?? cached.flatMap { force ? nil : $0 },
                blockSize: blockSize,
                archiveRoots: archiveRoots,
                workingCopyRoot: workingCopyRoot,
                reason: reason,
                log: log
            )
            try save(roots: roots, report: report)
            return report
        }
    }

    private static func formatGb(_ bytes: Int64) -> String {
        String(format: "%.1fGB", locale: .enUSPOSIX, Double(bytes) / 1_000_000_000.0)
    }

    /// Disk shape of [Report] — omits transient scan timing fields.
    private struct PersistedReport: Codable {
        var version: Int
        var projects: [ProjectLine]
        var sharedLayersBytes: Int64
        var statsBytes: Int64
        var processingBytes: Int64
        var otherBytes: Int64
        var totalBytes: Int64
        var logicalBytes: Int64
        var workingCopyBytes: Int64
        var complete: Bool
        var stamp: Stamp

        init(_ report: Report) {
            version = report.version
            projects = report.projects
            sharedLayersBytes = report.sharedLayersBytes
            statsBytes = report.statsBytes
            processingBytes = report.processingBytes
            otherBytes = report.otherBytes
            totalBytes = report.totalBytes
            logicalBytes = report.logicalBytes
            workingCopyBytes = report.workingCopyBytes
            complete = report.complete
            stamp = report.stamp
        }

        func asReport() -> Report {
            Report(
                version: version,
                projects: projects,
                sharedLayersBytes: sharedLayersBytes,
                statsBytes: statsBytes,
                processingBytes: processingBytes,
                otherBytes: otherBytes,
                totalBytes: totalBytes,
                logicalBytes: logicalBytes,
                workingCopyBytes: workingCopyBytes,
                complete: complete,
                stamp: stamp
            )
        }
    }
}

private extension Locale {
    static let enUSPOSIX = Locale(identifier: "en_US_POSIX")
}

/// Coalesces concurrent mapping storage scans for one missions directory.
private final class MappingStorageScanFlight: @unchecked Sendable {
    static let shared = MappingStorageScanFlight()
    private let lock = NSLock()
    private var flights: [String: Box] = [:]

    private final class Box {
        let group = DispatchGroup()
        var report: MappingStorageSummary.Report?
        var error: Error?
        init() { group.enter() }
    }

    func join(key: String, _ body: () throws -> MappingStorageSummary.Report) throws -> MappingStorageSummary.Report {
        lock.lock()
        if let existing = flights[key] {
            lock.unlock()
            existing.group.wait()
            if let error = existing.error { throw error }
            if let report = existing.report { return report }
            throw MappingStorageScanFlightError.missing
        }
        let box = Box()
        flights[key] = box
        lock.unlock()
        do {
            let report = try body()
            box.report = report
            box.group.leave()
            lock.lock()
            if flights[key] === box { flights[key] = nil }
            lock.unlock()
            return report
        } catch {
            box.error = error
            box.group.leave()
            lock.lock()
            if flights[key] === box { flights[key] = nil }
            lock.unlock()
            throw error
        }
    }
}

private enum MappingStorageScanFlightError: Error {
    case missing
}
