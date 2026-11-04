import Foundation
import Testing
@testable import R2CCore

@Suite struct AppleClueFlightFolderMigrationTests {
    private typealias Support = ClueFlightFolderTestSupport
    private let day = "2026-10-20"

    private func loadIndex(_ directory: URL) throws -> [OperationalClueRecord] {
        try JSONDecoder().decode([OperationalClueRecord].self,
                                 from: Data(contentsOf: directory.appendingPathComponent("clues.json")))
    }

    @Test func movesDayLevelCluesIntoFlightFoldersAndIsIdempotent() throws {
        let root = Support.tempRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let dayURL = root.appendingPathComponent(day, isDirectory: true)
        let a = UUID(), b = UUID(), c = UUID()
        func name(_ id: UUID) -> String { id.uuidString.lowercased() }
        try Support.write([
            Support.record(id: a, image: "\(day)/\(name(a)).jpg", state: .pending, flightID: "flight-one"),
            Support.record(id: b, image: "\(day)/\(name(b)).jpg", state: .published, flightID: "flight-one"),
            Support.record(id: c, image: "\(day)/\(name(c)).jpg", state: .localOnly, flightID: "flight-two"),
        ], to: dayURL)
        for id in [a, b, c] {
            try Support.photo("\(name(id)).jpg", in: dayURL)
            try Support.photo("\(name(id))-thumb.jpg", in: dayURL, bytes: [1])
        }
        try OperationalClueDeletionTombstone.persist([UUID()], root: root)
        try Data("log".utf8).write(to: dayURL.appendingPathComponent("RID-1_0641.json.keep"))

        #expect(AppleClueFlightFolderMigration.hasLegacyWork(root: root))
        let result = try AppleClueFlightFolderMigration.migrateIfNeeded(root: root)
        #expect(result.records == 3)
        #expect(result.flights == 2)
        #expect(result.recoveredFlightIDs == 0)

        let one = dayURL.appendingPathComponent("flight-one", isDirectory: true)
        let two = dayURL.appendingPathComponent("flight-two", isDirectory: true)
        #expect(Set(try loadIndex(one).map(\.id)) == [a, b])
        #expect(Set(try loadIndex(two).map(\.id)) == [c])
        #expect(try loadIndex(one).first { $0.id == a }?.imageFilename == "\(day)/flight-one/\(name(a)).jpg")
        #expect(try loadIndex(one).first { $0.id == b }?.uploadState == .published)
        #expect(Support.exists(one.appendingPathComponent("\(name(a)).jpg")))
        #expect(Support.exists(two.appendingPathComponent("\(name(c))-thumb.jpg")))
        // Originals removed only after verification; unrelated day files and root tombstones stay.
        #expect(!Support.exists(dayURL.appendingPathComponent("clues.json")))
        #expect(!Support.exists(dayURL.appendingPathComponent("\(name(a)).jpg")))
        #expect(Support.exists(dayURL.appendingPathComponent("RID-1_0641.json.keep")))
        #expect(Support.exists(root.appendingPathComponent(OperationalClueDeletionTombstone.filename)))
        #expect(Support.exists(root.appendingPathComponent(AppleClueFlightFolderMigration.markerFilename)))
        #expect(!AppleClueFlightFolderMigration.hasLegacyWork(root: root))

        let loaded = try OperationalClueIndex.load(root: root)
        #expect(Set(loaded.map(\.id)) == [a, b, c])

        // Idempotent.
        let again = try AppleClueFlightFolderMigration.migrateIfNeeded(root: root)
        #expect(again == AppleClueFlightFolderMigration.Result())
        #expect(Set(try OperationalClueIndex.load(root: root).map(\.id)) == [a, b, c])
    }

    @Test func missingFlightRecoversFromUniqueArchiveElseStableSyntheticAndNeverDrops() throws {
        let root = Support.tempRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let dayURL = root.appendingPathComponent(day, isDirectory: true)
        let hinted = UUID(), lonely = UUID(), orphan = UUID()
        try Support.write([
            Support.record(id: hinted, image: "\(day)/\(hinted.uuidString.lowercased()).jpg", aircraftID: "RID-7"),
            Support.record(id: lonely, image: "\(day)/\(lonely.uuidString.lowercased()).jpg", aircraftID: "OTHER"),
        ], to: dayURL)
        try Support.photo("\(hinted.uuidString.lowercased()).jpg", in: dayURL)
        try Support.photo("\(orphan.uuidString.lowercased()).jpg", in: dayURL)
        // One archived flight for RID-7 that day, carrying its flight id.
        let archive = """
        {"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-121.1,39.1,100,1699999990000],[-121.2,39.2,100,1700000010000]]},"properties":{"title":"t","r2c_flight_id":"archived-flight","r2c_prop":{"rid":"RID-7"}}}]}
        """
        try Data(archive.utf8).write(to: dayURL.appendingPathComponent("RID-7_0641.json"))

        let result = try AppleClueFlightFolderMigration.migrateIfNeeded(root: root)
        #expect(result.records == 3)
        #expect(result.recoveredFlightIDs == 3)

        let hintedIndex = try loadIndex(dayURL.appendingPathComponent("archived-flight"))
        #expect(hintedIndex.map(\.id) == [hinted])
        let synthetic = RidFlightID.make(aircraftID: "OTHER", startedAt: try #require(AppleFlightStorage.date(day)))
        let syntheticIDs = Set(try loadIndex(dayURL.appendingPathComponent(synthetic)).map(\.id))
        #expect(syntheticIDs.contains(lonely))
        // The orphan photo is re-indexed (local-only), not dropped.
        let all = try OperationalClueIndex.load(root: root)
        #expect(Set(all.map(\.id)) == [hinted, lonely, orphan])
        #expect(all.first { $0.id == orphan }?.uploadState == .localOnly)
        #expect(Support.exists(dayURL.appendingPathComponent("archived-flight/\(hinted.uuidString.lowercased()).jpg")))
    }

    @Test func existingFlightIndexIsNeverDemotedByAStaleDayIndex() throws {
        let root = Support.tempRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let dayURL = root.appendingPathComponent(day, isDirectory: true)
        let id = UUID()
        let name = id.uuidString.lowercased()
        try Support.write([Support.record(id: id, image: "\(day)/f/\(name).jpg", state: .published, flightID: "f")],
                          to: dayURL.appendingPathComponent("f", isDirectory: true))
        try Support.photo("\(name).jpg", in: dayURL.appendingPathComponent("f"))
        // Crash window: the day-level copy was not removed yet and still says pending.
        try Support.write([Support.record(id: id, image: "\(day)/\(name).jpg", state: .pending, flightID: "f")], to: dayURL)
        try Support.photo("\(name).jpg", in: dayURL)

        _ = try AppleClueFlightFolderMigration.migrateIfNeeded(root: root)

        let loaded = try OperationalClueIndex.load(root: root)
        #expect(loaded.count == 1)
        #expect(loaded[0].uploadState == .published)
        #expect(!Support.exists(dayURL.appendingPathComponent("clues.json")))
        #expect(!Support.exists(dayURL.appendingPathComponent("\(name).jpg")))
    }

    @Test func undecodableLegacyIndexKeepsEveryOriginal() throws {
        let root = Support.tempRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let dayURL = root.appendingPathComponent(day, isDirectory: true)
        try FileManager.default.createDirectory(at: dayURL, withIntermediateDirectories: true)
        let original = Data("[{torn".utf8)
        try original.write(to: dayURL.appendingPathComponent("clues.json"))
        try Support.photo("\(UUID().uuidString.lowercased()).jpg", in: dayURL)
        #expect(throws: (any Error).self) { try AppleClueFlightFolderMigration.migrateIfNeeded(root: root) }
        #expect(try Data(contentsOf: dayURL.appendingPathComponent("clues.json")) == original)
        #expect(!Support.exists(root.appendingPathComponent(AppleClueFlightFolderMigration.markerFilename)))
    }

    @Test func layoutHelpersKeepOneFlightInOneFolderAndRejectBlankFlights() throws {
        #expect(AppleClueFlightFolder.folderName(flightID: "  ") == nil)
        #expect(AppleClueFlightFolder.folderName(flightID: "a/b..c") == "a_b__c")
        #expect(AppleClueFlightFolder.relativePath(day: day, flightID: "f1", filename: "x.jpg") == "\(day)/f1/x.jpg")
        #expect(AppleClueFlightFolder.relativePath(day: "not-a-day", flightID: "f1", filename: "x.jpg") == nil)
        #expect(AppleClueFlightFolder.day(ofRelativePath: "\(day)/f1/x.jpg") == day)
        #expect(AppleClueFlightFolder.flightFolder(ofRelativePath: "\(day)/f1/x.jpg") == "f1")
        #expect(AppleClueFlightFolder.flightFolder(ofRelativePath: "\(day)/x.jpg") == nil)
        #expect(AppleClueFlightFolder.directory(ofRelativePath: "\(day)/f1/x.jpg") == "\(day)/f1")
        let earlier = Support.record(image: "2026-10-19/f1/x.jpg", flightID: "f1")
        #expect(AppleClueFlightFolder.resolveDay(flightID: "f1", existing: [earlier],
                                                 now: try #require(AppleFlightStorage.date(day))) == "2026-10-19")
        #expect(AppleClueFlightFolder.resolveDay(flightID: "f2", existing: [earlier],
                                                 now: try #require(AppleFlightStorage.date(day))) == day)
    }
}
