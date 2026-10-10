import WebKit
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
    @ObservedObject var bridgeAlerts: AppleDroneScoutBridgeAlertCenter
    let iCloudBackup: AppleICloudBackupCenter
    var startAtProximity = false
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
    @State private var teamsDraft = OperationalTeamsCredentialDraft()
    @State private var initialTeamsDraft = OperationalTeamsCredentialDraft()
    @State private var initialTrackerURL = ""
    @State private var initialTrackerKey = ""
    @State private var draftInitialized = false
    @State private var teamsSaveMessage = ""
    @StateObject private var draft = AppleSettingsDraft()
    @StateObject private var deviceNaming = AppleTrackerDeviceReconciliation()
    @Environment(\.dismiss) private var dismissSettings
    @State private var showUnsavedSettings = false
    @State private var trackerURLDraft = ""
    @State private var trackerKeyDraft = ""
    @State private var localConsent = RidProximityConsent()
    @State private var consentAtOpen = false
    @State private var publishingMessage = ""
    @Environment(\.colorScheme) private var colorScheme
    @State private var showingTeamMaps = false
    @State private var showingIncidentSelection = false
    @State private var connectMapAfterIncidentSelection = false

    private var hasChanges: Bool {
        draft.edits.hasChanges || teamsDraft.normalized != initialTeamsDraft.normalized ||
            trackerURLDraft != initialTrackerURL || trackerKeyDraft != initialTrackerKey ||
            localConsent.enabled != consentAtOpen
    }
    private func requestLeave() {
        if hasChanges { showUnsavedSettings = true } else { dismissSettings() }
    }
    private func saveAndClose() {
        if let error = teamsDraft.validationMessage { teamsSaveMessage = error; return }
        do {
            if teamsDraft.normalized != initialTeamsDraft.normalized { try settings.saveTeamsCredentials(teamsDraft) }
            if trackerURLDraft != initialTrackerURL || trackerKeyDraft != initialTrackerKey {
                try orgSettings.applyManualTrackerConfiguration(trackerURLPrefix: trackerURLDraft, trackerAPIKey: trackerKeyDraft)
            }
            draft.edits.commit()
            if localConsent.enabled != consentAtOpen {
                if localConsent.enabled {
                    // Every paragraph was acknowledged in the local draft before this Save.
                    proximityAlerts.requestEnable()
                    RidProximityConsent.noticeParagraphs.indices.forEach { proximityAlerts.toggleAcknowledgment($0) }
                    proximityAlerts.confirmEnable()
                } else { proximityAlerts.disable() }
            }
            onSave(settings.configuration)
            dismissSettings()
        } catch { teamsSaveMessage = "Unable to save Settings: \(error.localizedDescription)" }
    }

    var body: some View {
        ScrollViewReader { scroll in
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
                SettingsTextField(
                    "Organization designator",
                    text: draft.binding("orgSettings.organizationName", Binding(
                        get: { orgSettings.organizationName },
                        set: { orgSettings.setOrganizationNameForRidMappings($0) }
                    ))
                )
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                SettingsTextField(
                    "CalTopo track folder",
                    text: draft.binding("orgSettings.trackFolder", Binding(
                        get: { orgSettings.trackFolder },
                        set: { orgSettings.setTrackFolder($0) }
                    ))
                )
                Button { showingIncidentSelection = true } label: {
                    LabeledContent("Incident", value: OperationalIncidentSelection.name(mapID: settings.mapID,
                        mapTitle: settings.mapTitle, standaloneName: orgSettings.standaloneIncidentName))
                }
                SettingsTextField(
                    "Operational period",
                    text: draft.binding("orgSettings.operationalPeriod", Binding(
                        get: { orgSettings.operationalPeriod },
                        set: { orgSettings.setOperationalPeriod($0) }
                    ))
                )
                Text("Defaults used for this organization and operation. Tap a field title for help.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("CalTopo Teams account") {
                SettingsScannableTextField(
                    title: "Team ID",
                    text: $teamsDraft.teamID,
                    mode: .credential
                )
                SettingsScannableTextField(
                    title: "Credential ID",
                    text: $teamsDraft.credentialID,
                    mode: .credential
                )
                SettingsScannableTextField(
                    title: "Credential secret",
                    text: $teamsDraft.secret,
                    mode: .credential,
                    secure: true
                )
                SettingsScannableTextField(
                    title: "Connect Key",
                    text: $teamsDraft.connectKey,
                    mode: .credential
                )
                SettingsTextField("Domain", text: $teamsDraft.domain)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if settings.usesPersonalCredentials {
                    Text("Personal sign-in is active. These optional Teams credentials are separate; they do not change your personal sign-in or selected map.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Text("Save applies these settings locally on this device; the credential secret is stored in Keychain. File export and cloud backups are managed separately. Personal sign-in does not need Teams credentials.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Tracker coordination") {
                SettingsTextField("Tracker URL", text: $trackerURLDraft)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                SettingsScannableTextField(title: "Tracker API key", text: $trackerKeyDraft, mode: .credential, secure: true)
                Text("Changing tracker credentials on Save clears the managed FAA-proxy association. Import the organization QR again to restore FAA proxy access.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Incident map") {
                if settings.mapID.isEmpty {
                    Label("No CalTopo map selected", systemImage: "map")
                        .foregroundStyle(.secondary)
                } else {
                    SettingsValue("Connected Map", value: settings.mapTitle.isEmpty ? settings.mapID : settings.mapTitle)
                    SettingsValue("Map ID", value: settings.mapID)
                }
                Button { showingIncidentSelection = true } label: {
                    Label("Select Incident…", systemImage: "map.fill").font(.headline)
                }
                if settings.teamID.isEmpty || settings.credentialID.isEmpty || settings.credentialSecret.isEmpty {
                    Text("Personal sign-in lets you browse and publish to maps you can access. Teams credentials are optional.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Section("CalTopo access") {
                SettingsValue("Active profile", value: settings.usesPersonalCredentials
                    ? "Personal: \(settings.personalUsername.isEmpty ? "Sign in" : settings.personalUsername)"
                    : (settings.credentialID.isEmpty ? "No Teams credentials" : "Teams"))
                Text("Use personal sign-in or a Teams profile to access a map. Selecting credentials does not select a destination map.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Publishing") {
                SettingsToggle("Enable live CalTopo publishing", isOn: draft.binding("settings.enabled", Binding(
                    get: { settings.enabled },
                    set: { value in
                        _ = settings.setPublishingEnabled(value)
                    }
                )))
                Text("Changes apply when you Save. Select a map and personal or organization access through Select Incident.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("This device") {
                if managedDeviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    SettingsTextField("Device Name", text: draft.binding("deviceName", $deviceName))
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                } else {
                    SettingsValue("Device Name", value: managedDeviceName)
                    Button("Rename device") {
                        deviceNaming.beginNaming(
                            baseURL: UserDefaults.standard.string(forKey: "org.trackerURLPrefix") ?? "",
                            token: AppleOrgConfigSettings.loadTrackerAPIKey() ?? "")
                    }
                }
                Text(managedDeviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? "Used for this device's R2C map marker, Map Folders item, tracker identity, and local track metadata."
                    : "Saved with this device’s Tracker authorization. It is used consistently by RID2Caltopo, CalTopo, and r2c-tracker.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Video Streams") {
                SettingsToggle("Restrict media server access", isOn: draft.binding("restrictMediaServerAccess", $restrictMediaServerAccess))
                Text("Accept controller RTMP streams while blocking direct media-server viewing from other devices. Playback and recording on this tablet and authorized R2C sharing remain available. Turn off only for trusted local viewers. Changing this setting restarts video.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                SettingsToggle("Capture Streams", isOn: draft.binding("captureStreams", $captureStreams))
                Text("When enabled, incoming streams are recorded as fMP4 under Files > RID2Caltopo > FlightStorage. Changing this setting restarts the local media server.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                SettingsToggle("Remote Video Control", isOn: draft.binding("remoteVideoControlEnabled", $remoteVideoControlEnabled))
                Text("When enabled, an authenticated requester chooses video quality after the link test without a per-request approval prompt. Only one viewer can use this iPad at a time.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Stepper {
                    SettingsValue(
                        "Thumbnail & LiveTrack update interval",
                        value: "\(OperationalThumbnailRefreshInterval.formatted(draft.value("thumbnailRefreshSeconds", thumbnailRefreshSeconds))) seconds"
                    )
                } onIncrement: {
                    draft.binding("thumbnailRefreshSeconds", $thumbnailRefreshSeconds).wrappedValue = OperationalThumbnailRefreshInterval.incremented(
                        draft.value("thumbnailRefreshSeconds", thumbnailRefreshSeconds)
                    )
                } onDecrement: {
                    draft.binding("thumbnailRefreshSeconds", $thumbnailRefreshSeconds).wrappedValue = OperationalThumbnailRefreshInterval.decremented(
                        draft.value("thumbnailRefreshSeconds", thumbnailRefreshSeconds)
                    )
                }
                Text("Minimum time between thumbnail refreshes and LiveTrack updates. Default 5.0 seconds; lower values update more often and use more battery and data.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Bridge warnings") {
                SettingsToggle("Bridge audio warnings", isOn: draft.binding("binding4", Binding(
                    get: { !bridgeAlerts.audioMuted },
                    set: { bridgeAlerts.setAudioMuted(!$0) }
                )))
                Text("Enabled when the app starts. Turning this off silences bridge warnings for this app session only.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Traffic safety") {
                SettingsToggle(
                    "Use tracker peers",
                    isOn: draft.binding("orgSettings.usePeers", Binding(
                        get: { orgSettings.usePeers },
                        set: { orgSettings.setUsePeers($0) }
                    ))
                )
                Text("Standalone flights stay independent. Live aircraft coordination requires an incident map. Organization access and archive uploads remain available.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Proximity Alerts") {
                SettingsToggle(
                    "Proximity alerts: \(localConsent.enabled ? "On" : "Off")",
                    isOn: Binding(get: { localConsent.enabled }, set: { enabled in
                        if enabled { localConsent.requestEnable() } else { localConsent.disable() }
                    })
                )

                Text("Optional alerts based on received telemetry. No alert does not mean the airspace is clear.")
                    .font(.footnote).foregroundStyle(.secondary)
                SettingsToggle("Alert scope: \(draft.value("proximityAlerts.alertAllAircraft", proximityAlerts.alertAllAircraft) ? "All aircraft" : "Published only")",
                    isOn: draft.binding("proximityAlerts.alertAllAircraft", Binding(get: { proximityAlerts.alertAllAircraft }, set: { proximityAlerts.setAlertAllAircraft($0) })))
                Text("Published only: pairs involving an aircraft claimed by this tablet. All aircraft: any received pair, including ignored or unconfirmed flights. This does not change recording or publishing.")
                    .font(.footnote).foregroundStyle(.secondary)
                SettingsStepper(
                    "Proximity spacing: \(draft.value("orgSettings.proximityAlertSpacingFeet", orgSettings.proximityAlertSpacingFeet)) ft",
                    value: draft.binding("orgSettings.proximityAlertSpacingFeet", Binding(
                        get: { orgSettings.proximityAlertSpacingFeet },
                        set: { orgSettings.setProximityAlertSpacingFeet($0) }
                    )),
                    in: 50 ... 1_000
                )
            }
            .id("proximity-settings")
            Section("Tracking and device") {
                SettingsStepper(
                    "Min Dist: \(draft.value("orgSettings.minimumTrackDistanceFeet", orgSettings.minimumTrackDistanceFeet)) ft",
                    value: draft.binding("orgSettings.minimumTrackDistanceFeet", Binding(
                        get: { orgSettings.minimumTrackDistanceFeet },
                        set: { orgSettings.setMinimumTrackDistanceFeet($0) }
                    )),
                    in: 2 ... 1_000
                )
                SettingsStepper(
                    "New Track Delay: \(draft.value("orgSettings.newTrackDelaySeconds", orgSettings.newTrackDelaySeconds)) s",
                    value: draft.binding("orgSettings.newTrackDelaySeconds", Binding(
                        get: { orgSettings.newTrackDelaySeconds },
                        set: { orgSettings.setNewTrackDelaySeconds($0) }
                    )),
                    in: 1 ... 600
                )
                SettingsPicker("Minimum Location Accuracy", selection: draft.binding("minimumHorizontalAccuracyCode", $minimumHorizontalAccuracyCode)) {
                    Text("30 m").tag(9)
                    Text("10 m").tag(10)
                    Text("3 m").tag(11)
                    Text("1 m").tag(12)
                }
                .pickerStyle(.menu)
                Text("RID positions less accurate than this threshold remain signal-only and are not added to the track.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                SettingsStepper(
                    "Bridge Check Distance: \(draft.value("orgSettings.bridgeCheckDistanceFeet", orgSettings.bridgeCheckDistanceFeet)) ft",
                    value: draft.binding("orgSettings.bridgeCheckDistanceFeet", Binding(
                        get: { orgSettings.bridgeCheckDistanceFeet },
                        set: { orgSettings.setBridgeCheckDistanceFeet($0) }
                    )),
                    in: 1 ... 1_000
                )
                SettingsStepper(
                    "Max Idle Time: \(draft.value("orgSettings.maximumIdleMinutes", orgSettings.maximumIdleMinutes)) min",
                    value: draft.binding("orgSettings.maximumIdleMinutes", Binding(
                        get: { orgSettings.maximumIdleMinutes },
                        set: { orgSettings.setMaximumIdleMinutes($0) }
                    )),
                    in: 0 ... 1_440
                )
                Text("RID messages and user interaction reset the idle timer. Set Max Idle Time to 0 to disable automatic closing.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading) {
                    SettingsHelpLabel("Audio Alarm Volume: \(Int(draft.value("spokenWarnings.volumePercent", Double(spokenWarnings.volumePercent))))%")
                    Slider(
                        value: draft.binding("spokenWarnings.volumePercent", Binding(
                            get: { Double(spokenWarnings.volumePercent) },
                            set: { spokenWarnings.setVolumePercent(Int($0.rounded())) }
                        )),
                        in: 0 ... 100,
                        step: 5
                    )
                    Button("Audio Alarm Test (saved volume)") {
                        spokenWarnings.requestAudioAlarmTest()
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            Section("NOTAM / TFR") {
                SettingsToggle("Enable FAA Facility Map / LAANC lookup", isOn: draft.binding("airspace.enabled", $airspace.enabled))
                SettingsToggle("Refresh controlled airspace automatically", isOn: draft.binding("airspace.autoRefresh", $airspace.autoRefresh))
                    .disabled(!airspace.enabled)
                SettingsToggle("Enable nearby NOTAM / TFR monitoring", isOn: draft.binding("notams.enabled", $notams.enabled))
                SettingsToggle("Show NOTAMs on map", isOn: draft.binding("notams.showOnMap", $notams.showOnMap))
                    .disabled(!draft.value("notams.enabled", notams.enabled))
                SettingsToggle("Refresh automatically", isOn: draft.binding("notams.autoRefresh", $notams.autoRefresh))
                    .disabled(!draft.value("notams.enabled", notams.enabled))
                SettingsStepper(
                    "NOTAM radius: \(draft.value("notams.radiusStatuteMiles", notams.radiusStatuteMiles)) statute " +
                        (notams.radiusStatuteMiles == 1 ? "mile" : "miles"),
                    value: draft.binding("notams.radiusStatuteMiles", $notams.radiusStatuteMiles),
                    in: 1 ... 100
                )
                    .disabled(!draft.value("notams.enabled", notams.enabled))
                SettingsPicker("Refresh interval", selection: draft.binding("notams.refreshIntervalSeconds", $notams.refreshIntervalSeconds)) {
                    Text("30 minutes").tag(1_800)
                    Text("60 minutes").tag(3_600)
                }
                .disabled(!notams.enabled || !notams.autoRefresh)
                SettingsValue(
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
                SettingsToggle("Enable protected-land checks", isOn: draft.binding("landRestrictions.enabled", $landRestrictions.enabled))
                SettingsToggle("Show protected lands on map", isOn: draft.binding("landRestrictions.showOnMap", $landRestrictions.showOnMap))
                    .disabled(!draft.value("landRestrictions.enabled", landRestrictions.enabled))
                SettingsToggle("Refresh protected lands automatically", isOn: draft.binding("landRestrictions.autoRefresh", $landRestrictions.autoRefresh))
                    .disabled(!draft.value("landRestrictions.enabled", landRestrictions.enabled))
                SettingsStepper(
                    "Boundary query radius: \(draft.value("landRestrictions.radiusStatuteMiles", landRestrictions.radiusStatuteMiles)) statute " +
                        (landRestrictions.radiusStatuteMiles == 1 ? "mile" : "miles"),
                    value: draft.binding("landRestrictions.radiusStatuteMiles", $landRestrictions.radiusStatuteMiles),
                    in: 1 ... 50
                )
                .disabled(!draft.value("landRestrictions.enabled", landRestrictions.enabled))
                Text("Checks National Park Service, National Wildlife Refuge, U.S. Forest Service wilderness, and Colorado Parks and Wildlife boundaries. Results distinguish land-use rules from FAA airspace restrictions and include agency follow-up links.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("External display") {
                SettingsPicker("Mode", selection: draft.binding("externalDisplay.mode", $externalDisplay.mode)) {
                    ForEach(AppleExternalDisplayMode.allCases) { mode in Text(mode.label).tag(mode.rawValue) }
                }
                SettingsPicker("Content", selection: draft.binding("externalDisplay.content", $externalDisplay.content)) {
                    ForEach(AppleExternalDisplayContent.allCases) { content in Text(content.label).tag(content.rawValue) }
                }
                .disabled(draft.value("externalDisplay.mode", externalDisplay.mode) != AppleExternalDisplayMode.appManaged.rawValue)
                SettingsPicker("Alert routing", selection: draft.binding("externalDisplay.alertRouting", $externalDisplay.alertRouting)) {
                    ForEach(AppleExternalAlertRouting.allCases) { routing in Text(routing.label).tag(routing.rawValue) }
                }
                SettingsToggle("Open automatically when connected", isOn: draft.binding("externalDisplay.autoOpen", $externalDisplay.autoOpen))
                SettingsToggle("Allow interaction", isOn: draft.binding("externalDisplay.allowInteraction", $externalDisplay.allowInteraction))
                    .disabled(draft.value("externalDisplay.mode", externalDisplay.mode) != AppleExternalDisplayMode.appManaged.rawValue)
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

        }
        .onAppear {
            guard !draftInitialized else { return }
            draftInitialized = true
            teamsDraft = settings.teamsCredentialDraft
            initialTeamsDraft = teamsDraft
            initialTrackerURL = orgSettings.trackerURLPrefix
            initialTrackerKey = orgSettings.trackerAPIKey
            trackerURLDraft = orgSettings.trackerURLPrefix
            trackerKeyDraft = orgSettings.trackerAPIKey
            localConsent = proximityAlerts.consent
            consentAtOpen = localConsent.enabled
        }
        .task {
            guard startAtProximity else { return }
            // Form creates its rows lazily; wait for the sheet's initial layout.
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            scroll.scrollTo("proximity-settings", anchor: .top)
        }
        }
        .modifier(TrackerDeviceReconciliationModifier(
            model: deviceNaming, signInRequired: { _ in }, authorizationRejected: {},
            restored: { onSave(settings.configuration) }))
        .navigationTitle("Settings")
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) { Button("Back", action: requestLeave) }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                Text("Save applies changes. Cancel discards edits. Map connection, sign-in, file deletion, and tests are separate actions.")
                    .font(.footnote).foregroundStyle(.secondary)
                if !teamsSaveMessage.isEmpty { Text(teamsSaveMessage).foregroundStyle(.red).font(.footnote) }
                HStack {
                    Button("Cancel") { draft.edits.discard(); dismissSettings() }.buttonStyle(.bordered)
                    Spacer()
                    Button("Save", action: saveAndClose).buttonStyle(.borderedProminent)
                }
            }.padding().background(.bar)
        }
        .background(SettingsDismissGuard(hasChanges: hasChanges, onAttempt: requestLeave))
        .confirmationDialog("Unsaved Settings", isPresented: $showUnsavedSettings, titleVisibility: .visible) {
            Button("Save Changes", action: saveAndClose)
            Button("Discard Changes", role: .destructive) { draft.edits.discard(); dismissSettings() }
            Button("Keep Editing", role: .cancel) {}
        } message: { Text("Save your changes, discard them, or keep editing?") }
        .sheet(isPresented: Binding(get: { localConsent.noticePending }, set: { if !$0 { localConsent.cancel() } })) {
            SettingsDraftProximityConsent(consent: $localConsent)
        }
        .sheet(isPresented: $showingIncidentSelection, onDismiss: {
            if connectMapAfterIncidentSelection { connectMapAfterIncidentSelection = false; showingTeamMaps = true }
        }) {
            IncidentSelectionPanel(currentName: OperationalIncidentSelection.name(mapID: settings.mapID,
                mapTitle: settings.mapTitle, standaloneName: orgSettings.standaloneIncidentName),
                initialName: orgSettings.standaloneIncidentName,
                onConnectMap: { connectMapAfterIncidentSelection = true; showingIncidentSelection = false },
                onUseName: { name in
                    orgSettings.setIncident(name)
                    onSave(settings.disconnectMap())
                    showingIncidentSelection = false
                })
        }
        .sheet(isPresented: $showingTeamMaps) {
            CaltopoTeamMapBrowser(settings: settings, onCredentialSelect: { profileID in
                onSave(settings.disconnectMap())
                settings.usesPersonalCredentials = profileID == "personal"
                if profileID != "personal" {
                    _ = importer.activateProfile(profileID, caltopoSettings: settings, orgSettings: orgSettings)
                    onSave(settings.configuration)
                }
            }) { map in
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
            SettingsTextField("Tracker URL", text: $trackerURL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SettingsScannableTextField(
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
            let configs: [[String: Any]] = [
                ridMap,
                [
                    "type": "ct_credentials_enc",
                    "enc": OrgConfigTokenCodec.encryptPayload(credentialText),
                ],
            ]
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
    ) async {
        isWorking = true
        defer { isWorking = false }
        await PersonalProbeModel.shared.clearPersistedLogin()
        await WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
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
            Section("Experiments") {
                NavigationLink("Personal CalTopo Login") {
                    AppleCaltopoPersonalProbeView()
                }
            }
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
                SettingsTextField("Latitude, longitude", text: $locationText)
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
                Task {
                await manager.resetPersistedState(
                    caltopo: caltopo,
                    organization: organization,
                    identities: identities,
                    locationProvider: locationProvider,
                    iCloudBackup: iCloudBackup
                )
                }
            }
        } message: {
            Text("This clears saved settings, credentials, browser logins, and acknowledgement acceptance. Local operational files are retained. You must quit and reopen the app afterward.")
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
                                    Text(ArchiveFolderDisplay.detail(
                                        age: directory.ageLabel,
                                        size: directory.sizeLabel,
                                        protectionReason: directory.protectionReason,
                                        unuploadedClueCount: directory.unuploadedClueCount
                                    ))
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
            Text(ArchiveFolderDisplay.deleteConfirmation(
                folderCount: selectedDirectories.count,
                sizeLabel: selectedSize,
                unuploadedClueCount: selectedDirectories.reduce(0) { $0 + $1.unuploadedClueCount }
            ))
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
    var onCredentialSelect: ((String) -> Void)? = nil
    @ObservedObject private var profiles = AppleCaltopoProfileLifecycle.shared
    @State private var showPersonalLogin = false
    @State private var showWebsite = false
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
        navigationStack.last ?? (settings.usesPersonalCredentials ? settings.personalMaps : settings.teamMaps)
    }

    private var filteredItems: [CaltopoTeamMapNode] {
        guard !search.isEmpty else { return currentItems }
        func maps(_ nodes: [CaltopoTeamMapNode]) -> [CaltopoTeamMapNode] {
            nodes.flatMap { node in
                switch node { case .directory(_, _, let children): return maps(children); case .map: return [node] }
            }
        }
        return maps(settings.usesPersonalCredentials ? settings.personalMaps : settings.teamMaps)
            .filter { $0.title.localizedCaseInsensitiveContains(search) }
    }

    private func loadSelectedMaps() async {
        if settings.usesPersonalCredentials {
            if !(await settings.loadPersonalMaps()) { showPersonalLogin = true }
        } else { await settings.loadTeamMaps() }
    }

    var body: some View {
        NavigationStack {
            Group {
                if settings.isLoadingTeamMaps || settings.isLoadingPersonalMaps {
                    ProgressView(settings.isLoadingPersonalMaps ? "Loading personal maps…" : "Loading team maps…")
                } else if (settings.usesPersonalCredentials ? settings.personalMaps : settings.teamMaps).isEmpty {
                    ContentUnavailableView(
                        "Maps Unavailable",
                        systemImage: "map",
                        description: Text(settings.usesPersonalCredentials ? settings.personalMapsStatus : settings.status)
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
            .safeAreaInset(edge: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Menu {
                        Button("Personal: \(settings.personalUsername.isEmpty ? "Sign in" : settings.personalUsername)") { onCredentialSelect?("personal") }
                        Button("Edit personal account", systemImage: "pencil") { showWebsite = true }
                        ForEach(profiles.availableProfiles) { profile in
                            Button(profile.credentialLabel) { onCredentialSelect?(profile.id) }
                        }
                    } label: {
                        Label("Credentials: \(settings.usesPersonalCredentials ? "Personal: " + settings.personalUsername : profiles.activeCredentialLabel)", systemImage: "person.crop.circle.badge.checkmark")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .background(.background)
            }
            .navigationTitle("Select Map")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button { Task { await loadSelectedMaps() } } label: { Image(systemName: "arrow.clockwise") }
                        .disabled(settings.isLoadingTeamMaps)
                }
            }
            .sheet(isPresented: $showPersonalLogin, onDismiss: {
                Task { _ = await settings.loadPersonalMaps() }
            }) {
                NavigationStack {
                    AppleCaltopoPersonalProbeView(onCatalog: { username, maps in
                        settings.acceptPersonalCatalog(username, maps: maps)
                        showPersonalLogin = false
                    })
                }
            }
            .sheet(isPresented: $showWebsite, onDismiss: {
                Task { _ = await settings.loadPersonalMaps() }
            }) {
                NavigationStack { AppleCaltopoPersonalProbeView() }
            }
            .onChange(of: credentialsReady) { wasReady, isReady in
                guard !settings.usesPersonalCredentials, !wasReady, isReady,
                      settings.teamMaps.isEmpty,
                      !settings.isLoadingTeamMaps
                else { return }
                Task { await settings.loadTeamMaps() }
            }
        }
        .task(id: settings.usesPersonalCredentials ? "personal" : profiles.activeProfileID) {
            navigationStack = []; search = ""
            await loadSelectedMaps()
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
    @Environment(\.colorScheme) private var colorScheme
    private var valid: Bool {
        guard let gb = Double(size), gb.isFinite, (0.1...1000).contains(gb),
              let age = Int(days), (1...3650).contains(age) else { return false }
        return true
    }
    private var changed: Bool { Double(size) != maximumGB || Int(days) != maximumDays }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SettingsTextField("Max Size (GB)", text: $size).keyboardType(.decimalPad)
            SettingsTextField("Max Age (days)", text: $days).keyboardType(.numberPad)
            Button("Save Limits") {
                guard let gb = Double(size), gb.isFinite, (0.1...1000).contains(gb),
                      let age = Int(days), (1...3650).contains(age) else {
                    message = "Enter 0.1–1,000 GB and 1–3,650 days."; return
                }
                maximumGB = gb; maximumDays = age
                AppleFlightStorage.requestCheck()
                message = String(format: "Saved: %.1f GB, %d days.", maximumGB, maximumDays)
            }
            .buttonStyle(.borderedProminent)
            .tint(colorScheme == .dark ? Color(white: 0.14) : Color(white: 0.94))
            .foregroundStyle(!valid || !changed ? Color.gray : (colorScheme == .dark ? Color.white : Color.black))
            .disabled(!valid || !changed)
            Text(message.isEmpty ? String(format: "Saved limits: %.1f GB, %d days.", maximumGB, maximumDays) : message)
            if !valid { Text("Enter 0.1–1,000 GB and 1–3,650 days.").font(.footnote) }
            Text("At 90% usage, cleanup removes older eligible folders to restore 10% free. Today and active files stay protected. If that is not possible, increase the allowance.")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .onChange(of: size) { _, _ in message = "" }
        .onChange(of: days) { _, _ in message = "" }
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

// Settings labels remain visible even when a field contains a value.
struct SettingsHelpLabel: View {
    let title: String
    var detail: String = ""
    var centered = false
    var iconOnly = false
    @State private var showingHelp = false
    init(_ title: String, detail: String = "", centered: Bool = false, iconOnly: Bool = false) {
        self.title = title; self.detail = detail; self.centered = centered; self.iconOnly = iconOnly
    }
    var body: some View {
        Button { showingHelp = true } label: {
            HStack(spacing: 6) {
                if !iconOnly { Text(title).multilineTextAlignment(centered ? .center : .leading) }
                Image(systemName: "questionmark.circle").font(.callout).foregroundStyle(.tint)
            }
            .frame(minWidth: iconOnly ? 44 : nil, maxWidth: centered ? .infinity : nil, minHeight: 44, alignment: centered ? .center : .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .environment(\.isEnabled, true)
        .accessibilityLabel("Help: " + title)
        .accessibilityHint("Explains this setting and its allowed values")
        .sheet(isPresented: $showingHelp) {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(SettingsFieldHelp.description(for: title))
                        if !detail.isEmpty { Text(detail) }
                    }.frame(maxWidth: .infinity, alignment: .leading).padding()
                }
                .navigationTitle(title.components(separatedBy: ":").first ?? title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showingHelp = false } } }
            }.presentationDetents([.medium, .large])
        }
    }
}

struct SettingsFieldBox: ViewModifier {
    func body(content: Content) -> some View {
        content
            .textFieldStyle(.plain)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(minHeight: 48)
            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
            .overlay {
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.secondary.opacity(0.45), lineWidth: 1)
                    .allowsHitTesting(false)
            }
    }
}

struct SettingsTextField: View {
    let title: String
    @Binding var text: String
    init(_ title: String, text: Binding<String>) { self.title = title; _text = text }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            SettingsHelpLabel(title, centered: true)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.tint)
            TextField(title, text: $text)
                .accessibilityLabel(title)
                .modifier(SettingsFieldBox())
        }.padding(.vertical, 4)
    }
}

private struct SettingsScannableTextField: View {
    let title: String
    @Binding var text: String
    let mode: AppleScannedFieldMode
    var secure = false
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            SettingsHelpLabel(title, centered: true)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.tint)
            AppleScannableTextField(title: title, text: $text, mode: mode, secure: secure)
                .accessibilityLabel(title)
                .modifier(SettingsFieldBox())
        }.padding(.vertical, 4)
    }
}

struct SettingsToggle: View {
    let title: String
    @Binding var isOn: Bool
    var helpKey: String? = nil
    init(_ title: String, isOn: Binding<Bool>, helpKey: String? = nil) { self.title = title; _isOn = isOn; self.helpKey = helpKey }
    var body: some View {
        HStack {
            Text(title)
            SettingsHelpLabel(helpKey ?? title, detail: "Values: On or Off.", iconOnly: true)
            Spacer(minLength: 4)
            Toggle(title, isOn: $isOn).labelsHidden().fixedSize()
        }
    }
}

struct SettingsPicker<Selection: Hashable, Content: View>: View {
    let title: String
    @Binding var selection: Selection
    @ViewBuilder let content: () -> Content
    init(_ title: String, selection: Binding<Selection>, @ViewBuilder content: @escaping () -> Content) {
        self.title = title; _selection = selection; self.content = content
    }
    var body: some View {
        VStack(spacing: 4) {
            SettingsHelpLabel(title, centered: true).font(.subheadline.weight(.semibold)).foregroundStyle(.tint)
            Picker(title, selection: $selection, content: content)
                .labelsHidden().frame(maxWidth: .infinity).modifier(SettingsFieldBox())
        }
    }
}

struct SettingsStepper<Value: Strideable>: View where Value.Stride: SignedNumeric {
    let title: String
    @Binding var value: Value
    let range: ClosedRange<Value>
    init(_ title: String, value: Binding<Value>, in range: ClosedRange<Value>) {
        self.title = title; _value = value; self.range = range
    }
    var body: some View {
        VStack(spacing: 4) {
            SettingsHelpLabel(title.components(separatedBy: ":").first ?? title,
                              detail: "Allowed range: \(range.lowerBound)–\(range.upperBound).", centered: true)
                .font(.subheadline.weight(.semibold)).foregroundStyle(.tint)
            Stepper(value: $value, in: range) {
                Text(title.components(separatedBy: ":").dropFirst().joined(separator: ":").trimmingCharacters(in: .whitespaces))
            }.accessibilityLabel(title).modifier(SettingsFieldBox())
        }
    }
}

private struct SettingsValue: View {
    let title: String
    let value: String
    init(_ title: String, value: String) { self.title = title; self.value = value }
    var body: some View { LabeledContent { Text(value) } label: { SettingsHelpLabel(title) } }
}

/// Matches Android: one required checkbox per notice paragraph; Enable stays disabled until all are checked.
/// Like Android's dialog, the actions sit below the scrolling text: side by side (dismiss, then confirm)
/// when they fit, otherwise stacked full width with confirm on top.
private struct ProximityConsentSheet: View {
    @ObservedObject var proximityAlerts: AppleProximityAlertCenter
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(RidProximityConsent.title)
                    .font(.title2.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                    .padding(.bottom, 4)
                ForEach(Array(RidProximityConsent.noticeParagraphs.enumerated()), id: \.offset) { index, paragraph in
                    ProximityConsentRow(text: paragraph, isChecked: proximityAlerts.consent.acknowledged.contains(index)) {
                        proximityAlerts.toggleAcknowledgment(index)
                    }
                }
            }
            .padding()
        }
        .scrollIndicators(.visible)
        .scrollIndicatorsFlash(onAppear: true)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                Divider()
                actionBar
                    .padding(.horizontal)
                    .padding(.vertical, 12)
            }
            .background(.bar)
        }
    }

    @ViewBuilder private var actionBar: some View {
        if dynamicTypeSize.isAccessibilitySize {
            stackedActions
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    Spacer(minLength: 0)
                    keepDisabledButton(fullWidth: false)
                    confirmButton(fullWidth: false)
                }
                stackedActions
            }
        }
    }

    private var stackedActions: some View {
        VStack(spacing: 8) {
            confirmButton(fullWidth: true)
            keepDisabledButton(fullWidth: true)
        }
    }

    private func keepDisabledButton(fullWidth: Bool) -> some View {
        Button { proximityAlerts.cancelEnable() } label: {
            Text("Keep disabled")
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 44)
        }
        .buttonStyle(.bordered)
    }

    private func confirmButton(fullWidth: Bool) -> some View {
        Button { proximityAlerts.confirmEnable() } label: {
            Text(proximityAlerts.consent.confirmLabel)
                .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!proximityAlerts.consent.canConfirm)
    }
}

/// The whole row is one checkbox target; its accessibility label is the paragraph.
private struct ProximityConsentRow: View {
    let text: String
    let isChecked: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Image(systemName: isChecked ? "checkmark.square.fill" : "square")
                    .font(.title2)
                    .foregroundStyle(isChecked ? Color.accentColor : Color.secondary)
                Text(text)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 6)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(text)
        .accessibilityValue(isChecked ? "Checked" : "Not checked")
        .accessibilityAddTraits(.isToggle)
    }
}

struct IncidentSelectionPanel: View {
    let currentName: String
    let initialName: String
    let onConnectMap: () -> Void
    let onUseName: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    var body: some View {
        NavigationStack {
            Form {
                Section { Text("Current incident: \(currentName)") }
                Section("Incident map") {
                    Button("Connect to a map…", action: onConnectMap)
                    Text("Choose personal or organization credentials, then an incident map.")
                }
                Section("Incident without a map") {
                    TextField("Incident name", text: $name)
                    Text("Using this name disconnects the current incident map. Existing archived flights keep their original incident.")
                    Button("Use without a map") { onUseName(name.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationTitle("Select Incident")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .onAppear { name = initialName }
    }
}

@MainActor
private final class AppleSettingsDraft: ObservableObject {
    let edits = OperationalSettingsEdits()
    func value<T>(_ key: String, _ live: T) -> T { edits.value(for: key, fallback: live) }
    func binding<T: Equatable>(_ key: String, _ live: Binding<T>) -> Binding<T> {
        Binding(get: { self.value(key, live.wrappedValue) }, set: { value in
            self.objectWillChange.send()
            self.edits.stage(key, value: value, original: live.wrappedValue) { live.wrappedValue = $0 }
        })
    }
}

private struct SettingsDraftProximityConsent: View {
    @Binding var consent: RidProximityConsent
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(RidProximityConsent.title).font(.title2)
                ForEach(Array(RidProximityConsent.noticeParagraphs.enumerated()), id: \.offset) { index, text in
                    ProximityConsentRow(text: text, isChecked: consent.acknowledged.contains(index)) { consent.toggleAcknowledgment(index) }
                }
                Text("Alerts will change only after you Save Settings.").font(.footnote)
                Button(consent.confirmLabel) { consent.confirmEnable() }.disabled(!consent.canConfirm).buttonStyle(.borderedProminent)
                Button("Keep disabled") { consent.cancel() }
            }.padding()
        }
    }
}

private struct SettingsDismissGuard: UIViewControllerRepresentable {
    var hasChanges: Bool
    var onAttempt: () -> Void
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.hasChanges = hasChanges
        controller.onAttempt = onAttempt
        DispatchQueue.main.async { controller.install() }
    }
    final class Controller: UIViewController, UIAdaptivePresentationControllerDelegate {
        var hasChanges = false
        var onAttempt: (() -> Void)?
        override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); install() }
        func install() {
            var ancestor: UIViewController? = parent
            while let current = ancestor {
                if let presentation = current.presentationController { presentation.delegate = self }
                ancestor = current.parent
            }
        }
        func presentationControllerShouldDismiss(_ presentationController: UIPresentationController) -> Bool { !hasChanges }
        func presentationControllerDidAttemptToDismiss(_ presentationController: UIPresentationController) { onAttempt?() }
    }
}
