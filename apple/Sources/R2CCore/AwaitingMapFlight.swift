import Foundation

public struct AwaitingMapFlight: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let remoteID: String
    public var label: String
    public var points: [CaltopoInterruptedPublicationPoint]
    public var finished: Bool
    public var decision: String
    public var mapID: String
    public var teamID: String
    public var publicationStarted: Bool?
    public var firstTime: Date { points.first?.receivedAt ?? .distantPast }
    public var lastTime: Date { points.last?.receivedAt ?? .distantPast }

    public init(remoteID: String, label: String, observations: [RidObservation], teamID: String) {
        id = UUID().uuidString.lowercased()
        self.remoteID = remoteID; self.label = label
        points = observations.map(CaltopoInterruptedPublicationPoint.init)
        finished = false; decision = "review"; mapID = ""; self.teamID = teamID
    }
    public func suggested(icLocations: [MapCoordinate], now: Date = Date()) -> Bool {
        guard (0...86_400).contains(now.timeIntervalSince(lastTime)) else { return false }
        return icLocations.filter(\.isValid).contains { ic in
            points.contains { point in
                guard let position = RidGeometry.relativePosition(fromLatitude: point.latitude, longitude: point.longitude,
                    toLatitude: ic.latitude, longitude: ic.longitude) else { return false }
                return position.distanceMeters <= 16_093.44
            }
        }
    }
    public var publication: CaltopoInterruptedPublication {
        CaltopoInterruptedPublication(mapID: mapID, remoteID: remoteID, liveTrackID: id,
            label: CaltopoTrackLabel.androidCompatible(baseLabel: label, firstWaypointAt: firstTime),
            observations: points.map { $0.observation(remoteID: remoteID) })
    }
}

/// Serialized by the owning app model. Mutations commit before publication is allowed.
@MainActor
public final class AwaitingMapFlightJournal {
    public private(set) var entries: [AwaitingMapFlight]
    private let fileURL: URL
    public init(fileURL: URL) {
        self.fileURL = fileURL
        entries = (try? Data(contentsOf: fileURL)).flatMap { try? JSONDecoder().decode([AwaitingMapFlight].self, from: $0) } ?? []
        // In-memory flight sessions do not survive process death; recovered records are completed offers.
        entries = entries.map { value in
            var result = value; result.finished = true
            if result.decision == "bound" && result.publicationStarted != true { result.decision = "review" }
            return result
        }
    }
    public func record(remoteID: String, label: String, observations: [RidObservation], mapID: String, teamID: String, finished: Bool) throws {
        guard let first = observations.first else { return }
        var next = entries
        if let index = next.lastIndex(where: { $0.remoteID == remoteID && !$0.finished }) {
            next[index].points = observations.map(CaltopoInterruptedPublicationPoint.init)
            next[index].label = label; next[index].finished = finished
            if finished && next[index].decision == "bound" && next[index].publicationStarted != true { next[index].decision = "review" }
            if finished && ["bound", "local"].contains(next[index].decision) { next.remove(at: index) }
        } else {
            guard !finished, !next.contains(where: { $0.remoteID == remoteID && $0.firstTime == first.receivedAt }) else { return }
            var entry = AwaitingMapFlight(remoteID: remoteID, label: label, observations: observations, teamID: teamID)
            if !mapID.isEmpty { entry.mapID = mapID; entry.decision = "bound" }
            next.append(entry)
        }
        try commit(next)
    }
    public func decide(id: String, mapID: String?, teamID: String) throws {
        var next = entries
        guard let index = next.firstIndex(where: { $0.id == id && $0.decision == "review" }) else { return }
        if let mapID { guard !mapID.isEmpty, !teamID.isEmpty else { throw CaltopoLiveClientError.invalidConfiguration } }
        next[index].decision = mapID == nil ? "local" : "publish"
        next[index].mapID = mapID ?? ""; next[index].teamID = teamID
        try commit(next)
    }
    public func notePublication(remoteID: String, mapID: String) throws {
        var next = entries
        guard let index = next.lastIndex(where: { $0.remoteID == remoteID && !$0.finished && $0.mapID == mapID }), next[index].publicationStarted != true else { return }
        next[index].publicationStarted = true
        try commit(next)
    }
    public func discard(remoteID: String, startedAt: Date) throws {
        try commit(entries.filter { !($0.remoteID == remoteID && $0.firstTime == startedAt && ["review", "bound", "local"].contains($0.decision)) })
    }
    /// Removes exactly one undecided or local entry. Returns false when nothing matched.
    @discardableResult
    public func discard(id: String) throws -> Bool {
        guard entries.contains(where: { $0.id == id && ["review", "bound", "local"].contains($0.decision) }) else { return false }
        try commit(entries.filter { $0.id != id })
        return true
    }
    public func markQueued(id: String) throws {
        var next = entries
        if let index = next.firstIndex(where: { $0.id == id }) {
            next[index].decision = "queued"
            // Full geometry is now owned by the interrupted-publication journal.
            if let first = next[index].points.first, let last = next[index].points.last { next[index].points = [first, last] }
        }
        try commit(next)
    }
    private func commit(_ next: [AwaitingMapFlight]) throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(next).write(to: fileURL, options: .atomic)
        entries = next
    }
}

/// Session-scoped reminder; dismissing it never changes publication consent.
public struct AwaitingMapReminder: Sendable {
    private var reminded: Set<String> = []
    public init() {}
    public mutating func shouldPresent(eligibleFlightIDs: Set<String>, hasMap: Bool) -> Bool {
        guard !hasMap else { return false }
        let unseen = eligibleFlightIDs.subtracting(reminded)
        reminded.formUnion(eligibleFlightIDs)
        return !unseen.isEmpty
    }
}
