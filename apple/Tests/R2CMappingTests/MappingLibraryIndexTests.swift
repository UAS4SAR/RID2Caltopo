import XCTest
import R2CCore
@testable import R2CMapping

final class MappingLibraryIndexTests: XCTestCase {
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

    private func writeMission(root: URL, id: String, title: String = "Flight") throws {
        let mission = root.appendingPathComponent(id, isDirectory: true)
        try FileManager.default.createDirectory(at: mission, withIntermediateDirectories: true)
        let plan: [String: Any] = [
            "missionId": id, "title": title, "incidentId": "incident-1",
            "createdAt": "2026-01-01T00:00:00Z", "cameraProfileId": "m4td.visible.wide",
            "feature": ["geometry": ["coordinates": [[[ -121.0, 39.0 ], [ -120.9, 39.0 ], [ -120.9, 39.1 ], [ -121.0, 39.0 ]]]]],
        ]
        try JSONSerialization.data(withJSONObject: plan, options: [.sortedKeys])
            .write(to: mission.appendingPathComponent("mission.json"), options: .atomic)
    }

    func testIndexRoundTripAndFingerprintSkip() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let missions = root.appendingPathComponent("mapping-missions", isDirectory: true)
        let support = root.appendingPathComponent("Library/Application Support", isDirectory: true)
        try FileManager.default.createDirectory(at: missions, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = "e80815fa-0000-4000-8000-000000000020"
        try writeMission(root: missions, id: id)
        let first = try onIO { try MappingLibraryIndex.refresh(missionsRoot: missions, supportRoot: support) }
        XCTAssertEqual(first.index.missions.count, 1)
        XCTAssertEqual(first.changed, 1)
        let second = try onIO { try MappingLibraryIndex.refresh(missionsRoot: missions, supportRoot: support) }
        XCTAssertEqual(second.changed, 0)
        XCTAssertFalse(try onIO { try MappingLibraryIndex.cheapChanged(missionsRoot: missions, previous: first.index) })
        let loaded = try onIO { MappingLibraryIndex.load(supportRoot: support) }
        XCTAssertEqual(loaded?.missions.first?.id, id)
    }

    func testCorruptIndexForcesRebuild() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let missions = root.appendingPathComponent("mapping-missions", isDirectory: true)
        let support = root.appendingPathComponent("Library/Application Support", isDirectory: true)
        try FileManager.default.createDirectory(at: missions, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = "e80815fa-0000-4000-8000-000000000021"
        try writeMission(root: missions, id: id)
        let stats = MappingLibraryIndex.storageDirectory(supportRoot: support)
        try FileManager.default.createDirectory(at: stats, withIntermediateDirectories: true)
        try Data("{\"schemaVersion\":0}".utf8).write(to: MappingLibraryIndex.fileURL(supportRoot: support))
        XCTAssertNil(try onIO { MappingLibraryIndex.load(supportRoot: support) })
        let result = try onIO { try MappingLibraryIndex.refresh(missionsRoot: missions, supportRoot: support) }
        XCTAssertEqual(result.changed, 1)
        XCTAssertEqual(result.index.schemaVersion, MappingLibraryIndex.schemaVersion)
    }

    func testDeletedMissionRemovedFromIndex() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let missions = root.appendingPathComponent("mapping-missions", isDirectory: true)
        let support = root.appendingPathComponent("Library/Application Support", isDirectory: true)
        try FileManager.default.createDirectory(at: missions, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let keep = "e80815fa-0000-4000-8000-000000000022"
        let drop = "e80815fa-0000-4000-8000-000000000023"
        try writeMission(root: missions, id: keep)
        try writeMission(root: missions, id: drop)
        _ = try onIO { try MappingLibraryIndex.refresh(missionsRoot: missions, supportRoot: support) }
        try FileManager.default.removeItem(at: missions.appendingPathComponent(drop))
        let result = try onIO { try MappingLibraryIndex.refresh(missionsRoot: missions, supportRoot: support) }
        XCTAssertEqual(result.index.missions.map(\.id), [keep])
        XCTAssertEqual(result.removed, 1)
    }

    func testLargeUnreadableMapTifDoesNotBlockRefresh() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let missions = root.appendingPathComponent("mapping-missions", isDirectory: true)
        let support = root.appendingPathComponent("Library/Application Support", isDirectory: true)
        try FileManager.default.createDirectory(at: missions, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = "e80815fa-0000-4000-8000-000000000024"
        try writeMission(root: missions, id: id)
        let tif = missions.appendingPathComponent("\(id)/results/e80815fa-0000-4000-8000-000000000025/map.tif")
        try FileManager.default.createDirectory(at: tif.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(repeating: 7, count: 1024).write(to: tif)
        // chmod u-r if possible; refresh must still succeed via stat-only fingerprints.
        _ = chmod(tif.path, 0)
        defer { _ = chmod(tif.path, S_IRUSR | S_IWUSR) }
        XCTAssertNoThrow(try onIO { try MappingLibraryIndex.refresh(missionsRoot: missions, supportRoot: support) })
    }
}
