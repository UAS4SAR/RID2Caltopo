import Foundation
import Testing
@testable import R2CCore

@Test func round11ArchiveFileIsNamedFromTheGrant() async throws {
    var calendar = Calendar(identifier: .gregorian)
    let pacific = try #require(TimeZone(identifier: "America/Los_Angeles"))
    calendar.timeZone = pacific
    func at(_ hour: Int, _ minute: Int, _ second: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: 10, hour: hour, minute: minute, second: second))!
    }
    let aircraft = "1581F8HGX255S00A0FZT"
    let shadow = at(17, 39, 17)
    let grant = at(17, 41, 21)
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let shadowName = RidTrackGeoJSON.suggestedFilenameBase(aircraftID: aircraft, startDate: shadow, timeZone: pacific) + ".json"
    try Data("{\"kept\":true}".utf8).write(to: directory.appendingPathComponent(shadowName))
    let kmz = URL(fileURLWithPath: shadowName).deletingPathExtension().appendingPathExtension("kmz").lastPathComponent
    try Data([1, 2, 3]).write(to: directory.appendingPathComponent(kmz))

    let grantName = OwnedFlightFileName.adopt(
        directory: directory, aircraftID: aircraft, shadowStart: shadow, grant: grant, timeZone: pacific)

    #expect(grantName.contains("174121"))
    #expect(!grantName.contains("173917"))
    #expect(!FileManager.default.fileExists(atPath: directory.appendingPathComponent(shadowName).path))
    #expect(try String(contentsOf: directory.appendingPathComponent(grantName), encoding: .utf8) == "{\"kept\":true}")
    #expect(!FileManager.default.fileExists(atPath: directory.appendingPathComponent(kmz).path))

    let store = RidTrackStore(policy: .init(activeTimeout: 30))
    _ = await store.ingest(fileNameObservation(aircraft: aircraft, at: shadow))
    let raw = try #require(await store.track(aircraftID: aircraft))
    let namedFromGrant = RidTrackGeoJSON.suggestedFilename(
        for: raw, ownershipGrantedAt: grant, timeZone: pacific)
    #expect(namedFromGrant.contains("174121"))
    #expect(!namedFromGrant.contains("173917"))
}

@Test func round11JournalDoesNotBecomeASecondFile() throws {
    let serial = "1581F8HGX255S00A0FZT"
    let savedName = "\(serial)-10Nov2026-170808-PST-0800.json"
    let saved = RecoveredJournalDedup.SavedFlight(
        remoteID: serial, dayDirectory: "2026-11-10", fileName: savedName, pointTimesMs: [2_000, 3_000, 4_000])
    #expect(RecoveredJournalDedup.decide(
        remoteID: serial.lowercased(), journalTimesMs: [2_000, 3_000, 4_000], saved: [saved]
    ) == .retire(fileName: savedName))
    #expect(RecoveredJournalDedup.decide(
        remoteID: serial, journalTimesMs: [1_000, 2_000, 3_000, 4_000], saved: [saved]
    ) == .merge(dayDirectory: "2026-11-10", fileName: savedName, missingTimesMs: [1_000]))
    #expect(RecoveredJournalDedup.decide(
        remoteID: serial, journalTimesMs: [20_000, 21_000], saved: [saved]
    ) == .writeNew)
    #expect(RecoveredJournalDedup.decide(
        remoteID: "OTHER", journalTimesMs: [2_000, 3_000, 4_000], saved: [saved]
    ) == .writeNew)

    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    let day = root.appendingPathComponent("2026-11-10", isDirectory: true)
    try FileManager.default.createDirectory(at: day, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: root) }
    let file = day.appendingPathComponent(savedName)
    try Data(savedGeoJSON(times: [2_000, 3_000, 4_000], remoteID: serial).utf8).write(to: file)
    let merged = SavedFlightIndex.merge(
        root: root, dayDirectory: "2026-11-10", fileName: savedName,
        journalPoints: [
            ["-121.130000", "39.150000", "521", "1000"],
            ["-121.130000", "39.150000", "521", "2000"],
            ["-121.130000", "39.150000", "521", "3000"],
            ["-121.130000", "39.150000", "521", "4000"],
        ],
        journalTimesMs: [1_000, 2_000, 3_000, 4_000])
    #expect(merged)
    let text = try String(contentsOf: file, encoding: .utf8)
    #expect(text.contains("1000"))
    #expect(text.contains("4000"))
    let files = try FileManager.default.contentsOfDirectory(at: day, includingPropertiesForKeys: nil)
    #expect(files.count == 1)
}

private func fileNameObservation(aircraft: String, at: Date) -> RidObservation {
    RidObservation(source: .djiVideo, aircraftId: aircraft, receivedAt: at, latitude: 39.15, longitude: -121.13)
}

private func savedGeoJSON(times: [Int64], remoteID: String) -> String {
    let coordinates = times.map { "[\"-121.130000\",\"39.150000\",\"521\",\"\($0)\"]" }.joined(separator: ",")
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"r2c_prop\":{\"rid\":\"\(remoteID)\"}},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[\(coordinates)]}}]}"
}
