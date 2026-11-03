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

    func testCalibratedDefaultsMatchFullRunMagnitudes() {
        let n = 134
        let tiles = 195
        let costs = MappingProgressPhases.defaultCostsMs(photoCount: n, engine: "rtk-surface", tileCount: tiles)
        XCTAssertEqual(costs[MappingProgressPhases.matchSeams]!, 26_300.0 * Double(n), accuracy: 1)
        XCTAssertEqual(costs[MappingProgressPhases.projectTiles]!, 12_800.0 * Double(tiles), accuracy: 1)
        XCTAssertEqual(costs[MappingProgressPhases.prepareColors]!, 1_350.0 * Double(n), accuracy: 1)
        let sum = costs.values.reduce(0, +)
        XCTAssertEqual(sum, 6_490_000, accuracy: 50_000)
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

    func testTileLocalPrefersWorkUnitsOverUniformCount() {
        let byCount = MappingProgressPhases.tileLocalFraction(unitIndex: 100, unitTotal: 195)
        let byWork = MappingProgressPhases.tileLocalFraction(unitIndex: 100, unitTotal: 195, workDone: 200, workTotal: 2500)
        XCTAssertLessThan(byCount, 0.20)
        XCTAssertEqual(byWork, 0.08, accuracy: 1e-9)
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

    func testNonFiniteLocalAndTimeHintTreatedAsNull() {
        var tracker = MappingProgressPhases.tracker(engine: "flat", photoCount: 10)
        let band = tracker.band(MappingProgressPhases.matchSeams)
        XCTAssertEqual(tracker.overall(MappingProgressPhases.matchSeams, local: .nan), band.lowerBound, accuracy: 1e-9)
        XCTAssertEqual(tracker.overall(MappingProgressPhases.matchSeams, local: .infinity), band.lowerBound, accuracy: 1e-9)
        XCTAssertEqual(tracker.overall(MappingProgressPhases.matchSeams, local: nil, timeHint: .nan), band.lowerBound, accuracy: 1e-9)
        let advanced = tracker.overall(MappingProgressPhases.matchSeams, local: 0.5)
        XCTAssertTrue(advanced.isFinite && advanced > band.lowerBound)
    }

    func testUnitTotalNonPositiveSkippedAndIndexClamped() {
        var tracker = MappingProgressPhases.tracker(engine: "rtk-surface", photoCount: 20)
        let start = tracker.band(MappingProgressPhases.projectTiles).lowerBound
        XCTAssertEqual(tracker.overall(MappingProgressPhases.projectTiles, unitIndex: 5, unitTotal: 0), start, accuracy: 1e-9)
        XCTAssertEqual(tracker.overall(MappingProgressPhases.projectTiles, unitIndex: -3, unitTotal: 10), start, accuracy: 1e-9)
        let atEnd = tracker.overall(MappingProgressPhases.projectTiles, unitIndex: 99, unitTotal: 10)
        XCTAssertEqual(atEnd, tracker.band(MappingProgressPhases.projectTiles).upperBound, accuracy: 1e-9)
    }

    func testUnknownPhaseFallsBackSafely() {
        let tracker = MappingProgressPhases.tracker(engine: "flat", photoCount: 5)
        XCTAssertEqual(tracker.weight("no-such-phase"), 0)
        XCTAssertEqual(tracker.title("no-such-phase"), "no-such-phase")
        XCTAssertEqual(tracker.band("no-such-phase"), 0...0)
    }

    func testCostsWriteFailureDoesNotThrow() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        // Place a file where the stats directory should be so write fails without killing the caller.
        let blocker = root.appendingPathComponent("mapping-storage-stats")
        try Data("not-a-dir".utf8).write(to: blocker)
        MappingProgressPhases.Costs.record(supportRoot: root, phaseId: MappingProgressPhases.reconstruct, elapsedMs: 1000, photoCount: 10)
        MappingProgressPhases.Costs.write(supportRoot: root, msPerPhoto: [MappingProgressPhases.reconstruct: 1])
        XCTAssertTrue(FileManager.default.fileExists(atPath: blocker.path))
    }

    /// Replay the measured A5 Pro full run (134 photos, 195 tiles, ~6490 s).
    func testCalibratedFullRunReplayMatchesWallShareAndEta() {
        let n = 134
        let tiles = 195
        let totalSec = 6490.0
        let earlySec = 167.0
        let colorsSec = 180.0
        let seamsSec = 3527.0
        let tilesSec = 2500.0
        let zoomSec = 12.0
        var tracker = MappingProgressPhases.tracker(engine: "rtk-surface", photoCount: n, tileCount: tiles)
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        func at(_ elapsedSec: Double) -> Date { t0.addingTimeInterval(elapsedSec) }

        _ = tracker.overall(MappingProgressPhases.checkOriginals, local: 1)
        _ = tracker.etaSeconds(overall: tracker.band(MappingProgressPhases.checkOriginals).upperBound, now: at(10))
        let afterEarly = tracker.overall(MappingProgressPhases.reconstruct, local: 1)
        _ = tracker.etaSeconds(overall: afterEarly, now: at(earlySec))
        XCTAssertLessThanOrEqual(afterEarly, 0.15)

        let afterColors = tracker.overall(MappingProgressPhases.prepareColors, local: 1)
        let colorsShare = (earlySec + colorsSec) / totalSec
        _ = tracker.etaSeconds(overall: afterColors, now: at(earlySec + colorsSec))
        XCTAssertLessThanOrEqual(afterColors, 0.15)
        XCTAssertLessThanOrEqual(abs(afterColors - colorsShare), 0.10)

        var overall = afterColors
        let seamsStartElapsed = earlySec + colorsSec
        for step in 1...20 {
            let local = Double(step) / 20.0
            overall = tracker.overall(MappingProgressPhases.matchSeams, local: local)
            _ = tracker.etaSeconds(overall: overall, now: at(seamsStartElapsed + seamsSec * local))
        }
        let afterSeams = overall
        let seamsShare = (earlySec + colorsSec + seamsSec) / totalSec
        XCTAssertTrue((0.55...0.70).contains(afterSeams), "afterSeams=\(afterSeams)")
        XCTAssertLessThanOrEqual(abs(afterSeams - seamsShare), 0.10)

        func tileElapsed(_ completed: Int) -> Double {
            var sum = 0.0
            for i in 1...completed {
                if i <= 100 { sum += 2 }
                else if i <= 157 { sum += 10 }
                else { sum += 45 }
            }
            return sum
        }
        let tilesStartElapsed = seamsStartElapsed + seamsSec
        var midTileOverall = afterSeams
        var midTileEta: Int64?
        var midTileRemaining: Double?
        for i in 1...tiles {
            overall = tracker.overall(MappingProgressPhases.projectTiles, unitIndex: i, unitTotal: tiles)
            let elapsed = tilesStartElapsed + tileElapsed(i)
            let eta = tracker.etaSeconds(overall: overall, now: at(elapsed))
            if midTileRemaining == nil && tileElapsed(i) >= tilesSec / 2 {
                midTileOverall = overall
                midTileEta = eta
                midTileRemaining = totalSec - elapsed
            }
        }
        let afterTiles = overall
        let tilesShare = (earlySec + colorsSec + seamsSec + tilesSec) / totalSec
        XCTAssertGreaterThanOrEqual(afterTiles, 0.95)
        XCTAssertLessThanOrEqual(abs(afterTiles - tilesShare), 0.10)

        XCTAssertNotNil(midTileEta, "mid-tile ETA should be available")
        XCTAssertNotNil(midTileRemaining)
        let remaining = midTileRemaining!
        let eta = Double(midTileEta!)
        XCTAssertTrue(
            (remaining / 2)...(remaining * 2) ~= eta,
            "midTile overall=\(midTileOverall) eta=\(eta) remaining=\(remaining)"
        )

        overall = tracker.overall(MappingProgressPhases.zoomLevels, local: 1)
        _ = tracker.etaSeconds(overall: overall, now: at(tilesStartElapsed + tilesSec + zoomSec))
        overall = tracker.overall(MappingProgressPhases.package, local: 1)
        _ = tracker.etaSeconds(overall: overall, now: at(totalSec - 5))
        let done = tracker.overall(MappingProgressPhases.publish, local: 1)
        XCTAssertEqual(done, 1, accuracy: 1e-9)
    }
}
