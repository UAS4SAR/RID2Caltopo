import Foundation
import R2CCore
import Security
import SwiftUI
import UniformTypeIdentifiers
import QuickLook

struct CaltopoSettingsView: View {
    @ObservedObject var settings: AppleCaltopoSettings
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    @ObservedObject var locationProvider: AppleLocationProvider
    @ObservedObject var importer: AppleOrgConfigImporter
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    @ObservedObject var trackModel: RIDTrackViewModel
    @ObservedObject var proximityAlerts: AppleProximityAlertCenter
    let iCloudBackup: AppleICloudBackupCenter
    let onSave: (AppleCaltopoConfiguration) -> Void
    @ObservedObject private var notams = AppleNotamCenter.shared
    @ObservedObject private var airspace = AppleAirspaceCenter.shared
    @ObservedObject private var landRestrictions = AppleLandRestrictionCenter.shared
    @ObservedObject private var externalDisplay = AppleExternalDisplaySettings.shared
    @ObservedObject private var spokenWarnings = AppleSpokenWarningCenter.shared
    @ObservedObject private var profileLifecycle = AppleCaltopoProfileLifecycle.shared
    @AppStorage("video.captureStreams") private var captureStreams = true
    @AppStorage("video.restrictMediaServerAccess") private var restrictMediaServerAccess = true
    @AppStorage("video.remoteControlEnabled") private var remoteVideoControlEnabled = false
    @AppStorage(OperationalThumbnailRefreshInterval.storageKey)
    private var thumbnailRefreshSeconds = OperationalThumbnailRefreshInterval.defaultSeconds
    @AppStorage(AppleDeviceIdentity.storedNameKey) private var deviceName = AppleDeviceIdentity.displayName
    @AppStorage(AppleDeviceIdentity.managedNameKey) private var managedDeviceName = ""
    @AppStorage("rid.minimumHorizontalAccuracyCode") private var minimumHorizontalAccuracyCode = 9
    @State private var showingTeamMaps = false

    var body: some View {
        Form {
            Section("Administration") {
                NavigationLink {
                    RidMappingAdminView(
                        organization: orgSettings,
                        identities: identityStore
                    )
                } label: {
                    Label(
                        "RID Map Entries (\(identityStore.importedMappingCount))",
                        systemImage: "airplane.circle"
                    )
                }
                Text("Organization QR imports populate these Remote ID mappings. Open this editor to review, add, or correct entries stored on this device.")
                    .font(.footnote)
            }
            Section("Organization and operational defaults") {
                TextField(
                    "Organization designator",
                    text: Binding(
                        get: { orgSettings.organizationName },
                        set: { orgSettings.setOrganizationNameForRidMappings($0) }
                    )
                )
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                TextField(
                    "CalTopo track folder",
                    text: Binding(
                        get: { orgSettings.trackFolder },
                        set: { orgSettings.setTrackFolder($0) }
                    )
                )
                TextField(
                    "Incident",
                    text: Binding(
                        get: { orgSettings.incident },
                        set: { orgSettings.setIncident($0) }
                    )
                )
                TextField(
                    "Operational period",
                    text: Binding(
                        get: { orgSettings.operationalPeriod },
                        set: { orgSettings.setOperationalPeriod($0) }
                    )
                )
                Text("These are the same organization, track_folder, incident, and op_period values accepted by organization JSON.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("CalTopo Teams account") {
                AppleScannableTextField(
                    title: "Team ID",
                    text: $settings.teamID,
                    mode: .credential
                )
                AppleScannableTextField(
                    title: "Credential ID",
                    text: $settings.credentialID,
                    mode: .credential
                )
                AppleScannableTextField(
                    title: "Credential secret",
                    text: $settings.credentialSecret,
                    mode: .credential,
                    secure: true
                )
                AppleScannableTextField(
                    title: "Connect Key",
                    text: $settings.connectKey,
                    mode: .credential
                )
                Text("Enter the CalTopo team ID, credential ID, and credential secret tuple. The secret is stored in Apple Keychain when the configuration is saved or the map browser is opened.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            AppleTrackerConfigurationSection(settings: orgSettings)
            AppleMutualAidTupleSection(settings: orgSettings)
            Section("Incident map") {
                if settings.mapID.isEmpty {
                    Label("No CalTopo map selected", systemImage: "map")
                        .foregroundStyle(.secondary)
                } else {
                    LabeledContent("Connected Map", value: settings.mapTitle.isEmpty ? settings.mapID : settings.mapTitle)
                    LabeledContent("Map ID", value: settings.mapID)
                }
                Button {
                    onSave(settings.save())
                    showingTeamMaps = true
                } label: {
                    Label(settings.mapID.isEmpty ? "Connect to CalTopo Map" : "Switch CalTopo Map", systemImage: "map.fill")
                        .font(.headline)
                }
                .disabled(settings.teamID.isEmpty || settings.credentialID.isEmpty || settings.credentialSecret.isEmpty)
                if settings.teamID.isEmpty || settings.credentialID.isEmpty || settings.credentialSecret.isEmpty {
                    Text("Enter the CalTopo Teams account tuple above before browsing incident maps.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Section("CalTopo profiles") {
                LabeledContent(
                    "Active profile",
                    value: profileLifecycle.activeProfileID.isEmpty
                        ? "Not selected"
                        : profileLifecycle.activeProfileID
                )
                if let name = profileLifecycle.mutualAidDisplayName {
                    LabeledContent("Mutual aid", value: name)
                    if let expiresAt = profileLifecycle.mutualAidExpiresAt {
                        LabeledContent("Expires", value: expiresAt.formatted(date: .abbreviated, time: .shortened))
                    }
                } else {
                    Text("No mutual-aid profile installed")
                        .foregroundStyle(.secondary)
                }
                Text("Home credentials are preserved securely in Keychain. Imported mutual-aid access is removed at expiry and the app falls back to the home profile, matching Android.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Publishing") {
                Toggle("Enable live CalTopo publishing", isOn: $settings.enabled)
                TextField("Domain", text: $settings.domainAndPort)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Map ID", text: $settings.mapID)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Section("This device") {
                if managedDeviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    TextField("Device Name", text: $deviceName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                } else {
                    LabeledContent("Device Name", value: managedDeviceName)
                }
                Text(managedDeviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? "Used for this device's R2C map marker, Map Folders item, tracker identity, and local track metadata."
                    : "Assigned from the authenticated organization member and this device model. It is used consistently by RID2Caltopo, CalTopo, and r2c-tracker.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Video Streams") {
                Toggle("Restrict media server access", isOn: $restrictMediaServerAccess)
                Text("Accept controller RTMP streams while blocking direct media-server viewing from other devices. Playback and recording on this tablet and authorized R2C sharing remain available. Turn off only for trusted local viewers. Changing this setting restarts video.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Toggle("Capture Streams", isOn: $captureStreams)
                Text("When enabled, incoming streams are recorded as fMP4 under Files > RID2Caltopo > FlightStorage. Changing this setting restarts the local media server.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Toggle("Remote Video Control", isOn: $remoteVideoControlEnabled)
                Text("When enabled, an authenticated requester chooses video quality after the link test without a per-request approval prompt. Only one viewer can use this iPad at a time.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Stepper {
                    LabeledContent(
                        "Thumbnail Refresh",
                        value: "\(OperationalThumbnailRefreshInterval.formatted(thumbnailRefreshSeconds)) seconds"
                    )
                } onIncrement: {
                    thumbnailRefreshSeconds = OperationalThumbnailRefreshInterval.incremented(
                        thumbnailRefreshSeconds
                    )
                } onDecrement: {
                    thumbnailRefreshSeconds = OperationalThumbnailRefreshInterval.decremented(
                        thumbnailRefreshSeconds
                    )
                }
                Text("Defaults to 5.0 seconds. Shorter intervals use more battery and network data.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Traffic safety") {
                Toggle(
                    "Use tracker peers",
                    isOn: Binding(
                        get: { orgSettings.usePeers },
                        set: { orgSettings.setUsePeers($0) }
                    )
                )
                Text("Standalone flights stay independent. Live aircraft coordination requires an incident map. Organization access and archive uploads remain available.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Toggle(
                    "Proximity alerts: \(proximityAlerts.status)",
                    isOn: Binding(get: { proximityAlerts.consent.enabled }, set: { enabled in
                        if enabled { proximityAlerts.requestEnable() } else { proximityAlerts.disable() }
                    })
                )
                Text("Optional alerts based on received telemetry. No alert does not mean the airspace is clear.")
                    .font(.footnote).foregroundStyle(.secondary)
                Toggle("Alert scope: \(proximityAlerts.alertAllAircraft ? "All aircraft" : "Published only")",
                    isOn: Binding(get: { proximityAlerts.alertAllAircraft }, set: { proximityAlerts.setAlertAllAircraft($0) }))
                Text("Published only: pairs involving an aircraft claimed by this tablet. All aircraft: any received pair, including ignored or unconfirmed flights. This does not change recording or publishing.")
                    .font(.footnote).foregroundStyle(.secondary)
                Stepper(
                    "Proximity spacing: \(orgSettings.proximityAlertSpacingFeet) ft",
                    value: Binding(
                        get: { orgSettings.proximityAlertSpacingFeet },
                        set: { orgSettings.setProximityAlertSpacingFeet($0) }
                    ),
                    in: 50 ... 1_000
                )
                Stepper(
                    "Min Dist: \(orgSettings.minimumTrackDistanceFeet) ft",
                    value: Binding(
                        get: { orgSettings.minimumTrackDistanceFeet },
                        set: { orgSettings.setMinimumTrackDistanceFeet($0) }
                    ),
                    in: 2 ... 1_000
                )
                Stepper(
                    "New Track Delay: \(orgSettings.newTrackDelaySeconds) s",
                    value: Binding(
                        get: { orgSettings.newTrackDelaySeconds },
                        set: { orgSettings.setNewTrackDelaySeconds($0) }
                    ),
                    in: 1 ... 600
                )
                Picker("Minimum Location Accuracy", selection: $minimumHorizontalAccuracyCode) {
                    Text("30 m").tag(9)
                    Text("10 m").tag(10)
                    Text("3 m").tag(11)
                    Text("1 m").tag(12)
                }
                .pickerStyle(.menu)
                Text("RID positions less accurate than this threshold remain signal-only and are not added to the track.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Stepper(
                    "Bridge Check Distance: \(orgSettings.bridgeCheckDistanceFeet) ft",
                    value: Binding(
                        get: { orgSettings.bridgeCheckDistanceFeet },
                        set: { orgSettings.setBridgeCheckDistanceFeet($0) }
                    ),
                    in: 1 ... 1_000
                )
                Stepper(
                    "Max RID Idle Time: \(orgSettings.maximumIdleMinutes) min",
                    value: Binding(
                        get: { orgSettings.maximumIdleMinutes },
                        set: { orgSettings.setMaximumIdleMinutes($0) }
                    ),
                    in: 0 ... 1_440
                )
                Text("Track filtering, loss timing, bridge distance, proximity spacing, and maximum RID idle time use the same operator-adjustable controls as Android. Set Max RID Idle Time to 0 to disable automatic closing.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading) {
                    Text("Audio Alarm Volume: \(spokenWarnings.volumePercent)%")
                    Slider(
                        value: Binding(
                            get: { Double(spokenWarnings.volumePercent) },
                            set: { spokenWarnings.setVolumePercent(Int($0.rounded())) }
                        ),
                        in: 0 ... 100,
                        step: 5
                    )
                    Button("Audio Alarm Test") {
                        spokenWarnings.requestAudioAlarmTest()
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            Section("NOTAM / TFR") {
                Toggle("Enable FAA Facility Map / LAANC lookup", isOn: $airspace.enabled)
                Toggle("Refresh controlled airspace automatically", isOn: $airspace.autoRefresh)
                    .disabled(!airspace.enabled)
                Toggle("Enable nearby NOTAM / TFR monitoring", isOn: $notams.enabled)
                Toggle("Show NOTAMs on map", isOn: $notams.showOnMap)
                    .disabled(!notams.enabled)
                Toggle("Refresh automatically", isOn: $notams.autoRefresh)
                    .disabled(!notams.enabled)
                Stepper(
                    "NOTAM radius: \(notams.radiusStatuteMiles) statute " +
                        (notams.radiusStatuteMiles == 1 ? "mile" : "miles"),
                    value: $notams.radiusStatuteMiles,
                    in: 1 ... 100
                )
                    .disabled(!notams.enabled)
                Picker("Refresh interval", selection: $notams.refreshIntervalSeconds) {
                    Text("30 minutes").tag(1_800)
                    Text("60 minutes").tag(3_600)
                }
                .disabled(!notams.enabled || !notams.autoRefresh)
                LabeledContent(
                    "FAA proxy",
                    value: orgSettings.hasNotamAdminConfiguration
                        ? "Organization configured"
                        : "Not configured"
                )
                Text("Controlled-airspace status uses the public FAA UAS Facility Map. NOTAM proxy access is configured only by importing an r2c-tracker organization QR code.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Land / agency restrictions") {
                Toggle("Enable protected-land checks", isOn: $landRestrictions.enabled)
                Toggle("Show protected lands on map", isOn: $landRestrictions.showOnMap)
                    .disabled(!landRestrictions.enabled)
                Toggle("Refresh protected lands automatically", isOn: $landRestrictions.autoRefresh)
                    .disabled(!landRestrictions.enabled)
                Stepper(
                    "Boundary query radius: \(landRestrictions.radiusStatuteMiles) statute " +
                        (landRestrictions.radiusStatuteMiles == 1 ? "mile" : "miles"),
                    value: $landRestrictions.radiusStatuteMiles,
                    in: 1 ... 50
                )
                .disabled(!landRestrictions.enabled)
                Text("Checks National Park Service, National Wildlife Refuge, U.S. Forest Service wilderness, and Colorado Parks and Wildlife boundaries. Results distinguish land-use rules from FAA airspace restrictions and include agency follow-up links.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("External display") {
                Picker("Mode", selection: $externalDisplay.mode) {
                    ForEach(AppleExternalDisplayMode.allCases) { mode in Text(mode.label).tag(mode.rawValue) }
                }
                Picker("Content", selection: $externalDisplay.content) {
                    ForEach(AppleExternalDisplayContent.allCases) { content in Text(content.label).tag(content.rawValue) }
                }
                .disabled(externalDisplay.mode != AppleExternalDisplayMode.appManaged.rawValue)
                Picker("Alert routing", selection: $externalDisplay.alertRouting) {
                    ForEach(AppleExternalAlertRouting.allCases) { routing in Text(routing.label).tag(routing.rawValue) }
                }
                Toggle("Open automatically when connected", isOn: $externalDisplay.autoOpen)
                Toggle("Allow interaction", isOn: $externalDisplay.allowInteraction)
                    .disabled(externalDisplay.mode != AppleExternalDisplayMode.appManaged.rawValue)
                Text("OS mirroring uses the system display controls. App-managed mode presents the selected streams/map layout independently on an attached display.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Local storage") {
                NavigationLink {
                    AppleStorageManagementView(trackModel: trackModel)
                } label: {
                    Label("Manage Storage", systemImage: "externaldrive")
                }
                Text("Review dated folders, ages, and sizes before selecting anything to delete.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Advanced") {
                NavigationLink {
                    AppleDeveloperToolsView(
                        caltopo: settings,
                        organization: orgSettings,
                        locationProvider: locationProvider,
                        importer: importer,
                        identities: identityStore,
                        trackModel: trackModel,
                        proximityAlerts: proximityAlerts,
                        iCloudBackup: iCloudBackup
                    )
                } label: {
                    Label("Developer Tools", systemImage: "hammer")
                }
            }
            Section {
                Button("Save CalTopo Configuration") {
                    onSave(settings.save())
                }
                Text(settings.status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Settings")
        .alert(RidProximityConsent.title, isPresented: Binding(
            get: { proximityAlerts.consent.noticePending },
            set: { if !$0 { proximityAlerts.cancelEnable() } }
        )) {
            Button("Keep disabled", role: .cancel) { proximityAlerts.cancelEnable() }
            Button("Enable alerts") { proximityAlerts.confirmEnable() }
        } message: {
            Text(RidProximityConsent.notice)
        }
        .onDisappear { proximityAlerts.cancelEnable() }
        .sheet(isPresented: $showingTeamMaps) {
            CaltopoTeamMapBrowser(settings: settings) { map in
                orgSettings.setIncidentMapTitle(map.title)
                onSave(settings.selectMap(map))
                showingTeamMaps = false
            }
        }
    }
}

private struct AppleTrackerConfigurationSection: View {
    @ObservedObject var settings: AppleOrgConfigSettings
    @State private var trackerURL: String
    @State private var trackerAPIKey: String
    @State private var status = ""

    init(settings: AppleOrgConfigSettings) {
        self.settings = settings
        _trackerURL = State(initialValue: settings.trackerURLPrefix)
        _trackerAPIKey = State(initialValue: settings.trackerAPIKey)
    }

    var body: some View {
        Section("Tracker coordination") {
            TextField("Tracker URL", text: $trackerURL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            AppleScannableTextField(
                title: "Tracker API key",
                text: $trackerAPIKey,
                mode: .credential,
                secure: true
            )
            Button("Save Tracker Coordination") {
                do {
                    try settings.applyManualTrackerConfiguration(
                        trackerURLPrefix: trackerURL,
                        trackerAPIKey: trackerAPIKey
                    )
                    status = "Tracker coordination saved."
                } catch {
                    status = "Unable to save tracker API key: \(error.localizedDescription)"
                }
            }
            Text("Manual tracker values configure coordination only. Saving them clears the managed FAA-proxy association; import the r2c-tracker organization QR again to restore FAA proxy access.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            if !status.isEmpty {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(status.hasPrefix("Unable") ? .red : .secondary)
            }
        }
    }
}

private struct AppleMutualAidTupleSection: View {
    @ObservedObject var settings: AppleOrgConfigSettings
    @State private var teamID: String
    @State private var credentialID: String
    @State private var credentialSecret: String
    @State private var domainAndPort: String
    @State private var sourceLabel: String
    @State private var targetFolderHint: String
    @State private var connectKey: String
    @State private var status = ""

    init(settings: AppleOrgConfigSettings) {
        self.settings = settings
        let template = settings.mutualAidTemplate
        _teamID = State(initialValue: template?.teamID ?? "")
        _credentialID = State(initialValue: template?.credentialID ?? "")
        _credentialSecret = State(initialValue: template?.credentialSecret ?? "")
        _domainAndPort = State(initialValue: template?.domainAndPort ?? "caltopo.com")
        _sourceLabel = State(initialValue: template?.sourceLabel ?? settings.organizationName)
        _targetFolderHint = State(initialValue: template?.targetFolderHint ?? "MAI")
        _connectKey = State(initialValue: template?.connectKey ?? "")
    }

    var body: some View {
        Section("Mutual Aid Account") {
            AppleScannableTextField(
                title: "Team ID",
                text: $teamID,
                mode: .credential
            )
            AppleScannableTextField(
                title: "Credential ID",
                text: $credentialID,
                mode: .credential
            )
            AppleScannableTextField(
                title: "Credential secret",
                text: $credentialSecret,
                mode: .credential,
                secure: true
            )
            TextField("Domain and port", text: $domainAndPort)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Connect Key", text: $connectKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Source organization label", text: $sourceLabel)
            TextField("Target folder hint", text: $targetFolderHint)
            Button("Save Mutual Aid Account") {
                do {
                    try settings.apply(mutualAidTemplate: .init(
                        teamID: teamID.trimmingCharacters(in: .whitespacesAndNewlines),
                        credentialID: credentialID.trimmingCharacters(in: .whitespacesAndNewlines),
                        credentialSecret: credentialSecret.trimmingCharacters(in: .whitespacesAndNewlines),
                        domainAndPort: domainAndPort.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? "caltopo.com"
                            : domainAndPort.trimmingCharacters(in: .whitespacesAndNewlines),
                        sourceLabel: sourceLabel.trimmingCharacters(in: .whitespacesAndNewlines),
                        targetFolderHint: targetFolderHint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? "MAI"
                            : targetFolderHint.trimmingCharacters(in: .whitespacesAndNewlines),
                        connectKey: connectKey.trimmingCharacters(in: .whitespacesAndNewlines)
                    ))
                    status = "Mutual Aid account saved securely."
                } catch {
                    status = "Unable to save Mutual Aid account: \(error.localizedDescription)"
                }
            }
            Text("These values are accepted by ct_mutual_aid_credentials JSON. The Mutual Aid account may use the same Connect Key when both CalTopo teams share that key. Credential values are stored in Apple Keychain.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            if !status.isEmpty {
                Text(status)
                    .font(.footnote)
                    .foregroundStyle(status.hasPrefix("Unable") ? .red : .secondary)
            }
        }
    }
}

@MainActor
private final class AppleDeveloperToolsManager: ObservableObject {
    @Published var status = "Ready"
    @Published private(set) var exportURL: URL?
    @Published private(set) var isWorking = false

    func prepareOrgConfig(
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) async {
        isWorking = true
        exportURL = nil
        defer { isWorking = false }
        do {
            let ridMap: [String: Any] = [
                "type": "ct_ridmap",
                "file_version": "1.0",
                "load_type": "replace",
                "map": identities.importedMappings.map {
                    [
                        "remoteId": $0.remoteID,
                        "mappedId": $0.mappedID,
                        "org": $0.organization,
                        "model": $0.droneDescription,
                        "owner": $0.pilotCallsign,
                        "ownerName": $0.ownerName,
                        "ownerCallsign": $0.pilotCallsign,
                        "readiness": $0.readiness.dictionary,
                    ] as [String: Any]
                },
            ]
            var credentials: [String: Any] = [
                "type": "ct_credentials",
                "file_version": "1.0",
                "org_name": organization.organizationName,
                "team_id": caltopo.teamID,
                "credential_id": caltopo.credentialID,
                "credential_secret": caltopo.credentialSecret,
                "domain_and_port": caltopo.domainAndPort,
                "connect_key": caltopo.connectKey,
                "track_folder": organization.trackFolder,
                "incident": organization.incident,
                "op_period": organization.operationalPeriod,
                "tracker_enrollment_url": organization.trackerEnrollmentURL,
                "use_peers": organization.usePeers,
                "predictive_head_enabled": organization.predictiveHeadEnabled,
                "proximity_alert_spacing_feet": organization.proximityAlertSpacingFeet,
            ]
            credentials = credentials.filter { value in
                if let text = value.value as? String { return !text.isEmpty }
                return true
            }
            let credentialData = try JSONSerialization.data(
                withJSONObject: credentials,
                options: [.sortedKeys]
            )
            let credentialText = String(decoding: credentialData, as: UTF8.self)
            var configs: [[String: Any]] = [
                ridMap,
                [
                    "type": "ct_credentials_enc",
                    "enc": OrgConfigTokenCodec.encryptPayload(credentialText),
                ],
            ]
            if let template = organization.mutualAidTemplate {
                let mutualAid: [String: Any] = [
                    "type": "ct_mutual_aid_credentials",
                    "file_version": "1.0",
                    "team_id": template.teamID,
                    "credential_id": template.credentialID,
                    "credential_secret": template.credentialSecret,
                    "domain_and_port": template.domainAndPort,
                    "connect_key": template.connectKey,
                    "source_label": template.sourceLabel,
                    "target_folder_hint": template.targetFolderHint,
                ]
                let mutualAidData = try JSONSerialization.data(withJSONObject: mutualAid, options: [.sortedKeys])
                configs.append([
                    "type": "ct_credentials_enc",
                    "enc": OrgConfigTokenCodec.encryptPayload(String(decoding: mutualAidData, as: UTF8.self)),
                ])
            }
            let bundle: [String: Any] = [
                "format": "rid2caltopo_org_config",
                "version": 2,
                "org_name": organization.organizationName,
                "generated": ISO8601DateFormatter().string(from: Date()),
                "configs": configs,
            ]
            let data = try JSONSerialization.data(
                withJSONObject: bundle,
                options: [.sortedKeys]
            )
            let root = (FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory)
                .appendingPathComponent("RID2Caltopo/Exports", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let safeOrg = organization.organizationName
                .replacingOccurrences(of: "/", with: "-")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let name = safeOrg.isEmpty ? "RID2Caltopo_Org_Config.json" : "\(safeOrg)_Org_Config.json"
            let destination = root.appendingPathComponent(name)
            try data.write(to: destination, options: .atomic)
            exportURL = destination
            status = "Organization config prepared. Use Share Prepared Org Config to send the JSON file."
        } catch {
            status = "Organization export failed: \(error.localizedDescription)"
        }
    }

    func resetPersistedState(
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore,
        locationProvider: AppleLocationProvider,
        iCloudBackup: AppleICloudBackupCenter
    ) {
        if let bundleID = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundleID)
        }
        SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
        caltopo.resetPersistedState()
        organization.resetPersistedState()
        identities.resetPersistedState()
        AppleNotamCenter.shared.resetRuntimeState()
        locationProvider.clearLocationOverride()
        iCloudBackup.setEnabled(false, passphrase: "")
        status = "Persisted app state reset. Quit and reopen RID2Caltopo to rebuild all runtime settings."
        AppleLog.warning("DeveloperTools", "Persisted app state reset")
    }
}

private struct AppleDeveloperToolsView: View {
    @ObservedObject var caltopo: AppleCaltopoSettings
    @ObservedObject var organization: AppleOrgConfigSettings
    @ObservedObject var locationProvider: AppleLocationProvider
    @ObservedObject var importer: AppleOrgConfigImporter
    @ObservedObject var identities: AppleDroneConfirmationStore
    @ObservedObject var trackModel: RIDTrackViewModel
    @ObservedObject var proximityAlerts: AppleProximityAlertCenter
    let iCloudBackup: AppleICloudBackupCenter
    @StateObject private var manager = AppleDeveloperToolsManager()
    @State private var importingConfig = false
    @State private var locationText = ""
    @State private var locationError: String?
    @State private var recentDays = 2
    @State private var resubmitting = false
    @State private var showingResetConfirmation = false

    var body: some View {
        Form {
            Section("Configuration") {
                Button("Load Config File", systemImage: "doc.badge.plus") {
                    importingConfig = true
                }
                Button("Export Org Config", systemImage: "square.and.arrow.up") {
                    Task {
                        await manager.prepareOrgConfig(
                            caltopo: caltopo,
                            organization: organization,
                            identities: identities
                        )
                    }
                }
                if let exportURL = manager.exportURL {
                    ShareLink(item: exportURL) {
                        Label("Share Prepared Org Config", systemImage: "square.and.arrow.up")
                    }
                }
                Text(importer.statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Tracker archive") {
                Stepper("Recent days: \(recentDays)", value: $recentDays, in: 1 ... 30)
                Button(resubmitting ? "Resubmitting…" : "Resubmit Recent Tracks To Tracker") {
                    resubmitting = true
                    Task {
                        manager.status = await trackModel.resubmitRecentTracks(days: recentDays)
                        resubmitting = false
                    }
                }
                .disabled(resubmitting)
                Text("Clears the reported marker for the selected recent day folders and submits eligible team-drone tracks again.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Simulate MyLocation") {
                TextField("Latitude, longitude", text: $locationText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Apply Temporary Location") { applyLocationOverride() }
                Button("Clear Location Override", role: .destructive) {
                    locationProvider.clearLocationOverride()
                    locationText = ""
                    locationError = nil
                }
                .disabled(locationProvider.locationOverride == nil)
                if let override = locationProvider.locationOverride {
                    LabeledContent(
                        "Active override",
                        value: String(
                            format: "%.6f, %.6f",
                            override.coordinate.latitude,
                            override.coordinate.longitude
                        )
                    )
                }
                if let locationError {
                    Text(locationError).foregroundStyle(.red)
                }
                Text("The override is temporary and drives iOS airspace, NOTAM/TFR, protected-land, map-marker, and proximity checks until cleared or the app exits.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Peer coordination") {
                NavigationLink {
                    AppleProximityPairsView(proximityAlerts: proximityAlerts)
                } label: {
                    Label("Proximity Pairs", systemImage: "point.3.connected.trianglepath.dotted")
                }
                Toggle(
                    "Disable Peer Coordination",
                    isOn: Binding(
                        get: { !organization.usePeers },
                        set: { organization.setUsePeers(!$0) }
                    )
                )
                Text("Disable only for isolated testing. Multiple standalone instances can publish duplicate CalTopo updates.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            Section("Persistent state") {
                Button("Reset Persisted App State", role: .destructive) {
                    showingResetConfirmation = true
                }
                Text("Clears saved configuration, mappings, preferences, and app Keychain secrets. Local tracks, clues, logs, captured video, and cached maps are retained.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Status") {
                if manager.isWorking || resubmitting { ProgressView() }
                Text(manager.status).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Developer Tools")
        .fileImporter(
            isPresented: $importingConfig,
            allowedContentTypes: [.image, .json, .plainText, .data]
        ) { result in
            guard case let .success(url) = result else { return }
            Task {
                await importer.importFile(
                    url,
                    caltopoSettings: caltopo,
                    orgSettings: organization,
                    identityStore: identities
                )
            }
        }
        .alert("Reset Persisted App State?", isPresented: $showingResetConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Reset", role: .destructive) {
                manager.resetPersistedState(
                    caltopo: caltopo,
                    organization: organization,
                    identities: identities,
                    locationProvider: locationProvider,
                    iCloudBackup: iCloudBackup
                )
            }
        } message: {
            Text("This clears saved settings and credentials. Local operational files are retained. You must quit and reopen the app afterward.")
        }
        .onAppear {
            if let override = locationProvider.locationOverride {
                locationText = String(
                    format: "%.6f, %.6f",
                    override.coordinate.latitude,
                    override.coordinate.longitude
                )
            }
        }
    }

    private func applyLocationOverride() {
        let fields = locationText
            .split(separator: ",", maxSplits: 1)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard fields.count == 2,
              let latitude = Double(fields[0]),
              let longitude = Double(fields[1]),
              (-90 ... 90).contains(latitude),
              (-180 ... 180).contains(longitude)
        else {
            locationError = "Enter decimal latitude and longitude separated by a comma."
            return
        }
        locationProvider.setLocationOverride(latitude: latitude, longitude: longitude)
        locationError = nil
        manager.status = "Temporary MyLocation override applied."
    }

}

private struct AppleProximityPairsView: View {
    @ObservedObject var proximityAlerts: AppleProximityAlertCenter

    var body: some View {
        List {
            if proximityAlerts.pairs.isEmpty {
                ContentUnavailableView(
                    "No active drone pairs",
                    systemImage: "airplane",
                    description: Text(proximityAlerts.alertAllAircraft ? "Received aircraft pairs will appear here when at least two are active." : "Mapped aircraft pairs will appear here when at least two are active.")
                )
            } else {
                ForEach(proximityAlerts.pairs) { pair in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(pair.firstMappedID) ↔ \(pair.secondMappedID)")
                            .font(.headline)
                        Text(
                            "Horizontal \(feet(pair.horizontalFeet))  •  Vertical \(optionalFeet(pair.verticalFeet))  •  3D \(optionalFeet(pair.threeDimensionalFeet))"
                        )
                        .font(.subheadline)
                        .foregroundStyle(pair.alerting ? .red : .secondary)
                    }
                }
            }
        }
        .navigationTitle("Proximity Pairs")
    }

    private func feet(_ value: Double) -> String {
        String(format: "%.1f ft", value)
    }

    private func optionalFeet(_ value: Double?) -> String {
        value.map(feet) ?? "unknown"
    }
}

struct AppleArchiveCleanupView: View {
    @ObservedObject var trackModel: RIDTrackViewModel
    @State private var directories: [AppleArchiveDirectoryOption] = []
    @State private var selected: Set<String> = []
    @State private var loading = true
    @State private var deleting = false
    @State private var status: String?
    @State private var showingConfirmation = false
    @Environment(\.dismiss) private var dismiss

    private var selectedDirectories: [AppleArchiveDirectoryOption] {
        directories.filter { !$0.isToday && selected.contains($0.name) }
    }

    private var selectedSize: String {
        ArchiveFolderDisplay.size(selectedDirectories.reduce(0) { $0 + $1.byteCount })
    }

    var body: some View {
        List {
            if let status {
                Section { Text(status).foregroundStyle(.secondary) }
            }
            Section { AppleFlightStorageLimits() }
            Section("Flight folders • oldest first") {
                if loading {
                    HStack {
                        ProgressView()
                        Text("Scanning archive folders…")
                    }
                } else if directories.isEmpty {
                    ContentUnavailableView("No dated archive folders", systemImage: "archivebox")
                } else {
                    ForEach(directories) { directory in
                        HStack {
                            Button {
                                if selected.contains(directory.name) { selected.remove(directory.name) }
                                else { selected.insert(directory.name) }
                            } label: {
                                Image(systemName: selected.contains(directory.name) ? "checkmark.square" : "square")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Select \(directory.name)")
                            .accessibilityValue(selected.contains(directory.name) ? "Selected" : "Not selected")
                            .disabled(directory.isToday || deleting)
                            NavigationLink {
                                AppleFlightDirectoryBrowser(directory: AppleFlightStorage.root.appendingPathComponent(directory.name))
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(directory.name)
                                    Text("Age \(directory.ageLabel) • \(directory.sizeLabel)" + (directory.isToday ? " • protected" : ""))
                                        .font(.footnote).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            Section {
                Button("Delete Selected (\(selectedSize))", role: .destructive) {
                    showingConfirmation = true
                }
                .disabled(selectedDirectories.isEmpty || loading || deleting)
                Button("Cancel") { dismiss() }
                Text("Deletes selected daily flight folders, including logs, tracks, clues and video. Today and active folders are protected.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Delete Flight Storage")
        .task { await loadDirectories() }
        .refreshable { await loadDirectories() }
        .confirmationDialog(
            "Confirm Archive Deletion",
            isPresented: $showingConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                Task { await deleteSelectedDirectories() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Permanently delete \(selectedDirectories.count) archive folder\(selectedDirectories.count == 1 ? "" : "s") totaling \(selectedSize)?")
        }
    }

    @MainActor
    private func loadDirectories() async {
        loading = true
        directories = await trackModel.localArchiveDirectories()
        selected.formIntersection(directories.filter { !$0.isToday }.map(\.name))
        loading = false
    }

    @MainActor
    private func deleteSelectedDirectories() async {
        let names = Set(selectedDirectories.map(\.name))
        guard !names.isEmpty else { return }
        deleting = true
        status = await trackModel.deleteLocalArchiveDirectories(names)
        selected.removeAll()
        directories = await trackModel.localArchiveDirectories()
        deleting = false
    }
}

struct CaltopoTeamMapBrowser: View {
    @ObservedObject var settings: AppleCaltopoSettings
    let onSelect: (CaltopoTeamMap) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var navigationStack: [[CaltopoTeamMapNode]] = []
    @State private var search = ""

    private var credentialsReady: Bool {
        !settings.teamID.isEmpty &&
            !settings.credentialID.isEmpty &&
            !settings.credentialSecret.isEmpty
    }

    private var currentItems: [CaltopoTeamMapNode] {
        navigationStack.last ?? settings.teamMaps
    }

    private var filteredItems: [CaltopoTeamMapNode] {
        guard !search.isEmpty else { return currentItems }
        return currentItems.filter { $0.title.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if settings.isLoadingTeamMaps {
                    ProgressView("Loading team maps…")
                } else if settings.teamMaps.isEmpty {
                    ContentUnavailableView(
                        "Team Maps Unavailable",
                        systemImage: "map",
                        description: Text(settings.status)
                    )
                } else {
                    List {
                        if !navigationStack.isEmpty {
                            Button {
                                _ = navigationStack.popLast()
                                search = ""
                            } label: {
                                Label("Back", systemImage: "chevron.left")
                            }
                        }
                        ForEach(filteredItems) { node in
                            Button {
                                if let children = node.children {
                                    navigationStack.append(children)
                                    search = ""
                                } else if let map = node.map {
                                    onSelect(map)
                                }
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: node.children == nil ? "mappin.and.ellipse" : "folder.fill")
                                        .foregroundStyle(node.children == nil ? Color.accentColor : Color.orange)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(node.title).foregroundStyle(.primary)
                                        if let map = node.map {
                                            Text(map.updatedMilliseconds > 0
                                                 ? Date(timeIntervalSince1970: Double(map.updatedMilliseconds) / 1_000).formatted()
                                                 : "Date unknown")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                    if node.children != nil { Image(systemName: "chevron.right").foregroundStyle(.secondary) }
                                }
                            }
                        }
                    }
                    .searchable(text: $search, prompt: "Search maps")
                }
            }
            .navigationTitle("Team Maps")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button { Task { await settings.loadTeamMaps() } } label: { Image(systemName: "arrow.clockwise") }
                        .disabled(settings.isLoadingTeamMaps)
                }
            }
            .task {
                if settings.teamMaps.isEmpty { await settings.loadTeamMaps() }
            }
            .onChange(of: credentialsReady) { wasReady, isReady in
                guard !wasReady, isReady,
                      settings.teamMaps.isEmpty,
                      !settings.isLoadingTeamMaps
                else { return }
                Task { await settings.loadTeamMaps() }
            }
        }
    }
}


struct AppleStorageManagementView: View {
    @ObservedObject var trackModel: RIDTrackViewModel
    @ObservedObject private var maps = AppleMapOfflineManager.shared
    @State private var used: Int64?
    @State private var storageStatus = ""
    var body: some View {
        List {
            NavigationLink {
                AppleStorageCacheView(manager: maps)
            } label: {
                Text("Map & Terrain Cache: \(gb(maps.cacheStats.bytes)), Max Size: \(gb(Int64(maps.maximumCacheGB * 1_000_000_000))), Max Age: \(maps.maximumTileAgeDays) days")
            }
            NavigationLink {
                AppleArchiveCleanupView(trackModel: trackModel)
            } label: {
                Text("Flight Storage: \(used.map(gb) ?? "Scanning…"), Max Size: \(gb(AppleFlightStorage.maximumBytes)), Max Age: \(AppleFlightStorage.maximumDays) days")
            }
            if !storageStatus.isEmpty { Text(storageStatus).font(.footnote) }
        }
        .navigationTitle("Manage Storage")
        .task {
            maps.refreshStats()
            let snapshot = await Task.detached { AppleFlightStorage.maintain(purge: false) }.value
            used = snapshot.used
            storageStatus = snapshot.blocked ? snapshot.message : "Cleanup runs at startup and on demand near 90% of the allowance. Today and active folders are protected."
        }
    }
    private func gb(_ bytes: Int64) -> String { String(format: "%.2f GB", Double(bytes) / 1_000_000_000) }
}

struct AppleFlightStorageLimits: View {
    @AppStorage("flight.maximumGB") private var maximumGB = FlightStoragePolicy.defaultGB
    @AppStorage("flight.maximumDays") private var maximumDays = FlightStoragePolicy.defaultDays
    @State private var size = ""
    @State private var days = ""
    @State private var message = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("Max Size (GB)", text: $size).keyboardType(.decimalPad)
            TextField("Max Age (days)", text: $days).keyboardType(.numberPad)
            Button("Save Limits") {
                guard let gb = Double(size), gb.isFinite, (0.1...1000).contains(gb),
                      let age = Int(days), (1...3650).contains(age) else {
                    message = "Enter 0.1–1,000 GB and 1–3,650 days."; return
                }
                maximumGB = gb; maximumDays = age
                AppleFlightStorage.requestCheck()
                message = "Saved. Automatic cleanup applies these limits."
            }
            Text(message.isEmpty ? "Default allowance: 10 GB. At 90% usage, cleanup removes older eligible folders to restore 10% free. If that is not possible, increase the allowance." : message)
                .font(.footnote).foregroundStyle(.secondary)
        }
        .onAppear { size = String(format: "%.1f", maximumGB); days = String(maximumDays) }
    }
}

struct AppleFlightDirectoryBrowser: View {
    let directory: URL
    @State private var entries: [URL] = []
    @State private var preview: URL?
    var body: some View {
        List(entries, id: \.self) { url in
            if (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
                NavigationLink(url.lastPathComponent) { AppleFlightDirectoryBrowser(directory: url) }
            } else {
                Button(url.lastPathComponent) { preview = url }
            }
        }
        .navigationTitle(directory.lastPathComponent)
        .quickLookPreview($preview)
        .task {
            entries = await Task.detached {
                ((try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])) ?? []).sorted { $0.lastPathComponent < $1.lastPathComponent }
            }.value
        }
    }
}
