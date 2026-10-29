import XCTest

@MainActor final class StartupRecoveryUITests: XCTestCase {
    func testCorruptHistoryRemainsVisibleAcrossLaunchAndResume() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "org.ncssar.RID2CaltopoApple")
        let started = Date()
        addUIInterruptionMonitor(withDescription: "Disposable fixture system permissions") { alert in
            for title in ["Allow While Using App", "Allow", "OK"] {
                if alert.buttons[title].exists { alert.buttons[title].tap(); return true }
            }
            return false
        }
        app.launch()
        XCTAssertTrue(app.staticTexts["RID2Caltopo — Safety & License"].waitForExistence(timeout: 10))
        XCTAssertLessThan(Date().timeIntervalSince(started), 10, "Cold startup exceeded qualification budget")
        let consent = app.descendants(matching: .any).matching(identifier: "launch-terms-checkbox").firstMatch
        for _ in 0..<16 { if consent.isHittable { break }; app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(consent.isHittable, app.debugDescription); consent.tap()
        app.buttons["Acknowledge and Continue"].tap()
        let mappingRecovery = app.staticTexts["Aircraft mappings need review"]
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<20 {
            if mappingRecovery.waitForExistence(timeout: 1) { break }
            let prompt = springboard.alerts.firstMatch
            if prompt.exists {
                for title in ["Allow While Using App", "Allow", "OK"] {
                    if prompt.buttons[title].exists { prompt.buttons[title].tap(); break }
                }
            }
        }
        XCTAssertTrue(mappingRecovery.exists, "Duplicate mappings did not reach recoverable UI: \(app.debugDescription)")
        app.buttons["Dismiss mapping warning"].tap()
        let error = app.staticTexts["Saved flight history could not be read. Reconnect its storage and retry. The saved journal has been kept."]
        XCTAssertTrue(error.waitForExistence(timeout: 20), "Storage error was hidden or the app failed during startup")
        XCTAssertTrue(app.buttons["Retry flight history"].exists)
        app.buttons["Retry flight history"].tap()
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCUIDevice.shared.press(.home)
        let resumed = Date(); app.activate()
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertLessThan(Date().timeIntervalSince(resumed), 10, "Foreground return exceeded qualification budget")
        let options = XCTMeasureOptions(); options.iterationCount = 3
        measure(metrics: [XCTApplicationLaunchMetric()], options: options) {
            app.terminate(); let start = Date(); app.launch()
            let mappingRecovery = app.staticTexts["Aircraft mappings need review"]
            let loaded = NSPredicate { _, _ in
                mappingRecovery.exists || app.staticTexts["Protected access locked"].exists ||
                    XCUIApplication(bundleIdentifier: "com.apple.springboard").secureTextFields["Passcode"].exists
            }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: loaded, object: app)], timeout: 8), .completed)
            if mappingRecovery.exists { app.buttons["Dismiss mapping warning"].tap() }
            // The browser can retain cookies during the preceding real startup.
            // A fresh process must then present its normal protected-access gate.
            let ready = NSPredicate { _, _ in
                app.staticTexts["Saved flight history could not be read. Reconnect its storage and retry. The saved journal has been kept."].exists ||
                    app.staticTexts["Protected access locked"].exists ||
                    XCUIApplication(bundleIdentifier: "com.apple.springboard").secureTextFields["Passcode"].exists
            }
            let visible = XCTNSPredicateExpectation(predicate: ready, object: app)
            XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 8), .completed, app.debugDescription)
            XCTAssertLessThan(Date().timeIntervalSince(start), 10)
        }
    }
}
