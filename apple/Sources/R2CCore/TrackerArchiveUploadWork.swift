import Foundation

/// Joins concurrent archive/replay requests for one file; failed work can be retried later.
public actor TrackerArchiveUploadWork<Result: Sendable> {
    private var running: [String: Task<Result, Never>] = [:]
    public init() {}
    public func run(key: String, operation: @escaping @Sendable () async -> Result) async -> Result {
        if let task = running[key] { return await task.value }
        let task = Task { await operation() }
        running[key] = task
        let result = await task.value
        running.removeValue(forKey: key)
        return result
    }
}
