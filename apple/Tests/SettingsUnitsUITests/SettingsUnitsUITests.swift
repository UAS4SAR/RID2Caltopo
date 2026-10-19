import XCTest

@MainActor
final class SettingsUnitsUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "org.ncssar.RID2CaltopoApple")
    override func setUp() { continueAfterFailure = false }
    private func launch(reset: Bool = false) {
        app.launchArguments = ["--settings-units-ui-qualification"] + (reset ? ["--reset-units-fixture"] : [])
        app.launch()
        XCTAssertTrue(app.buttons["Open Settings"].waitForExistence(timeout: 20))
    }
    private func open() {
        app.buttons["Open Settings"].tap()
        XCTAssertTrue(app.buttons["Metric"].waitForExistence(timeout: 10))
    }
    private func visible(_ label: String) -> XCUIElement {
        let element = app.steppers.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
        for _ in 0..<16 {
            if element.exists {
                let frame = element.frame
                if element.isHittable && frame.minY > 100 && frame.maxY < app.frame.maxY - 180 { return element }
                // Keep the entire stepper clear of both navigation and the pinned Save bar.
                let down = frame.minY < 100
                let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.4 : 0.7))
                let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.65 : 0.45))
                start.press(forDuration: 0.05, thenDragTo: end)
            } else { app.swipeUp() }
        }
        XCTFail("Did not reveal \(label)\n\(app.debugDescription)")
        return element
    }
    private func capture(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }
    func testSettingsUnitsSaveCancelAndRelaunch() {
        launch(reset: true)
        open()
        XCTAssertTrue(app.buttons["Imperial"].isSelected)
        capture("apple-imperial-selector")
        app.buttons["Metric"].tap()
        XCTAssertTrue(app.buttons["Metric"].isSelected)
        XCTAssertTrue(visible("Proximity spacing: 30.5 m").exists)
        XCTAssertTrue(visible("Min Dist: 30.5 m").exists)
        XCTAssertTrue(visible("Bridge Check Distance: 6.1 m").exists)
        capture("apple-metric-conversions")
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["Stored units: imperial"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Stored track distance: 100 ft"].exists)
        open()
        XCTAssertTrue(app.buttons["Imperial"].isSelected)
        app.buttons["Metric"].tap()
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["Stored units: metric"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Stored track distance: 100 ft"].exists)
        app.terminate(); launch()
        XCTAssertTrue(app.staticTexts["Stored units: metric"].exists)
        open()
        XCTAssertTrue(app.buttons["Metric"].isSelected)
        XCTAssertTrue(visible("Min Dist: 30.5 m").exists)
        let stepper = app.steppers.matching(NSPredicate(format: "label CONTAINS 'Min Dist'")).firstMatch
        stepper.buttons["Increment"].tap()
        capture("apple-after-increment")
        XCTAssertTrue(visible("Min Dist: 30.8 m").exists)
        capture("apple-metric-edited-distance")
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["Stored track distance: 101 ft"].waitForExistence(timeout: 5))
        open()
        app.buttons["Imperial"].tap()
        XCTAssertTrue(visible("Min Dist: 101.0 ft").exists)
        let imperialStepper = app.steppers.matching(NSPredicate(format: "label CONTAINS 'Min Dist'")).firstMatch
        imperialStepper.buttons["Increment"].tap()
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.staticTexts["Stored units: metric"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Stored track distance: 101 ft"].exists)
        open()
        app.buttons["Imperial"].tap()
        app.buttons["Back"].tap()
        XCTAssertTrue(app.buttons["Discard Changes"].waitForExistence(timeout: 5))
        capture("apple-dirty-exit")
        if app.buttons["Keep Editing"].exists {
            app.buttons["Keep Editing"].tap()
        } else {
            // iPad confirmation popovers omit the cancel-role action; tapping outside keeps editing.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.8)).tap()
        }
        XCTAssertTrue(app.buttons["Discard Changes"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Imperial"].isSelected)
        app.buttons["Back"].tap()
        XCTAssertTrue(app.buttons["Discard Changes"].waitForExistence(timeout: 5))
        app.buttons["Discard Changes"].tap()
        XCTAssertTrue(app.staticTexts["Stored units: metric"].waitForExistence(timeout: 5))
        app.terminate(); launch()
        XCTAssertTrue(app.staticTexts["Stored units: metric"].exists)
        XCTAssertTrue(app.staticTexts["Stored track distance: 101 ft"].exists)
        capture("apple-final-relaunch-persistence")
    }
}
