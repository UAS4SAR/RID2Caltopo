import XCTest
@testable import R2CCore
final class AwaitingMapReminderTests: XCTestCase {
    func testLaterSuppressesRepeatsButNextFlightReminds() {
        var reminder = AwaitingMapReminder()
        XCTAssertFalse(reminder.shouldPresent(eligibleFlightIDs: [], hasMap: false))
        XCTAssertTrue(reminder.shouldPresent(eligibleFlightIDs: ["first"], hasMap: false))
        for _ in 0..<5 { XCTAssertFalse(reminder.shouldPresent(eligibleFlightIDs: ["first"], hasMap: false)) }
        XCTAssertFalse(reminder.shouldPresent(eligibleFlightIDs: [], hasMap: false))
        XCTAssertFalse(reminder.shouldPresent(eligibleFlightIDs: ["first"], hasMap: false))
        XCTAssertTrue(reminder.shouldPresent(eligibleFlightIDs: ["first", "second"], hasMap: false))
    }
    func testSelectedMapDoesNotConsumeReminderAndReconnectDoesNotReopenIt() {
        var reminder = AwaitingMapReminder()
        XCTAssertFalse(reminder.shouldPresent(eligibleFlightIDs: ["flight"], hasMap: true))
        XCTAssertTrue(reminder.shouldPresent(eligibleFlightIDs: ["flight"], hasMap: false))
        XCTAssertFalse(reminder.shouldPresent(eligibleFlightIDs: ["flight"], hasMap: true))
        XCTAssertFalse(reminder.shouldPresent(eligibleFlightIDs: ["flight"], hasMap: false))
    }
}
