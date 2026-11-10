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

@Test func shadowBeforeTheGrantStaysOutOfTheOwnedFlight() async {
    let store = RidTrackStore(policy: .init(activeTimeout: 30))
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
    let shadowAt = calendar.date(from: DateComponents(year: 2026, month: 11, day: 10, hour: 11, minute: 25, second: 46))!
    let grantedAt = calendar.date(from: DateComponents(year: 2026, month: 11, day: 10, hour: 11, minute: 28, second: 50))!
    let aircraft = "1581F8HGX255S00A0FZT"
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: shadowAt))
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: shadowAt.addingTimeInterval(60), latitude: 39.7400))
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: grantedAt.addingTimeInterval(1), latitude: 39.7500))

    let split = await store.closeShadow(aircraftID: aircraft, ownershipGrantedAt: grantedAt)
    #expect(split.shadow?.points.count == 2)
    #expect(split.shadow?.flightStartedAt == shadowAt)
    #expect(split.shadow?.points.allSatisfy { $0.receivedAt < grantedAt } == true)
    #expect(split.owned?.points.count == 1)
    #expect(split.owned?.flightStartedAt == grantedAt.addingTimeInterval(1))
    #expect(split.owned?.points.allSatisfy { $0.receivedAt >= grantedAt } == true)

    let kept = await store.track(aircraftID: aircraft)
    #expect(kept?.flightStartedAt == grantedAt.addingTimeInterval(1))
    #expect(kept?.points.contains { $0.receivedAt < grantedAt } == false)
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
