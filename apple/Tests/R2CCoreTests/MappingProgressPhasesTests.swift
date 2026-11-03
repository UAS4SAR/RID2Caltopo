import XCTest
@testable import R2CCore

final class MappingProgressPhasesTests: XCTestCase {
    func testOverallIsMonotonicAcrossPhases() {
        var tracker = MappingProgressPhases.tracker(engine: "flat", photoCount: 40)
        var samples: [Double] = []
        for phase in [
            MappingProgressPhases.checkOriginals,
            MappingProgressPhases.reconstruct,
            MappingProgressPhases.prepareColors,
            MappingProgressPhases.matchSeams,
            MappingProgressPhases.projectTiles,
            MappingProgressPhases.zoomLevels,
            MappingProgressPhases.package,
            MappingProgressPhases.publish,
        ] {
            samples.append(tracker.overall(phase, local: 0))
            samples.append(tracker.overall(phase, local: 1))
        }
        for i in 1..<samples.count {
            XCTAssertGreaterThanOrEqual(samples[i] + 1e-9, samples[i - 1])
        }
        XCTAssertEqual(samples.first!, 0, accuracy: 1e-9)
        XCTAssertEqual(samples.last!, 1, accuracy: 1e-9)
    }

    func testUnitStepsStayUnderThirtyPointsWithinKnownWorkPhase() {
        var tracker = MappingProgressPhases.tracker(engine: "rtk-surface", photoCount: 20)
        let total = 20
        var previous = tracker.overall(MappingProgressPhases.projectTiles, unitIndex: 0, unitTotal: total)
        for i in 1...total {
            let next = tracker.overall(MappingProgressPhases.projectTiles, unitIndex: i, unitTotal: total)
            XCTAssertGreaterThanOrEqual(next, previous)
            XCTAssertLessThanOrEqual((next - previous) * 100, 30.0 + 1e-9)
            previous = next
        }
    }

    func testIndeterminateStaysAtBandStart() {
        var tracker = MappingProgressPhases.tracker(engine: "flat", photoCount: 10)
        let band = tracker.band(MappingProgressPhases.matchSeams)
        let value = tracker.overall(MappingProgressPhases.matchSeams, local: nil)
        XCTAssertEqual(band.lowerBound, value, accuracy: 1e-9)
        XCTAssertLessThan(value, band.upperBound)
        XCTAssertGreaterThan(abs(tracker.overall(MappingProgressPhases.matchSeams, local: 0.85) - band.lowerBound), 0.01)
    }

    func testProgressNeverGoesBackward() {
        var tracker = MappingProgressPhases.tracker(engine: "rtk-surface", photoCount: 80)
        let mid = tracker.overall(MappingProgressPhases.reconstruct, local: 0.5)
        let retreated = tracker.overall(MappingProgressPhases.reconstruct, local: 0.1)
        XCTAssertGreaterThanOrEqual(retreated + 1e-9, mid)
        let timeAdvanced = tracker.overall(MappingProgressPhases.reconstruct, local: nil, timeHint: 0.9)
        XCTAssertGreaterThanOrEqual(timeAdvanced + 1e-9, retreated)
    }

    func testRtkWeightsEmphasizeReconstructForLargePhotoCount() {
        let phases = MappingProgressPhases.phases(engine: "rtk-surface", photoCount: 200)
        let reconstruct = phases.first { $0.id == MappingProgressPhases.reconstruct }!.weight
        let packageWeight = phases.first { $0.id == MappingProgressPhases.package }!.weight
        XCTAssertGreaterThan(reconstruct, packageWeight)
        XCTAssertGreaterThan(reconstruct, 0.35)
    }

    func testStageNamesPhaseAndCounts() {
        let tracker = MappingProgressPhases.tracker(engine: "flat")
        XCTAssertEqual(tracker.stage(MappingProgressPhases.checkOriginals, unitIndex: 3, unitTotal: 10), "Checking originals · 3/10")
        XCTAssertEqual(tracker.stage(MappingProgressPhases.publish), "Publishing")
    }

    func testEtaStableAfterEnoughProgress() {
        let tracker = MappingProgressPhases.tracker(engine: "rtk-surface", photoCount: 50)
        XCTAssertNil(tracker.etaSeconds(overall: 0.05, elapsedSeconds: 60))
        XCTAssertNil(tracker.etaSeconds(overall: 0.5, elapsedSeconds: 10))
        let eta = tracker.etaSeconds(overall: 0.5, elapsedSeconds: 120)
        XCTAssertNotNil(eta)
        XCTAssertTrue((100...140).contains(eta!))
    }

    func testPhaseCostsRoundTripAndBlend() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        MappingProgressPhases.Costs.record(supportRoot: root, phaseId: MappingProgressPhases.reconstruct, elapsedMs: 10_000, photoCount: 10)
        let loaded = MappingProgressPhases.Costs.load(supportRoot: root)
        XCTAssertEqual(loaded[MappingProgressPhases.reconstruct]!, 1000, accuracy: 1e-6)
        MappingProgressPhases.Costs.record(supportRoot: root, phaseId: MappingProgressPhases.reconstruct, elapsedMs: 20_000, photoCount: 10)
        let blended = MappingProgressPhases.Costs.load(supportRoot: root)[MappingProgressPhases.reconstruct]!
        XCTAssertTrue((1300...1900).contains(blended))
    }
}
