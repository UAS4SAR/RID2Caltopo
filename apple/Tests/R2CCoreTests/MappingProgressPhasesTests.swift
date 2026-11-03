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

    func testWeightsScaleWithPhotoCount() {
        let small = MappingProgressPhases.phases(engine: "rtk-surface", photoCount: 20)
        let large = MappingProgressPhases.phases(engine: "rtk-surface", photoCount: 200)
        let smallPrepare = small.first { $0.id == MappingProgressPhases.prepareColors }!.weight
        let largePrepare = large.first { $0.id == MappingProgressPhases.prepareColors }!.weight
        let smallPublish = small.first { $0.id == MappingProgressPhases.publish }!.weight
        let largePublish = large.first { $0.id == MappingProgressPhases.publish }!.weight
        XCTAssertGreaterThan(largePrepare, smallPrepare * 0.9)
        XCTAssertLessThan(largePublish, smallPublish)
        let seams = large.first { $0.id == MappingProgressPhases.matchSeams }!.weight
        XCTAssertGreaterThan(seams, 0.15)
    }

    func testPrepareColorsAtPartialCannotReachNextStageStart() {
        var tracker = MappingProgressPhases.tracker(engine: "rtk-surface", photoCount: 134)
        let at54 = tracker.overall(MappingProgressPhases.prepareColors, unitIndex: 54, unitTotal: 134)
        let seamsStart = tracker.band(MappingProgressPhases.matchSeams).lowerBound
        XCTAssertLessThan(at54, seamsStart - 1e-9)
        XCTAssertLessThan(at54, 0.8)
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

    func testStageNamesPhaseAndCounts() {
        let tracker = MappingProgressPhases.tracker(engine: "flat")
        XCTAssertEqual(tracker.stage(MappingProgressPhases.checkOriginals, unitIndex: 3, unitTotal: 10), "Checking originals · 3/10")
        XCTAssertEqual(tracker.stage(MappingProgressPhases.publish), "Publishing")
    }

    func testEtaDecreasesUnderConstantRate() {
        var tracker = MappingProgressPhases.tracker(engine: "rtk-surface", photoCount: 50)
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        var etaPrev: Int64?
        for i in 0...40 {
            let overall = min(0.9, 0.1 + 0.02 * Double(i))
            let now = t0.addingTimeInterval(Double(i) * 2)
            let eta = tracker.etaSeconds(overall: overall, now: now)
            if let eta, let prev = etaPrev {
                XCTAssertLessThanOrEqual(eta, prev + 2)
            }
            if let eta { etaPrev = eta }
        }
        XCTAssertNotNil(etaPrev)
        XCTAssertLessThan(etaPrev!, 300)
    }

    func testFlatStageDetection() {
        var tracker = MappingProgressPhases.tracker(engine: "rtk-surface", photoCount: 50)
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertFalse(tracker.isFlat(overall: 0.5, now: t0, flatSeconds: 60))
        XCTAssertFalse(tracker.isFlat(overall: 0.5, now: t0.addingTimeInterval(30), flatSeconds: 60))
        XCTAssertTrue(tracker.isFlat(overall: 0.5, now: t0.addingTimeInterval(61), flatSeconds: 60))
        XCTAssertFalse(tracker.isFlat(overall: 0.51, now: t0.addingTimeInterval(62), flatSeconds: 60))
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
