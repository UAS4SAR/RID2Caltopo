import Foundation
import XCTest
@testable import R2CCore

final class MapFolderVisibilityPreferencesTests: XCTestCase {
    private let selection = MapFolderVisibilitySelection(hiddenFolderIDs: ["tracks"], hiddenItemIDs: ["item"],
        folderVisibilityOverrides: ["tracks": false, "server-hidden": true])

    private func scope(_ domain: String = "caltopo.com", _ map: String = "Taylor-ID") -> MapFolderVisibilityScope {
        .init(domainAndPort: domain, mapID: map)
    }

    private func legacy() -> [String: Any] {
        ["hiddenFolders": ["tracks"], "hiddenItems": ["item"],
         "folderOverrides": ["tracks": false, "server-hidden": true]]
    }

    func testScopeNormalizesServerButPreservesPortsAndMapIdentity() {
        XCTAssertEqual(scope(" CALTOPO.COM ", " Taylor-ID "), scope())
        XCTAssertNotEqual(scope().storageKey, scope("caltopo.com:8443").storageKey)
        XCTAssertNotEqual(scope().storageKey, scope("sartopo.com").storageKey)
        XCTAssertNotEqual(scope().storageKey, scope("caltopo.com", "Taylor_ID").storageKey)
        XCTAssertNotEqual(scope().storageKey, scope("caltopo.com", "taylor-id").storageKey)
        XCTAssertNotEqual(scope("ab", "c").storageKey, scope("a", "bc").storageKey)
    }

    func testChoicesSurviveNewStoreAndStaySeparateAcrossServersAndPorts() async throws {
        let suite = "map-folder-test.\(UUID())", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = MapFolderVisibilityPreferences(suiteName: suite)
        store.save(selection, for: scope())
        let loaded = try await store.load(scope())
        XCTAssertEqual(loaded, selection)
        let otherServer = try await store.load(scope("sartopo.com"))
        let otherPort = try await store.load(scope("caltopo.com:8443"))
        XCTAssertEqual(otherServer, .init())
        XCTAssertEqual(otherPort, .init())
        let reopened = MapFolderVisibilityPreferences(suiteName: suite)
        let restored = try await reopened.load(scope())
        XCTAssertEqual(restored, selection)
    }

    func testLegacyChoicesMigrateOnlyToAnchoredServerEvenWhenAnotherServerLoadsFirst() async throws {
        let suite = "map-folder-test.\(UUID())", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(legacy(), forKey: "map.visibility.v2.Taylor-ID")
        let store = MapFolderVisibilityPreferences(suiteName: suite)
        store.prepareLegacyMigration(domainAndPort: "Field.Example:8443")
        let unrelated = try await store.load(scope())
        XCTAssertEqual(unrelated, .init())
        XCTAssertNotNil(defaults.dictionary(forKey: "map.visibility.v2.Taylor-ID"))
        let ownerScope = scope("field.example:8443")
        let migrated = try await store.load(ownerScope)
        XCTAssertEqual(migrated, selection)
        XCTAssertNotNil(defaults.data(forKey: ownerScope.storageKey))
        XCTAssertNil(defaults.object(forKey: "map.visibility.v2.Taylor-ID"))
        let newStore = MapFolderVisibilityPreferences(suiteName: suite)
        newStore.prepareLegacyMigration(domainAndPort: "caltopo.com")
        let otherAfterRestart = try await newStore.load(scope())
        XCTAssertEqual(otherAfterRestart, .init())
    }

    func testNewSelectionWinsOverLegacyAndLegacyCleanupPreservesCurrentKeys() async throws {
        let suite = "map-folder-test.\(UUID())", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = MapFolderVisibilityPreferences(suiteName: suite)
        let current = MapFolderVisibilitySelection(hiddenItemIDs: ["new-choice"])
        store.save(current, for: scope())
        _ = try await store.load(scope())
        defaults.set(legacy(), forKey: "map.visibility.v2.Taylor-ID")
        defaults.set(["old"], forKey: "map.visibility.Taylor-ID.items")
        store.prepareLegacyMigration(domainAndPort: "caltopo.com")
        let loaded = try await store.load(scope())
        XCTAssertEqual(loaded, current)
        XCTAssertNotNil(defaults.data(forKey: scope().storageKey))
        XCTAssertNil(defaults.object(forKey: "map.visibility.Taylor-ID.items"))
    }

    func testCorruptScopedPreferencesFailWithoutBeingErased() async throws {
        let suite = "map-folder-test.\(UUID())", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let original = Data("invalid-json".utf8)
        defaults.set(original, forKey: scope().storageKey)
        let store = MapFolderVisibilityPreferences(suiteName: suite)
        do { _ = try await store.load(scope()); XCTFail("A corrupt selection must not be accepted") }
        catch { XCTAssertEqual(defaults.data(forKey: scope().storageKey), original) }
    }

    func testCorruptLegacyPreferencesArePreservedInsteadOfMigratedAsEmpty() async throws {
        let suite = "map-folder-test.\(UUID())", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(["hiddenFolders": "invalid"], forKey: "map.visibility.v2.Taylor-ID")
        let store = MapFolderVisibilityPreferences(suiteName: suite)
        store.prepareLegacyMigration(domainAndPort: "caltopo.com")
        do { _ = try await store.load(scope()); XCTFail("A corrupt legacy selection must not be discarded") }
        catch {
            XCTAssertNotNil(defaults.dictionary(forKey: "map.visibility.v2.Taylor-ID"))
            XCTAssertNil(defaults.data(forKey: scope().storageKey))
        }
    }

    func testRapidSavesKeepLatestChoiceAfterCallerTaskEnds() async throws {
        let suite = "map-folder-test.\(UUID())", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = MapFolderVisibilityPreferences(suiteName: suite)
        let destination = scope()
        await Task.detached {
            for index in 0..<1000 {
                store.save(.init(hiddenItemIDs: ["item-\(index)"]), for: destination)
            }
        }.value
        let latest = try await store.load(destination)
        XCTAssertEqual(latest.hiddenItemIDs, ["item-999"])
    }

    func testUnassignedMapDoesNotCreatePreferenceEntry() async throws {
        let suite = "map-folder-test.\(UUID())", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = MapFolderVisibilityPreferences(suiteName: suite)
        let unassigned = scope("caltopo.com", "")
        store.save(selection, for: unassigned)
        let loaded = try await store.load(unassigned)
        XCTAssertEqual(loaded, .init())
        XCTAssertNil(defaults.object(forKey: unassigned.storageKey))
    }
}
