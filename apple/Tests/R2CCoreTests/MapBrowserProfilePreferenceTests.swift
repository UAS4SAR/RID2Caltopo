import XCTest
@testable import R2CCore

final class MapBrowserProfilePreferenceTests: XCTestCase {
    func testRememberedPersonalSurvivesRestartWhenCredentialIsAvailable() {
        let suite = "map-browser-profile.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = MapBrowserProfilePreference(suiteName: suite)
        store.remember(MapBrowserProfilePreference.personalProfileID)
        let reopened = MapBrowserProfilePreference(suiteName: suite)
        XCTAssertEqual(reopened.lastSelectedProfileID(), MapBrowserProfilePreference.personalProfileID)
        XCTAssertEqual(
            MapBrowserProfilePreference.resolveRestoredProfileID(
                lastSelectedProfileID: reopened.lastSelectedProfileID(),
                personalCredentialAvailable: true,
                defaultTeamProfileID: "home-default",
                knownTeamProfileIDs: ["home-default", "ma"]
            ),
            MapBrowserProfilePreference.personalProfileID
        )
    }

    func testMissingPersonalCredentialFallsBackToActiveTeamProfile() {
        XCTAssertEqual(
            MapBrowserProfilePreference.resolveRestoredProfileID(
                lastSelectedProfileID: MapBrowserProfilePreference.personalProfileID,
                personalCredentialAvailable: false,
                defaultTeamProfileID: "home-default",
                knownTeamProfileIDs: ["home-default"]
            ),
            "home-default"
        )
    }

    func testLegacyPersonalFlagMigratesIntoLastProfilePreference() {
        let suite = "map-browser-profile-legacy.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(true, forKey: MapBrowserProfilePreference.legacyUsesPersonalKey)
        let store = MapBrowserProfilePreference(suiteName: suite)
        XCTAssertEqual(store.lastSelectedProfileID(), MapBrowserProfilePreference.personalProfileID)
    }

    func testRememberedTeamProfileIsRestoredAndUnknownIdsFallBack() {
        XCTAssertEqual(
            MapBrowserProfilePreference.resolveRestoredProfileID(
                lastSelectedProfileID: "ma",
                personalCredentialAvailable: false,
                defaultTeamProfileID: "home-default",
                knownTeamProfileIDs: ["home-default", "ma"]
            ),
            "ma"
        )
        XCTAssertEqual(
            MapBrowserProfilePreference.resolveRestoredProfileID(
                lastSelectedProfileID: "retired",
                personalCredentialAvailable: true,
                defaultTeamProfileID: "home-default",
                knownTeamProfileIDs: ["home-default", "ma"]
            ),
            "home-default"
        )
    }

    func testActiveBrowseProfileReselectionIsNoOp() {
        XCTAssertTrue(
            MapBrowserProfilePreference.isActiveBrowseProfile(
                requestedProfileID: MapBrowserProfilePreference.personalProfileID,
                usesPersonalCredentials: true,
                activeTeamProfileID: "home"
            )
        )
        XCTAssertTrue(
            MapBrowserProfilePreference.isActiveBrowseProfile(
                requestedProfileID: "home",
                usesPersonalCredentials: false,
                activeTeamProfileID: "home"
            )
        )
        XCTAssertFalse(
            MapBrowserProfilePreference.isActiveBrowseProfile(
                requestedProfileID: MapBrowserProfilePreference.personalProfileID,
                usesPersonalCredentials: false,
                activeTeamProfileID: "home"
            )
        )
    }

    func testOnlyOpenMapRequiresDisconnectForBrowseProfileSwitch() {
        XCTAssertFalse(
            MapBrowserProfilePreference.shouldDisconnectMapForBrowseProfileSwitch(mapID: "")
        )
        XCTAssertFalse(
            MapBrowserProfilePreference.shouldDisconnectMapForBrowseProfileSwitch(mapID: "  ")
        )
        XCTAssertTrue(
            MapBrowserProfilePreference.shouldDisconnectMapForBrowseProfileSwitch(mapID: "4J0LF02")
        )
        XCTAssertTrue(
            MapBrowserProfilePreference.shouldKeepMapBrowserOpenAfterProfileDisconnect(
                browserPresented: true,
                disconnectCausedByProfileSwitch: true
            )
        )
        XCTAssertFalse(
            MapBrowserProfilePreference.shouldKeepMapBrowserOpenAfterProfileDisconnect(
                browserPresented: true,
                disconnectCausedByProfileSwitch: false
            )
        )
    }

    func testOpeningBrowserDoesNotReactivateAlreadySelectedPersonalProfile() {
        XCTAssertNil(
            MapBrowserProfilePreference.browseProfileToActivateOnOpen(
                rememberedProfileID: MapBrowserProfilePreference.personalProfileID,
                usesPersonalCredentials: true,
                activeTeamProfileID: "home",
                teamCredentialsPresent: true
            )
        )
        XCTAssertEqual(
            MapBrowserProfilePreference.browseProfileToActivateOnOpen(
                rememberedProfileID: MapBrowserProfilePreference.personalProfileID,
                usesPersonalCredentials: false,
                activeTeamProfileID: "home",
                teamCredentialsPresent: true
            ),
            MapBrowserProfilePreference.personalProfileID
        )
        XCTAssertNil(
            MapBrowserProfilePreference.browseProfileToActivateOnOpen(
                rememberedProfileID: "home",
                usesPersonalCredentials: false,
                activeTeamProfileID: "home",
                teamCredentialsPresent: true
            )
        )
    }
}
