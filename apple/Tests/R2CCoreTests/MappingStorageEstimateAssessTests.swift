import XCTest
@testable import R2CCore

final class MappingStorageEstimateAssessTests: XCTestCase {
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

    func testAssessShortageMessageNamesBucketNeedAndFree() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let originals = root.appendingPathComponent("m", isDirectory: true)
        let scratch = root.appendingPathComponent("p", isDirectory: true)
        let layers = root.appendingPathComponent("l", isDirectory: true)
        for url in [originals, scratch, layers] {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        defer { try? FileManager.default.removeItem(at: root) }
        let huge = MappingProcessingPolicy.StorageEstimate(
            originals: Int64.max / 8, products: Int64.max / 8, temporary: Int64.max / 8
        )
        let assessment = try onIO { try huge.assess(originalsRoot: originals, scratchRoot: scratch, layersRoot: layers) }
        XCTAssertFalse(assessment.shortages.isEmpty)
        let text = assessment.summary()
        XCTAssertTrue(text.contains("need "))
        XCTAssertTrue(text.contains("free "))
        XCTAssertTrue(text.contains("reserve "))
        XCTAssertTrue(assessment.shortages.contains { text.contains($0.bucket) })
    }

    func testAssessReportsPerBucketNeedFree() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let originals = root.appendingPathComponent("mapping-missions", isDirectory: true)
        let scratch = root.appendingPathComponent("mapping-processing", isDirectory: true)
        let layers = root.appendingPathComponent("mapping-layers", isDirectory: true)
        for url in [originals, scratch, layers] {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        defer { try? FileManager.default.removeItem(at: root) }
        let estimate = MappingProcessingPolicy.StorageEstimate(originals: 1000, products: 3000, temporary: 2000)
        let assessment = try onIO { try estimate.assess(originalsRoot: originals, scratchRoot: scratch, layersRoot: layers) }
        XCTAssertEqual(assessment.buckets.count, 3)
        XCTAssertEqual(Set(assessment.buckets.map(\.bucket)), ["Original photos", "Mapping work space", "Map products"])
        for bucket in assessment.buckets {
            XCTAssertGreaterThan(bucket.freeBytes, 0)
            XCTAssertTrue(bucket.message().contains("need "))
            XCTAssertTrue(bucket.message().contains("free "))
            XCTAssertTrue(bucket.message().contains(bucket.bucket))
        }
        XCTAssertFalse(assessment.summary().lowercased().contains("tablet storage is full"))
    }
}
