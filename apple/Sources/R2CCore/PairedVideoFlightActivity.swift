import Foundation

/// Session-scoped pairing and publisher presence used by flight lifecycle aging.
/// Publisher connection IDs may rotate; the stable stream designator retains the pairing.
public struct PairedVideoFlightActivityStore: Sendable, Equatable {
    private var aircraftIDByStreamID: [String: String] = [:]
    private var manuallyUnpairedStreamIDs: Set<String> = []
    private var livePublisherStreamIDs: Set<String> = []
    private var lastPublisherActivityAtByStreamID: [String: Date] = [:]

    public init() {}

    public mutating func pair(streamID: String, aircraftID: String) {
        let stream = Self.normalized(streamID)
        let aircraft = aircraftID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !stream.isEmpty, !aircraft.isEmpty else { return }
        aircraftIDByStreamID[stream] = aircraft
        manuallyUnpairedStreamIDs.remove(stream)
    }

    @discardableResult
    public mutating func pairIfUnbound(streamID: String, aircraftID: String) -> Bool {
        let stream = Self.normalized(streamID)
        guard !stream.isEmpty,
              aircraftIDByStreamID[stream] == nil,
              !manuallyUnpairedStreamIDs.contains(stream)
        else { return false }
        pair(streamID: stream, aircraftID: aircraftID)
        return aircraftIDByStreamID[stream] != nil
    }

    public mutating func unpair(streamID: String) {
        let stream = Self.normalized(streamID)
        guard !stream.isEmpty else { return }
        aircraftIDByStreamID.removeValue(forKey: stream)
        manuallyUnpairedStreamIDs.insert(stream)
    }

    public func boundAircraftID(for streamID: String) -> String? {
        aircraftIDByStreamID[Self.normalized(streamID)]
    }

    public mutating func publisherStarted(streamID: String, at date: Date) {
        let stream = Self.normalized(streamID)
        guard !stream.isEmpty else { return }
        livePublisherStreamIDs.insert(stream)
        lastPublisherActivityAtByStreamID[stream] = date
    }

    public mutating func publisherStopped(streamID: String, at date: Date) {
        let stream = Self.normalized(streamID)
        guard !stream.isEmpty else { return }
        livePublisherStreamIDs.remove(stream)
        lastPublisherActivityAtByStreamID[stream] = date
    }

    public func isPublisherActive(streamID: String) -> Bool {
        livePublisherStreamIDs.contains(Self.normalized(streamID))
    }

    /// Bind uniquely configured live streams before any aircraft track exists.
    /// Preserve explicit pair/unpair choices, and never guess ambiguous mappings.
    @discardableResult
    public mutating func pairConfiguredPublishers(
        mappings: [(remoteID: String, designator: String)]
    ) -> [String: String] {
        var added: [String: String] = [:]
        for stream in livePublisherStreamIDs.sorted() {
            guard let aircraft = OperationalStreamConfirmationMatch.remoteID(designator: stream, mappings: mappings),
                  pairIfUnbound(streamID: stream, aircraftID: aircraft)
            else { continue }
            added[stream] = aircraft
        }
        return added
    }

    public var activePublisherStreamIDs: Set<String> { livePublisherStreamIDs }

    public func activityByAircraftID(at date: Date) -> [String: Date] {
        aircraftIDByStreamID.reduce(into: [:]) { result, entry in
            let (streamID, aircraftID) = entry
            let activity = livePublisherStreamIDs.contains(streamID)
                ? date
                : lastPublisherActivityAtByStreamID[streamID]
            guard let activity else { return }
            result[aircraftID] = max(result[aircraftID] ?? .distantPast, activity)
        }
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }
}
