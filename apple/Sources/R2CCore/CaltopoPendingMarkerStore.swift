import Foundation

/// Serial diagnostic ownership journal. Callers never perform or wait for file I/O on MainActor.
public final class CaltopoPendingMarkerStore: Sendable {
    private let queue = OperationalWorkQueue(label: "org.ncssar.personal-probe.pending-marker")
    private let location: @Sendable () throws -> URL

    public init(location: @escaping @Sendable () throws -> URL = {
        try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                    appropriateFor: nil, create: false)
            .appendingPathComponent("personal-probe-pending-marker.txt")
    }) { self.location = location }

    public func load() async throws -> UUID? {
        try await queue.run { [location] in
            let url = try location()
            let text: String
            do { text = try String(contentsOf: url, encoding: .utf8) }
            catch let error as CocoaError where error.code == .fileReadNoSuchFile { return nil }
            guard let id = UUID(uuidString: text) else {
                throw CaltopoPersonalMarkerTest.Failure(message: "The pending test-marker record could not be read. It has been kept for recovery.")
            }
            return id
        }
    }

    public func save(_ id: UUID) async throws {
        try await queue.run { [location] in
            let url = try location()
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(id.uuidString.utf8).write(to: url, options: .atomic)
        }
    }

    public func clear() async throws {
        try await queue.run { [location] in
            do { try FileManager.default.removeItem(at: location()) }
            catch let error as CocoaError where error.code == .fileNoSuchFile { /* Already absent. */ }
        }
    }
}
