import Foundation

/// Remembers the Map Browser credential profile across launches, including personal.
public struct MapBrowserProfilePreference {
    public static let personalProfileID = "personal"
    public static let storageKey = "caltopo.lastMapBrowserProfileId"
    /// Legacy Apple bool that already persisted a personal Map Browser choice.
    public static let legacyUsesPersonalKey = "caltopo.usesPersonalCredentials"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public init(suiteName: String) {
        self.defaults = UserDefaults(suiteName: suiteName) ?? .standard
    }

    public func lastSelectedProfileID() -> String {
        if let stored = defaults.string(forKey: Self.storageKey)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !stored.isEmpty {
            return stored
        }
        // Migrate the earlier personal-only flag so a prior personal choice is not lost.
        if defaults.bool(forKey: Self.legacyUsesPersonalKey) {
            return Self.personalProfileID
        }
        return ""
    }

    public func remember(_ profileID: String) {
        let trimmed = profileID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        defaults.set(trimmed, forKey: Self.storageKey)
        defaults.set(trimmed == Self.personalProfileID, forKey: Self.legacyUsesPersonalKey)
    }

    public func clear() {
        defaults.removeObject(forKey: Self.storageKey)
        defaults.set(false, forKey: Self.legacyUsesPersonalKey)
    }

    /// Personal restores only when a stored credential is available; otherwise the team default.
    public static func resolveRestoredProfileID(
        lastSelectedProfileID: String,
        personalCredentialAvailable: Bool,
        defaultTeamProfileID: String,
        knownTeamProfileIDs: [String] = []
    ) -> String {
        let last = lastSelectedProfileID.trimmingCharacters(in: .whitespacesAndNewlines)
        if last == personalProfileID {
            return personalCredentialAvailable ? personalProfileID : defaultTeamProfileID
        }
        if !last.isEmpty && (knownTeamProfileIDs.isEmpty || knownTeamProfileIDs.contains(last)) {
            return last
        }
        return defaultTeamProfileID
    }
}
