import XCTest
@testable import R2CCore

final class WaypointRecordingCadenceTests: XCTestCase {
    func testInterleavedSourcesDoNotMoveAnchorWhenSuppressed() {
        var previous: Date?
        var accepted: [Double] = []
        for seconds in [10.0, 10.2, 10.9, 11.0, 11.05, 10.5, 11.999, 12.0] {
            let candidate = Date(timeIntervalSince1970: seconds)
            if WaypointRecordingCadence.accepts(previous: previous, candidate: candidate) {
                previous = candidate
                accepted.append(seconds)
            }
        }
        XCTAssertEqual(accepted, [10, 11, 12])
        XCTAssertTrue(WaypointRecordingCadence.accepts(previous: nil, candidate: Date(timeIntervalSince1970: 12.1)))
    }
}
