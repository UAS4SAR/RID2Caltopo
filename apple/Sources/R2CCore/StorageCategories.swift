import Foundation

public enum StorageCategory: String, CaseIterable, Sendable {
    case imagery = "Map imagery", terrain = "Terrain files", decoded = "Decoded terrain", aol = "AOL obstacle tiles"
    case icons = "Icons and elevation samples", projects = "Local Maps projects and originals", layers = "Map layers and models"
    case checkpoints = "Saved processing checkpoints", logs = "Diagnostic logs", tracks = "Track logs", clues = "Clue pictures"
    case recordings = "Captured video recordings", annotations = "Video review annotations", support = "Downloaded support data"
    case metadata = "Indexes and operational metadata", recovery = "Recovery files", other = "Other retained archive files", temporary = "Temporary files"
}

public struct StorageCategoryReport: Sendable {
    public let bytes: [StorageCategory: Int64]
    public let complete: Bool
    public var total: Int64 { bytes.values.reduce(0, +) }
    /// Worker-only scan. No image, media or record contents are decoded.
    public static func measure(_ groups: [(StorageCategory?, URL)], excluding privateRoots: [URL] = [], cancelled: @Sendable () -> Bool = { false }) throws -> Self {
        precondition(!Thread.isMainThread)
        let manager = FileManager.default
        var totals: [StorageCategory: Int64] = [:]
        let excluded = privateRoots.map { $0.resolvingSymlinksInPath().standardizedFileURL.path }
        var visited = Set<String>(), identities = Set<String>(), complete = true
        func walk(_ input: URL, relative: String, category: StorageCategory?) throws {
            if cancelled() { throw CancellationError() }
            let url = input.resolvingSymlinksInPath().standardizedFileURL
            guard !excluded.contains(where: { url.path == $0 || url.path.hasPrefix($0 + "/") }) else { return }
            guard visited.insert(url.path).inserted else { return }
            guard manager.fileExists(atPath: url.path) else { return }
            do {
                let values = try url.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey, .fileSizeKey])
                if values.isDirectory == true {
                    for child in try manager.contentsOfDirectory(at: url, includingPropertiesForKeys: nil) {
                        try walk(child, relative: relative.isEmpty ? child.lastPathComponent : relative + "/" + child.lastPathComponent, category: category)
                    }
                } else if values.isRegularFile == true {
                    let attributes = try manager.attributesOfItem(atPath: url.path)
                    if let device = attributes[.systemNumber] as? NSNumber, let inode = attributes[.systemFileNumber] as? NSNumber {
                        guard identities.insert("\(device):\(inode)").inserted else { return }
                    }
                    let effective: StorageCategory
                    if category == .projects && relative.contains("/results/") { effective = .layers }
                    else if category == .projects && relative.contains("/work/") { effective = .checkpoints }
                    else { effective = category ?? classifyFlightFile(relative) }
                    totals[effective, default: 0] += Int64(values.fileSize ?? 0)
                }
            } catch is CancellationError { throw CancellationError() }
            catch { complete = false }
        }
        for (category, root) in groups { try walk(root, relative: "", category: category) }
        return Self(bytes: totals, complete: complete)
    }
    public static func classifyFlightFile(_ path: String) -> StorageCategory {
        let name = URL(fileURLWithPath: path).lastPathComponent.lowercased()
        if name.hasSuffix(".review.json") { return .annotations }
        if ["jpg", "jpeg", "png"].contains(URL(fileURLWithPath: name).pathExtension) { return .clues }
        if ["mp4", "fmp4", "mov", "mkv", "ts"].contains(URL(fileURLWithPath: name).pathExtension) { return .recordings }
        if name.hasPrefix("log_") || name.hasPrefix("anr_") { return .logs }
        if name == "clues.json" { return .metadata }
        if ["json", "kmz", "kml", "geojson", "gpx"].contains(URL(fileURLWithPath: name).pathExtension) { return .tracks }
        return .other
    }
}
