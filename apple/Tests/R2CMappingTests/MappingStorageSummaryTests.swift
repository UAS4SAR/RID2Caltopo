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

    private func emptyReport(stamp: MappingStorageSummary.Stamp) -> MappingStorageSummary.Report {
        MappingStorageSummary.Report(
            projects: [],
            sharedLayersBytes: 0,
            statsBytes: 0,
            processingBytes: 0,
            otherBytes: 0,
            totalBytes: 0,
            logicalBytes: 0,
            workingCopyBytes: 0,
            complete: true,
            stamp: stamp
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
            XCTAssertFalse(MappingStorageSummary.isFresh(self.emptyReport(stamp: before), current: after))
        }
    }

    func testStampIgnoresStatsAndPaneSummaryWrites() throws {
        try onIO {
            let missionId = "2e30e0b4-0000-4000-8000-000000000001"
            let r = self.roots()
            try FileManager.default.createDirectory(
                at: r.missions.appendingPathComponent(missionId, isDirectory: true),
                withIntermediateDirectories: true
            )
            try FileManager.default.createDirectory(at: r.layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.stats, withIntermediateDirectories: true)
            let before = r.stamp()
            try Data("{}".utf8).write(to: r.stats.appendingPathComponent("pane-summary.json"))
            try Data(repeating: 1, count: 40).write(to: r.stats.appendingPathComponent("library-index.json"))
            try Data(repeating: 1, count: 12).write(
                to: r.stats.appendingPathComponent(String(repeating: "a", count: 64) + ".json")
            )
            try Data("{}".utf8).write(to: r.stats.appendingPathComponent("phase-costs.json"))
            Thread.sleep(forTimeInterval: 0.02)
            try FileManager.default.setAttributes(
                [.modificationDate: Date()],
                ofItemAtPath: r.stats.path
            )
            let after = r.stamp()
            XCTAssertTrue(MappingStorageSummary.isFresh(self.emptyReport(stamp: before), current: after))
            XCTAssertEqual(before, after)
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
            // blockSize=1 → allocated equals logical for structure assertions
            let report = try MappingStorageSummary.measure(roots: self.roots(), blockSize: 1)
            XCTAssertEqual(report.projects.count, 1)
            XCTAssertEqual(report.projects[0].bytes, 1_000)
            XCTAssertEqual(report.sharedLayersBytes, 500)
            XCTAssertEqual(report.processingBytes, 40)
            XCTAssertEqual(report.statsBytes, 20)
            XCTAssertEqual(report.otherBytes, 7)
            XCTAssertEqual(report.totalBytes, 1_000 + 500 + 40 + 20 + 7)
            XCTAssertEqual(report.lineSum(), report.totalBytes)
            XCTAssertEqual(report.workingCopyBytes, 0)
            XCTAssertTrue(report.projects[0].sized)
            XCTAssertTrue(report.complete)
        }
    }

    func testBlockRoundingUsesFilesystemBlockSize() throws {
        XCTAssertEqual(MappingStorageSummary.allocatedBytes(logical: 1, blockSize: 4096), 4096)
        XCTAssertEqual(MappingStorageSummary.allocatedBytes(logical: 4096, blockSize: 4096), 4096)
        XCTAssertEqual(MappingStorageSummary.allocatedBytes(logical: 4097, blockSize: 4096), 8192)
        XCTAssertEqual(MappingStorageSummary.allocatedBytes(logical: 1, blockSize: 512 * 1024), 512 * 1024)

        try onIO {
            try self.write("mapping-missions/m1/a.bin", bytes: 1)
            try FileManager.default.createDirectory(at: self.roots().layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: self.roots().processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: self.roots().stats, withIntermediateDirectories: true)
            let report = try MappingStorageSummary.measure(roots: self.roots(), blockSize: 4096)
            XCTAssertEqual(report.projects.single.bytes, 4096)
            XCTAssertEqual(report.totalBytes, 4096)
            XCTAssertEqual(report.logicalBytes, 1)
        }
    }

    func testArchiveAndWorkingCopyDifferAndWorkingExcludedFromTotal() throws {
        try onIO {
            let files = self.root.appendingPathComponent("files", isDirectory: true)
            let archive = self.root.appendingPathComponent("archive", isDirectory: true)
            let working = self.root.appendingPathComponent("operational-work", isDirectory: true)
            let missionId = "2e30e0b4-0000-4000-8000-000000000001"

            let localRoots = MappingStorageSummary.Roots(
                missions: files.appendingPathComponent("mapping-missions", isDirectory: true),
                layers: files.appendingPathComponent("mapping-layers", isDirectory: true),
                processing: files.appendingPathComponent("mapping-processing", isDirectory: true),
                stats: files.appendingPathComponent("mapping-storage-stats", isDirectory: true)
            )
            try FileManager.default.createDirectory(
                at: localRoots.missions.appendingPathComponent(missionId, isDirectory: true),
                withIntermediateDirectories: true
            )
            try FileManager.default.createDirectory(at: localRoots.layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: localRoots.processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: localRoots.stats, withIntermediateDirectories: true)

            let archiveMission = archive.appendingPathComponent(
                "mapping/missions/\(missionId)/originals/a.jpg"
            )
            try FileManager.default.createDirectory(
                at: archiveMission.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data(repeating: 1, count: 1_000).write(to: archiveMission)
            try FileManager.default.createDirectory(
                at: archive.appendingPathComponent("mapping/layers", isDirectory: true),
                withIntermediateDirectories: true
            )
            try FileManager.default.createDirectory(
                at: archive.appendingPathComponent("mapping/processing", isDirectory: true),
                withIntermediateDirectories: true
            )
            try FileManager.default.createDirectory(
                at: archive.appendingPathComponent("mapping/stats", isDirectory: true),
                withIntermediateDirectories: true
            )

            let workingFile = working.appendingPathComponent(
                "missions/\(missionId)/originals/a.jpg"
            )
            try FileManager.default.createDirectory(
                at: workingFile.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data(repeating: 1, count: 2_000).write(to: workingFile)

            let archiveRoots = MappingStorageSummary.fileArchiveRoots(archiveRoot: archive)!
            let report = try MappingStorageSummary.measure(
                roots: localRoots,
                blockSize: 1,
                archiveRoots: archiveRoots,
                workingCopyRoot: working,
                reason: "full"
            )
            XCTAssertEqual(report.totalBytes, 1_000)
            XCTAssertEqual(report.workingCopyBytes, 2_000)
            XCTAssertEqual(report.lineSum(), report.totalBytes)
            XCTAssertNotEqual(report.workingCopyBytes, report.totalBytes)
            XCTAssertNotEqual(report.lineSum(), report.totalBytes + report.workingCopyBytes)
        }
    }

    func testWorkingCopyUsesItsOwnBlockSize() throws {
        try onIO {
            let files = self.root.appendingPathComponent("files-block", isDirectory: true)
            let archive = self.root.appendingPathComponent("archive-block", isDirectory: true)
            let working = self.root.appendingPathComponent("work-block", isDirectory: true)
            let missionId = "2e30e0b4-0000-4000-8000-000000000009"
            let localRoots = MappingStorageSummary.Roots(
                missions: files.appendingPathComponent("mapping-missions", isDirectory: true),
                layers: files.appendingPathComponent("mapping-layers", isDirectory: true),
                processing: files.appendingPathComponent("mapping-processing", isDirectory: true),
                stats: files.appendingPathComponent("mapping-storage-stats", isDirectory: true)
            )
            try FileManager.default.createDirectory(at: localRoots.missions.appendingPathComponent(missionId, isDirectory: true), withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: localRoots.layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: localRoots.processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: localRoots.stats, withIntermediateDirectories: true)
            let archiveFile = archive.appendingPathComponent("mapping/missions/\(missionId)/originals/a.jpg")
            try FileManager.default.createDirectory(at: archiveFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(repeating: 1, count: 1_000).write(to: archiveFile)
            for name in ["layers", "processing", "stats"] {
                try FileManager.default.createDirectory(at: archive.appendingPathComponent("mapping/\(name)", isDirectory: true), withIntermediateDirectories: true)
            }
            let workingFile = working.appendingPathComponent("missions/\(missionId)/originals/a.jpg")
            try FileManager.default.createDirectory(at: workingFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(repeating: 1, count: 2_000).write(to: workingFile)
            let report = try MappingStorageSummary.measure(
                roots: localRoots,
                blockSize: 1,
                archiveRoots: MappingStorageSummary.fileArchiveRoots(archiveRoot: archive),
                workingCopyRoot: working,
                workingBlockSize: 4096,
                reason: "full"
            )
            XCTAssertEqual(report.totalBytes, 1_000)
            XCTAssertEqual(report.workingCopyBytes, 4_096)
        }
    }

    func testHeavyBusyServesLastKnownWithoutScanning() throws {
        try onIO {
            let r = self.roots()
            try FileManager.default.createDirectory(at: r.missions, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.stats, withIntermediateDirectories: true)
            MappingStorageGate.heavyBusy = { true }
            defer { MappingStorageGate.heavyBusy = { false } }
            let report = try MappingStorageSummary.cachedOrRefresh(roots: r, blockSize: 1)
            XCTAssertEqual(report.scanReason, "placeholders")
            XCTAssertEqual(report.scanElapsedMs, 0)
        }
    }

    func testIncrementalRescanOnlyChangedProject() throws {
        try onIO {
            let a = "aaaaaaaa-0000-4000-8000-000000000001"
            let b = "bbbbbbbb-0000-4000-8000-000000000002"
            try self.write("mapping-missions/\(a)/originals/a.jpg", bytes: 100)
            try self.write("mapping-missions/\(b)/originals/b.jpg", bytes: 200)
            try FileManager.default.createDirectory(at: self.roots().layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: self.roots().processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: self.roots().stats, withIntermediateDirectories: true)

            let first = try MappingStorageSummary.measure(roots: self.roots(), blockSize: 1, reason: "cold")
            XCTAssertEqual(first.projects.count, 2)
            XCTAssertEqual(first.rescannedProjects, 2)

            Thread.sleep(forTimeInterval: 0.02)
            try Data(repeating: 1, count: 250).write(
                to: self.root.appendingPathComponent("mapping-missions/\(b)/originals/b.jpg")
            )
            // DirStamp watches mission children (e.g. originals/), not the mission dir itself.
            try FileManager.default.setAttributes(
                [.modificationDate: Date()],
                ofItemAtPath: self.roots().missions.appendingPathComponent(b)
                    .appendingPathComponent("originals").path
            )

            let second = try MappingStorageSummary.measure(
                roots: self.roots(),
                previous: first,
                blockSize: 1,
                reason: "stale"
            )
            XCTAssertEqual(second.rescannedProjects, 1)
            XCTAssertEqual(second.projects.first { $0.flightId == a }?.bytes, 100)
            XCTAssertEqual(second.projects.first { $0.flightId == b }?.bytes, 250)
            XCTAssertEqual(second.totalBytes, 350)
        }
    }

    func testCacheSurvivesUntilStampChanges() throws {
        try onIO {
            try self.write("MappingLayers/x/map.tif", bytes: 64)
            let r = self.roots()
            try FileManager.default.createDirectory(at: r.missions, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.stats, withIntermediateDirectories: true)
            let first = try MappingStorageSummary.cachedOrRefresh(roots: r, blockSize: 1)
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
            let third = try MappingStorageSummary.cachedOrRefresh(roots: r, blockSize: 1)
            XCTAssertEqual(third.sharedLayersBytes, 74)
        }
    }

    func testScanLogLineIsCompact() {
        let stamp = MappingStorageSummary.Stamp(
            projects: [:],
            processing: MappingStorageSummary.DirStamp(modified: 0, count: 0, signature: "")
        )
        let report = MappingStorageSummary.Report(
            projects: [],
            sharedLayersBytes: 0,
            statsBytes: 0,
            processingBytes: 0,
            otherBytes: 0,
            totalBytes: 2_600_000_000,
            logicalBytes: 1_780_000_000,
            workingCopyBytes: 2_200_000_000,
            complete: true,
            stamp: stamp,
            scanReason: "stale",
            scanElapsedMs: 1400,
            rescannedProjects: 1
        )
        let line = MappingStorageSummary.scanLogLine(report)
        XCTAssertTrue(line.hasPrefix("Mapping storage scan:"))
        XCTAssertTrue(line.contains("total=2.6GB"))
        XCTAssertTrue(line.contains("working=2.2GB"))
        XCTAssertTrue(line.contains("reason=stale"))
        XCTAssertFalse(line.contains("lat="))
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

    func testCacheHitLogsReasonCache() throws {
        try onIO {
            let r = self.roots()
            try FileManager.default.createDirectory(at: r.missions, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.stats, withIntermediateDirectories: true)
            var lines: [String] = []
            _ = try MappingStorageSummary.cachedOrRefresh(roots: r, blockSize: 1, log: { lines.append($0) })
            MappingStorageSummary.invalidate()
            _ = try MappingStorageSummary.cachedOrRefresh(roots: r, blockSize: 1, log: { lines.append($0) })
            XCTAssertTrue(lines.contains { $0.contains("reason=cache") })
        }
    }

    func testWalkDoesNotFollowNestedSymlinks() throws {
        try onIO {
            let r = self.roots()
            try FileManager.default.createDirectory(at: r.missions, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.layers, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.processing, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: r.stats, withIntermediateDirectories: true)
            let mission = r.missions.appendingPathComponent("m1", isDirectory: true)
            try FileManager.default.createDirectory(at: mission, withIntermediateDirectories: true)
            try Data(repeating: 1, count: 100).write(to: mission.appendingPathComponent("a.bin"))
            let outside = self.root.appendingPathComponent("outside", isDirectory: true)
            try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
            try Data(repeating: 2, count: 8_000).write(to: outside.appendingPathComponent("big.bin"))
            try FileManager.default.createSymbolicLink(
                at: mission.appendingPathComponent("linked"),
                withDestinationURL: outside
            )
            let report = try MappingStorageSummary.measure(roots: r, blockSize: 1)
            XCTAssertEqual(report.totalBytes, 100)
        }
    }

    func testConcurrentScansShareOneFlight() throws {
        let r = roots()
        try FileManager.default.createDirectory(at: r.missions, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: r.layers, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: r.processing, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: r.stats, withIntermediateDirectories: true)
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        let runs = LockedInt()
        MappingStorageSummary.beforeScan = {
            runs.increment()
            entered.signal()
            release.wait()
        }
        defer {
            MappingStorageSummary.beforeScan = nil
            release.signal()
        }
        let box = LockedReports()
        let done = DispatchGroup()
        for _ in 0..<2 {
            done.enter()
            DispatchQueue.global().async {
                defer { done.leave() }
                if let report = try? MappingStorageSummary.cachedOrRefresh(roots: r, blockSize: 1) {
                    box.add(report)
                }
            }
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 10), .success)
        Thread.sleep(forTimeInterval: 0.05)
        XCTAssertEqual(runs.value, 1)
        release.signal()
        XCTAssertEqual(done.wait(timeout: .now() + 10), .success)
        XCTAssertEqual(box.count, 2)
    }
}

private final class LockedInt: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = 0
    var value: Int { lock.lock(); defer { lock.unlock() }; return stored }
    func increment() { lock.lock(); stored += 1; lock.unlock() }
}

private final class LockedReports: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [MappingStorageSummary.Report] = []
    var count: Int { lock.lock(); defer { lock.unlock() }; return stored.count }
    func add(_ report: MappingStorageSummary.Report) { lock.lock(); stored.append(report); lock.unlock() }
}

private extension Array {
    var single: Element {
        precondition(count == 1)
        return self[0]
    }
}
