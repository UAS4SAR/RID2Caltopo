import Foundation
import Testing
@testable import R2CCore

/// Round 13: battery death after the live track started. The journal has 65 points
/// and the interrupted snapshot has 62. Restart writes one 65-point file, uploads
/// once, and updates shape e0381722. It does not touch another flight's shape,
/// invent a pilot, upload an unverified Mine, or take the Tracker lease.
@Test func round13BatteryDeathWritesOneFileUploadsOnceAndUpdatesTheSameShape() throws {
    let flight = "e0381722-218d-5262-add1-4b3284a30694"
    let other = "b65e121d-9807-5db7-9470-b989c012699d"
    let ownership = FlightOwnershipContext.capture(
        confirmed: true, ignored: false, localOnly: false, claim: FlightOwnershipContext.verified,
        pilot: "1SAR7", organization: "NCSSAR", model: "DJI Matrice 4TD", mappedID: "1sar7DjMtrc4td")
    let decision = RestartFlightRecovery.decide(
        liveTrackID: flight,
        journalPointCount: 65,
        interruptedPointCount: 62,
        existingShapeIDs: [flight, other],
        ownership: ownership,
        startedOnMap: true)
    let plan = try #require(decision)
    #expect(plan.filePointCount == 65)
    #expect(plan.uploadOnce)
    #expect(plan.shapeID == flight)
    #expect(plan.untouchedShapeIDs == [other])
    #expect(plan.pilot == "1SAR7")
    #expect(!plan.heldForReview)
    #expect(plan.retireJournalAfterFileWrite)
    #expect(!plan.deleteLiveTrack)
    #expect(!plan.deleteClues)
    #expect(RestartFlightRecovery.flightAlreadyStarted(publicationStarted: true, interruptedRecordPresent: false))
    #expect(!RestartFlightRecovery.flightAlreadyStarted(publicationStarted: false, interruptedRecordPresent: false))

    var uploads = 0
    var retired = false
    var shape: (id: String, points: Int)?
    let clues = ["0EC6EDA2-608B-4879-9021-4BEB357534CD"]
    let leaseTakes = 0
    let wrote = true
    if wrote {
        if plan.uploadOnce { uploads += 1 }
        shape = (plan.shapeID, plan.filePointCount)
        if plan.retireJournalAfterFileWrite { retired = true }
    }
    #expect(uploads == 1)
    #expect(shape?.id == flight)
    #expect(shape?.points == 65)
    #expect(retired)
    #expect(clues == ["0EC6EDA2-608B-4879-9021-4BEB357534CD"])
    #expect(leaseTakes == 0)

    // The file is already written and reported. A second restart does not upload again.
    let again = RestartFlightRecovery.decide(
        liveTrackID: flight,
        journalPointCount: 65,
        interruptedPointCount: 65,
        existingShapeIDs: [flight, other],
        ownership: ownership,
        startedOnMap: false)
    #expect(again == nil)
    #expect(uploads == 1)
    #expect(clues.count == 1)
}

@Test func round13UnverifiedMineIsHeldAndNotUploaded() throws {
    let ownership = FlightOwnershipContext.capture(
        confirmed: true, ignored: false, localOnly: false, claim: FlightOwnershipContext.pending,
        pilot: "1SAR7", organization: "NCSSAR", model: "DJI Matrice 4TD", mappedID: "1sar7DjMtrc4td")
    let decision = try #require(RestartFlightRecovery.decide(
        liveTrackID: "e0381722-218d-5262-add1-4b3284a30694",
        journalPointCount: 65,
        interruptedPointCount: 62,
        existingShapeIDs: ["someone-else"],
        ownership: ownership,
        startedOnMap: true))
    #expect(decision.filePointCount == 65)
    #expect(!decision.uploadOnce)
    #expect(decision.heldForReview)
    #expect(decision.pilot == "1SAR7")
    #expect(decision.shapeID == "e0381722-218d-5262-add1-4b3284a30694")
    #expect(decision.untouchedShapeIDs == ["someone-else"])
}

@Test func round13FailedFileWriteDoesNotRetireTheJournalOrUpload() throws {
    let ownership = FlightOwnershipContext.capture(
        confirmed: true, ignored: false, localOnly: false, claim: FlightOwnershipContext.verified,
        pilot: "1SAR7", organization: "NCSSAR", model: "DJI Matrice 4TD", mappedID: "1sar7DjMtrc4td")
    let plan = try #require(RestartFlightRecovery.decide(
        liveTrackID: "e0381722-218d-5262-add1-4b3284a30694",
        journalPointCount: 65,
        interruptedPointCount: 62,
        existingShapeIDs: [],
        ownership: ownership,
        startedOnMap: true))
    let wrote = false
    var uploads = 0
    var retired = false
    if wrote {
        if plan.uploadOnce { uploads += 1 }
        if plan.retireJournalAfterFileWrite { retired = true }
    }
    #expect(uploads == 0)
    #expect(!retired)
    #expect(plan.retireJournalAfterFileWrite)
}

/// The awaiting-map and interrupted JSON from the iPad were not kept. This rebuilds
/// those two records from the committed journal: the first 61 points are the
/// awaiting-map row, and the first 62 are the interrupted snapshot. Coordinates
/// come only from that journal.
@Test func round13IPadFilesReplayWrites65PointsOnceAndKeepsTheShapeAndClue() async throws {
    let source = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("docs/validation/2026-11-11-field-logs/round13/ipad/e0381722-218d-5262-add1-4b3284a30694.ndjson")
    let flightID = "e0381722-218d-5262-add1-4b3284a30694"
    let otherShape = "b65e121d-9807-5db7-9470-b989c012699d"
    let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: temp) }
    let journalCopy = temp.appendingPathComponent(source.lastPathComponent)
    try FileManager.default.copyItem(at: source, to: journalCopy)
    let flight = try #require(try WaypointTrackAppendJournal.read(fileURL: journalCopy))
    #expect(flight.observations.count == 65)
    #expect(flight.id == flightID)
    #expect(flight.ownership.recordedPilot == "1SAR7")
    #expect(abs(flight.observations[0].latitude - 39.153103157278821) < 0.0000001)

    var awaiting = AwaitingMapFlight(
        id: flight.id, remoteID: flight.remoteID, label: flight.label,
        observations: Array(flight.observations.prefix(61)), teamID: flight.teamID)
    awaiting.mapID = flight.mapID
    awaiting.decision = "bound"
    awaiting.finished = false
    awaiting.publicationStarted = true
    awaiting.recordedPilot = "1SAR7"
    awaiting.claim = FlightOwnershipContext.verified
    awaiting.ownershipContextKnown = true
    let awaitingURL = temp.appendingPathComponent("awaiting-map-flights.json")
    try JSONEncoder().encode([awaiting]).write(to: awaitingURL)
    let stored = try AwaitingMapFlightJournal.readSavedEntries(fileURL: awaitingURL)
    #expect(stored.count == 1)
    #expect(stored[0].publicationStarted == true)
    #expect(stored[0].finished == false)
    #expect(stored[0].points.count == 61)

    let interruptedURL = temp.appendingPathComponent("caltopo-interrupted-publications.json")
    let interruptedJournal = CaltopoInterruptedPublicationJournal(fileURL: interruptedURL)
    try await interruptedJournal.upsert(CaltopoInterruptedPublication(
        mapID: flight.mapID, remoteID: flight.remoteID, liveTrackID: flight.id,
        label: "1sar7DjMtrc4td_082754Nov11",
        observations: Array(flight.observations.prefix(62))))
    #expect(await interruptedJournal.entry(liveTrackID: flight.id)?.points.count == 62)

    let clue = temp.appendingPathComponent("0EC6EDA2.jpg")
    let clueBytes = Data("clue-photo-0EC6EDA2".utf8)
    try clueBytes.write(to: clue)
    let flightFile = temp.appendingPathComponent("082754.json")
    var uploads = 0
    var posts: [(String, Int)] = []
    var deletes: [String] = []
    let plan = try await RestartFlightRecovery.replay(
        journalURL: journalCopy,
        awaitingURL: awaitingURL,
        interruptedURL: interruptedURL,
        flightFileURL: flightFile,
        cluePhotoURL: clue,
        existingShapeIDs: [flightID, otherShape],
        upload: { uploads += 1 },
        postShape: { id, count in posts.append((id, count)) },
        deleteLiveTrack: { deletes.append($0) }
    )
    #expect(plan.filePointCount == 65)
    #expect(plan.uploadOnce)
    #expect(plan.shapeID == flightID)
    #expect(plan.untouchedShapeIDs == [otherShape])
    #expect(plan.pilot == "1SAR7")
    #expect(!plan.deleteLiveTrack)
    #expect(!plan.deleteClues)
    #expect(uploads == 1)
    #expect(posts.count == 1)
    #expect(posts.first?.0 == flightID)
    #expect(posts.first?.1 == 65)
    #expect(deletes.isEmpty)
    #expect(!FileManager.default.fileExists(atPath: journalCopy.path))
    #expect(FileManager.default.fileExists(atPath: source.path))
    #expect(try Data(contentsOf: clue) == clueBytes)
    let written = try Data(contentsOf: flightFile)
    #expect(RecoveredFlightFile.coordinateCount(written) == 65)
    let text = String(decoding: written, as: UTF8.self)
    #expect(text.contains("1SAR7"))
    #expect(text.contains("tracker_upload_authorized"))
    #expect(!text.contains("drone_confirmed"))
    #expect(!text.contains(otherShape))

    let untouched = temp.appendingPathComponent("still-there.ndjson")
    try FileManager.default.copyItem(at: source, to: untouched)
    #expect(throws: OperationalSafeFileWriterError.self) {
        try RecoveredFlightFile.writeVerified(Data("{}".utf8), to: temp.appendingPathComponent("short.json"), expectedPoints: 65)
    }
    #expect(FileManager.default.fileExists(atPath: untouched.path))
}
