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
    let ipadGrant = at(14, 4, 42)
    let a5Grant = at(14, 6, 44)
    let ipadReturn = at(14, 9, 45)

    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: ipadGrant.addingTimeInterval(7)))
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: at(14, 6, 16), latitude: 39.7400))
    let first = await store.take(aircraftID: aircraft)
    #expect(first?.flightStartedAt == ipadGrant.addingTimeInterval(7))
    #expect(first?.points.last?.receivedAt == at(14, 6, 16))

    await store.suspendRecordingUntilOwned(aircraftID: aircraft)
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: at(14, 5, 11), latitude: 39.7410))
    #expect(await store.track(aircraftID: aircraft) == nil)

    await store.resumeOwnedRecording(aircraftID: aircraft)
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: a5Grant.addingTimeInterval(1), latitude: 39.7420))
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: at(14, 9, 18), latitude: 39.7430))
    let second = await store.track(aircraftID: aircraft)
    #expect(second?.flightStartedAt == a5Grant.addingTimeInterval(1))
    #expect(second?.points.allSatisfy { $0.receivedAt >= a5Grant && $0.receivedAt < ipadReturn } == true)
    #expect(second?.flightStartedAt != first?.flightStartedAt)

    await store.suspendRecordingUntilOwned(aircraftID: aircraft)
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: at(14, 9, 50), latitude: 39.7440))
    #expect(await store.track(aircraftID: aircraft) == nil)

    await store.resumeOwnedRecording(aircraftID: aircraft)
    _ = await store.ingest(handoffObservation(aircraft: aircraft, at: ipadReturn.addingTimeInterval(2), latitude: 39.7450))
    let third = await store.track(aircraftID: aircraft)
    #expect(third?.flightStartedAt == ipadReturn.addingTimeInterval(2))
    #expect(third?.points.allSatisfy { $0.receivedAt >= ipadReturn } == true)
    #expect((second?.points.last?.receivedAt ?? .distantFuture) < (third?.flightStartedAt ?? .distantPast))
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
