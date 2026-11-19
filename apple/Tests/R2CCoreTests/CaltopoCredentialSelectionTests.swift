import XCTest
@testable import R2CCore

/// Ken, 2026-11-19: App Store 2.5.7 on the Mac mini selected "Team: No Teams credentials" with no
/// Teams credentials configured. Swift copy of Android's CaltopoCredentialSelectionTest; same fixture.
final class CaltopoCredentialSelectionTests: XCTestCase {
    private func opt(_ v: String) -> String { v == "-" ? "" : v }

    func testSharedFixtureMatchesAndroid() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("test-fixtures/caltopo/credential-selection.tsv")
        let rows = try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }.map { $0.components(separatedBy: "\t") }
        XCTAssertEqual(rows.count, 10)
        for r in rows {
            let teamUsable = MapBrowserProfilePreference.teamUsable(teamCredentialsPresent: r[4] == "true", teamAccessRefused: r[5] == "true")
            let got = MapBrowserProfilePreference.resolveOpenProfileID(
                lastSelectedProfileID: opt(r[0]),
                personalCredentialAvailable: r[1] == "true",
                defaultTeamProfileID: opt(r[2]),
                knownTeamProfileIDs: opt(r[3]).split(separator: ",").map(String.init),
                teamUsable: teamUsable)
            XCTAssertEqual(got, r[6], r[7])
        }
    }

    func testTeamUsableNeedsCredentialsAndTrackerConsent() {
        XCTAssertFalse(MapBrowserProfilePreference.teamUsable(teamCredentialsPresent: false, teamAccessRefused: false))
        XCTAssertFalse(MapBrowserProfilePreference.teamUsable(teamCredentialsPresent: true, teamAccessRefused: true))
        XCTAssertTrue(MapBrowserProfilePreference.teamUsable(teamCredentialsPresent: true, teamAccessRefused: false))
    }

    func testPersonalIsTheInitialSelectionWithoutTeamsCredentials() {
        XCTAssertTrue(MapBrowserProfilePreference.initialUsesPersonal(stored: false, teamCredentialsPresent: false))
        XCTAssertTrue(MapBrowserProfilePreference.initialUsesPersonal(stored: true, teamCredentialsPresent: true))
        XCTAssertFalse(MapBrowserProfilePreference.initialUsesPersonal(stored: false, teamCredentialsPresent: true))
    }

    func testOpenProfileIsNeverEmpty() {
        XCTAssertEqual(MapBrowserProfilePreference.resolveOpenProfileID(
            lastSelectedProfileID: "", personalCredentialAvailable: false, defaultTeamProfileID: "", teamUsable: true),
            MapBrowserProfilePreference.personalProfileID)
    }
}
