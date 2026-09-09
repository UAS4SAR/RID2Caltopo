import CoreLocation
import LocalAuthentication
import R2CCore
import R2CAppleRadios
import SwiftUI
import UIKit

private struct DroneConfirmationRequest: Identifiable {
    let id: String
}

private struct TrackerReauthenticationPromptModifier: ViewModifier {
    @Environment(\.openURL) private var openURL
    @Binding var isPresented: Bool
    @Binding var reauthenticationURL: URL?
    @Binding var browserOpen: Bool

    func body(content: Content) -> some View {
        content.alert(
            "Tracker sign-in required",
            isPresented: $isPresented
        ) {
            Button("Sign in") {
                guard let url = reauthenticationURL else { return }
                browserOpen = true
                openURL(url)
            }
            Button("Continue offline", role: .cancel) {
                reauthenticationURL = nil
            }
        } message: {
            Text(
                "Sign in with an authorized organization account to restore Tracker "
                    + "sharing and online organization services. RID, video, maps, and "
                    + "your existing CalTopo configuration remain available."
            )
        }
    }
}

struct ContentView: View {
    private let endpoint = MediaStreamEndpoint(designator: "demo")
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @StateObject private var bluetoothScanner = BluetoothRIDScanner()
    @StateObject private var bridgeAlerts = AppleDroneScoutBridgeAlertCenter()
    @StateObject private var mediaMTX = MediaMTXViewModel()
    @ObservedObject private var videoFrames = AppleStreamRegistry.shared.primaryModel
    @StateObject private var ridTracks = RIDTrackViewModel()
    @StateObject private var locationProvider = AppleLocationProvider()
    @StateObject private var caltopoSettings = AppleCaltopoSettings()
    @StateObject private var clueStore = AppleClueStore()
    @StateObject private var diagnostics = AppleDiagnosticsCenter()
    @StateObject private var droneConfirmations = AppleDroneConfirmationStore()
    @StateObject private var orgConfigSettings = AppleOrgConfigSettings()
    @StateObject private var orgConfigImporter = AppleOrgConfigImporter()
    @ObservedObject private var profileLifecycle = AppleCaltopoProfileLifecycle.shared
    @StateObject private var peerCoordinator = AppleTrackerCoordinator()
    @StateObject private var proximityAlerts = AppleProximityAlertCenter()
    @StateObject private var operationalAlerts = AppleOperationalAlertCenter()
    @StateObject private var notams = AppleNotamCenter.shared
    @StateObject private var airspace = AppleAirspaceCenter.shared
    @StateObject private var landRestrictions = AppleLandRestrictionCenter.shared
    @StateObject private var streamRegistry = AppleStreamRegistry.shared
    @ObservedObject private var networkDiagnostics = AppleNetworkDiagnosticCenter.shared
    private let iCloudBackup = AppleICloudBackupCenter.shared
    @State private var showTrackMap = false
    @State private var showCaltopoSettings = false
    @State private var showDiagnosticLogs = false
    @State private var showStatus = false
    @State private var showReleaseNotes = false
    @State private var showAboutPrivacy = false
    @State private var showImportConfig = false
    @State private var importConfigNotice: ConfigImportNotice?
    @State private var showConfigurationTransfer = false
    @State private var showTeamMaps = false
    @State private var showMapOptions = false
    @State private var showConfirmExit = false
    @State private var pendingImportToken = ""
    @State private var selectedAircraftID: String?
    @State private var pendingDroneConfirmation: DroneConfirmationRequest?
    @State private var automaticStreamPairingAircraftID: String?
    @State private var automaticPairingOfferedStreamIDs: Set<String> = []
    @State private var controllerRTMPURL = "Connect this device to Wi-Fi"
    @State private var appStartedAt = Date()
    @State private var lastBridgePacketDiagnosticLogAt = Date.distantPast
    @State private var dismissedUpdateVersionCode = 0
    @State private var pendingCredentialProfileID: String?
    @State private var showCredentialSwitchConfirmation = false
    @State private var trackerReauthenticationBrowserOpen = false
    @State private var trackerReauthenticationURL: URL?
    @State private var showTrackerReauthenticationPrompt = false
    @State private var showTrackerReenrollmentRequired = false
    @State private var lastTrackerReenrollmentNoticeCredential: String?
    @State private var organizationAccessEvaluated = false
    @State private var organizationAccessGranted = false
    @State private var organizationAccessObscured = false
    @State private var organizationAccessBackgroundedAt: Date?
    @State private var organizationAuthenticationInFlight = false
    @State private var organizationAuthenticationError: String?
    @State private var pendingOrganizationAccessURL: URL?
    @State private var incidentMapConnectedAt = Date()
    @State private var incidentMapBackgroundedAt: Date?
    @State private var incidentMapBackgroundDisconnectTask: Task<Void, Never>?
    @State private var incidentMapRelocationGuard = IncidentMapRelocationGuard()
    @AppStorage("video.captureStreams") private var captureStreams = false

    private var startupRoot: some View {
        rootScreen
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Menu {
                        ForEach(profileLifecycle.availableProfiles) { profile in
                            Button {
                                requestCredentialProfileSwitch(profile.id)
                            } label: {
                                Label {
                                    VStack(alignment: .leading) {
                                        Text(profile.credentialLabel)
                                        Text(profileMenuDetail(profile))
                                    }
                                } icon: {
                                    Image(systemName: profile.id == profileLifecycle.activeProfileID
                                        ? "checkmark.circle.fill"
                                        : "circle")
                                }
                            }
                        }
                    } label: {
                        VStack(spacing: 0) {
                            Text("RID-2-Caltopo")
                                .font(.headline)
                            HStack(spacing: 2) {
                                Text("Teams: \(profileLifecycle.activeCredentialLabel)")
                                    .font(.caption)
                                    .foregroundStyle(activeCredentialNearExpiry ? .orange : .secondary)
                                Image(systemName: "chevron.down")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .accessibilityLabel("Selected Teams credentials: \(profileLifecycle.activeCredentialLabel)")
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        bridgeAlerts.toggleAudioMuted()
                    } label: {
                        BridgeSignalIndicator(rssi: bluetoothScanner.bridgeSignalStrengthDbm)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(
                        bridgeAlerts.audioMuted
                            ? "Double tap to enable bridge warnings"
                            : "Double tap to mute bridge warnings"
                    )
                    Menu {
                        Button("Live View", systemImage: "video") { showTrackMap = true }
                        Button("Send diagnostics to developer…", systemImage: "square.and.arrow.up") {
                            showDiagnosticLogs = true
                        }
                        Button("Status", systemImage: "info.circle") { showStatus = true }
                        Button("Release Notes", systemImage: "doc.text") { showReleaseNotes = true }
                        Divider()
                        Button("Import Config", systemImage: "qrcode.viewfinder") { showImportConfig = true }
                        Button("Backup & Transfer", systemImage: "shippingbox") { showConfigurationTransfer = true }
                        Button("Settings", systemImage: "gearshape") { showCaltopoSettings = true }
                        Button("About & Privacy", systemImage: "hand.raised") {
                            showAboutPrivacy = true
                        }
                        Divider()
                        Button("Quit", systemImage: "xmark.circle", role: .destructive) {
                            AppleLog.info("Lifecycle", "Quit menu selected")
                            showConfirmExit = true
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .navigationDestination(isPresented: $showTrackMap) {
                operationalMapView
                    .navigationBarBackButtonHidden(true)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button(action: closeLiveView) {
                                Label("Main Screen", systemImage: "chevron.left")
                            }
                        }
                    }
                    .onDisappear {
                        guard showTrackMap else { return }
                        AppleLog.warning(
                            "Navigation",
                            "Live View disappeared while its presentation flag remained set; clearing stale navigation state"
                        )
                        showTrackMap = false
                    }
            }
            .navigationDestination(isPresented: $showCaltopoSettings) {
                CaltopoSettingsView(
                    settings: caltopoSettings,
                    orgSettings: orgConfigSettings,
                    locationProvider: locationProvider,
                    importer: orgConfigImporter,
                    identityStore: droneConfirmations,
                    trackModel: ridTracks,
                    proximityAlerts: proximityAlerts,
                    iCloudBackup: iCloudBackup
                ) { configuration in
                    ridTracks.configureCaltopo(configuration, trackFolderName: orgConfigSettings.trackFolder)
                    clueStore.configure(configuration, trackFolderName: orgConfigSettings.trackFolder)
                }
            }
            .navigationDestination(isPresented: $showConfigurationTransfer) {
                AppleConfigurationTransferView(
                    caltopo: caltopoSettings,
                    organization: orgConfigSettings,
                    identities: droneConfirmations
                )
            }
            .navigationDestination(isPresented: $showDiagnosticLogs) {
                DiagnosticLogView(diagnostics: diagnostics)
            }
            .navigationDestination(isPresented: $showStatus) {
                AppleStatusView(snapshot: statusSnapshot)
            }
            .navigationDestination(isPresented: $showReleaseNotes) {
                AppleReleaseNotesView()
            }
            .navigationDestination(isPresented: $showAboutPrivacy) {
                AboutPrivacyView()
            }
            .navigationDestination(item: $selectedAircraftID) { aircraftID in
                aircraftDestination(aircraftID)
            }
            .sheet(isPresented: $showImportConfig) {
                NavigationStack {
                    ConfigImportView(
                        initialToken: pendingImportToken,
                        importer: orgConfigImporter,
                        caltopoSettings: caltopoSettings,
                        orgSettings: orgConfigSettings,
                        identityStore: droneConfirmations
                    ) { notice in
                        importConfigNotice = notice
                    }
                    .id(pendingImportToken)
                }
                .presentationDetents([.height(440), .large])
                .presentationDragIndicator(.visible)
            }
            .sheet(isPresented: $showTeamMaps) {
                CaltopoTeamMapBrowser(settings: caltopoSettings) { map in
                    orgConfigSettings.setIncidentMapTitle(map.title)
                    applyCaltopoConfiguration(caltopoSettings.selectMap(map))
                    showTeamMaps = false
                }
            }
            .confirmationDialog(
                "Map Options",
                isPresented: $showMapOptions,
                titleVisibility: .visible
            ) {
                Button("Switch Map") { showTeamMaps = true }
                Button("Disconnect", role: .destructive) {
                    applyCaltopoConfiguration(caltopoSettings.disconnectMap())
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("You are currently synced with: \(caltopoSettings.mapTitle)")
            }
            .alert("Update available", isPresented: updateAdvisoryPresented) {
                if let updateURL = peerCoordinator.recommendedUpdateURL {
                    Button("Check App Store") { openURL(updateURL) }
                }
                Button("Continue", role: .cancel) {
                    dismissedUpdateVersionCode = peerCoordinator.recommendedAppVersionCode
                }
            } message: {
                Text("Check the App Store for Update. If no update is offered, return here and continue. Do not delete RID2Caltopo.")
            }
            .modifier(TrackerReauthenticationPromptModifier(
                isPresented: $showTrackerReauthenticationPrompt,
                reauthenticationURL: $trackerReauthenticationURL,
                browserOpen: $trackerReauthenticationBrowserOpen
            ))
            .alert("Tracker re-enrollment required", isPresented: $showTrackerReenrollmentRequired) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(
                    "Tracker rejected this tablet's organization authorization. It may have "
                        + "been retired, expired, or replaced. In Import Config, scan a current "
                        + "organization enrollment QR to re-enroll this tablet. Offline RID and "
                        + "the incident map remain available."
                )
            }
            .alert("Confirm Exit", isPresented: $showConfirmExit) {
                Button("Cancel", role: .cancel) {
                    AppleLog.info("Lifecycle", "Quit cancelled")
                }
                Button("OK", role: .destructive) {
                    AppleLog.info("Lifecycle", "Quit confirmed")
                    AppleApplicationCleanupCenter.shared.quitPrimaryWindow(reason: "operator quit")
                }
            } message: {
                Text("Do you really want to close this application?")
            }
            .alert("Switch Teams Credentials?", isPresented: $showCredentialSwitchConfirmation) {
                Button("Cancel", role: .cancel) {
                    pendingCredentialProfileID = nil
                }
                Button("Disconnect and Switch", role: .destructive) {
                    if let profileID = pendingCredentialProfileID {
                        activateCredentialProfile(profileID)
                    }
                    pendingCredentialProfileID = nil
                }
            } message: {
                Text(
                    "Disconnect from the current map and stop arbitration for "
                        + "\(ridTracks.tracks.count) active aircraft before switching Teams credentials?"
                )
            }
            .alert(item: $importConfigNotice) { notice in
                Alert(
                    title: Text(notice.title),
                    message: Text(notice.message),
                    dismissButton: .default(Text("OK"))
                )
            }
            .modifier(RecordingDownloadApprovalModifier(coordinator: peerCoordinator))
            .sheet(item: Binding(
                get: { peerCoordinator.videoStreamRequestReadyForApproval },
                set: { value in
                    if value == nil {
                        peerCoordinator.acknowledgeVideoStreamRequest()
                    }
                }
            )) { request in
                NavigationStack {
                    VStack(spacing: 0) {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 18) {
                                Text("Video Stream Request")
                                    .font(.title2.bold())
                                LabeledContent("From", value: request.requesterEmail)
                        if let activeEmail = peerCoordinator.currentRemoteVideoRequesterEmail {
                            Text(
                                "The app is already streaming to \(activeEmail). " +
                                "Starting this request will redirect that viewer."
                            )
                            .foregroundStyle(.orange)
                        }
                        LabeledContent(
                            "Incident",
                            value: request.incidentName.isEmpty
                                ? "Not specified"
                                : request.incidentName
                        )
                        LabeledContent(
                            "Drone",
                            value: request.droneDesignator.isEmpty
                                ? "Not specified"
                                : request.droneDesignator
                        )
                        if let width = request.sourceWidth,
                           let height = request.sourceHeight,
                           width > 0,
                           height > 0 {
                            let frameRate = request.sourceFps ?? 0
                            let bitrate = request.sourceBitrateBps ?? 0
                            let source = [
                                "\(width)×\(height)",
                                frameRate > 0
                                    ? String(format: "%.1f fps", frameRate)
                                    : nil,
                                bitrate > 0
                                    ? String(
                                        format: "%.1f Mbps",
                                        Double(bitrate) / 1_000_000
                                    )
                                    : nil,
                            ]
                            .compactMap { $0 }
                            .joined(separator: ", ")
                            LabeledContent("Source", value: source)
                        } else {
                            LabeledContent(
                                "Source",
                                value: "Source details pending"
                            )
                        }
                        if let failure = peerCoordinator.videoPreflightFailure {
                            LabeledContent("Link", value: "Measurement unavailable")
                            Text(failure)
                                .foregroundStyle(.orange)
                        } else if
                            let route = peerCoordinator.videoPreflightRouteKind,
                            let bitsPerSecond = peerCoordinator.videoPreflightEstimatedUplinkBps
                        {
                            LabeledContent(
                                "Link",
                                value: route == "direct" ? "Direct" : "Routed"
                            )
                            LabeledContent(
                                "Usable uplink",
                                value: String(
                                    format: "%.2f Mbps",
                                    Double(bitsPerSecond) / 1_000_000
                                )
                            )
                        } else {
                            LabeledContent("Link", value: "Measuring routed link…")
                        }
                        Text(
                            "Remote video remains off. This check exchanges only " +
                            "synthetic data. Choose a complete quality preset, " +
                            "then explicitly select Start."
                        )
                        .foregroundStyle(.secondary)
                                if peerCoordinator.videoQualityChoices.contains(where: {
                                    $0.capacity == "fallback"
                                }) {
                                    Text(
                                        "The measurement is below every normal profile. " +
                                        "The smallest stream is available as a cautious fallback."
                                    )
                                    .foregroundStyle(.orange)
                                }
                                if peerCoordinator.videoPreflightRouteKind != nil {
                                    ForEach(peerCoordinator.videoQualityChoices) { choice in
                                        Button {
                                            peerCoordinator.selectVideoQuality(choice.id)
                                        } label: {
                                            HStack {
                                                Image(systemName:
                                                    peerCoordinator.selectedVideoQualityID == choice.id
                                                        ? "checkmark.circle.fill"
                                                        : "circle"
                                                )
                                                Text(choice.label)
                                            }
                                        }
                                        .foregroundStyle(
                                            choice.capacity == "enough"
                                                ? Color.green
                                                : choice.capacity == "marginal" ||
                                                    choice.capacity == "fallback"
                                                    ? Color.orange
                                                    : Color.red
                                        )
                                    }
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(24)
                        }
                        Divider()
                        HStack {
                            Button("Decline", role: .destructive) {
                                peerCoordinator.declineVideoStreamRequest()
                            }
                            Spacer()
                            Button(
                                peerCoordinator.videoPreflightRouteKind != nil
                                    ? "Start"
                                    : peerCoordinator.videoPreflightFailure != nil
                                        ? "Measurement unavailable"
                                        : "Measuring link…"
                            ) {
                                peerCoordinator.approveVideoStreamRequest()
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(
                                peerCoordinator.videoPreflightRouteKind == nil ||
                                !peerCoordinator.selectedVideoQualityIsStartable
                            )
                        }
                        .frame(maxWidth: .infinity)
                        .padding(24)
                    }
                    .navigationBarTitleDisplayMode(.inline)
                    .interactiveDismissDisabled()
                }
            }
            .sheet(item: $pendingDroneConfirmation, onDismiss: queueNextDroneConfirmation) { request in
                DroneConfirmationView(
                    remoteID: request.id,
                    existing: droneConfirmations.identity(for: request.id),
                    identityStore: droneConfirmations,
                    onConfirm: confirmDrone,
                    onIgnore: {
                        droneConfirmations.ignore(request.id)
                        ridTracks.suppressCaltopoPublication(remoteID: request.id)
                    }
                )
                .interactiveDismissDisabled()
            }
            .task {
                iCloudBackup.configure(
                    caltopo: caltopoSettings,
                    organization: orgConfigSettings,
                    identities: droneConfirmations
                )
                iCloudBackup.scheduleBackup()
                mediaMTX.eventHandler = { event in
                    streamRegistry.handle(event)
                    reconcileStreamFlightPairings()
                    if case .recordFileCompleted = event {
                        peerCoordinator.updateManagedVideoStreams(
                            incidentName: currentIncidentName,
                            incidentKey: currentIncidentKey,
                            sessions: streamRegistry.sessions,
                            droneDesignatorProvider: managedVideoDroneDesignator
                        )
                    }
                }
                await diagnostics.start()
                AppleLog.info("App", "Application UI started")
                networkDiagnostics.start()
                if !ProcessInfo.processInfo.arguments.contains("--no-location") {
                    locationProvider.start()
                }
                if !ProcessInfo.processInfo.arguments.contains("--manual-radios") {
                    try? await bluetoothScanner.start()
                }
                ridTracks.configurePublicationSuppression(droneConfirmations.isIgnored)
                ridTracks.configurePairedVideoActivity(
                    { streamRegistry.flightActivityByAircraftID() },
                    validatedSEIPositionProvider: { tracks, date in
                        streamRegistry.freshValidatedDJIPositionByAircraftID(
                            tracks: tracks,
                            at: date
                        )
                    }
                )
                ridTracks.bind(to: bluetoothScanner.observations, sourceID: "bluetooth")
                ridTracks.bindAircraftMessages(
                    to: bluetoothScanner.aircraftMessages,
                    sourceID: "bluetooth"
                )
                ridTracks.bindRIDMessageTimes(
                    to: bluetoothScanner.ridMessageTimes,
                    sourceID: "bluetooth"
                )
                ridTracks.configureClueArchiveProvider(clueStore.archiveClues)
                _ = orgConfigImporter.restoreActiveProfile(
                    caltopoSettings: caltopoSettings,
                    orgSettings: orgConfigSettings
                )
                ridTracks.configureCaltopo(
                    caltopoSettings.configuration,
                    trackFolderName: orgConfigSettings.trackFolder
                )
                clueStore.configure(
                    caltopoSettings.configuration,
                    trackFolderName: orgConfigSettings.trackFolder
                )
                if !caltopoSettings.mapID.isEmpty, caltopoSettings.mapTitle.isEmpty {
                    await caltopoSettings.loadTeamMaps()
                    if !caltopoSettings.mapTitle.isEmpty {
                        orgConfigSettings.setIncidentMapTitle(caltopoSettings.mapTitle)
                    }
                }
                orgConfigImporter.caltopoConfigurationHandler = { configuration in
                    ridTracks.configureCaltopo(configuration, trackFolderName: orgConfigSettings.trackFolder)
                    clueStore.configure(configuration, trackFolderName: orgConfigSettings.trackFolder)
                }
                orgConfigImporter.notamEnrollmentAppliedHandler = { faaProxyURL, trackerURLPrefix, trackerAPIKey in
                    notams.configure(
                        faaProxyURL: faaProxyURL,
                        trackerURLPrefix: trackerURLPrefix,
                        trackerAPIKey: trackerAPIKey
                    )
                    notams.enabled = true
                    notams.refreshNow(location: locationProvider.lastLocation)
                    configurePeerCoordinator(forceReconnect: true)
                }
                orgConfigImporter.trackerReauthenticationRequiredHandler = { url in
                    peerCoordinator.requireReauthentication(at: url)
                }
                ridTracks.configurePeerCoordination(
                    peerCoordinator,
                    identityProvider: droneConfirmations.identity,
                    peerConfirmationConsumer: droneConfirmations.applyPeerConfirmation,
                    peerConfirmationClearer: droneConfirmations.clearPeerConfirmation
                )
                configurePeerCoordinator()
                configureTrackArchive()
                configureTrackPolicy()
                if ProcessInfo.processInfo.arguments.contains("--demo-notam") {
                    notams.installSimulatorDemo()
                    airspace.installSimulatorDemo()
                } else {
                    notams.reconcileEnrollmentActivation(
                        hasNotamAdminConfiguration: orgConfigSettings.hasNotamAdminConfiguration
                    )
                    notams.configure(
                        faaProxyURL: orgConfigSettings.faaProxyURL,
                        trackerURLPrefix: orgConfigSettings.trackerURLPrefix,
                        trackerAPIKey: orgConfigSettings.trackerAPIKey
                    )
                    notams.update(location: locationProvider.lastLocation)
                    airspace.update(location: locationProvider.lastLocation)
                    landRestrictions.update(location: locationProvider.lastLocation)
                }
                if !ProcessInfo.processInfo.arguments.contains("--manual-mediamtx") {
                    mediaMTX.start(captureStreams: captureStreams)
                }
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("--simulate-mediamtx-listener-exit") {
                    Task { @MainActor in
                        try? await Task.sleep(for: .seconds(5))
                        mediaMTX.simulateSilentListenerExit()
                    }
                }
                #endif
                if ProcessInfo.processInfo.arguments.contains("--anomaly-color") {
                    videoFrames.setAnomalyMode(.colorUniqueness)
                } else if ProcessInfo.processInfo.arguments.contains("--anomaly-off") {
                    videoFrames.setAnomalyMode(.off)
                } else if ProcessInfo.processInfo.arguments.contains("--anomaly-infrared") {
                    videoFrames.setAnomalyMode(.infrared)
                }
                if ProcessInfo.processInfo.arguments.contains("--start-video"),
                   let url = endpoint.loopbackHlsURL {
                    videoFrames.start(url: url)
                }
                if ProcessInfo.processInfo.arguments.contains("--demo-streams") {
                    streamRegistry.handle(.streamConnecting(path: "demo"))
                    streamRegistry.handle(.streamConnecting(path: "RC2/Red1"))
                    streamRegistry.handle(.streamConnecting(path: "AUTEL/Blue2"))
                }
                if ProcessInfo.processInfo.arguments.contains("--demo-rid") {
                    ridTracks.startSimulatorDemo(
                        proximityAlert: ProcessInfo.processInfo.arguments.contains("--demo-proximity-alert"),
                        predictiveAlert: ProcessInfo.processInfo.arguments.contains("--demo-predictive-alert"),
                        altitudeAlert: ProcessInfo.processInfo.arguments.contains("--demo-altitude-alert")
                    )
                }
                if ProcessInfo.processInfo.arguments.contains("--demo-confirm-first-drone") {
                    try? await Task.sleep(for: .seconds(2))
                    if let remoteID = ridTracks.tracks.first?.aircraftID {
                        let identity = RidAircraftIdentity(
                            remoteID: remoteID,
                            organization: "mySAR",
                            pilotCallsign: "Apple1",
                            droneDescription: "Simulator Drone"
                        )
                        droneConfirmations.confirm(identity)
                        peerCoordinator.confirm(identity)
                    }
                }
                if ProcessInfo.processInfo.arguments.contains("--archive-demo") {
                    try? await Task.sleep(for: .seconds(3))
                    ridTracks.archiveActiveTracks()
                }
                if ProcessInfo.processInfo.arguments.contains("--show-map") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showTrackMap = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-caltopo-settings") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showCaltopoSettings = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-anomaly") {
                    try? await Task.sleep(for: .milliseconds(500))
                    UserDefaults.standard.set(OperationalMapVideoLayout.video.rawValue, forKey: "map.videoLayout")
                    showTrackMap = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-streams") {
                    try? await Task.sleep(for: .milliseconds(500))
                    UserDefaults.standard.set(OperationalMapVideoLayout.video.rawValue, forKey: "map.videoLayout")
                    showTrackMap = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-transfer") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showConfigurationTransfer = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-logs") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showDiagnosticLogs = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-status") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showStatus = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-privacy") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showAboutPrivacy = true
                }
                if ProcessInfo.processInfo.arguments.contains("--package-logs") {
                    await diagnostics.prepareSelectedBundle()
                }
                if ProcessInfo.processInfo.arguments.contains("--show-aircraft-detail") {
                    try? await Task.sleep(for: .seconds(2))
                    selectedAircraftID = ridTracks.tracks.first?.aircraftID
                }
                if ProcessInfo.processInfo.arguments.contains("--show-import-config") {
                    if ProcessInfo.processInfo.arguments.contains("--demo-org-token") {
                        pendingImportToken = "R2C2:not-valid-demo-token"
                    }
                    try? await Task.sleep(for: .milliseconds(500))
                    showImportConfig = true
                }
            }
            .onChange(of: managedVideoPresenceFingerprint, initial: true) {
                peerCoordinator.updateManagedVideoStreams(
                    incidentName: currentIncidentName,
                    incidentKey: currentIncidentKey,
                    sessions: streamRegistry.sessions,
                    droneDesignatorProvider: managedVideoDroneDesignator
                )
            }
            .task {
                while !Task.isCancelled,
                      !AppleApplicationCleanupCenter.shared.isShutdownRequested {
                    peerCoordinator.updateManagedVideoStreams(
                        incidentName: currentIncidentName,
                        incidentKey: currentIncidentKey,
                        sessions: streamRegistry.sessions,
                        droneDesignatorProvider: managedVideoDroneDesignator
                    )
                    try? await Task.sleep(for: .seconds(2))
                }
            }
    }

    private func managedVideoDroneDesignator(for session: AppleLiveStreamSession) -> String {
        guard let aircraftID = streamRegistry.boundAircraftID(for: session.id),
              let identity = droneConfirmations.identity(for: aircraftID)
        else { return session.id }
        let mappedID = identity.mappedID.trimmingCharacters(in: .whitespacesAndNewlines)
        return mappedID.isEmpty ? session.id : mappedID
    }

    private var lifecycleRoot: some View {
        startupRoot
            .task {
                await monitorOperationalState()
            }
            .onChange(of: idleTimeoutFingerprint, initial: true) {
                AppleApplicationCleanupCenter.shared.configureIdleTimeout(
                    appStartedAt: appStartedAt,
                    lastRIDMessageAt: ridTracks.lastRIDMessageAt,
                    maximumIdleMinutes: orgConfigSettings.maximumIdleMinutes
                )
            }
            .task(id: profileLifecycle.mutualAidExpiresAt) {
                await enforceMutualAidExpiryAtDeadline()
            }
            .task(id: scenePhase == .active) {
                guard scenePhase == .active else { return }
                while !Task.isCancelled,
                      !AppleApplicationCleanupCenter.shared.isShutdownRequested {
                    mediaMTX.ensureHealthy(captureStreams: captureStreams)
                    try? await Task.sleep(for: .seconds(15))
                }
            }
            .onReceive(orgConfigSettings.objectWillChange) { _ in
                guard !ProcessInfo.processInfo.arguments.contains("--demo-notam") else { return }
                Task { @MainActor in
                    await Task.yield()
                    notams.configure(
                        faaProxyURL: orgConfigSettings.faaProxyURL,
                        trackerURLPrefix: orgConfigSettings.trackerURLPrefix,
                        trackerAPIKey: orgConfigSettings.trackerAPIKey
                    )
                    notams.update(location: locationProvider.lastLocation)
                }
            }
            .onOpenURL { url in
                receiveIncomingURL(url)
            }
    }

    private func receiveIncomingURL(_ url: URL) {
        if organizationAuthenticationRequired, !organizationAccessGranted {
            pendingOrganizationAccessURL = url
            AppleLog.info(
                "OrganizationAccess",
                "Deferred incoming configuration URL until authentication"
            )
            reconcileOrganizationAuthentication()
            return
        }
        handleIncomingURL(url)
    }

    private func handleIncomingURL(_ url: URL) {
        let rawValue = url.absoluteString
        if url.scheme == "r2creauth" {
            trackerReauthenticationBrowserOpen = false
            trackerReauthenticationURL = nil
            showTrackerReauthenticationPrompt = false
            if url.host == "complete" {
                AppleLog.info(
                    "TrackerPeer",
                    "Reauthentication completed; configuration preserved"
                )
                configurePeerCoordinator(forceReconnect: true)
            } else if url.host == "erase" {
                droneConfirmations.resetPersistedState()
                caltopoSettings.resetPersistedState()
                orgConfigSettings.resetPersistedState()
                configurePeerCoordinator()
            }
            return
        }
        if let enrollmentURL = AppleTrackerEnrollmentClient.normalizedEnrollmentURL(rawValue) {
            pendingImportToken = enrollmentURL
            showImportConfig = true
            return
        }
        guard AndroidConfigTokenCodec.decode(rawValue) != nil else {
            AppleLog.error("OrgConfig", "Ignored unrecognised URL scheme payload")
            return
        }
        pendingImportToken = rawValue
        showImportConfig = true
    }

    private var mediaMonitoredRoot: some View {
        lifecycleRoot
            .onChange(of: bluetoothStatus) { _, status in
                AppleLog.info("BluetoothRID", status)
            }
            .onChange(of: bluetoothScanner.lastAircraftID) { _, aircraftID in
                if let aircraftID { AppleLog.info("BluetoothRID", "Observation from \(aircraftID)") }
            }
            .onChange(of: bluetoothScanner.lastDecodeError) { _, error in
                if let error { AppleLog.error("BluetoothRID", "Rejected advertisement: \(error)") }
            }
            .onChange(of: bluetoothScanner.bridgeEventCount) { _, _ in
                guard let diagnostic = bluetoothScanner.lastBridgePacketDiagnostic else { return }
                let now = Date()
                guard now.timeIntervalSince(lastBridgePacketDiagnosticLogAt) >= 5 else { return }
                lastBridgePacketDiagnosticLogAt = now
                AppleLog.info(
                    "DroneScoutBridge",
                    "Bridge event=\(diagnostic.eventCount) " +
                        "classification=\(diagnostic.classification.rawValue) " +
                        "transmitter=\(diagnostic.transmitterID.uuidString) " +
                        "messageCounter=\(diagnostic.messageCounter) " +
                        "aircraft=\(diagnostic.aircraftID) " +
                        "bridgeRssiDbm=\(diagnostic.bridgeToDeviceRssiDbm)"
                )
            }
            .onChange(of: bluetoothScanner.ingressDiagnostic) { _, diagnostic in
                guard let diagnostic else { return }
                AppleLog.info(
                    "BluetoothRID",
                    "Ingress callbacks=\(diagnostic.discoveryCallbacks) " +
                        "nonRID=\(diagnostic.nonRemoteIDCallbacks) packets=\(diagnostic.receivedPackets) " +
                        "decoded=\(diagnostic.decodedPackets) locations=\(diagnostic.locationPackets) " +
                        "observations=\(diagnostic.emittedObservations) relayPings=\(diagnostic.relayPings) " +
                        "noFreshLocation=\(diagnostic.noFreshLocation) " +
                        "missingIdentity=\(diagnostic.missingIdentity) " +
                        "invalidLocation=\(diagnostic.invalidLocation) " +
                        "decodeFailures=\(diagnostic.decodeFailures) streamDrops=\(diagnostic.streamDrops) " +
                        "lastSequence=\(diagnostic.lastSequence) " +
                        "lastTransmitter=\(diagnostic.lastTransmitterID.uuidString) " +
                        "lastCounter=\(diagnostic.lastMessageCounter.map(String.init) ?? "unavailable") " +
                        "lastKinds=\(diagnostic.lastMessageKinds) rssi=\(diagnostic.lastRSSIDbm)"
                )
            }
            .onChange(of: bluetoothScanner.scanRestartCount) { _, count in
                guard count > 0 else { return }
                AppleLog.info(
                    "BluetoothRID",
                    "High-priority scan restart=\(count) callbacks=\(bluetoothScanner.ingressDiagnostic?.discoveryCallbacks ?? 0)"
                )
            }
            .onChange(of: mediaMTX.status) { _, status in
                AppleLog.info("MediaMTX", status)
            }
            .onChange(of: captureStreams) { _, enabled in
                guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                mediaMTX.restart(captureStreams: enabled)
            }
    }

    private var lifecycleEventRoot: some View {
        mediaMonitoredRoot
            .onChange(of: videoStatus) { _, status in
                AppleLog.info("Video", status)
            }
            .onChange(of: ridTracks.archiveStatus) { _, status in
                AppleLog.info("Archive", status)
            }
            .onChange(of: ridTracks.caltopoStatus) { _, status in
                AppleLog.info("CalTopo", status)
            }
            .onChange(of: locationProvider.statusText) { _, status in
                AppleLog.info("Location", status)
            }
            .onChange(of: locationProvider.authorizationStatus) { _, _ in
                Task {
                    await networkDiagnostics.refresh(reason: .locationAuthorizationChanged)
                }
            }
            .onChange(of: locationProvider.lastLocation?.timestamp) { _, _ in
                peerCoordinator.updatePosition(locationProvider.lastLocation)
                publishLocalDeviceMarker()
                evaluateIncidentMapRelocation()
                if let coordinate = locationProvider.lastLocation?.coordinate {
                    ridTracks.prefetchTerrainForDeviceLocation(
                        latitude: coordinate.latitude,
                        longitude: coordinate.longitude
                    )
                }
            }
            .onChange(of: networkDiagnostics.currentSnapshotID) { _, _ in
                refreshControllerRTMPURL()
            }
            .onChange(of: scenePhase) { _, phase in
                guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                updateIdleTimerPolicy(for: phase)
                switch phase {
                case .active:
                    handleIncidentMapBecameActive()
                    resumeTrackerAfterBrowserReturnIfNeeded(callbackPending: false)
                    mediaMTX.ensureHealthy(captureStreams: captureStreams)
                    Task {
                        await networkDiagnostics.refresh(reason: .applicationBecameActive)
                        await ridTracks.setLocalDeviceMarkerPublishingEnabled(true)
                    }
                case .background:
                    removeLocalDeviceMarkerInBackground()
                    scheduleIncidentMapBackgroundDisconnect()
                case .inactive:
                    break
                @unknown default:
                    break
                }
            }
            .onChange(of: showTrackMap) { _, showing in
                guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                updateIdleTimerPolicy(for: scenePhase)
                if showing {
                    mediaMTX.ensureHealthy(captureStreams: captureStreams)
                }
            }
            .onChange(of: caltopoSettings.mapID, initial: true) { oldMapID, newMapID in
                if oldMapID != newMapID || !newMapID.isEmpty {
                    incidentMapConnectedAt = Date()
                    incidentMapRelocationGuard.reset()
                }
                if newMapID.isEmpty {
                    incidentMapBackgroundDisconnectTask?.cancel()
                    incidentMapBackgroundDisconnectTask = nil
                    incidentMapBackgroundedAt = nil
                }
                locationProvider.setIncidentMapBackgroundMonitoringEnabled(!newMapID.isEmpty)
            }
            .onChange(of: peerCoordinator.trackerSilenceGeneration) { _, _ in
                guard scenePhase != .active else { return }
                _ = disconnectInactiveIncidentMap(reason: "tracker silent for 60 seconds")
            }
    }

    private var monitoredRoot: some View {
        lifecycleEventRoot
            .onChange(of: ridTracks.caltopoRTTMilliseconds) { _, milliseconds in
                peerCoordinator.updateCaltopoRTT(milliseconds: milliseconds)
            }
            .onChange(of: peerConfigurationFingerprint) { _, _ in
                configurePeerCoordinator()
                configureTrackArchive()
                ridTracks.configureCaltopo(
                    caltopoSettings.configuration,
                    trackFolderName: orgConfigSettings.trackFolder
                )
            }
            .onChange(of: peerCoordinator.statusDetail) { _, detail in
                AppleLog.info("TrackerPeer", detail)
                publishLocalDeviceMarker(force: true)
                if detail == AppleTrackerCoordinator.reenrollmentRequiredStatusDetail {
                    let credential = orgConfigSettings.trackerAPIKey
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if lastTrackerReenrollmentNoticeCredential != credential {
                        lastTrackerReenrollmentNoticeCredential = credential
                        showTrackerReenrollmentRequired = true
                    }
                }
            }
            .onChange(
                of: peerCoordinator.reauthenticationRequiredGeneration,
                initial: true
            ) { _, generation in
                guard generation > 0 else { return }
                let managedCaltopoCleared =
                    caltopoSettings.quarantineTrackerManagedCredentials()
                if managedCaltopoCleared {
                    ridTracks.configureCaltopo(
                        caltopoSettings.configuration,
                        trackFolderName: orgConfigSettings.trackFolder
                    )
                }
                AppleLog.warning(
                    "TrackerPeer",
                    managedCaltopoCleared
                        ? "Tracker access paused; Tracker-managed CalTopo credentials cleared; offline RID remains available"
                        : "Tracker access paused; independent CalTopo credentials and offline RID remain available"
                )
                if let url = peerCoordinator.reauthenticationURL {
                    trackerReauthenticationURL = url
                    showTrackerReauthenticationPrompt = true
                }
            }
            .onChange(of: peerCoordinator.heartbeatAcknowledgedAtMilliseconds) { _, _ in
                publishLocalDeviceMarker()
            }
            .onChange(of: peerCoordinator.peers.count) { _, _ in
                publishLocalDeviceMarker(force: true)
            }
            .onReceive(ridTracks.$tracks) { tracks in
                updateProximityAlerts()
                reconcileStreamFlightPairings()
                let remoteID = droneConfirmations.reconcileActiveFlights(tracks.map(\.aircraftID))
                if !ProcessInfo.processInfo.arguments.contains("--suppress-auto-confirmation"),
                   pendingDroneConfirmation == nil,
                   let remoteID {
                    pendingDroneConfirmation = DroneConfirmationRequest(id: remoteID)
                }
            }
            .onReceive(peerCoordinator.objectWillChange) { _ in
                Task { @MainActor in
                    await Task.yield()
                    updateProximityAlerts()
                }
            }
            .onReceive(droneConfirmations.objectWillChange) { _ in
                Task { @MainActor in
                    await Task.yield()
                    updateProximityAlerts()
                    configureTrackArchive()
                    reconcileStreamFlightPairings()
                }
            }
            .onChange(of: proximityConfigurationFingerprint) { _, _ in
                peerCoordinator.updateProximityAlertDistanceFeet(
                    Double(orgConfigSettings.proximityAlertSpacingFeet)
                )
                updateProximityAlerts()
            }
            .onChange(of: trackPolicyConfigurationFingerprint) { _, _ in
                configureTrackPolicy()
                updateOperationalAlerts()
            }
        .overlay(alignment: .top) {
            VStack(spacing: 0) {
                if operationalAlerts.signalLossAlerts.first != nil || operationalAlerts.altitudeAlerts.first != nil {
                    OperationalAlertBanner(
                        signalLoss: operationalAlerts.signalLossAlerts.first,
                        altitude: operationalAlerts.altitudeAlerts.first,
                        onMap: { showTrackMap = true },
                        onMuteSignal: operationalAlerts.muteSignal,
                        onMuteAltitude: operationalAlerts.muteAltitude
                    )
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                if let alert = proximityAlerts.activeAlert {
                    ProximityAlertBanner(
                        alert: alert,
                        onMap: { showTrackMap = true },
                        onSuspend: proximityAlerts.suspend
                    )
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .animation(.default, value: alert.alertInstanceID)
                }
            }
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if organizationAccessBlocked {
                    organizationAccessGate
                } else {
                    monitoredRoot
                        .privacySensitive()
                }
            }
            .navigationTitle("RID-2-Caltopo")
            .navigationBarTitleDisplayMode(.inline)
        }
            .background {
                PrimaryWindowSceneReader { scene in
                    AppleApplicationCleanupCenter.shared.registerPrimaryWindowScene(scene)
                }
                .frame(width: 0, height: 0)
            }
            .onAppear {
                AppleApplicationCleanupCenter.shared.register(
                    markerCleanup: {
                        await ridTracks.setLocalDeviceMarkerPublishingEnabled(false)
                    },
                    fullCleanup: {
                        await bluetoothScanner.stop()
                        locationProvider.stop()
                        networkDiagnostics.stop()
                        streamRegistry.shutdown()
                        await ridTracks.setLocalDeviceMarkerPublishingEnabled(false)
                        await ridTracks.shutdown()
                        await peerCoordinator.shutdown()
                        await mediaMTX.shutdown()
                    }
                )
                reconcileOrganizationAuthentication()
                organizationAccessEvaluated = true
            }
            .onChange(of: orgConfigSettings.organizationName, initial: true) { oldValue, newValue in
                let wasRequired = OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(
                    organizationName: oldValue,
                    trackerURLPrefix: orgConfigSettings.trackerURLPrefix,
                    trackerAPIKey: orgConfigSettings.trackerAPIKey,
                    caltopoTeamID: caltopoSettings.teamID,
                    caltopoCredentialID: caltopoSettings.credentialID,
                    caltopoCredentialSecret: caltopoSettings.credentialSecret
                )
                let isRequired = OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(
                    organizationName: newValue,
                    trackerURLPrefix: orgConfigSettings.trackerURLPrefix,
                    trackerAPIKey: orgConfigSettings.trackerAPIKey,
                    caltopoTeamID: caltopoSettings.teamID,
                    caltopoCredentialID: caltopoSettings.credentialID,
                    caltopoCredentialSecret: caltopoSettings.credentialSecret
                )
                if isRequired && (!wasRequired || oldValue != newValue) {
                    organizationAccessGranted = false
                }
                reconcileOrganizationAuthentication()
            }
            .onChange(of: caltopoTeamsAuthenticationConfigured, initial: true) { wasRequired, isRequired in
                if isRequired && !wasRequired {
                    organizationAccessGranted = false
                }
                reconcileOrganizationAuthentication()
            }
            .onChange(of: scenePhase) { _, phase in
                guard organizationAuthenticationRequired else {
                    organizationAccessObscured = false
                    organizationAccessBackgroundedAt = nil
                    return
                }
                if phase == .background {
                    organizationAccessObscured = true
                    organizationAccessBackgroundedAt = Date()
                } else if phase == .inactive {
                    // Hide protected content from system overlays and app-switcher
                    // snapshots without expiring a still-recent authentication.
                    organizationAccessObscured = true
                } else if phase == .active {
                    organizationAccessGranted =
                        OrganizationAccessPolicy.authenticatedSessionRemainsValid(
                            accessWasGranted: organizationAccessGranted,
                            backgroundedAt: organizationAccessBackgroundedAt,
                            resumedAt: Date()
                        )
                    organizationAccessBackgroundedAt = nil
                    organizationAccessObscured = false
                    reconcileOrganizationAuthentication()
                }
            }
    }

    private var organizationAuthenticationRequired: Bool {
        OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(
            organizationName: orgConfigSettings.organizationName,
            trackerURLPrefix: orgConfigSettings.trackerURLPrefix,
            trackerAPIKey: orgConfigSettings.trackerAPIKey,
            caltopoTeamID: caltopoSettings.teamID,
            caltopoCredentialID: caltopoSettings.credentialID,
            caltopoCredentialSecret: caltopoSettings.credentialSecret
        )
    }

    private var caltopoTeamsAuthenticationConfigured: Bool {
        OrganizationAccessPolicy.requiresDeviceOwnerAuthentication(
            organizationName: "",
            caltopoTeamID: caltopoSettings.teamID,
            caltopoCredentialID: caltopoSettings.credentialID,
            caltopoCredentialSecret: caltopoSettings.credentialSecret
        )
    }

    private var organizationAuthenticationBypassedForTesting: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("--skip-organization-authentication")
        #else
        false
        #endif
    }

    private var organizationAccessBlocked: Bool {
        organizationAccessObscured
            || !organizationAccessEvaluated
            || (organizationAuthenticationRequired
                && !organizationAuthenticationBypassedForTesting
                && !organizationAccessGranted)
    }

    private var organizationAccessGate: some View {
        ZStack {
            Color(uiColor: .systemBackground).ignoresSafeArea()
            VStack(spacing: 18) {
                if organizationAccessEvaluated {
                    Image(systemName: "lock.shield.fill")
                        .font(.system(size: 52))
                        .foregroundStyle(.orange)
                    Text("Protected access locked")
                        .font(.title2.bold())
                    Text(
                        "Authenticate with this device's biometric or passcode to access "
                            + protectedAccessDescription + "."
                    )
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    if let organizationAuthenticationError {
                        Text(organizationAuthenticationError)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.red)
                    }
                    Button(organizationAuthenticationInFlight ? "Authenticating…" : "Unlock") {
                        authenticateOrganizationAccess()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(organizationAuthenticationInFlight)
                    Text(
                        "Set up a device passcode or biometrics in Settings before an ORG or "
                            + "CalTopo Teams configuration can be used."
                    )
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                } else {
                    ProgressView()
                    Text("Checking protected access…")
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: 560)
            .padding(32)
        }
    }

    private var protectedAccessDescription: String {
        let organizationName = orgConfigSettings.organizationName
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return organizationName.isEmpty
            ? "the configured CalTopo Teams account, maps, and settings"
            : organizationName + "'s CalTopo maps and settings"
    }

    private func reconcileOrganizationAuthentication() {
        guard organizationAuthenticationRequired,
              !organizationAuthenticationBypassedForTesting
        else {
            organizationAccessGranted = true
            organizationAuthenticationError = nil
            return
        }
        guard scenePhase == .active, !organizationAccessGranted else { return }
        authenticateOrganizationAccess()
    }

    private func authenticateOrganizationAccess() {
        guard organizationAuthenticationRequired,
              !organizationAuthenticationBypassedForTesting,
              !organizationAuthenticationInFlight
        else { return }

        let context = LAContext()
        context.localizedCancelTitle = "Cancel"
        context.localizedFallbackTitle = "Use Device Passcode"
        var policyError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &policyError) else {
            organizationAuthenticationError =
                "Device authentication is unavailable. Set up a device passcode or biometrics in Settings, then try again."
            AppleLog.error(
                "OrganizationAccess",
                "Device-owner authentication unavailable code=\(policyError?.code ?? -1)"
            )
            return
        }

        organizationAuthenticationInFlight = true
        organizationAuthenticationError = nil
        context.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: "Access your organization's CalTopo maps and settings"
        ) { success, error in
            Task { @MainActor in
                if success {
                    // LocalAuthentication reports success before its system UI has fully
                    // dismissed. Keep the protected gate mounted until that transition
                    // finishes so UIKit is not asked to replace navigation content mid-layout.
                    try? await Task.sleep(for: .milliseconds(500))
                    let pendingURL = pendingOrganizationAccessURL
                    let trackerCallbackPending =
                        pendingURL?.scheme?.lowercased() == "r2creauth"
                    resumeTrackerAfterBrowserReturnIfNeeded(
                        callbackPending: trackerCallbackPending
                    )
                    organizationAccessGranted = true
                    organizationAccessBackgroundedAt = nil
                    organizationAuthenticationInFlight = false
                    organizationAuthenticationError = nil
                    AppleLog.info("OrganizationAccess", "Device owner authenticated")
                    if let pendingURL {
                        pendingOrganizationAccessURL = nil
                        handleIncomingURL(pendingURL)
                    }
                } else {
                    organizationAuthenticationInFlight = false
                    organizationAccessGranted = false
                    organizationAuthenticationError =
                        "Authentication was not completed. Organization maps remain locked."
                    let code = (error as NSError?)?.code ?? -1
                    AppleLog.info(
                        "OrganizationAccess",
                        "Device-owner authentication not completed code=\(code)"
                    )
                }
            }
        }
    }

    private func resumeTrackerAfterBrowserReturnIfNeeded(callbackPending: Bool) {
        let shouldRetry = TrackerReauthenticationBrowserReturnPolicy.shouldRetryCredential(
            browserWasOpen: trackerReauthenticationBrowserOpen,
            challengeURLPresent: trackerReauthenticationURL != nil,
            callbackPending: callbackPending
        )
        trackerReauthenticationBrowserOpen = false
        guard shouldRetry else { return }

        trackerReauthenticationURL = nil
        showTrackerReauthenticationPrompt = false
        AppleLog.info(
            "TrackerPeer",
            "Returned from Tracker sign-in browser without an app callback; retrying Tracker access"
        )
        configurePeerCoordinator(forceReconnect: true)
    }

    private var rootScreen: some View {
        androidParityDashboard
    }

    private var idleTimeoutFingerprint: String {
        "\(orgConfigSettings.maximumIdleMinutes)"
    }

    private var bluetoothStatus: String {
        switch bluetoothScanner.state {
        case .idle: "Idle"
        case .waitingForBluetooth: "Waiting"
        case .scanning: "Scanning"
        case let .unavailable(reason): reason
        }
    }

    @ViewBuilder private func aircraftDestination(_ aircraftID: String) -> some View {
        if let track = ridTracks.tracks.first(where: { track in track.aircraftID == aircraftID }) {
            RIDAircraftDetailView(
                track: track,
                operatorLocation: locationProvider.lastLocation,
                identityStore: droneConfirmations,
                onConfirm: confirmDrone
            )
        } else {
            ContentUnavailableView("Aircraft no longer active", systemImage: "airplane")
        }
    }

    private var incidentSection: some View {
        Section("Incident") {
            LabeledContent("Organization", value: orgConfigSettings.organizationName.isEmpty ? "Not configured" : orgConfigSettings.organizationName)
            LabeledContent("Incident", value: orgConfigSettings.incident.isEmpty ? "Not selected" : orgConfigSettings.incident)
            if !orgConfigSettings.operationalPeriod.isEmpty {
                LabeledContent("Operational period", value: orgConfigSettings.operationalPeriod)
            }
            LabeledContent("Coordinator", value: peerCoordinator.status.rawValue)
            if !peerCoordinator.peers.isEmpty { LabeledContent("Peer zones", value: String(peerCoordinator.peers.count)) }
            LabeledContent("Team drones", value: String(droneConfirmations.importedMappingCount))
            Button("Import Config", systemImage: "qrcode.viewfinder") { showImportConfig = true }
            NavigationLink {
                AppleConfigurationTransferView(caltopo: caltopoSettings, organization: orgConfigSettings, identities: droneConfirmations)
            } label: { Label("Backup & Transfer", systemImage: "shippingbox") }
        }
    }

    private var androidParityDashboard: some View {
        ScrollView(.vertical) {
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 8) {
                    androidRestrictionStrip
                    androidOperationsHeader
                    androidAircraftTable
                }
                .padding(8)
                .frame(minWidth: 1_120, alignment: .topLeading)
            }
        }
        .background(Color(uiColor: .systemBackground))
    }

    private var androidOperationsHeader: some View {
        HStack(spacing: 2) {
            androidIncidentMapButton
            androidOpPeriodCell
            androidPilotCallsignCell
            androidHeaderCell("Coordinator", androidCoordinatorStatus, width: 150)
            androidHeaderCell("Team Drones", "\(droneConfirmations.importedMappingCount)", width: 110)
            androidHeaderCell("", deviceVersionText, width: 190)
            androidHeaderCell("Up Time", appUptimeText, width: 100)
            androidHeaderCell("Caltopo msg rtt", caltopoRTTText, width: 145)
            androidHeaderCell("Invalid RID msgs", "\(ridTracks.invalidObservationCount)", width: 125)
        }
        .padding(2)
        .background(Color.accentColor.opacity(0.18))
    }

    private var androidIncidentMapButton: some View {
        Button(action: openCaltopoMapActions) {
            VStack(spacing: 2) {
                Text(OperationalMainScreenPresentation.incidentMapLabel)
                    .font(.caption)
                Text(OperationalMainScreenPresentation.incidentMapValue(
                    mapID: caltopoSettings.mapID,
                    mapTitle: caltopoSettings.mapTitle
                ))
                    .font(.subheadline.bold())
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
            }
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .frame(width: 190, height: 58)
            .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(OperationalMainScreenPresentation.incidentMapLabel)
        .accessibilityValue(OperationalMainScreenPresentation.incidentMapValue(
            mapID: caltopoSettings.mapID,
            mapTitle: caltopoSettings.mapTitle
        ))
    }

    private var androidOpPeriodCell: some View {
        VStack(spacing: 2) {
            Text("Op Period")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField("", text: Binding(
                get: { orgConfigSettings.operationalPeriod },
                set: { orgConfigSettings.setOperationalPeriod($0) }
            ))
                .textFieldStyle(.roundedBorder)
                .multilineTextAlignment(.center)
                .accessibilityLabel("Op Period")
        }
        .padding(.horizontal, 6)
        .frame(width: 120, height: 58)
        .background(Color(uiColor: .secondarySystemBackground))
    }

    private var androidPilotCallsignCell: some View {
        VStack(spacing: 2) {
            Text("Pilot Callsign")
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField("", text: Binding(
                get: { droneConfirmations.preferredPilotCallsign },
                set: { droneConfirmations.setPreferredPilotCallsign($0) }
            ))
                .textFieldStyle(.roundedBorder)
                .multilineTextAlignment(.center)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .accessibilityLabel("Pilot Callsign")
        }
        .padding(.horizontal, 6)
        .frame(width: 140, height: 58)
        .background(Color(uiColor: .secondarySystemBackground))
    }

    private var androidRestrictionStrip: some View {
        HStack(spacing: 8) {
            if airspace.enabled || notams.state.visible {
                NavigationLink {
                    if usesAirspaceRestrictionStatus {
                        AppleAirspacePanel(
                            center: airspace,
                            notams: notams,
                            location: locationProvider.lastLocation
                        )
                    } else {
                        AppleNotamPanel(center: notams, location: locationProvider.lastLocation)
                    }
                } label: {
                    AppleOperationalStatusChipLabel(
                        title: conciseAirspaceOrNotamChipLabel,
                        tone: usesAirspaceRestrictionStatus ? airspaceChipTone : notamChipTone
                    )
                }
                .buttonStyle(.plain)
            }
            if landRestrictions.state.visible {
                NavigationLink {
                    AppleLandRestrictionPanel(center: landRestrictions, location: locationProvider.lastLocation)
                } label: {
                    AppleOperationalStatusChipLabel(
                        title: OperationalStatusChipText.land(
                            severity: landRestrictions.state.severity,
                            detailedLabel: landRestrictions.state.chipLabel
                        ),
                        tone: landRestrictionChipTone
                    )
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 6)
    }

    private var usesAirspaceRestrictionStatus: Bool {
        !notams.state.visible || airspace.state.severity != .normal
    }

    private var conciseAirspaceOrNotamChipLabel: String {
        if usesAirspaceRestrictionStatus {
            return OperationalStatusChipText.airspace(
                severity: airspace.state.severity,
                detailedLabel: airspace.state.chipLabel
            )
        }
        return OperationalStatusChipText.notam(
            severity: notams.state.chipSeverity,
            detailedLabel: notams.state.chipLabel
        )
    }

    private var notamChipTone: AppleOperationalStatusChipTone {
        switch notams.state.chipSeverity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    private var airspaceChipTone: AppleOperationalStatusChipTone {
        switch airspace.state.severity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    private var landRestrictionChipTone: AppleOperationalStatusChipTone {
        switch landRestrictions.state.severity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    private var androidAircraftTable: some View {
        VStack(alignment: .leading, spacing: 1) {
            if ridTracks.tracks.isEmpty {
                Text("No aircraft detected — Bluetooth and external Remote ID are monitoring.")
                    .foregroundStyle(.secondary)
                    .frame(width: 1_118, height: 58)
                    .background(Color(uiColor: .secondarySystemBackground))
            } else {
                if OperationalMainScreenPresentation.showsAircraftHeader(
                    activeTrackCount: ridTracks.tracks.count
                ) {
                    androidAircraftHeader
                }
                ForEach(ridTracks.tracks) { track in androidAircraftRow(track) }
            }
        }
    }

    private var androidAircraftHeader: some View {
        HStack(spacing: 1) {
            androidGroupedHeader(top: "", bottom: "", width: 28)
            androidGroupedHeader(top: "", bottom: "Track Label:", width: 200)
            androidGroupedHeader(top: "Drone→Bridge RSSI", bottom: "Remote ID:", width: 240)
            VStack(spacing: 1) {
                Text("Waypoints Received")
                    .font(.caption.bold())
                    .frame(width: 400, height: 24)
                    .background(Color.accentColor.opacity(0.16))
                HStack(spacing: 1) {
                    ForEach(["BT4:", "BT5:"], id: \.self) { label in
                        androidTableHeader(label, width: 120)
                    }
                    androidTableHeader("R2C:", width: 80)
                    androidTableHeader("Total:", width: 80)
                }
            }
            androidGroupedHeader(top: "Flight", bottom: "Duration:", width: 125)
            androidGroupedHeader(top: "", bottom: "R2C RTT:", width: 125)
        }
    }

    private func androidAircraftRow(_ track: RidAircraftTrack) -> some View {
        let identity = droneConfirmations.identity(for: track.aircraftID)
        let confirmed = droneConfirmations.isCurrentFlightConfirmed(track.aircraftID)
        return HStack(spacing: 1) {
            Group {
                if confirmed && caltopoSettings.configuration.liveConfiguration != nil {
                    Image(systemName: "globe.americas.fill")
                        .foregroundStyle(.tint)
                }
            }
                .frame(width: 28, height: 42)
                .background(Color(uiColor: .secondarySystemBackground))
            Button(identity?.displayLabel ?? "Confirm Drone") { selectedAircraftID = track.aircraftID }
                .font(.caption.monospaced())
                .lineLimit(1)
                .buttonStyle(.bordered)
                .frame(width: 200, height: 42)
                .background(Color(uiColor: .secondarySystemBackground))
            VStack(spacing: 2) {
                Text(track.aircraftID)
                    .font(.caption.monospaced())
                    .lineLimit(1)
                if let bridgeRSSI = OperationalMainScreenPresentation.droneToBridgeRSSIText(
                    track.lastDroneToBridgeSignalStrengthDbm
                ) {
                    Text(bridgeRSSI)
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(width: 240, height: 42)
            .background(Color(uiColor: .secondarySystemBackground))
            androidTransportCell(track, source: .bluetoothLegacy)
            androidTransportCell(track, source: .bluetoothExtended)
            androidTableValue("\(sourceCount(track, .trackerRelay))", width: 80)
            androidTableValue("\(track.points.count)", width: 80)
            androidTableValue(flightDuration(track), width: 125)
            androidTableValue("", width: 125)
        }
        .contentShape(Rectangle())
    }

    private func androidTransportCell(_ track: RidAircraftTrack, source: RidObservation.Source) -> some View {
        HStack(spacing: 1) {
            androidTableValue("\(sourceCount(track, source))", width: 80)
            androidSignalBars(
                rssi: track.lastDirectSignalSource == source
                    ? track.lastDirectSignalStrengthDbm : nil
            )
            .frame(width: 40, height: 42)
            .background(Color(uiColor: .secondarySystemBackground))
        }
    }

    private func androidSignalBars(rssi: Int?, colorByStrength: Bool = false) -> some View {
        let filled = rssi.map { value in
            if value >= -60 { return 4 }
            if value >= -70 { return 3 }
            if value >= -80 { return 2 }
            if value >= -90 { return 1 }
            return 0
        } ?? 0
        let filledColor: Color = if colorByStrength {
            switch filled {
            case 1: .red
            case 2: .yellow
            default: .green
            }
        } else {
            .green
        }
        let emptyColor = colorByStrength ? Color.secondary.opacity(0.2) : Color.green.opacity(0.25)
        return HStack(alignment: .bottom, spacing: 2) {
            ForEach(0 ..< 4, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1)
                    .fill(index < filled ? filledColor : emptyColor)
                    .frame(width: 4, height: CGFloat(5 + index * 4))
            }
        }
    }

    private func androidHeaderCell(_ title: String, _ value: String, width: CGFloat) -> some View {
        VStack(spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.subheadline.bold()).lineLimit(2).minimumScaleFactor(0.7)
        }
        .multilineTextAlignment(.center)
        .frame(width: width, height: 58)
        .background(Color(uiColor: .secondarySystemBackground))
    }

    private func androidTableHeader(_ value: String, width: CGFloat) -> some View {
        Text(value)
            .font(.caption.bold())
            .multilineTextAlignment(.center)
            .frame(width: width, height: 36)
            .background(Color.accentColor.opacity(0.16))
    }

    private func androidGroupedHeader(top: String, bottom: String, width: CGFloat) -> some View {
        VStack(spacing: 1) {
            Text(top)
                .font(.caption.bold())
                .frame(width: width, height: 24)
                .background(Color.accentColor.opacity(0.16))
            androidTableHeader(bottom, width: width)
        }
    }

    private func androidTableValue(_ value: String, width: CGFloat, monospaced: Bool = false) -> some View {
        Text(value)
            .font(monospaced ? .caption.monospaced() : .caption)
            .lineLimit(1)
            .minimumScaleFactor(0.65)
            .frame(width: width, height: 42)
            .background(Color(uiColor: .secondarySystemBackground))
    }

    private func sourceCount(_ track: RidAircraftTrack, _ source: RidObservation.Source) -> Int {
        track.acceptedCountBySource[source] ?? 0
    }

    private func flightDuration(_ track: RidAircraftTrack) -> String {
        guard let first = track.points.first?.receivedAt else { return "0:00" }
        let seconds = max(0, Int(track.lastSignalAt.timeIntervalSince(first)))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    private var deviceVersionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        return "\(AppleDeviceIdentity.displayName)\n\(version)"
    }

    private var appUptimeText: String {
        let seconds = max(0, Int(Date().timeIntervalSince(appStartedAt)))
        return String(format: "%d:%02d:%02d", seconds / 3_600, (seconds / 60) % 60, seconds % 60)
    }

    private var androidCoordinatorStatus: String {
        switch peerCoordinator.status {
        case .healthy: "Tracker verified"
        case .standby: "Tracker standby"
        case .degraded: "Tracker degraded"
        case .unavailable:
            peerCoordinator.statusDetail == AppleTrackerCoordinator.reenrollmentRequiredStatusDetail
                ? "Re-enroll required"
                : "Unavailable"
        case .standalone: "Disabled"
        case .unconfigured: "Not configured"
        case .connecting: "Connecting"
        }
    }

    private var caltopoRTTText: String {
        ridTracks.caltopoRTTMilliseconds.map { String(format: "%.3f sec", Double($0) / 1_000) } ?? "—"
    }

    private func dashboardIdentityCard(title: String, value: String, detail: String, icon: String) -> some View {
        GroupBox {
            HStack(spacing: 14) {
                Image(systemName: icon).font(.title2).foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.caption).foregroundStyle(.secondary)
                    Text(value).font(.title3.bold())
                    Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, minHeight: 64)
        }
    }

    private func dashboardStatus(title: String, value: String, icon: String) -> some View {
        HStack(spacing: 9) {
            Image(systemName: icon).foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.headline).lineLimit(1).minimumScaleFactor(0.75)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var nearbyAircraftSection: some View {
        Section("Nearby Aircraft") {
            if ridTracks.tracks.isEmpty {
                ContentUnavailableView(
                    "No aircraft detected",
                    systemImage: "airplane",
                    description: Text("Bluetooth is monitoring direct Remote ID and DS110-bridged Wi-Fi reports.")
                )
            } else {
                ForEach(ridTracks.tracks) { track in
                    NavigationLink {
                        RIDAircraftDetailView(
                            track: track,
                            operatorLocation: locationProvider.lastLocation,
                            identityStore: droneConfirmations,
                            onConfirm: confirmDrone
                        )
                    } label: {
                        RIDAircraftSummaryRow(
                            track: track,
                            operatorLocation: locationProvider.lastLocation,
                            identity: droneConfirmations.identity(for: track.aircraftID),
                            confirmedForCurrentFlight: droneConfirmations.isCurrentFlightConfirmed(track.aircraftID)
                        )
                    }
                }
            }
        }
    }

    @ViewBuilder private var trafficSeparationSection: some View {
        if let closestPair {
            Section("Traffic Separation") {
                LabeledContent("Closest pair", value: closestPair.firstAircraftID + " / " + closestPair.secondAircraftID)
                LabeledContent("Horizontal", value: separationFeet(closestPair.horizontalMeters))
                if let vertical = closestPair.verticalMeters { LabeledContent("Vertical", value: separationFeet(vertical)) }
                if let threeDimensional = closestPair.threeDimensionalMeters {
                    LabeledContent("3D separation", value: separationFeet(threeDimensional))
                }
                if proximityAlerts.canResume { Button("Resume Proximity Alert") { proximityAlerts.resume() } }
                Text("Alerts require at least one confirmed team drone and a current local tracker lease plus local Save confirmation.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder private var flightRestrictionsSection: some View {
        if notams.state.visible || airspace.enabled {
            Section("Flight Restrictions") {
                if airspace.enabled {
                    NavigationLink {
                        AppleAirspacePanel(
                            center: airspace,
                            notams: notams,
                            location: locationProvider.lastLocation
                        )
                    } label: {
                        LabeledContent("Controlled airspace", value: airspace.state.chipLabel)
                    }
                }
                if notams.state.visible {
                    NavigationLink { AppleNotamPanel(center: notams, location: locationProvider.lastLocation) } label: {
                        LabeledContent("Nearby NOTAMs", value: notams.state.chipLabel)
                    }
                    if notams.state.stale {
                        Label("NOTAM results are stale", systemImage: "clock.badge.exclamationmark").foregroundStyle(.orange)
                    }
                }
            }
        }
    }

    private var remoteIDSection: some View {
        Section("Remote ID") {
            HStack {
                Label("Bluetooth scanner", systemImage: "antenna.radiowaves.left.and.right")
                Spacer()
                Text(bluetoothStatus).foregroundStyle(.secondary)
            }
            Button(bluetoothScanner.state == .scanning ? "Stop Bluetooth Scan" : "Start Bluetooth Scan") {
                Task {
                    if bluetoothScanner.state == .scanning { await bluetoothScanner.stop() }
                    else { try? await bluetoothScanner.start() }
                }
            }
            if let aircraftID = bluetoothScanner.lastAircraftID { LabeledContent("Last aircraft", value: aircraftID) }
            if bluetoothScanner.rejectedAdvertisementCount > 0 {
                LabeledContent("Rejected Bluetooth packets", value: String(bluetoothScanner.rejectedAdvertisementCount))
            }
            Text("Wi-Fi Remote ID aircraft are received through the DS110 Bluetooth bridge and appear as Bluetooth observations.")
                .font(.caption).foregroundStyle(.secondary)
            HStack {
                Label("My location", systemImage: "location")
                Spacer()
                Text(locationProvider.statusText).foregroundStyle(.secondary)
            }
            if locationProvider.authorizationStatus == .denied {
                Text("Enable Location for RID2Caltopo in the Settings app to show the operator on the map.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            NavigationLink { operationalMapView } label: {
                LabeledContent("Live map", value: String(ridTracks.tracks.count) + " aircraft")
            }
            LabeledContent("Accepted track points", value: String(ridTracks.acceptedObservationCount))
            LabeledContent("Filtered observations", value: String(ridTracks.filteredObservationCount))
            LabeledContent("Duplicate position", value: String(ridTracks.duplicatePositionFilterCount))
            LabeledContent("Under minimum distance", value: String(ridTracks.minimumDistanceFilterCount))
            LabeledContent("Implausible speed", value: String(ridTracks.implausibleSpeedFilterCount))
            LabeledContent("Horizontal accuracy", value: String(ridTracks.horizontalAccuracyFilterCount))
            LabeledContent("Invalid observation", value: String(ridTracks.invalidObservationCount))
            LabeledContent("Archived tracks", value: String(ridTracks.archivedTrackCount))
            Text(ridTracks.archiveStatus).font(.caption).foregroundStyle(.secondary)
            LabeledContent("Tracker archive", value: ridTracks.trackerArchiveStatus)
            Text("Files › On My iPad › RID2Caltopo › RID2Caltopo › Tracks")
                .font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
            if let url = ridTracks.latestArchiveURL {
                ShareLink(item: url) { Label("Export Latest Track", systemImage: "square.and.arrow.up") }
            }
            Button("Archive Active Tracks") { ridTracks.archiveActiveTracks() }.disabled(ridTracks.tracks.isEmpty)
            NavigationLink {
                CaltopoSettingsView(
                    settings: caltopoSettings,
                    orgSettings: orgConfigSettings,
                    locationProvider: locationProvider,
                    importer: orgConfigImporter,
                    identityStore: droneConfirmations,
                    trackModel: ridTracks,
                    proximityAlerts: proximityAlerts,
                    iCloudBackup: iCloudBackup
                ) { configuration in
                    ridTracks.configureCaltopo(configuration, trackFolderName: orgConfigSettings.trackFolder)
                    clueStore.configure(configuration, trackFolderName: orgConfigSettings.trackFolder)
                }
            } label: { LabeledContent("CalTopo publishing", value: ridTracks.caltopoStatus) }
            NavigationLink { DiagnosticLogView(diagnostics: diagnostics) } label: {
                Label("Send diagnostics to developer…", systemImage: "doc.zipper")
            }
        }
    }

    private var anomalySection: some View {
        Section("Anomaly Detector") {
            Picker("Detector mode", selection: Binding(
                get: { videoFrames.anomalyMode },
                set: { videoFrames.setAnomalyMode($0) }
            )) {
                ForEach(AppleAnomalyMode.allCases) { mode in Text(mode.label).tag(mode) }
            }
            LabeledContent("MediaMTX ingest", value: endpoint.loopbackHlsURL?.absoluteString ?? "Unavailable")
            LabeledContent("Controller RTMP server", value: controllerRTMPURL).textSelection(.enabled)
            HStack {
                Label("Native bridge", systemImage: "video")
                Spacer()
                Text(mediaMTX.status).foregroundStyle(.secondary)
            }
            Button(mediaMTX.isRunning ? "Stop MediaMTX" : "Start MediaMTX") {
                if mediaMTX.isRunning { mediaMTX.stop() } else { mediaMTX.start() }
            }
            LabeledContent("Apple decoder", value: videoStatus)
            LabeledContent("Decoded frames", value: String(videoFrames.frameCount))
            LabeledContent("Analyzed frames", value: String(videoFrames.analyzedFrameCount))
            LabeledContent("Analysis drops", value: String(videoFrames.droppedAnalysisFrameCount))
            LabeledContent("Anomaly boxes", value: String(videoFrames.anomalyCount))
            LabeledContent("Frame size", value: videoFrames.dimensions)
            LabeledContent("MediaMTX publisher", value: videoFrames.mediaPublisherStatus)
            LabeledContent("Video recoveries", value: String(videoFrames.recoveryCount))
            LabeledContent("Last recovery", value: videoFrames.lastRecoveryReason)
            Button(videoFrames.state == .idle ? "Connect Video Decoder" : "Disconnect Video Decoder") {
                if videoFrames.state == .idle, let url = endpoint.loopbackHlsURL { videoFrames.start(url: url) }
                else { videoFrames.stop() }
            }
            NavigationLink { AnomalyLiveView(model: videoFrames, streamURL: endpoint.loopbackHlsURL) } label: {
                Label("Open Live Anomaly View", systemImage: "rectangle.inset.filled.and.person.filled")
            }
            NavigationLink { AppleAnomalySettingsView(model: videoFrames) } label: {
                Label("Advanced Anomaly Settings", systemImage: "slider.horizontal.3")
            }
            NavigationLink {
                AppleStreamsGridView(
                    registry: streamRegistry,
                    ingestAddress: controllerRTMPURL,
                    networkSSID: controllerWiFiSSID
                )
            } label: {
                LabeledContent("Live streams", value: String(liveStreamCount) + " / " + String(AppleStreamRegistry.maximumStreams))
            }
            NavigationLink { AppleCapturedVideoReviewView() } label: {
                Label("Play Captured Video", systemImage: "film")
            }
        }
    }

    private var operationalNotesSection: some View {
        Section {
            Text("Bluetooth Remote ID starts automatically, including Wi-Fi reports bridged by the DS110. iOS can discover Bluetooth devices in the background, but scans slow down and duplicate advertisements are coalesced. Video and anomaly processing require the app in the foreground.")
                .font(.footnote).foregroundStyle(.secondary)
            Text("The Simulator validates the UI and shared logic. Bluetooth, DS110 bridging, background behavior, and live camera streaming require the iPad or iPhone hardware gate.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var liveStreamCount: Int {
        streamRegistry.sessions.reduce(into: 0) { count, session in
            if session.state == .live { count += 1 }
        }
    }

    private var controllerWiFiSSID: String {
        networkDiagnostics.currentWiFiSSID ?? "Wi-Fi name unavailable"
    }

    private var currentIncidentName: String {
        if !caltopoSettings.mapID.isEmpty, !caltopoSettings.mapTitle.isEmpty {
            return caltopoSettings.mapTitle
        }
        return orgConfigSettings.incident.isEmpty ? "Not selected" : orgConfigSettings.incident
    }

    private var currentIncidentKey: String {
        let mapID = caltopoSettings.mapID.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        if !mapID.isEmpty {
            return "map:\(mapID)"
        }
        return "incident:\(currentIncidentName.lowercased())"
    }

    private var operationalMapView: some View {
        RIDTrackMapView(
            model: ridTracks,
            locationProvider: locationProvider,
            caltopoConfiguration: caltopoSettings.configuration,
            streamRegistry: streamRegistry,
            videoModel: streamRegistry.focusedSession.model,
            clueStore: clueStore,
            identityStore: droneConfirmations,
            orgSettings: orgConfigSettings,
            notams: notams,
            peerCoordinator: peerCoordinator,
            streamURL: streamRegistry.focusedPlaybackURL,
            ingestAddress: controllerRTMPURL,
            networkSSID: controllerWiFiSSID,
            bridgeSignalStrengthDbm: bluetoothScanner.bridgeSignalStrengthDbm,
            automaticStreamPairingAircraftID: $automaticStreamPairingAircraftID,
            onMapStatusTap: openCaltopoMapActions,
            onSwitchMap: { showTeamMaps = true },
            onDisconnectMap: {
                applyCaltopoConfiguration(caltopoSettings.disconnectMap())
            },
            onRestartStreams: {
                mediaMTX.restart(captureStreams: captureStreams)
            }
        )
    }

    private func updateIdleTimerPolicy(for phase: ScenePhase) {
        let keepScreenAwake = showTrackMap && phase == .active
        guard UIApplication.shared.isIdleTimerDisabled != keepScreenAwake else { return }
        UIApplication.shared.isIdleTimerDisabled = keepScreenAwake
        AppleLog.info(
            "Network",
            keepScreenAwake
                ? "Automatic screen lock disabled while Live View is active"
                : "Automatic screen lock restored"
        )
    }

    private func closeLiveView() {
        AppleLog.info("Navigation", "Live View back selected; returning to Main Screen")
        showTrackMap = false
    }

    private func togglePrimaryScreenFromTopBar() {
        if showTrackMap {
            AppleLog.info("Navigation", "Top bar double tap: returning to Main Screen")
            closeLiveView()
            return
        }
        guard !showCaltopoSettings,
              !showDiagnosticLogs,
              !showStatus,
              !showReleaseNotes,
              !showAboutPrivacy,
              !showConfigurationTransfer,
              selectedAircraftID == nil
        else { return }
        AppleLog.info("Navigation", "Top bar double tap: opening Live View")
        showTrackMap = true
    }

    private var activeCredentialNearExpiry: Bool {
        guard let expiry = profileLifecycle.availableProfiles.first(where: {
            $0.id == profileLifecycle.activeProfileID
        })?.expiresAt else { return false }
        return expiry > Date() && expiry.timeIntervalSinceNow <= 3_600
    }

    private func profileMenuDetail(_ profile: AppleOperationalProfileOption) -> String {
        guard let expiry = profile.expiresAt else { return profile.description }
        return "\(profile.description) • expires \(expiry.formatted(date: .abbreviated, time: .shortened))"
    }

    private func requestCredentialProfileSwitch(_ profileID: String) {
        guard profileID != profileLifecycle.activeProfileID else { return }
        if ridTracks.tracks.isEmpty {
            activateCredentialProfile(profileID)
        } else {
            pendingCredentialProfileID = profileID
            showCredentialSwitchConfirmation = true
        }
    }

    private func activateCredentialProfile(_ profileID: String) {
        guard orgConfigImporter.activateProfile(
            profileID,
            caltopoSettings: caltopoSettings,
            orgSettings: orgConfigSettings
        ) else { return }
        applyCaltopoConfiguration(caltopoSettings.configuration)
    }

    private func enforceMutualAidExpiryAtDeadline() async {
        guard let expiry = profileLifecycle.mutualAidExpiresAt else { return }
        let delaySeconds = max(0, expiry.timeIntervalSinceNow)
        if delaySeconds > 0 {
            let maximumSeconds = Double(UInt64.max) / 1_000_000_000
            let nanoseconds = UInt64(min(delaySeconds, maximumSeconds) * 1_000_000_000)
            do {
                try await Task.sleep(nanoseconds: nanoseconds)
            } catch {
                return
            }
        }
        guard !Task.isCancelled else { return }
        if orgConfigImporter.removeExpiredProfiles(
            caltopoSettings: caltopoSettings,
            orgSettings: orgConfigSettings
        ) {
            applyCaltopoConfiguration(caltopoSettings.configuration)
        }
    }

    private func openCaltopoMapActions() {
        if caltopoSettings.teamID.isEmpty
            || caltopoSettings.credentialID.isEmpty
            || caltopoSettings.credentialSecret.isEmpty {
            showImportConfig = true
        } else if caltopoSettings.mapID.isEmpty {
            showTeamMaps = true
        } else {
            showMapOptions = true
        }
    }

    private func applyCaltopoConfiguration(_ configuration: AppleCaltopoConfiguration) {
        ridTracks.configureCaltopo(
            configuration,
            trackFolderName: orgConfigSettings.trackFolder
        )
        clueStore.configure(configuration, trackFolderName: orgConfigSettings.trackFolder)
        configurePeerCoordinator()
        configureTrackArchive()
    }

    private func incidentMapOperationalState() -> IncidentMapOperationalState {
        IncidentMapOperationalState(
            connectedToIncidentMap: !caltopoSettings.mapID
                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            activeFlightCount: ridTracks.tracks.count,
            lastRIDMessageAt: ridTracks.lastRIDMessageAt,
            mapConnectedAt: incidentMapConnectedAt,
            hasManagedVideoOrTransfer:
                peerCoordinator.hasOperationalActivityPreventingIncidentDisconnect
                || !streamRegistry.activePublisherStreamIDs.isEmpty,
            offlineMapPreparationActive: AppleMapOfflineManager.shared.isRunning
        )
    }

    private func evaluateIncidentMapRelocation(now: Date = Date()) {
        guard let location = locationProvider.lastLocation else {
            incidentMapRelocationGuard.reset()
            return
        }
        let shouldDisconnect = incidentMapRelocationGuard.evaluate(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            horizontalAccuracyMeters: location.horizontalAccuracy,
            operationalState: incidentMapOperationalState(),
            now: now
        )
        guard shouldDisconnect else { return }
        disconnectInactiveIncidentMap(reason: "relocation", now: now)
    }

    private func scheduleIncidentMapBackgroundDisconnect(now: Date = Date()) {
        incidentMapBackgroundedAt = now
        if let location = locationProvider.lastLocation {
            incidentMapRelocationGuard.arm(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                horizontalAccuracyMeters: location.horizontalAccuracy
            )
        }
        incidentMapBackgroundDisconnectTask?.cancel()
        incidentMapBackgroundDisconnectTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(
                IncidentMapAutoDisconnectPolicy.backgroundGraceInterval
            ))
            while !Task.isCancelled, !caltopoSettings.mapID.isEmpty {
                if disconnectInactiveIncidentMap(reason: "display inactive") { break }
                try? await Task.sleep(for: .seconds(60))
            }
            incidentMapBackgroundDisconnectTask = nil
        }
    }

    private func handleIncidentMapBecameActive(now: Date = Date()) {
        let backgroundedAt = incidentMapBackgroundedAt
        incidentMapBackgroundDisconnectTask?.cancel()
        incidentMapBackgroundDisconnectTask = nil
        incidentMapBackgroundedAt = nil
        if let backgroundedAt,
           now.timeIntervalSince(backgroundedAt)
            >= IncidentMapAutoDisconnectPolicy.backgroundGraceInterval {
            disconnectInactiveIncidentMap(reason: "display inactive", now: now)
        }
    }

    @discardableResult
    private func disconnectInactiveIncidentMap(reason: String, now: Date = Date()) -> Bool {
        guard IncidentMapAutoDisconnectPolicy.isOperationallyIdle(
            incidentMapOperationalState(),
            now: now
        ) else {
            AppleLog.info(
                "Lifecycle",
                "Incident map retained for \(reason); active or recent operational work is present"
            )
            return false
        }
        let mapID = caltopoSettings.mapID
        AppleLog.warning(
            "Lifecycle",
            "Leaving incident map id=\(mapID) reason=\(reason); standalone tracker standby will follow"
        )
        incidentMapRelocationGuard.reset()
        applyCaltopoConfiguration(caltopoSettings.disconnectMap())
        return true
    }

    private var closestPair: RidPairSeparation? {
        RidTrafficSeparation.closestPair(in: ridTracks.tracks.map { track in
            RidTrafficPosition(
                aircraftID: track.aircraftID,
                latitude: track.lastObservation.latitude,
                longitude: track.lastObservation.longitude,
                altitudeMeters: track.lastObservation.altitudeMeters
            )
        })
    }

    private var statusSnapshot: AppleStatusSnapshot {
        AppleStatusSnapshot(
            bluetoothStatus: bluetoothStatus,
            bluetoothObservations: bluetoothScanner.observationCount,
            bluetoothRejected: bluetoothScanner.rejectedAdvertisementCount,
            locationStatus: locationProvider.statusText,
            configSource: orgConfigSettings.sourceDescription,
            organization: orgConfigSettings.organizationName,
            hasManagedTrackerEnrollment: orgConfigSettings.hasManagedTrackerEnrollment,
            notamProxyStatus: notams.state.errorMessage ?? notams.state.statusLine,
            incident: orgConfigSettings.incident,
            operationalPeriod: orgConfigSettings.operationalPeriod,
            trackerStatus: peerCoordinator.status.rawValue,
            trackerDetail: peerCoordinator.statusDetail,
            trackerArchiveStatus: ridTracks.trackerArchiveStatus,
            peerCount: peerCoordinator.peers.count,
            caltopoStatus: ridTracks.caltopoStatus,
            activeAircraft: ridTracks.tracks.count,
            acceptedTrackPoints: ridTracks.acceptedObservationCount,
            filteredObservations: ridTracks.filteredObservationCount,
            duplicatePositionFilters: ridTracks.duplicatePositionFilterCount,
            minimumDistanceFilters: ridTracks.minimumDistanceFilterCount,
            implausibleSpeedFilters: ridTracks.implausibleSpeedFilterCount,
            horizontalAccuracyFilters: ridTracks.horizontalAccuracyFilterCount,
            invalidObservationFilters: ridTracks.invalidObservationCount,
            archivedTracks: ridTracks.archivedTrackCount,
            mediaMTXStatus: mediaMTX.status,
            videoStatus: videoStatus,
            anomalyMode: videoFrames.anomalyMode.label,
            importedMappings: droneConfirmations.importedMappings
        )
    }

    private var peerConfigurationFingerprint: String {
        [
            orgConfigSettings.usePeers ? "1" : "0",
            orgConfigSettings.standaloneR2CCoordinationEnabled ? "1" : "0",
            orgConfigSettings.trackerURLPrefix,
            orgConfigSettings.trackerAPIKey.isEmpty ? "0" : "1",
            caltopoSettings.mapID,
            orgConfigSettings.trackFolder,
        ].joined(separator: "|")
    }

    private var proximityConfigurationFingerprint: String {
        "\(orgConfigSettings.proximityAlertSpacingFeet)|\(orgConfigSettings.predictiveHeadEnabled)"
    }

    private var managedVideoPresenceFingerprint: String {
        let streams = streamRegistry.sessions
            .map {
                let eligible = ManagedVideoPresencePolicy.hasRecentDecodedFrame(
                    frameCount: $0.model.frameCount,
                    decodedFrameAge: $0.model.decodedFrameAgeSeconds
                )
                return "\($0.sourcePath)|\($0.state.rawValue)|\(eligible ? 1 : 0)"
            }
            .sorted()
            .joined(separator: ",")
        return "\(currentIncidentKey)|\(currentIncidentName)|\(streamRegistry.managedPresenceRevision)|\(streams)"
    }

    private func configurePeerCoordinator(forceReconnect: Bool = false) {
        guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
        let arguments = ProcessInfo.processInfo.arguments
        peerCoordinator.configureOrganizationConfig(
            snapshotProvider: {
                try AppleManagedOrganizationConfig.snapshot(
                    caltopo: caltopoSettings,
                    organization: orgConfigSettings,
                    identities: droneConfirmations
                )
            },
            applyHandler: { snapshot, versionMs in
                try AppleManagedOrganizationConfig.apply(
                    snapshot: snapshot,
                    versionMs: versionMs,
                    caltopo: caltopoSettings,
                    organization: orgConfigSettings,
                    identities: droneConfirmations
                )
            }
        )
        peerCoordinator.updatePosition(locationProvider.lastLocation)
        peerCoordinator.updateProximityAlertDistanceFeet(
            Double(orgConfigSettings.proximityAlertSpacingFeet)
        )
        peerCoordinator.configure(
            usePeers: arguments.contains("--tracker-use-peers") || orgConfigSettings.usePeers,
            standaloneR2CCoordinationEnabled:
                orgConfigSettings.standaloneR2CCoordinationEnabled,
            trackerURLPrefix: argumentValue("--tracker-url") ?? orgConfigSettings.trackerURLPrefix,
            trackerAPIKey: argumentValue("--tracker-token") ?? orgConfigSettings.trackerAPIKey,
            mapID: argumentValue("--tracker-map") ?? caltopoSettings.mapID,
            forceReconnect: forceReconnect
        )
        publishLocalDeviceMarker(force: true)
    }

    private func publishLocalDeviceMarker(force: Bool = false) {
        guard !AppleApplicationCleanupCenter.shared.isShutdownRequested,
              let coordinate = locationProvider.lastLocation?.coordinate,
              CLLocationCoordinate2DIsValid(coordinate),
              !caltopoSettings.mapID.isEmpty
        else { return }
        let color: String
        switch peerCoordinator.status {
        case .healthy: color = "#2e7d32"
        case .standby: color = "#1976d2"
        case .standalone: color = "#1976d2"
        case .connecting: color = "#f9a825"
        case .degraded: color = "#f9a825"
        case .unavailable: color = peerCoordinator.coordinationRequired ? "#f9a825" : "#1976d2"
        case .unconfigured: color = "#1976d2"
        }
        let markerDescription = TrackerTabletLink.markerDescription(
            trackerURLPrefix: argumentValue("--tracker-url")
                ?? orgConfigSettings.trackerURLPrefix,
            tabletName: AppleDeviceIdentity.displayName,
            trackerConnected: peerCoordinator.status == .healthy
        )
        ridTracks.publishLocalDeviceMarker(
            CaltopoDeviceMarker(
                id: peerCoordinator.localZoneID,
                title: "R2C: \(AppleDeviceIdentity.displayName)",
                deviceName: AppleDeviceIdentity.displayName,
                latitude: coordinate.latitude,
                longitude: coordinate.longitude,
                description: markerDescription,
                color: color
            ),
            force: force
        )
    }

    private func removeLocalDeviceMarkerInBackground() {
        AppleApplicationCleanupCenter.shared.removeMarkerForBackgrounding()
    }

    private func configureTrackArchive() {
        ridTracks.configureTrackArchive(
            trackerURLPrefix: argumentValue("--tracker-url") ?? orgConfigSettings.trackerURLPrefix,
            trackerAPIKey: argumentValue("--tracker-token") ?? orgConfigSettings.trackerAPIKey,
            organization: orgConfigSettings.organizationName,
            incident: currentIncidentName == "Not selected" ? "" : currentIncidentName,
            operationalPeriod: orgConfigSettings.operationalPeriod,
            mapID: caltopoSettings.mapID,
            identities: droneConfirmations.importedMappings
        )
    }

    private func queueNextDroneConfirmation() {
        guard pendingDroneConfirmation == nil,
              let remoteID = droneConfirmations.reconcileActiveFlights(ridTracks.tracks.map(\.aircraftID))
        else { return }
        pendingDroneConfirmation = DroneConfirmationRequest(id: remoteID)
    }

    private func confirmDrone(_ identity: RidAircraftIdentity) {
        peerCoordinator.confirm(identity)
        automaticStreamPairingAircraftID = identity.remoteID
        guard let flightEpoch = ridTracks.peerTrafficFlightEpoch(remoteID: identity.remoteID) else {
            return
        }
        peerCoordinator.beginPeerTrafficAltitudeCalibration(
            remoteID: identity.remoteID,
            flightEpoch: flightEpoch
        )
        Task { @MainActor in
            let calibration = await ridTracks.lockPeerTrafficAltitudeCalibration(
                remoteID: identity.remoteID,
                flightEpoch: flightEpoch
            )
            peerCoordinator.finishPeerTrafficAltitudeCalibration(
                remoteID: identity.remoteID,
                flightEpoch: flightEpoch,
                calibration: calibration
            )
        }
    }

    private func updateProximityAlerts() {
        proximityAlerts.update(
            tracks: ridTracks.tracks,
            thresholdFeet: orgConfigSettings.proximityAlertSpacingFeet,
            predictiveEnabled: ProcessInfo.processInfo.arguments.contains("--demo-predictive-alert")
                || orgConfigSettings.predictiveHeadEnabled,
            operatorLocation: locationProvider.lastLocation,
            identityProvider: droneConfirmations.identity,
            alertEligibility: peerCoordinator.isLocalAlertEligible
        )
    }

    private func reconcileStreamFlightPairings() {
        let activeStreamIDs = streamRegistry.activePublisherStreamIDs
        automaticPairingOfferedStreamIDs.formIntersection(activeStreamIDs)
        for streamID in activeStreamIDs {
            let normalizedStreamID = streamID.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            let matches = ridTracks.tracks.filter { track in
                let identity = droneConfirmations.identity(for: track.aircraftID)
                return track.aircraftID.uppercased() == normalizedStreamID
                    || identity?.mappedID.uppercased() == normalizedStreamID
            }
            if matches.count == 1 {
                streamRegistry.pairIfUnbound(streamID: streamID, aircraftID: matches[0].aircraftID)
            }
        }

        guard automaticStreamPairingAircraftID == nil else { return }
        let liveUnpairedStreamIDs = activeStreamIDs.filter {
            streamRegistry.boundAircraftID(for: $0) == nil
                && !automaticPairingOfferedStreamIDs.contains($0)
        }
        let confirmedCandidateIDs = ridTracks.tracks.compactMap { track in
            droneConfirmations.isCurrentFlightConfirmed(track.aircraftID)
                ? track.aircraftID
                : nil
        }
        guard confirmedCandidateIDs.count == 1,
              let streamID = OperationalStreamDesignatorMatch.automaticPairingStreamID(
                confirmedCandidateID: confirmedCandidateIDs[0],
                activeCandidateIDs: ridTracks.tracks.map(\.aircraftID),
                liveUnpairedStreamIDs: Array(liveUnpairedStreamIDs)
              )
        else { return }
        automaticPairingOfferedStreamIDs.insert(streamID)
        AppleLog.info(
            "Streams",
            "Presenting telemetry pairing prompt for late stream=\(streamID) remoteID=\(confirmedCandidateIDs[0])"
        )
        automaticStreamPairingAircraftID = confirmedCandidateIDs[0]
    }

    private func updateOperationalAlerts() {
        let demoAlerts = ProcessInfo.processInfo.arguments.contains("--demo-operational-alert")
        operationalAlerts.update(
            tracks: ridTracks.tracks,
            altitudeDisplay: ridTracks.altitudeDisplayByAircraftID,
            operatorLocation: locationProvider.lastLocation,
            identityProvider: droneConfirmations.identity,
            alertEligibility: demoAlerts ? { _ in true } : peerCoordinator.isLocalAlertEligible,
            bridgeCheckDistanceFeet: Double(orgConfigSettings.bridgeCheckDistanceFeet),
            maximumTrackDelaySeconds: Double(orgConfigSettings.newTrackDelaySeconds),
            bridgeLastSeenAt: bluetoothScanner.bridgeLastSeenAt,
            pairedSEILastActivityAt: streamRegistry.djiSEILastActivityByAircraftID()
        )
    }

    private var trackPolicyConfigurationFingerprint: String {
        "\(orgConfigSettings.minimumTrackDistanceFeet)|\(orgConfigSettings.newTrackDelaySeconds)|\(orgConfigSettings.bridgeCheckDistanceFeet)"
    }

    private var updateAdvisoryPresented: Binding<Bool> {
        Binding(
            get: {
                let recommended = peerCoordinator.recommendedAppVersionCode
                return recommended > currentAppBuildNumber
                    && recommended != dismissedUpdateVersionCode
            },
            set: { presented in
                if !presented {
                    dismissedUpdateVersionCode = peerCoordinator.recommendedAppVersionCode
                }
            }
        )
    }

    private var currentAppBuildNumber: Int {
        Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
    }

    private func configureTrackPolicy() {
        ridTracks.configureTrackPolicy(
            minimumDistanceFeet: orgConfigSettings.minimumTrackDistanceFeet,
            activeTimeoutSeconds: orgConfigSettings.newTrackDelaySeconds
        )
    }

    private func monitorOperationalState() async {
        while !Task.isCancelled,
              !AppleApplicationCleanupCenter.shared.isShutdownRequested {
            updateOperationalAlerts()
            let activeAircraftIDs = ridTracks.tracks.map(\.aircraftID)
            let allActiveFlightsCoveredByFreshPairedSEI =
                streamRegistry.allAircraftHaveFreshPairedSEI(
                    aircraftIDs: activeAircraftIDs
                )
            bridgeAlerts.update(
                monitoringActive: OperationalBridgeAlertPolicy.shouldMonitor(
                    scannerRunning: bluetoothScanner.state == .scanning,
                    activeFlightCount: activeAircraftIDs.count,
                    allActiveFlightsCoveredByFreshPairedSEI:
                        allActiveFlightsCoveredByFreshPairedSEI
                ),
                lastPingAt: bluetoothScanner.bridgeLastSeenAt
            )
            if !ProcessInfo.processInfo.arguments.contains("--demo-notam") {
                notams.update(location: locationProvider.lastLocation)
                airspace.update(location: locationProvider.lastLocation)
                landRestrictions.update(location: locationProvider.lastLocation)
            }
            let altitudeText = operationalAlerts.altitudeAlerts.first.map {
                "Altitude Limit Exceeded: " + ($0.mappedID.isEmpty ? $0.remoteID : $0.mappedID)
            }
            let signalText = operationalAlerts.signalLossAlerts.first.map {
                ($0.bridgeRecentlySeen ? "Drone Location Stale: " : "Drone Signal Lost: ")
                    + ($0.mappedID.isEmpty ? $0.remoteID : $0.mappedID)
            }
            let proximityText = proximityAlerts.activeAlert.map { _ in "Aircraft Proximity Alert" }
            AppleExternalDisplayData.shared.update(
                tracks: ridTracks.tracks,
                alertText: altitudeText ?? signalText ?? proximityText
            )
            try? await Task.sleep(for: .seconds(1))
        }
    }

    private func argumentValue(_ flag: String) -> String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
        return arguments[index + 1]
    }

    private func separationFeet(_ meters: Double) -> String {
        "\(Int((meters * 3.28084).rounded())) ft"
    }

    private var videoStatus: String {
        switch videoFrames.state {
        case .idle: "Idle"
        case .connecting: "Connecting"
        case .streaming: "Streaming"
        case let .waitingForPublisher(reason): "Waiting for publisher: \(reason)"
        case let .failed(reason): "Failed: \(reason)"
        }
    }

    private func refreshControllerRTMPURL() {
        let interfaces = AppleNetworkAddress.ipv4DiagnosticSummary()
        if let address = networkDiagnostics.currentControllerIPv4Address {
            let nextURL = "rtmp://\(address)"
            let changed = nextURL != controllerRTMPURL
            controllerRTMPURL = nextURL
            if changed {
                AppleLog.info(
                    "Network",
                    "Controller RTMP server \(nextURL) interfaces=\(interfaces)"
                )
            }
        } else {
            let changed = controllerRTMPURL != "Connect this device to Wi-Fi"
            controllerRTMPURL = "Connect this device to Wi-Fi"
            if changed {
                AppleLog.warning(
                    "Network",
                    "No usable Wi-Fi/Ethernet IPv4 address for controller RTMP interfaces=\(interfaces)"
                )
            }
        }
    }

}

#Preview {
    ContentView()
}
private struct RecordingDownloadApprovalModifier: ViewModifier {
    @ObservedObject var coordinator: AppleTrackerCoordinator

    func body(content: Content) -> some View {
        content.alert(item: Binding(
            get: { coordinator.pendingRecordingDownloadRequest },
            set: { _ in }
        )) { request in
            Alert(
                title: Text("Recording Download Request"),
                message: Text(
                    "\(request.requesterEmail) requested the recorded video for "
                        + "\(request.droneDesignator). Approve transfer to the authorized tracker account?"
                ),
                primaryButton: .default(Text("Approve transfer")) {
                    coordinator.approveRecordingDownloadRequest()
                },
                secondaryButton: .destructive(Text("Decline")) {
                    coordinator.declineRecordingDownloadRequest()
                }
            )
        }
    }
}
