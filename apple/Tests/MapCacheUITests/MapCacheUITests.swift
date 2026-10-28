import XCTest

@MainActor final class MapCacheUITests: XCTestCase {
    func testItemizedCachePanelAndCancelPreserveLimits() {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "org.ncssar.RID2CaltopoApple")
        app.launchArguments = ["--map-cache-ui-qualification"]
        app.launch()
        let open = app.buttons["Open Map Cache Management"]
        XCTAssertTrue(open.waitForExistence(timeout: 20))
        func field(_ label: String) -> XCUIElement {
            let value = app.textFields[label]
            for _ in 0..<15 { if value.isHittable { return value }; app.swipeUp() }
            XCTFail("Missing field: \(label)"); return value
        }
        open.tap()
        XCTAssertTrue(app.staticTexts["Map imagery"].waitForExistence(timeout: 30))
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "cache-usage-bins"; attachment.lifetime = .keepAlways; add(attachment)
        for _ in 0..<5 { if app.staticTexts["Total local maps"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.staticTexts["Total local maps"].exists)
        let size = field("Max Cache Size (GB)")
        let originalSize = size.value as? String ?? ""
        XCTAssertFalse(originalSize.isEmpty)
        let age = field("Max Age (days)")
        let originalAge = age.value as? String ?? ""
        XCTAssertFalse(originalAge.isEmpty)
        age.tap(); age.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: originalAge.count) + "123")
        app.navigationBars.buttons["Cancel"].tap()
        XCTAssertTrue(open.waitForExistence(timeout: 5)); open.tap()
        XCTAssertEqual(field("Max Cache Size (GB)").value as? String, originalSize)
        XCTAssertEqual(field("Max Age (days)").value as? String, originalAge)
    }
}
