import XCTest
@testable import R2CCore

final class AircraftModelSuggestionTests: XCTestCase {
    func testKnownAircraftAndNormalizedInput() {
        XCTAssertEqual(AircraftModelSuggestion.from(remoteID: " 1581f6z9c24bh0036ejl "), "DJI Mini 4 Pro")
        XCTAssertEqual(AircraftModelSuggestion.from(remoteID: "1581F8HGX255S00A0FZT"), "DJI Matrice 4TD")
    }
    func testAmbiguousAndUnknownIdentifiersRemainBlank() {
        XCTAssertEqual(AircraftModelSuggestion.from(remoteID: "1581"), "")
        XCTAssertEqual(AircraftModelSuggestion.from(remoteID: "unknown-module"), "")
        XCTAssertEqual(AircraftModelSuggestion.from(remoteID: ""), "")
    }
}
