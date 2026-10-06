import Foundation
import Testing
@testable import R2CCore

private func awaitingSample(_ time: TimeInterval = 1_000_000, latitude: Double = 39) -> RidObservation {
    RidObservation(source: .bluetoothLegacy, aircraftId: "RID-1", receivedAt: Date(timeIntervalSince1970: time), latitude: latitude, longitude: -121, altitudeMeters: 500)
}

@Test @MainActor func pendingFlightRequiresDecisionAndSurvivesRestart() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let file = root.appendingPathComponent("pending.json")
    let journal = AwaitingMapFlightJournal(fileURL: file)
    try journal.record(remoteID: "RID-1", label: "Flight", observations: [awaitingSample()], mapID: "", teamID: "team-a", finished: false)
    let id = try #require(journal.entries.first?.id)
    #expect(journal.entries[0].decision == "review")
    // Connecting a map does not bind or authorize the flight.
    try journal.record(remoteID: "RID-1", label: "Flight", observations: [awaitingSample(), awaitingSample(1_000_010)], mapID: "map-a", teamID: "team-a", finished: false)
    #expect(journal.entries[0].mapID.isEmpty)
    #expect(journal.entries[0].decision == "review")
    try journal.decide(id: id, mapID: "map-a", teamID: "team-a")
    let reopened = AwaitingMapFlightJournal(fileURL: file)
    #expect(reopened.entries[0].id == id)
    #expect(reopened.entries[0].finished)
    #expect(reopened.entries[0].mapID == "map-a")
    #expect(reopened.entries[0].points.count == 2)
    // Subsequent requests cannot silently retarget an accepted flight.
    try reopened.decide(id: id, mapID: "map-b", teamID: "team-b")
    #expect(reopened.entries[0].mapID == "map-a")
}

@Test @MainActor func keepLocalAndSeparateFlightConsentSurviveRestart() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let file = root.appendingPathComponent("pending.json")
    let journal = AwaitingMapFlightJournal(fileURL: file)
    try journal.record(remoteID: "RID-1", label: "Flight", observations: [awaitingSample()], mapID: "", teamID: "", finished: false)
    try journal.decide(id: journal.entries[0].id, mapID: nil, teamID: "")
    let reopened = AwaitingMapFlightJournal(fileURL: file)
    try reopened.record(remoteID: "RID-1", label: "New flight", observations: [awaitingSample(1_100_000)], mapID: "", teamID: "", finished: false)
    #expect(reopened.entries.map(\.decision) == ["local", "review"])
}

@Test func suggestionsRequireRecentFlightAndIcProximity() {
    let flight = AwaitingMapFlight(remoteID: "RID-1", label: "Flight", observations: [awaitingSample()], teamID: "team-a")
    #expect(!flight.suggested(icLocations: [], now: flight.lastTime))
    #expect(flight.suggested(icLocations: [.init(latitude: 39, longitude: -121)], now: flight.lastTime))
    #expect(!flight.suggested(icLocations: [.init(latitude: 40, longitude: -121)], now: flight.lastTime))
    #expect(!flight.suggested(icLocations: [.init(latitude: 39, longitude: -121)], now: flight.lastTime.addingTimeInterval(86_401)))
    #expect(flight.decision == "review")
}

@Test func extendedFlightRecoveryDoesNotTruncateGeometry() {
    let samples = (0..<5_101).map { awaitingSample(Double(1_000_000 + $0)) }
    let entry = CaltopoInterruptedPublication(mapID: "map-a", remoteID: "RID-1", liveTrackID: "stable", label: "Long flight", observations: samples)
    #expect(entry.points.count == 5_101)
    #expect(entry.points.first?.receivedAt == samples.first?.receivedAt)
}

@Test @MainActor func selectedMapIsBoundAndDoesNotBecomeAnOfferWhenMapChanges() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let journal = AwaitingMapFlightJournal(fileURL: root.appendingPathComponent("pending.json"))
    try journal.record(remoteID: "RID-1", label: "Flight", observations: [awaitingSample()], mapID: "original", teamID: "team-a", finished: false)
    try journal.record(remoteID: "RID-1", label: "Flight", observations: [awaitingSample(), awaitingSample(1_000_001)], mapID: "other", teamID: "team-b", finished: false)
    #expect(journal.entries[0].decision == "bound")
    #expect(journal.entries[0].mapID == "original")
    #expect(journal.entries[0].teamID == "team-a")
    try journal.notePublication(remoteID: "RID-1", mapID: "original")
    try journal.record(remoteID: "RID-1", label: "Flight", observations: [awaitingSample()], mapID: "", teamID: "", finished: true)
    #expect(journal.entries.isEmpty)
}

@Test @MainActor func discardingOneShortFlightDoesNotDiscardEarlierFlightOffer() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let journal = AwaitingMapFlightJournal(fileURL: root.appendingPathComponent("pending.json"))
    try journal.record(remoteID: "RID-1", label: "Old", observations: [awaitingSample()], mapID: "", teamID: "", finished: false)
    try journal.record(remoteID: "RID-1", label: "Old", observations: [awaitingSample()], mapID: "", teamID: "", finished: true)
    try journal.record(remoteID: "RID-1", label: "New", observations: [awaitingSample(1_100_000)], mapID: "", teamID: "", finished: false)
    try journal.discard(remoteID: "RID-1", startedAt: Date(timeIntervalSince1970: 1_100_000))
    #expect(journal.entries.count == 1)
    #expect(journal.entries[0].label == "Old")
}

@Test func deferredFlightPublicationUsesFirstWaypointTimestamp() throws {
    let first = awaitingSample(1_790_553_873)
    let flight = AwaitingMapFlight(remoteID: "RID-1", label: "1sar7DjMn4Pr",
        observations: [first, awaitingSample(1_790_554_000)], teamID: "team-a")
    let expected = CaltopoTrackLabel.androidCompatible(baseLabel: flight.label, firstWaypointAt: first.receivedAt)
    #expect(flight.publication.label == expected)
    #expect(flight.publication.label != flight.label)
    // Existing saved offers must receive the same label after restarting.
    let restored = try JSONDecoder().decode(AwaitingMapFlight.self, from: JSONEncoder().encode(flight))
    #expect(restored.publication.label == expected)
    #expect(restored.publication.liveTrackID == flight.id)
    #expect(restored.label == flight.label) // Publication does not mutate the saved base label.
}

@Test @MainActor func personalFlightCanBeAssignedAfterMapSelectionAndRestart() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let url = root.appendingPathComponent("pending.json")
    let journal = AwaitingMapFlightJournal(fileURL: url)
    let samples = (0..<64).map { awaitingSample(Double(1_000_000 + $0)) }
    try journal.record(remoteID: "RID-1", label: "Personal flight", observations: samples, mapID: "", teamID: "", finished: false)
    try journal.record(remoteID: "RID-1", label: "Personal flight", observations: samples, mapID: "personal-map", teamID: "", finished: true)
    let reopened = AwaitingMapFlightJournal(fileURL: url)
    #expect(reopened.entries[0].decision == "review")
    let scope = CaltopoPublicationScope.identifier(personalAccountID: "responder-a", teamID: "")
    try reopened.decide(id: reopened.entries[0].id, mapID: "personal-map", teamID: scope)
    let saved = AwaitingMapFlightJournal(fileURL: url).entries[0]
    #expect(saved.decision == "publish")
    #expect(saved.teamID == "personal:responder-a")
    #expect(saved.publication.points.count == 64)
    #expect(saved.publication.mapID == "personal-map")
    #expect(scope != CaltopoPublicationScope.identifier(personalAccountID: "responder-b", teamID: ""))
    #expect(CaltopoPublicationScope.identifier(personalAccountID: "", teamID: "team-a").isEmpty)
    #expect(CaltopoPublicationScope.identifier(personalAccountID: nil, teamID: "team-a") == "team-a")
}

@Test @MainActor func journalEntriesUseTheStableFlightID() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let journal = AwaitingMapFlightJournal(fileURL: root.appendingPathComponent("pending.json"))
    let flightID = RidFlightID.make(aircraftID: "RID-1", startedAt: Date(timeIntervalSince1970: 1_000_000))
    try journal.record(remoteID: "RID-1", label: "Flight", observations: [awaitingSample()], mapID: "", teamID: "team-a",
                       finished: false, flightID: flightID)
    #expect(journal.entries.map(\.id) == [flightID])
    // Later snapshots of the same open flight keep the id (and it stays a UUID for CalTopo live tracks).
    try journal.record(remoteID: "RID-1", label: "Flight", observations: [awaitingSample(), awaitingSample(1_000_010)],
                       mapID: "", teamID: "team-a", finished: false, flightID: "other")
    #expect(journal.entries.map(\.id) == [flightID])
    #expect(journal.entries[0].publication.liveTrackID == flightID)
}
