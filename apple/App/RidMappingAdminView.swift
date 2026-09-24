import R2CCore
import SwiftUI
import Foundation
import VisionKit

@MainActor
enum AppleAircraftOrganizationAccess {
    static var operatingAssignment = OperatingProfileAssignment()
    static var operatingIncidentID = ""
    private static var authorizedToken = ""
    private static var expires = Date.distantPast
    private static var verifiedToken = ""
    private static var verifiedScope = ""
    private static var username = ""
    private static var accessMessage = "Account has not been verified. Refresh access while online."
    private static var messageToken = ""
    private static var messageScope = ""
    static var accessStatus: String {
        guard belongsToOrganization else { return "Local aircraft entries. No organization account is signed in." }
        guard messageToken == AppleOrgConfigSettings.loadTrackerAPIKey(), messageScope == scope else {
            return "Account has not been verified. Refresh access while online."
        }
        return accessMessage
    }
    static var organizationUser: String? {
        guard belongsToOrganization, verifiedToken == AppleOrgConfigSettings.loadTrackerAPIKey(), verifiedScope == scope, !username.isEmpty else { return nil }
        return username
    }
    static var scope: String { UserDefaults.standard.string(forKey: "org.trackerURLPrefix") ?? "" }
    static var cachedState: [String: Any] {
        guard let data = UserDefaults.standard.data(forKey: "readiness:" + scope) else { return [:] }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }
    static var belongsToOrganization: Bool { (AppleOrgConfigSettings.loadTrackerAPIKey() ?? "").hasPrefix("r2c_dev_") }
    static var canEdit: Bool {
        !belongsToOrganization || (authorizedToken == AppleOrgConfigSettings.loadTrackerAPIKey() && verifiedScope == scope && Date() < expires)
    }
    static func requireEdit() throws {
        guard canEdit else { throw AppleTrackerEnrollmentClient.EnrollmentError.server("Organization aircraft changes require current config_admin authorization. Refresh RID Map Entries while online.") }
    }
    static func refresh(baseURL: String) async -> Bool {
        authorizedToken = ""; expires = .distantPast
        username = ""
        defer { NotificationCenter.default.post(name: Notification.Name("aircraftReadinessUpdated"), object: nil) }
        if !belongsToOrganization { return true }
        let token = AppleOrgConfigSettings.loadTrackerAPIKey() ?? ""
        messageToken = token; messageScope = baseURL
        accessMessage = "Checking organization account and RID editing access…"
        NotificationCenter.default.post(name: Notification.Name("aircraftReadinessUpdated"), object: nil)
        guard let base = URL(string: baseURL) else {
            accessMessage = "Tracker account verification could not be completed. Check the organization Tracker address."
            return false
        }
        var request = URLRequest(url: base.appendingPathComponent("api/v1/aircraft-readiness"))
        request.setValue(token, forHTTPHeaderField: "X-SAR-Token")
        request.timeoutInterval = 20
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard token == AppleOrgConfigSettings.loadTrackerAPIKey(), baseURL == scope else { return false }
            if [401, 403].contains((response as? HTTPURLResponse)?.statusCode ?? 0) {
                UserDefaults.standard.removeObject(forKey: "readiness:" + baseURL)
                NotificationCenter.default.post(name: Notification.Name("aircraftReadinessUpdated"), object: nil)
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200 else {
                accessMessage = [401, 403].contains(status)
                    ? "Tracker could not verify this tablet's enrollment (HTTP \(status)). Re-enroll using the current organization QR and sign in."
                    : "Tracker could not verify your account (HTTP \(status)). Try Refresh access again."
                return false
            }
            guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                accessMessage = "Tracker account verification could not be completed. Try Refresh access again."
                return false
            }
            verifiedToken = token; verifiedScope = baseURL
            username = value["username"] as? String ?? ""
            UserDefaults.standard.set(data, forKey: "readiness:" + baseURL)
            NotificationCenter.default.post(name: Notification.Name("aircraftReadinessUpdated"), object: nil)
            guard value["canEditAircraft"] as? Bool == true else {
                accessMessage = "Read-only · this account does not have config_admin. Contact an organization administrator."
                return false
            }
            accessMessage = "RID editing allowed · config_admin verified."
            authorizedToken = token; expires = Date().addingTimeInterval(300)
            return canEdit
        } catch {
            if token == AppleOrgConfigSettings.loadTrackerAPIKey(), baseURL == scope {
                accessMessage = error is URLError
                    ? "Unable to reach Tracker to verify your account. Check your connection and try Refresh access again."
                    : "Tracker account verification could not be completed. Try Refresh access again."
            }
            return false
        }
    }
}

struct AppleOrganizationUserLabel: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var username: String?
    var body: some View {
        let currentUser = username == AppleAircraftOrganizationAccess.organizationUser ? username : nil
        return Group {
            if AppleAircraftOrganizationAccess.belongsToOrganization {
                Text("Organization account: " + (currentUser ?? "Not verified"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .accessibilityLabel("Organization user: " + (currentUser ?? "not verified"))
            }
        }
        .task(id: AppleAircraftOrganizationAccess.scope + (AppleOrgConfigSettings.loadTrackerAPIKey() ?? "")) {
            _ = await AppleAircraftOrganizationAccess.refresh(baseURL: AppleAircraftOrganizationAccess.scope)
            username = AppleAircraftOrganizationAccess.organizationUser
        }
        .onChange(of: scenePhase) { _, phase in
            // Browser sign-in changes server authorization without changing the device token.
            if phase == .active {
                Task {
                    _ = await AppleAircraftOrganizationAccess.refresh(baseURL: AppleAircraftOrganizationAccess.scope)
                    username = AppleAircraftOrganizationAccess.organizationUser
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("aircraftReadinessUpdated"))) { _ in
            username = AppleAircraftOrganizationAccess.organizationUser
        }
    }
}

private struct AppleAircraftReadinessFields: View {
    @Binding var value: AircraftReadiness
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Aircraft serial number").font(.caption).foregroundStyle(.secondary)
            TextField("Aircraft serial number", text: $value.serialNumber)
        }
        VStack(alignment: .leading, spacing: 4) {
            Text("FAA registration number").font(.caption).foregroundStyle(.secondary)
            TextField("FAA registration number", text: $value.registrationNumber)
        }
        VStack(alignment: .leading, spacing: 4) {
            Text("Base weight (grams)").font(.caption).foregroundStyle(.secondary)
            TextField("Base weight (grams)", value: $value.baseWeightGrams, format: .number)
        }
        VStack(alignment: .leading, spacing: 4) {
            Text("Included in base weight").font(.caption).foregroundStyle(.secondary)
            TextField("Included in base weight", text: $value.baseWeightIncludes)
        }
        Text("Exclude selectable batteries, accessories and payload from base weight. Blank weights remain unknown.").font(.footnote)
        VStack(alignment: .leading, spacing: 4) {
            Text("Required equipment").font(.caption).foregroundStyle(.secondary)
            TextField("Required equipment", text: $value.requiredEquipment)
        }
        VStack(alignment: .leading, spacing: 4) {
            Text("Air traffic monitoring equipment").font(.caption).foregroundStyle(.secondary)
            TextField("Air traffic monitoring equipment", text: $value.monitoringEquipment)
        }
        Text("Examples: aircraft ADS-B warnings on controller; standalone skyAlert.").font(.footnote)
        ForEach($value.accessories) { $accessory in
            VStack(alignment: .leading, spacing: 4) {
                RIDRequiredFieldLabel("Accessory / battery name").font(.caption).foregroundStyle(.secondary)
                TextField("Accessory / battery name", text: $accessory.name)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Accessory weight (grams)").font(.caption).foregroundStyle(.secondary)
                TextField("Accessory weight (grams)", value: $accessory.weightGrams, format: .number)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Choose-one group (for example battery)").font(.caption).foregroundStyle(.secondary)
                TextField("Choose-one group (for example battery)", text: $accessory.group)
            }
            Toggle("Required equipment", isOn: $accessory.required)
            Button("Remove accessory", role: .destructive) { value.accessories.removeAll { $0.id == accessory.id } }
        }
        Button("Add accessory or battery") { value.accessories.append(AircraftAccessory()) }
            .disabled(value.accessories.count >= 32)
    }
}


private struct AppleRidMappingDraft: Identifiable, Codable, Equatable {
    var id = UUID()
    var remoteID: String
    var ownerName: String
    var ownerCallsign: String
    var model: String
    var readiness: AircraftReadiness

    init(identity: RidAircraftIdentity? = nil, defaultPilotCallsign: String = "") {
        remoteID = identity?.remoteID ?? ""
        ownerName = identity?.ownerName ?? ""
        ownerCallsign = identity?.pilotCallsign ?? defaultPilotCallsign
        model = identity?.droneDescription ?? ""
        readiness = identity?.readiness ?? AircraftReadiness()
    }
}

struct AppleTrackerEnrollmentResult: Sendable {
    let organization: String
    let trackerBaseURL: String
    let deviceToken: String
    let faaProxyURL: String
    let reauthenticationURL: URL?
}

struct AppleManagedOrganizationConfigResult: @unchecked Sendable {
    let snapshot: [String: Any]
    let versionMs: Int64
}

enum AppleTrackerEnrollmentClient {
    static let appLinkScheme = "r2cenroll"
    private static let enrollmentSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        configuration.waitsForConnectivity = false
        configuration.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: configuration)
    }()

    static func normalizedEnrollmentURL(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if isEnrollmentURL(trimmed) { return trimmed }
        guard let wrapper = URLComponents(string: trimmed),
              wrapper.scheme?.lowercased() == appLinkScheme,
              let nested = wrapper.queryItems?.first(where: { $0.name == "url" })?.value,
              isEnrollmentURL(nested)
        else { return nil }
        return nested.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func isEnrollmentURL(_ value: String) -> Bool {
        guard let components = URLComponents(
            string: value.trimmingCharacters(in: .whitespacesAndNewlines)
        ),
        components.scheme?.lowercased() == "https",
        let host = components.host?.lowercased(),
        host == "r2c-tracker.com" || host.hasSuffix(".r2c-tracker.com"),
        components.path.hasSuffix("/enroll"),
        components.queryItems?.first(where: { $0.name == "token" })?.value?.isEmpty == false
        else { return false }
        return true
    }

    static func enrollmentOrganization(_ value: String) -> String? {
        guard isEnrollmentURL(value),
              let components = URLComponents(
                  string: value.trimmingCharacters(in: .whitespacesAndNewlines)
              )
        else { return nil }
        let segments = components.path.split(separator: "/").map(String.init)
        guard segments.count >= 2 else { return nil }
        return segments[segments.count - 2]
    }

    static func redeem(
        _ value: String,
        deviceName: String,
        previousDeviceToken: String? = nil
    ) async throws -> AppleTrackerEnrollmentResult {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isEnrollmentURL(trimmed),
              let enrollment = URLComponents(string: trimmed),
              let token = enrollment.queryItems?.first(where: { $0.name == "token" })?.value,
              let scheme = enrollment.scheme,
              let host = enrollment.host
        else { throw EnrollmentError.invalidURL }
        var endpoint = URLComponents()
        endpoint.scheme = scheme
        endpoint.host = host
        endpoint.port = enrollment.port
        endpoint.path = "/api/v1/device-enrollment/redeem"
        guard let url = endpoint.url else { throw EnrollmentError.invalidURL }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(
            String(TrackerCoordinationClient.trackerFunctionalityRelease),
            forHTTPHeaderField: "X-R2C-Functionality-Release"
        )
        if let previousDeviceToken = previousDeviceToken?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !previousDeviceToken.isEmpty {
            request.setValue(
                previousDeviceToken,
                forHTTPHeaderField: "X-R2C-Previous-Device-Token"
            )
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "token": token,
            "device_name": deviceName,
            "device_model": AppleDeviceIdentity.modelName,
            "platform": "ios",
            "installation_id": AppleDeviceIdentity.installationID(),
            "functionality_release": TrackerCoordinationClient.trackerFunctionalityRelease,
        ])
        let requestStartedAt = ProcessInfo.processInfo.systemUptime
        AppleLog.info("OrgConfig", "Starting managed tracker enrollment network request")
        let (data, response) = try await enrollmentSession.data(for: request)
        let durationMs = Int(
            ((ProcessInfo.processInfo.systemUptime - requestStartedAt) * 1_000).rounded()
        )
        guard let http = response as? HTTPURLResponse else {
            throw EnrollmentError.invalidResponse
        }
        AppleLog.info(
            "OrgConfig",
            "Managed tracker enrollment response status=\(http.statusCode) durationMs=\(durationMs)"
        )
        guard (200 ..< 300).contains(http.statusCode) else {
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            throw EnrollmentError.server(
                object?["detail"] as? String ?? "Enrollment failed (\(http.statusCode))."
            )
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let organization = object["organization"] as? [String: Any],
              let tracker = object["tracker"] as? [String: Any],
              let designator = organization["designator"] as? String,
              let baseURL = tracker["base_url"] as? String,
              let apiKey = tracker["api_key"] as? String,
              let faaProxyURL = tracker["faa_proxy_url"] as? String
        else { throw EnrollmentError.invalidResponse }
        return AppleTrackerEnrollmentResult(
            organization: designator,
            trackerBaseURL: baseURL,
            deviceToken: apiKey,
            faaProxyURL: faaProxyURL,
            reauthenticationURL: TrackerReauthenticationChallenge.url(
                fromEnrollmentResponse: data
            )
        )
    }

    static func fetchManagedOrganizationConfig(
        trackerBaseURL: String,
        deviceToken: String
    ) async throws -> AppleManagedOrganizationConfigResult? {
        guard let baseURL = URL(string: trackerBaseURL) else {
            throw EnrollmentError.invalidResponse
        }
        var request = URLRequest(
            url: baseURL.appendingPathComponent("api/v1/organization-config/current")
        )
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 60
        request.setValue(deviceToken, forHTTPHeaderField: "X-SAR-Token")
        request.setValue(
            String(TrackerCoordinationClient.trackerFunctionalityRelease),
            forHTTPHeaderField: "X-R2C-Functionality-Release"
        )
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw EnrollmentError.invalidResponse
        }
        if http.statusCode == 204 { return nil }
        guard (200 ..< 300).contains(http.statusCode),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let versionMs = (root["versionMs"] as? NSNumber)?.int64Value,
              let snapshot = root["config"] as? [String: Any]
        else { throw EnrollmentError.invalidResponse }
        return AppleManagedOrganizationConfigResult(snapshot: snapshot, versionMs: versionMs)
    }

    enum EnrollmentError: LocalizedError {
        case invalidURL
        case invalidResponse
        case server(String)

        var errorDescription: String? {
            switch self {
            case .invalidURL: "Enrollment QR is not an r2c-tracker.com enrollment URL."
            case .invalidResponse: "The tracker returned an invalid enrollment response."
            case let .server(message): message
            }
        }
    }
}

struct RidMappingAdminView: View {
    @State private var canEdit = AppleAircraftOrganizationAccess.canEdit
    @State private var refreshingAccess = false
    @State private var accountName = AppleAircraftOrganizationAccess.organizationUser
    @State private var accessStatus = AppleAircraftOrganizationAccess.accessStatus
    @ObservedObject var organization: AppleOrgConfigSettings
    @ObservedObject var identities: AppleDroneConfirmationStore
    private let onSaved: ((String) -> Void)?
    @State private var organizationName: String
    @State private var mappings: [AppleRidMappingDraft]
    @State private var errors: [String] = []
    @State private var showingValidationErrors = false
    @State private var selectedID: UUID?
    @State private var baseline: [AppleRidMappingDraft] = []
    @State private var baselineOrganization = ""
    @State private var saving = false

    private static let draftOrganizationKey = "org.ridMappingDraft.organization"
    private static let draftsKey = "org.ridMappingDraft.entries"
    private static let draftBaselineRemoteIDsKey = "org.ridMappingDraft.baselineRemoteIDs"

    init(
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore,
        initialRemoteID: String? = nil,
        startWithAddAircraft: Bool = false,
        onSaved: ((String) -> Void)? = nil
    ) {
        self.organization = organization
        self.identities = identities
        self.onSaved = onSaved
        _organizationName = State(initialValue: organization.organizationName)
        var initialMappings = identities.importedMappings.map { AppleRidMappingDraft(identity: $0) }
        let normalizedRemoteID = initialRemoteID?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        var selected: UUID?
        if !normalizedRemoteID.isEmpty {
            if let existing = initialMappings.first(where: { $0.remoteID.caseInsensitiveCompare(normalizedRemoteID) == .orderedSame }) {
                selected = existing.id
            } else {
                var draft = AppleRidMappingDraft(identity: RidAircraftIdentity(
                    remoteID: normalizedRemoteID,
                    organization: organization.organizationName,
                    pilotCallsign: identities.preferredPilotCallsign,
                    droneDescription: ""
                ))
                draft.readiness.serialNumber = normalizedRemoteID
                selected = draft.id
                initialMappings.append(draft)
            }
        } else if startWithAddAircraft {
            let draft = AppleRidMappingDraft(defaultPilotCallsign: identities.preferredPilotCallsign)
            selected = draft.id
            initialMappings.append(draft)
        }
        _mappings = State(initialValue: initialMappings)
        _selectedID = State(initialValue: selected)
        _baseline = State(initialValue: initialMappings)
        _baselineOrganization = State(initialValue: organization.organizationName)
    }

    private func refreshAccess() async {
        refreshingAccess = true
        defer { refreshingAccess = false }
        canEdit = await AppleAircraftOrganizationAccess.refresh(baseURL: organization.trackerURLPrefix)
        accountName = AppleAircraftOrganizationAccess.organizationUser
        accessStatus = AppleAircraftOrganizationAccess.accessStatus
    }

    var body: some View {
        Form {
            if AppleAircraftOrganizationAccess.belongsToOrganization {
                Section("Organization account") {
                    Text(accountName ?? "Not verified").font(.headline)
                    Text(accessStatus).font(.caption)
                    Button(refreshingAccess ? "Checking access…" : "Refresh access") {
                        Task { await refreshAccess() }
                    }.disabled(refreshingAccess)
                }
            }
            if selectedID == nil {
                Section("Aircraft") {
                    if mappings.isEmpty { Text("No aircraft entries.").foregroundStyle(.secondary) }
                    ForEach(Array(mappings.enumerated()), id: \.element.id) { index, mapping in
                        Button { beginEdit(mapping.id) } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(index + 1).  \(mapping.remoteID)").font(.headline)
                                Text("Owner: \(mapping.ownerName.isEmpty ? "—" : mapping.ownerName) · Model: \(mapping.model)")
                                Text("Designator: \(identity(for: mapping).mappedID)").foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }.foregroundStyle(.primary)
                    }
                    Button("Add aircraft", systemImage: "plus") {
                        let draft = AppleRidMappingDraft(defaultPilotCallsign: identities.preferredPilotCallsign)
                        beginEdit(draft.id)
                        mappings.append(draft)
                    }.disabled(!canEdit || saving)
                }
            } else {
                Section {
                    HStack(spacing: 4) {
                        Text("*").foregroundStyle(.red)
                        Text("Required Fields")
                    }
                    .font(.footnote)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Asterisk indicates required fields")
                }
                Section("Organization") {
                    VStack(alignment: .leading, spacing: 4) {
                        RIDRequiredFieldLabel("Organization designator", required: AppleAircraftOrganizationAccess.belongsToOrganization).font(.caption).foregroundStyle(.secondary)
                        TextField("Organization designator", text: $organizationName).disabled(true)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                    }
                    Text(AppleAircraftOrganizationAccess.belongsToOrganization
                         ? "Stored once and applied to every aircraft."
                         : "Optional for local aircraft entries. No organization configuration is needed.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .disabled(!canEdit)
                ForEach($mappings) { $mapping in
                    if mapping.id == selectedID {
                        Section("Aircraft") {
                            VStack(alignment: .leading, spacing: 4) {
                                RIDRequiredFieldLabel("Remote ID")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                AppleScannableTextField(
                                    title: "Remote ID",
                                    text: $mapping.remoteID,
                                    mode: .remoteID
                                )
                                .frame(minHeight: 44)
                                .background(RIDEntryImmediateTouches())
                                .onChange(of: mapping.remoteID) { _, value in
                                    if mapping.readiness.serialNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                        mapping.readiness.serialNumber = value.uppercased()
                                    }
                                }
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Owner name")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                TextField("Owner name", text: $mapping.ownerName)
                                    .frame(minHeight: 44)
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                RIDRequiredFieldLabel("Pilot callsign / name")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                TextField("Pilot callsign / name", text: $mapping.ownerCallsign)
                                    .frame(minHeight: 44)
                                    .textInputAutocapitalization(.words)
                                    .autocorrectionDisabled()
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                RIDRequiredFieldLabel("Model")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                TextField("Model", text: $mapping.model)
                                    .frame(minHeight: 44)
                            }
                            AppleAircraftReadinessFields(value: $mapping.readiness)
                            LabeledContent("Drone designator", value: identity(for: mapping).mappedID)
                            Button("Remove aircraft", role: .destructive) {
                                let previous = mappings
                                mappings.removeAll { $0.id == mapping.id }
                                Task { if !(await save()) { mappings = previous } }
                            }
                        }.disabled(!canEdit || saving)
                    }
                }
            }
            if !errors.isEmpty {
                Section("Please correct") {
                    ForEach(errors, id: \.self) {
                        Text($0).foregroundStyle(.red)
                    }
                }
            }
        }
        .id(selectedID)
        .task { await refreshAccess() }
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("aircraftReadinessUpdated"))) { _ in
            accountName = AppleAircraftOrganizationAccess.organizationUser
            accessStatus = AppleAircraftOrganizationAccess.accessStatus
            canEdit = AppleAircraftOrganizationAccess.canEdit
        }
        .alert("RID entries were not saved", isPresented: $showingValidationErrors) {
            Button("Review Fields", role: .cancel) {}
        } message: {
            Text(errors.joined(separator: "\n"))
        }
        .navigationTitle(selectedID == nil ? "RID Map Entries" : "Aircraft details")
        .navigationBarBackButtonHidden(selectedID != nil)
        .toolbar {
            if selectedID != nil {
                ToolbarItem(placement: .cancellationAction) {
                    Button(canEdit ? "Cancel" : "Back") {
                        mappings = baseline
                        organizationName = baselineOrganization
                        selectedID = nil
                        errors = []
                    }.disabled(saving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if canEdit { Button(saving ? "Checking permission…" : "Save") { Task { _ = await save() } }.disabled(saving) }
                }
            }
        }
    }

    private func identity(for mapping: AppleRidMappingDraft) -> RidAircraftIdentity {
        RidAircraftIdentity(
            remoteID: mapping.remoteID.uppercased(),
            organization: organizationName,
            ownerName: mapping.ownerName,
            pilotCallsign: mapping.ownerCallsign,
            droneDescription: mapping.model,
            readiness: mapping.readiness
        )
    }

    private func beginEdit(_ id: UUID) {
        baseline = mappings
        baselineOrganization = organizationName
        errors = []
        selectedID = id
    }

    @discardableResult
    private func save() async -> Bool {
        guard !saving else { return false }
        errors = validate()
        guard errors.isEmpty else {
            showingValidationErrors = true
            return false
        }
        saving = true
        defer { saving = false }
        if !canEdit {
            guard await AppleAircraftOrganizationAccess.refresh(baseURL: organization.trackerURLPrefix) else {
                errors = ["Editing permission could not be verified. Check your connection and organization administrator access, then try Save again. Your edits are retained."]
                showingValidationErrors = true
                return false
            }
        }
        let savedRemoteID = mappings.first(where: { $0.id == selectedID })?.remoteID
        do {
            let originals = identities.importedMappings
            try identities.replacePersistedMappings(mappings.map { mapping in
                if mapping.id != selectedID, let original = originals.first(where: { $0.remoteID == mapping.remoteID }) { return original }
                return identity(for: mapping)
            })
        }
        catch { errors = [error.localizedDescription]; showingValidationErrors = true; return false }
        UserDefaults.standard.removeObject(forKey: Self.draftOrganizationKey)
        UserDefaults.standard.removeObject(forKey: Self.draftsKey)
        UserDefaults.standard.removeObject(forKey: Self.draftBaselineRemoteIDsKey)
        organization.setOrganizationNameForRidMappings(organizationName)
        selectedID = nil
        if let savedRemoteID, !savedRemoteID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            onSaved?(savedRemoteID.uppercased())
        }
        return true
    }

    private func validate() -> [String] {
        guard let selected = mappings.first(where: { $0.id == selectedID }) else { return [] }
        return RidMappingEditValidation.errors(identity(for: selected), others: mappings.filter { $0.id != selectedID }.map { identity(for: $0) }, requireOrganization: AppleAircraftOrganizationAccess.belongsToOrganization)
    }

}

enum AppleScannedFieldMode {
    case remoteID
    case credential
}

struct AppleScannableTextField: View {
    let title: String
    @Binding var text: String
    let mode: AppleScannedFieldMode
    var secure = false
    @State private var showingScanner = false

    var body: some View {
        HStack {
            Group {
                if secure {
                    SecureField(title, text: $text)
                } else {
                    TextField(title, text: $text)
                }
            }
            .textInputAutocapitalization(mode == .remoteID ? .characters : .never)
            .autocorrectionDisabled()

            Button {
                showingScanner = true
            } label: {
                Image(systemName: "viewfinder")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Scan \(title)")
            .disabled(!DataScannerViewController.isSupported || !DataScannerViewController.isAvailable)
        }
        .sheet(isPresented: $showingScanner) {
            AppleAlphanumericScannerSheet(title: title, mode: mode) { value in
                text = value
                showingScanner = false
            }
        }
    }
}

private struct AppleAlphanumericScannerSheet: View {
    let title: String
    let mode: AppleScannedFieldMode
    let onSelected: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var candidateCounts: [String: Int] = [:]

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                AppleAlphanumericDataScanner(mode: mode) { value, barcode in
                    let increment = barcode ? 3 : 1
                    candidateCounts[value, default: 0] += increment
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text(
                        mode == .remoteID
                            ? "Center the barcode or printed serial number. Confirm every character before saving."
                            : "Center one tuple value at a time. Confirm every character before saving."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    if candidates.isEmpty {
                        Text("Looking for text or a barcode…")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(candidates, id: \.value) { candidate in
                            Button {
                                onSelected(candidate.value)
                            } label: {
                                HStack {
                                    Text(candidate.value)
                                        .font(.system(.body, design: .monospaced))
                                        .lineLimit(2)
                                    Spacer()
                                    if candidate.count >= 2 {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(.green)
                                    }
                                }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.regularMaterial)
            }
            .navigationTitle("Scan \(title)")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var candidates: [(value: String, count: Int)] {
        candidateCounts
            .map { (value: $0.key, count: $0.value) }
            .sorted {
                if ($0.count >= 2) != ($1.count >= 2) { return $0.count >= 2 }
                if $0.count != $1.count { return $0.count > $1.count }
                return $0.value.count > $1.value.count
            }
            .prefix(5)
            .map { $0 }
    }
}

private struct AppleAlphanumericDataScanner: UIViewControllerRepresentable {
    let mode: AppleScannedFieldMode
    let onCandidate: (String, Bool) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(mode: mode, onCandidate: onCandidate)
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [
                .barcode(symbologies: [.qr, .code128, .code39, .code93, .dataMatrix, .pdf417, .aztec]),
                .text(languages: ["en-US"]),
            ],
            qualityLevel: .accurate,
            recognizesMultipleItems: true,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        if !controller.isScanning { try? controller.startScanning() }
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let mode: AppleScannedFieldMode
        let onCandidate: (String, Bool) -> Void

        init(mode: AppleScannedFieldMode, onCandidate: @escaping (String, Bool) -> Void) {
            self.mode = mode
            self.onCandidate = onCandidate
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            process(addedItems)
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didUpdate updatedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            process(updatedItems)
        }

        private func process(_ items: [RecognizedItem]) {
            for item in items {
                switch item {
                case let .barcode(barcode):
                    guard let value = barcode.payloadStringValue else { continue }
                    emitCandidates(from: value, barcode: true)
                case let .text(text):
                    emitCandidates(from: text.transcript, barcode: false)
                @unknown default:
                    continue
                }
            }
        }

        private func emitCandidates(from raw: String, barcode: Bool) {
            for candidate in Self.candidates(from: raw, mode: mode) {
                onCandidate(candidate, barcode)
            }
        }

        private static func candidates(
            from raw: String,
            mode: AppleScannedFieldMode
        ) -> [String] {
            let upper = raw.uppercased()
            let pattern = mode == .remoteID
                ? #"[A-Z0-9]{8,24}"#
                : #"[A-Za-z0-9+/_=-]{4,256}"#
            guard let expression = try? NSRegularExpression(pattern: pattern) else { return [] }
            let source = mode == .remoteID ? upper : raw
            let range = NSRange(source.startIndex..., in: source)
            let ignored = ["SERIAL", "NUMBER", "REMOTEID", "CREDENTIAL"]
            var values: [String] = []
            for match in expression.matches(in: source, range: range) {
                guard let matchRange = Range(match.range, in: source) else { continue }
                let value = String(source[matchRange])
                guard !ignored.contains(value.uppercased()), !values.contains(value) else { continue }
                values.append(value)
            }
            return values
        }
    }
}

/// Let text controls receive touch-down immediately instead of waiting for the
/// form's scroll recognizer to decide whether a tap will become a drag.
private struct RIDEntryImmediateTouches: UIViewRepresentable {
    func makeUIView(context: Context) -> TouchAnchor { TouchAnchor() }
    func updateUIView(_ view: TouchAnchor, context: Context) { view.configureScrollView() }
    static func dismantleUIView(_ view: TouchAnchor, coordinator: ()) { view.restore() }

    final class TouchAnchor: UIView {
        private weak var scrollView: UIScrollView?
        private var originalDelay = true

        init() {
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil { restore() } else { configureScrollView() }
        }
        override func layoutSubviews() {
            super.layoutSubviews()
            configureScrollView()
        }
        func configureScrollView() {
            guard window != nil else { return }
            var ancestor = superview
            while let current = ancestor {
                if let scroll = current as? UIScrollView {
                    guard scrollView !== scroll else { return }
                    restore()
                    scrollView = scroll
                    originalDelay = scroll.delaysContentTouches
                    scroll.delaysContentTouches = false
                    return
                }
                ancestor = current.superview
            }
        }
        func restore() {
            scrollView?.delaysContentTouches = originalDelay
            scrollView = nil
        }
    }
}

private struct RIDRequiredFieldLabel: View {
    let title: String
    let required: Bool
    init(_ title: String, required: Bool = true) {
        self.title = title
        self.required = required
    }
    var body: some View {
        HStack(spacing: 3) {
            Text(title)
            if required { Text("*").foregroundStyle(.red) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(required ? "\(title), required" : title)
    }
}
