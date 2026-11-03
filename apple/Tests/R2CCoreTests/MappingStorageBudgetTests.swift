import XCTest
@testable import R2CCore

final class MappingStorageBudgetTests: XCTestCase {
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

    func testMissingPathIsProgrammingErrorNotFull() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let missing = root.appendingPathComponent("gone/deeper/missing", isDirectory: true)
        XCTAssertThrowsError(try onIO { try MappingStorageBudget.freeBytes(missing) }) { error in
            let message = (error as? MappingMission.Failure)?.errorDescription ?? String(describing: error)
            XCTAssertTrue(message.contains("programming error"))
            XCTAssertFalse(message.lowercased().contains("full"))
            XCTAssertFalse(message.contains("need "))
        }
    }

    func testRequireFreeMessageIncludesNeedFreeBucketAndLabel() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let free = try onIO { try MappingStorageBudget.freeBytes(dir) }
        let need = free + MappingStorageBudget.intakeReserveBytes
        XCTAssertThrowsError(try onIO { try MappingStorageBudget.requireFree(dir, needBytes: need, bucket: "Mapping work space") }) { error in
            let message = (error as? MappingMission.Failure)?.errorDescription ?? String(describing: error)
            XCTAssertTrue(message.hasPrefix("Mapping work space ("))
            XCTAssertTrue(message.contains("need "))
            XCTAssertTrue(message.contains("free "))
            XCTAssertTrue(message.contains("reserve "))
            XCTAssertTrue(message.contains(" GB"))
            XCTAssertNotNil(message.range(of: #"need \d+\.\d{2} GB"#, options: .regularExpression))
            XCTAssertNotNil(message.range(of: #"free \d+\.\d{2} GB"#, options: .regularExpression))
            XCTAssertNotNil(message.range(of: #"reserve \d+\.\d{2} GB"#, options: .regularExpression))
        }
    }

    func testCheckExistingAgainstDestinationAfterScratchRename() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let destination = root.appendingPathComponent("mapping-layers/e80815fa-0000-0000-0000-000000000001", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let missingScratch = root.appendingPathComponent("mapping-processing/dead-scratch", isDirectory: true)
        XCTAssertThrowsError(try onIO { try MappingStorageBudget.freeBytes(missingScratch) })
        XCTAssertNoThrow(try onIO { try MappingStorageBudget.checkExisting(destination, bucket: "Mapping work space") })
    }

    func testLabelUsesTabletFallbackForAppSupportStylePath() {
        let path = URL(fileURLWithPath: "/var/mobile/Containers/Data/Application/ABC/Library/Application Support/mapping-missions")
        XCTAssertEqual(MappingStorageBudget.label(path), "Tablet")
    }
}
