import Foundation
import Testing
@testable import R2CCore

@Suite struct WaypointTrackAppendJournalTests {
    @Test func uncleanExitRecoversUnfinishedPointsWithoutDeletingUntilAck() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("waypoint-journals-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = WaypointTrackAppendJournal(directory: directory)
        let flightID = "flight-apple-1"
        try await journal.ensureOpen(
            flightID: flightID, remoteID: "RID-1", label: "1sar7DjMtrc4td_143651Oct30",
            mapID: "", teamID: "team")
        let first = RidObservation(
            source: .wifiBeacon, aircraftId: "RID-1", receivedAt: Date(timeIntervalSince1970: 1),
            latitude: 39.15, longitude: -121.13, altitudeMeters: 520, droneTimestamp: Date(timeIntervalSince1970: 1))
        let second = RidObservation(
            source: .wifiBeacon, aircraftId: "RID-1", receivedAt: Date(timeIntervalSince1970: 2),
            latitude: 39.16, longitude: -121.14, altitudeMeters: 530, droneTimestamp: Date(timeIntervalSince1970: 2))
        try await journal.append(observation: first, flightID: flightID)
        try await journal.append(observation: second, flightID: flightID)

        let recovered = try await journal.recoverUnfinished()
        #expect(recovered.count == 1)
        #expect(recovered[0].id == flightID)
        #expect(recovered[0].observations.count == 2)
        #expect(recovered[0].label == "1sar7DjMtrc4td_143651Oct30")
        // Journal must survive until awaiting-map commit is acknowledged.
        let leftoverAfterRecover = try FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil)
        #expect(leftoverAfterRecover.count == 1)
        #expect(leftoverAfterRecover[0].pathExtension == "ndjson")

        try await journal.acknowledgeRecovered(flightID: flightID)
        let leftoverAfterAck = try FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil)
        #expect(leftoverAfterAck.isEmpty)
    }

    @Test func killBetweenRecoverAndAwaitingMapCommitLeavesJournalForRetry() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("waypoint-journals-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = WaypointTrackAppendJournal(directory: directory)
        let flightID = "flight-kill-mid-commit"
        try await journal.ensureOpen(
            flightID: flightID, remoteID: "RID-K", label: "label", mapID: "map", teamID: "team")
        try await journal.append(
            observation: RidObservation(
                source: .wifiBeacon, aircraftId: "RID-K", receivedAt: Date(),
                latitude: 1, longitude: 2, altitudeMeters: 3),
            flightID: flightID)

        let firstPass = try await journal.recoverUnfinished()
        #expect(firstPass.count == 1)
        // Simulate process death before awaiting-map record / acknowledgeRecovered.
        let secondPass = try await journal.recoverUnfinished()
        #expect(secondPass.count == 1)
        #expect(secondPass[0].id == flightID)
        #expect(secondPass[0].observations.count == 1)
        try await journal.acknowledgeRecovered(flightID: flightID)
        #expect(try await journal.recoverUnfinished().isEmpty)
    }

    @Test func finishedJournalIsNotRecovered() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("waypoint-journals-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = WaypointTrackAppendJournal(directory: directory)
        let flightID = "flight-apple-finished"
        try await journal.ensureOpen(
            flightID: flightID, remoteID: "RID-2", label: "label", mapID: "", teamID: "team")
        try await journal.append(
            observation: RidObservation(
                source: .wifiBeacon, aircraftId: "RID-2", receivedAt: Date(),
                latitude: 1, longitude: 2, altitudeMeters: 3),
            flightID: flightID)
        try await journal.markFinishedAndRemove(flightID: flightID)
        let recovered = try await journal.recoverUnfinished()
        #expect(recovered.isEmpty)
    }
}
