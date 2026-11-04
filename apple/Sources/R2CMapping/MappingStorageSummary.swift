import Foundation

/// Persisted Manage Storage → Mapping pane sizes. Survives restarts. Show cache
/// immediately; rescan only when top-level mapping artifacts are newer than the
/// stamp, or after [invalidate]. Worker-only.
public enum MappingStorageSummary {
    public static let fileName = "pane-summary.json"
    private static let version = 1
    private static let lock = NSLock()
    // Locked by [lock]; Swift 6 requires an explicit unsafe annotation for mutable statics.
    nonisolated(unsafe) private static var memory: Report?

    public struct DirStamp: Codable, Equatable, Sendable {
        public var modified: Double
        public var count: Int
        public var signature: String

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
            // Use filtered children only — writing pane-summary.json must not stale the stamp.
            let modified = sorted.compactMap {
                try? $0.resourceValues(forKeys: [.contentModificationDateKey])
                    .contentModificationDate?.timeIntervalSince1970
            }.max() ?? 0
            return DirStamp(modified: modified, count: sorted.count, signature: signature)
        }
    }

    public struct Stamp: Codable, Equatable, Sendable {
        public var missions: DirStamp
        public var layers: DirStamp
        public var processing: DirStamp
        public var stats: DirStamp
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

        public init(
            id: String,
            name: String,
            timeLabel: String,
            photoCount: Int,
            bytes: Int64,
            layersBytes: Int64,
            workingBytes: Int64,
            complete: Bool
        ) {
            self.id = id
            self.name = name
            self.timeLabel = timeLabel
            self.photoCount = photoCount
            self.bytes = bytes
            self.layersBytes = layersBytes
            self.workingBytes = workingBytes
            self.complete = complete
        }
    }

    public struct Report: Codable, Equatable, Sendable {
        public var version: Int
        public var projects: [ProjectLine]
        public var sharedLayersBytes: Int64
        public var statsBytes: Int64
        public var processingBytes: Int64
        public var otherBytes: Int64
        public var totalBytes: Int64
        public var complete: Bool
        public var stamp: Stamp

        public init(
            version: Int,
            projects: [ProjectLine],
            sharedLayersBytes: Int64,
            statsBytes: Int64,
            processingBytes: Int64,
            otherBytes: Int64,
            totalBytes: Int64,
            complete: Bool,
            stamp: Stamp
        ) {
            self.version = version
            self.projects = projects
            self.sharedLayersBytes = sharedLayersBytes
            self.statsBytes = statsBytes
            self.processingBytes = processingBytes
            self.otherBytes = otherBytes
            self.totalBytes = totalBytes
            self.complete = complete
            self.stamp = stamp
        }

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

        public func stamp() -> Stamp {
            Stamp(
                missions: .of(missions),
                layers: .of(layers),
                processing: .of(processing),
                stats: .of(stats) { name in
                    name == MappingStorageSummary.fileName
                        || name.hasPrefix("\(MappingStorageSummary.fileName).")
                        || name.hasSuffix(".tmp")
                }
            )
        }

        public var cacheFile: URL {
            stats.appendingPathComponent(MappingStorageSummary.fileName)
        }
    }

    public static func isFresh(_ cached: Report, current: Stamp) -> Bool {
        cached.stamp == current
    }

    public static func loadCached(roots: Roots) -> Report? {
        precondition(!Thread.isMainThread)
        lock.lock(); defer { lock.unlock() }
        if let memory { return memory }
        guard let data = try? Data(contentsOf: roots.cacheFile),
              let report = try? JSONDecoder().decode(Report.self, from: data),
              report.version == version else { return nil }
        memory = report
        return report
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
        let data = try JSONEncoder().encode(report)
        let temporary = roots.cacheFile.appendingPathExtension("tmp")
        try data.write(to: temporary, options: .atomic)
        _ = try FileManager.default.replaceItemAt(roots.cacheFile, withItemAt: temporary)
        lock.lock(); memory = report; lock.unlock()
    }

    public static func peekMemory() -> Report? {
        lock.lock(); defer { lock.unlock() }
        return memory
    }

    /// Physical walk of every mapping root. Project bytes are mission folders only so
    /// Shared layers / Processing / Stats / Other lines sum with projects to the total.
    public static func measure(
        roots: Roots,
        projects: [ProjectLine] = []
    ) throws -> Report {
        precondition(!Thread.isMainThread)
        let stamp = roots.stamp()
        var complete = true
        func walk(_ url: URL, skipNames: Set<String> = []) -> Int64 {
            let fm = FileManager.default
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: url.path, isDirectory: &isDir) else { return 0 }
            if !isDir.boolValue {
                return Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            }
            guard let children = try? fm.contentsOfDirectory(
                at: url,
                includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .isDirectoryKey],
                options: []
            ) else {
                complete = false
                return 0
            }
            return children.reduce(Int64(0)) { sum, child in
                if skipNames.contains(child.lastPathComponent) { return sum }
                return sum + walk(child)
            }
        }

        let missionDirs = (try? FileManager.default.contentsOfDirectory(
            at: roots.missions,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        var missionBytes: [String: Int64] = [:]
        var looseMission: Int64 = 0
        for child in missionDirs {
            var isDir: ObjCBool = false
            FileManager.default.fileExists(atPath: child.path, isDirectory: &isDir)
            if isDir.boolValue {
                missionBytes[child.lastPathComponent] = walk(child)
            } else {
                looseMission += walk(child)
            }
        }

        var projectLines = projects
        if projectLines.isEmpty {
            projectLines = missionBytes.keys.sorted().map { id in
                ProjectLine(
                    id: "flight:\(id)",
                    name: id,
                    timeLabel: "",
                    photoCount: 0,
                    bytes: missionBytes[id] ?? 0,
                    layersBytes: 0,
                    workingBytes: 0,
                    complete: true
                )
            }
        } else {
            projectLines = projectLines.map { line in
                let flightId = line.id.hasPrefix("flight:") ? String(line.id.dropFirst(7)) : line.id
                var copy = line
                if let bytes = missionBytes[flightId] {
                    copy.bytes = bytes
                }
                return copy
            }
        }

        let sharedLayersBytes = walk(roots.layers)
        let processingBytes = walk(roots.processing)
        let statsBytes = walk(roots.stats, skipNames: [fileName, "\(fileName).tmp"])
        let listed = Set(projectLines.map { $0.id.hasPrefix("flight:") ? String($0.id.dropFirst(7)) : $0.id })
        let orphan = missionBytes.filter { !listed.contains($0.key) }.values.reduce(Int64(0), +)
        let otherBytes = looseMission + orphan
        let projectSum = projectLines.reduce(Int64(0)) { $0 + $1.bytes }
        let totalBytes = projectSum + sharedLayersBytes + processingBytes + statsBytes + otherBytes

        return Report(
            version: version,
            projects: projectLines,
            sharedLayersBytes: sharedLayersBytes,
            statsBytes: statsBytes,
            processingBytes: processingBytes,
            otherBytes: otherBytes,
            totalBytes: totalBytes,
            complete: complete,
            stamp: stamp
        )
    }

    public static func cachedOrRefresh(
        roots: Roots,
        projects: [ProjectLine] = [],
        force: Bool = false
    ) throws -> Report {
        precondition(!Thread.isMainThread)
        let current = roots.stamp()
        if !force, let cached = loadCached(roots: roots), isFresh(cached, current: current) {
            return cached
        }
        let report = try measure(roots: roots, projects: projects)
        try save(roots: roots, report: report)
        return report
    }
}
