import Foundation
import Testing
@testable import R2CCore

// Waypoint binding for clue snapshots. Android mirrors these cases in ClueBindingTest.kt.

private let t0: Int64 = 1_790_553_873_000

private func point(_ offsetMs: Int64, _ latitude: Double = 39.0, _ longitude: Double = -121.0,
                   receivedLagMs: Int64 = 150) -> ClueBindingPoint {
    ClueBindingPoint(timeMs: t0 + offsetMs, receivedAtMs: t0 + offsetMs + receivedLagMs, latitude: latitude,
                     longitude: longitude, altitudeMeters: 120, source: "bluetoothLegacy", droneClock: true)
}

/// Points along a line moving north ~11 m per 100 µdeg so nothing counts as hovering.
private func moving(_ offsets: [Int64]) -> [ClueBindingPoint] {
    offsets.enumerated().map { point($1, 39.0 + Double($0) * 0.001, -121.0) }
}

private func bind(_ points: [ClueBindingPoint], capture: Int64 = 0, receivedAt: Int64? = nil,
                  frame: ClueBindingFramePosition? = nil, ended: Bool = false, now: Int64? = nil,
                  flightID: String? = "flight-1") -> ClueBinding {
    ClueBinder.bind(aircraftID: "RID-1", flightID: flightID, captureTimeMs: t0 + capture,
                    captureTimeSource: "stream-pts", captureReceivedAtMs: receivedAt ?? (t0 + capture + 200),
                    points: points, framePosition: frame, originIsWaypoint: frame == nil,
                    flightEnded: ended, nowReceivedAtMs: now ?? (t0 + capture + 1_000))
}

private func record(capture: Date = Date(timeIntervalSince1970: 1_790_553_873), binding: ClueBinding? = nil,
                    state: OperationalClueUploadState = .pending, aircraft: String = "RID-1") -> OperationalClueRecord {
    OperationalClueRecord(capturedAt: capture, aircraftID: aircraft, designator: "D",
        droneLatitude: 39, droneLongitude: -121, droneAltitudeMeters: nil, clueLatitude: 39.0005, clueLongitude: -121.0005,
        clueAltitudeMeters: 30, headingDegrees: nil, aglMeters: nil, atoMeters: nil, gimbalAngleDegrees: -45,
        title: "Clue", clueDescription: "time: 08:00:00", imageFilename: "a.jpg", thumbnailFilename: "a-thumb.jpg",
        uploadState: state, destinationMapID: "MAP", destinationTeamID: "TEAM", binding: binding)
}

@Test func bindingQualityThresholds() {
    #expect(ClueBinder.quality(offsetMs: 0, hovering: false) == .exact)
    #expect(ClueBinder.quality(offsetMs: 2_000, hovering: false) == .exact)
    #expect(ClueBinder.quality(offsetMs: -2_000, hovering: false) == .exact)
    #expect(ClueBinder.quality(offsetMs: 2_001, hovering: false) == .approximate)
    #expect(ClueBinder.quality(offsetMs: -10_000, hovering: false) == .approximate)
    #expect(ClueBinder.quality(offsetMs: 10_001, hovering: false) == .approximateWarning)
    #expect(ClueBinder.quality(offsetMs: -30_000, hovering: false) == .approximateWarning)
    #expect(ClueBinder.quality(offsetMs: 30_001, hovering: false) == .unbound)
    #expect(ClueBinder.quality(offsetMs: nil, hovering: false) == .unbound)
    // Hovering is never flagged while bound.
    #expect(ClueBinder.quality(offsetMs: 25_000, hovering: true) == .exact)
    #expect(ClueBinder.quality(offsetMs: 30_001, hovering: true) == .unbound)
}

@Test func bindsNearestWaypointBeforeOrAfterWithSignedOffset() throws {
    // Next waypoint 0.8 s after the capture beats the previous one 1.2 s before.
    let after = bind(moving([-1_200, 800, 3_000]))
    #expect(after.offsetMs == 800)
    #expect(after.waypoint?.timeMs == t0 + 800)
    #expect(after.quality == .exact)
    #expect(ClueBindingText.offset(try #require(after.offsetMs)) == "+0.800 s")
    // Previous waypoint nearer: negative offset (waypoint minus capture).
    let before = bind(moving([-250, 900]))
    #expect(before.offsetMs == -250)
    #expect(ClueBindingText.offset(-250) == "-0.250 s")
    // Ties go to the earlier waypoint.
    #expect(bind(moving([-500, 500])).offsetMs == -500)
}

@Test func signedOffsetKeepsMillisecondPrecision() {
    #expect(ClueBindingText.offset(1_234) == "+1.234 s")
    #expect(ClueBindingText.offset(-1_234) == "-1.234 s")
    #expect(ClueBindingText.offset(0) == "+0.000 s")
    #expect(ClueBindingText.offset(-1) == "-0.001 s")
    #expect(ClueBindingText.offset(12_005) == "+12.005 s")
    let binding = bind(moving([-1_234, 5_000]))
    #expect(binding.offsetMs == -1_234)
    let data = Dictionary(uniqueKeysWithValues: ClueBindingText.extendedData(binding))
    #expect(data["r2c_binding_offset_s"] == "-1.234")
    #expect(data["r2c_binding_quality"] == "exact")
    #expect(ClueBindingText.descriptionLines(binding).contains("  Offset: -1.234 s (waypoint minus capture, drone clock)"))
}

@Test func bindingUsesCaptureTimeNeverSubmissionOrReceiveTime() {
    // The app received the frame (and the operator submitted) 20 s later; binding still uses the capture.
    let binding = bind(moving([-300, 19_800]), capture: 0, receivedAt: t0 + 20_000, now: t0 + 21_000)
    #expect(binding.offsetMs == -300)
    #expect(binding.captureTimeMs == t0)
    #expect(binding.captureReceivedAtMs == t0 + 20_000)
}

@Test func approximateBindingsAreFlaggedUnlessHovering() {
    let approximate = bind(moving([-6_000, 9_000]))
    #expect(approximate.quality == .approximate)
    #expect(approximate.flagged)
    #expect(ClueBindingText.qualityLabel(approximate) == "approximate")
    let warning = bind(moving([-14_000, 16_000]))
    #expect(warning.quality == .approximateWarning)
    #expect(ClueBindingText.qualityLabel(warning) == "approximate - check position")
    // Same timing while hovering (bracketing waypoints within 5 m): never flagged.
    let hover = bind([point(-14_000, 39.0, -121.0), point(16_000, 39.00002, -121.0)])
    #expect(hover.hovering)
    #expect(hover.quality == .exact)
    #expect(!hover.flagged)
    #expect(ClueBindingText.qualityLabel(hover) == "exact (hovering)")
    // Frame position within 5 m of the waypoint also means hovering.
    let frame = bind(moving([-8_000, 20_000]), frame: ClueBindingFramePosition(latitude: 39.00003, longitude: -121.0))
    #expect(frame.hovering)
    #expect(frame.quality == .exact)
}

@Test func noBindingBeyondThirtySeconds() {
    let binding = bind(moving([-45_000, -40_000]), ended: true)
    #expect(binding.quality == .unbound)
    #expect(binding.waypoint == nil)
    #expect(binding.offsetMs == nil)
    #expect(binding.nearestOffsetMs == -40_000)
    #expect(binding.nearest?.timeMs == t0 - 40_000)
    #expect(ClueBindingText.formSummary(binding) == "not bound (no waypoint within 30 s); nearest -40.000 s")
    #expect(Dictionary(uniqueKeysWithValues: ClueBindingText.extendedData(binding))["r2c_binding_offset_s"] == nil)
}

@Test func bindingStaysOpenUntilNoNearerWaypointCanArrive() {
    // Nearest so far is 1.5 s before; a waypoint up to 1.5 s after could still be nearer.
    let open = bind(moving([-1_500]), now: t0 + 1_000)
    #expect(!open.final)
    #expect(ClueBindingText.formSummary(open).hasSuffix(" · waiting for next fix"))
    #expect(ClueBindingText.descriptionLines(open).contains("  Binding: provisional"))
    // A nearer fix arrives 0.4 s after the capture: rebinds and finalizes (latest ≥ capture + 0.4 s).
    let refreshed = ClueBinder.refresh(open, points: moving([-1_500, 400]), flightEnded: false, nowReceivedAtMs: t0 + 1_500)
    #expect(refreshed.offsetMs == 400)
    #expect(refreshed.final)
    // A final binding never changes.
    #expect(ClueBinder.refresh(refreshed, points: moving([-1_500, 0]), flightEnded: false, nowReceivedAtMs: t0 + 2_000) == refreshed)
    // Flight end finalizes.
    #expect(ClueBinder.refresh(open, points: moving([-1_500]), flightEnded: true, nowReceivedAtMs: t0 + 1_100).final)
    // So does waiting 35 s on the app clock.
    #expect(!ClueBinder.refresh(open, points: moving([-1_500]), flightEnded: false, nowReceivedAtMs: t0 + 200 + 34_999).final)
    #expect(ClueBinder.refresh(open, points: moving([-1_500]), flightEnded: false, nowReceivedAtMs: t0 + 200 + 35_000).final)
    // Unavailable points keep the stored binding.
    let kept = ClueBinder.refresh(open, points: [], flightEnded: true, nowReceivedAtMs: t0 + 1_100)
    #expect(kept.offsetMs == -1_500)
    #expect(kept.final)
}

@Test func streamDroneClockAnchorsPTSAndResetsOnJump() {
    var clock = StreamDroneClock()
    #expect(clock.droneTimeMs(ptsMicroseconds: 1_000_000) == nil)
    clock.observe(ptsMicroseconds: 1_000_000, receivedAtMs: t0 + 1_300) // 300 ms late
    clock.observe(ptsMicroseconds: 2_000_000, receivedAtMs: t0 + 2_120) // least delayed
    clock.observe(ptsMicroseconds: 3_000_000, receivedAtMs: t0 + 3_900) // jitter never moves frames later
    #expect(clock.droneTimeMs(ptsMicroseconds: 2_500_000) == t0 + 2_620)
    #expect(clock.droneTimeMs(ptsMicroseconds: nil) == nil)
    // New stream session (PTS restarts): re-anchor.
    clock.observe(ptsMicroseconds: 100_000, receivedAtMs: t0 + 60_000)
    #expect(clock.droneTimeMs(ptsMicroseconds: 100_000) == t0 + 60_000)
}

@Test func ridTimestampResolvesToNearestHour() throws {
    let receivedAt = try #require(ISO8601DateFormatter().date(from: "2026-10-06T15:00:01Z"))
    let ms = Int64(receivedAt.timeIntervalSince1970 * 1_000)
    // 59:59.5 past the hour belongs to the previous hour.
    let late = try #require(RidDroneTimestamp.utcMilliseconds(tenths: 35_995, receivedAtMs: ms))
    #expect(late == ms - 1_000 - 500)
    #expect(RidDroneTimestamp.utcMilliseconds(tenths: 5, receivedAtMs: ms) == ms - 1_000 + 500)
    #expect(RidDroneTimestamp.utcMilliseconds(tenths: 0xFFFF, receivedAtMs: ms) == nil)
}

@Test func ownershipUsesLeadingWindowNearestPointAndBoundFlight() {
    let base = Date(timeIntervalSince1970: 1_790_553_873)
    func times(_ seconds: [TimeInterval]) -> [Date] { seconds.map { base.addingTimeInterval($0) } }
    let first = AwaitingMapClueMatch.Candidate(id: "A", remoteID: "RID-1", times: times([0, 60, 120]))
    let second = AwaitingMapClueMatch.Candidate(id: "B", remoteID: "RID-1", times: times([150, 200]))
    let other = AwaitingMapClueMatch.Candidate(id: "C", remoteID: "RID-2", times: times([0, 200]))
    func owner(_ at: TimeInterval, bound: String? = nil) -> String? {
        AwaitingMapClueMatch.ownerID(clueAircraftID: "RID-1", capturedAt: base.addingTimeInterval(at),
                                     boundFlightID: bound, candidates: [first, second, other])
    }
    #expect(owner(-29) == "A")        // leading window
    #expect(owner(-31) == nil)
    #expect(owner(130) == "A")        // 10 s after A's last vs 20 s before B's first
    #expect(owner(140) == "B")        // nearer to B's first point
    #expect(owner(135) == "A")        // tie: earlier flight
    #expect(owner(140, bound: "A") == "A") // bound flight wins
    #expect(owner(140, bound: "C") == "B") // a different aircraft's flight is never the owner
    #expect(owner(231) == nil)
}

@Test func uploadWaitsForFinalBinding() {
    let open = bind(moving([-1_500]))
    #expect(!record(binding: open).canAutomaticallyPublish(mapID: "MAP", teamID: "TEAM"))
    var closed = open; closed.final = true
    #expect(record(binding: closed).canAutomaticallyPublish(mapID: "MAP", teamID: "TEAM"))
    #expect(record().canAutomaticallyPublish(mapID: "MAP", teamID: "TEAM")) // pre-binding clues
}

@Test func clueRecordsWithoutBindingStillDecode() throws {
    let legacy = record()
    var json = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(legacy)) as? [String: Any])
    json.removeValue(forKey: "binding")
    let decoded = try JSONDecoder().decode(OperationalClueRecord.self, from: JSONSerialization.data(withJSONObject: json))
    #expect(decoded.binding == nil)
    #expect(decoded.bindingFinal)
    #expect(decoded.publishedDescription == legacy.clueDescription)
    let bound = record(binding: bind(moving([-1_234, 4_000]), ended: true))
    let roundTrip = try JSONDecoder().decode(OperationalClueRecord.self, from: JSONEncoder().encode(bound))
    #expect(roundTrip.binding == bound.binding)
}

@Test func publishedDescriptionCarriesTheBinding() {
    let binding = bind(moving([1_234, 4_000]), ended: true)
    let text = record(binding: binding).publishedDescription
    #expect(text.hasPrefix("time: 08:00:00\n\nWaypoint binding:\n"))
    #expect(text.contains("  Offset: +1.234 s (waypoint minus capture, drone clock)"))
    #expect(text.contains("  Quality: exact"))
    #expect(text.contains("  Capture: "))
    #expect(text.contains("(stream-pts)"))
    #expect(text.contains("App receive times (diagnostic): capture "))
    #expect(!text.contains("provisional"))
}

@Test func nearerWaypointMovesAWaypointProjectedClue() throws {
    let open = bind([point(-1_000, 39.0, -121.0)], now: t0 + 500)
    #expect(open.originIsWaypoint)
    let saved = record(binding: open)
    let updated = ClueBindingUpdate.apply(saved, points: [point(-1_000, 39.0, -121.0), point(300, 39.0001, -121.0002)],
                                          flightEnded: false, nowReceivedAtMs: t0 + 800)
    let binding = try #require(updated.binding)
    #expect(binding.offsetMs == 300)
    #expect(binding.final)
    #expect(abs(updated.clueLatitude - (saved.clueLatitude + 0.0001)) < 1e-9)
    #expect(abs(updated.clueLongitude - (saved.clueLongitude - 0.0002)) < 1e-7)
    #expect(updated.droneLatitude == 39.0001)
    #expect(binding.movedFrom == ClueBindingFramePosition(latitude: saved.clueLatitude, longitude: saved.clueLongitude))
    #expect(ClueBindingText.descriptionLines(binding).contains { $0.hasPrefix("  Position: moved after submit") })
    // A frame-positioned (SEI) clue keeps its projection; only the binding updates.
    let seiOpen = bind([point(-1_000)], frame: ClueBindingFramePosition(latitude: 39.2, longitude: -121.2), now: t0 + 500)
    let seiUpdated = ClueBindingUpdate.apply(record(binding: seiOpen), points: [point(-1_000), point(300, 39.0001, -121.0002)],
                                             flightEnded: false, nowReceivedAtMs: t0 + 800)
    #expect(seiUpdated.binding?.offsetMs == 300)
    #expect(seiUpdated.clueLatitude == saved.clueLatitude)
    #expect(seiUpdated.binding?.movedFrom == nil)
}

@Test func flightKMZIsWrittenWithoutClues() throws {
    let points = [OperationalFlightKMZPoint(latitude: 39, longitude: -121, altitudeMeters: 100),
                  OperationalFlightKMZPoint(latitude: 39.001, longitude: -121.001, altitudeMeters: nil)]
    let data = try OperationalFlightKMZ.archive(title: "Track & Co", points: points, clues: [])
    #expect(OperationalFlightKMZ.isValidArchive(data))
    let entries = try OperationalZipArchive.decode(data)
    #expect(entries.map(\.path) == ["doc.kml"])
    let kml = String(decoding: try #require(entries.first?.data), as: UTF8.self)
    #expect(kml.contains("<name>Track &amp; Co</name>"))
    #expect(kml.contains("<LineString>"))
    #expect(kml.contains("-121.001000,39.001000,0.0"))
    #expect(!kml.contains("<TimeStamp>"))
    // A one-point flight still gets a placemark.
    let single = OperationalFlightKMZ.kml(title: "One", points: [points[0]], clues: [])
    #expect(single.contains("<Point><coordinates>-121.000000,39.000000,100.0</coordinates></Point>"))
    #expect(!single.contains("<LineString>"))
}

@Test func flightKMZIncludesLocalMarkersAndBindingData() throws {
    let binding = bind(moving([-1_234, 4_000]), ended: true)
    let published = OperationalFlightKMZClue(record: record(binding: binding, state: .published), jpegData: Data([0xFF, 0xD8, 0xFF]))
    let local = OperationalFlightKMZClue(record: record(state: .localOnly), jpegData: nil)
    #expect(local.localOnly)
    let data = try OperationalFlightKMZ.archive(title: "T", points: [], clues: [published, local])
    let entries = try OperationalZipArchive.decode(data)
    #expect(entries.map(\.path) == ["doc.kml", "files/clue_0.jpg"])
    let kml = String(decoding: entries[0].data, as: UTF8.self)
    #expect(kml.contains("<Data name=\"r2c_local_only\"><value>true</value></Data>"))
    #expect(kml.contains("<Data name=\"r2c_local_only\"><value>false</value></Data>"))
    #expect(kml.contains("<Data name=\"r2c_binding_offset_s\"><value>-1.234</value></Data>"))
    #expect(kml.contains("<Data name=\"r2c_flight_id\"><value>flight-1</value></Data>"))
    #expect(kml.contains("Offset: -1.234 s (waypoint minus capture, drone clock)"))
    #expect(kml.contains("<img src=\"files/clue_0.jpg\"/>"))
}

@Test func archivedFlightOwnershipAndBindingFallback() throws {
    func archive(_ start: Int64, _ count: Int) -> RidTrackGeoJSON.ArchiveContents {
        RidTrackGeoJSON.ArchiveContents(title: "T", remoteID: "RID-1",
            points: (0..<count).map { point(start + Int64($0) * 10_000, 39 + Double($0) * 0.001, -121) })
    }
    let archives = ["d/a.json": archive(0, 7), "d/b.json": archive(100_000, 5)]
    let early = record(capture: Date(timeIntervalSince1970: Double(t0 + 20_000) / 1_000))
    let late = record(capture: Date(timeIntervalSince1970: Double(t0 + 95_000) / 1_000)) // 35 s after a, 5 s before b
    let other = record(capture: Date(timeIntervalSince1970: Double(t0 + 20_000) / 1_000), aircraft: "RID-2")
    #expect(OperationalFlightKMZ.ownedClues(archiveID: "d/a.json", archives: archives, clues: [late, other, early]).map(\.id) == [early.id])
    #expect(OperationalFlightKMZ.ownedClues(archiveID: "d/b.json", archives: archives, clues: [late, early]).map(\.id) == [late.id])
    // A clue saved without a binding binds to the archived flight's nearest stored point.
    let filled = OperationalFlightKMZ.bindingFallback(early, contents: try #require(archives["d/a.json"]), nowReceivedAtMs: t0 + 999_000)
    #expect(filled.binding?.offsetMs == 0)
    #expect(filled.binding?.final == true)
    #expect(filled.binding?.captureTimeSource == "app-receive")
}

@Test func archiveDecodeReadsAndroidStringsAndAppleNumbers() throws {
    let android = """
    {"type":"FeatureCollection","features":[{"type":"Feature","properties":{"title":"D_1","r2c_prop":{"rid":"RID-1"},
    "r2c_point_received_ms":[1790553873150]},"geometry":{"type":"LineString","coordinates":[["-121.000000","39.000000","120","1790553873000"],["-121.001","39.001","-1000","1790553874000"]]}}]}
    """
    let contents = try #require(RidTrackGeoJSON.decodeArchive(Data(android.utf8)))
    #expect(contents.title == "D_1")
    #expect(contents.remoteID == "RID-1")
    #expect(contents.points.map(\.timeMs) == [1_790_553_873_000, 1_790_553_874_000])
    #expect(contents.points[0].receivedAtMs == 1_790_553_873_150)
    #expect(contents.points[1].receivedAtMs == nil)
    #expect(contents.points[1].altitudeMeters == nil)
    #expect(RidTrackGeoJSON.decodeArchive(Data("{}".utf8)) == nil)
}

@Test func geoJSONStoresDroneTimeAndDiagnosticReceiveTime() async throws {
    let start = Date(timeIntervalSince1970: 1_790_553_873)
    let store = RidTrackStore()
    for second in [0.0, 10.0] {
        _ = await store.ingest(RidObservation(source: .bluetoothLegacy, aircraftId: "RID01", receivedAt: start.addingTimeInterval(second + 0.4),
            latitude: 39 + second * 0.0001, longitude: -121, altitudeMeters: 100, horizontalAccuracyCode: 11,
            droneTimestamp: start.addingTimeInterval(second)))
    }
    let track = try #require(await store.snapshot().first)
    let encoded = try RidTrackGeoJSON.encode(track: track)
    let contents = try #require(RidTrackGeoJSON.decodeArchive(encoded))
    #expect(contents.points.map(\.timeMs) == [1_790_553_873_000, 1_790_553_883_000])
    #expect(contents.points.map(\.receivedAtMs) == [1_790_553_873_400, 1_790_553_883_400])
    #expect(contents.points.allSatisfy { $0.droneClock })
}

@Test func safeWriterReplacesVerifiesAndSweeps() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let day = root.appendingPathComponent("2026-10-06")
    try FileManager.default.createDirectory(at: day, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let target = day.appendingPathComponent("RID-1.kmz")
    let good = try OperationalFlightKMZ.archive(title: "A", points: [], clues: [])
    try OperationalSafeFileWriter.replace(target, with: good)
    #expect(try Data(contentsOf: target) == good)
    #expect(!FileManager.default.fileExists(atPath: OperationalSafeFileWriter.partialURL(for: target).path))
    // Invalid content never replaces the existing file.
    #expect(throws: OperationalSafeFileWriterError.self) { try OperationalSafeFileWriter.replace(target, with: Data("torn".utf8)) }
    #expect(try Data(contentsOf: target) == good)
    // Launch sweep: a complete partial is finished, a torn one is rolled back.
    let newer = try OperationalFlightKMZ.archive(title: "B", points: [], clues: [])
    try newer.write(to: OperationalSafeFileWriter.partialURL(for: target))
    let other = day.appendingPathComponent("RID-2.json")
    try Data("{\"ok\":true}".utf8).write(to: other)
    try Data("{\"ok\":".utf8).write(to: OperationalSafeFileWriter.partialURL(for: other))
    let actions = OperationalSafeFileWriter.sweep(directory: root)
    #expect(actions.contains(.completed("2026-10-06/RID-1.kmz.partial")))
    #expect(actions.contains(.discarded("2026-10-06/RID-2.json.partial")))
    #expect(try Data(contentsOf: target) == newer)
    #expect(try Data(contentsOf: other) == Data("{\"ok\":true}".utf8))
    #expect(try FileManager.default.contentsOfDirectory(atPath: day.path).sorted() == ["RID-1.kmz", "RID-2.json"])
}

@Test func rewriteQueueIsDurableAndDeduplicated() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let url = root.appendingPathComponent("rewrites.json")
    let queue = FlightArchiveRewriteQueue(fileURL: url)
    let job = FlightArchiveRewriteJob(aircraftID: "RID-1", dayDirectory: "2026-10-06", geoJSONFilename: "a.json", kmzFilename: "a.kmz")
    try queue.enqueue(job)
    try queue.enqueue(job)
    #expect(FlightArchiveRewriteQueue(fileURL: url).jobs().map(\.id) == ["2026-10-06/a.json"])
    try queue.recordFailure(job.id, error: "disk full")
    let failed = try #require(FlightArchiveRewriteQueue(fileURL: url).jobs().first)
    #expect(failed.attempts == 1)
    #expect(failed.lastError == "disk full")
    try queue.complete(job.id)
    #expect(FlightArchiveRewriteQueue(fileURL: url).jobs().isEmpty)
}
