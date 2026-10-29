import CryptoKit
import Foundation

/// Stable flight identity: a UUID derived from the aircraft's canonical RID and the flight start time
/// (receive time of the track's first accepted observation, in ms). It is the awaiting-map journal id
/// (and so the CalTopo live-track id), the flightID of clue bindings and the archive's
/// `r2c_flight_id`, so all of them agree for the life of the flight and after a relaunch. Android
/// builds the identical id (RidFlightId.kt) and writes it under the same `r2c_flight_id` key.
public enum RidFlightID {
    public static func make(aircraftID: String, startedAt: Date) -> String {
        let milliseconds = Int64((startedAt.timeIntervalSince1970 * 1_000).rounded())
        let name = "\(RidTrackStore.canonicalAircraftID(aircraftID))|\(milliseconds)"
        var bytes = Array(SHA256.hash(data: Data(name.utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50 // name-based UUID
        bytes[8] = (bytes[8] & 0x3F) | 0x80 // RFC 4122 variant
        let uuid = UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                               bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
        return uuid.uuidString.lowercased()
    }
}

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
    /// First/last waypoint on the drone clock (falls back to receive time), used for clue ownership.
    public var firstBindingTime: Date { points.map(\.bindingTime).min() ?? .distantPast }
    public var lastBindingTime: Date { points.map(\.bindingTime).max() ?? .distantPast }

    public init(id: String? = nil, remoteID: String, label: String, observations: [RidObservation], teamID: String) {
        self.id = id ?? UUID().uuidString.lowercased()
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

public enum AwaitingMapFlightJournalError: Error, LocalizedError, Sendable {
    case unavailable
    public var errorDescription: String? {
        "Saved flight history could not be read. Reconnect its storage and retry. The saved journal has been kept."
    }
}

/// Geometry and journal mutations stay on this actor; durable writes precede publication.
public actor AwaitingMapFlightJournal {
    /// Use only from a worker. Every reader distinguishes absent data from failed recovery.
    public nonisolated static func readSavedEntries(fileURL: URL) throws -> [AwaitingMapFlight] {
        let data: Data
        do { data = try Data(contentsOf: fileURL) }
        catch let error as CocoaError where error.code == .fileReadNoSuchFile { return [] }
        return try JSONDecoder().decode([AwaitingMapFlight].self, from: data)
    }
    public private(set) var entries: [AwaitingMapFlight]
    public private(set) var loadError: String?
    private let fileURL: URL
    private let disk = OperationalWorkQueue(label: "org.ncssar.awaiting-map.disk")
    private enum LoadResult: Sendable { case loaded([AwaitingMapFlight]), failed }
    private var loading: Task<LoadResult, Never>?
    private var loaded = false
    private var retrying = false
    private var loadGeneration = 0
    private var mutationInProgress = false
    private var mutationWaiters: [CheckedContinuation<Void, Never>] = []
    func isExecutingOnMainThread() -> Bool { Thread.isMainThread }
    public init(fileURL: URL) {
        self.fileURL = fileURL
        entries = []
    }
    public func ready() async {
        guard !loaded else { return }
        if loading == nil {
            let disk = self.disk, fileURL = self.fileURL
            loading = Task {
                do {
                    return .loaded(try await disk.run {
                        return try Self.readSavedEntries(fileURL: fileURL).map { value in
                            var result = value; result.finished = true
                            if result.decision == "bound" && result.publicationStarted != true { result.decision = "review" }
                            return result
                        }
                    })
                } catch { return .failed }
            }
        }
        let generation = loadGeneration
        let result = await loading!.value
        guard generation == loadGeneration else { await ready(); return }
        guard !loaded else { return }
        switch result {
        case .loaded(let recovered): entries = recovered; loadError = nil
        case .failed: loadError = AwaitingMapFlightJournalError.unavailable.localizedDescription
        }
        loaded = true
    }
    public func retryLoading() async {
        guard loadError != nil, !retrying else { return }
        retrying = true
        defer { retrying = false }
        loadGeneration &+= 1
        loaded = false; loading = nil
        await ready()
    }
    public static func loaded(fileURL: URL) async -> AwaitingMapFlightJournal {
        let journal = AwaitingMapFlightJournal(fileURL: fileURL)
        await journal.ready()
        return journal
    }
    private func beginMutation() async throws {
        await ready()
        try Task.checkCancellation()
        guard loadError == nil else { throw AwaitingMapFlightJournalError.unavailable }
        if mutationInProgress {
            await withCheckedContinuation { mutationWaiters.append($0) }
        } else { mutationInProgress = true }
        if Task.isCancelled { endMutation(); throw CancellationError() }
    }
    private func endMutation() {
        if mutationWaiters.isEmpty { mutationInProgress = false }
        else { mutationWaiters.removeFirst().resume() }
    }
    /// `flightID` (RidFlightID) becomes the id of a newly created entry; an open entry keeps its id.
    public func record(remoteID: String, label: String, observations: [RidObservation], mapID: String, teamID: String, finished: Bool,
                       flightID: String? = nil) async throws {
        try await beginMutation()
        defer { endMutation() }
        guard let first = observations.first else { return }
        var next = entries
        if let index = next.lastIndex(where: { $0.remoteID == remoteID && !$0.finished }) {
            next[index].points = observations.map(CaltopoInterruptedPublicationPoint.init)
            next[index].label = label; next[index].finished = finished
            if finished && next[index].decision == "bound" && next[index].publicationStarted != true { next[index].decision = "review" }
            if finished && ["bound", "local"].contains(next[index].decision) { next.remove(at: index) }
        } else {
            guard !finished, !next.contains(where: { $0.remoteID == remoteID && $0.firstTime == first.receivedAt }) else { return }
            var entry = AwaitingMapFlight(id: flightID, remoteID: remoteID, label: label, observations: observations, teamID: teamID)
            if !mapID.isEmpty { entry.mapID = mapID; entry.decision = "bound" }
            next.append(entry)
        }
        try await commit(next)
    }
    public func decide(id: String, mapID: String?, teamID: String) async throws {
        try await beginMutation()
        defer { endMutation() }
        var next = entries
        guard let index = next.firstIndex(where: { $0.id == id && $0.decision == "review" }) else { return }
        if let mapID { guard !mapID.isEmpty, !teamID.isEmpty else { throw CaltopoLiveClientError.invalidConfiguration } }
        next[index].decision = mapID == nil ? "local" : "publish"
        next[index].mapID = mapID ?? ""; next[index].teamID = teamID
        try await commit(next)
    }
    public func notePublication(remoteID: String, mapID: String) async throws {
        try await beginMutation()
        defer { endMutation() }
        var next = entries
        guard let index = next.lastIndex(where: { $0.remoteID == remoteID && !$0.finished && $0.mapID == mapID }), next[index].publicationStarted != true else { return }
        next[index].publicationStarted = true
        try await commit(next)
    }
    public func discard(remoteID: String, startedAt: Date) async throws {
        try await beginMutation()
        defer { endMutation() }
        try await commit(entries.filter { !($0.remoteID == remoteID && $0.firstTime == startedAt && ["review", "bound", "local"].contains($0.decision)) })
    }
    /// Removes exactly one undecided or local entry. Returns false when nothing matched.
    @discardableResult
    public func discard(id: String) async throws -> Bool {
        try await beginMutation()
        defer { endMutation() }
        guard entries.contains(where: { $0.id == id && ["review", "bound", "local"].contains($0.decision) }) else { return false }
        try await commit(entries.filter { $0.id != id })
        return true
    }
    public func markQueued(id: String) async throws {
        try await beginMutation()
        defer { endMutation() }
        var next = entries
        if let index = next.firstIndex(where: { $0.id == id }) {
            next[index].decision = "queued"
            // Full geometry is now owned by the interrupted-publication journal.
            if let first = next[index].points.first, let last = next[index].points.last { next[index].points = [first, last] }
        }
        try await commit(next)
    }
    private func commit(_ next: [AwaitingMapFlight]) async throws {
        let url = fileURL
        try await disk.run {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(next).write(to: url, options: .atomic)
        }
        // Only expose consent/publication decisions after their write succeeds.
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
