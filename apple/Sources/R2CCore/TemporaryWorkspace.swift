import Foundation

/// Scratch files have an invocation identity. Retained originals/checkpoints never go here.
public enum TemporaryWorkspace {
    private static let invocation = UUID().uuidString
    public static var root: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("RID2Caltopo-Temporary/" + invocation, isDirectory: true)
    }
    public static func directory(_ kind: String) throws -> URL {
        precondition(!Thread.isMainThread)
        precondition(!kind.isEmpty && !kind.contains("/") && kind != "." && kind != "..")
        let url = root.appendingPathComponent(kind, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
    @discardableResult public static func cleanAbandoned() throws -> Int64 {
        precondition(!Thread.isMainThread)
        let fm = FileManager.default
        let parent = root.deletingLastPathComponent()
        guard fm.fileExists(atPath: parent.path) else { return 0 }
        var bytes: Int64 = 0
        for item in try fm.contentsOfDirectory(at: parent, includingPropertiesForKeys: nil) where item.lastPathComponent != invocation {
            let size = try StorageCategoryReport.measure([(.temporary, item)]).total
            try fm.removeItem(at: item); bytes += size
        }
        return bytes
    }
}
