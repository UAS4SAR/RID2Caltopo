import Foundation

public struct AwaitingMapPreparedRow: Sendable, Identifiable {
    public var id: String { flight.id }
    public let flight: AwaitingMapFlight
    public let nearIC: Bool
    public let proximity: AwaitingMapFlightProximity?
    public let cluePhotoCount: Int
}

public struct AwaitingMapPreparedReview: Sendable {
    public let rows: [AwaitingMapPreparedRow]
    public let clueCounts: [String: Int]
    public let reminder: AwaitingMapReminder
    public let shouldRemind: Bool
    public static var empty: Self { .init(rows: [], clueCounts: [:], reminder: .init(), shouldRemind: false) }
}

/// A separate actor owns all work that scales with saved flights, track points or clues.
public actor AwaitingMapReviewWorker {
    public init() {}
    public func prepare(flights: [AwaitingMapFlight], allFlights: [AwaitingMapFlight],
                        clues: [OperationalClueRecord], icLocations: [MapCoordinate],
                        latitude: Double?, longitude: Double?, hasMap: Bool,
                        reminder: AwaitingMapReminder, now: Date = Date()) throws -> AwaitingMapPreparedReview {
        try Task.checkCancellation()
        let candidates = allFlights.map(AwaitingMapClueMatch.Candidate.init(flight:))
        var counts: [String: Int] = [:]
        for clue in clues {
            try Task.checkCancellation()
            if let id = AwaitingMapClueMatch.ownerID(clue, candidates: candidates) { counts[id, default: 0] += 1 }
        }
        var rows: [AwaitingMapPreparedRow] = []
        var eligible: Set<String> = []
        for flight in flights {
            try Task.checkCancellation()
            if !flight.finished && flight.mapID.isEmpty { eligible.insert(flight.id) }
            let proximity: AwaitingMapFlightProximity?
            if let latitude, let longitude { proximity = flight.proximity(latitude: latitude, longitude: longitude) }
            else { proximity = nil }
            rows.append(.init(flight: flight, nearIC: flight.suggested(icLocations: icLocations, now: now),
                              proximity: proximity, cluePhotoCount: counts[flight.id, default: 0]))
        }
        // Preserve chronological/input order for equal relevance.
        rows = rows.filter(\.nearIC) + rows.filter { !$0.nearIC }
        var nextReminder = reminder
        let shouldRemind = nextReminder.shouldPresent(eligibleFlightIDs: eligible, hasMap: hasMap)
        try Task.checkCancellation()
        return .init(rows: rows, clueCounts: counts, reminder: nextReminder, shouldRemind: shouldRemind)
    }
    // Test the actual actor execution context, not its method name or async signature.
    func isExecutingOnMainThread() -> Bool { Thread.isMainThread }
}
