import Foundation

/// Shared serial CPU worker for full diagnostic map responses, separate from the file journal.
public final class CaltopoDiagnosticResponses: Sendable {
    public static let shared = CaltopoDiagnosticResponses()
    private let worker = OperationalWorkQueue(label: "org.ncssar.caltopo.diagnostic-responses")
    private let beforeDecode: @Sendable () -> Void
    public init() { beforeDecode = {} }
    // The hook allows a regression to block the actual worker before real decoding.
    init(beforeDecode: @escaping @Sendable () -> Void) { self.beforeDecode = beforeDecode }

    public func result(code: Int, data: Data) async throws -> CaltopoPersonalProbe.Result {
        try Task.checkCancellation()
        let decoded = await worker.perform { [beforeDecode] in
            beforeDecode()
            return CaltopoPersonalProbe.result(code: code, data: data)
        }
        try Task.checkCancellation()
        return decoded
    }

    public func presence(_ reply: CaltopoPersonalMarkerTest.Reply, id: UUID) async throws -> CaltopoPersonalMarkerTest.Presence {
        try Task.checkCancellation()
        let decoded = try await worker.run { [beforeDecode] in
            beforeDecode()
            return try CaltopoPersonalMarkerTest.presence(reply, id: id)
        }
        try Task.checkCancellation()
        return decoded
    }
}
