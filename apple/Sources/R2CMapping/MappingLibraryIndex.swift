import Foundation
import os
import R2CCore

/// Persisted library listing fingerprints so UI can show rows without a full mission scan.
/// Fingerprints use stat only (mtime/length/list count) — never read originals, map.tif, tiles, glb, or hash contents.
public enum MappingLibraryIndex {
    public static let schemaVersion = 1
    private static let log = Logger(subsystem: "org.ncssar.RID2Caltopo", category: "MappingProcessing")

    public struct Fingerprint: Codable, Sendable, Equatable {
        public var dirModified: Double
        public var dirCount: Int
        public var files: [String: FileStat]
    }

    public struct FileStat: Codable, Sendable, Equatable {
        public var modified: Double
        public var length: Int64
    }

    public struct MissionEntry: Codable, Sendable, Equatable {
        public var id, title, createdAt, captureTime, stage: String
        public var photoCount, pendingCount, selectedCount: Int
        public var completeBytes: Int64
        public var hasRoute, has2D, has3D: Bool
        public var routeUnavailable: String?
        public var resultIds: [String]
        public var center: [Double]?
        public var fingerprint: Fingerprint
        public var planningBytes, photoBytes, tileBytes, modelBytes, workBytes, exportBytes, controllerBytes, metadataBytes: Int64
        public var incidentID: String
        public var cameraLabel: String
    }

    public struct LayerEntry: Codable, Sendable, Equatable {
        public var id, missionID, incidentID, title: String
        public var has2D, has3D: Bool
        public var fingerprint: Fingerprint
        public var captureTime, processedAt: String
        public var photoCount: Int
    }

    public struct Index: Codable, Sendable, Equatable {
        public var schemaVersion: Int
        public var missions: [MissionEntry]
        public var layers: [LayerEntry]
        public var updatedAt: String
    }

    public struct RefreshResult: Sendable {
        public let index: Index
        public let changed: Int
        public let removed: Int
        public let loadMs: Int
        public let refreshMs: Int
        public let fromCache: Bool
        public let stale: Bool
        public let skipReason: String?
    }

    public static let staleStage = "Storage is being prepared · showing saved list"
    public static let reasonHydrating = "hydrating"
    public static let reasonUnavailable = "missions-unavailable"

    /// Injectable for tests. When non-nil, overrides directory listing.
    nonisolated(unsafe) public static var missionListingOverride: (() -> [URL]?)?

    public static func storageDirectory(supportRoot: URL) -> URL {
        supportRoot.appendingPathComponent("mapping-storage-stats", isDirectory: true)
    }

    public static func fileURL(supportRoot: URL) -> URL {
        storageDirectory(supportRoot: supportRoot).appendingPathComponent("library-index.json")
    }

    public static func load(supportRoot: URL) -> Index? {
        precondition(!Thread.isMainThread)
        let url = fileURL(supportRoot: supportRoot)
        guard let data = try? Data(contentsOf: url),
              let index = try? JSONDecoder().decode(Index.self, from: data),
              index.schemaVersion == schemaVersion else { return nil }
        return index
    }

    public static func refresh(
        missionsRoot: URL,
        supportRoot: URL,
        liveMissionID: String? = nil,
        liveStage: String? = nil,
        liveProgress: Double? = nil,
        areaBusy: Bool = false
    ) throws -> RefreshResult {
        precondition(!Thread.isMainThread)
        let loadStarted = Date()
        let previous = load(supportRoot: supportRoot)
        let loadMs = Int(Date().timeIntervalSince(loadStarted) * 1000)
        let refreshStarted = Date()
        let fm = FileManager.default
        try fm.createDirectory(at: storageDirectory(supportRoot: supportRoot), withIntermediateDirectories: true)
        let byID = Dictionary(uniqueKeysWithValues: (previous?.missions ?? []).map { ($0.id, $0) })
        var layerByID = Dictionary(uniqueKeysWithValues: (previous?.layers ?? []).map { ($0.id, $0) })
        var changed = 0
        var nextMissions: [MissionEntry] = []
        var nextLayers: [LayerEntry] = []

        if areaBusy {
            log.debug("LibIdx skip reason=\(reasonHydrating, privacy: .public)")
            let index = previous ?? Index(schemaVersion: schemaVersion, missions: [], layers: [], updatedAt: ISO8601DateFormatter().string(from: Date()))
            var missions = index.missions
            if previous != nil {
                for i in missions.indices { missions[i].stage = staleStage }
            }
            let staleIndex = Index(schemaVersion: schemaVersion, missions: missions, layers: index.layers, updatedAt: index.updatedAt)
            let refreshMs = Int(Date().timeIntervalSince(refreshStarted) * 1000)
            return RefreshResult(index: staleIndex, changed: 0, removed: 0, loadMs: loadMs, refreshMs: refreshMs, fromCache: previous != nil, stale: previous != nil, skipReason: reasonHydrating)
        }

        let missionDirs: [URL]
        if let override = missionListingOverride {
            guard let listed = override() else {
                log.debug("LibIdx skip reason=\(reasonUnavailable, privacy: .public)")
                let index = previous ?? Index(schemaVersion: schemaVersion, missions: [], layers: [], updatedAt: ISO8601DateFormatter().string(from: Date()))
                var missions = index.missions
                if previous != nil {
                    for i in missions.indices { missions[i].stage = staleStage }
                }
                let staleIndex = Index(schemaVersion: schemaVersion, missions: missions, layers: index.layers, updatedAt: index.updatedAt)
                let refreshMs = Int(Date().timeIntervalSince(refreshStarted) * 1000)
                return RefreshResult(index: staleIndex, changed: 0, removed: 0, loadMs: loadMs, refreshMs: refreshMs, fromCache: previous != nil, stale: previous != nil, skipReason: reasonUnavailable)
            }
            missionDirs = listed
        } else {
            var isDir: ObjCBool = false
            let resolved = missionsRoot.resolvingSymlinksInPath()
            if fm.fileExists(atPath: missionsRoot.path, isDirectory: &isDir) == false ||
                (try? missionsRoot.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true &&
                fm.fileExists(atPath: resolved.path) == false {
                log.debug("LibIdx skip reason=\(reasonUnavailable, privacy: .public)")
                let index = previous ?? Index(schemaVersion: schemaVersion, missions: [], layers: [], updatedAt: ISO8601DateFormatter().string(from: Date()))
                var missions = index.missions
                if previous != nil {
                    for i in missions.indices { missions[i].stage = staleStage }
                }
                let staleIndex = Index(schemaVersion: schemaVersion, missions: missions, layers: index.layers, updatedAt: index.updatedAt)
                let refreshMs = Int(Date().timeIntervalSince(refreshStarted) * 1000)
                return RefreshResult(index: staleIndex, changed: 0, removed: 0, loadMs: loadMs, refreshMs: refreshMs, fromCache: previous != nil, stale: previous != nil, skipReason: reasonUnavailable)
            }
            missionDirs = (try? fm.contentsOfDirectory(at: missionsRoot, includingPropertiesForKeys: [.contentModificationDateKey, .isDirectoryKey], options: [.skipsHiddenFiles])) ?? []
        }
        var seen = Set<String>()
        for mission in missionDirs {
            guard let uuid = UUID(uuidString: mission.lastPathComponent) else { continue }
            let id = uuid.uuidString.lowercased()
            seen.insert(id)
            let evidence = hasRetainedEvidence(mission)
            let fingerprint: Fingerprint
            do { fingerprint = try missionFingerprint(mission) } catch {
                if evidence, var cached = byID[id] {
                    cached.stage = "Mission metadata unreadable; originals retained"
                    nextMissions.append(cached); continue
                }
                if evidence {
                    nextMissions.append(syntheticUnreadable(id: id, mission: mission))
                    changed += 1
                }
                continue
            }
            if var cached = byID[id], cached.fingerprint == fingerprint {
                if id == liveMissionID {
                    if let liveStage { cached.stage = liveStage }
                    _ = liveProgress
                }
                nextMissions.append(cached)
                for resultID in cached.resultIds {
                    if let layer = layerByID[resultID] { nextLayers.append(layer) }
                }
                continue
            }
            do {
                let rebuilt = try rebuildMission(mission, fingerprint: fingerprint)
                changed += 1
                var entry = rebuilt.mission
                if entry.photoCount == 0 && evidence {
                    entry.stage = entry.stage.contains("interrupted") ? entry.stage : "Mission metadata unreadable; originals retained"
                }
                nextMissions.append(entry)
                nextLayers.append(contentsOf: rebuilt.layers)
                for layer in rebuilt.layers { layerByID[layer.id] = layer }
            } catch {
                let status = (try? JSONSerialization.jsonObject(with: Data(contentsOf: mission.appendingPathComponent("processing-status.json"))) as? [String: Any]) ?? [:]
                let stage = (status["active"] as? Bool) == true
                    ? "Processing interrupted; originals retained"
                    : "Mission metadata unreadable; originals retained"
                if evidence, var cached = byID[id] {
                    cached.stage = stage
                    nextMissions.append(cached)
                } else if evidence {
                    var synthetic = syntheticUnreadable(id: id, mission: mission)
                    synthetic.stage = stage
                    nextMissions.append(synthetic)
                    changed += 1
                }
            }
        }
        // Drop only when parent listing succeeded and id is absent.
        let removed = byID.keys.filter { !seen.contains($0) }.count
        if let liveMissionID, let liveStage, let idx = nextMissions.firstIndex(where: { $0.id == liveMissionID }) {
            nextMissions[idx].stage = liveStage
            _ = liveProgress
        }
        let index = Index(
            schemaVersion: schemaVersion,
            missions: nextMissions,
            layers: nextLayers,
            updatedAt: ISO8601DateFormatter().string(from: Date())
        )
        if previous != index {
            try writeAtomic(index, to: fileURL(supportRoot: supportRoot))
        }
        let refreshMs = Int(Date().timeIntervalSince(refreshStarted) * 1000)
        log.debug("LibIdx load ms=\(loadMs, privacy: .public) n=\(nextMissions.count, privacy: .public) | refresh ms=\(refreshMs, privacy: .public) changed=\(changed, privacy: .public) removed=\(removed, privacy: .public)")
        return RefreshResult(index: index, changed: changed, removed: removed, loadMs: loadMs, refreshMs: refreshMs, fromCache: previous != nil, stale: false, skipReason: nil)
    }

    public static func hasRetainedEvidence(_ mission: URL) -> Bool {
        let fm = FileManager.default
        if fm.fileExists(atPath: mission.appendingPathComponent("processing-status.json").path) { return true }
        let originals = mission.appendingPathComponent("originals", isDirectory: true)
        if let names = try? fm.contentsOfDirectory(atPath: originals.path), names.contains(where: { $0.lowercased().hasSuffix(".jpg") }) { return true }
        let inventory = mission.appendingPathComponent("inventory", isDirectory: true)
        if let names = try? fm.contentsOfDirectory(atPath: inventory.path), names.contains(where: { $0.hasSuffix(".json") }) { return true }
        return false
    }

    private static func syntheticUnreadable(id: String, mission: URL) -> MissionEntry {
        let fp = (try? missionFingerprint(mission)) ?? Fingerprint(dirModified: 0, dirCount: 0, files: [:])
        return MissionEntry(
            id: id, title: "Mission", createdAt: "", captureTime: "",
            stage: "Mission metadata unreadable; originals retained",
            photoCount: 0, pendingCount: 0, selectedCount: 0, completeBytes: 0,
            hasRoute: FileManager.default.fileExists(atPath: mission.appendingPathComponent("mission.kmz").path),
            has2D: false, has3D: false, routeUnavailable: nil, resultIds: [], center: nil, fingerprint: fp,
            planningBytes: 0, photoBytes: 0, tileBytes: 0, modelBytes: 0, workBytes: 0, exportBytes: 0,
            controllerBytes: 0, metadataBytes: 0, incidentID: "", cameraLabel: "Camera unavailable"
        )
    }

    /// Cheap fingerprint compare — max caller interval should be 30s.
    public static func cheapChanged(missionsRoot: URL, previous: Index) throws -> Bool {
        precondition(!Thread.isMainThread)
        let fm = FileManager.default
        let dirs = (try? fm.contentsOfDirectory(at: missionsRoot, includingPropertiesForKeys: [.contentModificationDateKey], options: [.skipsHiddenFiles])) ?? []
        let ids = Set(dirs.compactMap { UUID(uuidString: $0.lastPathComponent)?.uuidString.lowercased() })
        if ids != Set(previous.missions.map(\.id)) { return true }
        for mission in dirs {
            guard let id = UUID(uuidString: mission.lastPathComponent)?.uuidString.lowercased(),
                  let cached = previous.missions.first(where: { $0.id == id }) else { return true }
            if try missionFingerprint(mission) != cached.fingerprint { return true }
        }
        return false
    }

    public static func toLocalMission(_ entry: MissionEntry, missionsRoot: URL, photos: [MappingOriginal] = []) -> MappingLocalMission {
        let results = entry.resultIds.map {
            missionsRoot.appendingPathComponent(entry.id, isDirectory: true)
                .appendingPathComponent("results", isDirectory: true)
                .appendingPathComponent($0, isDirectory: true)
        }
        var mission = MappingLocalMission(
            id: entry.id, title: entry.title, incidentID: entry.incidentID, date: entry.captureTime.isEmpty ? entry.createdAt : entry.captureTime,
            plannedAt: entry.createdAt, cameraLabel: entry.cameraLabel, photos: photos, results: results,
            planningBytes: entry.planningBytes, photoBytes: entry.photoBytes, tileBytes: entry.tileBytes,
            modelBytes: entry.modelBytes, workBytes: entry.workBytes, exportBytes: entry.exportBytes,
            controllerBytes: entry.controllerBytes, metadataBytes: entry.metadataBytes,
            latitude: entry.center.flatMap { $0.count == 2 ? $0[1] : nil },
            longitude: entry.center.flatMap { $0.count == 2 ? $0[0] : nil }
        )
        mission.listedPhotoCount = entry.photoCount
        mission.stageOverride = entry.stage
        mission.has2D = entry.has2D
        mission.has3D = entry.has3D
        return mission
    }

    private static func rebuildMission(_ mission: URL, fingerprint: Fingerprint) throws -> (mission: MissionEntry, layers: [LayerEntry]) {
        let fm = FileManager.default
        let planURL = mission.appendingPathComponent("mission.json")
        guard fm.fileExists(atPath: planURL.path),
              let plan = try JSONSerialization.jsonObject(with: Data(contentsOf: planURL)) as? [String: Any] else {
            throw MappingMission.Failure("Invalid mission plan.")
        }
        let id = mission.lastPathComponent
        let inventory = mission.appendingPathComponent("inventory", isDirectory: true)
        let inventoryFiles = fm.fileExists(atPath: inventory.path)
            ? (try fm.contentsOfDirectory(at: inventory, includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" })
            : []
        var photos: [MappingOriginal] = []
        photos.reserveCapacity(inventoryFiles.count)
        for file in inventoryFiles {
            photos.append(try JSONDecoder().decode(MappingOriginal.self, from: Data(contentsOf: file)))
        }
        photos.sort { $0.capturedAt == $1.capturedAt ? $0.name < $1.name : $0.capturedAt < $1.capturedAt }
        let completed = (try? JSONDecoder().decode([String].self, from: Data(contentsOf: mission.appendingPathComponent("completed-selection.json")))) ?? []
        let pending = (try? fm.contentsOfDirectory(atPath: mission.appendingPathComponent("incoming").path).filter { $0.hasSuffix(".json") }.count) ?? 0
        let resultRoot = mission.appendingPathComponent("results", isDirectory: true)
        var resultIds: [String] = []
        var layers: [LayerEntry] = []
        var has2D = false, has3D = false
        if fm.fileExists(atPath: resultRoot.path) {
            for dir in try fm.contentsOfDirectory(at: resultRoot, includingPropertiesForKeys: nil) {
                guard UUID(uuidString: dir.lastPathComponent) != nil else { continue }
                let layerFP = try layerFingerprint(dir)
                let tiles = MappingLayerTiles.hasTileManifest(dir)
                let surface = MappingLayerTiles.hasSurfaceModel(dir)
                if tiles { has2D = true }
                if surface { has3D = true }
                resultIds.append(dir.lastPathComponent)
                var title = plan["title"] as? String ?? "Mission"
                var capture = ""
                var processed = ""
                var photoCount = photos.count
                var incident = plan["incidentId"] as? String ?? ""
                if let data = try? Data(contentsOf: dir.appendingPathComponent("layer.json")),
                   let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    title = object["title"] as? String ?? title
                    capture = object["captureTimeLocal"] as? String ?? ""
                    processed = object["processedAt"] as? String ?? ""
                    photoCount = object["photoCount"] as? Int ?? photoCount
                    incident = object["incidentId"] as? String ?? incident
                }
                layers.append(LayerEntry(
                    id: dir.lastPathComponent, missionID: id, incidentID: incident, title: title,
                    has2D: tiles, has3D: surface, fingerprint: layerFP,
                    captureTime: capture, processedAt: processed, photoCount: photoCount
                ))
            }
        }
        var planning: Int64 = 0, original: Int64 = 0, tiles: Int64 = 0, models: Int64 = 0
        var work: Int64 = 0, exports: Int64 = 0, controller: Int64 = 0, metadata: Int64 = 0
        if let files = fm.enumerator(at: mission, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey]) {
            for case let file as URL in files {
                let info = try file.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                guard info.isRegularFile == true else { continue }
                let bytes = Int64(info.fileSize ?? 0)
                let relative = String(file.path.dropFirst(mission.path.count + 1))
                if relative.hasPrefix("originals/") { original += bytes }
                else if relative.hasPrefix("work/") { work += bytes }
                else if relative.hasPrefix("incoming/") || relative.hasPrefix("cloud/") { controller += bytes }
                else if relative.hasPrefix("results/") {
                    if file.pathExtension == "png" { tiles += bytes }
                    else if relative.contains("/surface/") { models += bytes }
                    else if file.pathExtension == "zip" { exports += bytes }
                    else { metadata += bytes }
                } else if ["mission.json", "boundary.kml", "mission.kmz", "route-summary.json"].contains(relative) {
                    planning += bytes
                } else { metadata += bytes }
            }
        }
        let feature = plan["feature"] as? [String: Any]
        let geometry = feature?["geometry"] as? [String: Any]
        let ring = (geometry?["coordinates"] as? [[[Double]]])?.first
        let center: [Double]? = {
            guard let ring, !ring.isEmpty else { return nil }
            let lat = ring.map { $0[1] }.reduce(0, +) / Double(ring.count)
            let lon = ring.map { $0[0] }.reduce(0, +) / Double(ring.count)
            return [lon, lat]
        }()
        let status = (try? JSONSerialization.jsonObject(with: Data(contentsOf: mission.appendingPathComponent("processing-status.json"))) as? [String: Any]) ?? [:]
        let stage: String = {
            if (status["active"] as? Bool) == true { return "Processing interrupted; originals retained" }
            if let stage = status["stage"] as? String, !stage.isEmpty { return stage }
            if has2D { return "Map ready" }
            if photos.isEmpty { return "Map prepared" }
            return "Images captured"
        }()
        let capture = photos.first(where: { !$0.capturedAt.isEmpty })?.capturedAt ?? (plan["createdAt"] as? String ?? "")
        let hasRoute = fm.fileExists(atPath: mission.appendingPathComponent("route-summary.json").path)
            || fm.fileExists(atPath: mission.appendingPathComponent("mission.kmz").path)
        let entry = MissionEntry(
            id: id, title: plan["title"] as? String ?? "Mission",
            createdAt: plan["createdAt"] as? String ?? "", captureTime: capture, stage: stage,
            photoCount: photos.count, pendingCount: pending, selectedCount: completed.count, completeBytes: original,
            hasRoute: hasRoute, has2D: has2D, has3D: has3D, routeUnavailable: nil, resultIds: resultIds.sorted(),
            center: center, fingerprint: fingerprint,
            planningBytes: planning, photoBytes: original, tileBytes: tiles, modelBytes: models,
            workBytes: work, exportBytes: exports, controllerBytes: controller, metadataBytes: metadata,
            incidentID: plan["incidentId"] as? String ?? "",
            cameraLabel: MappingResolution.cameraLabel(profileID: plan["cameraProfileId"] as? String, fallbackName: plan["cameraName"] as? String)
        )
        return (entry, layers)
    }

    private static func missionFingerprint(_ mission: URL) throws -> Fingerprint {
        let names = ["mission.json", "processing-status.json", "received-batch.json", "route-summary.json", "completed-selection.json"]
        var files: [String: FileStat] = [:]
        for name in names {
            let url = mission.appendingPathComponent(name)
            if let stat = try? fileStat(url) { files[name] = stat }
        }
        let inventory = mission.appendingPathComponent("inventory", isDirectory: true)
        let inventoryCount = (try? FileManager.default.contentsOfDirectory(atPath: inventory.path).count) ?? 0
        let inventoryModified = (try? inventory.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate?.timeIntervalSince1970) ?? 0
        files["inventory/"] = FileStat(modified: inventoryModified, length: Int64(inventoryCount))
        let results = mission.appendingPathComponent("results", isDirectory: true)
        let resultsCount = (try? FileManager.default.contentsOfDirectory(atPath: results.path).count) ?? 0
        let resultsModified = (try? results.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate?.timeIntervalSince1970) ?? 0
        files["results/"] = FileStat(modified: resultsModified, length: Int64(resultsCount))
        let dirModified = (try? mission.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate?.timeIntervalSince1970) ?? 0
        let dirCount = (try? FileManager.default.contentsOfDirectory(atPath: mission.path).count) ?? 0
        return Fingerprint(dirModified: dirModified, dirCount: dirCount, files: files)
    }

    private static func layerFingerprint(_ directory: URL) throws -> Fingerprint {
        var files: [String: FileStat] = [:]
        if let layer = try? fileStat(directory.appendingPathComponent("layer.json")) { files["layer.json"] = layer }
        let surface = directory.appendingPathComponent("surface", isDirectory: true)
        let surfaceModified = (try? surface.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate?.timeIntervalSince1970) ?? 0
        files["surface/"] = FileStat(modified: surfaceModified, length: 0)
        let dirModified = (try? directory.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate?.timeIntervalSince1970) ?? 0
        let dirCount = (try? FileManager.default.contentsOfDirectory(atPath: directory.path).count) ?? 0
        return Fingerprint(dirModified: dirModified, dirCount: dirCount, files: files)
    }

    private static func fileStat(_ url: URL) throws -> FileStat {
        let values = try url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
        return FileStat(modified: values.contentModificationDate?.timeIntervalSince1970 ?? 0, length: Int64(values.fileSize ?? 0))
    }

    private static func writeAtomic(_ index: Index, to url: URL) throws {
        let data = try JSONEncoder().encode(index)
        let temporary = url.appendingPathExtension("tmp")
        try data.write(to: temporary, options: .atomic)
        let fm = FileManager.default
        if fm.fileExists(atPath: url.path) { try fm.removeItem(at: url) }
        try fm.moveItem(at: temporary, to: url)
    }
}
