import Foundation
import Testing
@testable import R2CCore

private func reviewSample(_ time: TimeInterval, _ latitude: Double, _ longitude: Double, id: String = "RID-1") -> RidObservation {
    RidObservation(source: .bluetoothLegacy, aircraftId: id, receivedAt: Date(timeIntervalSince1970: time),
                   latitude: latitude, longitude: longitude, altitudeMeters: 100)
}

private func reviewFlight(id: String = "RID-1", start: TimeInterval = 1_790_553_873, seconds: TimeInterval = 754) -> AwaitingMapFlight {
    AwaitingMapFlight(remoteID: id, label: "1SAR7Db150Brdg", observations: [
        reviewSample(start, 39.0, -121.0, id: id),
        reviewSample(start + seconds / 2, 39.01, -120.99, id: id),
        reviewSample(start + seconds, 39.005, -120.995, id: id),
    ], teamID: "")
}

private func clue(_ aircraftID: String, at time: TimeInterval, file: String = "f.jpg") -> OperationalClueRecord {
    OperationalClueRecord(capturedAt: Date(timeIntervalSince1970: time), aircraftID: aircraftID, designator: "D",
        droneLatitude: 39, droneLongitude: -121, droneAltitudeMeters: nil, clueLatitude: 39, clueLongitude: -121,
        clueAltitudeMeters: nil, headingDegrees: nil, aglMeters: nil, atoMeters: nil, gimbalAngleDegrees: -90,
        title: "Clue", clueDescription: "", imageFilename: file, thumbnailFilename: file, uploadState: .localOnly)
}

@Test func boundingBoxReportsInsideWhenCurrentLocationIsWithinTheTrackExtent() throws {
    let flight = reviewFlight()
    let box = try #require(flight.boundingBox)
    #expect(box == AwaitingMapBoundingBox(coordinates: [(39.0, -121.0), (39.01, -120.99)]))
    #expect(flight.proximity(latitude: 39.004, longitude: -120.996) == .inside)
    #expect(flight.proximity(latitude: 39.0, longitude: -121.0) == .inside) // corner counts as inside
    #expect(AwaitingMapFlightText.location(.inside) == "You are within this flight's area")
}

@Test func boundingBoxDistanceUsesNearestEdgePoint() throws {
    let flight = reviewFlight()
    // Due north of the box: nearest point is on the top edge at the same longitude.
    let proximity = try #require(flight.proximity(latitude: 39.02, longitude: -120.995))
    let expected = try #require(RidGeometry.relativePosition(fromLatitude: 39.02, longitude: -120.995, toLatitude: 39.01, longitude: -120.995))
    guard case let .away(meters, degrees, cardinal) = proximity else { Issue.record("expected away"); return }
    #expect(abs(meters - expected.distanceMeters) < 0.001)
    #expect(abs(meters - 1_111.95) < 1)
    #expect(degrees == 180)
    #expect(cardinal == "S")
    #expect(AwaitingMapFlightText.location(proximity) == "3650 ft from current location at bearing 180° S")
}

@Test func boundingBoxDistanceUsesNearestCornerOutsideBothRanges() throws {
    let flight = reviewFlight()
    let proximity = try #require(flight.proximity(latitude: 38.99, longitude: -121.01))
    let corner = try #require(RidGeometry.relativePosition(fromLatitude: 38.99, longitude: -121.01, toLatitude: 39.0, longitude: -121.0))
    guard case let .away(meters, degrees, cardinal) = proximity else { Issue.record("expected away"); return }
    #expect(abs(meters - corner.distanceMeters) < 0.001)
    #expect(degrees == Int(corner.bearingDegrees.rounded()))
    #expect(cardinal == "NE")
    // The centroid would be farther than the nearest corner.
    let centroid = try #require(RidGeometry.relativePosition(fromLatitude: 38.99, longitude: -121.01, toLatitude: 39.005, longitude: -120.995))
    #expect(meters < centroid.distanceMeters)
}

@Test func sixteenPointCardinalCoversBoundariesWithoutChangingEightPointHelper() throws {
    let cases: [(Double, String)] = [(0, "N"), (11.24, "N"), (11.25, "NNE"), (47, "NE"), (90, "E"), (191.25, "SSW"),
                                     (212, "SSW"), (225, "SW"), (337.5, "NNW"), (348.74, "NNW"), (348.75, "N"),
                                     (359.9, "N"), (360, "N"), (-22.5, "NNW"), (.nan, "N")]
    for (bearing, name) in cases { #expect(RidGeometry.cardinalDirection16(for: bearing) == name, "\(bearing)") }
    // The existing 8-point helper still reports SW for 212 degrees.
    let radians: Double = 32 * Double.pi / 180
    let targetLatitude: Double = 39 - 0.01 * cos(radians)
    let targetLongitude: Double = -121 - 0.01 * sin(radians) / cos(39 * Double.pi / 180)
    let position = try #require(RidGeometry.relativePosition(fromLatitude: 39, longitude: -121,
        toLatitude: targetLatitude, longitude: targetLongitude))
    #expect(Int(position.bearingDegrees.rounded()) == 212)
    #expect(position.cardinalDirection == "SW")
}

@Test func distanceFormattingSwitchesFromFeetToMilesAtOneMile() {
    #expect(AwaitingMapFlightText.distance(meters: 128.016) == "420 ft")
    #expect(AwaitingMapFlightText.distance(meters: 1.4) == "0 ft")
    #expect(AwaitingMapFlightText.distance(meters: 129.6) == "430 ft") // 425.2 ft rounds to 430
    #expect(AwaitingMapFlightText.distance(meters: 1_609.0) == "5280 ft") // 5278.9 ft, still below a mile
    #expect(AwaitingMapFlightText.distance(meters: 1_609.344) == "1.0 mi")
    #expect(AwaitingMapFlightText.distance(meters: 1_609.344 * 1.3) == "1.3 mi")
    #expect(AwaitingMapFlightText.distance(meters: 1_609.344 * 12.46) == "12.5 mi")
}

@Test func durationCluePhotoAndDiscardWording() {
    #expect(reviewFlight(seconds: 754).durationSeconds == 754)
    #expect(AwaitingMapFlightText.duration(seconds: 754) == "Duration 12m 34s")
    #expect(AwaitingMapFlightText.duration(seconds: 7) == "Duration 0m 07s")
    #expect(AwaitingMapFlightText.duration(seconds: 3_725) == "Duration 1h 02m 05s")
    #expect(AwaitingMapFlightText.cluePhotos(0) == "0 clue photos")
    #expect(AwaitingMapFlightText.cluePhotos(1) == "1 clue photo")
    #expect(AwaitingMapFlightText.discardMessage(cluePhotoCount: 0) == "This permanently deletes the track from this device.")
    #expect(AwaitingMapFlightText.discardMessage(cluePhotoCount: 3) == "This permanently deletes the track and 3 clue photos from this device.")
    #expect(AwaitingMapFlightText.flightCountHeader(1) == "1 FLIGHT ON THIS DEVICE")
    #expect(AwaitingMapFlightText.flightCountHeader(3) == "3 FLIGHTS ON THIS DEVICE")
    #expect(AwaitingMapFlightText.locationSummary(accuracyMeters: 4.9, fixTime: "7:03 AM") == "Distances from your current location · GPS ±16 ft · 7:03 AM")
    #expect(AwaitingMapFlightText.locationSummary(accuracyMeters: nil, fixTime: "7:03 AM") == "Distances from your current location · 7:03 AM")
}

@Test func clueWindowSpansFirstPointThroughThirtySecondsAfterLastPoint() {
    let flight = reviewFlight()
    let first = flight.firstTime.timeIntervalSince1970, last = flight.lastTime.timeIntervalSince1970
    #expect(AwaitingMapClueMatch.matches(clue("RID-1", at: first), flight: flight))
    #expect(AwaitingMapClueMatch.matches(clue("rid1", at: last), flight: flight)) // canonical ID
    #expect(AwaitingMapClueMatch.matches(clue("RID-1", at: last + 30), flight: flight))
    #expect(!AwaitingMapClueMatch.matches(clue("RID-1", at: last + 30.5), flight: flight))
    #expect(!AwaitingMapClueMatch.matches(clue("RID-1", at: first - 0.5), flight: flight))
    #expect(!AwaitingMapClueMatch.matches(clue("RID-2", at: first + 10), flight: flight))
    // A trailing-window clue inside the next flight of the same aircraft belongs to that flight.
    let next = reviewFlight(start: last + 10)
    let tail = clue("RID-1", at: last + 20), own = clue("RID-1", at: first + 5), lateTail = clue("RID-1", at: last + 5)
    #expect(AwaitingMapClueMatch.ownedClues([tail, own, lateTail], flight: flight, otherFlights: [next]).map(\.id) == [own.id, lateTail.id])
    #expect(AwaitingMapClueMatch.ownedClues([tail, own], flight: flight, otherFlights: []).count == 2)
}

@Test func archiveFilenamesMatchTheArchiveWriter() throws {
    let flight = reviewFlight()
    let tz = try #require(TimeZone(identifier: "America/Los_Angeles"))
    let names = AwaitingMapFlightArchive.filenames(remoteID: flight.remoteID, startedAt: flight.firstTime, timeZone: tz)
    let base = "RID-1-" + OperationalDiagnosticLogFormat.filenameTimestamp(flight.firstTime, timeZone: tz)
    #expect(names == [base + ".json", base + ".kmz"])
}

@Test @MainActor func discardDeletesExactlyThatFlightsPhotosArchiveAndEntry() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let tz = TimeZone.current
    let journal = AwaitingMapFlightJournal(fileURL: root.appendingPathComponent("pending.json"))
    let start: TimeInterval = 1_790_553_873
    // `record` ignores a finished first sighting, so create it live and then finish it.
    try journal.record(remoteID: "RID-1", label: "Target", observations: reviewFlight(start: start).points.map { $0.observation(remoteID: "RID-1") },
                       mapID: "", teamID: "", finished: false)
    try journal.record(remoteID: "RID-1", label: "Target", observations: reviewFlight(start: start).points.map { $0.observation(remoteID: "RID-1") },
                       mapID: "", teamID: "", finished: true)
    try journal.record(remoteID: "RID-2", label: "Other", observations: reviewFlight(id: "RID-2", start: start).points.map { $0.observation(remoteID: "RID-2") },
                       mapID: "", teamID: "", finished: false)
    let target = try #require(journal.entries.first { $0.remoteID == "RID-1" })
    let other = try #require(journal.entries.first { $0.remoteID == "RID-2" })

    // Archive files: the target's GeoJSON and KMZ, plus neighbours that must survive.
    let archive = root.appendingPathComponent("archive")
    let day = archive.appendingPathComponent(AppleFlightStorage.dayName(target.lastTime))
    try FileManager.default.createDirectory(at: day, withIntermediateDirectories: true)
    let targetNames = AwaitingMapFlightArchive.filenames(remoteID: "RID-1", startedAt: target.firstTime, timeZone: tz)
    let keepNames = AwaitingMapFlightArchive.filenames(remoteID: "RID-2", startedAt: other.firstTime, timeZone: tz)
        + AwaitingMapFlightArchive.filenames(remoteID: "RID-1", startedAt: target.firstTime.addingTimeInterval(-3_600), timeZone: tz)
        + ["clues.json", "r2c_reported.txt"]
    for name in targetNames + keepNames { try Data("x".utf8).write(to: day.appendingPathComponent(name)) }

    // Clue photos: two belong to the target, one to the other aircraft, one is from earlier.
    var store = [clue("RID-1", at: start + 10, file: "a.jpg"), clue("RID-1", at: start + 754 + 25, file: "b.jpg"),
                 clue("RID-2", at: start + 10, file: "c.jpg"), clue("RID-1", at: start - 60, file: "d.jpg")]
    for record in store { try Data("jpg".utf8).write(to: root.appendingPathComponent(record.imageFilename)) }
    let owned = AwaitingMapClueMatch.ownedClues(store, flight: target, otherFlights: journal.entries)
    #expect(owned.map(\.imageFilename) == ["a.jpg", "b.jpg"])

    let result = await AwaitingMapFlightDiscarder.discard(
        flight: target, journal: journal, clueIDs: owned.map(\.id),
        deleteClue: { id in
            guard let index = store.firstIndex(where: { $0.id == id }) else { return false }
            try? FileManager.default.removeItem(at: root.appendingPathComponent(store[index].imageFilename))
            store.remove(at: index)
            return true
        },
        deleteArchive: { AwaitingMapFlightArchive.deleteFiles(for: target, root: archive, timeZone: tz) })

    #expect(result.succeeded)
    #expect(result.entryRemoved)
    #expect(Set(result.deletedClueIDs) == Set(owned.map(\.id)))
    #expect(result.archive.deleted.sorted() == targetNames.map { "\(day.lastPathComponent)/\($0)" }.sorted())
    #expect(journal.entries.map(\.id) == [other.id])
    #expect(AwaitingMapFlightJournal(fileURL: root.appendingPathComponent("pending.json")).entries.map(\.id) == [other.id])
    #expect(store.map(\.imageFilename) == ["c.jpg", "d.jpg"])
    for name in ["a.jpg", "b.jpg"] { #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent(name).path)) }
    for name in ["c.jpg", "d.jpg"] { #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent(name).path)) }
    for name in targetNames { #expect(!FileManager.default.fileExists(atPath: day.appendingPathComponent(name).path)) }
    for name in keepNames { #expect(FileManager.default.fileExists(atPath: day.appendingPathComponent(name).path)) }
    #expect(result.logSummary(for: target).contains("clues=2"))
}

@Test @MainActor func discardKeepsTheEntryWhenAFileCannotBeDeleted() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let journal = AwaitingMapFlightJournal(fileURL: root.appendingPathComponent("pending.json"))
    try journal.record(remoteID: "RID-1", label: "Target", observations: [reviewSample(1_000, 39, -121)], mapID: "", teamID: "", finished: false)
    let target = try #require(journal.entries.first)
    let result = await AwaitingMapFlightDiscarder.discard(flight: target, journal: journal, clueIDs: [UUID()],
        deleteClue: { _ in false }, deleteArchive: { .init() })
    #expect(!result.succeeded)
    #expect(!result.entryRemoved)
    #expect(journal.entries.map(\.id) == [target.id])
}
