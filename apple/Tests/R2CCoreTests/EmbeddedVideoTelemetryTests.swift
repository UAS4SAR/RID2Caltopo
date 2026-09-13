import Foundation
import Testing
@testable import R2CCore

@Test func embeddedVideoStartsTrackAndAltitudeWithoutRID() async throws {
    let store = RidTrackStore()
    let now = Date()
    let sessionID = "1sar7djmtrc4td"
    var activity = PairedVideoFlightActivityStore()
    activity.publisherStarted(streamID: sessionID, at: now)
    #expect(await store.snapshot().isEmpty)
    activity.pairConfiguredPublishers(mappings: [("1581F8HGX255S00A0FZT", "1sar7DjMtrc4td")])
    #expect(activity.isPublisherActive(streamID: sessionID))
    let aircraftID = try #require(activity.boundAircraftID(for: sessionID))
    let observation = RidObservation(source: .djiVideo, aircraftId: aircraftID,
        receivedAt: now, latitude: 39.1536, longitude: -121.1322,
        altitudeMeters: 605.742, heightMeters: 4.803, heightReference: .takeoff)
    guard case let .accepted(track) = await store.ingest(observation) else {
        Issue.record("A stream must create a track without RID"); return
    }
    #expect(track.acceptedCountBySource[.djiVideo] == 1)
    var coordinator = OperationalAltitudeCoordinator()
    coordinator.ingest(observation)
    let display = coordinator.display(at: now)
    #expect(!display.positionStale)
    #expect(abs((display.atoFeet ?? 0) - 15.75787) < 0.001)
    #expect(coordinator.aolTakeoffCoordinate == nil)
    #expect(coordinator.display(at: now.addingTimeInterval(6)).positionStale)
    let hover = RidObservation(source: .djiVideo, aircraftId: observation.aircraftId,
        receivedAt: now.addingTimeInterval(1), latitude: 39.1536001, longitude: -121.1322,
        altitudeMeters: 606.742, heightMeters: 5.803, heightReference: .takeoff)
    guard case .accepted = await store.ingest(hover) else {
        Issue.record("Small horizontal motion must not discard fresh stream height"); return
    }
}

@Test func configuredVideoBindingRespectsAmbiguityAndOperatorChoices() {
    let mappings = [(remoteID: "aircraft-a", designator: "Matrice")]
    var activity = PairedVideoFlightActivityStore()
    activity.publisherStarted(streamID: "matrice", at: Date())
    #expect(activity.pairConfiguredPublishers(mappings: mappings).count == 1)
    #expect(activity.pairConfiguredPublishers(mappings: mappings).isEmpty)
    activity.unpair(streamID: "MATRICE")
    #expect(activity.pairConfiguredPublishers(mappings: mappings).isEmpty)
    #expect(activity.boundAircraftID(for: "matrice") == nil)
    activity.pair(streamID: "matrice", aircraftID: "manual-aircraft")
    #expect(activity.pairConfiguredPublishers(mappings: mappings).isEmpty)
    #expect(activity.boundAircraftID(for: "matrice") == "manual-aircraft")
    activity.publisherStopped(streamID: "matrice", at: Date())
    #expect(!activity.isPublisherActive(streamID: "MATRICE"))

    var ambiguous = PairedVideoFlightActivityStore()
    ambiguous.publisherStarted(streamID: "matrice", at: Date())
    #expect(ambiguous.pairConfiguredPublishers(mappings: mappings + [("aircraft-b", "MATRICE")]).isEmpty)
    #expect(ambiguous.boundAircraftID(for: "matrice") == nil)
}
