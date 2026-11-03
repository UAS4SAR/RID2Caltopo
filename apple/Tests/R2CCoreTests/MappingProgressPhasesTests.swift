import XCTest
@testable import R2CCore

final class MappingProgressPhasesTests: XCTestCase {
    func testOverallIsMonotonicAcrossPhases() {
        let tracker = MappingProgressPhases.tracker(engine: "flat")
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
        let tracker = MappingProgressPhases.tracker(engine: "rtk-surface")
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
        let tracker = MappingProgressPhases.tracker(engine: "flat")
        let band = tracker.band(MappingProgressPhases.matchSeams)
        let value = tracker.overall(MappingProgressPhases.matchSeams, local: nil)
        XCTAssertEqual(band.lowerBound, value, accuracy: 1e-9)
        XCTAssertLessThan(value, band.upperBound)
        XCTAssertEqual(band.lowerBound, tracker.overall(MappingProgressPhases.matchSeams, local: nil), accuracy: 1e-9)
        XCTAssertGreaterThan(abs(tracker.overall(MappingProgressPhases.matchSeams, local: 0.85) - band.lowerBound), 0.01)
    }

    func testStageNamesPhaseAndCounts() {
        let tracker = MappingProgressPhases.tracker(engine: "flat")
        XCTAssertEqual(tracker.stage(MappingProgressPhases.checkOriginals, unitIndex: 3, unitTotal: 10), "Checking originals · 3/10")
        XCTAssertEqual(tracker.stage(MappingProgressPhases.publish), "Publishing")
    }
}
