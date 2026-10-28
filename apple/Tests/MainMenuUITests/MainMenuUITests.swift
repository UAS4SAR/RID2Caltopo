import XCTest

@MainActor
final class MainMenuUITests: XCTestCase {
    func testOpenMenuSurvivesStatusRefreshesAndUsesCurrentActions() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "org.ncssar.RID2CaltopoApple")
        app.launchArguments = ["--main-menu-ui-qualification"]
        app.launch()
        let menu = app.buttons["Main menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20))
        menu.tap()
        XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 5))
        for title in ["Settings", "Release Notes", "Import Config", "Manage Storage...",
                      "Send diagnostics to developer...", "Terms of Use", "About & Privacy", "Quit"] {
            XCTAssertTrue(app.buttons[title].exists, title)
        }
        XCTAssertFalse(app.buttons["Backup & Transfer"].exists)
        XCTAssertFalse(app.buttons["Archive Storage..."].exists)
        func waitForTick(_ minimum: Int) {
            let refreshed = NSPredicate { _, _ in
                let tick = app.staticTexts["menu-refresh-tick"].label
                return Int(tick.components(separatedBy: ": ").last ?? "0").map { $0 >= minimum } ?? false
            }
            expectation(for: refreshed, evaluatedWith: nil)
            waitForExpectations(timeout: 20)
        }
        func currentTick() -> Int {
            Int(app.staticTexts["menu-refresh-tick"].label.components(separatedBy: ": ").last ?? "0") ?? 0
        }
        waitForTick(currentTick() + 2)
        let counter = app.staticTexts["menu-body-count"]
        let baseline = counter.label
        waitForTick(currentTick() + 8)
        XCTAssertEqual(counter.label, baseline, "Periodic status updates must not rebuild an open menu")
        XCTAssertTrue(app.buttons["Release Notes"].exists)
        XCTAssertTrue(app.buttons["About & Privacy"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "open-menu-after-status-refreshes"; attachment.lifetime = .keepAlways
        add(attachment)
        app.buttons["About & Privacy"].tap()
        XCTAssertEqual(app.staticTexts["menu-selection"].label, "Selected: privacy")
        menu.tap()
        app.buttons["Manage Storage..."].tap()
        XCTAssertEqual(app.staticTexts["menu-selection"].label, "Selected: storage")
    }
}
