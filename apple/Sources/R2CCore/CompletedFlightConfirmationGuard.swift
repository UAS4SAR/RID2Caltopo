import Foundation

/// A retained LIVE flag is not evidence of a new flight. Dates are receive times.
public struct CompletedFlightConfirmationGuard: Sendable {
    private struct Ended: Sendable {
        let at: Date
        let sessions: Set<String>
        var suppressionLogged = false
    }
    private var ended: [String: Ended] = [:]
    private var sessions: [String: Set<String>] = [:]

    private var lastObservedSessions: [String: Set<String>] = [:]

    public init() {}

    public mutating func updateSessions(_ current: [String: Set<String>]) {
        sessions = current
        for (id, tokens) in current where !tokens.isEmpty { lastObservedSessions[id] = tokens }
    }

    public mutating func end(remoteID: String, at: Date, log: (String) -> Void = { _ in }) {
        let retired = (ended[remoteID]?.sessions ?? []).union(lastObservedSessions[remoteID] ?? [])
        ended[remoteID] = Ended(at: at, sessions: retired)
        log("Confirmation retired remoteId=\(remoteID) endedAt=\(at.timeIntervalSince1970) publisherSessions=\(retired.sorted())")
    }

    public mutating func allows(remoteID: String, receivedAt: Date? = nil, log: (String) -> Void = { _ in }) -> Bool {
        guard var completion = ended[remoteID] else { return true }
        let freshAircraft = receivedAt.map { $0 > completion.at } ?? false
        let newPublisher = (sessions[remoteID] ?? []).contains { token in
            let designator = token.split(separator: "|", omittingEmptySubsequences: false).dropLast().joined(separator: "|")
            return !token.hasSuffix("|unknown") && !completion.sessions.contains(token)
                && !completion.sessions.contains("\(designator)|unknown")
        }
        guard freshAircraft || newPublisher else {
            if !completion.suppressionLogged {
                completion.suppressionLogged = true
                ended[remoteID] = completion
                log("Confirmation suppressed remoteId=\(remoteID) endedAt=\(completion.at.timeIntervalSince1970) aircraftReceivedAt=\(receivedAt?.timeIntervalSince1970 ?? -1) publisherSessions=\((sessions[remoteID] ?? []).sorted()) reason=no_new_flight_evidence")
            }
            return false
        }
        log("Confirmation rearmed remoteId=\(remoteID) aircraftReceivedAt=\(receivedAt?.timeIntervalSince1970 ?? -1) publisherSessions=\((sessions[remoteID] ?? []).sorted()) reason=\(freshAircraft ? "fresh_aircraft" : "new_publisher")")
        ended.removeValue(forKey: remoteID)
        return true
    }
}
