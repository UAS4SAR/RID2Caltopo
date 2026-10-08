import Combine
import Foundation
import R2CCore
import Security

struct AppleCaltopoConfiguration: Sendable, Equatable {
    let enabled: Bool
    let domainAndPort: String
    let mapID: String
    let mapTitle: String
    let credentialID: String
    let credentialSecret: String
    let teamID: String
    var personalSessionID: UUID? = nil
    var personalAccountID: String = ""
    var personalMediaOwnerID: String = ""
    let connectKey: String

    var publicationScope: String {
        CaltopoPublicationScope.identifier(personalAccountID: personalSessionID == nil ? nil : personalAccountID, teamID: teamID)
    }

    var liveConfiguration: CaltopoLiveConfiguration? {
        guard enabled,
              !domainAndPort.isEmpty,
              !mapID.isEmpty,
              (personalSessionID != nil || (!credentialID.isEmpty && !credentialSecret.isEmpty))
        else { return nil }
        return CaltopoLiveConfiguration(
            domainAndPort: domainAndPort,
            mapID: mapID,
            credentialID: credentialID,
            credentialSecretBase64: credentialSecret,
            connectKey: connectKey,
            personalSessionID: personalSessionID,
            personalAccountID: personalAccountID,
            personalMediaOwnerID: personalMediaOwnerID
        )
    }
}

@MainActor
final class AppleCaltopoSettings: ObservableObject {
    @Published var usesPersonalCredentials = false
    @Published private(set) var personalUsername = ""
    @Published private(set) var personalMaps: [CaltopoTeamMapNode] = []
    @Published private(set) var personalMapsStatus = "Sign in to load personal maps"
    func acceptPersonalCatalog(_ username: String, maps: [CaltopoTeamMapNode]) {
        personalUsername = username
        personalMaps = maps
        personalMapsStatus = maps.isEmpty ? "No maps available for this account" : "Personal maps loaded"
    }
    @Published private(set) var isLoadingPersonalMaps = false
    private var personalResetGeneration = 0
    func loadPersonalMaps() async -> Bool {
        let generation = personalResetGeneration
        isLoadingPersonalMaps = true
        defer { if generation == personalResetGeneration { isLoadingPersonalMaps = false } }
        do {
            let (username, maps) = try await PersonalProbeModel.shared.loadNativeCatalog()
            guard generation == personalResetGeneration else { return false }
            acceptPersonalCatalog(username, maps: maps)
            return true
        } catch {
            guard generation == personalResetGeneration else { return false }
            if case CaltopoLiveClientError.httpStatus(let code, _) = error, code == 401 || code == 403 {
                personalUsername = ""
            }
            personalMaps = []
            personalMapsStatus = "Open CalTopo to sign in and load your personal maps."
            return false
        }
    }
    private var selectedPersonalAccountID = ""
    private var selectedPersonalMediaOwnerID = ""
    @Published private(set) var personalSessionID: UUID?
    @Published var enabled: Bool
    @Published var domainAndPort: String
    @Published var mapID: String
    @Published private(set) var mapTitle: String
    @Published var credentialID: String
    @Published var credentialSecret: String
    @Published var teamID: String
    @Published var connectKey: String
    @Published private(set) var status = "Not configured"
    @Published private(set) var teamMaps: [CaltopoTeamMapNode] = []
    @Published private(set) var isLoadingTeamMaps = false

    private let defaults: UserDefaults
    private var credentialOrigin: String
    private static let keychainService = "org.ncssar.RID2CaltopoApple.caltopo"
    private static let secretAccount = "credential-secret"
    private static let credentialOriginKey = "caltopo.credentialOrigin"
    private static let originUnknown = "unknown"
    private static let originIndependent = "independent"
    private static let originTracker = "tracker"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: "caltopo.enabled")
        domainAndPort = defaults.string(forKey: "caltopo.domain") ?? "caltopo.com"
        // Match Android's session lifecycle: credentials and profiles persist,
        // but every process launch begins without an active incident map. A map
        // becomes active only after the operator explicitly selects it.
        mapID = ""
        mapTitle = ""
        defaults.removeObject(forKey: "caltopo.mapID")
        defaults.removeObject(forKey: "caltopo.mapTitle")
        credentialID = defaults.string(forKey: "caltopo.credentialID") ?? ""
        credentialSecret = Self.loadSecret() ?? ""
        teamID = defaults.string(forKey: "caltopo.teamID") ?? ""
        connectKey = defaults.string(forKey: "caltopo.connectKey") ?? ""
        credentialOrigin = defaults.string(forKey: Self.credentialOriginKey)
            ?? Self.originUnknown
        status = enabled ? "Standalone; select the incident map" : "Publishing disabled"
    }

    var configuration: AppleCaltopoConfiguration {
        let normalizedDomain = domainAndPort.trimmingCharacters(in: .whitespacesAndNewlines)
        return AppleCaltopoConfiguration(
            enabled: enabled,
            domainAndPort: personalSessionID != nil ? "caltopo.com" : (normalizedDomain.isEmpty ? "caltopo.com" : normalizedDomain),
            mapID: mapID.trimmingCharacters(in: .whitespacesAndNewlines),
            mapTitle: mapTitle.trimmingCharacters(in: .whitespacesAndNewlines),
            credentialID: credentialID.trimmingCharacters(in: .whitespacesAndNewlines),
            credentialSecret: credentialSecret.trimmingCharacters(in: .whitespacesAndNewlines),
            teamID: teamID.trimmingCharacters(in: .whitespacesAndNewlines),
            personalSessionID: personalSessionID,
            personalAccountID: selectedPersonalAccountID,
            personalMediaOwnerID: selectedPersonalMediaOwnerID,
            connectKey: connectKey.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    var teamsCredentialDraft: OperationalTeamsCredentialDraft {
        .init(teamID: teamID, credentialID: credentialID, secret: credentialSecret,
              connectKey: connectKey, domain: domainAndPort)
    }

    /// Explicit local credential save. Does not select a map or switch credential sources.
    func saveTeamsCredentials(_ draft: OperationalTeamsCredentialDraft) throws {
        if let message = draft.validationMessage {
            throw NSError(domain: "CalTopoSettings", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
        let value = draft.normalized
        // Store the secret first; a Keychain failure must not apply a partial credential edit.
        try Self.storeSecret(value.secret)
        defaults.set(value.teamID, forKey: "caltopo.teamID")
        defaults.set(value.credentialID, forKey: "caltopo.credentialID")
        defaults.set(value.connectKey, forKey: "caltopo.connectKey")
        defaults.set(value.domain, forKey: "caltopo.domain")
        defaults.set(Self.originIndependent, forKey: Self.credentialOriginKey)
        teamID = value.teamID
        credentialID = value.credentialID
        credentialSecret = value.secret
        connectKey = value.connectKey
        domainAndPort = value.domain
        credentialOrigin = Self.originIndependent
    }

    /// Publishing preference applies immediately without saving unrelated credential drafts.
    func setPublishingEnabled(_ value: Bool) -> AppleCaltopoConfiguration {
        enabled = value
        defaults.set(value, forKey: "caltopo.enabled")
        return configuration
    }

    @discardableResult
    func save(markCredentialsIndependent: Bool = true) -> AppleCaltopoConfiguration {
        let value = configuration
        if usesPersonalCredentials {
            // Organization refreshes may update saved credentials while a personal map is active.
            // Never persist that map under the organization profile.
            defaults.set(domainAndPort, forKey: "caltopo.domain")
            defaults.set(credentialID, forKey: "caltopo.credentialID")
            defaults.set(teamID, forKey: "caltopo.teamID")
            defaults.set(connectKey, forKey: "caltopo.connectKey")
            do { try Self.storeSecret(credentialSecret) }
            catch { status = "Keychain save failed: \(error.localizedDescription)" }
            return value
        }
        if markCredentialsIndependent {
            credentialOrigin = Self.originIndependent
            defaults.set(credentialOrigin, forKey: Self.credentialOriginKey)
        }
        defaults.set(value.enabled, forKey: "caltopo.enabled")
        defaults.set(value.domainAndPort, forKey: "caltopo.domain")
        defaults.set(value.mapID, forKey: "caltopo.mapID")
        defaults.set(value.mapTitle, forKey: "caltopo.mapTitle")
        defaults.set(value.credentialID, forKey: "caltopo.credentialID")
        defaults.set(value.teamID, forKey: "caltopo.teamID")
        defaults.set(value.connectKey, forKey: "caltopo.connectKey")
        do {
            try Self.storeSecret(value.credentialSecret)
            status = value.liveConfiguration == nil
                ? "Saved; publishing remains disabled or incomplete"
                : "Saved securely"
        } catch {
            status = "Keychain save failed: \(error.localizedDescription)"
        }
        return value
    }

    func applyImported(credentials: OrgConfigCredentials?) throws {
        guard let credentials else { return }
        if !credentials.domainAndPort.isEmpty { domainAndPort = credentials.domainAndPort }
        if !credentials.credentialID.isEmpty { credentialID = credentials.credentialID }
        if !credentials.credentialSecret.isEmpty { credentialSecret = credentials.credentialSecret }
        teamID = credentials.teamID
        connectKey = credentials.connectKey
        defaults.set(teamID, forKey: "caltopo.teamID")
        credentialOrigin = Self.originIndependent
        defaults.set(credentialOrigin, forKey: Self.credentialOriginKey)
        _ = save(markCredentialsIndependent: false)
        status = "QR credentials loaded; select the incident Map ID to publish"
    }

    func applyManagedCredentials(_ object: [String: Any]) throws {
        domainAndPort = (object["domain_and_port"] as? String)?.isEmpty == false
            ? object["domain_and_port"] as! String : "caltopo.com"
        credentialID = object["credential_id"] as? String ?? ""
        credentialSecret = object["credential_secret"] as? String ?? ""
        teamID = object["team_id"] as? String ?? ""
        connectKey = object["connect_key"] as? String ?? ""
        defaults.set(teamID, forKey: "caltopo.teamID")
        credentialOrigin = Self.originTracker
        defaults.set(credentialOrigin, forKey: Self.credentialOriginKey)
        _ = save(markCredentialsIndependent: false)
        status = "Managed organization credentials loaded; select the incident map"
    }

    func applyImported(mutualAid profile: MutualAidSharedProfile) throws {
        if !profile.domainAndPort.isEmpty { domainAndPort = profile.domainAndPort }
        if !usesPersonalCredentials {
            if !profile.targetMapID.isEmpty { mapID = profile.targetMapID }
            mapTitle = profile.displayName
            personalSessionID = nil
        }
        if !profile.credentialID.isEmpty { credentialID = profile.credentialID }
        if !profile.credentialSecret.isEmpty { credentialSecret = profile.credentialSecret }
        teamID = profile.teamID
        connectKey = profile.connectKey
        defaults.set(teamID, forKey: "caltopo.teamID")
        credentialOrigin = Self.originIndependent
        defaults.set(credentialOrigin, forKey: Self.credentialOriginKey)
        _ = save(markCredentialsIndependent: false)
        status = "Mutual-aid QR loaded for \(profile.displayName)"
    }

    func apply(storedProfile profile: AppleStoredOperationalProfile, connectMap: Bool) throws {
        if !usesPersonalCredentials {
            enabled = profile.enabled
            mapID = connectMap && profile.autoConnect ? profile.mapID : ""
            mapTitle = mapID.isEmpty ? "" : profile.mapTitle
            personalSessionID = nil
        }
        domainAndPort = profile.domainAndPort.isEmpty ? "caltopo.com" : profile.domainAndPort
        credentialID = profile.credentialID
        credentialSecret = profile.credentialSecret
        teamID = profile.teamID
        connectKey = profile.connectKey ?? ""
        defaults.set(teamID, forKey: "caltopo.teamID")
        credentialOrigin = Self.originIndependent
        defaults.set(credentialOrigin, forKey: Self.credentialOriginKey)
        _ = save(markCredentialsIndependent: false)
        status = mapID.isEmpty
            ? "Profile restored; select the incident map"
            : "Connected to \(profile.mapTitle)"
    }

    func transferSnapshot() -> [String: Any] {
        [
            "enabled": enabled,
            "domain_and_port": domainAndPort,
            "map_id": mapID,
            "map_title": mapTitle,
            "credential_id": credentialID,
            "credential_secret": credentialSecret,
            "team_id": teamID,
            "connect_key": connectKey,
        ]
    }

    func applyTransferSnapshot(_ object: [String: Any]) throws {
        enabled = (object["enabled"] as? NSNumber)?.boolValue ?? false
        domainAndPort = object["domain_and_port"] as? String ?? "caltopo.com"
        mapID = object["map_id"] as? String ?? ""
        mapTitle = object["map_title"] as? String ?? ""
        credentialID = object["credential_id"] as? String ?? ""
        credentialSecret = object["credential_secret"] as? String ?? ""
        teamID = object["team_id"] as? String ?? ""
        connectKey = object["connect_key"] as? String ?? ""
        defaults.set(teamID, forKey: "caltopo.teamID")
        credentialOrigin = Self.originIndependent
        defaults.set(credentialOrigin, forKey: Self.credentialOriginKey)
        _ = save(markCredentialsIndependent: false)
        status = "Configuration restored from local backup"
    }

    func loadTeamMaps() async {
        let value = configuration
        guard !value.teamID.isEmpty, !value.credentialID.isEmpty, !value.credentialSecret.isEmpty else {
            status = "Enter the CalTopo team ID, credential ID, and credential secret before browsing team maps"
            teamMaps = []
            return
        }
        isLoadingTeamMaps = true
        status = "Loading CalTopo team maps…"
        AppleLog.info(
            "CalTopo",
            "Loading team maps domain='\(value.domainAndPort)' teamPresent=\(!value.teamID.isEmpty) credentialPresent=\(!value.credentialID.isEmpty) secretPresent=\(!value.credentialSecret.isEmpty)"
        )
        defer { isLoadingTeamMaps = false }
        do {
            let client = try CaltopoTeamMapClient(configuration: .init(
                domainAndPort: value.domainAndPort,
                teamID: value.teamID,
                credentialID: value.credentialID,
                credentialSecretBase64: value.credentialSecret
            ))
            teamMaps = try await client.fetch()
            if let selected = findMap(id: mapID, in: teamMaps) {
                mapTitle = selected.title
                defaults.set(mapTitle, forKey: "caltopo.mapTitle")
                status = "Connected to \(selected.title)"
            } else {
                status = teamMaps.isEmpty ? "No team maps were returned" : "Select the incident map"
            }
        } catch {
            teamMaps = []
            status = "Unable to load team maps: \(error.localizedDescription)"
            AppleLog.error("CalTopo", status)
        }
    }

    @discardableResult
    func selectMap(_ map: CaltopoTeamMap) -> AppleCaltopoConfiguration {
        personalSessionID = map.personalSessionID
        selectedPersonalAccountID = map.personalAccountID
        selectedPersonalMediaOwnerID = map.personalMediaOwnerID
        mapID = map.id
        mapTitle = map.personalSessionID != nil ? "\(personalUsername): \(map.title)" : map.title
        enabled = true
        let value = personalSessionID == nil ? save(markCredentialsIndependent: false) : configuration
        status = "Connected to \(map.title)"
        return value
    }

    @discardableResult
    func disconnectMap() -> AppleCaltopoConfiguration {
        personalSessionID = nil
        mapID = ""
        mapTitle = ""
        defaults.removeObject(forKey: "caltopo.mapID")
        defaults.removeObject(forKey: "caltopo.mapTitle")
        status = "Standalone; select the incident map"
        return configuration
    }

    func resetPersistedState() {
        personalResetGeneration += 1
        personalMaps = []
        personalMapsStatus = "Sign in to load personal maps"
        isLoadingPersonalMaps = false
        selectedPersonalAccountID = ""
        selectedPersonalMediaOwnerID = ""
        usesPersonalCredentials = false
        personalUsername = ""
        personalSessionID = nil
        enabled = false
        domainAndPort = "caltopo.com"
        mapID = ""
        mapTitle = ""
        credentialID = ""
        credentialSecret = ""
        teamID = ""
        connectKey = ""
        teamMaps = []
        credentialOrigin = Self.originUnknown
        status = "Not configured"
        for key in [
            "caltopo.enabled", "caltopo.domainAndPort", "caltopo.mapID",
            "caltopo.mapTitle", "caltopo.credentialID", "caltopo.teamID",
            "caltopo.connectKey",
            Self.credentialOriginKey,
        ] {
            defaults.removeObject(forKey: key)
        }
        try? Self.storeSecret("")
    }

    /// Clears credentials delivered by Tracker while leaving independently entered or
    /// imported credentials available during offline operation and Tracker outages.
    @discardableResult
    func quarantineTrackerManagedCredentials() -> Bool {
        guard credentialOrigin != Self.originIndependent else {
            status = "Tracker authorization paused; independent CalTopo credentials preserved"
            return false
        }
        enabled = false
        mapID = ""
        mapTitle = ""
        credentialID = ""
        credentialSecret = ""
        teamID = ""
        connectKey = ""
        teamMaps = []
        credentialOrigin = Self.originTracker
        defaults.set(credentialOrigin, forKey: Self.credentialOriginKey)
        defaults.removeObject(forKey: AppleManagedOrganizationConfig.versionDefaultsKey)
        _ = save(markCredentialsIndependent: false)
        status = "Tracker-managed CalTopo credentials cleared pending reauthentication"
        return true
    }

    private func findMap(id: String, in nodes: [CaltopoTeamMapNode]) -> CaltopoTeamMap? {
        for node in nodes {
            if let map = node.map, map.id == id { return map }
            if let children = node.children, let map = findMap(id: id, in: children) { return map }
        }
        return nil
    }

    private static func loadSecret() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: secretAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func storeSecret(_ secret: String) throws {
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: secretAccount,
        ]
        if secret.isEmpty {
            let status = SecItemDelete(key as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
            }
            return
        }
        let data = Data(secret.utf8)
        let updateStatus = SecItemUpdate(
            key as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(updateStatus))
        }
        var insert = key
        insert[kSecValueData as String] = data
        let insertStatus = SecItemAdd(insert as CFDictionary, nil)
        guard insertStatus == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(insertStatus))
        }
    }
}
