import XCTest
@testable import R2CMapping

final class PilotAppRegistrationTests: XCTestCase {
    private func fixture() throws -> Data {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return try Data(contentsOf: root.appendingPathComponent("tools/mapping/tests/fixtures/dji-app-registration.json"))
    }
    func testSameAppRegistrationRequiresNoEnrollmentOrSavedKeychainItem() throws {
        for _ in 0..<2 {
            let value = try PilotAppRegistration.decode(fixture())
            XCTAssertEqual(value.appId, "123456")
            XCTAssertEqual(value.appKey, "synthetic-app-key-1234567890")
            XCTAssertEqual(value.appLicense, String(repeating: "synthetic-license-not-for-flight-", count: 3))
            let roundTrip = try PilotAppRegistration.decode(PilotAppRegistration.encode(value))
            XCTAssertEqual(roundTrip.appLicense, value.appLicense)
        }
    }
    func testRejectsCorruptMissingAndDeviceBoundRegistration() throws {
        for text in ["", "{}", "{\"type\":\"r2c_dji_device_provisioning\",\"version\":2}"] {
            XCTAssertThrowsError(try PilotAppRegistration.decode(Data(text.utf8)))
        }
        for (key, value): (String, Any) in [("sha256", String(repeating: "0", count: 64)), ("enc", "invalid"), ("version", true)] {
            var object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture()) as? [String: Any])
            object[key] = value
            XCTAssertThrowsError(try PilotAppRegistration.decode(JSONSerialization.data(withJSONObject: object)))
        }
    }
    func testBootstrapsSeparateFreshStoresAndDoesNotAcknowledgeFailedSave() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        for index in 0..<2 {
            var stored: PilotCloudCredentials?, writes = 0
            let receipt = folder.appendingPathComponent("receipt-\(index)")
            let bytes = try fixture()
            let save: (PilotCloudCredentials) -> Void = { stored = $0; writes += 1 }
            try PilotAppRegistration.install(bytes, receipt: receipt, load: { stored }, save: save)
            XCTAssertEqual(stored?.appId, "123456")
            try PilotAppRegistration.install(bytes, receipt: receipt, load: { stored }, save: save)
            XCTAssertEqual(writes, 1)
            stored = nil
            try PilotAppRegistration.install(bytes, receipt: receipt, load: { stored }, save: save)
            XCTAssertEqual(writes, 2)
        }
        let failed = folder.appendingPathComponent("failed")
        XCTAssertThrowsError(try PilotAppRegistration.install(fixture(), receipt: failed, load: { nil }, save: { _ in throw NSError(domain: "Synthetic locked store", code: 1) }))
        XCTAssertFalse(FileManager.default.fileExists(atPath: failed.path))
    }

}
