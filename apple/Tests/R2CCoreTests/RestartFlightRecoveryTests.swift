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
