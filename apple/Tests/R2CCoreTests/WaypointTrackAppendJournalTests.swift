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

    @Test func ensureOpenReplacesUnfinishedJournalWhenStartedAtDiffers() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("waypoint-journals-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = WaypointTrackAppendJournal(directory: directory)
        let flightID = "flight-start-mismatch"
        try await journal.ensureOpen(
            flightID: flightID, remoteID: "RID-7", label: "morning", mapID: "", teamID: "team",
            startedAtMs: 1_000)
        try await journal.append(
            observation: RidObservation(
                source: .wifiBeacon, aircraftId: "RID-7",
                receivedAt: Date(timeIntervalSince1970: 1),
                latitude: 1, longitude: 2, altitudeMeters: 3,
                droneTimestamp: Date(timeIntervalSince1970: 1)),
            flightID: flightID)

        try await journal.ensureOpen(
            flightID: flightID, remoteID: "RID-7", label: "afternoon", mapID: "", teamID: "team",
            startedAtMs: 2_000)
        try await journal.append(
            observation: RidObservation(
                source: .wifiBeacon, aircraftId: "RID-7",
                receivedAt: Date(timeIntervalSince1970: 2),
                latitude: 4, longitude: 5, altitudeMeters: 6,
                droneTimestamp: Date(timeIntervalSince1970: 2)),
            flightID: flightID)

        let recovered = try await journal.recoverUnfinished()
        #expect(recovered.count == 1)
        #expect(recovered[0].startedAtMs == 2_000)
        #expect(recovered[0].observations.count == 1)
        #expect(recovered[0].label == "afternoon")
    }

    @Test func discardedFlightIsNotRepromotedAfterColdRestartWithLeftoverJournal() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("discard-persist-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let journalDir = root.appendingPathComponent("journals", isDirectory: true)
        let awaitingURL = root.appendingPathComponent("awaiting.json")
        let ledgerURL = root.appendingPathComponent("ledger.json")
        let appendJournal = WaypointTrackAppendJournal(directory: journalDir)
        let awaiting = await AwaitingMapFlightJournal.loaded(fileURL: awaitingURL)
        let ledger = FlightPublicationLedger(fileURL: ledgerURL)
        let flightID = "flight-discard-persist"
        try await appendJournal.ensureOpen(
            flightID: flightID, remoteID: "RID-6", label: "label6", mapID: "", teamID: "team",
            startedAtMs: 1_000)
        try await appendJournal.append(
            observation: RidObservation(
                source: .wifiBeacon, aircraftId: "RID-6",
                receivedAt: Date(timeIntervalSince1970: 1),
                latitude: 39, longitude: -121, altitudeMeters: 50,
                droneTimestamp: Date(timeIntervalSince1970: 1)),
            flightID: flightID)
        let recovered = try await appendJournal.recoverUnfinished()
        #expect(recovered.count == 1)
        try await awaiting.record(
            remoteID: recovered[0].remoteID, label: recovered[0].label,
            observations: recovered[0].observations, mapID: "", teamID: "team",
            finished: false, flightID: flightID)
        try await awaiting.record(
            remoteID: recovered[0].remoteID, label: recovered[0].label,
            observations: recovered[0].observations, mapID: "", teamID: "team",
            finished: true, flightID: flightID)
        try await appendJournal.acknowledgeRecovered(flightID: flightID)
        #expect((await awaiting.entries).filter { $0.decision == "review" && $0.finished }.count == 1)

        try await ledger.remember(flightID: flightID, disposition: .discarded)
        #expect(try await awaiting.discard(id: flightID))
        #expect((await awaiting.entries).isEmpty)

        // Cold restart artifacts: leftover journal + archive file remain on disk.
        let leftover = journalDir.appendingPathComponent("\(flightID).ndjson")
        let leftoverBody = """
        {"type":"header","id":"\(flightID)","remote":"RID-6","label":"label6","map":"","team":"team","startedAtMs":1000}
        {"type":"point","lat":39.0,"lng":-121.0,"ele":50.0,"t":1000,"recv":1001,"src":"rid","droneClock":true}
        """
        try leftoverBody.write(to: leftover, atomically: true, encoding: .utf8)
        let archive = root.appendingPathComponent("tracks-leftover", isDirectory: true)
        try FileManager.default.createDirectory(at: archive, withIntermediateDirectories: true)
        try Data("{}".utf8).write(to: archive.appendingPathComponent("RID-6.json"))

        let coldAwaiting = await AwaitingMapFlightJournal.loaded(fileURL: awaitingURL)
        let coldLedger = FlightPublicationLedger(fileURL: ledgerURL)
        let coldJournal = WaypointTrackAppendJournal(directory: journalDir)
        let again = try await coldJournal.recoverUnfinished()
        for flight in again {
            if try await coldLedger.blocksAwaitingMapPromotion(flightID: flight.id) {
                try await coldJournal.acknowledgeRecovered(flightID: flight.id)
                continue
            }
            try await coldAwaiting.record(
                remoteID: flight.remoteID, label: flight.label,
                observations: flight.observations, mapID: flight.mapID, teamID: flight.teamID,
                finished: false, flightID: flight.id)
            try await coldAwaiting.record(
                remoteID: flight.remoteID, label: flight.label,
                observations: flight.observations, mapID: flight.mapID, teamID: flight.teamID,
                finished: true, flightID: flight.id)
            try await coldJournal.acknowledgeRecovered(flightID: flight.id)
        }
        var inbox: [AwaitingMapFlight] = []
        for entry in await coldAwaiting.entries {
            let blocked = try await coldLedger.blocksAwaitingMapPromotion(flightID: entry.id)
            if TrackArchiveConfirmation.showsInAwaitingPublicationInbox(
                decision: entry.decision,
                finished: entry.finished,
                archiveConfirmed: entry.archiveConfirmed == true,
                hasArchiveId: !(entry.caltopoArchiveId ?? "").isEmpty,
                ledgerBlocksPromotion: blocked
            ) {
                inbox.append(entry)
            }
        }
        #expect(inbox.isEmpty)
        #expect((await coldAwaiting.entries).isEmpty)
        #expect(try await coldLedger.disposition(flightID: flightID) == .discarded)
        #expect(!FileManager.default.fileExists(atPath: leftover.path))
        #expect(FileManager.default.fileExists(atPath: archive.appendingPathComponent("RID-6.json").path))
    }
}
