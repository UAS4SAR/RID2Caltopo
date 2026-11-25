import XCTest
@testable import R2CCore

final class DroneConfirmationInspectionTests: XCTestCase {
    func testConfirmationStaysHiddenOnlyWhileInspectionIsOpen() {
        XCTAssertTrue(DroneConfirmationInspection.shouldPresentPanel(hasPendingConfirmation: true, activeInspectionRemoteID: nil))
        XCTAssertFalse(DroneConfirmationInspection.shouldPresentPanel(hasPendingConfirmation: true, activeInspectionRemoteID: "ABC"))
        XCTAssertFalse(DroneConfirmationInspection.shouldPresentPanel(hasPendingConfirmation: false, activeInspectionRemoteID: nil))
        XCTAssertFalse(DroneConfirmationInspection.shouldPresentPanel(hasPendingConfirmation: false, activeInspectionRemoteID: "ABC"))
    }

    func testHeldConfirmationReturnsUntilMineOrNotMine() {
        XCTAssertTrue(DroneConfirmationInspection.shouldRestoreHeldConfirmation(heldRemoteID: "ABC", alreadyConfirmed: false, alreadyIgnored: false))
        XCTAssertFalse(DroneConfirmationInspection.shouldRestoreHeldConfirmation(heldRemoteID: "ABC", alreadyConfirmed: true, alreadyIgnored: false))
        XCTAssertFalse(DroneConfirmationInspection.shouldRestoreHeldConfirmation(heldRemoteID: "ABC", alreadyConfirmed: false, alreadyIgnored: true))
        XCTAssertFalse(DroneConfirmationInspection.shouldRestoreHeldConfirmation(heldRemoteID: nil, alreadyConfirmed: false, alreadyIgnored: false))
        XCTAssertFalse(DroneConfirmationInspection.shouldRestoreHeldConfirmation(heldRemoteID: "", alreadyConfirmed: false, alreadyIgnored: false))
        XCTAssertTrue(DroneConfirmationInspection.shouldQueueNextConfirmation(heldRemoteID: nil))
        XCTAssertFalse(DroneConfirmationInspection.shouldQueueNextConfirmation(heldRemoteID: "ABC"))
    }

    func testInspectFromConfirmationHoldsThePanelInAppSources() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let content = try String(contentsOf: root.appendingPathComponent("apple/App/ContentView.swift"), encoding: .utf8)
        XCTAssertTrue(content.contains("confirmationHeldForInspection"))
        XCTAssertTrue(content.contains("restoreConfirmationAfterInspection()"))
        XCTAssertTrue(content.contains("mapViewportMemory.inspectionSessionRemoteID = remoteID"))
        XCTAssertTrue(content.contains("DroneConfirmationInspection.shouldRestoreHeldConfirmation"))
        XCTAssertTrue(content.contains("DroneConfirmationInspection.shouldQueueNextConfirmation"))
        // Inspect must not discard the pending confirmation without holding it first.
        XCTAssertFalse(content.contains("addRidMapRemoteID = nil\n                pendingDroneConfirmation = nil"))
        let map = try String(contentsOf: root.appendingPathComponent("apple/App/RIDTrackMapView.swift"), encoding: .utf8)
        XCTAssertTrue(map.contains("inspectionSessionRemoteID"))
        XCTAssertTrue(map.contains("viewportMemory.inspectionSessionRemoteID = nil"))
        let detail = try String(contentsOf: root.appendingPathComponent("apple/App/RIDAircraftDetailView.swift"), encoding: .utf8)
        XCTAssertTrue(detail.contains("Notification.Name(\"workspaceInspectDrone\")"))
    }
}

