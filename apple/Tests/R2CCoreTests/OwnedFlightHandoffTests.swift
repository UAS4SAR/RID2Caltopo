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

    await store.trimShadow(aircraftID: aircraft, ownershipGrantedAt: grantedAt)
    let kept = await store.track(aircraftID: aircraft)
    #expect(kept?.points.count == 1)
    #expect(kept?.flightStartedAt == grantedAt.addingTimeInterval(1))
    #expect(kept?.points.contains { $0.receivedAt < grantedAt } == false)

    await store.trimShadow(aircraftID: aircraft, ownershipGrantedAt: grantedAt)
    let again = await store.track(aircraftID: aircraft)
    #expect(again?.points.count == 1)
    #expect(again?.flightStartedAt == grantedAt.addingTimeInterval(1))
}

/// Round 8: iPad 14:04:42, A5 Pro grant 14:06:44, iPad grant 14:09:45.
/// The owned flight starts at its grant, and the handoff does not keep a tail.
@Test func round8HandoffStartsAtTheGrantAndDropsTheTail() async {
    let store = RidTrackStore(policy: .init(activeTimeout: 30))
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
    func at(_ hour: Int, _ minute: Int, _ second: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: 10, hour: hour, minute: minute, second: second))!
    }
    let aircraft = "1581F8HGX255S00A0FZT"
    let a5Grant = at(14, 6, 44)
    let ipadReturn = at(14, 9, 45)

    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: at(14, 5, 11), latitude: 39.7410))
    await store.markOwned(aircraftID: aircraft, at: a5Grant)
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: a5Grant.addingTimeInterval(1), latitude: 39.7420))
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: at(14, 9, 18), latitude: 39.7430))
    let live = await store.track(aircraftID: aircraft)
    #expect(live?.points.contains { $0.receivedAt == at(14, 5, 11) } == true)
    let archived = await store.recordForArchive(live!, ownedDuringFlight: true)
    #expect(archived?.flightStartedAt == a5Grant.addingTimeInterval(1))
    #expect(archived?.points.allSatisfy { $0.receivedAt >= a5Grant && $0.receivedAt < ipadReturn } == true)
    #expect(archived?.points.contains { $0.receivedAt == at(14, 5, 11) } == false)

    await store.clearOwned(aircraftID: aircraft)
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: at(14, 9, 50), latitude: 39.7440))
    let withTail = await store.track(aircraftID: aircraft)
    #expect(withTail?.points.contains { $0.receivedAt == at(14, 9, 50) } == true)
    #expect(await store.recordForArchive(withTail!, ownedDuringFlight: false) == nil)
    _ = await store.take(aircraftID: aircraft)

    await store.markOwned(aircraftID: aircraft, at: ipadReturn)
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: ipadReturn.addingTimeInterval(2), latitude: 39.7450))
    let thirdLive = await store.track(aircraftID: aircraft)
    let third = await store.recordForArchive(thirdLive!, ownedDuringFlight: true)
    #expect(third?.flightStartedAt == ipadReturn.addingTimeInterval(2))
    #expect(third?.points.allSatisfy { $0.receivedAt >= ipadReturn } == true)
    #expect((archived?.points.last?.receivedAt ?? .distantFuture) < (third?.flightStartedAt ?? .distantPast))
}

/// 14:46:54 on 250792fe. A valid position was logged invalid_observation because the other tablet held the lease.
@Test func leaseHeldByAnotherTabletStillAcceptsAValidPosition() async {
    let store = RidTrackStore(policy: .init(activeTimeout: 30))
    let aircraft = "1581F8HGX255S00A0FZT"
    let at = Date(timeIntervalSince1970: 1_794_350_814)
    let first = await store.ingest(handoffObservation(aircraft: aircraft, at: at, latitude: 39.7410))
    guard case .accepted = first else {
        Issue.record("first point")
        return
    }
    await store.markOwned(aircraftID: aircraft, at: at)
    await store.clearOwned(aircraftID: aircraft)
    let video = await store.ingest(handoffObservation(
        aircraft: aircraft, at: at.addingTimeInterval(10), latitude: 39.7412, source: .djiVideo))
    let radio = await store.ingest(handoffObservation(
        aircraft: aircraft, at: at.addingTimeInterval(20), latitude: 39.7414, source: .bluetoothLegacy))
    guard case .accepted = video, case .accepted = radio else {
        Issue.record("valid positions were rejected")
        return
    }
    let live = await store.track(aircraftID: aircraft)
    #expect(live?.points.contains { $0.latitude == 39.7412 } == true)
    #expect(live?.points.contains { $0.latitude == 39.7414 } == true)
    #expect(await store.recordForArchive(live!, ownedDuringFlight: false) == nil)
}

private func handoffObservation(
    aircraft: String, at: Date, latitude: Double = 39.7392, source: RidObservation.Source = .djiVideo
) -> RidObservation {
    RidObservation(
        source: source,
        aircraftId: aircraft,
        receivedAt: at,
        latitude: latitude,
        longitude: -104.9903
    )
}
