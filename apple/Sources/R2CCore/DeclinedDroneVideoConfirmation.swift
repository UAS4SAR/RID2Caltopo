import Foundation

/// Remember RID-only declines, but offer confirmation once for each new local publisher.
public struct DeclinedDroneVideoConfirmation: Sendable {
    private var current: [String: Set<String>] = [:]
    private var handled: [String: Set<String>] = [:]
    public init() {}
    public mutating func update(_ sessions: [String: Set<String>]) {
        for (id, previous) in handled {
            var next = previous
            for token in previous where token.hasSuffix("|unknown") {
                let prefix = String(token.dropLast("unknown".count))
                let resolved = (sessions[id] ?? []).filter { !$0.hasSuffix("|unknown") && $0.hasPrefix(prefix) }
                if !resolved.isEmpty || (sessions[id] ?? []).isEmpty {
                    next.remove(token)
                    next.formUnion(resolved)
                }
            }
            handled[id] = next
        }
        current = sessions
    }
    public mutating func markHandled(_ remoteID: String) {
        handled[remoteID, default: []].formUnion(current[remoteID] ?? [])
    }
    public func hasNewPublisher(_ remoteID: String) -> Bool {
        let previous = handled[remoteID] ?? []
        return (current[remoteID] ?? []).contains { token in
            let designator = token.split(separator: "|", omittingEmptySubsequences: false).dropLast().joined(separator: "|")
            return !token.hasSuffix("|unknown") && !previous.contains(token)
                && !previous.contains("\(designator)|unknown")
        }
    }
}
