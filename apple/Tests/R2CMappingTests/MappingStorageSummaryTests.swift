import XCTest
@testable import R2CMapping

final class MappingStorageSummaryTests: XCTestCase {
    private var root: URL!

    private func onIO<T>(_ body: @escaping () throws -> T) throws -> T {
        if !Thread.isMainThread { return try body() }
        let box = ResultBox<T>()
        let sem = DispatchSemaphore(value: 0)
        DispatchQueue.global(qos: .userInitiated).async {
            do { box.result = .success(try body()) } catch { box.result = .failure(error) }
            sem.signal()
        }
        sem.wait()
        return try box.result.get()
    }
    private final class ResultBox<T>: @unchecked Sendable { var result: Result<T, Error>! }

    override func setUp() {
        super.setUp()
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try! FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        MappingStorageSummary.invalidate()
        MappingStorageMigration.resetForTests()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
        MappingStorageSummary.invalidate()
        super.tearDown()
    }

    private func roots() -> MappingStorageSummary.Roots {
        MappingStorageSummary.Roots(
            missions: root.appendingPathComponent("mapping-missions", isDirectory: true),
            layers: root.appendingPathComponent("MappingLayers", isDirectory: true),
            processing: root.appendingPathComponent("mapping-processing", isDirectory: true),
            stats: root.appendingPathComponent("mapping-storage-stats", isDirectory: true)
        )
    }

    private func write(_ relative: String, bytes: Int) throws {
        let url = root.appendingPathComponent(relative)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(repeating: 1, count: bytes).write(to: url)
    }

    func testStampStaleWhenMissionChildAppears() throws {
        try onIO {
            let r = self.roots()
            try FileManager.default.createDirectory(at: r.missions, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.stats, withIntermediateDirectories: true)
            let before = r.stamp()
            let mission = r.missions.appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: mission, withIntermediateDirectories: true)
            try Data("{}".utf8).write(to: mission.appendingPathComponent("mission.json"))
            Thread.sleep(forTimeInterval: 0.02)
            let after = r.stamp()
            let cached = MappingStorageSummary.Report(
                version: 1, projects: [], sharedLayersBytes: 0, statsBytes: 0,
                processingBytes: 0, otherBytes: 0, totalBytes: 0, complete: true, stamp: before
            )
            XCTAssertFalse(MappingStorageSummary.isFresh(cached, current: after))
        }
    }

    func testTotalsIncludeLayersStatsProcessingAndOther() throws {
        try onIO {
            let mission = UUID().uuidString
            try self.write("mapping-missions/\(mission)/originals/a.jpg", bytes: 1_000)
            try self.write("MappingLayers/\(UUID().uuidString)/map.tif", bytes: 500)
            try self.write("mapping-processing/\(UUID().uuidString)/work.bin", bytes: 40)
            try self.write("mapping-storage-stats/library-index.json", bytes: 20)
            try self.write("mapping-missions/loose.txt", bytes: 7)
            let report = try MappingStorageSummary.measure(roots: self.roots())
            XCTAssertEqual(report.projects.count, 1)
            XCTAssertEqual(report.projects[0].bytes, 1_000)
            XCTAssertEqual(report.sharedLayersBytes, 500)
            XCTAssertEqual(report.processingBytes, 40)
            XCTAssertEqual(report.statsBytes, 20)
            XCTAssertEqual(report.otherBytes, 7)
            XCTAssertEqual(report.totalBytes, 1_000 + 500 + 40 + 20 + 7)
            XCTAssertEqual(report.lineSum(), report.totalBytes)
        }
    }

    func testCacheSurvivesUntilStampChanges() throws {
        try onIO {
            try self.write("MappingLayers/x/map.tif", bytes: 64)
            let r = self.roots()
            try FileManager.default.createDirectory(at: r.missions, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.processing, withIntermediateDirectories: true)
            let first = try MappingStorageSummary.cachedOrRefresh(roots: r)
            XCTAssertEqual(first.sharedLayersBytes, 64)
            MappingStorageSummary.invalidate()
            let second = MappingStorageSummary.loadCached(roots: r)
            XCTAssertEqual(second?.sharedLayersBytes, 64)
            XCTAssertTrue(MappingStorageSummary.isFresh(second!, current: r.stamp()))
            try self.write("MappingLayers/y/map.tif", bytes: 10)
            try FileManager.default.setAttributes(
                [.modificationDate: Date()],
                ofItemAtPath: r.layers.path
            )
            Thread.sleep(forTimeInterval: 0.02)
            XCTAssertFalse(MappingStorageSummary.isFresh(second!, current: r.stamp()))
            let third = try MappingStorageSummary.cachedOrRefresh(roots: r)
            XCTAssertEqual(third.sharedLayersBytes, 74)
        }
    }

    func testMigrationOutcomeWording() {
        XCTAssertEqual(
            MappingStorageMigration.outcomeLine(.alreadyMigrated),
            "Mapping storage: already migrated"
        )
        XCTAssertEqual(
            MappingStorageMigration.outcomeLine(.nothingToMigrate),
            "Mapping storage: nothing to migrate"
        )
        XCTAssertEqual(
            MappingStorageMigration.outcomeLine(.migrated, files: 3, bytes: 2 * 1024 * 1024),
            "Mapping storage: migrated 3 items (2 MB)"
        )
        XCTAssertEqual(
            MappingStorageMigration.outcomeLine(.failed, failureReason: "failed (IOError); legacy retained"),
            "Mapping storage: failed (IOError); legacy retained"
        )
    }

    func testMigrationEvaluateWritesMarkerOnce() throws {
        try onIO {
            let support = self.root.appendingPathComponent("support", isDirectory: true)
            try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
            let first = MappingStorageMigration.evaluate(supportRoot: support)
            XCTAssertEqual(first.0, .nothingToMigrate)
            XCTAssertEqual(first.1, "Mapping storage: nothing to migrate")
            let second = MappingStorageMigration.evaluate(supportRoot: support)
            XCTAssertEqual(second.0, .alreadyMigrated)
            XCTAssertEqual(second.1, "Mapping storage: already migrated")
            var lines: [String] = []
            MappingStorageMigration.emitOnce(first.1) { lines.append($0) }
            MappingStorageMigration.emitOnce(second.1) { lines.append($0) }
            XCTAssertEqual(lines, [first.1])
        }
    }
}
