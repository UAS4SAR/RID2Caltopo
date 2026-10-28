import Foundation
import R2CCore

public struct MappingResultFiles: Sendable {
    public let assignmentName: String
    public let cameraLabel: String
    public let missionID: String?
    public let hasOriginals: Bool
    public let archive: URL?
    public let surfaces: [URL]

    public static func archiveName(assignment: String) -> String {
        let safe = String(assignment.map { character -> Character in
            character.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) || "/\\:*?\"<>|".unicodeScalars.contains($0) } ? "_" : character
        }).trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let name = String(safe.prefix(60))
        return (name.isEmpty ? "Map" : name) + "_2D_map.zip"
    }

    public static func geoTiffName(assignment: String) -> String {
        String(archiveName(assignment: assignment).dropLast(4)) + ".tif"
    }

    /// Inspect result/mission metadata on a worker, including older saved results.
    public static func inspect(result: URL, layer: MappingLayer, missionsRoot: URL) throws -> MappingResultFiles {
        let fm = FileManager.default
        let manifest = (try? Data(contentsOf: result.appendingPathComponent("layer.json")))
            .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        let fallback = result.deletingLastPathComponent().lastPathComponent == "results" ? result.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent : ""
        let missionID = [layer.missionId, manifest?["missionId"] as? String, fallback]
            .compactMap { $0 }.compactMap { UUID(uuidString: $0)?.uuidString.lowercased() }.first
        let mission = missionID.map { missionsRoot.appendingPathComponent($0) }
        let plan = mission.flatMap { try? Data(contentsOf: $0.appendingPathComponent("mission.json")) }
            .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        let cameraID = [layer.cameraProfileId, manifest?["cameraProfileId"] as? String, plan?["cameraProfileId"] as? String]
            .compactMap { $0 }.first { !$0.isEmpty }
        let label = MappingResolution.cameraLabel(profileID: cameraID, fallbackName: plan?["cameraName"] as? String)
        let hasOriginals = mission.map { folder in
            ((try? fm.contentsOfDirectory(at: folder.appendingPathComponent("originals"), includingPropertiesForKeys: nil)) ?? []).contains { $0.pathExtension.lowercased() == "jpg" }
        } ?? false
        let archive = result.appendingPathComponent("share.zip")
        var sharedArchive: URL?
        let assignment = (manifest?["assignmentName"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? (plan?["title"] as? String)
            ?? layer.resultTitle.components(separatedBy: " · Visible").first ?? "Map"
        if fm.fileExists(atPath: archive.path) {
            let shares = TemporaryWorkspace.root.appendingPathComponent("MapShares")
            try fm.createDirectory(at: shares, withIntermediateDirectories: true)
            for old in (try? fm.contentsOfDirectory(at: shares, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
                if let date = try? old.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
                   date.timeIntervalSinceNow < -86400 { try? fm.removeItem(at: old) }
            }
            let directory = shares.appendingPathComponent(UUID().uuidString)
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let named = directory.appendingPathComponent(archiveName(assignment: assignment))
            do { try fm.linkItem(at: archive, to: named) }
            catch { try fm.copyItem(at: archive, to: named) }
            sharedArchive = named
        }
        let surface = result.appendingPathComponent("surface")
        let single = surface.appendingPathComponent("surface.glb")
        var surfaces: [URL] = []
        if fm.fileExists(atPath: single.path) { surfaces = [single] }
        else if let data = try? Data(contentsOf: surface.appendingPathComponent("collection.json")),
                let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for region in object["regions"] as? [[String: Any]] ?? [] {
                guard let path = region["path"] as? String,
                      path.range(of: "^regions/[0-9]{1,3}/surface\\.glb$", options: .regularExpression) != nil else { continue }
                let file = surface.appendingPathComponent(path)
                if fm.fileExists(atPath: file.path) { surfaces.append(file) }
            }
        }
        return MappingResultFiles(assignmentName: assignment, cameraLabel: label, missionID: missionID, hasOriginals: hasOriginals,
            archive: sharedArchive, surfaces: surfaces)
    }
}
