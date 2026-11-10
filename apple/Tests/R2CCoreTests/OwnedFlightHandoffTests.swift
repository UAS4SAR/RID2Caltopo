import Foundation
import Testing
@testable import R2CCore

/// Round 4: owner A is still inside the 30s end-of-flight window when B claims
/// the aircraft. A's points close as A's flight. The next ingest is a new flight.
@Test func takeoverRemovesTheOwnedFlightAndTheNextIngestStartsEmpty() async {
    let store = RidTrackStore(policy: .init(activeTimeout: 30))
    let firstStart = Date(timeIntervalSince1970: 1_700_000_000)
    _ = await store.ingest(handoffObservation(aircraft: "1581F8HGX255S00A0FZT", at: firstStart))
    _ = await store.ingest(handoffObservation(
        aircraft: "1581F8HGX255S00A0FZT",
        at: firstStart.addingTimeInterval(10),
        latitude: 39.7400
    ))
    let owned = await store.track(aircraftID: "1581f8hgx255s00a0fzt")
    #expect(owned?.points.count == 2)

    let closed = await store.take(aircraftID: "1581F8HGX255S00A0FZT")
    #expect(closed?.points.count == 2)
    #expect(closed?.flightStartedAt == firstStart)
    #expect(await store.track(aircraftID: "1581F8HGX255S00A0FZT") == nil)

    let nextStart = firstStart.addingTimeInterval(20)
    _ = await store.ingest(handoffObservation(
        aircraft: "1581F8HGX255S00A0FZT", at: nextStart, latitude: 39.7500))
    let next = await store.track(aircraftID: "1581F8HGX255S00A0FZT")
    #expect(next?.points.count == 1)
    #expect(next?.flightStartedAt == nextStart)
    #expect(next?.flightStartedAt != closed?.flightStartedAt)
    #expect(next?.points.first?.latitude == 39.7500)
}

private func handoffObservation(aircraft: String, at: Date, latitude: Double = 39.7392) -> RidObservation {
    RidObservation(
        source: .djiVideo,
        aircraftId: aircraft,
        receivedAt: at,
        latitude: latitude,
        longitude: -104.9903
    )
}
