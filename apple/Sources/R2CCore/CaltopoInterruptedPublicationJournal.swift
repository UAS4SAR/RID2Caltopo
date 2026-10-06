import Foundation

public struct CaltopoInterruptedPublicationPoint: Codable, Sendable, Equatable {
    public let source: RidObservation.Source
    public let receivedAt: Date
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    /// Drone-provided waypoint time (RID timestamp or stream clock); nil in older journals.
    public let droneTime: Date?

    /// Clock used for clue binding and ownership: the drone's time when known.
    public var bindingTime: Date { droneTime ?? receivedAt }

    public init(observation: RidObservation) {
        source = observation.source
        receivedAt = observation.receivedAt
        latitude = observation.latitude
        longitude = observation.longitude
        altitudeMeters = observation.altitudeMeters
        droneTime = observation.droneTimestamp
    }

    public func observation(remoteID: String) -> RidObservation {
        RidObservation(
            source: source,
            aircraftId: remoteID,
            receivedAt: receivedAt,
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: altitudeMeters,
            droneTimestamp: droneTime
        )
    }
}

public struct CaltopoInterruptedPublication: Codable, Sendable, Equatable, Identifiable {
    public var id: String { liveTrackID }
    public let mapID: String
    public let remoteID: String
    public let liveTrackID: String
    public let label: String
    public var description: String
    public var points: [CaltopoInterruptedPublicationPoint]

    public init(
        mapID: String,
        remoteID: String,
        liveTrackID: String,
        label: String,
        description: String = "",
        observations: [RidObservation]
    ) {
        self.mapID = mapID
        self.remoteID = remoteID
        self.liveTrackID = liveTrackID
        self.label = label
        self.description = description
        points = observations.map(CaltopoInterruptedPublicationPoint.init)
    }

    public var observations: [RidObservation] {
        points.map { $0.observation(remoteID: remoteID) }
    }
}

/// Durable handoff between an active CalTopo publication and the next app process.
/// Entries are removed only after the LiveTrack has been converted and stopped.
public actor CaltopoInterruptedPublicationJournal {
    private struct Payload: Codable {
        var version = 1
        var entries: [CaltopoInterruptedPublication] = []
    }

    private let fileURL: URL
    private var payload: Payload

    public init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode(Payload.self, from: data) {
            payload = decoded
        } else {
            payload = Payload()
        }
    }

    public func upsert(_ entry: CaltopoInterruptedPublication) throws {
        if let index = payload.entries.firstIndex(where: { $0.liveTrackID == entry.liveTrackID }) {
            payload.entries[index] = entry
        } else {
            payload.entries.append(entry)
        }
        try save()
    }

    public func entries(mapID: String) -> [CaltopoInterruptedPublication] {
        payload.entries.filter { $0.mapID == mapID }
    }

    public func remove(liveTrackID: String) throws {
        payload.entries.removeAll { $0.liveTrackID == liveTrackID }
        try save()
    }

    private func save() throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let data = try JSONEncoder().encode(payload)
        try data.write(to: fileURL, options: .atomic)
    }
}
