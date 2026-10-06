import Combine
import CoreLocation
import LocalAuthentication
import R2CCore
import R2CAppleRadios
import SwiftUI
import UIKit

/// Lays children out left to right and wraps onto further rows when the offered width runs
/// out, so fixed-width Android-parity cells and status chips stay on screen in portrait,
/// on iPhone, and in narrow multitasking windows instead of clipping past the edge.
struct AppleWrappingRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, maxWidth: proposal.width ?? .infinity)
        return CGSize(width: rows.width, height: rows.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = arrange(subviews, maxWidth: bounds.width)
        for item in rows.items {
            subviews[item.index].place(
                at: CGPoint(x: bounds.minX + item.frame.minX, y: bounds.minY + item.frame.minY),
                proposal: ProposedViewSize(item.frame.size)
            )
        }
    }

    private func arrange(
        _ subviews: Subviews,
        maxWidth: CGFloat
    ) -> (items: [(index: Int, frame: CGRect)], width: CGFloat, height: CGFloat) {
        var rows: [[(index: Int, size: CGSize)]] = [[]]
        var x: CGFloat = 0
        for index in subviews.indices {
            var size = subviews[index].sizeThatFits(.unspecified)
            if size.width > maxWidth {
                size = subviews[index].sizeThatFits(ProposedViewSize(width: maxWidth, height: nil))
            }
            if x > 0, x + size.width > maxWidth {
                rows.append([])
                x = 0
            }
            rows[rows.count - 1].append((index, size))
            x += size.width + spacing
        }
        var items: [(index: Int, frame: CGRect)] = []
        var y: CGFloat = 0
        var widest: CGFloat = 0
        for row in rows where !row.isEmpty {
            let rowHeight = row.map(\.size.height).max() ?? 0
            var rowX: CGFloat = 0
            for entry in row {
                let origin = CGPoint(x: rowX, y: y + (rowHeight - entry.size.height) / 2)
                items.append((entry.index, CGRect(origin: origin, size: entry.size)))
                rowX += entry.size.width + spacing
            }
            widest = max(widest, rowX - spacing)
            y += rowHeight + spacing
        }
        return (items, widest, max(0, y - spacing))
    }
}

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
                    + "independently configured CalTopo access remain available. Organization "
                    + "aircraft and managed CalTopo credentials load after sign-in completes."
            )
        }
    }
}

struct ContentView: View {
    private let endpoint = MediaStreamEndpoint(designator: "demo")
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// Dashboard grid cells keep their Android-parity sizes at the default text size and
    /// grow with Dynamic Type (relative to caption, the grid's base text style) so labels
    /// are not truncated; 100 means unscaled.
    @ScaledMetric(relativeTo: .caption) private var dashboardCellScalePercent: CGFloat = 100
    @StateObject private var bluetoothScanner = BluetoothRIDScanner()
    @StateObject private var bridgeAlerts = AppleDroneScoutBridgeAlertCenter()
    @StateObject private var mediaMTX = MediaMTXViewModel()
    @ObservedObject private var videoFrames = AppleStreamRegistry.shared.primaryModel
    @StateObject private var ridTracks = RIDTrackViewModel()
    @StateObject private var mapViewportMemory = AppleMapViewportMemory()
    @StateObject private var locationProvider = AppleLocationProvider()
    @StateObject private var caltopoSettings = AppleCaltopoSettings()
    @State private var showingFlightAllowance = false
    @State private var showingBluetoothDisabled = false
    @StateObject private var clueStore = AppleClueStore()
    @StateObject private var diagnostics = AppleDiagnosticsCenter()
    @StateObject private var droneConfirmations = AppleDroneConfirmationStore()
    @StateObject private var orgConfigSettings = AppleOrgConfigSettings()
    @StateObject private var orgConfigImporter = AppleOrgConfigImporter()
    @StateObject private var deviceReconciliation = AppleTrackerDeviceReconciliation()
    @ObservedObject private var profileLifecycle = AppleCaltopoProfileLifecycle.shared
    @StateObject private var peerCoordinator = AppleTrackerCoordinator()
    @StateObject private var proximityAlerts = AppleProximityAlertCenter()
    @StateObject private var operationalAlerts = AppleOperationalAlertCenter()
    @ObservedObject private var alertBell = AppleAlertBellCenter.shared
    /// Widens alert freshness while locked; set by AppleAlertCoordinator lifecycle.
    @State private var alertEvaluationInBackground = false
    @StateObject private var notams = AppleNotamCenter.shared
    @StateObject private var airspace = AppleAirspaceCenter.shared
    @StateObject private var landRestrictions = AppleLandRestrictionCenter.shared
    @StateObject private var streamRegistry = AppleStreamRegistry.shared
    @ObservedObject private var networkDiagnostics = AppleNetworkDiagnosticCenter.shared
    private let iCloudBackup = AppleICloudBackupCenter.shared
    @State private var showTrackMap = ProcessInfo.processInfo.arguments.contains("--show-map")
    @State private var showCaltopoSettings = false
    @State private var showProximitySettings = false
    @State private var showDiagnosticLogs = false
    @State private var showStatus = false
    @State private var showReleaseNotes = false
    @State private var showTerms = false
    @State private var showAboutPrivacy = false
    @State private var showImportConfig = false
    @State private var importConfigNotice: ConfigImportNotice?
    @State private var pendingImportConfigNotice: ConfigImportNotice?
    @State private var showConfigurationTransfer = false
    @State private var showStorageManagement = false
    @State private var showPersonalAccountEditor = false
    @State private var showPersonalCredentialLogin = false
    @State private var openMapsAfterPersonalLogin = false
    @State private var showTeamMaps = false
    @State private var showMapOptions = false
    @State private var showConfirmExit = false
    @State private var pendingImportToken = ""
    @State private var selectedAircraftID: String?
    @State private var addRidMapRemoteID: String?
    @State private var confirmationPresentation = PendingFlightConfirmation()
    private var pendingDroneConfirmation: DroneConfirmationRequest? {
        get { confirmationPresentation.remoteID.map { DroneConfirmationRequest(id: $0) } }
        nonmutating set { confirmationPresentation.present(remoteID: newValue?.id) }
    }
    @State private var automaticStreamPairingAircraftID: String?
    @State private var automaticPairingOfferedStreamIDs: Set<String> = []
    @State private var controllerRTMPURL = "Connect this device to Wi-Fi or Ethernet"
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
    /// Access was granted and then cleared by a device lock: the device unlock that follows may satisfy
    /// re-authentication (biometric reuse window), like Android's screen-lock handoff.
    @State private var organizationAccessRevokedByDeviceLock = false
    @State private var organizationAuthenticationInFlight = false
    @State private var organizationAuthenticationError: String?
    @State private var pendingOrganizationAccessURL: URL?
    @State private var incidentMapConnectedAt = Date()
    @State private var incidentMapBackgroundedAt: Date?
    @State private var incidentMapBackgroundDisconnectTask: Task<Void, Never>?
    @State private var incidentMapRelocationGuard = IncidentMapRelocationGuard()
    @AppStorage("video.captureStreams") private var captureStreams = true
    @AppStorage("video.restrictMediaServerAccess") private var restrictMediaServerAccess = true
    @AppStorage("rid.minimumHorizontalAccuracyCode") private var minimumHorizontalAccuracyCode = 9

    // Bound generic view depth: the signed device runtime otherwise overflows
    // while decoding the combined navigation, presentation, and lifecycle type.
    private func mainHeaderTitle(centered: Bool) -> some View {
        Menu {
            Button { requestCredentialProfileSwitch("personal") } label: {
                Label("Personal: \(caltopoSettings.personalUsername.isEmpty ? "Sign in" : caltopoSettings.personalUsername)", systemImage: caltopoSettings.usesPersonalCredentials ? "checkmark.circle.fill" : "person.circle")
            }
            Button("Edit personal account", systemImage: "pencil") { showPersonalAccountEditor = true }
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
                        Image(systemName: !caltopoSettings.usesPersonalCredentials && profile.id == profileLifecycle.activeProfileID
                            ? "checkmark.circle.fill" : "circle")
                    }
                }
            }
        } label: {
            VStack(alignment: centered ? .center : .leading, spacing: 1) {
                Text("RID-2-Caltopo")
                    .font(.headline)
                HStack(spacing: 2) {
                    Text("Credentials: \(caltopoSettings.usesPersonalCredentials ? "Personal: " + caltopoSettings.personalUsername : profileLifecycle.activeCredentialLabel)")
                        .font(.caption)
                        .foregroundStyle(activeCredentialNearExpiry ? .orange : .secondary)
                    Image(systemName: "chevron.down")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                if caltopoSettings.isLoadingPersonalMaps {
                    ProgressView("Checking personal credentials…").font(.caption)
                }
                AppleOrganizationUserLabel(compactHeader: true)
            }
            .lineLimit(1)
            .foregroundStyle(.primary)
        }
        .disabled(caltopoSettings.isLoadingPersonalMaps)
        .accessibilityLabel("Selected credentials: \(caltopoSettings.usesPersonalCredentials ? "Personal: " + caltopoSettings.personalUsername : profileLifecycle.activeCredentialLabel)")
    }


    private var navigationRoot: some View {
        AnyView(rootScreen)
            .toolbar(showTrackMap || showCaltopoSettings || showConfigurationTransfer || showStorageManagement || showDiagnosticLogs || showStatus || showReleaseNotes || showTerms || showAboutPrivacy || selectedAircraftID != nil || addRidMapRemoteID != nil ? .visible : .hidden, for: .navigationBar)
            .safeAreaInset(edge: .top, spacing: 0) {
                AdaptiveOperatorHeader { centered in
                    mainHeaderTitle(centered: centered)
                } actions: {
                    // Unified session alert bell lives beside the Bridge RSSI gauge so it
                    // never overlays the horizontally scrolling chip / URL row.
                    AppleAlertStatusBell(center: alertBell)
                        .padding(.trailing, 6)
                    Button {
                        if showTrackMap { closeLiveView() }
                        else { openLiveViewFromBridgeChip() }
                    } label: {
                        BridgeSignalIndicator(rssi: bluetoothScanner.bridgeSignalStrengthDbm)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(showTrackMap ? "Show Main Screen" : "Show Live View")
                    .padding(.trailing, 16)
                    MainScreenMenu(
                        showTrackMap: $showTrackMap,
                        showDiagnosticLogs: $showDiagnosticLogs,
                        showStatus: $showStatus,
                        showReleaseNotes: $showReleaseNotes,
                        showImportConfig: $showImportConfig,
                        showConfigurationTransfer: $showConfigurationTransfer,
                        showStorageManagement: $showStorageManagement,
                        showCaltopoSettings: $showCaltopoSettings,
                        showAboutPrivacy: $showAboutPrivacy,
                        showTerms: $showTerms,
                        showConfirmExit: $showConfirmExit
                    ).equatable()
                }
            }
            .navigationDestination(isPresented: $showTrackMap) {
                operationalMapView
                    .navigationBarBackButtonHidden(true)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            LiveViewBackButton(onDismiss: closeLiveView)
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
            .sheet(isPresented: $alertBell.showPanel) {
                AppleAlertStatusPanel(center: alertBell)
            }
            .sheet(isPresented: $showProximitySettings) {
                NavigationStack {
                    CaltopoSettingsView(
                        settings: caltopoSettings, orgSettings: orgConfigSettings,
                        locationProvider: locationProvider, importer: orgConfigImporter,
                        identityStore: droneConfirmations, trackModel: ridTracks,
                        proximityAlerts: proximityAlerts, bridgeAlerts: bridgeAlerts,
                        iCloudBackup: iCloudBackup, startAtProximity: true
                    ) { configuration in
                        ridTracks.configureCaltopo(configuration, trackFolderName: orgConfigSettings.trackFolder)
                        clueStore.configure(configuration, trackFolderName: orgConfigSettings.trackFolder)
                    }
                    .toolbar { ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { showProximitySettings = false }
                    } }
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
                    bridgeAlerts: bridgeAlerts,
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
            .navigationDestination(isPresented: $showStorageManagement) {
                AppleStorageManagementView(trackModel: ridTracks)
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
            .navigationDestination(isPresented: $showTerms) {
                ApplicationTermsReadOnlyView()
            }
            .navigationDestination(isPresented: $showAboutPrivacy) {
                AboutPrivacyView()
            }
            .navigationDestination(item: $selectedAircraftID) { aircraftID in
                aircraftDestination(aircraftID)
            }
            .navigationDestination(item: $addRidMapRemoteID) { remoteID in
                RidMappingAdminView(
                    organization: orgConfigSettings,
                    identities: droneConfirmations,
                    initialRemoteID: remoteID,
                    onSaved: { savedRemoteID in
                        addRidMapRemoteID = nil
                        pendingDroneConfirmation = DroneConfirmationRequest(id: savedRemoteID)
                    }
                )
            }
    }

    private var authenticationPresentationRoot: some View {
        AnyView(navigationRoot)
            .sheet(isPresented: $showImportConfig, onDismiss: {
                if let notice = pendingImportConfigNotice {
                    pendingImportConfigNotice = nil
                    importConfigNotice = notice
                } else if trackerReauthenticationURL != nil {
                    showTrackerReauthenticationPrompt = true
                }
            }) {
                NavigationStack {
                    ConfigImportView(
                        initialToken: pendingImportToken,
                        importer: orgConfigImporter,
                        caltopoSettings: caltopoSettings,
                        orgSettings: orgConfigSettings,
                        identityStore: droneConfirmations
                    ) { notice in
                        pendingImportConfigNotice = notice
                    }
                    .id(pendingImportToken)
                }
                // A 440pt detent hides the Import action below the fold on compact iPhones.
                .presentationDetents(horizontalSizeClass == .compact ? [.large] : [.height(440), .large])
                .presentationDragIndicator(.visible)
            }
            .sheet(isPresented: $showTeamMaps) {
                CaltopoTeamMapBrowser(settings: caltopoSettings, onCredentialSelect: requestCredentialProfileSwitch) { map in
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
                Button("Switch Map") { openMapBrowser() }
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
            .alert("Switch Credentials?", isPresented: $showCredentialSwitchConfirmation) {
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
                        + "\(ridTracks.tracks.count) active aircraft before switching credentials?"
                )
            }
            .alert(item: $importConfigNotice) { notice in
                Alert(
                    title: Text(notice.title),
                    message: Text(notice.message),
                    dismissButton: .default(Text("OK"))
                )
            }
    }

    private var startupRoot: some View {
        AnyView(authenticationPresentationRoot)
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
            .sheet(item: Binding(
                get: { pendingDroneConfirmation },
                set: { pendingDroneConfirmation = $0 }
            ), onDismiss: queueNextDroneConfirmation) { request in
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
                .onAppear { AppleLog.info("DroneConfirmation", "Confirmation panel presented remoteId=\(request.id)") }
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
                ridTracks.configureFlightRecording(droneConfirmations.isCurrentFlightConfirmed)
                ridTracks.configurePublicationSuppression { remoteID in
                    !droneConfirmations.isCurrentFlightConfirmed(remoteID) || droneConfirmations.isIgnored(remoteID)
                }
                ridTracks.configurePairedVideoActivity(
                    { streamRegistry.flightActivityByAircraftID() },
                    validatedSEIPositionProvider: { tracks, date in
                        streamRegistry.freshOperationalDJIPositionByAircraftID(
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
                ridTracks.attachClueStore(clueStore)
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
                    peerConfirmationClearer: droneConfirmations.clearPeerConfirmation,
                    flightEndConsumer: { remoteID in
                        AppleLog.info("DroneConfirmation", "Flight end remoteId=\(remoteID) \(confirmationMediaDiagnostics)")
                        droneConfirmations.endFlight(remoteID: remoteID)
                        dismissEndedDroneConfirmation(remoteID)
                    }
                )
                configurePeerCoordinator()
                Task { await refreshManagedOrganizationConfiguration() }
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
                #if DEBUG
                // Screenshot hook: --demo-proximity-consent[=N] opens the consent sheet with the first N boxes checked.
                if let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("--demo-proximity-consent") }) {
                    try? await Task.sleep(for: .milliseconds(500))
                    showProximitySettings = true
                    try? await Task.sleep(for: .seconds(1))
                    proximityAlerts.requestEnable()
                    let checked = Int(argument.split(separator: "=").last ?? "") ?? 0
                    for index in 0..<min(max(checked, 0), RidProximityConsent.noticeParagraphCount) {
                        proximityAlerts.toggleAcknowledgment(index)
                    }
                }
                #endif
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
                while !Task.isCancelled {
                    if AppleApplicationCleanupCenter.shared.isShutdownRequested {
                        break
                    }
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
        AnyView(startupRoot)
            .alert("Flight Storage", isPresented: Binding(
                get: { mediaMTX.storageWarning != nil },
                set: { if !$0 { mediaMTX.storageWarning = nil } }
            )) {
                Button(mediaMTX.storageNeedsAllowance ? "Increase Allowance" : "Manage Storage") { mediaMTX.storageWarning = nil; showingFlightAllowance = true }
                Button("Later", role: .cancel) { mediaMTX.storageWarning = nil }
            } message: {
                Text(mediaMTX.storageWarning ?? "")
            }
            .sheet(isPresented: $showingFlightAllowance) {
                NavigationStack {
                    Group {
                        if mediaMTX.storageNeedsAllowance {
                            Form { AppleFlightStorageLimits() }.navigationTitle("Flight Storage Allowance")
                        } else {
                            AppleStorageManagementView(trackModel: ridTracks)
                        }
                    }
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showingFlightAllowance = false } } }
                }
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
            // RTMP ingest health and storage pruning run in AppleAlertCoordinator's
            // 15 s maintenance so they continue while the display is locked.
            .task(id: scenePhase == .active) {
                guard scenePhase == .active else { return }
                // NWPath can stay identical across SSID changes, including the same IP/subnet.
                // Read local identity only; this does not probe the network or restart streams.
                while !Task.isCancelled,
                      !AppleApplicationCleanupCenter.shared.isShutdownRequested {
                    await networkDiagnostics.refresh(reason: .foregroundIdentityCheck)
                    do { try await Task.sleep(for: .seconds(3)) }
                    catch { return }
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
                deviceReconciliation.markPending()
                reconcileTrackerDevice()
                AppleLog.info(
                    "TrackerPeer",
                    "Reauthentication completed; configuration preserved"
                )
                notams.configure(
                    faaProxyURL: orgConfigSettings.faaProxyURL,
                    trackerURLPrefix: orgConfigSettings.trackerURLPrefix,
                    trackerAPIKey: orgConfigSettings.trackerAPIKey
                )
                notams.enabled = true
                notams.refreshNow(location: locationProvider.lastLocation)
                airspace.update(location: locationProvider.lastLocation)
                configurePeerCoordinator(forceReconnect: true)
                Task { await refreshManagedOrganizationConfiguration(force: true) }
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
        guard AndroidConfigTokenCodec.decode(rawValue) != nil || MutualAidPackageTransferToken.decode(rawValue) != nil else {
            AppleLog.error("OrgConfig", "Ignored unrecognised URL scheme payload")
            return
        }
        pendingImportToken = rawValue
        showImportConfig = true
    }

    private var mediaMonitoredRoot: some View {
        AnyView(lifecycleRoot)
            .onChange(of: bluetoothScanner.state, initial: true) { _, state in
                if state == .bluetoothDisabled { showingBluetoothDisabled = true }
                else if state == .scanning { showingBluetoothDisabled = false }
            }
            .alert("Bluetooth is disabled", isPresented: $showingBluetoothDisabled) {
                Button("Continue", role: .cancel) { }
            } message: {
                Text("Bluetooth is disabled. Bluetooth Remote ID broadcasts, including those relayed by a bridge, will not be detected. Video and SEI telemetry can continue.")
            }
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
            // Bluetooth ingress and scan-restart diagnostics are logged by
            // AppleAlertCoordinator so they continue while the display is locked.
            .onChange(of: mediaMTX.status) { _, status in
                AppleLog.info("MediaMTX", status)
            }
            .onChange(of: restrictMediaServerAccess) { _, _ in
                guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                mediaMTX.restart(captureStreams: captureStreams)
            }
            .onChange(of: captureStreams) { _, enabled in
                guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                mediaMTX.restart(captureStreams: enabled)
            }
    }

    private var lifecycleEventRoot: some View {
        AnyView(mediaMonitoredRoot)
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
            .onChange(of: locationProvider.lastLocation?.timestamp, initial: true) { _, _ in
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
            .onChange(of: networkDiagnostics.checkRecoveryGeneration) { _, _ in
                guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                AppleLog.info("NetworkChecks", "Network path available; refreshing safety data and organization Tracker check-in")
                airspace.refreshNow(location: locationProvider.lastLocation)
                notams.refreshNow(location: locationProvider.lastLocation)
                landRestrictions.refreshNow(location: locationProvider.lastLocation)
                Task {
                    await refreshManagedOrganizationConfiguration(force: true, waitForInFlight: true)
                }
                Task {
                    guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                    if AppleAircraftOrganizationAccess.belongsToOrganization {
                        _ = await AppleAircraftOrganizationAccess.refresh(baseURL: AppleAircraftOrganizationAccess.scope)
                    }
                }
            }
            .onChange(of: networkDiagnostics.currentSnapshotID) { _, _ in
                refreshControllerRTMPURL()
            }
            .onChange(of: showTrackMap) { _, showing in
                guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
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
            }
            .onChange(of: peerCoordinator.trackerSilenceGeneration) { _, _ in
                guard scenePhase != .active else { return }
                _ = disconnectInactiveIncidentMap(reason: "tracker silent for 60 seconds")
            }
    }

    private var monitoredRoot: some View {
        AnyView(lifecycleEventRoot)
            .task {
                // After a notice-version change, show the new notice once, over proximity settings.
                // monitoredRoot exists only after terms and organization access checks pass.
                guard proximityAlerts.reacknowledgmentPending else { return }
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled else { return }
                showProximitySettings = true
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled else { return }
                proximityAlerts.beginReacknowledgment()
            }
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
            .onReceive(NotificationCenter.default.publisher(
                for: AppleTrackArchiveStore.authorizationRejectedNotification
            )) { notification in
                let credential = orgConfigSettings.trackerAPIKey
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard notification.userInfo?["credential"] as? String == credential,
                      lastTrackerReenrollmentNoticeCredential != credential else { return }
                lastTrackerReenrollmentNoticeCredential = credential
                showTrackerReenrollmentRequired = true
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
                guard generation > 0, peerCoordinator.reauthenticationURL != nil else { return }
                deviceReconciliation.markPending()
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
                    showTrackerReauthenticationPrompt = !showImportConfig
                }
            }
            .onChange(of: peerCoordinator.heartbeatAcknowledgedAtMilliseconds) { _, _ in
                publishLocalDeviceMarker()
                reconcileTrackerDevice()
            }
            .onChange(of: peerCoordinator.peers.count) { _, _ in
                publishLocalDeviceMarker(force: true)
            }
            // RID track snapshots are handled by AppleAlertCoordinator (handleTrackSnapshot),
            // which keeps running while this subtree is replaced by the access gate.
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
        // Reserve the measured status height so larger text and alert panels
        // push the restriction chips down instead of drawing over them.
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 4) {
                ForEach(ridTracks.tracks) { track in
                    if let identity = droneConfirmations.identity(for: track.aircraftID),
                       let profile = OperatingProfiles.active(identity.flightReadiness)["profile"] as? [String: Any] {
                        Text(identity.mappedID + ": " + (profile["name"] as? String ?? "Other / details pending"))
                            .font(.caption2).padding(2).background(.regularMaterial)
                    }
                }
                ShortFlightRecordingPanel(gate: ridTracks.shortFlightDecisions)
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
            }
            .padding(.vertical, 4)
            .background(Color(uiColor: .systemBackground))
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
            // Bound from body, not monitoredRoot: the access gate replaces
            // monitoredRoot while the app is inactive or in background.
            .task { startAlertCoordinator() }
            .modifier(TrackerDeviceReconciliationModifier(
                model: deviceReconciliation,
                signInRequired: handleDeviceReconciliationSignIn,
                authorizationRejected: handleDeviceReconciliationRejection
            ) {
                configurePeerCoordinator(forceReconnect: true)
            })
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
                        + "organization enrollment QR to re-enroll this tablet. Saved flights stay "
                        + "on this tablet; resubmit recent tracks after reconnecting. Offline RID and "
                        + "the incident map remain available."
                )
            }

        .sheet(isPresented: $showPersonalAccountEditor, onDismiss: {
            Task { _ = await caltopoSettings.loadPersonalMaps() }
        }) {
            NavigationStack {
                AppleCaltopoPersonalProbeView()
            }
        }
        .sheet(isPresented: $showPersonalCredentialLogin, onDismiss: {
            if openMapsAfterPersonalLogin {
                openMapsAfterPersonalLogin = false
                showTeamMaps = true
            } else if caltopoSettings.usesPersonalCredentials {
                // Closing the browser is not proof that sign-in was cancelled.
                Task {
                    if await caltopoSettings.loadPersonalMaps() { showTeamMaps = true }
                }
            }
        }) {
            NavigationStack {
                AppleCaltopoPersonalProbeView(onCatalog: { username, maps in
                    caltopoSettings.acceptPersonalCatalog(username, maps: maps)
                    openMapsAfterPersonalLogin = true
                    showPersonalCredentialLogin = false
                })
            }
        }
        .safeAreaInset(edge: .bottom, alignment: .leading, spacing: 0) {
            if !organizationAccessBlocked {
                AwaitingMapPublicationPanel(tracks: ridTracks, settings: caltopoSettings, clues: clueStore,
                    location: locationProvider, onChooseMap: openCaltopoMapActions)
            }
        }
        // Keep the compact warning host above navigation, including Live View.
        .overlay(alignment: .topTrailing) {
            if !organizationAccessBlocked {
                AppleProximityWarningHost(center: proximityAlerts, alertBell: alertBell, onMap: { showTrackMap = true })
                    .padding(.top, 52)
                    .padding(.trailing, 12)
                    .padding(.leading, 12)
            }
        }
            // Keep URL and lifecycle delivery outside the protected-content branch:
            // that branch is removed while Safari or a system overlay is active.
            .onOpenURL { url in
                receiveIncomingURL(url)
            }
            .onChange(of: organizationAccessGranted, initial: true) { _, granted in
                if granted || !organizationAuthenticationRequired {
                    reconcileTrackerDevice()
                }
            }
            .onChange(of: scenePhase) { _, phase in
                guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                // Live View no longer holds the screen on (matches Android). Background
                // scan mode and lifecycle logging are handled by AppleAlertCoordinator.
                switch phase {
                case .active:
                    handleIncidentMapBecameActive()
                    if organizationAccessGranted || !organizationAuthenticationRequired {
                        reconcileTrackerDevice()
                        resumeTrackerAfterBrowserReturnIfNeeded(
                            callbackPending: pendingOrganizationAccessURL?.scheme?.lowercased() == "r2creauth"
                        )
                    }
                    mediaMTX.ensureHealthy(captureStreams: captureStreams)
                    Task {
                        await networkDiagnostics.refresh(reason: .applicationBecameActive)
                        await refreshManagedOrganizationConfiguration()
                        await ridTracks.setLocalDeviceMarkerPublishingEnabled(true)
                    }
                case .background:
                    // Android keeps the device marker while the display is off. Keep
                    // it too when background location will keep this session running;
                    // otherwise iOS may suspend the app, so remove it as before.
                    if !locationProvider.backgroundOperationAvailable {
                        removeLocalDeviceMarkerInBackground()
                    }
                    scheduleIncidentMapBackgroundDisconnect()
                case .inactive:
                    break
                @unknown default:
                    break
                }
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
            .onReceive(
                NotificationCenter.default.publisher(
                    for: UIApplication.protectedDataWillBecomeUnavailableNotification
                )
            ) { _ in
                guard organizationAuthenticationRequired else { return }
                if organizationAccessGranted { organizationAccessRevokedByDeviceLock = true }
                organizationAccessGranted = false
                organizationAccessObscured = true
                organizationAccessBackgroundedAt = nil
                organizationAuthenticationError = nil
                AppleLog.info("OrganizationAccess", "Protected access locked by device lock")
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
        // A Face ID / Touch ID device unlock within this window satisfies re-authentication without a
        // second prompt, only after a device lock ended a granted session (see OrganizationAccessPolicy).
        context.touchIDAuthenticationAllowableReuseDuration = min(
            OrganizationAccessPolicy.biometricUnlockReuseSeconds(
                accessRevokedByDeviceLock: organizationAccessRevokedByDeviceLock
            ),
            LATouchIDAuthenticationMaximumAllowableReuseDuration
        )
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
                    organizationAccessRevokedByDeviceLock = false
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
                    // Like Android: a prompt the system dismissed (lock, app switch) keeps the device-unlock
                    // handoff; a declined or failed prompt ends it.
                    let laCode = (error as? LAError)?.code
                    if laCode != .systemCancel && laCode != .appCancel {
                        organizationAccessRevokedByDeviceLock = false
                    }
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
        deviceReconciliation.markPending()
        reconcileTrackerDevice()

        trackerReauthenticationURL = nil
        showTrackerReauthenticationPrompt = false
        AppleLog.info(
            "TrackerPeer",
            "Returned from Tracker sign-in browser without an app callback; retrying Tracker access"
        )
        configurePeerCoordinator(forceReconnect: true)
        Task { await refreshManagedOrganizationConfiguration(force: true) }
    }

    private func handleDeviceReconciliationSignIn(_ url: URL) {
        if caltopoSettings.quarantineTrackerManagedCredentials() {
            ridTracks.configureCaltopo(
                caltopoSettings.configuration,
                trackFolderName: orgConfigSettings.trackFolder
            )
        }
        trackerReauthenticationURL = url
        showTrackerReauthenticationPrompt = !showImportConfig
    }

    private func handleDeviceReconciliationRejection() {
        AppleLog.info("TrackerPeer", "Presenting Tracker re-enrollment notice")
        let credential = orgConfigSettings.trackerAPIKey
        guard lastTrackerReenrollmentNoticeCredential != credential else { return }
        lastTrackerReenrollmentNoticeCredential = credential
        showTrackerReenrollmentRequired = true
    }

    private func reconcileTrackerDevice() {
        Task { await deviceReconciliation.check(baseURL: orgConfigSettings.trackerURLPrefix, token: orgConfigSettings.trackerAPIKey) }
    }

    private func refreshManagedOrganizationConfiguration(force: Bool = false, waitForInFlight: Bool = false) async {
        guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
        await orgConfigImporter.refreshManagedConfiguration(
            caltopoSettings: caltopoSettings, orgSettings: orgConfigSettings,
            identityStore: droneConfirmations, force: force, waitForInFlight: waitForInFlight)
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
        case .bluetoothDisabled: "Bluetooth is disabled"
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
            LabeledContent("Tracker", value: peerCoordinator.status.rawValue)
            if !peerCoordinator.peers.isEmpty { LabeledContent("Peer zones", value: String(peerCoordinator.peers.count)) }
            LabeledContent("Team drones", value: String(droneConfirmations.importedMappingCount))
            Button("Import Config", systemImage: "qrcode.viewfinder") { showImportConfig = true }
            NavigationLink {
                AppleConfigurationTransferView(caltopo: caltopoSettings, organization: orgConfigSettings, identities: droneConfirmations)
            } label: { Label("Backup & Transfer", systemImage: "shippingbox") }
        }
    }

    private var androidParityDashboard: some View {
      VStack(spacing: 4) {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 8) {
                // The restriction chips, the status header, and the aircraft table form one
                // spreadsheet that scrolls sideways together with a visible indicator. Rows
                // keep their (Dynamic Type-scaled) widths instead of wrapping.
                ScrollView(.horizontal) {
                    VStack(alignment: .leading, spacing: 8) {
                        androidRestrictionStrip
                        androidOperationsHeader
                        if !ridTracks.tracks.isEmpty { androidAircraftTable }
                    }
                }
                .scrollIndicators(.visible)
                .scrollIndicatorsFlash(onAppear: true)
                if ridTracks.tracks.isEmpty { androidNoAircraftRow }
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(Color(uiColor: .systemBackground))
      }
    }

    private var androidOperationsHeader: some View {
        HStack(spacing: 2) {
            androidIncidentMapButton
            androidOpPeriodCell
            androidPilotCallsignCell
            androidHeaderCell("Tracker", androidCoordinatorStatus, width: 150)
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
            .frame(width: cell(190), height: cell(58))
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
        .frame(width: cell(120), height: cell(58))
        .background(Color(uiColor: .secondarySystemBackground))
    }

    private var androidPilotCallsignCell: some View {
        VStack(spacing: 2) {
            Text("Pilot Callsign/Name")
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
                .accessibilityLabel("Pilot Callsign/Name")
        }
        .padding(.horizontal, 6)
        .frame(width: cell(140), height: cell(58))
        .background(Color(uiColor: .secondarySystemBackground))
    }

    /// One non-wrapping row inside the dashboard's sideways scroll. The chips size to their
    /// text, which already grows with Dynamic Type; the gaps scale like the grid cells.
    private var androidRestrictionStrip: some View {
          HStack(spacing: cell(8)) {
            AppleProximityStatusChip(center: proximityAlerts) { showProximitySettings = true }
            if airspace.enabled || notams.state.visible {
                NavigationLink {
                    if usesAirspaceRestrictionStatus {
                        AppleAirspacePanel(
                            center: airspace,
                            notams: notams,
                            location: locationProvider.lastLocation
                        )
                        .onDisappear { if notams.mapFocusRequest != nil { showTrackMap = true } }
                    } else {
                        AppleNotamPanel(center: notams, location: locationProvider.lastLocation)
                            .onDisappear { if notams.mapFocusRequest != nil { showTrackMap = true } }
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
        }
          .fixedSize()
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
        case .normal: .neutral
        case .neutral: .neutral
        }
    }

    private var airspaceChipTone: AppleOperationalStatusChipTone {
        switch airspace.state.severity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .neutral
        case .neutral: .neutral
        }
    }

    private var landRestrictionChipTone: AppleOperationalStatusChipTone {
        switch landRestrictions.state.severity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .neutral
        case .neutral: .neutral
        }
    }

    /// Kept outside the sideways-scrolling spreadsheet so the message always fits the window.
    private var androidNoAircraftRow: some View {
        Text("No aircraft detected — Bluetooth and external Remote ID are monitoring.")
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: cell(58))
            .background(Color(uiColor: .secondarySystemBackground))
    }

    private var androidAircraftTable: some View {
        VStack(alignment: .leading, spacing: 1) {
            if OperationalMainScreenPresentation.showsAircraftHeader(
                activeTrackCount: ridTracks.tracks.count
            ) {
                androidAircraftHeader
            }
            ForEach(ridTracks.tracks) { track in androidAircraftRow(track) }
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
                    .frame(width: cell(120) * 2 + cell(80) * 3 + 4, height: cell(24))
                    .background(Color.accentColor.opacity(0.16))
                HStack(spacing: 1) {
                    ForEach(["BT4:", "BT5:"], id: \.self) { label in
                        androidTableHeader(label, width: 120)
                    }
                    androidTableHeader("R2C:", width: 80)
                    androidTableHeader("SEI:", width: 80)
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
                .frame(width: cell(28), height: cell(42))
                .background(Color(uiColor: .secondarySystemBackground))
            Button(
                identity?.displayLabel
                    ?? (droneConfirmations.isUnassociated(track.aircraftID) ? "Add to RID Map" : "Confirm Drone")
            ) {
                if droneConfirmations.isUnassociated(track.aircraftID) {
                    addRidMapRemoteID = track.aircraftID
                } else {
                    // Manual confirmation must remain available after Don't publish,
                    // just like the Android Main Screen drone-spec button.
                    pendingDroneConfirmation = DroneConfirmationRequest(id: track.aircraftID)
                }
            }
                .font(.caption.monospaced())
                .lineLimit(1)
                .buttonStyle(.bordered)
                .frame(width: cell(200), height: cell(42))
                .background(Color(uiColor: .secondarySystemBackground))
            VStack(spacing: 2) {
                Text(track.aircraftID)
                    .font(.caption.monospaced())
                    .lineLimit(1)
                if let bridgeRSSI = OperationalMainScreenPresentation.droneToBridgeRSSIText(
                    track.lastDroneToBridgeSignalStrengthDbm
                ) {
                    Text(bridgeRSSI)
                        .font(.system(size: 9 * dashboardCellScalePercent / 100, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(width: cell(240), height: cell(42))
            .background(Color(uiColor: .secondarySystemBackground))
            androidTransportCell(track, source: .bluetoothLegacy)
            androidTransportCell(track, source: .bluetoothExtended)
            androidTableValue("\(sourceCount(track, .trackerRelay))", width: 80)
            androidTableValue("\(sourceCount(track, .djiVideo))", width: 80)
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
            .frame(width: cell(40), height: cell(42))
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

    /// Scales an Android-parity dashboard cell dimension with Dynamic Type.
    private func cell(_ length: CGFloat) -> CGFloat {
        (length * dashboardCellScalePercent / 100).rounded()
    }

    private func androidHeaderCell(_ title: String, _ value: String, width: CGFloat) -> some View {
        VStack(spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.subheadline.bold()).lineLimit(2).minimumScaleFactor(0.7)
        }
        .multilineTextAlignment(.center)
        .frame(width: cell(width), height: cell(58))
        .background(Color(uiColor: .secondarySystemBackground))
    }

    private func androidTableHeader(_ value: String, width: CGFloat) -> some View {
        Text(value)
            .font(.caption.bold())
            .multilineTextAlignment(.center)
            .frame(width: cell(width), height: cell(36))
            .background(Color.accentColor.opacity(0.16))
    }

    private func androidGroupedHeader(top: String, bottom: String, width: CGFloat) -> some View {
        VStack(spacing: 1) {
            Text(top)
                .font(.caption.bold())
                .frame(width: cell(width), height: cell(24))
                .background(Color.accentColor.opacity(0.16))
            androidTableHeader(bottom, width: width)
        }
    }

    private func androidTableValue(_ value: String, width: CGFloat, monospaced: Bool = false) -> some View {
        Text(value)
            .font(monospaced ? .caption.monospaced() : .caption)
            .lineLimit(1)
            .minimumScaleFactor(0.65)
            .frame(width: cell(width), height: cell(42))
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
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(AppleDeviceIdentity.displayName)\n\(version)(\(build))"
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
                if proximityAlerts.isSuspended { Button("Resume Proximity Alert") { proximityAlerts.resume() } }
                Text(proximityAlerts.alertAllAircraft ? "Alert scope: All aircraft — any received pair." : "Alert scope: Published only — pairs involving an aircraft claimed by this tablet.")
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
                        .onDisappear { if notams.mapFocusRequest != nil { showTrackMap = true } }
                    } label: {
                        LabeledContent("Controlled airspace", value: airspace.state.chipLabel)
                    }
                }
                if notams.state.visible {
                    NavigationLink {
                        AppleNotamPanel(center: notams, location: locationProvider.lastLocation)
                            .onDisappear { if notams.mapFocusRequest != nil { showTrackMap = true } }
                    } label: {
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
            Text("Files › On My iPad › RID2Caltopo › RID2Caltopo › FlightStorage")
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
                    bridgeAlerts: bridgeAlerts,
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
            AppleControllerConnectionURLs()
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
                    registeredDroneDesignators: droneConfirmations.importedMappings.map(\.mappedID),
                    aircraftDetailsView: {
                        AnyView(RidMappingAdminView(
                            organization: orgConfigSettings,
                            identities: droneConfirmations,
                            startWithAddAircraft: true
                        ))
                    }
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
        networkDiagnostics.currentControllerConnectionLabel
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
            caltopoSettings: caltopoSettings,
            streamRegistry: streamRegistry,
            videoModel: streamRegistry.focusedSession.model,
            clueStore: clueStore,
            identityStore: droneConfirmations,
            orgSettings: orgConfigSettings,
            notams: notams,
            proximityAlerts: proximityAlerts,
            peerCoordinator: peerCoordinator,
            streamURL: streamRegistry.focusedPlaybackURL,
            bridgeSignalStrengthDbm: bluetoothScanner.bridgeSignalStrengthDbm,
            automaticStreamPairingAircraftID: $automaticStreamPairingAircraftID,
            onReturnToMain: closeLiveView,
            onConfirmPairedDrone: confirmDrone,
            onMapStatusTap: openCaltopoMapActions,
            onProximitySettings: { showProximitySettings = true },
            onSwitchMap: { openMapBrowser() },
            onDisconnectMap: {
                applyCaltopoConfiguration(caltopoSettings.disconnectMap())
            },
            onRestartStreams: {
                AppleLog.info("MediaMTX", "Operator restart streams requested from Live View")
                streamRegistry.shutdown()
                mediaMTX.restart(captureStreams: captureStreams)
            },
            viewportMemory: mapViewportMemory
        )
    }

    private func closeLiveView() {
        AppleLog.info("Navigation", "Live View back selected; returning to Main Screen")
        showTrackMap = false
    }

    private func openLiveViewFromBridgeChip() {
        guard !showTrackMap,
              !showCaltopoSettings,
              !showDiagnosticLogs,
              !showStatus,
              !showReleaseNotes,
              !showAboutPrivacy,
              !showTerms,
              !showConfigurationTransfer,
              !showStorageManagement,
              selectedAircraftID == nil
        else { return }
        AppleLog.info("Navigation", "Bridge chip: opening Live View")
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
        guard profileID != (caltopoSettings.usesPersonalCredentials ? "personal" : profileLifecycle.activeProfileID) else { return }
        if ridTracks.tracks.isEmpty {
            activateCredentialProfile(profileID)
        } else {
            pendingCredentialProfileID = profileID
            showCredentialSwitchConfirmation = true
        }
    }

    private func activateCredentialProfile(_ profileID: String) {
        showTeamMaps = false
        showPersonalCredentialLogin = false
        if profileID == "personal" {
            applyCaltopoConfiguration(caltopoSettings.disconnectMap())
            caltopoSettings.usesPersonalCredentials = true
            Task {
                let loaded = await caltopoSettings.loadPersonalMaps()
                guard caltopoSettings.usesPersonalCredentials else { return }
                showPersonalCredentialLogin = !loaded
                showTeamMaps = loaded
            }
            return
        }
        if caltopoSettings.usesPersonalCredentials {
            applyCaltopoConfiguration(caltopoSettings.disconnectMap())
            caltopoSettings.usesPersonalCredentials = false
        }
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

    private func openMapBrowser() {
        if caltopoSettings.usesPersonalCredentials || caltopoSettings.credentialID.isEmpty || caltopoSettings.credentialSecret.isEmpty {
            activateCredentialProfile("personal")
        } else {
            showTeamMaps = true
        }
    }

    private func openCaltopoMapActions() {
        if caltopoSettings.mapID.isEmpty {
            openMapBrowser()
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
            offlineMapPreparationActive: AppleMapOfflineManager.shared.isRunning,
            offlineMapPreparationEndedAt: AppleMapOfflineManager.shared.lastPreparationEndedAt
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
        "\(orgConfigSettings.proximityAlertSpacingFeet)|\(proximityAlerts.consent.enabled)|\(proximityAlerts.alertAllAircraft)"
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
        droneConfirmations.setPeerConfirmationMapID(caltopoSettings.mapID)
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

    private var confirmationMediaDiagnostics: String {
        streamRegistry.sessions.filter { $0.state == .live }.map { session in
            "designator=\(session.id) publisherConnId=\(session.publisherConnectionID ?? "unknown") decodedFrameAgeSeconds=\(session.model.decodedFrameAgeSeconds ?? -1)"
        }.joined(separator: "; ")
    }

    private var confirmationVideoSessions: [String: Set<String>] {
        let mappings = droneConfirmations.importedMappings.map { (remoteID: $0.remoteID, designator: $0.mappedID) }
        var result: [String: Set<String>] = [:]
        for session in streamRegistry.sessions where session.state == .live {
            guard let remoteID = OperationalStreamConfirmationMatch.remoteID(designator: session.id, mappings: mappings) else { continue }
            let designator = session.id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let publisher = session.publisherConnectionID.flatMap { $0.isEmpty ? nil : $0 } ?? "unknown"
            result[remoteID, default: []].insert("\(designator)|\(publisher)")
        }
        return result
    }

    private var confirmationActiveRemoteIDs: [String] {
        confirmationActiveRemoteIDs(tracks: ridTracks.tracks)
    }

    private func confirmationActiveRemoteIDs(tracks: [RidAircraftTrack]) -> [String] {
        let mappings=droneConfirmations.importedMappings.map { (remoteID:$0.remoteID,designator:$0.mappedID) }
        let videoIDs=streamRegistry.activePublisherStreamIDs.compactMap {
            OperationalStreamConfirmationMatch.remoteID(designator:$0,mappings:mappings)
        }
        return Array(Set(tracks.map(\.aircraftID)+videoIDs)).sorted()
    }

    private func dismissEndedDroneConfirmation(_ remoteID: String) {
        if confirmationPresentation.endFlight(remoteID: remoteID) {
            AppleLog.info("DroneConfirmation", "Dismissed expired confirmation remoteId=\(remoteID)")
        }
    }

    private func queueNextDroneConfirmation() {
        guard pendingDroneConfirmation == nil,
              let remoteID = droneConfirmations.reconcileActiveFlights(
                confirmationActiveRemoteIDs,
                aircraftReceivedAt: Dictionary(ridTracks.tracks.map { ($0.aircraftID, $0.lastAircraftMessageAt) }, uniquingKeysWith: max),
                videoSessions: confirmationVideoSessions,
                mediaDiagnostics: confirmationMediaDiagnostics,
                onFlightEnded: dismissEndedDroneConfirmation
              ),
              !droneConfirmations.isUnassociated(remoteID)
        else { return }
        pendingDroneConfirmation = DroneConfirmationRequest(id: remoteID)
    }

    private func confirmDrone(_ identity: RidAircraftIdentity) {
        ridTracks.capturePublicationIntent(remoteID: identity.remoteID)
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

    /// Every RID track snapshot, delivered by AppleAlertCoordinator so proximity
    /// evaluation and flight reconciliation continue while the display is locked.
    private func handleTrackSnapshot(_ tracks: [RidAircraftTrack]) {
        updateProximityAlerts(tracks: tracks)
        reconcileStreamFlightPairings(tracks: tracks, offerConfirmation: false)
        // @Published emits before ridTracks.tracks is assigned. Use this snapshot,
        // especially the empty list that ends a video-only flight.
        let remoteID = droneConfirmations.reconcileActiveFlights(
            confirmationActiveRemoteIDs(tracks: tracks),
            aircraftReceivedAt: Dictionary(tracks.map { ($0.aircraftID, $0.lastAircraftMessageAt) }, uniquingKeysWith: max),
            videoSessions: confirmationVideoSessions,
            allowPrompt: pendingDroneConfirmation == nil,
            mediaDiagnostics: confirmationMediaDiagnostics,
            onFlightEnded: dismissEndedDroneConfirmation
        )
        if !ProcessInfo.processInfo.arguments.contains("--suppress-auto-confirmation"),
           pendingDroneConfirmation == nil,
           let remoteID,
           !droneConfirmations.isUnassociated(remoteID) {
            pendingDroneConfirmation = DroneConfirmationRequest(id: remoteID)
        }
    }

    /// Binds the process-wide alert coordinator (iOS counterpart of Android's
    /// AlertSpeechCoordinator). Idempotent; later calls only refresh the hooks.
    private func startAlertCoordinator() {
        AppleAlertCoordinator.shared.start(
            hooks: AppleAlertCoordinator.Hooks(
                tracks: { tracks in handleTrackSnapshot(tracks) },
                evaluate: {
                    guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                    updateProximityAlerts()
                    evaluateOperationalState()
                },
                maintain: {
                    guard !AppleApplicationCleanupCenter.shared.isShutdownRequested else { return }
                    mediaMTX.ensureHealthy(captureStreams: captureStreams)
                    clueStore.pruneDeletedStorage()
                },
                status: {
                    let freshness = AlertTrackFreshnessSummary(
                        sampleDates: ridTracks.tracks.map(\.lastObservation.receivedAt),
                        now: Date(),
                        maximumAgeSeconds: RidAlertPositionFreshness.maximumAgeSeconds(
                            inBackground: alertEvaluationInBackground
                        )
                    )
                    return "\(locationProvider.backgroundStatusDescription) "
                        + "\(proximityAlerts.diagnosticSummary) \(freshness.logDescription)"
                },
                lifecycle: { background in
                    alertEvaluationInBackground = background
                    bluetoothScanner.setApplicationInBackground(background)
                }
            ),
            tracks: ridTracks.$tracks.eraseToAnyPublisher(),
            scanner: bluetoothScanner
        )
    }

    private func updateProximityAlerts(tracks: [RidAircraftTrack]? = nil) {
        proximityAlerts.update(
            tracks: tracks ?? ridTracks.tracks,
            thresholdFeet: orgConfigSettings.proximityAlertSpacingFeet,
            predictiveEnabled: false,
            operatorLocation: locationProvider.lastLocation,
            identityProvider: droneConfirmations.identity,
            alertEligibility: { remoteID in
                RidProximityEligibility.allows(
                    locallyConfirmed: droneConfirmations.isCurrentFlightConfirmed(remoteID),
                    coordinationRequired: peerCoordinator.coordinationRequired,
                    coordinatorEligible: peerCoordinator.isLocalAlertEligible(remoteID: remoteID)
                )
            },
            maximumPositionAgeSeconds: RidAlertPositionFreshness.maximumAgeSeconds(
                inBackground: alertEvaluationInBackground
            )
        )
    }

    private func reconcileStreamFlightPairings(
        tracks snapshot: [RidAircraftTrack]? = nil,
        offerConfirmation: Bool = true
    ) {
        let tracks = snapshot ?? ridTracks.tracks
        streamRegistry.pairConfiguredPublishers(mappings: droneConfirmations.importedMappings.map {
            (remoteID: $0.remoteID, designator: $0.mappedID)
        })
        // The tracks publisher reconciles confirmation itself using its incoming snapshot.
        // Do not read the old @Published value during that callback.
        if offerConfirmation && !ProcessInfo.processInfo.arguments.contains("--suppress-auto-confirmation") {
            queueNextDroneConfirmation()
        }
        let activeStreamIDs = streamRegistry.activePublisherStreamIDs
        automaticPairingOfferedStreamIDs.formIntersection(activeStreamIDs)
        for streamID in activeStreamIDs {
            let normalizedStreamID = streamID.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            let matches = tracks.filter { track in
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
        let confirmedCandidateIDs = tracks.compactMap { track in
            droneConfirmations.isCurrentFlightConfirmed(track.aircraftID)
                ? track.aircraftID
                : nil
        }
        guard confirmedCandidateIDs.count == 1,
              let streamID = OperationalStreamDesignatorMatch.automaticPairingStreamID(
                confirmedCandidateID: confirmedCandidateIDs[0],
                activeCandidateIDs: tracks.map(\.aircraftID),
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

    private func ensureAlertBellMuteBridges() {
        if alertBell.onProximityMuteChanged == nil {
            alertBell.onProximityMuteChanged = { [proximityAlerts] muted in
                if muted {
                    proximityAlerts.suspend()
                } else if proximityAlerts.consent.enabled {
                    proximityAlerts.resume()
                }
            }
            alertBell.onBridgeMuteChanged = { [bridgeAlerts] muted in
                bridgeAlerts.setAudioMuted(muted)
            }
            alertBell.onAltitudeTypeUnmuted = { [operationalAlerts] in
                operationalAlerts.clearAltitudeMutes()
            }
            alertBell.onDroneSignalLossTypeUnmuted = { [operationalAlerts] in
                operationalAlerts.clearSignalMutes()
            }
        }
        alertBell.reflectExternalMute(.bridgeSignalLoss, muted: bridgeAlerts.audioMuted)
    }

    private func refreshAlertBellMetrics() {
        ensureAlertBellMuteBridges()
        let threshold = Double(orgConfigSettings.proximityAlertSpacingFeet)
        // Red ⇔ the engine's active alert has been spoken (never geometry alone).
        let proximityActive = AlertBellThresholdPolicy.proximityAlarmAnnounced(
            activeAlertInstanceID: proximityAlerts.activeAlert?.alertInstanceID,
            announcedAlertInstanceID: proximityAlerts.announcedAlertInstanceID,
            suspended: proximityAlerts.isSuspended
        )
        // Approach colour uses engine decision-bound separation of pairs that pass
        // the vertical gate, never raw UI pair feet.
        let separation = proximityAlerts.activeAlert?.horizontalSeparationFeet
            ?? proximityAlerts.nearestDecisionHorizontalFeet
        let maxAgl = ridTracks.altitudeDisplayByAircraftID.values.compactMap(\.aglFeet).max()
        let maxRange = ridTracks.altitudeDisplayByAircraftID.values.compactMap(\.rangeFeet).max()
        let bridgeAge: Double? = {
            guard let last = bluetoothScanner.bridgeLastSeenAt else { return nil }
            return Date().timeIntervalSince(last)
        }()
        let activeIDs = ridTracks.tracks.map(\.aircraftID)
        let allSEI = streamRegistry.allAircraftHaveFreshPairedSEI(aircraftIDs: activeIDs)
        let bridgeMonitoring = OperationalBridgeAlertPolicy.shouldMonitor(
            scannerRunning: bluetoothScanner.state == .scanning,
            activeFlightCount: activeIDs.count,
            allActiveFlightsCoveredByFreshPairedSEI: allSEI
        )
        alertBell.updateMetrics(
            AlertBellMetrics(
                proximitySeparationFeet: separation,
                proximityThresholdFeet: threshold,
                proximityActivelyAlerting: proximityActive,
                maxAglFeet: maxAgl,
                maxRangeFeet: maxRange,
                droneSignalLossActive: !operationalAlerts.signalLossAlerts.isEmpty,
                bridgeSecondsSinceLastPing: bridgeAge,
                bridgeMonitoringActive: bridgeMonitoring,
                wifiSignalPercent: nil,
                videoRequestPending: peerCoordinator.pendingVideoStreamRequest != nil
            )
        )
    }

    private func updateOperationalAlerts() {
        let demoAlerts = ProcessInfo.processInfo.arguments.contains("--demo-operational-alert")
        operationalAlerts.update(
            tracks: ridTracks.tracks,
            altitudeDisplay: ridTracks.altitudeDisplayByAircraftID,
            operatorLocation: locationProvider.lastLocation,
            identityProvider: droneConfirmations.identity,
            // Same rule as proximity (and Android DefaultPeerCoordinator): local
            // confirmation is enough in Standalone; a coordinated incident also
            // needs the local owner/confirmation lease.
            // Standalone altitude matches Android ComplianceAlertCenter: no flight-
            // confirmation gate. identityProvider below already limits candidates to
            // known team aircraft. Coordinated incidents still need the lease.
            alertEligibility: demoAlerts ? { _ in true } : { remoteID in
                if peerCoordinator.coordinationRequired {
                    return RidProximityEligibility.allows(
                        locallyConfirmed: droneConfirmations.isCurrentFlightConfirmed(remoteID),
                        coordinationRequired: true,
                        coordinatorEligible: peerCoordinator.isLocalAlertEligible(remoteID: remoteID)
                    )
                }
                return true
            },
            bridgeCheckDistanceFeet: Double(orgConfigSettings.bridgeCheckDistanceFeet),
            maximumTrackDelaySeconds: Double(orgConfigSettings.newTrackDelaySeconds),
            bridgeLastSeenAt: bluetoothScanner.bridgeLastSeenAt,
            pairedSEILastActivityAt: streamRegistry.djiSEILastActivityByAircraftID(),
            pairedSEIRelativeUpMeters: streamRegistry.djiSEIRelativeUpMetersByAircraftID(),
            maximumSampleAgeSeconds: RidAlertPositionFreshness.maximumAgeSeconds(
                inBackground: alertEvaluationInBackground
            )
        )
    }

    private var trackPolicyConfigurationFingerprint: String {
        "\(orgConfigSettings.minimumTrackDistanceFeet)|\(orgConfigSettings.newTrackDelaySeconds)|\(orgConfigSettings.bridgeCheckDistanceFeet)|\(minimumHorizontalAccuracyCode)"
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
            activeTimeoutSeconds: orgConfigSettings.newTrackDelaySeconds,
            minimumHorizontalAccuracyCode: UInt8(minimumHorizontalAccuracyCode)
        )
    }

    /// Called every second by AppleAlertCoordinator (not by a view task, which the
    /// protected-access gate cancels while the display is locked).
    private func evaluateOperationalState() {
        updateOperationalAlerts()
        refreshAlertBellMetrics()
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
            let changed = controllerRTMPURL != "Connect this device to Wi-Fi or Ethernet"
            controllerRTMPURL = "Connect this device to Wi-Fi or Ethernet"
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


private struct ShortFlightRecordingPanel: View {
    @ObservedObject var gate: ShortFlightRecordingGate
    @Environment(\.scenePhase) private var phase
    var body: some View {
        VStack(spacing: 4) {
            ForEach(gate.prompts) { prompt in
                VStack(alignment: .leading) {
                    Text("Short flight — Record (Yes/No)?").font(.headline)
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text("\(prompt.aircraft) · Yes automatically in \(gate.remainingSeconds(prompt))s")
                    }
                    HStack {
                        Button("Yes — Record") { gate.decide(prompt.id, record: true) }.buttonStyle(.borderedProminent)
                        Button("No") { gate.decide(prompt.id, record: false) }.buttonStyle(.bordered)
                    }
                }
                .padding(12)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
            }
        }
        .onAppear { gate.setActive(phase == .active) }
        .onChange(of: phase) { _, next in gate.setActive(next == .active) }
        .onDisappear { gate.setActive(false) }
    }
}

// The menu has static contents and stable presentation bindings. Telemetry and
// one-second status updates must not rebuild an already presented native menu.
private struct LiveViewBackButton: View {
    @Environment(\.dismiss) private var dismiss
    let onDismiss: () -> Void

    var body: some View {
        Button {
            // Explicitly pop the destination on iPad, then synchronize the
            // source presentation flag for programmatic navigation.
            dismiss()
            onDismiss()
        } label: {
            Label("Main Screen", systemImage: "chevron.left")
        }
        .accessibilityIdentifier("live-view-back")
    }
}

private struct MainScreenMenu: View, Equatable {
    @Binding var showTrackMap: Bool
    @Binding var showDiagnosticLogs: Bool
    @Binding var showStatus: Bool
    @Binding var showReleaseNotes: Bool
    @Binding var showImportConfig: Bool
    @Binding var showConfigurationTransfer: Bool
    @Binding var showStorageManagement: Bool
    @Binding var showCaltopoSettings: Bool
    @Binding var showAboutPrivacy: Bool
    @Binding var showTerms: Bool
    @Binding var showConfirmExit: Bool

    nonisolated static func == (lhs: Self, rhs: Self) -> Bool { true }

    var body: some View {
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
            Button("Manage Storage...", systemImage: "externaldrive") { showStorageManagement = true }
            Button("Settings", systemImage: "gearshape") { showCaltopoSettings = true }
            Button("Terms of Use", systemImage: "doc.text") { showTerms = true }
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


private struct AwaitingMapPublicationPanel: View {
    @ObservedObject var tracks: RIDTrackViewModel
    @ObservedObject var settings: AppleCaltopoSettings
    @ObservedObject var clues: AppleClueStore
    @ObservedObject var location: AppleLocationProvider
    let onChooseMap: () -> Void
    @State private var reviewing = false
    @State private var reminder = AwaitingMapReminder()
    @State private var selected: AwaitingMapFlight?
    @State private var discardCandidate: AwaitingMapFlight?
    @State private var chooseMapAfterDismiss = false
    @State private var destinationMap = ""
    @State private var destinationTeam = ""
    @State private var destinationTitle = ""
    private var reminderFlightIDs: [String] {
        guard settings.mapID.isEmpty else { return [] }
        return tracks.awaitingMapFlights.filter { !$0.finished && $0.mapID.isEmpty }.map(\.id).sorted()
    }
    var body: some View {
        if !tracks.awaitingMapFlights.isEmpty {
            Button("\(tracks.awaitingMapFlights.count) flight(s) awaiting a map — Review") { reviewing = true }
                .font(.caption).padding(4).background(.regularMaterial)
                .task(id: reminderFlightIDs) {
                    if reminder.shouldPresent(eligibleFlightIDs: Set(reminderFlightIDs), hasMap: !settings.mapID.isEmpty) {
                        reviewing = true
                    }
                }
                .sheet(isPresented: $reviewing, onDismiss: {
                    // Open the incident-map picker only after this sheet has gone away.
                    guard chooseMapAfterDismiss else { return }
                    chooseMapAfterDismiss = false
                    onChooseMap()
                }) {
                    NavigationStack {
                        ScrollView {
                            reviewContent
                                .padding(.horizontal, 22)
                                .padding(.vertical, 18)
                        }
                        .background(Color(uiColor: .systemGroupedBackground))
                        .navigationTitle(AwaitingMapFlightText.title)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("Dismiss") { reviewing = false }
                            }
                        }
                        .task { await tracks.refreshIncidentCommandLocations() }
                        .alert("Publish earlier flight?", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } })) {
                            Button("Publish") {
                                guard let flight = selected else { return }
                                let map = destinationMap; let team = destinationTeam
                                Task {
                                    if await tracks.decideAwaitingFlight(flight.id, mapID: map, teamID: team) {
                                        clues.bindAwaitingFlight(flight, mapID: map, teamID: team)
                                    }
                                }
                                selected = nil
                            }
                            Button("Keep local") {
                                guard let flight = selected else { return }
                                Task { _ = await tracks.decideAwaitingFlight(flight.id, mapID: nil, teamID: destinationTeam) }
                                selected = nil
                            }
                            Button("Later", role: .cancel) { selected = nil }
                        } message: {
                            Text("\(selected?.label ?? "Flight")\nTo \(destinationTitle) (\(destinationMap))\nIncludes the recorded track and associated clues marked for publication. Clues kept local stay local.")
                        }
                    }
                    .alert(
                        AwaitingMapFlightText.discardTitle,
                        isPresented: Binding(get: { discardCandidate != nil }, set: { if !$0 { discardCandidate = nil } }),
                        presenting: discardCandidate
                    ) { flight in
                        Button("Cancel", role: .cancel) { discardCandidate = nil }
                        Button("Discard", role: .destructive) {
                            discardCandidate = nil
                            Task { await tracks.discardAwaitingFlight(flight, clues: clues) }
                        }
                    } message: { flight in
                        Text(verbatim: "\(flight.label) · \(flight.firstTime.formatted())\n"
                            + AwaitingMapFlightText.discardMessage(cluePhotoCount: cluePhotoCount(flight)))
                    }
                    .modifier(AwaitingMapSheetSizing())
                }
        }
    }

    private var sortedFlights: [AwaitingMapFlight] {
        tracks.awaitingMapFlights.sorted { a, b in
            a.suggested(icLocations: tracks.incidentCommandLocations) && !b.suggested(icLocations: tracks.incidentCommandLocations)
        }
    }

    /// A usable fix, or nil when Core Location has none (negative accuracy means invalid).
    private var currentFix: CLLocation? {
        guard let fix = location.lastLocation, fix.horizontalAccuracy >= 0 else { return nil }
        return fix
    }

    private var locationSummary: String {
        guard let fix = currentFix else { return AwaitingMapFlightText.locationUnavailable }
        return AwaitingMapFlightText.locationSummary(accuracyMeters: fix.horizontalAccuracy,
            fixTime: fix.timestamp.formatted(date: .omitted, time: .shortened))
    }

    private func cluePhotoCount(_ flight: AwaitingMapFlight) -> Int {
        clues.awaitingFlightClues(flight, otherFlights: tracks.awaitingMapJournalEntries).count
    }

    private func proximity(_ flight: AwaitingMapFlight) -> AwaitingMapFlightProximity? {
        currentFix.flatMap { flight.proximity(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude) }
    }

    private func beginPublish(_ flight: AwaitingMapFlight) {
        destinationMap = flight.mapID.isEmpty ? settings.mapID : flight.mapID
        destinationTeam = flight.mapID.isEmpty ? settings.configuration.publicationScope : flight.teamID
        destinationTitle = flight.mapID.isEmpty || flight.mapID == settings.mapID ? settings.mapTitle : "Original incident map"
        selected = flight
    }

    private var reviewContent: some View {
        let flights = sortedFlights
        return VStack(alignment: .leading, spacing: 0) {
            mapBanner
            ViewThatFits(in: .horizontal) {
                HStack {
                    Text(verbatim: AwaitingMapFlightText.flightCountHeader(flights.count))
                    Spacer(minLength: 12)
                    Text(verbatim: locationSummary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: AwaitingMapFlightText.flightCountHeader(flights.count))
                    Text(verbatim: locationSummary)
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .padding(.horizontal, 16)
            .padding(.top, 22)
            .padding(.bottom, 7)
            // One layout decision for every row, so rows never mix wide and stacked forms.
            ViewThatFits(in: .horizontal) {
                flightGroup(flights, wide: true)
                flightGroup(flights, wide: false)
            }
            Text("Publish needs a connected map. **Discard** permanently deletes a flight's track and clue photos from this device. **Dismiss** only hides this list; flights stay saved and can be reviewed again from Live View.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .padding(.top, 9)
        }
    }

    private func flightGroup(_ flights: [AwaitingMapFlight], wide: Bool) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(flights.enumerated()), id: \.element.id) { index, flight in
                AwaitingMapFlightRow(
                    flight: flight,
                    proximity: proximity(flight),
                    cluePhotoCount: cluePhotoCount(flight),
                    nearIC: flight.suggested(icLocations: tracks.incidentCommandLocations),
                    canPublish: !settings.mapID.isEmpty,
                    wide: wide,
                    onPublish: { beginPublish(flight) },
                    onDiscard: { discardCandidate = flight }
                )
                if index < flights.count - 1 { Divider().padding(.leading, 74) }
            }
        }
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
    }

    @ViewBuilder private var mapBanner: some View {
        Group {
            if settings.mapID.isEmpty {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) {
                        bannerIcon
                        bannerText.lineLimit(1)
                        Spacer(minLength: 12)
                        chooseMapButton
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(alignment: .top, spacing: 12) {
                            bannerIcon
                            bannerText.fixedSize(horizontal: false, vertical: true)
                        }
                        chooseMapButton
                    }
                }
            } else {
                HStack(spacing: 12) {
                    Image(systemName: "map").foregroundStyle(.tint)
                    Text(verbatim: "Destination: \(settings.mapTitle) (\(settings.mapID))")
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: 0)
                }
            }
        }
        .font(.subheadline)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
    }

    private var bannerIcon: some View {
        Image(systemName: "info.circle").font(.title3).foregroundStyle(.orange)
    }

    private var bannerText: Text {
        Text("**No map connected.** \(AwaitingMapFlightText.noMapBanner)")
    }

    private var chooseMapButton: some View {
        Button {
            chooseMapAfterDismiss = true
            reviewing = false
        } label: {
            Label("Choose Map…", systemImage: "map")
        }
        .buttonStyle(.bordered)
        .font(.subheadline.weight(.semibold))
        .fixedSize()
    }
}

private struct AwaitingMapFlightRow: View {
    let flight: AwaitingMapFlight
    let proximity: AwaitingMapFlightProximity?
    let cluePhotoCount: Int
    let nearIC: Bool
    let canPublish: Bool
    let wide: Bool
    let onPublish: () -> Void
    let onDiscard: () -> Void

    var body: some View {
        Group {
            if wide {
                HStack(spacing: 14) {
                    AwaitingMapCompassChip(proximity: proximity)
                    details
                    Spacer(minLength: 12)
                    actions
                }
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .top, spacing: 14) {
                        AwaitingMapCompassChip(proximity: proximity)
                        details
                        Spacer(minLength: 0)
                    }
                    actions.padding(.leading, 58)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(verbatim: flight.label).font(.title3.weight(.semibold))
                Text(verbatim: flight.firstTime.formatted()).font(.callout).foregroundStyle(.secondary)
            }
            .lineLimit(1)
            if wide {
                HStack(spacing: 10) {
                    durationItem
                    separator
                    photosItem
                    separator
                    locationItem
                }
                .fixedSize()
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    durationItem
                    photosItem
                    locationItem
                }
            }
            if nearIC {
                Text("Recent flight near IC").font(.caption).foregroundStyle(.secondary)
            }
        }
        .font(.callout)
    }

    private var separator: some View {
        Text(verbatim: "·").foregroundStyle(.tertiary)
    }

    private var durationItem: some View {
        HStack(spacing: 6) {
            Image(systemName: "timer").foregroundStyle(.secondary)
            Text(verbatim: AwaitingMapFlightText.duration(seconds: flight.durationSeconds))
        }
        .lineLimit(1)
    }

    private var photosItem: some View {
        HStack(spacing: 6) {
            Image(systemName: "camera").foregroundStyle(.secondary)
            Text(verbatim: AwaitingMapFlightText.cluePhotos(cluePhotoCount))
                .foregroundStyle(cluePhotoCount == 0 ? .secondary : .primary)
        }
        .lineLimit(1)
    }

    @ViewBuilder private var locationItem: some View {
        switch proximity {
        case .inside:
            HStack(spacing: 6) {
                Image(systemName: "dot.square")
                Text(verbatim: AwaitingMapFlightText.insideArea).fontWeight(.medium)
            }
            .foregroundStyle(Color.green)
            .lineLimit(1)
        case .away:
            HStack(spacing: 6) {
                Image(systemName: "mappin.and.ellipse").foregroundStyle(Color.blue)
                Text(verbatim: AwaitingMapFlightText.location(proximity))
            }
            .lineLimit(1)
        case nil:
            HStack(spacing: 6) {
                Image(systemName: "location.slash")
                Text(verbatim: AwaitingMapFlightText.distanceUnavailable)
            }
            .foregroundStyle(.secondary)
            .lineLimit(1)
        }
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button(action: onPublish) {
                Label("Publish…", systemImage: "square.and.arrow.up")
            }
            .buttonStyle(.bordered)
            .disabled(!canPublish)
            Button(role: .destructive, action: onDiscard) {
                Label("Discard", systemImage: "trash")
            }
            .buttonStyle(.bordered)
            .tint(.red)
            // A flight still in the air keeps recording; it can be discarded once it ends.
            .disabled(!flight.finished)
        }
        .font(.subheadline.weight(.semibold))
        .fixedSize()
    }
}

private struct AwaitingMapCompassChip: View {
    let proximity: AwaitingMapFlightProximity?

    var body: some View {
        ZStack {
            Circle().fill(Color(uiColor: .tertiarySystemFill))
            Circle().strokeBorder(ringColor, lineWidth: 1.2)
            Text(verbatim: "N")
                .font(.system(size: 7, weight: .bold))
                .foregroundStyle(.secondary)
                .offset(y: -15)
            switch proximity {
            case .inside:
                Circle()
                    .stroke(Color.green, style: StrokeStyle(lineWidth: 2, dash: [3, 2.4]))
                    .frame(width: 20, height: 20)
                Circle().fill(Color.green).frame(width: 9, height: 9)
            case let .away(_, degrees, _):
                Image(systemName: "location.north.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Color.blue)
                    .rotationEffect(.degrees(Double(degrees)))
            case nil:
                Image(systemName: "location.slash")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 44, height: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: AwaitingMapFlightText.location(proximity)))
    }

    private var ringColor: Color {
        if case .inside = proximity { return .green }
        return Color(uiColor: .separator)
    }
}

/// iPad sheets default to portrait-page width; widen so each row stays on one line.
private struct AwaitingMapSheetSizing: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.presentationSizing(AwaitingMapWideSheetSizing())
        } else {
            content
        }
    }
}

@available(iOS 18.0, *)
private struct AwaitingMapWideSheetSizing: PresentationSizing {
    func proposedSize(for root: PresentationSizingRoot, context: PresentationSizingContext) -> ProposedViewSize {
        var size = PagePresentationSizing.page.proposedSize(for: root, context: context)
        size.width = 1_120 // The system clamps this to the window.
        return size
    }
}

/// Measures the complete header instead of squeezing three identity lines into a navigation bar.
struct AdaptiveOperatorHeader<Title: View, Actions: View>: View {
    @ViewBuilder var title: (Bool) -> Title
    @ViewBuilder var actions: () -> Actions
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                HStack(spacing: 8) { actions() }.hidden().accessibilityHidden(true).allowsHitTesting(false)
                Spacer(minLength: 8)
                title(true).fixedSize(horizontal: true, vertical: true)
                Spacer(minLength: 8)
                HStack(spacing: 8) { actions() }
            }
            .frame(minWidth: 600)
            HStack(spacing: 8) {
                title(false).frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 8) { actions() }.fixedSize(horizontal: true, vertical: false)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color(uiColor: .systemBackground))
    }
}

struct AppleProximityStatusChip: View {
    @ObservedObject var center: AppleProximityAlertCenter
    var onSettings: (() -> Void)? = nil
    var body: some View {
        Button {
            if let onSettings { onSettings() } else if center.isSuspended { center.resume() }
        } label: {
            AppleOperationalStatusChipLabel(title: "Proximity Alerts: \(center.status)", tone: .neutral)
        }
        .buttonStyle(.plain)
    }
}
