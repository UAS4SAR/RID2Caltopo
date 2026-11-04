import Foundation
import Testing
@testable import R2CCore

@Test func corruptClueIndexIsNotSilentlyAnEmptyDay() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let day = root.appendingPathComponent(AppleFlightStorage.dayName(Date()))
    try FileManager.default.createDirectory(at: day, withIntermediateDirectories: true)
    let index = day.appendingPathComponent("clues.json"), original = Data("[{torn index".utf8)
    try original.write(to: index)
    #expect(throws: (any Error).self) { try OperationalClueIndex.load(root: root) }
    #expect(try Data(contentsOf: index) == original)
}

@Test func missingClueRootIsEmptyButUnreadableRootIsAnError() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    #expect(try OperationalClueIndex.load(root: root).isEmpty)
    try Data([1]).write(to: root)
    #expect(throws: (any Error).self) { try OperationalClueIndex.load(root: root) }
}

@Test func perFlightIndexesLoadAlongsideLegacyDayIndexAndDedupeInterruptedMigration() throws {
    typealias Support = ClueFlightFolderTestSupport
    let root = Support.tempRoot()
    defer { try? FileManager.default.removeItem(at: root) }
    let day = "2026-10-21"
    let dayURL = root.appendingPathComponent(day)
    let nested = UUID(), legacy = UUID(), both = UUID()
    try Support.write([
        Support.record(id: nested, image: "\(day)/flight-a/\(nested.uuidString.lowercased()).jpg", flightID: "flight-a"),
        Support.record(id: both, image: "\(day)/flight-a/\(both.uuidString.lowercased()).jpg", state: .published, flightID: "flight-a"),
    ], to: dayURL.appendingPathComponent("flight-a"))
    try Support.write([
        Support.record(id: legacy, image: "\(day)/\(legacy.uuidString.lowercased()).jpg"),
        Support.record(id: both, image: "\(day)/\(both.uuidString.lowercased()).jpg", state: .pending),
    ], to: dayURL)
    // A recordings folder without an index is not a clue folder.
    try FileManager.default.createDirectory(at: dayURL.appendingPathComponent("drone"), withIntermediateDirectories: true)

    let loaded = try OperationalClueIndex.load(root: root)
    #expect(Set(loaded.map(\.id)) == [nested, legacy, both])
    #expect(loaded.count == 3)
    let kept = try #require(loaded.first { $0.id == both })
    #expect(kept.uploadState == .published)
    #expect(AppleClueFlightFolder.flightFolder(ofRelativePath: kept.imageFilename) == "flight-a")
}

@Test func corruptFlightIndexIsAnErrorNotAnEmptyFlight() throws {
    let root = ClueFlightFolderTestSupport.tempRoot()
    defer { try? FileManager.default.removeItem(at: root) }
    let flight = root.appendingPathComponent("2026-10-21/flight-a")
    try FileManager.default.createDirectory(at: flight, withIntermediateDirectories: true)
    let original = Data("[{torn".utf8)
    try original.write(to: flight.appendingPathComponent("clues.json"))
    #expect(throws: (any Error).self) { try OperationalClueIndex.load(root: root) }
    #expect(try Data(contentsOf: flight.appendingPathComponent("clues.json")) == original)
}
