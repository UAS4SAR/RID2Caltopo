import Foundation
import XCTest
@testable import R2CCore

final class AircraftMappingSnapshotTests: XCTestCase {
    func testConflictingDuplicateIDsAreUnassignedAndOriginalRecordsSurvive() throws {
        let entries = [["remoteID": "A", "mappedID": "first"],
                       ["remoteID": "A", "mappedID": "second"],
                       ["remoteID": "A", "mappedID": "first"],
                       ["remoteID": "B", "mappedID": "other"]]
        let snapshot = AircraftMappingSnapshot(records: entries)
        XCTAssertEqual(snapshot.records, entries)
        XCTAssertEqual(snapshot.allIdentities.count, entries.count)
        XCTAssertNil(snapshot.identities["A"])
        XCTAssertEqual(snapshot.identities["B"]?.mappedID, "other")
        XCTAssertEqual(snapshot.conflictingIDs, ["A"])
        XCTAssertThrowsError(try snapshot.requireUnambiguous())
    }

    func testIdenticalDuplicatesCollapseForDisplayWithoutRewritingOriginals() throws {
        let entry = ["remoteID": "A", "ownerCallsign": "1SAR7", "mappedID": "saved"]
        let snapshot = AircraftMappingSnapshot(records: [entry, entry])
        XCTAssertEqual(snapshot.identities.count, 1)
        XCTAssertEqual(snapshot.records.count, 2)
        XCTAssertEqual(snapshot.duplicateIDs, ["A"])
        try snapshot.requireUnambiguous()
    }

    func testLargeConflictListPreparesABoundedRecoveryMessage() {
        let entries = (0..<100).flatMap { index in
            [["remoteID": "\(index)-" + String(repeating: "A", count: 1_000), "mappedID": "first"],
             ["remoteID": "\(index)-" + String(repeating: "A", count: 1_000), "mappedID": "second"]]
        }
        let snapshot = AircraftMappingSnapshot(records: entries)
        XCTAssertEqual(snapshot.conflictingIDs.count, 100)
        XCTAssertLessThan(snapshot.recoveryMessage!.count, 600)
        XCTAssertTrue(snapshot.recoveryMessage!.contains("95 more"))
        let unicodeID = "A" + String(repeating: "\u{0301}", count: 10_000)
        let unicode = AircraftMappingSnapshot(records: [["remoteID": unicodeID, "mappedID": "first"], ["remoteID": unicodeID, "mappedID": "second"]])
        XCTAssertLessThan(unicode.recoveryMessage!.utf8.count, 600)
        do { try snapshot.requireUnambiguous(); XCTFail("Expected conflict") }
        catch { XCTAssertLessThan(error.localizedDescription.count, 600) }
    }

    func testPreparationLeavesMainBeforeLargeSavedProfileWork() async {
        let snapshot = await Task.detached {
            XCTAssertFalse(Thread.isMainThread)
            return AircraftMappingSnapshot(records: (0..<20_000).map { ["remoteID": "\($0)"] })
        }.value
        XCTAssertEqual(snapshot.identities.count, 20_000)
    }
}
