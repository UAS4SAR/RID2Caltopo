import Foundation
import R2CCore
import Security
import SwiftUI
import UniformTypeIdentifiers
import Vision
import VisionKit

struct AppleStoredOperationalProfile: Codable {
    let profileID: String
    let displayName: String
    let profileType: String
    let enabled: Bool
    let organizationName: String
    let incident: String
    let operationalPeriod: String
    let trackFolder: String
    let teamID: String
    let trackerURLPrefix: String
    let trackerAPIKey: String
    let domainAndPort: String
    let mapID: String
    let mapTitle: String
    let credentialID: String
    let credentialSecret: String
    let autoConnect: Bool
    let expiresAtEpochMilliseconds: Int64
    let quietRemoveOnExpiry: Bool
    let connectKey: String?
}

struct AppleOperationalProfileOption: Identifiable, Equatable {
    let id: String
    let credentialLabel: String
    let description: String
    let expiresAt: Date?
}

@MainActor
final class AppleCaltopoProfileLifecycle: ObservableObject {
    static let shared = AppleCaltopoProfileLifecycle()
    @Published private(set) var activeProfileID: String
    @Published private(set) var mutualAidDisplayName: String?
    @Published private(set) var mutualAidExpiresAt: Date?
    @Published private(set) var availableProfiles: [AppleOperationalProfileOption] = []

    private let defaults: UserDefaults
    private static let service = "org.ncssar.RID2CaltopoApple.caltopo-profiles"
    private static let homeAccount = "profile.home"
    private static let mutualAidAccount = "profile.mutual-aid"
    private static let activeKey = "caltopoProfiles.activeID"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        activeProfileID = defaults.string(forKey: Self.activeKey) ?? ""
        if let profile = try? Self.load(account: Self.mutualAidAccount) {
            mutualAidDisplayName = profile.displayName
            mutualAidExpiresAt = profile.expiresAtEpochMilliseconds > 0
                ? Date(timeIntervalSince1970: Double(profile.expiresAtEpochMilliseconds) / 1_000)
                : nil
        }
        refreshPublishedProfiles()
    }

    var activeCredentialLabel: String {
        availableProfiles.first(where: { $0.id == activeProfileID })?.credentialLabel
            ?? "No Teams credentials"
    }

    func captureHome(
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings
    ) throws {
        let configuration = caltopo.configuration
        let profile = AppleStoredOperationalProfile(
            profileID: "home",
            displayName: org.organizationName.isEmpty ? "Home" : org.organizationName,
            profileType: "HOME",
            enabled: configuration.enabled,
            organizationName: org.organizationName,
            incident: org.incident,
            operationalPeriod: org.operationalPeriod,
            trackFolder: org.trackFolder,
            teamID: configuration.teamID.isEmpty ? org.teamID : configuration.teamID,
            trackerURLPrefix: org.trackerURLPrefix,
            trackerAPIKey: org.trackerAPIKey,
            domainAndPort: configuration.domainAndPort,
            mapID: "",
            mapTitle: "",
            credentialID: configuration.credentialID,
            credentialSecret: configuration.credentialSecret,
            autoConnect: false,
            expiresAtEpochMilliseconds: 0,
            quietRemoveOnExpiry: false,
            connectKey: configuration.connectKey
        )
        try Self.store(profile, account: Self.homeAccount)
        setActive("home")
        refreshPublishedProfiles()
    }

    func install(
        _ profile: MutualAidSharedProfile,
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings
    ) throws -> Bool {
        if (try? Self.load(account: Self.homeAccount)) == nil {
            try captureHome(org: org, caltopo: caltopo)
        }
        let stored = AppleStoredOperationalProfile(
            profileID: profile.profileID,
            displayName: profile.displayName,
            profileType: "MUTUAL_AID",
            enabled: true,
            organizationName: profile.sourceLabel,
            incident: profile.incident,
            operationalPeriod: profile.operationalPeriod,
            trackFolder: profile.trackFolder,
            teamID: profile.teamID,
            trackerURLPrefix: profile.trackerURLPrefix,
            trackerAPIKey: profile.trackerAPIKey,
            domainAndPort: profile.domainAndPort,
            mapID: profile.targetMapID,
            mapTitle: profile.targetMapTitle.isEmpty ? profile.displayName : profile.targetMapTitle,
            credentialID: profile.credentialID,
            credentialSecret: profile.credentialSecret,
            autoConnect: profile.autoConnect,
            expiresAtEpochMilliseconds: profile.expiresAtEpochMilliseconds,
            quietRemoveOnExpiry: profile.quietRemoveOnExpiry,
            connectKey: profile.connectKey
        )
        try Self.store(stored, account: Self.mutualAidAccount)
        mutualAidDisplayName = stored.displayName
        mutualAidExpiresAt = stored.expiresAtEpochMilliseconds > 0
            ? Date(timeIntervalSince1970: Double(stored.expiresAtEpochMilliseconds) / 1_000)
            : nil
        let unexpired = stored.expiresAtEpochMilliseconds <= 0
            || Int64(Date().timeIntervalSince1970 * 1_000) < stored.expiresAtEpochMilliseconds
        if unexpired {
            setActive(stored.profileID)
        }
        refreshPublishedProfiles()
        return unexpired
    }

    @discardableResult
    func activate(
        profileID: String,
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings,
        now: Date = Date()
    ) throws -> Bool {
        let account = profileID == "home" ? Self.homeAccount : Self.mutualAidAccount
        let profile = try Self.load(account: account)
        guard profile.profileID == profileID else { return false }
        if profile.expiresAtEpochMilliseconds > 0,
           Int64(now.timeIntervalSince1970 * 1_000) >= profile.expiresAtEpochMilliseconds {
            _ = try removeExpired(org: org, caltopo: caltopo, now: now)
            return false
        }
        try org.apply(storedProfile: profile)
        try caltopo.apply(
            storedProfile: profile,
            connectMap: profile.profileType == "MUTUAL_AID"
        )
        setActive(profile.profileID)
        refreshPublishedProfiles(now: now)
        AppleLog.info(
            "OrgConfig",
            "Activated operational profile id='\(profile.profileID)' type='\(profile.profileType)'"
        )
        return true
    }

    @discardableResult
    func restoreActive(
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings,
        now: Date = Date()
    ) throws -> Bool {
        guard !activeProfileID.isEmpty, activeProfileID != "home",
              let mutualAid = try? Self.load(account: Self.mutualAidAccount),
              mutualAid.profileID == activeProfileID
        else { return false }
        if mutualAid.expiresAtEpochMilliseconds > 0,
           Int64(now.timeIntervalSince1970 * 1_000) >= mutualAid.expiresAtEpochMilliseconds {
            return try removeExpired(org: org, caltopo: caltopo, now: now)
        }
        try org.apply(storedProfile: mutualAid)
        try caltopo.apply(storedProfile: mutualAid, connectMap: true)
        return true
    }

    @discardableResult
    func removeExpired(
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings,
        now: Date = Date()
    ) throws -> Bool {
        guard let mutualAid = try? Self.load(account: Self.mutualAidAccount),
              mutualAid.expiresAtEpochMilliseconds > 0,
              Int64(now.timeIntervalSince1970 * 1_000) >= mutualAid.expiresAtEpochMilliseconds
        else { return false }
        try Self.delete(account: Self.mutualAidAccount)
        mutualAidDisplayName = nil
        mutualAidExpiresAt = nil
        if activeProfileID == mutualAid.profileID,
           let home = try? Self.load(account: Self.homeAccount) {
            try org.apply(storedProfile: home)
            try caltopo.apply(storedProfile: home, connectMap: false)
            setActive(home.profileID)
        }
        refreshPublishedProfiles(now: now)
        AppleLog.info(
            "OrgConfig",
            "Removed expired mutual-aid profile id='\(mutualAid.profileID)' quiet=\(mutualAid.quietRemoveOnExpiry)"
        )
        return true
    }

    private func setActive(_ profileID: String) {
        activeProfileID = profileID
        defaults.set(profileID, forKey: Self.activeKey)
    }

    private func refreshPublishedProfiles(now: Date = Date()) {
        var options: [AppleOperationalProfileOption] = []
        if let home = try? Self.load(account: Self.homeAccount) {
            options.append(Self.option(for: home))
        }
        if let mutualAid = try? Self.load(account: Self.mutualAidAccount),
           mutualAid.expiresAtEpochMilliseconds <= 0
            || Int64(now.timeIntervalSince1970 * 1_000) < mutualAid.expiresAtEpochMilliseconds {
            options.append(Self.option(for: mutualAid))
        }
        availableProfiles = options
    }

    private static func option(for profile: AppleStoredOperationalProfile) -> AppleOperationalProfileOption {
        let fallback = profile.profileType == "HOME" ? "Home" : "Mutual Aid"
        let base = [profile.organizationName, profile.displayName]
            .first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? fallback
        let label = profile.profileType == "MUTUAL_AID" && !base.uppercased().hasSuffix("-MA")
            ? "\(base)-MA"
            : base
        let expiry = profile.expiresAtEpochMilliseconds > 0
            ? Date(timeIntervalSince1970: Double(profile.expiresAtEpochMilliseconds) / 1_000)
            : nil
        return AppleOperationalProfileOption(
            id: profile.profileID,
            credentialLabel: label,
            description: profile.profileType == "MUTUAL_AID" ? "Mutual Aid" : "Home organization",
            expiresAt: expiry
        )
    }

    private static func load(account: String) throws -> AppleStoredOperationalProfile {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return try JSONDecoder().decode(AppleStoredOperationalProfile.self, from: data)
    }

    private static func store(_ profile: AppleStoredOperationalProfile, account: String) throws {
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let data = try JSONEncoder().encode(profile)
        let update = SecItemUpdate(key as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(update))
        }
        var insert = key
        insert[kSecValueData as String] = data
        let status = SecItemAdd(insert as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }

    private static func delete(account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }
}

@MainActor
final class AppleOrgConfigSettings: ObservableObject {
    @Published private(set) var organizationName: String
    @Published private(set) var incident: String
    @Published private(set) var operationalPeriod: String
    @Published private(set) var trackFolder: String
    @Published private(set) var teamID: String
    @Published private(set) var trackerURLPrefix: String
    @Published private(set) var faaProxyURL: String
    @Published private(set) var trackerEnrollmentURL: String
    @Published private(set) var usePeers: Bool
    @Published private(set) var standaloneR2CCoordinationEnabled: Bool
    @Published private(set) var predictiveHeadEnabled: Bool
    @Published private(set) var proximityAlertSpacingFeet: Int
    @Published private(set) var minimumTrackDistanceFeet: Int
    @Published private(set) var newTrackDelaySeconds: Int
    @Published private(set) var bridgeCheckDistanceFeet: Int
    @Published private(set) var maximumIdleMinutes: Int
    @Published private(set) var sourceDescription: String

    private let defaults: UserDefaults
    private static let keychainService = "org.ncssar.RID2CaltopoApple.org-config"
    private static let trackerAccount = "tracker-api-key"
    private static let managedStandaloneMigrationKey =
        "org.managedTrackerStandaloneMigration.v1"
    private static let idleTimeoutMigrationKey = "app.maximumIdleMinutesMigration.v1"
    private static let defaultMaximumIdleMinutes = 30
    private static let legacyDefaultMaximumIdleMinutes = 120
    private static let faaClientIDAccount = "faa-client-id"
    private static let faaClientSecretAccount = "faa-client-secret"
    private static let mutualAidCredentialIDAccount = "mutual-aid-credential-id"
    private static let mutualAidCredentialSecretAccount = "mutual-aid-credential-secret"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        organizationName = defaults.string(forKey: "org.name") ?? ""
        // Incident and operational period are session choices on Android.
        // Begin every process with Android's defaults instead of restoring the
        // previous operational session.
        incident = "Training"
        operationalPeriod = "1"
        trackFolder = Self.normalizedTrackFolder(defaults.string(forKey: "org.trackFolder"))
        teamID = defaults.string(forKey: "org.teamID") ?? ""
        trackerURLPrefix = defaults.string(forKey: "org.trackerURLPrefix") ?? ""
        faaProxyURL = defaults.string(forKey: "org.faaProxyURL") ?? ""
        trackerEnrollmentURL = defaults.string(forKey: "org.trackerEnrollmentURL") ?? ""
        usePeers = defaults.object(forKey: "org.usePeers") as? Bool ?? true
        standaloneR2CCoordinationEnabled = false
        predictiveHeadEnabled = defaults.object(forKey: "org.predictiveHead") as? Bool ?? true
        proximityAlertSpacingFeet = defaults.object(forKey: "org.proximityFeet") as? Int ?? 40
        minimumTrackDistanceFeet = max(2, defaults.object(forKey: "track.minimumDistanceFeet") as? Int ?? 2)
        newTrackDelaySeconds = max(1, defaults.object(forKey: "track.newTrackDelaySeconds") as? Int ?? 30)
        bridgeCheckDistanceFeet = max(1, defaults.object(forKey: "track.bridgeCheckDistanceFeet") as? Int ?? 20)
        let storedMaximumIdleMinutes = defaults.object(forKey: "app.maximumIdleMinutes") as? Int
        let shouldMigrateIdleTimeout =
            !defaults.bool(forKey: Self.idleTimeoutMigrationKey) &&
            storedMaximumIdleMinutes == Self.legacyDefaultMaximumIdleMinutes
        if shouldMigrateIdleTimeout {
            maximumIdleMinutes = Self.defaultMaximumIdleMinutes
        } else {
            maximumIdleMinutes = max(
                0,
                storedMaximumIdleMinutes ?? Self.defaultMaximumIdleMinutes
            )
        }
        sourceDescription = defaults.string(forKey: "org.sourceDescription") ?? "Not loaded"
        if shouldMigrateIdleTimeout {
            defaults.set(Self.defaultMaximumIdleMinutes, forKey: "app.maximumIdleMinutes")
        }
        defaults.set(true, forKey: Self.idleTimeoutMigrationKey)
        if sourceDescription == "Managed r2c-tracker enrollment" {
            let scopedPrefix = TrackerCoordinationEndpoint.organizationScopedPrefix(
                from: trackerURLPrefix,
                organization: organizationName
            )
            if scopedPrefix != trackerURLPrefix {
                trackerURLPrefix = scopedPrefix
                defaults.set(scopedPrefix, forKey: "org.trackerURLPrefix")
                AppleLog.info("TrackerPeer", "Migrated managed tracker prefix to organization scope")
            }
        }
        if sourceDescription == "Managed r2c-tracker enrollment",
           !defaults.bool(forKey: Self.managedStandaloneMigrationKey) {
            usePeers = true
            standaloneR2CCoordinationEnabled = false
            defaults.set(true, forKey: "org.usePeers")
            defaults.set(false, forKey: "org.standaloneR2CCoordination")
            defaults.set(true, forKey: Self.managedStandaloneMigrationKey)
        }
        defaults.set("Training", forKey: "org.incident")
        defaults.set("1", forKey: "org.operationalPeriod")
    }

    var trackerAPIKey: String { Self.loadTrackerAPIKey() ?? "" }

    var hasManagedTrackerEnrollment: Bool {
        sourceDescription == "Managed r2c-tracker enrollment" &&
            !trackerURLPrefix.isEmpty &&
            !trackerAPIKey.isEmpty &&
            !faaProxyURL.isEmpty
    }

    var hasNotamAdminConfiguration: Bool {
        !trackerURLPrefix.isEmpty && !trackerAPIKey.isEmpty && !faaProxyURL.isEmpty
    }

    func setOrganizationNameForRidMappings(_ organization: String) {
        organizationName = organization.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(organizationName, forKey: "org.name")
    }

    func setTrackFolder(_ value: String) {
        trackFolder = Self.normalizedTrackFolder(value)
        defaults.set(trackFolder, forKey: "org.trackFolder")
    }

    func applyManagedOrganization(
        organizationName: String,
        trackFolder: String,
        mutualAidTemplate: MutualAidTemplateCredentials?
    ) throws {
        self.organizationName = organizationName.trimmingCharacters(in: .whitespacesAndNewlines)
        self.trackFolder = Self.normalizedTrackFolder(trackFolder)
        defaults.set(self.organizationName, forKey: "org.name")
        defaults.set(self.trackFolder, forKey: "org.trackFolder")
        if let mutualAidTemplate {
            try apply(mutualAidTemplate: mutualAidTemplate)
        } else {
            for key in [
                "mutualAid.template.teamID", "mutualAid.template.domainAndPort",
                "mutualAid.template.sourceLabel", "mutualAid.template.targetFolderHint",
                "mutualAid.template.connectKey",
            ] {
                defaults.removeObject(forKey: key)
            }
            try Self.storeSecret("", account: Self.mutualAidCredentialIDAccount)
            try Self.storeSecret("", account: Self.mutualAidCredentialSecretAccount)
        }
        objectWillChange.send()
    }

    func applyManualTrackerConfiguration(
        trackerURLPrefix: String,
        trackerAPIKey: String
    ) throws {
        self.trackerURLPrefix = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        faaProxyURL = ""
        trackerEnrollmentURL = ""
        sourceDescription = "Manual Settings"
        defaults.set(self.trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.removeObject(forKey: "org.faaProxyURL")
        defaults.removeObject(forKey: "org.trackerEnrollmentURL")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        try Self.storeTrackerAPIKey(trackerAPIKey.trimmingCharacters(in: .whitespacesAndNewlines))
        objectWillChange.send()
    }

    func applyTrackerEnrollment(
        organization: String,
        trackerURLPrefix: String,
        trackerAPIKey: String,
        faaProxyURL: String,
        enrollmentURL: String
    ) throws {
        organizationName = organization.trimmingCharacters(in: .whitespacesAndNewlines)
        self.trackerURLPrefix = TrackerCoordinationEndpoint.organizationScopedPrefix(
            from: trackerURLPrefix,
            organization: organizationName
        )
        self.faaProxyURL = faaProxyURL.trimmingCharacters(in: .whitespacesAndNewlines)
        trackerEnrollmentURL = enrollmentURL.trimmingCharacters(in: .whitespacesAndNewlines)
        usePeers = true
        standaloneR2CCoordinationEnabled = false
        sourceDescription = "Managed r2c-tracker enrollment"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(self.trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(self.faaProxyURL, forKey: "org.faaProxyURL")
        defaults.set(trackerEnrollmentURL, forKey: "org.trackerEnrollmentURL")
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(
            standaloneR2CCoordinationEnabled,
            forKey: "org.standaloneR2CCoordination"
        )
        defaults.set(true, forKey: Self.managedStandaloneMigrationKey)
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        try Self.storeTrackerAPIKey(trackerAPIKey)
    }

    var faaConfiguration: FaaSharedConfig? {
        let clientID = Self.loadSecret(account: Self.faaClientIDAccount) ?? ""
        let clientSecret = Self.loadSecret(account: Self.faaClientSecretAccount) ?? ""
        guard !clientID.isEmpty, !clientSecret.isEmpty else { return nil }
        return FaaSharedConfig(
            sourceLabel: defaults.string(forKey: "faa.sourceLabel") ?? "FAA NOTAM credentials",
            apiBaseURL: defaults.string(forKey: "faa.apiBaseURL") ?? "",
            tokenURL: defaults.string(forKey: "faa.tokenURL") ?? "",
            clientID: clientID,
            clientSecret: clientSecret,
            scope: defaults.string(forKey: "faa.scope") ?? ""
        )
    }

    var mutualAidTemplate: MutualAidTemplateCredentials? {
        let credentialID = Self.loadSecret(account: Self.mutualAidCredentialIDAccount) ?? ""
        let credentialSecret = Self.loadSecret(account: Self.mutualAidCredentialSecretAccount) ?? ""
        guard !credentialID.isEmpty, !credentialSecret.isEmpty else { return nil }
        return MutualAidTemplateCredentials(
            teamID: defaults.string(forKey: "mutualAid.template.teamID") ?? "",
            credentialID: credentialID,
            credentialSecret: credentialSecret,
            domainAndPort: defaults.string(forKey: "mutualAid.template.domainAndPort") ?? "caltopo.com",
            sourceLabel: defaults.string(forKey: "mutualAid.template.sourceLabel") ?? organizationName,
            targetFolderHint: defaults.string(forKey: "mutualAid.template.targetFolderHint") ?? "MAI",
            connectKey: defaults.string(forKey: "mutualAid.template.connectKey") ?? ""
        )
    }

    func apply(bundle: OrgConfigBundle, normalizedToken: String) throws {
        let credentials = bundle.credentials
        organizationName = credentials?.organizationName.isEmpty == false
            ? credentials?.organizationName ?? bundle.organizationName
            : bundle.organizationName
        incident = credentials?.incident ?? ""
        operationalPeriod = credentials?.operationalPeriod ?? ""
        trackFolder = Self.normalizedTrackFolder(credentials?.trackFolder)
        teamID = credentials?.teamID ?? ""
        trackerURLPrefix = ""
        faaProxyURL = ""
        trackerEnrollmentURL = ""
        usePeers = credentials?.usePeers ?? true
        predictiveHeadEnabled = credentials?.predictiveHeadEnabled ?? true
        proximityAlertSpacingFeet = credentials?.proximityAlertSpacingFeet ?? 40
        sourceDescription = "R2C2 • \(organizationName.isEmpty ? "Unnamed org" : organizationName)"

        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.removeObject(forKey: "org.trackerEnrollmentURL")
        if faaProxyURL.isEmpty {
            defaults.removeObject(forKey: "org.faaProxyURL")
        } else {
            defaults.set(faaProxyURL, forKey: "org.faaProxyURL")
        }
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(standaloneR2CCoordinationEnabled, forKey: "org.standaloneR2CCoordination")
        defaults.set(predictiveHeadEnabled, forKey: "org.predictiveHead")
        defaults.set(proximityAlertSpacingFeet, forKey: "org.proximityFeet")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        defaults.set(normalizedToken, forKey: "org.joinToken")
        try Self.storeTrackerAPIKey("")
    }

    private static func resolvedFaaProxyURL(
        explicit: String,
        trackerURLPrefix: String
    ) -> String {
        let trimmed = explicit.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        guard var tracker = URLComponents(
            string: trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        ),
        tracker.scheme?.lowercased() == "https",
        let host = tracker.host?.lowercased(),
        host == "r2c-tracker.com" || host.hasSuffix(".r2c-tracker.com")
        else { return "" }
        tracker.path = "/faa/notams"
        tracker.query = nil
        tracker.fragment = nil
        return tracker.url?.absoluteString ?? ""
    }

    func apply(mutualAid profile: MutualAidSharedProfile, normalizedToken: String) throws {
        organizationName = profile.sourceLabel
        incident = profile.incident
        operationalPeriod = profile.operationalPeriod
        trackFolder = Self.normalizedTrackFolder(profile.trackFolder)
        teamID = profile.teamID
        trackerURLPrefix = profile.trackerURLPrefix
        usePeers = true
        predictiveHeadEnabled = true
        sourceDescription = "Android MA QR • \(profile.displayName)"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(predictiveHeadEnabled, forKey: "org.predictiveHead")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        defaults.set(normalizedToken, forKey: "org.mutualAidToken")
        defaults.set(profile.expiresAtEpochMilliseconds, forKey: "org.mutualAidExpiresAt")
        try Self.storeSecret(profile.trackerAPIKey, account: Self.trackerAccount)
    }

    func apply(storedProfile profile: AppleStoredOperationalProfile) throws {
        organizationName = profile.organizationName
        incident = profile.incident
        operationalPeriod = profile.operationalPeriod
        trackFolder = Self.normalizedTrackFolder(profile.trackFolder)
        teamID = profile.teamID
        trackerURLPrefix = profile.trackerURLPrefix
        sourceDescription = profile.profileType == "HOME"
            ? "Home organization • \(profile.displayName)"
            : "Mutual aid • \(profile.displayName)"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        try Self.storeTrackerAPIKey(profile.trackerAPIKey)
    }

    func setPredictiveHeadEnabled(_ enabled: Bool) {
        predictiveHeadEnabled = enabled
        defaults.set(enabled, forKey: "org.predictiveHead")
    }

    func setUsePeers(_ enabled: Bool) {
        usePeers = enabled
        defaults.set(enabled, forKey: "org.usePeers")
        AppleLog.info("TrackerPeer", "Peer coordination setting enabled=\(enabled)")
    }

    func setStandaloneR2CCoordinationEnabled(_ enabled: Bool) {
        standaloneR2CCoordinationEnabled = false
        defaults.set(false, forKey: "org.standaloneR2CCoordination")
        AppleLog.info("TrackerPeer", "Standalone flights remain independent")
    }

    func setProximityAlertSpacingFeet(_ value: Int) {
        proximityAlertSpacingFeet = max(1, value)
        defaults.set(proximityAlertSpacingFeet, forKey: "org.proximityFeet")
    }

    func setMinimumTrackDistanceFeet(_ value: Int) {
        minimumTrackDistanceFeet = max(2, value)
        defaults.set(minimumTrackDistanceFeet, forKey: "track.minimumDistanceFeet")
    }

    func setNewTrackDelaySeconds(_ value: Int) {
        newTrackDelaySeconds = max(1, value)
        defaults.set(newTrackDelaySeconds, forKey: "track.newTrackDelaySeconds")
    }

    func setBridgeCheckDistanceFeet(_ value: Int) {
        bridgeCheckDistanceFeet = max(1, value)
        defaults.set(bridgeCheckDistanceFeet, forKey: "track.bridgeCheckDistanceFeet")
    }

    func setMaximumIdleMinutes(_ value: Int) {
        maximumIdleMinutes = max(0, value)
        defaults.set(maximumIdleMinutes, forKey: "app.maximumIdleMinutes")
    }

    func resetPersistedState() {
        AppleDeviceIdentity.applyManagedDisplayName("")
        organizationName = ""
        incident = "Training"
        operationalPeriod = "1"
        trackFolder = "Drone Tracks"
        teamID = ""
        trackerURLPrefix = ""
        faaProxyURL = ""
        usePeers = true
        standaloneR2CCoordinationEnabled = false
        predictiveHeadEnabled = true
        proximityAlertSpacingFeet = 40
        minimumTrackDistanceFeet = 2
        newTrackDelaySeconds = 30
        bridgeCheckDistanceFeet = 20
        maximumIdleMinutes = Self.defaultMaximumIdleMinutes
        defaults.set(maximumIdleMinutes, forKey: "app.maximumIdleMinutes")
        sourceDescription = "Not loaded"
        trackerEnrollmentURL = ""
        for key in [
            "org.name", "org.teamID", "org.trackerURLPrefix", "org.faaProxyURL",
            "org.trackerEnrollmentURL", "org.sourceDescription", "org.joinToken",
            "org.mutualAidToken", "mutualAid.template.teamID",
            "mutualAid.template.domainAndPort", "mutualAid.template.sourceLabel",
            "mutualAid.template.targetFolderHint",
        ] {
            defaults.removeObject(forKey: key)
        }
        for account in [
            Self.trackerAccount,
            Self.faaClientIDAccount,
            Self.faaClientSecretAccount,
            Self.mutualAidCredentialIDAccount,
            Self.mutualAidCredentialSecretAccount,
        ] {
            try? Self.storeSecret("", account: account)
        }
    }

    private static func normalizedTrackFolder(_ value: String?) -> String {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "Drone Tracks" : trimmed
    }

    func setIncidentMapTitle(_ title: String) {
        let value = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        incident = value
        defaults.set(value, forKey: "org.incident")
    }

    func setIncident(_ value: String) {
        incident = value.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(incident, forKey: "org.incident")
    }

    func setOperationalPeriod(_ value: String) {
        operationalPeriod = value.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
    }

    func transferSnapshot() -> [String: Any] {
        var value: [String: Any] = [
            "organization_name": organizationName,
            "incident": incident,
            "operational_period": operationalPeriod,
            "track_folder": trackFolder,
            "team_id": teamID,
            "tracker_url_prefix": trackerURLPrefix,
            "tracker_api_key": trackerAPIKey,
            "use_peers": usePeers,
            "standalone_r2c_coordination_enabled": standaloneR2CCoordinationEnabled,
            "predictive_head": predictiveHeadEnabled,
            "proximity_feet": proximityAlertSpacingFeet,
            "minimum_track_distance_feet": minimumTrackDistanceFeet,
            "new_track_delay_seconds": newTrackDelaySeconds,
            "bridge_check_distance_feet": bridgeCheckDistanceFeet,
            "maximum_idle_minutes": maximumIdleMinutes,
            "source_description": sourceDescription,
        ]
        if let faaConfiguration {
            value["faa"] = [
                "source_label": faaConfiguration.sourceLabel,
                "api_base_url": faaConfiguration.apiBaseURL,
                "token_url": faaConfiguration.tokenURL,
                "client_id": faaConfiguration.clientID,
                "client_secret": faaConfiguration.clientSecret,
                "scope": faaConfiguration.scope,
            ]
        }
        if let mutualAidTemplate {
            value["mutual_aid_template"] = [
                "team_id": mutualAidTemplate.teamID,
                "credential_id": mutualAidTemplate.credentialID,
                "credential_secret": mutualAidTemplate.credentialSecret,
                "domain_and_port": mutualAidTemplate.domainAndPort,
                "source_label": mutualAidTemplate.sourceLabel,
                "target_folder_hint": mutualAidTemplate.targetFolderHint,
                "connect_key": mutualAidTemplate.connectKey,
            ]
        }
        return value
    }

    func applyTransferSnapshot(_ object: [String: Any]) throws {
        organizationName = object["organization_name"] as? String ?? ""
        incident = object["incident"] as? String ?? ""
        operationalPeriod = object["operational_period"] as? String ?? ""
        trackFolder = Self.normalizedTrackFolder(object["track_folder"] as? String)
        teamID = object["team_id"] as? String ?? ""
        trackerURLPrefix = object["tracker_url_prefix"] as? String ?? ""
        usePeers = (object["use_peers"] as? NSNumber)?.boolValue ?? true
        standaloneR2CCoordinationEnabled = false
        predictiveHeadEnabled = (object["predictive_head"] as? NSNumber)?.boolValue ?? true
        proximityAlertSpacingFeet = (object["proximity_feet"] as? NSNumber)?.intValue ?? 40
        minimumTrackDistanceFeet = max(2, (object["minimum_track_distance_feet"] as? NSNumber)?.intValue ?? 2)
        newTrackDelaySeconds = max(1, (object["new_track_delay_seconds"] as? NSNumber)?.intValue ?? 30)
        bridgeCheckDistanceFeet = max(1, (object["bridge_check_distance_feet"] as? NSNumber)?.intValue ?? 20)
        maximumIdleMinutes = max(
            0,
            (object["maximum_idle_minutes"] as? NSNumber)?.intValue
                ?? Self.defaultMaximumIdleMinutes
        )
        sourceDescription = object["source_description"] as? String ?? "Local backup"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(standaloneR2CCoordinationEnabled, forKey: "org.standaloneR2CCoordination")
        defaults.set(predictiveHeadEnabled, forKey: "org.predictiveHead")
        defaults.set(proximityAlertSpacingFeet, forKey: "org.proximityFeet")
        defaults.set(minimumTrackDistanceFeet, forKey: "track.minimumDistanceFeet")
        defaults.set(newTrackDelaySeconds, forKey: "track.newTrackDelaySeconds")
        defaults.set(bridgeCheckDistanceFeet, forKey: "track.bridgeCheckDistanceFeet")
        defaults.set(maximumIdleMinutes, forKey: "app.maximumIdleMinutes")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        try Self.storeSecret(object["tracker_api_key"] as? String ?? "", account: Self.trackerAccount)
        if let faa = object["faa"] as? [String: Any] {
            try applyEmbedded(faa: FaaSharedConfig(
                sourceLabel: faa["source_label"] as? String ?? "",
                apiBaseURL: faa["api_base_url"] as? String ?? "",
                tokenURL: faa["token_url"] as? String ?? "",
                clientID: faa["client_id"] as? String ?? "",
                clientSecret: faa["client_secret"] as? String ?? "",
                scope: faa["scope"] as? String ?? ""
            ))
        }
        if let template = object["mutual_aid_template"] as? [String: Any] {
            try apply(mutualAidTemplate: MutualAidTemplateCredentials(
                teamID: template["team_id"] as? String ?? "",
                credentialID: template["credential_id"] as? String ?? "",
                credentialSecret: template["credential_secret"] as? String ?? "",
                domainAndPort: template["domain_and_port"] as? String ?? "caltopo.com",
                sourceLabel: template["source_label"] as? String ?? "",
                targetFolderHint: template["target_folder_hint"] as? String ?? "MAI",
                connectKey: template["connect_key"] as? String ?? ""
            ))
        }
    }

    func apply(faa config: FaaSharedConfig, normalizedToken: String) throws {
        try apply(faa: config, sourceToken: normalizedToken)
    }

    func applyEmbedded(faa config: FaaSharedConfig) throws {
        try apply(faa: config, sourceToken: nil)
    }

    func apply(mutualAidTemplate template: MutualAidTemplateCredentials) throws {
        defaults.set(template.teamID, forKey: "mutualAid.template.teamID")
        defaults.set(template.domainAndPort, forKey: "mutualAid.template.domainAndPort")
        defaults.set(template.sourceLabel, forKey: "mutualAid.template.sourceLabel")
        defaults.set(template.targetFolderHint, forKey: "mutualAid.template.targetFolderHint")
        defaults.set(template.connectKey, forKey: "mutualAid.template.connectKey")
        try Self.storeSecret(template.credentialID, account: Self.mutualAidCredentialIDAccount)
        try Self.storeSecret(template.credentialSecret, account: Self.mutualAidCredentialSecretAccount)
    }

    private func apply(faa config: FaaSharedConfig, sourceToken: String?) throws {
        defaults.set(config.sourceLabel, forKey: "faa.sourceLabel")
        defaults.set(config.apiBaseURL, forKey: "faa.apiBaseURL")
        defaults.set(config.tokenURL, forKey: "faa.tokenURL")
        defaults.set(config.scope, forKey: "faa.scope")
        if let sourceToken { defaults.set(sourceToken, forKey: "faa.joinToken") }
        try Self.storeSecret(config.clientID, account: Self.faaClientIDAccount)
        try Self.storeSecret(config.clientSecret, account: Self.faaClientSecretAccount)
    }

    static func loadTrackerAPIKey() -> String? {
        loadSecret(account: trackerAccount)
    }

    private static func loadSecret(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func storeTrackerAPIKey(_ value: String) throws {
        try storeSecret(value, account: trackerAccount)
    }

    private static func storeSecret(_ value: String, account: String) throws {
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        if value.isEmpty {
            let status = SecItemDelete(key as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
            }
            return
        }
        let data = Data(value.utf8)
        let update = SecItemUpdate(key as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(update))
        }
        var insert = key
        insert[kSecValueData as String] = data
        let status = SecItemAdd(insert as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }
}

@MainActor
final class AppleOrgConfigImporter: ObservableObject {
    enum State: Equatable {
        case idle
        case downloading
        case applied(String)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    let profileLifecycle = AppleCaltopoProfileLifecycle.shared
    var caltopoConfigurationHandler: ((AppleCaltopoConfiguration) -> Void)?
    var notamEnrollmentAppliedHandler: ((String, String, String) -> Void)?
    var trackerReauthenticationRequiredHandler: ((URL) -> Void)?

    var statusText: String {
        switch state {
        case .idle: "No import in progress"
        case .downloading: "Downloading shared configuration…"
        case let .applied(message), let .failed(message): message
        }
    }

    func prepareForImport() {
        guard state != .downloading else { return }
        state = .idle
    }

    func importTrackerEnrollment(
        _ rawURL: String,
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings,
        identityStore: AppleDroneConfirmationStore
    ) async {
        state = .downloading
        let deviceName = AppleDeviceIdentity.displayName
        AppleLog.info("OrgConfig", "Redeeming managed tracker enrollment")
        do {
            let result = try await AppleTrackerEnrollmentClient.redeem(
                rawURL,
                deviceName: deviceName,
                previousDeviceToken: orgSettings.trackerAPIKey
            )
            try orgSettings.applyTrackerEnrollment(
                organization: result.organization,
                trackerURLPrefix: result.trackerBaseURL,
                trackerAPIKey: result.deviceToken,
                faaProxyURL: result.faaProxyURL,
                enrollmentURL: rawURL
            )
            try profileLifecycle.captureHome(org: orgSettings, caltopo: caltopoSettings)
            if let url = result.reauthenticationURL {
                trackerReauthenticationRequiredHandler?(url)
                AppleLog.info(
                    "OrgConfig",
                    "Deferring protected configuration sync until tracker reauthentication completes"
                )
            } else {
                AppleNotamCenter.shared.enabled = true
                notamEnrollmentAppliedHandler?(
                    orgSettings.faaProxyURL,
                    orgSettings.trackerURLPrefix,
                    orgSettings.trackerAPIKey
                )
                do {
                    if let managed = try await AppleTrackerEnrollmentClient.fetchManagedOrganizationConfig(
                        trackerBaseURL: result.trackerBaseURL,
                        deviceToken: result.deviceToken
                    ) {
                        try AppleManagedOrganizationConfig.apply(
                            snapshot: managed.snapshot,
                            versionMs: managed.versionMs,
                            caltopo: caltopoSettings,
                            organization: orgSettings,
                            identities: identityStore
                        )
                        caltopoConfigurationHandler?(caltopoSettings.configuration)
                    }
                } catch {
                    AppleLog.error(
                        "OrgConfig",
                        "Initial managed configuration sync failed; tracker will retry: \(error.localizedDescription)"
                    )
                }
            }
            state = .applied("Joined \(result.organization) on r2c-tracker.")
            AppleLog.info(
                "OrgConfig",
                "Installed managed tracker enrollment org='\(result.organization)'"
            )
        } catch {
            state = .failed(error.localizedDescription)
            AppleLog.error(
                "OrgConfig",
                "Managed tracker enrollment failed: \(error.localizedDescription)"
            )
        }
    }

    func importToken(
        _ rawToken: String,
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings,
        identityStore: AppleDroneConfirmationStore
    ) async {
        let normalized = AndroidConfigTokenCodec.normalize(rawToken)
        guard let token = AndroidConfigTokenCodec.decode(normalized), token.version == 2 else {
            state = .failed("Token not recognised or unsupported.")
            return
        }
        state = .downloading
        AppleLog.info("OrgConfig", "Downloading \(token.kind.rawValue) QR config label='\(token.displayName)'")
        do {
            var components = URLComponents(string: "https://drive.google.com/uc")
            components?.queryItems = [
                URLQueryItem(name: "export", value: "download"),
                URLQueryItem(name: "id", value: token.driveFileID),
            ]
            guard let url = components?.url else { throw OrgConfigInteropError.invalidToken }
            var request = URLRequest(url: url)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            request.timeoutInterval = 90
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                throw URLError(.badServerResponse)
            }
            switch token.kind {
            case .organization:
                let bundle = try OrgConfigTokenCodec.parseBundle(data)
                let name = bundle.organizationName.isEmpty ? token.displayName : bundle.organizationName
                try orgSettings.apply(bundle: bundle, normalizedToken: normalized)
                try caltopoSettings.applyImported(credentials: bundle.credentials)
                caltopoConfigurationHandler?(caltopoSettings.configuration)
                try identityStore.applyImportedMappings(bundle.mappings)
                if let mutualAidTemplate = bundle.mutualAidTemplate {
                    try orgSettings.apply(mutualAidTemplate: mutualAidTemplate)
                }
                guard AppleTrackerEnrollmentClient.isEnrollmentURL(bundle.trackerEnrollmentURL) else {
                    throw OrgConfigInteropError.invalidBundle
                }
                let enrollment = try await AppleTrackerEnrollmentClient.redeem(
                    bundle.trackerEnrollmentURL,
                    deviceName: AppleDeviceIdentity.displayName,
                    previousDeviceToken: orgSettings.trackerAPIKey
                )
                guard enrollment.organization.caseInsensitiveCompare(name) == .orderedSame else {
                    throw OrgConfigInteropError.invalidBundle
                }
                try orgSettings.applyTrackerEnrollment(
                    organization: enrollment.organization,
                    trackerURLPrefix: enrollment.trackerBaseURL,
                    trackerAPIKey: enrollment.deviceToken,
                    faaProxyURL: enrollment.faaProxyURL,
                    enrollmentURL: bundle.trackerEnrollmentURL
                )
                try profileLifecycle.captureHome(org: orgSettings, caltopo: caltopoSettings)
                if let url = enrollment.reauthenticationURL {
                    trackerReauthenticationRequiredHandler?(url)
                } else {
                    AppleNotamCenter.shared.enabled = true
                    notamEnrollmentAppliedHandler?(
                        orgSettings.faaProxyURL,
                        orgSettings.trackerURLPrefix,
                        orgSettings.trackerAPIKey
                    )
                }
                let extras = [
                    bundle.mutualAidTemplate == nil ? nil : "mutual-aid template",
                ].compactMap { $0 }
                let extraMessage = extras.isEmpty ? "" : " Included \(extras.joined(separator: " and "))."
                state = .applied(
                    "\(name) R2C2 configuration loaded and this device enrolled with r2c-tracker. "
                        + "Applied \(bundle.mappings.count) RID mappings.\(extraMessage)"
                )
                AppleLog.info(
                    "OrgConfig",
                    "Applied Android org QR config org='\(name)' mappings=\(bundle.mappings.count) ignored=\(bundle.ignoredConfigTypes.joined(separator: ","))"
                )
            case .faa:
                let config = try AndroidConfigTokenCodec.parseFaaBundle(data)
                try orgSettings.apply(faa: config, normalizedToken: normalized)
                state = .applied("FAA config imported: \(config.sourceLabel.isEmpty ? token.displayName : config.sourceLabel).")
                AppleLog.info("OrgConfig", "Applied Android FAA QR config label='\(config.sourceLabel)'")
            case .mutualAid:
                let profile = try AndroidConfigTokenCodec.parseMutualAidBundle(data)
                let active = try profileLifecycle.install(
                    profile,
                    org: orgSettings,
                    caltopo: caltopoSettings
                )
                if active {
                    try orgSettings.apply(mutualAid: profile, normalizedToken: normalized)
                    try caltopoSettings.applyImported(mutualAid: profile)
                    caltopoConfigurationHandler?(caltopoSettings.configuration)
                }
                state = .applied(
                    active
                        ? "Mutual-aid access installed for \(profile.displayName)."
                        : "Expired mutual-aid access was not activated."
                )
                AppleLog.info("OrgConfig", "Applied Android MA QR profile='\(profile.profileID)' map='\(profile.targetMapID)'")
            }
        } catch {
            state = .failed("Import failed: \(error.localizedDescription)")
            AppleLog.error("OrgConfig", "Import failed: \(error.localizedDescription)")
        }
    }

    func importFile(
        _ url: URL,
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings,
        identityStore: AppleDroneConfirmationStore
    ) async {
        state = .downloading
        let importStartedAt = ProcessInfo.processInfo.systemUptime
        AppleLog.info(
            "OrgConfig",
            "Reading local config file name='\(url.lastPathComponent)'"
        )
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            if url.pathExtension.lowercased() == "aol" {
                let id = try await AppleSurfaceStore.shared.install(data)
                state = .applied("Surface prepared and pinned: \(id)")
                return
            }
            if Self.isQRCodeImageFile(url) {
                let decodeStartedAt = ProcessInfo.processInfo.systemUptime
                guard let payload = try await Self.decodeQRCodePayload(from: data, fileURL: url) else {
                    state = .failed("No readable QR code was found in that image.")
                    AppleLog.error("OrgConfig", "Selected image did not contain a readable QR code")
                    return
                }
                AppleLog.info(
                    "OrgConfig",
                    "Decoded local QR image bytes=\(data.count) durationMs=\(Int(((ProcessInfo.processInfo.systemUptime - decodeStartedAt) * 1_000).rounded()))"
                )
                if let enrollmentURL = AppleTrackerEnrollmentClient.normalizedEnrollmentURL(payload) {
                    await importTrackerEnrollment(
                        enrollmentURL,
                        caltopoSettings: caltopoSettings,
                        orgSettings: orgSettings,
                        identityStore: identityStore
                    )
                } else if AndroidConfigTokenCodec.decode(payload) != nil {
                    await importToken(
                        payload,
                        caltopoSettings: caltopoSettings,
                        orgSettings: orgSettings,
                        identityStore: identityStore
                    )
                } else {
                    state = .failed("The QR code does not contain a supported configuration.")
                    AppleLog.error("OrgConfig", "Selected QR image contained an unsupported payload")
                }
                AppleLog.info(
                    "OrgConfig",
                    "Finished local QR import durationMs=\(Int(((ProcessInfo.processInfo.systemUptime - importStartedAt) * 1_000).rounded()))"
                )
                return
            }
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw OrgConfigInteropError.invalidBundle
            }
            guard root["format"] as? String == "rid2caltopo_org_config" else {
                throw OrgConfigInteropError.invalidBundle
            }
            let bundle = try OrgConfigTokenCodec.parseBundle(data)
            try orgSettings.apply(bundle: bundle, normalizedToken: "local-file:\(url.lastPathComponent)")
            try caltopoSettings.applyImported(credentials: bundle.credentials)
            caltopoConfigurationHandler?(caltopoSettings.configuration)
            if !bundle.mappings.isEmpty {
                try identityStore.applyImportedMappings(bundle.mappings)
            }
            if let mutualAidTemplate = bundle.mutualAidTemplate {
                try orgSettings.apply(mutualAidTemplate: mutualAidTemplate)
            }
            guard AppleTrackerEnrollmentClient.isEnrollmentURL(bundle.trackerEnrollmentURL) else {
                throw OrgConfigInteropError.invalidBundle
            }
            let enrollment = try await AppleTrackerEnrollmentClient.redeem(
                bundle.trackerEnrollmentURL,
                deviceName: AppleDeviceIdentity.displayName,
                previousDeviceToken: orgSettings.trackerAPIKey
            )
            guard enrollment.organization.caseInsensitiveCompare(bundle.organizationName) == .orderedSame else {
                throw OrgConfigInteropError.invalidBundle
            }
            try orgSettings.applyTrackerEnrollment(
                organization: enrollment.organization,
                trackerURLPrefix: enrollment.trackerBaseURL,
                trackerAPIKey: enrollment.deviceToken,
                faaProxyURL: enrollment.faaProxyURL,
                enrollmentURL: bundle.trackerEnrollmentURL
            )
            try profileLifecycle.captureHome(org: orgSettings, caltopo: caltopoSettings)
            if let url = enrollment.reauthenticationURL {
                trackerReauthenticationRequiredHandler?(url)
            } else {
                AppleNotamCenter.shared.enabled = true
                notamEnrollmentAppliedHandler?(
                    orgSettings.faaProxyURL,
                    orgSettings.trackerURLPrefix,
                    orgSettings.trackerAPIKey
                )
            }
            state = .applied(
                "Loaded \(url.lastPathComponent): \(bundle.mappings.count) RID mapping(s)."
            )
            AppleLog.info(
                "OrgConfig",
                "Loaded local config file name='\(url.lastPathComponent)' mappings=\(bundle.mappings.count) durationMs=\(Int(((ProcessInfo.processInfo.systemUptime - importStartedAt) * 1_000).rounded()))"
            )
        } catch {
            state = .failed("Config file import failed: \(error.localizedDescription)")
            AppleLog.error("OrgConfig", "Local config import failed: \(error.localizedDescription)")
        }
    }

    private nonisolated static func isQRCodeImageFile(_ url: URL) -> Bool {
        if let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .image) {
            return true
        }
        return (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType)?
            .conforms(to: .image) == true
    }

    private nonisolated static func decodeQRCodePayload(
        from data: Data,
        fileURL: URL
    ) async throws -> String? {
        try await Task.detached(priority: .userInitiated) {
            let request = VNDetectBarcodesRequest()
            request.symbologies = [.qr]
            let handler: VNImageRequestHandler
            if isSVGQRCodeFile(fileURL) {
                handler = VNImageRequestHandler(
                    cgImage: try TrackerQRSVG.rasterize(data),
                    options: [:]
                )
            } else {
                handler = VNImageRequestHandler(data: data, options: [:])
            }
            try handler.perform([request])
            return request.results?.compactMap(\.payloadStringValue).first { !$0.isEmpty }
        }.value
    }

    private nonisolated static func isSVGQRCodeFile(_ url: URL) -> Bool {
        if url.pathExtension.caseInsensitiveCompare("svg") == .orderedSame {
            return true
        }
        guard let type = try? url.resourceValues(forKeys: [.contentTypeKey]).contentType else {
            return false
        }
        return type == .svg || type.identifier == "public.svg-image"
    }

    @discardableResult
    func activateProfile(
        _ profileID: String,
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings
    ) -> Bool {
        do {
            let activated = try profileLifecycle.activate(
                profileID: profileID,
                org: orgSettings,
                caltopo: caltopoSettings
            )
            if activated {
                caltopoConfigurationHandler?(caltopoSettings.configuration)
                state = .applied("Selected \(profileLifecycle.activeCredentialLabel) Teams credentials.")
            }
            return activated
        } catch {
            AppleLog.error("OrgConfig", "Profile activation failed: \(error.localizedDescription)")
            state = .failed("Could not switch Teams credentials: \(error.localizedDescription)")
            return false
        }
    }

    @discardableResult
    func removeExpiredProfiles(
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings
    ) -> Bool {
        do {
            let removed = try profileLifecycle.removeExpired(
                org: orgSettings,
                caltopo: caltopoSettings
            )
            if removed {
                caltopoConfigurationHandler?(caltopoSettings.configuration)
                state = .applied("Expired mutual-aid access removed; home organization restored.")
            }
            return removed
        } catch {
            AppleLog.error("OrgConfig", "Mutual-aid expiry cleanup failed: \(error.localizedDescription)")
            return false
        }
    }

    @discardableResult
    func restoreActiveProfile(
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings
    ) -> Bool {
        do {
            return try profileLifecycle.restoreActive(
                org: orgSettings,
                caltopo: caltopoSettings
            )
        } catch {
            AppleLog.error("OrgConfig", "Profile restoration failed: \(error.localizedDescription)")
            return false
        }
    }
}

struct ConfigImportNotice: Identifiable, Equatable {
    let id = UUID()
    let succeeded: Bool
    let message: String

    var title: String {
        succeeded ? "Configuration imported" : "Import failed"
    }
}

struct ConfigImportView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var importer: AppleOrgConfigImporter
    @ObservedObject var caltopoSettings: AppleCaltopoSettings
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let onFinished: (ConfigImportNotice) -> Void
    @State private var tokenText: String
    @State private var showScanner = false
    @State private var showFileImporter = false
    @State private var submitting = false
    @State private var prepared = false

    init(
        initialToken: String,
        importer: AppleOrgConfigImporter,
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings,
        identityStore: AppleDroneConfirmationStore,
        onFinished: @escaping (ConfigImportNotice) -> Void = { _ in }
    ) {
        self.importer = importer
        self.caltopoSettings = caltopoSettings
        self.orgSettings = orgSettings
        self.identityStore = identityStore
        self.onFinished = onFinished
        _tokenText = State(initialValue: initialToken)
    }

    var body: some View {
        Form {
            Section {
                Text("Scan or choose an image of an R2C2 organization QR or a direct r2c-tracker enrollment QR. R2C1 organization tokens are no longer accepted.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                HStack(alignment: .top, spacing: 10) {
                    TextField("Import token", text: $tokenText, axis: .vertical)
                        .font(.caption.monospaced())
                        .lineLimit(2 ... 4)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textFieldStyle(.roundedBorder)
                    Button {
                        showScanner = true
                    } label: {
                        Image(systemName: "qrcode.viewfinder")
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Scan QR code")
                    .disabled(
                        submitting
                            || !DataScannerViewController.isSupported
                            || !DataScannerViewController.isAvailable
                    )
                }
                Text(recognitionText)
                    .font(.footnote)
                    .foregroundStyle(recognitionColor)
            }
            Section {
                HStack(spacing: 12) {
                    Button("Choose Config or Surface File", systemImage: "doc.badge.arrow.up") {
                        showFileImporter = true
                    }
                    .buttonStyle(.bordered)
                    .disabled(submitting)

                    Spacer()

                    Button("Cancel", role: .cancel) { dismiss() }
                        .buttonStyle(.bordered)
                        .disabled(submitting)

                    Button("Import") { submitToken() }
                        .buttonStyle(.borderedProminent)
                        .disabled(!canImport)
                }
            }
        }
        .navigationTitle("Import Config")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !prepared else { return }
            prepared = true
            importer.prepareForImport()
        }
        .sheet(isPresented: $showScanner) {
            QRCodeScannerView { value in
                tokenText = value
                showScanner = false
            }
            .ignoresSafeArea()
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.image, .json, .plainText, .data]
        ) { result in
            guard case let .success(url) = result else { return }
            submitFile(url)
        }
    }

    private var decodedToken: AndroidConfigJoinToken? { AndroidConfigTokenCodec.decode(tokenText) }
    private var trackerEnrollmentURL: String? {
        AppleTrackerEnrollmentClient.normalizedEnrollmentURL(tokenText)
    }
    private var isTrackerEnrollment: Bool {
        trackerEnrollmentURL != nil
    }

    private var canImport: Bool {
        !submitting
            && importer.state != .downloading
            && (isTrackerEnrollment || decodedToken != nil)
    }

    private var recognitionText: String {
        if tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "Scan QR, paste token, or choose a QR image, JSON config, or prepared .aol surface package"
        }
        if isTrackerEnrollment { return "Managed r2c-tracker enrollment identified"
        }
        if let decodedToken {
            let label = decodedToken.displayName.isEmpty ? "Unnamed" : decodedToken.displayName
            if decodedToken.kind == .organization {
                return "Organization Teams config: \(label) (tracker access verified after import)"
            }
            return "\(tokenKindName(decodedToken.kind)): \(label)"
        }
        return "Token not recognised"
    }

    private var recognitionColor: Color {
        tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || canImport
            ? .secondary
            : .red
    }

    private func submitToken() {
        guard canImport else { return }
        submitting = true
        let value = tokenText
        let normalizedTrackerEnrollment = trackerEnrollmentURL
        Task { @MainActor in
            if let normalizedTrackerEnrollment {
                await importer.importTrackerEnrollment(
                    normalizedTrackerEnrollment,
                    caltopoSettings: caltopoSettings,
                    orgSettings: orgSettings,
                    identityStore: identityStore
                )
            } else {
                await importer.importToken(
                    value,
                    caltopoSettings: caltopoSettings,
                    orgSettings: orgSettings,
                    identityStore: identityStore
                )
            }
            reportResult()
        }
        dismiss()
    }

    private func submitFile(_ url: URL) {
        guard !submitting else { return }
        submitting = true
        Task { @MainActor in
            await importer.importFile(
                url,
                caltopoSettings: caltopoSettings,
                orgSettings: orgSettings,
                identityStore: identityStore
            )
            reportResult()
        }
        dismiss()
    }

    private func reportResult() {
        switch importer.state {
        case let .applied(message):
            onFinished(.init(succeeded: true, message: message))
        case let .failed(message):
            onFinished(.init(succeeded: false, message: message))
        case .idle, .downloading:
            onFinished(.init(succeeded: false, message: "The configuration import did not complete."))
        }
    }

    private func tokenKindName(_ kind: AndroidConfigTokenKind) -> String {
        switch kind {
        case .organization: "Organization"
        case .faa: "FAA"
        case .mutualAid: "Mutual Aid"
        }
    }

}

private struct QRCodeScannerView: UIViewControllerRepresentable {
    let onScanned: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onScanned: onScanned) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {
        if !uiViewController.isScanning { try? uiViewController.startScanning() }
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScanned: (String) -> Void
        init(onScanned: @escaping (String) -> Void) { self.onScanned = onScanned }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            for item in addedItems {
                guard case let .barcode(barcode) = item,
                      let value = barcode.payloadStringValue
                else { continue }
                dataScanner.stopScanning()
                onScanned(value)
                return
            }
        }
    }
}
