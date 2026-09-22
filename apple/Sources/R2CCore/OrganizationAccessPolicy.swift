import Foundation

public enum OrganizationAccessPolicy {
    public static func requiresDeviceOwnerAuthentication(
        organizationName: String,
        trackerURLPrefix: String = "",
        trackerAPIKey: String = "",
        caltopoTeamID: String = "",
        caltopoCredentialID: String = "",
        caltopoCredentialSecret: String = ""
    ) -> Bool {
        let organizationConfigured = !organizationName
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let trackerOrganizationConfigured = [trackerURLPrefix, trackerAPIKey]
            .allSatisfy {
                !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
        let teamsAccountConfigured = [
            caltopoTeamID,
            caltopoCredentialID,
            caltopoCredentialSecret,
        ].allSatisfy {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return organizationConfigured ||
            trackerOrganizationConfigured ||
            teamsAccountConfigured
    }

    public static func authenticatedSessionRemainsValid(
        accessWasGranted: Bool,
        backgroundedAt: Date?,
        resumedAt: Date
    ) -> Bool {
        // The operating system protects an unlocked device from access by a non-owner.
        // Keep the app session across ordinary backgrounding; the app clears access when
        // iOS reports that protected data became unavailable (device lock), and a fresh
        // process still starts locked.
        accessWasGranted
    }
}
