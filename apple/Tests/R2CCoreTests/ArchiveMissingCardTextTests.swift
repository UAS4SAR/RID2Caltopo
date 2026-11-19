import Foundation
import XCTest
@testable import R2CCore

/// Ken, 2026-11-18: a deliberately swapped card is not an error; the chip and alert are only for
/// the active archive folder. Same strings as Android ArchiveWritabilityTextTest.
final class ArchiveMissingCardTextTests: XCTestCase {
    private let missing = ArchiveWritabilityVerdict(writable: false, problem: .missing, removable: true, generation: 1)

    func testActiveArchiveCardMissingSaysReinsertOrChooseAnother() {
        XCTAssertEqual(ArchiveWritabilityText.alertTitle(missing), "SD card is missing")
        XCTAssertEqual(ArchiveWritabilityText.chip(missing, fallbackAvailable: true), "SD card missing · saving to tablet")
        let message = ArchiveWritabilityText.alertMessage(missing, fallbackAvailable: true)
        XCTAssertTrue(message.hasPrefix("The SD card with your archive folder is not in this tablet."), message)
        XCTAssertTrue(message.contains("Reinsert that card, or choose another archive folder in Manage Storage"), message)
        XCTAssertFalse(message.localizedCaseInsensitiveContains("previous"), message)
        XCTAssertTrue(ArchiveWritabilityText.alertMessage(missing, fallbackAvailable: false).contains("Tablet storage is also low"))
    }

    func testOtherProblemsKeepTheirWording() {
        let readOnly = ArchiveWritabilityVerdict(writable: false, problem: .readOnly, removable: true, generation: 1)
        XCTAssertTrue(ArchiveWritabilityText.alertMessage(readOnly, fallbackAvailable: true).hasPrefix("SD card is read-only (likely failing)."))
    }
}
