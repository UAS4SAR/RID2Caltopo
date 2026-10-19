import XCTest

@MainActor final class MappingUITests:XCTestCase {
    func testDeletionConfirmationPreservesSelectedOperation() {
        let app=XCUIApplication(bundleIdentifier:"org.ncssar.RID2CaltopoApple")
        app.launchArguments=["--mapping-ui-qualification","--mapping-ui-deletion-fixture","--mapping-ui-no-incident"]
        app.launchEnvironment["TZ"]="America/Los_Angeles"
        app.launch()
        let open=app.buttons["mapping-open-00000000-0000-0000-0000-000000000081"]
        XCTAssertTrue(open.waitForExistence(timeout:20))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format:"label BEGINSWITH 'Planned: Oct 15, 2026'")).firstMatch.exists)
        XCTAssertFalse(app.staticTexts["Planned: 2026-10-15T14:00:00Z"].exists)
        open.tap()
        let delete=app.buttons["mapping-delete-flight"]
        XCTAssertTrue(delete.waitForExistence(timeout:10));delete.tap()
        app.alerts.buttons["Cancel"].tap()
        XCTAssertTrue(delete.exists)
        app.buttons["mapping-delete-results"].tap()
        app.alerts.buttons["Delete results"].tap()
        XCTAssertTrue(open.waitForExistence(timeout:10));open.tap()
        XCTAssertTrue(delete.waitForExistence(timeout:10));delete.tap()
        app.alerts.buttons["Delete flight and original photos"].tap()
        XCTAssertTrue(open.waitForNonExistence(timeout:10))
        XCTAssertTrue(app.staticTexts["No mapping missions yet."].waitForExistence(timeout:10))
    }
    func testPlannerUsesOneObstacleField() {
        let app=XCUIApplication(bundleIdentifier:"org.ncssar.RID2CaltopoApple")
        app.launchArguments=["--mapping-ui-qualification","--mapping-ui-planner"]
        app.launch();let obstacle=app.textFields["mapping-obstacle-height"]
        for _ in 0..<5 {if obstacle.isHittable {break};app.swipeUp()}
        XCTAssertTrue(obstacle.exists)
        XCTAssertFalse(app.textFields.matching(NSPredicate(format:"label CONTAINS 'Canopy + terrain'")).firstMatch.exists)
        obstacle.tap();obstacle.typeText("80")
        XCTAssertEqual(obstacle.value as? String,"80")
        let clearance=app.textFields["mapping-clearance-margin"]
        for _ in 0..<5 {if clearance.isHittable {break};app.swipeUp()}
        XCTAssertTrue(clearance.exists)
        XCTAssertEqual(Double(clearance.value as? String ?? "") ?? 0,49.21,accuracy:0.02)
        func replace(_ value:String){clearance.tap();clearance.typeText(String(repeating:XCUIKeyboardKey.delete.rawValue,count:(clearance.value as? String ?? "").count)+value)}
        replace("200")
        XCTAssertTrue(app.staticTexts["mapping-route-estimate"].waitForNonExistence(timeout:10))
        replace("30")
        XCTAssertTrue(app.staticTexts["mapping-route-estimate"].waitForExistence(timeout:10))

    }
}
