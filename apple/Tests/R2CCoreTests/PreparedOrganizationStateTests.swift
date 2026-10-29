import XCTest
@testable import R2CCore

final class PreparedOrganizationStateTests: XCTestCase {
    func testLargeRosterIsParsedAndBridgedBeforeReturningToUI() async throws {
        let snapshot = try await Task.detached {
            XCTAssertFalse(Thread.isMainThread)
            let data = try JSONSerialization.data(withJSONObject: ["aircraft": (0..<20_000).map { ["remoteId": "\($0)"] }])
            return try PreparedOrganizationState(data: data)
        }.value
        XCTAssertEqual((snapshot.value["aircraft"] as? [[String: Any]])?.count, 20_000)
    }

    func testMalformedOrWrongShapeStateIsRejectedOnWorker() async throws {
        try await Task.detached {
            for data in [Data("{damaged".utf8), Data("[]".utf8)] {
                XCTAssertThrowsError(try PreparedOrganizationState(data: data))
            }
        }.value
    }
}
