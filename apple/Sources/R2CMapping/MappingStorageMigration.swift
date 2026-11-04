import Foundation

/// One-shot Mapping storage layout check mirrored from Android's archive relocate.
/// Apple already stores mapping under Application Support; this still emits exactly
/// one compact outcome line per process after the diagnostic log is ready.
public enum MappingStorageMigration {
    public static let markerName = ".mapping-repo-migration-v1-done"
    private static let lock = NSLock()
    // Locked by [lock]; Swift 6 requires an explicit unsafe annotation for mutable statics.
    nonisolated(unsafe) private static var emitted = false

    public enum Outcome: String, Sendable {
        case alreadyMigrated
        case nothingToMigrate
        case migrated
        case failed
    }

    public static func outcomeLine(
        _ outcome: Outcome,
        files: Int = 0,
        bytes: Int64 = 0,
        failureReason: String? = nil
    ) -> String {
        if let failureReason, !failureReason.isEmpty {
            return "Mapping storage: \(failureReason)"
        }
        switch outcome {
        case .alreadyMigrated:
            return "Mapping storage: already migrated"
        case .nothingToMigrate:
            return "Mapping storage: nothing to migrate"
        case .migrated:
            let mb = (bytes + 512 * 1024) / (1024 * 1024)
            return "Mapping storage: migrated \(files) items (\(mb) MB)"
        case .failed:
            return "Mapping storage: failed"
        }
    }

    /// Apple already uses Application Support mapping roots; record the check once.
    public static func evaluate(supportRoot: URL) -> (Outcome, String) {
        precondition(!Thread.isMainThread)
        let fm = FileManager.default
        let marker = supportRoot.appendingPathComponent("mapping-storage-stats/\(markerName)")
        if fm.fileExists(atPath: marker.path) {
            return (.alreadyMigrated, outcomeLine(.alreadyMigrated))
        }
        try? fm.createDirectory(
            at: marker.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let payload = Data("{\"version\":1,\"platform\":\"apple\"}".utf8)
        try? payload.write(to: marker, options: .atomic)
        return (.nothingToMigrate, outcomeLine(.nothingToMigrate))
    }

    /// Emit at most once per process. Caller must invoke after the diagnostic log is open.
    public static func emitOnce(_ line: String, log: (String) -> Void) {
        lock.lock()
        let first = !emitted
        if first { emitted = true }
        lock.unlock()
        guard first else { return }
        log(line)
    }

    public static func resetForTests() {
        lock.lock(); emitted = false; lock.unlock()
    }
}
