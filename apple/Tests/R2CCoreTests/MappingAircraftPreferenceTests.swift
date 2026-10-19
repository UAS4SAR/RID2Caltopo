import XCTest
@testable import R2CCore

final class MappingAircraftPreferenceTests:XCTestCase {
    func testReopenedPlannerRetainsSelectionAndDelayedWritesCannotRestoreAnOlderAircraft() async {
        let suite="r2c-aircraft-preference-"+UUID().uuidString
        defer{UserDefaults(suiteName:suite)?.removePersistentDomain(forName:suite)}
        let first=MappingAircraftPreference(suiteName:suite)
        let initial=await first.load();XCTAssertEqual(initial,"DJI Matrice 4TD")
        await first.save("DJI Matrice 4T",revision:2)
        await first.save("DJI Matrice 30",revision:1)
        let reopened=MappingAircraftPreference(suiteName:suite)
        let selected=await reopened.load();XCTAssertEqual(selected,"DJI Matrice 4T")
        await reopened.save("DJI Matrice 30T",revision:3)
        let another=MappingAircraftPreference(suiteName:suite)
        let m30=await another.load();XCTAssertEqual(m30,"DJI Matrice 30T")
        await another.save("DJI Matrice 400",revision:4)
        let unsupported=await MappingAircraftPreference(suiteName:suite).load()
        XCTAssertEqual(unsupported,"DJI Matrice 400")
        await another.save("retired model",revision:5)
        let fallback=await MappingAircraftPreference(suiteName:suite).load()
        XCTAssertEqual(fallback,"DJI Matrice 4TD")
    }
}
