import Foundation

/// Keeps one replaceable pending report per aircraft, with no historical replay.
actor LatestPositionReports {
    private struct Pending {
        let send: @Sendable () async throws -> Void
        let completion: CheckedContinuation<Bool, Error>
    }
    private struct Worker {
        let id: UUID
        let task: Task<Void, Never>
    }
    private let interval: Duration
    private let clock = ContinuousClock()
    private var cancelling: Set<String> = []
    private var pending: [String: Pending] = [:]
    private var workers: [String: Worker] = [:]
    private var readyAt: [String: ContinuousClock.Instant] = [:]

    init(interval: Duration = .seconds(5)) { self.interval = interval }

    func submit(key: String, send: @escaping @Sendable () async throws -> Void) async throws -> Bool {
        guard !cancelling.contains(key) else { return false }
        return try await withCheckedThrowingContinuation { completion in
            pending.removeValue(forKey: key)?.completion.resume(returning: false)
            pending[key] = Pending(send: send, completion: completion)
            if workers[key] == nil {
                let id = UUID()
                let task = Task { await self.drain(key: key, id: id) }
                workers[key] = Worker(id: id, task: task)
            }
        }
    }

    func cancel(key: String) async {
        cancelling.insert(key)
        defer { cancelling.remove(key) }
        pending.removeValue(forKey: key)?.completion.resume(returning: false)
        guard let worker = workers[key] else { return }
        worker.task.cancel()
        await worker.task.value
    }

    var pendingCountForTesting: Int { pending.count }

    func cancelAll() async {
        for key in Array(workers.keys) { await cancel(key: key) }
    }

    private func drain(key: String, id: UUID) async {
        defer { if workers[key]?.id == id { workers.removeValue(forKey: key) } }
        while pending[key] != nil && !Task.isCancelled {
            if let deadline = readyAt[key] {
                do { try await clock.sleep(until: deadline) } catch { return }
            }
            guard !Task.isCancelled, let report = pending.removeValue(forKey: key) else { return }
            do {
                try await report.send()
                report.completion.resume(returning: true)
            } catch {
                report.completion.resume(throwing: error)
            }
            // No sleep in the network worker, no retry of the stale sample, no catch-up burst.
            readyAt[key] = clock.now.advanced(by: interval)
        }
    }
}
