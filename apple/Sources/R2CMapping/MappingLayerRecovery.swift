import Foundation
import os
import R2CCore

/// Adopt complete unpublished result dirs into the local layer registry. IO worker only.
public enum MappingLayerRecovery {
    private static let log = Logger(subsystem: "org.ncssar.RID2Caltopo", category: "MappingProcessing")

    public struct Adoption: Sendable {
        public let layer: MappingLayer
        public let directory: URL
        public let missionID: String
    }

    /// Idempotent. Scans mission `results/` dirs; returns complete orphans; removes empty unprotected dirs.
    @discardableResult
    public static func adoptCompleteOrphans(
        missionsRoot: URL,
        protectedIds: Set<String> = [],
        alreadyRegistered: Set<String> = []
    ) throws -> [Adoption] {
        precondition(!Thread.isMainThread, "Mapping layer orphan adoption belongs on an IO worker.")
        let fm = FileManager.default
        guard fm.fileExists(atPath: missionsRoot.path) else { return [] }
        var adopted: [Adoption] = []
        let missions = try fm.contentsOfDirectory(at: missionsRoot, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])
        for mission in missions {
            guard UUID(uuidString: mission.lastPathComponent) != nil else { continue }
            let results = mission.appendingPathComponent("results", isDirectory: true)
            guard fm.fileExists(atPath: results.path) else { continue }
            let dirs = try fm.contentsOfDirectory(at: results, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])
            for dir in dirs {
                let id = dir.lastPathComponent
                guard UUID(uuidString: id)?.uuidString.lowercased() == id.lowercased() else { continue }
                let children = try fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
                if children.isEmpty {
                    if !protectedIds.contains(id) {
                        try? fm.removeItem(at: dir)
                        log.debug("orphan empty removed id=\(id.prefix(8), privacy: .public)")
                    }
                    continue
                }
                guard MappingLayerTiles.hasTileManifest(dir) else { continue }
                let data = try Data(contentsOf: dir.appendingPathComponent("layer.json"))
                guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let incident = object["incidentId"] as? String else { continue }
                let layer = try MappingLayer.decode(data, incidentID: incident)
                if alreadyRegistered.contains(layer.id) { continue }
                adopted.append(Adoption(layer: layer, directory: dir, missionID: mission.lastPathComponent))
                log.debug("orphan adopted id=\(layer.id.prefix(8), privacy: .public) incident=\(layer.incidentId.prefix(8), privacy: .public)")
                linkMission(mission: mission, layerID: layer.id)
            }
        }
        return adopted
    }

    private static func linkMission(mission: URL, layerID: String) {
        let statusFile = mission.appendingPathComponent("processing-status.json")
        guard FileManager.default.fileExists(atPath: statusFile.path),
              let data = try? Data(contentsOf: statusFile),
              var status = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        if let existing = status["layerId"] as? String, !existing.isEmpty { return }
        status["layerId"] = layerID
        if (status["stage"] as? String)?.isEmpty != false { status["stage"] = "Map ready" }
        status["active"] = false
        status["progress"] = 1.0
        if let out = try? JSONSerialization.data(withJSONObject: status, options: [.sortedKeys]) {
            try? out.write(to: statusFile, options: .atomic)
        }
    }
}
