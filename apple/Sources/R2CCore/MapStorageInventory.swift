import Foundation

public struct MapStorageInventory: Sendable {
    public let bytes: [Int64]
    public let complete: Bool

    /// Worker-only. Canonical paths prevent overlapping roots and aliases counting twice.
    public static func measure(_ groups: [[URL]], cancelled: @Sendable () -> Bool = { false }) throws -> Self {
        precondition(!Thread.isMainThread, "Storage inventory must run on a worker")
        let manager = FileManager.default
        var visited = Set<String>(), identities = Set<String>()
        var complete = true
        func measure(_ input: URL) throws -> Int64 {
            if cancelled() { throw CancellationError() }
            let url = input.resolvingSymlinksInPath().standardizedFileURL
            guard visited.insert(url.path).inserted else { return 0 }
            var directory: ObjCBool = false
            guard manager.fileExists(atPath: url.path, isDirectory: &directory) else {
                if (try? manager.attributesOfItem(atPath: input.path)[.type]) as? FileAttributeType == .typeSymbolicLink { complete = false }
                return 0
            }
            if directory.boolValue {
                do {
                    let children = try manager.contentsOfDirectory(at: url, includingPropertiesForKeys: [.fileSizeKey])
                    return try children.reduce(0) { try $0 + measure($1) }
                } catch is CancellationError { throw CancellationError() }
                catch { complete = false; return 0 }
            }
            do {
                let attributes = try manager.attributesOfItem(atPath: url.path)
                if let device = attributes[.systemNumber] as? NSNumber, let inode = attributes[.systemFileNumber] as? NSNumber {
                    guard identities.insert("\(device):\(inode)").inserted else { return 0 }
                }
                return Int64(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
            }
            catch { complete = false; return 0 }
        }
        let bytes = try groups.map { try $0.reduce(0) { try $0 + measure($1) } }
        return Self(bytes: bytes, complete: complete)
    }
}
