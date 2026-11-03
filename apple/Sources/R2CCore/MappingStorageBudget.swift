import Foundation

/// Free-space helpers for mapping IO workers only.
/// Callers must not invoke these from the main thread; volume capacity queries are filesystem IO.
public enum MappingStorageBudget {
    public static let intakeReserveBytes: Int64 = 256 * 1024 * 1024
    public static let reconstructReserveBytes: Int64 = 512 * 1024 * 1024

    public static func gb(_ bytes: Int64) -> String {
        String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), Double(bytes) / 1_000_000_000.0)
    }

    public static func message(bucket: String, label: String, needBytes: Int64, freeBytes: Int64, reserveBytes: Int64) -> String {
        "\(bucket) (\(label)): need \(gb(needBytes)) GB, free \(gb(freeBytes)) GB, reserve \(gb(reserveBytes)) GB"
    }

    /// Volume label for operator-facing storage errors.
    public static func label(_ path: URL) -> String {
        let resolved = path.standardizedFileURL
        if let values = try? resolved.resourceValues(forKeys: [.volumeLocalizedNameKey, .volumeNameKey, .volumeIsRootFileSystemKey, .volumeIsRemovableKey]),
           values.volumeIsRootFileSystem == true, values.volumeIsRemovable != true {
            return "Tablet"
        }
        if let name = (try? resolved.resourceValues(forKeys: [.volumeLocalizedNameKey]).volumeLocalizedName)?
            .trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
            let tag = String(name.filter(\.isLetter).prefix(4)).uppercased()
            if !tag.isEmpty { return name == "Macintosh HD" || name.lowercased().contains("ipados") ? "Tablet" : "SD \(tag)" }
            return name
        }
        if let name = (try? resolved.resourceValues(forKeys: [.volumeNameKey]).volumeName)?
            .trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
            let tag = String(name.filter { $0.isLetter || $0.isNumber }.prefix(4)).uppercased()
            return tag.isEmpty ? "Tablet" : "SD \(tag)"
        }
        return labelFromPath(resolved.path)
    }

    /// Resolve to an existing directory on the same volume.
    /// Allows a not-yet-created child of an existing parent; never stats a missing path
    /// (that returns 0 and was misread as "full").
    public static func existingDirectory(_ path: URL) throws -> URL {
        let fm = FileManager.default
        let resolved = path.standardizedFileURL
        var isDir: ObjCBool = false
        if fm.fileExists(atPath: resolved.path, isDirectory: &isDir) {
            if isDir.boolValue { return resolved }
            let parent = resolved.deletingLastPathComponent()
            if fm.fileExists(atPath: parent.path, isDirectory: &isDir), isDir.boolValue { return parent }
        }
        let parent = resolved.deletingLastPathComponent()
        if fm.fileExists(atPath: parent.path, isDirectory: &isDir), isDir.boolValue { return parent }
        throw MappingMission.Failure(
            "Mapping storage path does not exist (programming error); cannot measure free space: \(resolved.path)"
        )
    }

    public static func freeBytes(_ path: URL) throws -> Int64 {
        checkOffMain()
        let directory = try existingDirectory(path)
        if let available = try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage {
            return available
        }
        let attributes = try FileManager.default.attributesOfFileSystem(forPath: directory.path)
        return (attributes[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
    }

    public static func requireFree(
        _ path: URL,
        needBytes: Int64,
        reserveBytes: Int64 = intakeReserveBytes,
        bucket: String
    ) throws {
        checkOffMain()
        let free = try freeBytes(path)
        let volume = label(path)
        if free - needBytes < reserveBytes {
            throw MappingMission.Failure(message(bucket: bucket, label: volume, needBytes: needBytes, freeBytes: free, reserveBytes: reserveBytes))
        }
    }

    public static func checkExisting(
        _ path: URL,
        reserveBytes: Int64 = intakeReserveBytes,
        bucket: String = "Mapping work space"
    ) throws {
        try requireFree(path, needBytes: 0, reserveBytes: reserveBytes, bucket: bucket)
    }

    private static func labelFromPath(_ path: String) -> String {
        let parts = path.split(separator: "/").map(String.init)
        if let idx = parts.firstIndex(of: "Volumes"), idx + 1 < parts.count {
            let tag = String(parts[idx + 1].filter { $0.isLetter || $0.isNumber }.prefix(4)).uppercased()
            if !tag.isEmpty { return "SD \(tag)" }
        }
        return "Tablet"
    }

    private static func checkOffMain() {
        precondition(!Thread.isMainThread, "Mapping storage free-space checks belong on an IO worker.")
    }
}
