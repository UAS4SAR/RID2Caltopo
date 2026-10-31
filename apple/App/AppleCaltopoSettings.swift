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
    var usesSignedPersonalAccount: Bool = false
    let connectKey: String

    var publicationScope: String {
        let personal = personalSessionID != nil || usesSignedPersonalAccount
        return CaltopoPublicationScope.identifier(personalAccountID: personal ? personalAccountID : nil, teamID: teamID)
    }

    var liveConfiguration: CaltopoLiveConfiguration? {
        guard enabled,
              !domainAndPort.isEmpty,
              !mapID.isEmpty,
              (personalSessionID != nil || usesSignedPersonalAccount || (!credentialID.isEmpty && !credentialSecret.isEmpty))
        else { return nil }
        let fallback = CaltopoLiveConfiguration(
            domainAndPort: domainAndPort,
            mapID: mapID,
            credentialID: credentialID,
            credentialSecretBase64: credentialSecret,
            connectKey: connectKey,
            personalSessionID: personalSessionID,
            personalAccountID: personalAccountID,
            personalMediaOwnerID: personalMediaOwnerID
        )
        return CaltopoPersonalAccountConnection.shared.resolveLiveConfiguration(
            lookup: CaltopoPersonalAccountConnection.shared.cachedLookup(host: "caltopo.com", accountID: personalAccountID),
            mapID: mapID,
            connectKey: connectKey,
            personalSessionID: personalSessionID,
            personalAccountID: personalAccountID,
            personalMediaOwnerID: personalMediaOwnerID,
            useSignedPersonalAccount: usesSignedPersonalAccount,
            fallback: fallback
        )
    }
}

@MainActor
final class AppleCaltopoSettings: ObservableObject {
    @Published private(set) var personalLoginMode: CaltopoPersonalLoginMode
    @Published private(set) var personalCredentialSummary = "Not connected"
    @Published private(set) var personalCredentialBusy = false
    @Published var openAccountBrowserAfterCredentialSignIn = false
    @Published var showSyncAccountPrompt = false
    @Published var showPersonalLogOutPrompt = false
    private static let personalLoginModeKey = "caltopo.personalLoginSupport"
    @Published var usesPersonalCredentials: Bool {
        didSet { defaults.set(usesPersonalCredentials, forKey: "caltopo.usesPersonalCredentials") }
    }

    func rememberBrowseProfile(_ profileID: String) {
        MapBrowserProfilePreference(defaults: defaults).remember(profileID)
    }

    /// Restores the last Map Browser profile once a stored personal credential is available.
    func restoreRememberedBrowseProfile(
        personalCredentialAvailable: Bool,
        activeTeamProfileID: String,
        knownTeamProfileIDs: [String]
    ) -> String {
        let preference = MapBrowserProfilePreference(defaults: defaults)
        let target = MapBrowserProfilePreference.resolveRestoredProfileID(
            lastSelectedProfileID: preference.lastSelectedProfileID(),
            personalCredentialAvailable: personalCredentialAvailable,
            defaultTeamProfileID: activeTeamProfileID,
            knownTeamProfileIDs: knownTeamProfileIDs
        )
        if target == MapBrowserProfilePreference.personalProfileID {
            if !usesPersonalCredentials { usesPersonalCredentials = true }
            preference.remember(MapBrowserProfilePreference.personalProfileID)
        } else {
            if usesPersonalCredentials { usesPersonalCredentials = false }
            if !target.isEmpty { preference.remember(target) }
        }
        return target
    }
    @Published private(set) var personalUsername = ""
    @Published private(set) var personalMaps: [CaltopoTeamMapNode] = []
    @Published private(set) var personalMapsStatus = "Sign in to load personal maps"
    func acceptPersonalCatalog(_ username: String, maps: [CaltopoTeamMapNode]) {
        personalLoginRequired = false
        personalUsername = username
        personalMaps = maps
        personalMapsStatus = maps.isEmpty ? "No maps available for this account" : "Personal maps loaded"
    }
    @Published private(set) var personalLoginRequired = false
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
            personalLoginRequired = CaltopoPersonalCatalog.requiresInteractiveLogin(after: error)
            if personalLoginRequired { personalUsername = "" }
            personalMaps = []
            personalMapsStatus = personalLoginRequired
                ? "Sign in to CalTopo to load your personal maps."
                : "Personal maps could not load. Check your connection and tap Retry."
            AppleLog.info("CalTopo", "Personal catalog load failed; signInRequired=\(personalLoginRequired), errorType=\(String(describing: type(of: error)))")
            return false
        }
    }
    private var personalCredentialRevision: UInt64 = 0
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

    private var teamCredentialsExpired = false
    private var credentialRevision = 0
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
        let storedLoginMode = defaults.string(forKey: Self.personalLoginModeKey)
        let resolvedLoginMode = CaltopoPersonalLoginMode.resolve(
            stored: storedLoginMode,
            cookieSessionAlreadyInUse: defaults.bool(forKey: "caltopo.usesPersonalCredentials")
        )
        if storedLoginMode == nil { defaults.set(resolvedLoginMode.rawValue, forKey: Self.personalLoginModeKey) }
        personalLoginMode = resolvedLoginMode
        usesPersonalCredentials = defaults.bool(forKey: "caltopo.usesPersonalCredentials")
        // Seed the last-profile preference from the legacy personal flag on first launch
        // after this change, so a previous personal choice is restored in Map Browser.
        if defaults.bool(forKey: "caltopo.usesPersonalCredentials"),
           MapBrowserProfilePreference(defaults: defaults).lastSelectedProfileID().isEmpty {
            MapBrowserProfilePreference(defaults: defaults).remember(MapBrowserProfilePreference.personalProfileID)
        }
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
        CaltopoPersonalAccountConnection.shared.use(AppleCaltopoPersonalAccountKeychain())
        CaltopoPersonalAccountConnection.shared.setFeatureEnabled(personalLoginMode == .credentials)
        refreshPersonalCredentialSummary()
    }

    /// Catalog title only. A previously stored "account: title" prefix is not shown.
    var displayMapTitle: String {
        OperationalIncidentSelection.displayMapTitle(mapTitle, personalName: personalUsername)
    }

    func mapOptionsText(teamLabel: String) -> String {
        let personal = usesPersonalCredentials || personalSessionID != nil
        return OperationalIncidentSelection.mapOptionsText(
            personal: personal,
            accountName: personal ? personalUsername : teamLabel,
            mapTitle: displayMapTitle)
    }

    var configuration: AppleCaltopoConfiguration {
        let normalizedDomain = domainAndPort.trimmingCharacters(in: .whitespacesAndNewlines)
        return AppleCaltopoConfiguration(
            enabled: enabled && (usesPersonalCredentials || !teamCredentialsExpired),
            domainAndPort: (personalSessionID != nil || (personalLoginMode == .credentials && usesPersonalCredentials && !selectedPersonalAccountID.isEmpty)) ? "caltopo.com" : (normalizedDomain.isEmpty ? "caltopo.com" : normalizedDomain),
            mapID: mapID.trimmingCharacters(in: .whitespacesAndNewlines),
            mapTitle: mapTitle.trimmingCharacters(in: .whitespacesAndNewlines),
            credentialID: credentialID.trimmingCharacters(in: .whitespacesAndNewlines),
            credentialSecret: credentialSecret.trimmingCharacters(in: .whitespacesAndNewlines),
            teamID: teamID.trimmingCharacters(in: .whitespacesAndNewlines),
            personalSessionID: personalSessionID,
            personalAccountID: selectedPersonalAccountID,
            personalMediaOwnerID: selectedPersonalMediaOwnerID,
            usesSignedPersonalAccount: personalLoginMode == .credentials && usesPersonalCredentials && personalSessionID == nil && !selectedPersonalAccountID.isEmpty,
            connectKey: connectKey.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    var teamsCredentialDraft: OperationalTeamsCredentialDraft {
        .init(teamID: teamID, credentialID: credentialID, secret: credentialSecret,
              connectKey: connectKey, domain: domainAndPort)
    }

    /// Explicit local credential save. Does not select a map or switch credential sources.
    func saveTeamsCredentials(_ draft: OperationalTeamsCredentialDraft) async throws {
        if let message = draft.validationMessage {
            throw NSError(domain: "CalTopoSettings", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
        try await commitTeamsCredentials(draft, origin: Self.originIndependent)
    }

    private func commitTeamsCredentials(_ draft: OperationalTeamsCredentialDraft, origin: String) async throws {
        try await CredentialMutationGate.shared.run { [self] in
            try Task.checkCancellation()
            credentialRevision += 1
            let revision = credentialRevision
            let value = draft.normalized
            try await Self.storeSecret(value.secret)
            guard revision == credentialRevision else { throw CancellationError() }
            applyTeamsCredentialMetadata(value, origin: origin)

        }
    }

    static var credentialStoreKey: PrivateCredentialStore.Key { .init(keychainService, secretAccount) }

    private func applyTeamsCredentialMetadata(_ value: OperationalTeamsCredentialDraft, origin: String) {
        teamCredentialsExpired = false
        defaults.set(value.teamID, forKey: "caltopo.teamID")
        defaults.set(value.credentialID, forKey: "caltopo.credentialID")
        defaults.set(value.connectKey, forKey: "caltopo.connectKey")
        defaults.set(value.domain, forKey: "caltopo.domain")
        defaults.set(origin, forKey: Self.credentialOriginKey)
        teamID = value.teamID
        credentialID = value.credentialID
        credentialSecret = value.secret
        connectKey = value.connectKey
        domainAndPort = value.domain
        credentialOrigin = origin
    }

    func suspendExpiredTeamsPublication() {
        teamCredentialsExpired = true
        if !usesPersonalCredentials { _ = disconnectMap() }
        status = "Organization access expired; restore Home or load current credentials"
    }

    /// Publishing preference applies immediately without saving unrelated credential drafts.
    func setPublishingEnabled(_ value: Bool) -> AppleCaltopoConfiguration {
        enabled = value
        defaults.set(value, forKey: "caltopo.enabled")
        return configuration
    }

    func applyImported(credentials: OrgConfigCredentials?) async throws {
        try await CredentialMutationGate.shared.run { [self] in
            guard let credentials else { return }
            try await commitTeamsCredentials(.init(
                teamID: credentials.teamID,
                credentialID: credentials.credentialID.isEmpty ? credentialID : credentials.credentialID,
                secret: credentials.credentialSecret.isEmpty ? credentialSecret : credentials.credentialSecret,
                connectKey: credentials.connectKey,
                domain: credentials.domainAndPort.isEmpty ? domainAndPort : credentials.domainAndPort), origin: Self.originIndependent)
            status = "QR credentials loaded; select the incident Map ID to publish"

        }
    }

    func applyManagedCredentials(_ object: [String: Any]) async throws {
        try await CredentialMutationGate.shared.run { [self] in
            try await commitTeamsCredentials(.init(
                teamID: object["team_id"] as? String ?? "",
                credentialID: object["credential_id"] as? String ?? "",
                secret: object["credential_secret"] as? String ?? "",
                connectKey: object["connect_key"] as? String ?? "",
                domain: object["domain_and_port"] as? String ?? "caltopo.com"), origin: Self.originTracker)
            status = "Managed organization credentials loaded; select the incident map"

        }
    }

    func applyImported(mutualAid profile: MutualAidSharedProfile) async throws {
        try await CredentialMutationGate.shared.run { [self] in
            try await commitTeamsCredentials(.init(
                teamID: profile.teamID,
                credentialID: profile.credentialID.isEmpty ? credentialID : profile.credentialID,
                secret: profile.credentialSecret.isEmpty ? credentialSecret : profile.credentialSecret,
                connectKey: profile.connectKey,
                domain: profile.domainAndPort.isEmpty ? domainAndPort : profile.domainAndPort), origin: Self.originIndependent)
            if !usesPersonalCredentials {
                if !profile.targetMapID.isEmpty { mapID = profile.targetMapID }
                mapTitle = profile.displayName
                personalSessionID = nil
            }
            status = "Organization profile loaded for \(profile.displayName)"

        }
    }

    func apply(storedProfile profile: AppleStoredOperationalProfile, connectMap: Bool,
               credentialsAlreadyStored: Bool = false) async throws {
        try await CredentialMutationGate.shared.run { [self] in
            let draft = OperationalTeamsCredentialDraft(teamID: profile.teamID, credentialID: profile.credentialID,
                secret: profile.credentialSecret, connectKey: profile.connectKey ?? "", domain: profile.domainAndPort).normalized
            if credentialsAlreadyStored {
                credentialRevision += 1
                applyTeamsCredentialMetadata(draft, origin: Self.originIndependent)
            } else { try await commitTeamsCredentials(draft, origin: Self.originIndependent) }
            if !usesPersonalCredentials {
                enabled = profile.enabled
                mapID = connectMap && profile.autoConnect ? profile.mapID : ""
                mapTitle = mapID.isEmpty ? "" : profile.mapTitle
                personalSessionID = nil
            }
            status = mapID.isEmpty ? "Profile restored; select the incident map" : "Connected to \(profile.mapTitle)"
        }
    }

    func transferSnapshot() -> [String: Any] {
        ["map_id": mapID, "map_title": mapTitle]
    }

    func applyTransferSnapshot(_ object: [String: Any]) throws {
        // Ignore credential and service fields, including those in older backups.
        mapID = object["map_id"] as? String ?? ""
        mapTitle = object["map_title"] as? String ?? ""
        defaults.set(mapID, forKey: "caltopo.mapID")
        defaults.set(mapTitle, forKey: "caltopo.mapTitle")
        status = "Map selection restored; device credentials retained"
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

    func setPersonalLoginMode(_ mode: CaltopoPersonalLoginMode) {
        guard mode != personalLoginMode, !personalCredentialBusy, !isLoadingPersonalMaps else { return }
        personalCredentialRevision &+= 1
        personalLoginMode = mode
        defaults.set(mode.rawValue, forKey: Self.personalLoginModeKey)
        CaltopoPersonalAccountConnection.shared.setFeatureEnabled(mode == .credentials)
        if mode == .credentials { refreshPersonalCredentialSummary() }
        if usesPersonalCredentials || personalSessionID != nil {
            personalSessionID = nil
            selectedPersonalAccountID = ""
            selectedPersonalMediaOwnerID = ""
            personalMaps = []
            personalUsername = ""
            usesPersonalCredentials = false
            mapID = ""
            mapTitle = ""
            defaults.removeObject(forKey: "caltopo.mapID")
            defaults.removeObject(forKey: "caltopo.mapTitle")
        }
        status = mode == .credentials
            ? "Credentials selected. Browser sign-in is not used. Sign in to connect an account credential."
            : "Session Cookies selected. An account credential stays on this device until you log out, and is not used for personal maps."
        Task { await CaltopoPersonalSessions.shared.clear() }
    }

    var storedPersonalMenuName: String? {
        let stored = personalUsername.trimmingCharacters(in: .whitespacesAndNewlines)
        if !stored.isEmpty { return stored }
        let prefix = "Connected as "
        if personalCredentialSummary.hasPrefix(prefix) {
            let name = String(personalCredentialSummary.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
            return name.isEmpty ? nil : name
        }
        return nil
    }

    func refreshStoredPersonalName() async {
        let connected = await Task.detached(priority: .utility) {
            CaltopoPersonalAccountConnection.shared.status(host: "caltopo.com")
        }.value
        guard personalLoginMode == .credentials else { return }
        if let connected, !connected.revoked, !connected.displayName.isEmpty {
            if personalUsername.isEmpty { personalUsername = connected.displayName }
            personalCredentialSummary = "Connected as \(connected.displayName)"
        }
    }

    func selectStoredPersonalAccount() async {
        guard personalLoginMode == .credentials, !personalCredentialBusy else { return }
        let existing = await Task.detached(priority: .userInitiated) {
            CaltopoPersonalAccountConnection.shared.reconnect(host: "caltopo.com")
        }.value
        guard existing != nil else {
            showSyncAccountPrompt = true
            return
        }
        usesPersonalCredentials = true
        rememberBrowseProfile(MapBrowserProfilePreference.personalProfileID)
        personalUsername = existing?.displayName ?? personalUsername
        await loadCredentialPersonalMaps()
    }

    func signInWithPersonalCredential() async {
        guard personalLoginMode == .credentials, !personalCredentialBusy else { return }
        personalCredentialRevision &+= 1
        personalCredentialBusy = true
        defer { personalCredentialBusy = false }
        do {
            let connected = try await Task.detached(priority: .userInitiated) {
                if let existing = CaltopoPersonalAccountConnection.shared.reconnect(host: "caltopo.com") {
                    return existing
                }
                return try await CaltopoPersonalAccountConnection.shared.enroll(
                    deviceName: CaltopoPersonalAccountConnection.defaultDeviceName,
                    browser: AppleCaltopoPersonalAccountBrowser(),
                    exchange: CaltopoPersonalAccountHTTPSExchange()
                )
            }.value
            personalCredentialSummary = "Connected as \(connected.displayName)"
            usesPersonalCredentials = true
            rememberBrowseProfile(MapBrowserProfilePreference.personalProfileID)
            personalUsername = connected.displayName
            selectedPersonalAccountID = connected.accountID
            await loadCredentialPersonalMaps()
            openAccountBrowserAfterCredentialSignIn = true
        } catch {
            status = error.localizedDescription
        }
    }

    func logOutPersonalCredential() async {
        guard !personalCredentialBusy, !isLoadingPersonalMaps else { return }
        personalCredentialRevision &+= 1
        personalCredentialBusy = true
        defer { personalCredentialBusy = false }
        await CaltopoPersonalAccountConnection.shared.logOut()
        personalCredentialSummary = "Not connected"
        if personalLoginMode == .credentials {
            personalSessionID = nil
            selectedPersonalAccountID = ""
            selectedPersonalMediaOwnerID = ""
            personalMaps = []
            personalCatalogAccountID = ""
            personalUsername = ""
            personalMapsStatus = "Sign in to connect your CalTopo account"
            if usesPersonalCredentials {
                usesPersonalCredentials = false
                mapID = ""
                mapTitle = ""
                defaults.removeObject(forKey: "caltopo.mapID")
                defaults.removeObject(forKey: "caltopo.mapTitle")
            }
        }
        status = "Logged out of the CalTopo account credential."
        Task { await CaltopoPersonalSessions.shared.clear() }
    }

    func fallBackFromMissingPersonalCredential(activeTeamProfileID: String) {
        usesPersonalCredentials = false
        if !activeTeamProfileID.isEmpty {
            rememberBrowseProfile(activeTeamProfileID)
        }
    }

    private var personalCatalogAccountID = ""

    func loadCredentialPersonalMaps() async {
        guard personalLoginMode == .credentials, !isLoadingPersonalMaps else { return }
        let revision = personalCredentialRevision
        isLoadingPersonalMaps = true
        defer { isLoadingPersonalMaps = false }
        do {
            let configuration = try await Task.detached(priority: .userInitiated) {
                try CaltopoPersonalAccountConnection.shared.teamMapConfiguration()
            }.value
            if personalCatalogAccountID == configuration.teamID, !personalMaps.isEmpty {
                CaltopoPersonalAccountConnection.logState("catalog cache hit account=\(configuration.teamID) maps=\(personalMaps.count)")
                return
            }
            let client = try CaltopoTeamMapClient(configuration: configuration)
            let request = try await client.makeRequest(now: Date())
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                if status == 401 || status == 403 {
                    await CaltopoPersonalAccountConnection.shared.disconnect()
                    personalCatalogAccountID = ""
                    personalUsername = ""
                    personalCredentialSummary = "Not connected"
                    usesPersonalCredentials = false
                    personalMaps = []
                    showSyncAccountPrompt = true
                    personalMapsStatus = "CalTopo rejected the saved account. Sign in again."
                    return
                }
                throw CaltopoLiveClientError.httpStatus(status, "CalTopo account catalog failed.")
            }
            await CaltopoPersonalAccountConnection.shared.deleteSpareActivationCredential(body: data)
            let prepared = try await Task.detached(priority: .userInitiated) {
                try CaltopoPersonalCatalog.prepare(data, expectedID: configuration.teamID)
            }.value
            guard revision == personalCredentialRevision, personalLoginMode == .credentials else { return }
            acceptPersonalCatalog(prepared.username, maps: prepared.maps)
            selectedPersonalAccountID = configuration.teamID
            personalCatalogAccountID = configuration.teamID
        } catch {
            guard revision == personalCredentialRevision, personalLoginMode == .credentials else { return }
            personalMaps = []
            personalMapsStatus = error.localizedDescription
        }
    }

    private func refreshPersonalCredentialSummary() {
        guard personalLoginMode == .credentials else { return }
        let revision = personalCredentialRevision
        Task { [weak self] in
            let connected = await Task.detached(priority: .utility) {
                CaltopoPersonalAccountConnection.shared.status(host: "caltopo.com")
            }.value
            guard let self, revision == self.personalCredentialRevision else { return }
            personalCredentialSummary = connected.flatMap { $0.revoked ? nil : "Connected as \($0.displayName)" } ?? "Not connected"
        }
    }

    @discardableResult
    func selectMap(_ map: CaltopoTeamMap) -> AppleCaltopoConfiguration {
        if personalLoginMode == .credentials && usesPersonalCredentials {
            personalSessionID = nil
            let account = CaltopoPersonalAccountConnection.shared.cachedStatus(host: "caltopo.com")?.accountID ?? selectedPersonalAccountID
            selectedPersonalAccountID = map.personalAccountID.isEmpty ? account : map.personalAccountID
            selectedPersonalMediaOwnerID = map.personalMediaOwnerID.isEmpty ? selectedPersonalAccountID : map.personalMediaOwnerID
            mapID = map.id
            mapTitle = map.title
            enabled = true
            rememberBrowseProfile(MapBrowserProfilePreference.personalProfileID)
            status = "Connected to \(map.title)"
            return configuration
        }
        personalSessionID = map.personalSessionID
        selectedPersonalAccountID = map.personalAccountID
        selectedPersonalMediaOwnerID = map.personalMediaOwnerID
        mapID = map.id
        mapTitle = map.title
        enabled = true
        // Selecting a map does not edit or rewrite credentials.
        defaults.set(true, forKey: "caltopo.enabled")
        defaults.set(mapID, forKey: "caltopo.mapID")
        defaults.set(mapTitle, forKey: "caltopo.mapTitle")
        if usesPersonalCredentials || personalSessionID != nil {
            rememberBrowseProfile(MapBrowserProfilePreference.personalProfileID)
        }
        let value = configuration
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

    func resetPersistedState() async {
        _ = try? await CredentialMutationGate.shared.run { [self] in
            credentialRevision += 1
            personalResetGeneration += 1
            personalLoginRequired = false
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
            MapBrowserProfilePreference(defaults: defaults).clear()
            for key in [
                "caltopo.enabled", "caltopo.domainAndPort", "caltopo.mapID",
                "caltopo.mapTitle", "caltopo.credentialID", "caltopo.teamID",
                "caltopo.connectKey",
                Self.credentialOriginKey,
            ] {
                defaults.removeObject(forKey: key)
            }
            try? await Self.storeSecret("")

        }
    }

    /// Clears credentials delivered by Tracker while leaving independently entered or
    /// imported credentials available during offline operation and Tracker outages.
    @discardableResult
    func quarantineTrackerManagedCredentials() async -> Bool {
        (try? await CredentialMutationGate.shared.run { [self] in
            guard credentialOrigin != Self.originIndependent else {
                status = "Tracker authorization paused; independent CalTopo credentials preserved"
                return false
            }
            credentialRevision += 1
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
            for key in ["caltopo.credentialID", "caltopo.teamID", "caltopo.connectKey", "caltopo.mapID", "caltopo.mapTitle"] {
                defaults.removeObject(forKey: key)
            }
            defaults.set(false, forKey: "caltopo.enabled")
            do { try await Self.storeSecret("") }
            catch { AppleLog.error("CalTopo", "Managed credential removal unavailable; runtime access remains revoked") }
            status = "Tracker-managed CalTopo credentials cleared pending reauthentication"
            return true

        }) ?? false
    }

    private func findMap(id: String, in nodes: [CaltopoTeamMapNode]) -> CaltopoTeamMap? {
        for node in nodes {
            if let map = node.map, map.id == id { return map }
            if let children = node.children, let map = findMap(id: id, in: children) { return map }
        }
        return nil
    }

    private static func loadSecret() -> String? {
        PrivateCredentialStore.live.cachedString(.init(keychainService, secretAccount))
    }

    private static func storeSecret(_ secret: String) async throws {
        try await PrivateCredentialStore.live.store(secret.isEmpty ? nil : Data(secret.utf8),
                                                    for: .init(keychainService, secretAccount))
    }
}
