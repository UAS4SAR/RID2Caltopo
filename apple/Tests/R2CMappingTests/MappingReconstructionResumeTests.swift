import XCTest
@testable import R2CMapping

final class MappingReconstructionResumeTests: XCTestCase {
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

    func testFindInterruptedJobsReadsStatusAndDigests() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let missionID = UUID().uuidString.lowercased()
        let mission = root.appendingPathComponent(missionID, isDirectory: true)
        try FileManager.default.createDirectory(at: mission, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let plan: [String: Any] = [
            "title": "Resume Test",
            "incidentId": "inc-1",
            "profile": "daylight-rgb",
        ]
        try JSONSerialization.data(withJSONObject: plan, options: [.sortedKeys])
            .write(to: mission.appendingPathComponent("mission.json"), options: .atomic)
        let status: [String: Any] = [
            "active": true,
            "stage": "Reconstructing geometry",
            "progress": 0.32,
            "selectedDigests": ["aa", "bb", "cc"],
        ]
        try JSONSerialization.data(withJSONObject: status, options: [.sortedKeys])
            .write(to: mission.appendingPathComponent("processing-status.json"), options: .atomic)

        let jobs = try onIO { MappingReconstruction.findInterruptedJobs(missionsRoot: root) }
        XCTAssertEqual(jobs.count, 1)
        XCTAssertEqual(jobs[0].missionID, missionID)
        XCTAssertEqual(jobs[0].title, "Resume Test")
        XCTAssertEqual(jobs[0].incidentID, "inc-1")
        XCTAssertEqual(jobs[0].selectedDigests, Set(["aa", "bb", "cc"]))
    }

    func testMarkStoppedClearsActiveSoResumeSkips() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let missionID = UUID().uuidString.lowercased()
        let mission = root.appendingPathComponent(missionID, isDirectory: true)
        try FileManager.default.createDirectory(at: mission, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try JSONSerialization.data(withJSONObject: ["title": "x", "incidentId": ""], options: [.sortedKeys])
            .write(to: mission.appendingPathComponent("mission.json"), options: .atomic)
        try JSONSerialization.data(withJSONObject: ["active": true, "stage": "working"], options: [.sortedKeys])
            .write(to: mission.appendingPathComponent("processing-status.json"), options: .atomic)

        MappingReconstruction.markStopped(root: mission, message: "Processing cancelled; originals and completed regions retained.", cancelled: true)
        let jobs = try onIO { MappingReconstruction.findInterruptedJobs(missionsRoot: root) }
        XCTAssertTrue(jobs.isEmpty)
    }

    func testClearCrashResidueWithoutRequeue() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let missionID = UUID().uuidString.lowercased()
        let mission = root.appendingPathComponent(missionID, isDirectory: true)
        try FileManager.default.createDirectory(at: mission, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try JSONSerialization.data(withJSONObject: ["title": "Crash", "incidentId": "inc"], options: [.sortedKeys])
            .write(to: mission.appendingPathComponent("mission.json"), options: .atomic)
        try JSONSerialization.data(withJSONObject: ["active": true, "stage": "Estimating unknown 2D areas"], options: [.sortedKeys])
            .write(to: mission.appendingPathComponent("processing-status.json"), options: .atomic)
        try onIO { MappingReconstruction.clearCrashResidue(missionsRoot: root) }
        let status = try JSONSerialization.jsonObject(with: Data(contentsOf: mission.appendingPathComponent("processing-status.json"))) as! [String: Any]
        XCTAssertEqual(status["active"] as? Bool, false)
        XCTAssertEqual(status["stage"] as? String, "Processing interrupted; originals retained")
        let remaining = try onIO { MappingReconstruction.findInterruptedJobs(missionsRoot: root) }
        XCTAssertTrue(remaining.isEmpty)
    }

    func testCancellationExposesIsCancelled() {
        let cancel = MappingCancellation()
        XCTAssertFalse(cancel.isCancelled)
        cancel.cancel()
        XCTAssertTrue(cancel.isCancelled)
    }
}
