import Foundation

/// Decides which CalTopo folder id a clue POST should use after the connect full-map read.
/// Only clue uploads wait on the initial read; incremental / single-entity GETs do not.
public enum CaltopoClueTrackFolderSend {
    public static let initialReadWaitSeconds: TimeInterval = 30

    /// - Parameters:
    ///   - initialReadCompleted: true when the connect full-map read finished within the wait budget
    ///   - cachedFolderID: already-adopted or previously ensured active track folder
    ///   - ensureFolderID: create/reuse Drone Tracks folders (flight folders); used only after a completed read
    /// - Returns: folder id for the clue POST, or nil to send without `folderId` and move later
    public static func folderIDForSend(
        initialReadCompleted: Bool,
        cachedFolderID: String?,
        ensureFolderID: @Sendable () async throws -> String?
    ) async -> String? {
        if let cached = normalized(cachedFolderID) {
            return cached
        }
        guard initialReadCompleted else {
            return nil
        }
        do {
            return normalized(try await ensureFolderID())
        } catch {
            return nil
        }
    }

    /// Same decision as `folderIDForSend` when the ensure result is already available.
    public static func folderIDForSendSync(
        initialReadCompleted: Bool,
        cachedFolderID: String?,
        ensuredFolderID: String?
    ) -> String? {
        if let cached = normalized(cachedFolderID) {
            return cached
        }
        guard initialReadCompleted else {
            return nil
        }
        return normalized(ensuredFolderID)
    }

    private static func normalized(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// Latch for the connect full-map read. Clue uploads may wait; other map traffic must not.
public actor CaltopoInitialFullReadGate {
    public static let shared = CaltopoInitialFullReadGate()

    private var completed = true
    private var waiters: [UUID: CheckedContinuation<Bool, Never>] = [:]

    public init() {}

    public func begin() {
        completed = false
    }

    public func complete() {
        guard !completed else { return }
        completed = true
        let pending = Array(waiters.values)
        waiters.removeAll()
        pending.forEach { $0.resume(returning: true) }
    }

    /// Waits until complete or timeout. Returns true when the read finished in time.
    public func wait(timeoutSeconds: TimeInterval = CaltopoClueTrackFolderSend.initialReadWaitSeconds) async -> Bool {
        if completed { return true }
        let nanoseconds = UInt64(max(0, timeoutSeconds) * 1_000_000_000)
        let waiterID = UUID()
        return await withCheckedContinuation { continuation in
            if completed {
                continuation.resume(returning: true)
                return
            }
            waiters[waiterID] = continuation
            Task {
                try? await Task.sleep(nanoseconds: nanoseconds)
                await self.expire(waiterID)
            }
        }
    }

    private func expire(_ waiterID: UUID) {
        guard let continuation = waiters.removeValue(forKey: waiterID) else { return }
        continuation.resume(returning: false)
    }
}
