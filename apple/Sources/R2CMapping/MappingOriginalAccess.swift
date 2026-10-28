import R2CCore
import Foundation

public struct MappingOriginalPhoto: Identifiable, Sendable {
    public var id: String { digest }
    public let digest, name, capturedAt: String
    public let bytes: Int64
    public let url: URL
}

/// Worker-only access to verified originals. Display names never determine source paths.
public enum MappingOriginalAccess {
    public static func list(store: MappingMissionStore, missionID: String) throws -> [MappingOriginalPhoto] {
        let root = try store.directory(missionID).appendingPathComponent("originals")
        return try store.originals(missionID).compactMap { original in
            guard original.digest.count == 64, original.digest.allSatisfy({ "0123456789abcdef".contains($0) }) else { return nil }
            let url = root.appendingPathComponent(original.digest + ".jpg")
            guard let info = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]),
                  info.isRegularFile == true, info.isSymbolicLink != true, let bytes = info.fileSize, bytes > 0 else { return nil }
            let safe = !original.name.isEmpty && original.name.count <= 180 &&
                !original.name.contains(where: { $0 == "/" || $0 == "\\" || $0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }) &&
                ["jpg", "jpeg"].contains(URL(fileURLWithPath: original.name).pathExtension.lowercased())
            return MappingOriginalPhoto(digest: original.digest, name: safe ? original.name : original.digest + ".jpg",
                capturedAt: original.capturedAt, bytes: Int64(bytes), url: url)
        }.sorted { $0.name == $1.name ? $0.digest < $1.digest : $0.name < $1.name }
    }

    /// Use per-photo directories so identically named originals never overwrite each other.
    /// Copy bytes unchanged into the OS share/preview area; keep mission originals untouched.
    public static func prepare(_ photos: [MappingOriginalPhoto], cancellation: MappingCancellation? = nil) throws -> [URL] {
        let fm = FileManager.default
        let root = TemporaryWorkspace.root.appendingPathComponent("OriginalPhotoShares")
        try fm.createDirectory(at: root, withIntermediateDirectories: true)
        for old in (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            if let date = try? old.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
               date.timeIntervalSinceNow < -86400 { try? fm.removeItem(at: old) }
        }
        let directory = root.appendingPathComponent(UUID().uuidString)
        var finished = false
        defer { if !finished { try? fm.removeItem(at: directory) } }
        var urls: [URL] = []
        for photo in photos {
            try cancellation?.check()
            let target = directory.appendingPathComponent(photo.digest).appendingPathComponent(photo.name)
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try MappingFileIO.copy(photo.url, to: target) { _ in try cancellation?.check() }
            urls.append(target)
        }
        finished = true
        return urls
    }
}
