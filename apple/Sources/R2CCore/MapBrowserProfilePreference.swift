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

    /// Selecting the already-active Map Browser profile is a session no-op.
    public static func isActiveBrowseProfile(
        requestedProfileID: String,
        usesPersonalCredentials: Bool,
        activeTeamProfileID: String
    ) -> Bool {
        let requested = requestedProfileID.trimmingCharacters(in: .whitespacesAndNewlines)
        let current = usesPersonalCredentials ? personalProfileID : activeTeamProfileID
        return !requested.isEmpty && requested == current
    }

    /// Only an open incident map needs disconnect when switching browse credentials.
    public static func shouldDisconnectMapForBrowseProfileSwitch(mapID: String) -> Bool {
        !mapID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Internal profile-switch disconnect must keep Map Browser presented.
    public static func shouldKeepMapBrowserOpenAfterProfileDisconnect(
        browserPresented: Bool,
        disconnectCausedByProfileSwitch: Bool
    ) -> Bool {
        browserPresented && disconnectCausedByProfileSwitch
    }

    /// Opening Map Browser should restore the remembered profile without treating it as a switch
    /// when that profile is already active.
    public static func browseProfileToActivateOnOpen(
        rememberedProfileID: String,
        usesPersonalCredentials: Bool,
        activeTeamProfileID: String,
        teamCredentialsPresent: Bool
    ) -> String? {
        let remembered = rememberedProfileID.trimmingCharacters(in: .whitespacesAndNewlines)
        if usesPersonalCredentials ||
            remembered == personalProfileID ||
            !teamCredentialsPresent {
            let current = usesPersonalCredentials ? personalProfileID : activeTeamProfileID
            if current == personalProfileID {
                return nil // already personal; just present the browser
            }
            return personalProfileID
        }
        return nil // team credentials; present browser on the active team profile
    }
}
