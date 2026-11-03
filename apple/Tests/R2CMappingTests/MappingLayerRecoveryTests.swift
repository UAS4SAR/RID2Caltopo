import XCTest
import R2CCore
@testable import R2CMapping

final class MappingLayerRecoveryTests: XCTestCase {
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

    private func completeResult(missionsRoot: URL, missionID: String, layerID: String, incident: String = "incident-1") throws -> URL {
        let dir = missionsRoot
            .appendingPathComponent(missionID, isDirectory: true)
            .appendingPathComponent("results", isDirectory: true)
            .appendingPathComponent(layerID, isDirectory: true)
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("18/123", isDirectory: true), withIntermediateDirectories: true)
        let layer: [String: Any] = [
            "type": "r2c_mapping_layer", "version": 1, "tileScheme": "xyz",
            "layerId": layerID, "incidentId": incident, "title": "Orphan map",
            "tileUrlTemplate": "http://127.0.0.1/tiles/{Z}/{X}/{Y}.png",
            "minZoom": 18, "maxZoom": 20, "bounds": [-121.1, 39.1, -121.0, 39.2],
            "missionId": missionID,
        ]
        try JSONSerialization.data(withJSONObject: layer, options: [.sortedKeys])
            .write(to: dir.appendingPathComponent("layer.json"), options: .atomic)
        try Data([1, 2, 3]).write(to: dir.appendingPathComponent("18/123/456.png"))
        return dir
    }

    func testAdoptsCompleteOrphanIdempotently() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let mission = "e80815fa-0000-4000-8000-000000000010"
        let layer = "e80815fa-0000-4000-8000-000000000011"
        _ = try completeResult(missionsRoot: root, missionID: mission, layerID: layer)
        let first = try onIO { try MappingLayerRecovery.adoptCompleteOrphans(missionsRoot: root) }
        XCTAssertEqual(first.count, 1)
        let second = try onIO { try MappingLayerRecovery.adoptCompleteOrphans(missionsRoot: root, alreadyRegistered: Set(first.map(\.layer.id))) }
        XCTAssertEqual(second.count, 0)
    }

    func testSkipsIncompleteDirs() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let mission = "e80815fa-0000-4000-8000-000000000012"
        let incomplete = root.appendingPathComponent("\(mission)/results/e80815fa-0000-4000-8000-000000000013", isDirectory: true)
        try FileManager.default.createDirectory(at: incomplete, withIntermediateDirectories: true)
        try Data("{}".utf8).write(to: incomplete.appendingPathComponent("layer.json"))
        XCTAssertEqual(try onIO { try MappingLayerRecovery.adoptCompleteOrphans(missionsRoot: root).count }, 0)
    }

    func testRemovesEmptyOrphanButLeavesProtected() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let support = root.appendingPathComponent("support", isDirectory: true)
        try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        let emptyID = "e80815fa-0000-4000-8000-000000000014"
        let empty = root.appendingPathComponent("e80815fa-0000-4000-8000-000000000015/results/\(emptyID)", isDirectory: true)
        try FileManager.default.createDirectory(at: empty, withIntermediateDirectories: true)
        _ = try onIO { try MappingLayerRecovery.adoptCompleteOrphans(missionsRoot: root, supportRoot: support) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: empty.path))
        XCTAssertTrue(OrphanEmptyRegistry.load(supportRoot: support).contains(emptyID))
        // Second pass must not re-log; registry already remembers the id.
        try FileManager.default.createDirectory(at: empty, withIntermediateDirectories: true)
        _ = try onIO { try MappingLayerRecovery.adoptCompleteOrphans(missionsRoot: root, supportRoot: support) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: empty.path))
        XCTAssertEqual(OrphanEmptyRegistry.load(supportRoot: support), [emptyID])

        let protectedID = "e80815fa-0000-4000-8000-000000000016"
        let protected = root.appendingPathComponent("e80815fa-0000-4000-8000-000000000017/results/\(protectedID)", isDirectory: true)
        try FileManager.default.createDirectory(at: protected, withIntermediateDirectories: true)
        _ = try onIO { try MappingLayerRecovery.adoptCompleteOrphans(missionsRoot: root, protectedIds: [protectedID], supportRoot: support) }
        XCTAssertTrue(FileManager.default.fileExists(atPath: protected.path))
    }

    func testHasTileManifestIsShallow() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        XCTAssertFalse(try onIO { MappingLayerTiles.hasTileManifest(dir) })
        try Data("{}".utf8).write(to: dir.appendingPathComponent("layer.json"))
        XCTAssertFalse(try onIO { MappingLayerTiles.hasTileManifest(dir) })
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("18/1", isDirectory: true), withIntermediateDirectories: true)
        try Data([9]).write(to: dir.appendingPathComponent("18/1/2.png"))
        XCTAssertTrue(try onIO { MappingLayerTiles.hasTileManifest(dir) })
    }
}
