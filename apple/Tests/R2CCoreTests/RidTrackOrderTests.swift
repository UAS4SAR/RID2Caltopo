import Foundation
import Testing
@testable import R2CCore

private let orderStart = Date(timeIntervalSince1970: 1_790_000_000)

private func obs(_ id: String, _ t: TimeInterval, lat: Double = 39, lon: Double = -121) -> RidObservation {
    RidObservation(source: .bluetoothLegacy, aircraftId: id, receivedAt: orderStart.addingTimeInterval(t),
                   latitude: lat, longitude: lon, altitudeMeters: 100, horizontalAccuracyCode: 10)
}

@Test func aircraftListOrderIsFlightStartEarliestFirstAndStable() async {
    let store = RidTrackStore()
    _ = await store.ingest(obs("BRAVO", 0))      // B starts first
    _ = await store.ingest(obs("ALPHA", 5))      // A starts later
    #expect(await store.snapshot().map(\.aircraftID) == ["BRAVO", "ALPHA"])
    // Alternating messages must not reorder the list (old recency sort flipped it each time).
    for step in 1 ... 6 {
        let t = 5 + TimeInterval(step) * 1.5
        let id = step.isMultiple(of: 2) ? "BRAVO" : "ALPHA"
        _ = await store.ingest(obs(id, t, lat: 39 + Double(step) * 0.0001))
        #expect(await store.snapshot().map(\.aircraftID) == ["BRAVO", "ALPHA"])
    }
}

@Test func aircraftListOrderTieBreaksOnAircraftID() async {
    let store = RidTrackStore()
    _ = await store.ingest(obs("ZULU", 0))
    _ = await store.ingest(obs("MIKE", 0))
    #expect(await store.snapshot().map(\.aircraftID) == ["MIKE", "ZULU"])
}
