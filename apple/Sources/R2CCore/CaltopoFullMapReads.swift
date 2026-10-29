import Foundation

/// Full reads of the same server/map share one cooldown across accounts and clients.
/// Cooldown starts on completion, conservatively ensuring at least 90 seconds
/// between network attempts even if authorization or response delivery is slow.
public actor CaltopoFullMapReads {
    public static let shared = CaltopoFullMapReads()
    public static let minimumInterval: TimeInterval = 90
    private let interval: TimeInterval
    private let now: @Sendable () -> TimeInterval
    private let sleep: @Sendable (TimeInterval) async throws -> Void
    private struct State { var busy = false; var lastFinished: TimeInterval? }
    private var maps: [String: State] = [:]

    public init(interval: TimeInterval = minimumInterval,
                now: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
                sleep: @escaping @Sendable (TimeInterval) async throws -> Void = { try await Task.sleep(for: .seconds($0)) }) {
        self.interval = max(0, interval); self.now = now; self.sleep = sleep
    }
    public func perform<Value: Sendable>(mapURL: URL, _ operation: @Sendable () async throws -> Value) async throws -> Value {
        let key = Self.mapKey(mapURL)
        maps = maps.filter { $0.value.busy || $0.value.lastFinished.map { now() - $0 < interval } == true }
        var announcedWait = false
        while true {
            try Task.checkCancellation()
            let state = maps[key] ?? State()
            let remaining = state.lastFinished.map { max(0, interval - (now() - $0)) } ?? 0
            if !state.busy && remaining <= 0 { maps[key] = State(busy: true); break }
            if !announcedWait {
                let milliseconds = Int((state.busy ? 0.25 : remaining) * 1_000)
                CaltopoPersonalAccountConnection.logState("map open waiting full-read gate \(milliseconds)ms")
                announcedWait = true
            }
            try await sleep(state.busy ? 0.25 : remaining)
        }
        defer { maps[key] = State(lastFinished: now()) }
        try Task.checkCancellation()
        return try await operation()
    }
    public nonisolated static func mapKey(_ url: URL) -> String {
        precondition(isFullMapRead(method: "GET", path: url.path))
        let port = url.port.flatMap { $0 == 443 ? nil : ":\($0)" } ?? ""
        let mapID = url.path.split(separator: "/")[3].uppercased()
        return "https://\(url.host!.lowercased())\(port)/\(mapID)"
    }
    public nonisolated static func isFullMapRead(method: String, path: String) -> Bool {
        method == "GET" && path.range(of: "^/api/v1/map/[A-Za-z0-9]{3,16}/since/0$", options: .regularExpression) != nil
    }
}
