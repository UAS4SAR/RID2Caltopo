import Foundation
import CryptoKit

/// What we know about a lidar file that finished downloading: written after the atomic rename.
public struct OperationalRetainedSourceRecord: Sendable, Equatable {
    public let url: String
    public let bytes: Int64
    public let sha256: String

    public init(url: String, bytes: Int64, sha256: String) {
        self.url = url; self.bytes = bytes; self.sha256 = sha256
    }

    public func encode() -> String { "v1\n\(url)\n\(bytes)\n\(sha256)\n" }

    public static func decode(_ text: String?) -> OperationalRetainedSourceRecord? {
        guard var text else { return nil }
        while text.hasSuffix("\n") { text.removeLast() }
        let lines = text.components(separatedBy: "\n")
        guard lines.count == 4, lines[0] == "v1", let bytes = Int64(lines[2]),
              lines[3].count == 64, lines[3].allSatisfy({ "0123456789abcdef".contains($0) }) else { return nil }
        return .init(url: lines[1], bytes: bytes, sha256: lines[3])
    }
}

/// Pure rules for keeping lidar files between AOL attempts. Android mirrors this in
/// SurfaceSourceReuse.kt; keep the two in step.
public enum OperationalSurfaceSourceReuse {
    public static let workPrefix = "aol-prep-"
    public static let partialSuffix = ".part"
    private static let maximumSourceBytes: Int64 = 1_000_000_000

    /// Same AOL selection (bounds and exact source list) gives the same key; anything else differs.
    public static func workKey(_ plan: OperationalSurfacePreparationPlan) -> String {
        let b = plan.bounds
        var canonical = "v1|" + String(format: "%.7f,%.7f,%.7f,%.7f", b.west, b.south, b.east, b.north)
        for source in plan.sources.sorted(by: { $0.url < $1.url }) { canonical += "|\(source.url),\(source.bytes)" }
        return String(SHA256.hash(data: Data(canonical.utf8)).map { String(format: "%02x", $0) }.joined().prefix(32))
    }

    public static func workDirectoryName(_ plan: OperationalSurfacePreparationPlan) -> String { workPrefix + workKey(plan) }

    /// A kept file is reused only if its record matches this source, the file on disk is exactly the
    /// size the server declared when it finished downloading, and its SHA-256 still matches.
    public static func reusable(source: OperationalSurfaceSource, record: OperationalRetainedSourceRecord?, fileLength: Int64?, computedSHA256: String?) -> Bool {
        guard let record, record.url == source.url, (1 ... maximumSourceBytes).contains(record.bytes),
              fileLength == record.bytes, let computedSHA256 else { return false }
        return computedSHA256 == record.sha256
    }

    /// Work folders to delete: every AOL work folder except `keepName` (nil deletes all).
    public static func staleWorkDirectories(_ names: [String], keepName: String?) -> [String] {
        names.filter { $0.hasPrefix(workPrefix) && $0 != keepName }
    }

    /// Deletes AOL work folders (raw lidar, partial files, records) directly under `root`, except
    /// `keepName`; other folders are left alone. Returns how many were deleted. Call off the main thread.
    @discardableResult
    public static func deleteWorkDirectories(in root: URL, keepName: String? = nil) -> Int {
        let fm = FileManager.default
        let names = (try? fm.contentsOfDirectory(atPath: root.path)) ?? []
        var deleted = 0
        for name in staleWorkDirectories(names, keepName: keepName) {
            let url = root.appendingPathComponent(name)
            var isDirectory: ObjCBool = false
            guard fm.fileExists(atPath: url.path, isDirectory: &isDirectory), isDirectory.boolValue else { continue }
            if (try? fm.removeItem(at: url)) != nil { deleted += 1 }
        }
        return deleted
    }
}
