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
    /// CalTopo Shape id returned by a successful LiveTrack→Shape archive.
    public var caltopoArchiveId: String?
    /// Dated "Drone Tracks <ddMMM>" folder title used for that archive.
    public var caltopoArchiveFolder: String?
    /// Durable confirmation that the Shape archive completed (never auto-reupload).
    public var archiveConfirmed: Bool?
    /// A Mine selection was not acknowledged by Tracker before this flight ended.
    public var ownershipReviewPending: Bool?
    /// Operator explicitly released that hold. Does not invent a pilot.
    public var ownershipReviewAuthorized: Bool?
    /// Pilot entered with Mine. Empty when the journal did not record one.
    public var recordedPilot: String?
    public var claim: String?
    public var ownershipContextKnown: Bool?
    /// Recovered without Mine / Not Mine. Retained locally and not publishable.
    public var automaticPublicationBlocked: Bool?
    public var firstTime: Date { points.first?.receivedAt ?? .distantPast }
    public var lastTime: Date { points.last?.receivedAt ?? .distantPast }
    /// First/last waypoint on the drone clock (falls back to receive time), used for clue ownership.
    public var firstBindingTime: Date { points.map(\.bindingTime).min() ?? .distantPast }
    public var lastBindingTime: Date { points.map(\.bindingTime).max() ?? .distantPast }
    public var hasArchiveConfirmation: Bool {
        TrackArchiveConfirmation.isConfirmed(
            decision: decision,
            archiveConfirmed: archiveConfirmed == true,
            hasArchiveId: !(caltopoArchiveId ?? "").isEmpty
        )
    }

    public init(id: String? = nil, remoteID: String, label: String, observations: [RidObservation], teamID: String) {
        self.id = id ?? UUID().uuidString.lowercased()
        self.remoteID = remoteID; self.label = label
        points = observations.map(CaltopoInterruptedPublicationPoint.init)
        finished = false; decision = "review"; mapID = ""; self.teamID = teamID
        caltopoArchiveId = nil; caltopoArchiveFolder = nil; archiveConfirmed = nil
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
    case ownershipReviewRequired
    public var errorDescription: String? {
        switch self {
        case .unavailable: "Saved flight history could not be read. Reconnect its storage and retry. The saved journal has been kept."
        case .ownershipReviewRequired: "This flight requires an ownership review before it can be published."
        }
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
                       flightID: String? = nil, ownershipReviewPending: Bool? = nil,
                       recordedPilot: String? = nil, claim: String? = nil, ownershipContextKnown: Bool? = nil,
                       blockAutomaticPublication: Bool = false) async throws {
        try await beginMutation()
        defer { endMutation() }
        guard let first = observations.first else { return }
        var next = entries
        if let index = next.lastIndex(where: { $0.remoteID == remoteID && !$0.finished }) {
            next[index].points = observations.map(CaltopoInterruptedPublicationPoint.init)
            next[index].label = label; next[index].finished = finished
            if let ownershipReviewPending { next[index].ownershipReviewPending = ownershipReviewPending }
            if let recordedPilot { next[index].recordedPilot = recordedPilot.isEmpty ? nil : recordedPilot }
            if let claim { next[index].claim = claim }
            if let ownershipContextKnown { next[index].ownershipContextKnown = ownershipContextKnown }
            if finished && next[index].ownershipReviewPending == true {
                next[index].decision = "review"
                next[index].publicationStarted = false
            }
            // Match Android: a connected-map bound flight that never started LiveTrack still
            // publishes through the archive path — it must not fall into the review inbox.
            if finished && next[index].decision == "bound" && next[index].publicationStarted != true {
                next[index].decision = next[index].mapID.isEmpty ? "review" : "publish"
            }
            if blockAutomaticPublication || next[index].automaticPublicationBlocked == true {
                next[index].automaticPublicationBlocked = true
                next[index].decision = "review"
                next[index].publicationStarted = false
            }
            // Live-published flights are removed here; Shape confirmation is recorded separately
            // via confirmArchive (and must not reappear as a review offer).
            if finished && ["bound", "local"].contains(next[index].decision) { next.remove(at: index) }
        } else {
            guard !finished, !next.contains(where: { $0.remoteID == remoteID && $0.firstTime == first.receivedAt }) else { return }
            var entry = AwaitingMapFlight(id: flightID, remoteID: remoteID, label: label, observations: observations, teamID: teamID)
            if !mapID.isEmpty { entry.mapID = mapID; entry.decision = "bound" }
            entry.ownershipReviewPending = ownershipReviewPending
            entry.recordedPilot = recordedPilot.flatMap { $0.isEmpty ? nil : $0 }
            entry.claim = claim
            entry.ownershipContextKnown = ownershipContextKnown
            if blockAutomaticPublication {
                entry.automaticPublicationBlocked = true
                entry.decision = "review"
                entry.publicationStarted = false
            }
            next.append(entry)
        }
        try await commit(next)
    }
    /// Explicit operator release of one held flight. Does not invent a pilot.
    /// Returns whether tracker upload of a recorded Mine pilot may proceed.
    @discardableResult
    public func authorizeOwnershipReview(id: String) async throws -> Bool {
        try await beginMutation()
        defer { endMutation() }
        var next = entries
        guard let index = next.firstIndex(where: {
            $0.id == id && $0.decision == "review" && $0.ownershipReviewPending == true && $0.automaticPublicationBlocked != true
        }) else { return false }
        let pilot = next[index].recordedPilot ?? ""
        let claim = next[index].claim ?? ""
        let known = next[index].ownershipContextKnown == true
        next[index].ownershipReviewPending = false
        next[index].ownershipReviewAuthorized = true
        try await commit(next)
        return known && !pilot.isEmpty && claim == FlightOwnershipContext.pending
    }
    public func decide(id: String, mapID: String?, teamID: String) async throws {
        try await beginMutation()
        defer { endMutation() }
        var next = entries
        guard let index = next.firstIndex(where: { $0.id == id && $0.decision == "review" }) else { return }
        guard mapID == nil || next[index].ownershipReviewPending != true else { throw AwaitingMapFlightJournalError.ownershipReviewRequired }
        guard mapID == nil || next[index].automaticPublicationBlocked != true else { throw AwaitingMapFlightJournalError.ownershipReviewRequired }
        if let mapID { guard !mapID.isEmpty, !teamID.isEmpty else { throw CaltopoLiveClientError.invalidConfiguration } }
        next[index].decision = mapID == nil ? "local" : "publish"
        next[index].mapID = mapID ?? ""; next[index].teamID = teamID
        try await commit(next)
    }


    /// Bind an unfinished empty-map review intent to the connected map so LiveTrack can start
    /// without waiting for the review inbox. Finished review offers are left alone.
    @discardableResult
    public func bindOpenReviewToConnectedMap(remoteID: String, mapID: String, teamID: String) async throws -> String? {
        try await beginMutation()
        defer { endMutation() }
        guard !mapID.isEmpty else { return nil }
        var next = entries
        guard let index = next.lastIndex(where: {
            $0.remoteID == remoteID && !$0.finished && $0.decision == "review" && $0.mapID.isEmpty && $0.ownershipReviewPending != true && $0.automaticPublicationBlocked != true
        }) else { return nil }
        next[index].mapID = mapID
        next[index].teamID = teamID
        next[index].decision = "bound"
        try await commit(next)
        return next[index].id
    }
    public func notePublication(remoteID: String, mapID: String, label: String? = nil) async throws {
        try await beginMutation()
        defer { endMutation() }
        guard !mapID.isEmpty else { return }
        var next = entries
        // Live publish to a connected map must bind an empty-map review intent captured before
        // connect; requiring mapID equality left those rows as finished review forever.
        guard let index = next.lastIndex(where: {
            $0.remoteID == remoteID && !$0.finished && $0.ownershipReviewPending != true
                && ($0.mapID == mapID || ($0.mapID.isEmpty && $0.decision == "review"))
        }) else { return }
        var changed = false
        if next[index].mapID.isEmpty {
            next[index].mapID = mapID
            next[index].decision = "bound"
            changed = true
        }
        if next[index].publicationStarted != true {
            next[index].publicationStarted = true
            changed = true
        }
        if let label {
            let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, next[index].label != trimmed {
                next[index].label = trimmed
                changed = true
            }
        }
        guard changed else { return }
        try await commit(next)
    }

    /// Records durable CalTopo Shape-archive confirmation and removes the flight from the
    /// awaiting-publication inbox. Confirmed rows are pruned (never auto-reuploaded).
    @discardableResult
    public func confirmArchive(
        remoteID: String,
        flightID: String?,
        archiveId: String,
        folderTitle: String
    ) async throws -> Bool {
        try await beginMutation()
        defer { endMutation() }
        let trimmedId = archiveId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedId.isEmpty else { return false }
        var next = entries
        let index = next.lastIndex(where: { entry in
            if let flightID, entry.id == flightID { return true }
            if entry.id == trimmedId { return true }
            return entry.remoteID == remoteID
                && (entry.decision == "review" || entry.decision == "queued"
                    || entry.decision == "publish" || entry.decision == "bound"
                    || entry.decision == "published")
        })
        guard let index else { return false }
        next[index].decision = "published"
        next[index].caltopoArchiveId = trimmedId
        next[index].caltopoArchiveFolder = folderTitle
        next[index].archiveConfirmed = true
        next[index].finished = true
        if next[index].points.count > 1, let first = next[index].points.first, let last = next[index].points.last {
            next[index].points = [first, last]
        }
        // Prune confirmed archives so stale queued/published rows cannot linger or block.
        next.removeAll {
            TrackArchiveConfirmation.isConfirmed(
                decision: $0.decision,
                archiveConfirmed: $0.archiveConfirmed == true,
                hasArchiveId: !($0.caltopoArchiveId ?? "").isEmpty
            )
        }
        try await commit(next)
        return true
    }

    /// Removes entries whose CalTopo Shape archive is already confirmed. Never deletes
    /// unpublished review/local offers.
    public func pruneConfirmedArchives() async throws {
        try await beginMutation()
        defer { endMutation() }
        let remaining = entries.filter {
            !TrackArchiveConfirmation.isConfirmed(
                decision: $0.decision,
                archiveConfirmed: $0.archiveConfirmed == true,
                hasArchiveId: !($0.caltopoArchiveId ?? "").isEmpty
            )
        }
        guard remaining.count != entries.count else { return }
        try await commit(remaining)
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
