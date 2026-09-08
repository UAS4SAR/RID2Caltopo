import AVKit
import CryptoKit
import MapKit
import R2CCore
import SwiftUI

private let unresolvedAppleMapRegion = MKCoordinateRegion(
    center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
    span: MKCoordinateSpan(latitudeDelta: 160, longitudeDelta: 360)
)

private struct ArtifactZoomRequest: Equatable {
    let id = UUID()
    let title: String
    let coordinates: [MapCoordinate]
}

private struct ArtifactInspection: Identifiable {
    let id = UUID()
    let title: String
    let description: String?
}

private struct ClueSelectionCandidates: Identifiable {
    let id = UUID()
    let clueIDs: [UUID]
}

private struct ClueSelectionDialogModifier: ViewModifier {
    @Binding var selection: ClueSelectionCandidates?
    let clues: [OperationalClueRecord]
    let onSelect: (UUID) -> Void

    func body(content: Content) -> some View {
        content.confirmationDialog(
            "Select Clue",
            isPresented: Binding(
                get: { selection != nil },
                set: { if !$0 { selection = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let selection {
                ForEach(selection.clueIDs, id: \.self) { clueID in
                    if let clue = clues.first(where: { $0.id == clueID }) {
                        Button(clue.title.isEmpty ? "Clue" : clue.title) {
                            onSelect(clueID)
                        }
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("More than one clue is at this map location.")
        }
    }
}

enum AppleOperationalStatusChipTone {
    case accent
    case danger
    case caution
    case normal
    case neutral

    var backgroundColor: Color {
        switch self {
        case .accent: Color.accentColor
        case .danger: Color(red: 0.827, green: 0.184, blue: 0.184)
        case .caution: Color(red: 0.961, green: 0.486, blue: 0)
        case .normal: Color(red: 0.180, green: 0.490, blue: 0.196)
        case .neutral: Color.secondary.opacity(0.18)
        }
    }

    var foregroundColor: Color {
        self == .neutral ? .primary : .white
    }
}

struct AppleOperationalStatusChipLabel: View {
    let title: String
    let tone: AppleOperationalStatusChipTone

    var body: some View {
        Text(title)
            .fontWeight(.medium)
            .foregroundStyle(tone.foregroundColor)
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                tone.backgroundColor,
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(
                        tone == .neutral ? Color.secondary.opacity(0.35) : Color.clear,
                        lineWidth: 1
                    )
            }
    }
}

@MainActor
private final class AppleMapViewportMemory: ObservableObject {
    var region = unresolvedAppleMapRegion
    var visibleMapRect: MKMapRect?
    var hasOperationalViewport = false
}

@MainActor
private final class ApplePilotDisplayStore: ObservableObject {
    @Published private(set) var revision = 0
    private let defaults = UserDefaults.standard

    func preference(for pilotCallsign: String?) -> PilotDisplayPreference {
        guard let pilotKey = PilotDisplayPreference.normalizePilotCallsign(pilotCallsign) else {
            return PilotDisplayPreference()
        }
        let key = storageKey(pilotKey)
        return PilotDisplayPreference(
            activeTrackColor: defaults.string(forKey: key + ".active") ?? defaultActiveTrackColor,
            archiveTrackColor: defaults.string(forKey: key + ".archive") ?? defaultArchiveTrackColor,
            bearingEnabled: defaults.bool(forKey: key + ".bearing")
        )
    }

    func save(_ preference: PilotDisplayPreference, for pilotCallsign: String) {
        guard let pilotKey = PilotDisplayPreference.normalizePilotCallsign(pilotCallsign) else { return }
        let sanitized = PilotDisplayPreference(
            activeTrackColor: preference.activeTrackColor,
            archiveTrackColor: preference.archiveTrackColor,
            bearingEnabled: preference.bearingEnabled
        )
        let key = storageKey(pilotKey)
        defaults.set(sanitized.activeTrackColor, forKey: key + ".active")
        defaults.set(sanitized.archiveTrackColor, forKey: key + ".archive")
        defaults.set(sanitized.bearingEnabled, forKey: key + ".bearing")
        revision += 1
    }

    func reset(_ pilotCallsign: String) {
        guard let pilotKey = PilotDisplayPreference.normalizePilotCallsign(pilotCallsign) else { return }
        let key = storageKey(pilotKey)
        defaults.removeObject(forKey: key + ".active")
        defaults.removeObject(forKey: key + ".archive")
        defaults.removeObject(forKey: key + ".bearing")
        revision += 1
    }

    private func storageKey(_ pilotKey: String) -> String {
        let safe = pilotKey.unicodeScalars.filter {
            CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_")).contains($0)
        }.map(String.init).joined()
        return "map.pilotDisplay.\(safe.isEmpty ? "pilot" : safe)"
    }
}

@MainActor
private final class AppleMapArtifactModel: ObservableObject {
    @Published private(set) var snapshot = CaltopoArtifactSnapshot()
    @Published private(set) var status = "Map artifacts not configured"
    @Published private(set) var isRefreshing = false
    @Published var hiddenFolderIDs: Set<String> = []
    @Published var hiddenItemIDs: Set<String> = []
    @Published private(set) var zoomRequest: ArtifactZoomRequest?

    private var refreshTask: Task<Void, Never>?
    private var configurationFingerprint = ""
    private var configuredMapID = ""
    private var visibilityInitialized = false
    private var folderVisibilityOverrides: [String: Bool] = [:]
    private var lastPollAtByMapID: [String: Date] = [:]

    private static let minimumPollInterval: TimeInterval = 30
    private static let automaticPollInterval: Duration = .seconds(90)

    init() {
        Self.removeLegacyPersistedVisibility()
    }

    var visibleSnapshot: CaltopoArtifactSnapshot {
        snapshot.hiding(folderIDs: hiddenFolderIDs, itemIDs: hiddenItemIDs)
    }

    func configure(_ configuration: AppleCaltopoConfiguration) {
        let fingerprint = [
            configuration.domainAndPort,
            configuration.mapID,
            configuration.credentialID,
            String(configuration.credentialSecret.hashValue),
        ].joined(separator: "|")
        guard fingerprint != configurationFingerprint else { return }
        let mapSessionChanged = configuredMapID != configuration.mapID
        configurationFingerprint = fingerprint
        configuredMapID = configuration.mapID
        refreshTask?.cancel()
        hiddenFolderIDs = []
        hiddenItemIDs = []
        if mapSessionChanged {
            folderVisibilityOverrides = [:]
        }
        visibilityInitialized = false
        guard !configuration.domainAndPort.isEmpty,
              !configuration.mapID.isEmpty,
              !configuration.credentialID.isEmpty,
              !configuration.credentialSecret.isEmpty
        else {
            snapshot = CaltopoArtifactSnapshot()
            status = "Map artifacts not configured"
            return
        }
        if let cached = Self.loadCachedSnapshot(mapID: configuration.mapID) {
            snapshot = cached
            initializeVisibility(fallbackFolders: cached.folders)
            status = "Cached: \(cached.points.count) markers, \(cached.lines.count) lines, \(cached.polygons.count) areas"
        } else {
            initializeVisibility(fallbackFolders: [])
        }
        let live = CaltopoLiveConfiguration(
            domainAndPort: configuration.domainAndPort,
            mapID: configuration.mapID,
            credentialID: configuration.credentialID,
            credentialSecretBase64: configuration.credentialSecret
        )
        refreshTask = Task { [weak self] in
            guard let self else { return }
            do {
                let client = try CaltopoLiveClient(configuration: live)
                while !Task.isCancelled {
                    await self.refresh(using: client)
                    try? await Task.sleep(for: Self.automaticPollInterval)
                }
            } catch {
                status = "Map artifacts: \(error.localizedDescription)"
            }
        }
    }

    func refresh(_ configuration: AppleCaltopoConfiguration) {
        AppleLog.info("Map", "Manual CalTopo artifact reload requested map=\(configuration.mapID)")
        // Operator-requested reloads are immediate. The minimum interval only
        // protects the automatic poller from unnecessary requests.
        lastPollAtByMapID[configuration.mapID] = nil
        configurationFingerprint = ""
        configure(configuration)
    }

    func toggleFolder(_ folder: CaltopoArtifactFolder) {
        if isFolderEffectivelyVisible(folder) {
            hiddenFolderIDs.insert(folder.id)
            folderVisibilityOverrides[folder.id] = false
        } else {
            unhideFolderAndAncestors(folder.id, recordOperatorOverride: true)
        }
    }

    func toggleItem(_ item: CaltopoArtifactItem) {
        if isItemEffectivelyVisible(item) {
            hiddenItemIDs.insert(item.id)
        } else {
            hiddenItemIDs.remove(item.id)
            unhideFolderAndAncestors(item.folderID, recordOperatorOverride: true)
        }
    }

    func setItems(_ items: [CaltopoArtifactItem], visible: Bool) {
        let ids = Set(items.map(\.id))
        if visible {
            hiddenItemIDs.subtract(ids)
            if let folderID = items.first?.folderID {
                unhideFolderAndAncestors(folderID, recordOperatorOverride: true)
            }
        }
        else { hiddenItemIDs.formUnion(ids) }
    }

    func requestZoom(to item: CaltopoArtifactItem) {
        let coordinates = snapshot.coordinates(forItemID: item.id)
        guard !coordinates.isEmpty else { return }
        zoomRequest = ArtifactZoomRequest(title: item.title, coordinates: coordinates)
    }

    func isFolderEffectivelyVisible(_ folder: CaltopoArtifactFolder) -> Bool {
        var current: CaltopoArtifactFolder? = folder
        var visited: Set<String> = []
        while let value = current, visited.insert(value.id).inserted {
            if hiddenFolderIDs.contains(value.id) { return false }
            current = value.parentID.flatMap { parentID in snapshot.folders.first { $0.id == parentID } }
        }
        return true
    }

    func isItemEffectivelyVisible(_ item: CaltopoArtifactItem) -> Bool {
        guard !hiddenItemIDs.contains(item.id),
              let folder = snapshot.folders.first(where: { $0.id == item.folderID })
        else { return false }
        return isFolderEffectivelyVisible(folder)
    }

    private func unhideFolderAndAncestors(_ folderID: String, recordOperatorOverride: Bool = false) {
        var currentID: String? = folderID
        var visited: Set<String> = []
        while let id = currentID, visited.insert(id).inserted {
            hiddenFolderIDs.remove(id)
            if recordOperatorOverride {
                folderVisibilityOverrides[id] = true
            }
            currentID = snapshot.folders.first { $0.id == id }?.parentID
        }
    }

    private func refresh(using client: CaltopoLiveClient) async {
        let mapID = configuredMapID
        if let lastPollAt = lastPollAtByMapID[mapID] {
            let remaining = Self.minimumPollInterval - Date().timeIntervalSince(lastPollAt)
            if remaining > 0 {
                status = "CalTopo refresh available in \(Int(remaining.rounded(.up)))s"
                do {
                    try await Task.sleep(for: .milliseconds(Int(remaining * 1_000)))
                } catch {
                    return
                }
            }
        }
        guard !Task.isCancelled, mapID == configuredMapID else { return }
        lastPollAtByMapID[mapID] = Date()
        isRefreshing = true
        defer { isRefreshing = false }
        status = "Refreshing CalTopo artifacts…"
        do {
            let value = try await client.fetchMapArtifacts()
            snapshot = value
            let serverHiddenFolders = Set(value.folders.filter { !$0.initiallyVisible }.map(\.id))
            if !visibilityInitialized {
                visibilityInitialized = true
            }
            // Match Android: folders hidden by CalTopo (notably the dated
            // completed-track archive) stay hidden after reconnects, while
            // local hides of otherwise-visible folders are preserved.
            hiddenFolderIDs = CaltopoArtifactVisibilityPolicy.hiddenFolderIDs(
                localHidden: hiddenFolderIDs,
                defaultHidden: serverHiddenFolders,
                operatorVisibilityOverrides: folderVisibilityOverrides
            )
            status = "\(value.points.count) markers, \(value.lines.count) lines, \(value.polygons.count) areas"
            Self.saveCachedSnapshot(value, mapID: configuredMapID)
            AppleLog.info("Map", "CalTopo artifact refresh features=\(value.totalFeatureCount) ignoredTracks=\(value.ignoredTrackCount) \(status)")
        } catch {
            status = "Map artifact refresh failed: \(error.localizedDescription)"
            AppleLog.error("Map", status)
        }
    }

    private static func cacheURL(mapID: String) -> URL {
        let root = (FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory)
            .appendingPathComponent("RID2Caltopo/MapArtifacts", isDirectory: true)
        let safeMapID = mapID.filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
        return root.appendingPathComponent("\(safeMapID.isEmpty ? "map" : safeMapID).json")
    }

    private func initializeVisibility(fallbackFolders: [CaltopoArtifactFolder]) {
        hiddenFolderIDs = Set(fallbackFolders.filter { !$0.initiallyVisible }.map(\.id))
        hiddenItemIDs = []
        visibilityInitialized = !fallbackFolders.isEmpty
        hiddenFolderIDs = CaltopoArtifactVisibilityPolicy.hiddenFolderIDs(
            localHidden: hiddenFolderIDs,
            defaultHidden: [],
            operatorVisibilityOverrides: folderVisibilityOverrides
        )
    }

    private static func removeLegacyPersistedVisibility() {
        let defaults = UserDefaults.standard
        let keys = CaltopoArtifactVisibilityPolicy.legacyPersistedSelectionKeys(
            Array(defaults.dictionaryRepresentation().keys)
        )
        keys.forEach { defaults.removeObject(forKey: $0) }
        if !keys.isEmpty {
            AppleLog.info("Map", "Removed \(keys.count) legacy persisted Map Folders selection keys")
        }
    }

    private static func loadCachedSnapshot(mapID: String) -> CaltopoArtifactSnapshot? {
        guard let data = try? Data(contentsOf: cacheURL(mapID: mapID)) else { return nil }
        return try? JSONDecoder().decode(CaltopoArtifactSnapshot.self, from: data)
    }

    private static func saveCachedSnapshot(_ snapshot: CaltopoArtifactSnapshot, mapID: String) {
        let destination = cacheURL(mapID: mapID)
        do {
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(snapshot).write(to: destination, options: .atomic)
        } catch {
            AppleLog.warning("Map", "Artifact cache write failed: \(error.localizedDescription)")
        }
    }
}

struct RIDTrackMapView: View {
    private static let splitDividerTouchThickness: CGFloat = 88
    private static let splitDividerTouchLength: CGFloat = 96

    @ObservedObject var model: RIDTrackViewModel
    @ObservedObject var locationProvider: AppleLocationProvider
    let caltopoConfiguration: AppleCaltopoConfiguration
    @ObservedObject var streamRegistry: AppleStreamRegistry
    @ObservedObject var videoModel: AppleVideoFrameSource
    @ObservedObject var clueStore: AppleClueStore
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    @ObservedObject var notams: AppleNotamCenter
    @ObservedObject var peerCoordinator: AppleTrackerCoordinator
    @ObservedObject private var airspace = AppleAirspaceCenter.shared
    @ObservedObject private var landRestrictions = AppleLandRestrictionCenter.shared
    let streamURL: URL?
    let ingestAddress: String
    let networkSSID: String
    let bridgeSignalStrengthDbm: Int?
    @Binding var automaticStreamPairingAircraftID: String?
    let onMapStatusTap: () -> Void
    let onSwitchMap: () -> Void
    let onDisconnectMap: () -> Void
    let onRestartStreams: () -> Void

    @StateObject private var artifacts = AppleMapArtifactModel()
    @StateObject private var pilotDisplay = ApplePilotDisplayStore()
    @ObservedObject private var offlineMaps = AppleMapOfflineManager.shared
    @StateObject private var viewportMemory = AppleMapViewportMemory()
    @AppStorage("map.baseLayer") private var storedBaseLayer = OperationalMapBaseLayer.openStreetMap.rawValue
    // Match Android's session-scoped StreamsLayoutMode: every app process starts
    // in Split, while changes remain local to the current Live View session.
    @State private var storedLayout = OperationalMapVideoLayout.split.rawValue
    @AppStorage("map.showContours") private var showContours = false
    @AppStorage("map.offlineOnly") private var offlineOnly = false
    @AppStorage("map.followFocusedDrone") private var followFocusedDrone = true
    @AppStorage("map.videoPipEnabled") private var videoPipEnabled = false
    @AppStorage("map.videoPipInsetFraction")
    private var videoPipInsetFraction = OperationalPipSizing.defaultInsetFraction
    @AppStorage("video.coordinateDisplayFormat")
    private var coordinateDisplayFormatRaw = OperationalCoordinateDisplayFormat.decimal.rawValue
    @State private var viewport = unresolvedAppleMapRegion
    @State private var showMapItems = false
    @State private var showOfflinePreparation = false
    @State private var showMapManagement = false
    @State private var selectedBadTile: AppleCachedMapTileSelection?
    @State private var mapTileNotice: String?
    @State private var selectedPilotSettings: PilotDisplaySelection?
    @State private var focusedAircraftID: String?
    @State private var operatorAdjustedViewport = false
    @State private var pendingSnapshot: PendingClueSnapshot?
    @State private var selectedClueID: UUID?
    @State private var clueSelectionCandidates: ClueSelectionCandidates?
    @State private var selectedArtifactInspection: ArtifactInspection?
    @State private var capturingSnapshot = false
    @State private var clueError: String?
    @State private var showNotams = false
    @State private var showAirspace = false
    @State private var showLandRestrictions = false
    @State private var showMapOptions = false
    @State private var showMutualAidExport = false
    @State private var splitFraction: CGFloat = 0.5
    @State private var splitDragStartFraction: CGFloat?
    @State private var streamsFullScreen = false
    @State private var automaticallyExpandedStreamID: String?
    @State private var pipEditorMode = false
    @State private var pipResizeDragStartFraction: Double?
    @State private var expandedStreamID: String?
    @State private var pairingStreamID: String?
    @State private var cameraTelemetryRefreshToken = 0
    @State private var seiTrackPointsByAircraftID: [String: [AppleSEIMapPoint]] = [:]

    private var baseLayer: OperationalMapBaseLayer {
        get { OperationalMapBaseLayer(rawValue: storedBaseLayer) ?? .openStreetMap }
        nonmutating set { storedBaseLayer = newValue.rawValue }
    }

    private var layout: OperationalMapVideoLayout {
        get { OperationalMapVideoLayout(rawValue: storedLayout) ?? .map }
        nonmutating set { storedLayout = newValue.rawValue }
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                layoutContent(
                    size: geometry.size,
                    presentation: streamsFullScreen
                        ? layout.fullScreenPresentation(pictureInPictureEnabled: videoPipEnabled)
                        : layout
                )
                if streamsFullScreen {
                    HStack {
                        Button("Exit FS") { streamsFullScreen = false }
                            .accessibilityLabel("Exit full screen")
                        Button(videoPipEnabled ? "PiP:On" : "PiP:Off") {
                            videoPipEnabled.toggle()
                            if !videoPipEnabled { pipEditorMode = false }
                            applyPipPreference()
                        }
                        .accessibilityLabel(
                            videoPipEnabled
                                ? "Turn picture in picture off"
                                : "Turn picture in picture on"
                        )
                        BridgeSignalIndicator(rssi: bridgeSignalStrengthDbm)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(10)
                }
            }
        }
        .safeAreaInset(edge: .top) {
            if !streamsFullScreen {
                androidLiveViewStatusBar
            }
        }
        .safeAreaInset(edge: .bottom) {
            if peerCoordinator.activeRemoteVideoConnectionCount > 0 {
                remoteVideoStatusBar
            }
        }
        .navigationTitle("Live View")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(streamsFullScreen ? .hidden : .visible, for: .navigationBar)
        .toolbar { mapToolbar }
        .sheet(isPresented: $showMapItems) {
            MapItemsVisibilityView(
                model: artifacts,
                onZoomToItem: { item in
                    focusedAircraftID = nil
                    operatorAdjustedViewport = true
                    artifacts.requestZoom(to: item)
                }
            )
        }
        .sheet(isPresented: $showOfflinePreparation) {
            AppleOfflineMapPreparationView(
                manager: offlineMaps,
                viewportBounds: viewport.offlineBounds,
                boundaries: offlineBoundaryOptions,
                baseLayer: baseLayer,
                contoursInitiallyEnabled: showContours
            )
        }
        .sheet(isPresented: $showMapManagement) {
            AppleMapCacheManagementView(
                manager: offlineMaps,
                offlineOnly: $offlineOnly,
                followFocusedDrone: Binding(
                    get: { followFocusedDrone },
                    set: { enabled in
                        followFocusedDrone = enabled
                        if enabled { operatorAdjustedViewport = false }
                    }
                ),
                canReloadMap: caltopoConfiguration.liveConfiguration != nil,
                mapReloadInFlight: artifacts.isRefreshing,
                mapReloadStatus: artifacts.status,
                onReloadMap: { artifacts.refresh(caltopoConfiguration) },
                onExportMutualAid: {
                    showMapManagement = false
                    Task { @MainActor in showMutualAidExport = true }
                }
            )
        }
        .sheet(item: $selectedBadTile) { selection in
            BadTileRemovalView(
                selection: selection,
                onRemove: { quarantine in
                    offlineMaps.removeCachedTile(selection, quarantineMatchingHash: quarantine)
                    selectedBadTile = nil
                }
            )
        }
        .alert(
            "Bad Tile",
            isPresented: Binding(
                get: { mapTileNotice != nil },
                set: { if !$0 { mapTileNotice = nil } }
            )
        ) {
            Button("OK") { mapTileNotice = nil }
        } message: {
            Text(mapTileNotice ?? "")
        }
        .alert(item: $selectedArtifactInspection) { artifact in
            Alert(
                title: Text(artifact.title),
                message: Text(artifact.description ?? "No description is available for this map item."),
                dismissButton: .default(Text("Close"))
            )
        }
        .modifier(ClueSelectionDialogModifier(
            selection: $clueSelectionCandidates,
            clues: clueStore.records,
            onSelect: { selectedClueID = $0 }
        ))
        .sheet(isPresented: pairingSheetPresented) {
            if let pairingStreamID {
                StreamAircraftPairingView(
                    streamID: pairingStreamID,
                    tracks: model.tracks,
                    identityStore: identityStore,
                    selectedAircraftID: streamRegistry.boundAircraftID(for: pairingStreamID),
                    onSelect: { aircraftID in
                        streamRegistry.pair(streamID: pairingStreamID, aircraftID: aircraftID)
                        self.pairingStreamID = nil
                    },
                    onUnpair: {
                        streamRegistry.unpair(streamID: pairingStreamID)
                        self.pairingStreamID = nil
                    }
                )
            }
        }
        .onChange(of: automaticStreamPairingAircraftID, initial: true) { _, aircraftID in
            handleAutomaticStreamPairing(aircraftID)
        }
        .sheet(isPresented: $showNotams) { AppleNotamPanel(center: notams, location: locationProvider.lastLocation) }
        .sheet(isPresented: $showAirspace) {
            AppleAirspacePanel(
                center: airspace,
                notams: notams,
                location: locationProvider.lastLocation
            )
        }
        .sheet(isPresented: $showLandRestrictions) {
            AppleLandRestrictionPanel(center: landRestrictions, location: locationProvider.lastLocation)
        }
        .confirmationDialog(
            "Map Options",
            isPresented: $showMapOptions,
            titleVisibility: .visible
        ) {
            Button("Switch Map", action: onSwitchMap)
            Button("Disconnect", role: .destructive, action: onDisconnectMap)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You are currently synced with: \(liveViewMapTitle)")
        }
        .sheet(isPresented: $showMutualAidExport) {
            AppleMutualAidExportView(
                bounds: viewport.offlineBounds,
                layer: baseLayer,
                caltopo: caltopoConfiguration,
                organization: orgSettings
            )
        }
        .sheet(item: $selectedPilotSettings) { selection in
            PilotDisplaySettingsView(
                selection: selection,
                track: model.tracks.first { $0.aircraftID == selection.remoteID },
                altitudeDisplay: model.altitudeDisplayByAircraftID[selection.remoteID],
                followFocusedDrone: $followFocusedDrone,
                onCalibrateAltitude: { model.manualCalibrateAltitude(remoteID: selection.remoteID) },
                store: pilotDisplay
            )
        }
        .sheet(item: $pendingSnapshot) { pending in
            ClueSubmissionView(
                pending: pending,
                model: model,
                tracks: model.tracks,
                altitudeDisplay: model.altitudeDisplayByAircraftID,
                identityStore: identityStore,
                coordinateDisplayFormat: coordinateDisplayFormat,
                onSubmit: { draft, jpeg, publish in
                    do {
                        try clueStore.save(draft, jpegData: jpeg, publishToCaltopo: publish)
                        pendingSnapshot = nil
                    } catch {
                        clueError = "Clue could not be saved locally: \(error.localizedDescription)"
                    }
                },
                onCancel: { pendingSnapshot = nil }
            )
        }
        .sheet(item: Binding(
            get: { selectedClueID.flatMap { id in clueStore.records.first { $0.id == id } } },
            set: { selectedClueID = $0?.id }
        )) { clue in
            ClueDetailView(
                clue: clue,
                store: clueStore,
                coordinateDisplayFormat: coordinateDisplayFormat,
                onClose: { selectedClueID = nil }
            )
        }
        .alert("Clue Snapshot", isPresented: Binding(
            get: { clueError != nil },
            set: { if !$0 { clueError = nil } }
        )) { Button("OK") { clueError = nil } } message: { Text(clueError ?? "") }
        .task { artifacts.configure(caltopoConfiguration) }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard !Task.isCancelled else { break }
                refreshSEIMapTails()
                cameraTelemetryRefreshToken &+= 1
            }
        }
        .task {
            guard ProcessInfo.processInfo.arguments.contains("--demo-focus-first-drone") else { return }
            try? await Task.sleep(for: .seconds(2))
            focusedAircraftID = model.tracks.first?.aircraftID
            followFocusedDrone = true
            operatorAdjustedViewport = false
        }
        .task {
            let arguments = ProcessInfo.processInfo.arguments
            guard arguments.contains("--demo-clue-sheet") || arguments.contains("--demo-local-clue") else { return }
            try? await Task.sleep(for: .seconds(2))
            guard let track = model.tracks.first, let snapshot = Self.demoClueSnapshot() else { return }
            if arguments.contains("--demo-clue-sheet") {
                pendingSnapshot = PendingClueSnapshot(
                    snapshot: snapshot,
                    defaultAircraftID: track.aircraftID,
                    gimbalAngleDegrees: -60,
                    observation: track.lastObservation,
                    altitudeDisplay: model.altitudeDisplayByAircraftID[track.aircraftID],
                    heading: OperationalClueGeometry.selectedHeading(
                        cameraYawDegrees: nil,
                        streamHeadingDegrees: nil,
                        ridHeadingDegrees: track.lastObservation.headingDegrees
                    )
                )
            }
            if arguments.contains("--demo-local-clue"),
               !clueStore.records.contains(where: { $0.title == "Simulator clue" }) {
                let observation = track.lastObservation
                let display = model.altitudeDisplayByAircraftID[track.aircraftID]
                let aglMeters = display?.aglFeet.map { $0 * 0.3048 }
                let projection = OperationalClueGeometry.project(
                    droneLatitude: observation.latitude,
                    droneLongitude: observation.longitude,
                    droneAltitudeMeters: observation.altitudeMeters,
                    headingDegrees: observation.headingDegrees,
                    aglMeters: aglMeters,
                    gimbalAngleDegrees: -60
                )
                let identity = identityStore.identity(for: track.aircraftID)
                _ = try? clueStore.save(AppleClueDraft(
                    capturedAt: snapshot.capturedAt,
                    aircraftID: track.aircraftID,
                    designator: identity?.mappedID ?? track.aircraftID,
                    droneLatitude: observation.latitude,
                    droneLongitude: observation.longitude,
                    droneAltitudeMeters: observation.altitudeMeters,
                    clueLatitude: projection.latitude,
                    clueLongitude: projection.longitude,
                    clueAltitudeMeters: projection.altitudeMeters,
                    headingDegrees: observation.headingDegrees,
                    aglMeters: aglMeters,
                    atoMeters: display?.atoFeet.map { $0 * 0.3048 },
                    gimbalAngleDegrees: -60,
                    title: "Simulator clue",
                    description: "Deterministic local clue used for Apple MapPane qualification."
                ), jpegData: snapshot.jpegData, publishToCaltopo: false)
            }
        }
        .onChange(of: caltopoConfiguration) { _, configuration in
            artifacts.configure(configuration)
        }
        .onAppear {
            if offlineMaps.isRunning {
                showOfflinePreparation = true
            }
            if ProcessInfo.processInfo.arguments.contains("--show-anomaly")
                || ProcessInfo.processInfo.arguments.contains("--show-streams") {
                layout = .video
            } else {
                applyPipPreference()
            }
        }
    }

    private func handleAutomaticStreamPairing(_ aircraftID: String?) {
        guard let aircraftID else { return }
        let liveUnpairedStreamIDs: [String] = streamRegistry.sessions.compactMap {
            (session: AppleLiveStreamSession) -> String? in
            guard session.id != "demo",
                  session.state == .live,
                  streamRegistry.boundAircraftID(for: session.id) == nil
            else { return nil }
            return session.id
        }
        let target = OperationalStreamDesignatorMatch.automaticPairingStreamID(
            confirmedCandidateID: aircraftID,
            activeCandidateIDs: model.tracks.map(\.aircraftID),
            liveUnpairedStreamIDs: liveUnpairedStreamIDs
        )
        automaticStreamPairingAircraftID = nil
        if let target {
            AppleLog.info(
                "Streams",
                "Presenting automatic telemetry pairing prompt stream=\(target) remoteID=\(aircraftID)"
            )
            pairingStreamID = target
        } else {
            AppleLog.info(
                "Streams",
                "Automatic telemetry pairing prompt skipped after confirmation remoteID=\(aircraftID) "
                    + "activeTelemetry=\(model.tracks.count) liveUnpaired=\(liveUnpairedStreamIDs.count)"
            )
        }
    }

    private var remoteVideoStatusBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .foregroundStyle(.green)
            VStack(alignment: .leading, spacing: 2) {
                Text(
                    "Remote viewing: \(peerCoordinator.activeRemoteVideoConnectionCount) " +
                    (peerCoordinator.activeRemoteVideoConnectionCount == 1 ? "connection" : "connections")
                )
                .font(.headline)
                Text(
                    "\(peerCoordinator.activeRemoteVideoRequesterSummary) • " +
                    "\(peerCoordinator.activeRemoteVideoRouteSummary) • " +
                    "\(remoteVideoByteText(peerCoordinator.remoteVideoBytesSent)) sent"
                )
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                if peerCoordinator.remoteVideoEffectiveWidth > 0 {
                    Text(String(
                        format: "%d×%d • %.1f fps • %.2f Mbps actual",
                        peerCoordinator.remoteVideoEffectiveWidth,
                        peerCoordinator.remoteVideoEffectiveHeight,
                        peerCoordinator.remoteVideoEffectiveFPS,
                        Double(peerCoordinator.remoteVideoEffectiveBitrateBps) / 1_000_000
                    ))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }
                if let microphoneError = peerCoordinator.remoteVideoMicrophoneError {
                    Text(microphoneError)
                        .font(.caption2)
                        .foregroundStyle(.red)
                        .lineLimit(1)
                }
                Text(
                    "VoIP: \(remoteVideoByteText(peerCoordinator.remoteVideoAudioBytesSent)) sent • " +
                    "\(remoteVideoByteText(peerCoordinator.remoteVideoAudioBytesReceived)) received"
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            }
            Spacer(minLength: 8)
            Button {
                peerCoordinator.toggleRemoteVideoMicrophone()
            } label: {
                ZStack {
                    Image(systemName: "mic.fill")
                        .font(.title2)
                    if peerCoordinator.remoteVideoMicrophoneEnabled {
                        Image(systemName: "megaphone.fill")
                            .font(.caption)
                            .foregroundStyle(.green)
                            .offset(x: 16, y: -13)
                    } else {
                        Rectangle()
                            .fill(.red)
                            .frame(width: 30, height: 3)
                            .rotationEffect(.degrees(-45))
                    }
                }
                .frame(width: 40, height: 40)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel(
                peerCoordinator.remoteVideoMicrophoneEnabled
                    ? "Turn microphone off"
                    : "Turn microphone on"
            )
            Button("Terminate", role: .destructive) {
                peerCoordinator.terminateAllRemoteVideoStreams()
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(.regularMaterial)
        .overlay(alignment: .top) { Divider() }
    }

    private func remoteVideoByteText(_ bytes: Int64) -> String {
        let count = Double(max(0, bytes))
        if count >= 1_000_000_000 { return String(format: "%.2f GB", count / 1_000_000_000) }
        if count >= 1_000_000 { return String(format: "%.1f MB", count / 1_000_000) }
        if count >= 1_000 { return String(format: "%.1f KB", count / 1_000) }
        return "\(Int64(count)) B"
    }

    @ViewBuilder
    private func layoutContent(
        size: CGSize,
        presentation: OperationalMapVideoLayout? = nil
    ) -> some View {
        let activeLayout = presentation ?? layout
        return ZStack {
            switch activeLayout {
            case .map:
                mapPane(inset: false)
            case .video:
                videoPane
            case .split:
                splitLayout(size: size)
            case .mapPrimary:
                ZStack(alignment: .bottomTrailing) {
                    mapPane(inset: false)
                    insetFrame(size: size, onTap: { layout = .videoPrimary }) { videoPane }
                }
            case .videoPrimary:
                ZStack(alignment: .bottomTrailing) {
                    videoPane
                    insetFrame(size: size, onTap: { layout = .mapPrimary }) { mapPane(inset: true) }
                }
            }
            if !streamsFullScreen {
                let vertical = size.width > size.height
                let fraction = effectiveSplitFraction(for: activeLayout)
                splitDivider(vertical: vertical, available: vertical ? size.width : size.height)
                    .frame(
                        width: vertical ? Self.splitDividerTouchThickness : size.width,
                        height: vertical ? size.height : Self.splitDividerTouchThickness
                    )
                    .position(
                        x: vertical ? size.width * fraction : size.width / 2,
                        y: vertical ? size.height / 2 : size.height * fraction
                    )
            }
        }
    }

    @ViewBuilder
    private func splitLayout(size: CGSize) -> some View {
        if size.width > size.height {
            HStack(spacing: 0) {
                if splitFraction > 0 {
                    videoPane
                        .frame(width: size.width * splitFraction)
                        .contentShape(Rectangle())
                        .simultaneousGesture(TapGesture().onEnded {
                            splitFraction = 1
                            layout = OperationalMapVideoLayout.video.withPictureInPicture(videoPipEnabled)
                        })
                }
                if splitFraction < 1 {
                    mapPane(inset: false)
                        .frame(width: size.width * (1 - splitFraction))
                        .contentShape(Rectangle())
                        .simultaneousGesture(TapGesture().onEnded {
                            splitFraction = 0
                            layout = OperationalMapVideoLayout.map.withPictureInPicture(videoPipEnabled)
                        })
                }
            }
        } else {
            VStack(spacing: 0) {
                if splitFraction > 0 {
                    videoPane
                        .frame(height: size.height * splitFraction)
                        .contentShape(Rectangle())
                        .simultaneousGesture(TapGesture().onEnded {
                            splitFraction = 1
                            layout = OperationalMapVideoLayout.video.withPictureInPicture(videoPipEnabled)
                        })
                }
                if splitFraction < 1 {
                    mapPane(inset: false)
                        .frame(height: size.height * (1 - splitFraction))
                        .contentShape(Rectangle())
                        .simultaneousGesture(TapGesture().onEnded {
                            splitFraction = 0
                            layout = OperationalMapVideoLayout.map.withPictureInPicture(videoPipEnabled)
                        })
                }
            }
        }
    }

    private func effectiveSplitFraction(for layout: OperationalMapVideoLayout) -> CGFloat {
        switch layout {
        case .map, .mapPrimary: 0
        case .video, .videoPrimary: 1
        case .split: splitFraction
        }
    }

    private func applySplitFraction(_ fraction: CGFloat) {
        splitFraction = fraction
        if fraction <= CGFloat(OperationalSplitSizing.minimumFraction) {
            layout = OperationalMapVideoLayout.map.withPictureInPicture(videoPipEnabled)
        } else if fraction >= CGFloat(OperationalSplitSizing.maximumFraction) {
            layout = OperationalMapVideoLayout.video.withPictureInPicture(videoPipEnabled)
        } else {
            videoPipEnabled = false
            pipEditorMode = false
            layout = .split
        }
    }

    private func splitDivider(vertical: Bool, available: CGFloat) -> some View {
        ZStack {
            Rectangle()
                .fill(Color.accentColor.opacity(0.75))
                .frame(
                    width: vertical ? 4 : nil,
                    height: vertical ? nil : 4
                )
                .allowsHitTesting(false)
            ZStack {
                Color.clear
                Capsule()
                    .fill(Color.accentColor)
                    .frame(
                        width: vertical ? 28 : 48,
                        height: vertical ? 48 : 28
                    )
                    .overlay {
                        HStack(spacing: 5) {
                            ForEach(0..<3) { _ in
                                Circle().fill(.white).frame(width: 4, height: 4)
                            }
                        }
                        .rotationEffect(vertical ? .degrees(90) : .zero)
                    }
            }
            .frame(
                width: vertical
                    ? Self.splitDividerTouchThickness
                    : Self.splitDividerTouchLength,
                height: vertical
                    ? Self.splitDividerTouchLength
                    : Self.splitDividerTouchThickness
            )
            .contentShape(Rectangle())
            .gesture(
                // Measure against the fixed screen coordinate space. Measuring in the
                // divider's local space makes its own movement alter the reported
                // translation, which produces visible jitter during a long drag.
                DragGesture(coordinateSpace: .global)
                    .onChanged { value in
                        guard available > 0 else { return }
                        let start = splitDragStartFraction ?? effectiveSplitFraction(for: layout)
                        if splitDragStartFraction == nil { splitDragStartFraction = start }
                        let delta = vertical ? value.translation.width : value.translation.height
                        let adjusted = OperationalSplitSizing.adjustedFraction(
                            current: Double(start),
                            dragDelta: Double(delta),
                            available: Double(available)
                        )
                        applySplitFraction(CGFloat(OperationalSplitSizing.snappedFraction(
                            adjusted,
                            available: Double(available),
                            handleWidth: 44
                        )))
                    }
                    .onEnded { _ in splitDragStartFraction = nil }
            )
            .accessibilityLabel("Resize video and map panes")
            .accessibilityValue("\(Int((effectiveSplitFraction(for: layout) * 100).rounded())) percent video")
            .accessibilityAdjustableAction { direction in
                let delta: Double = direction == .increment ? 0.05 : -0.05
                applySplitFraction(CGFloat(OperationalSplitSizing.adjustedFraction(
                    current: Double(effectiveSplitFraction(for: layout)),
                    dragDelta: delta,
                    available: 1
                )))
            }
        }
    }

    private func mapPane(inset: Bool) -> some View {
        let localClueArtifactIDs = Set(clueStore.records.flatMap { clue in
            [
                clue.caltopoMarkerID,
                clue.caltopoMediaID.uuidString,
            ].compactMap { $0 }
        })
        let renderedArtifacts = artifacts.visibleSnapshot.excludingRenderedPointIDs(
            localClueArtifactIDs.union([peerCoordinator.localZoneID])
        )
        let cameraFovByAircraftID = self.cameraFovByAircraftID
        let activeSEITrackPointsByAircraftID = self.activeSEITrackPointsByAircraftID
        return ZStack(alignment: .bottomTrailing) {
            OperationalMKMapView(
                tracks: mapTracks,
                seiTrackPointsByAircraftID: activeSEITrackPointsByAircraftID,
                aircraftDisplay: aircraftDisplay,
                altitudeDisplay: model.altitudeDisplayByAircraftID,
                cameraFovByAircraftID: cameraFovByAircraftID,
                clues: clueStore.records,
                artifacts: renderedArtifacts,
                airspaceState: airspace.enabled ? airspace.state : OperationalAirspaceState(),
                notamState: notams.showOnMap ? notams.state : AppleNotamState(),
                landRestrictionState: landRestrictions.showOnMap
                    ? landRestrictions.state
                    : AppleLandRestrictionState(),
                baseLayer: baseLayer,
                showContours: showContours,
                offlineOnly: offlineOnly,
                tileCacheRevision: offlineMaps.cacheStats.files,
                operatorCoordinate: locationProvider.lastLocation?.coordinate,
                operatorStatusLines: peerCoordinator.localDeviceStatusLines,
                viewport: $viewport,
                viewportMemory: viewportMemory,
                inset: inset,
                predictiveHeadEnabled: orgSettings.predictiveHeadEnabled,
                focusedAircraftID: focusedAircraftID,
                followFocusedDrone: followFocusedDrone,
                artifactZoomRequest: artifacts.zoomRequest,
                operatorAdjustedViewport: $operatorAdjustedViewport,
                onSelectClue: { clueIDs in
                    let available = clueIDs.filter { clueID in
                        clueStore.records.contains { $0.id == clueID }
                    }
                    if available.count == 1 {
                        selectedClueID = available[0]
                    } else if !available.isEmpty {
                        clueSelectionCandidates = ClueSelectionCandidates(clueIDs: available)
                    }
                },
                onSelectArtifact: { title, description in
                    guard !inset else { return }
                    selectedArtifactInspection = ArtifactInspection(title: title, description: description)
                },
                onSelectAircraft: { remoteID in
                    focusedAircraftID = remoteID
                    if followFocusedDrone { operatorAdjustedViewport = false }
                    guard !inset else { return }
                    let identity = identityStore.identity(for: remoteID)
                    selectedPilotSettings = PilotDisplaySelection(
                        id: remoteID,
                        remoteID: remoteID,
                        displayName: identity?.mappedID ?? remoteID,
                        pilotCallsign: identity?.pilotCallsign ?? ""
                    )
                },
                onOperatorViewportGesture: {
                    guard focusedAircraftID != nil else { return }
                    focusedAircraftID = nil
                    AppleLog.info("MapViewport", "Operator gesture released focused drone")
                },
                onLongPressTile: { zoom, x, y in
                    Task {
                        if let selection = await offlineMaps.cachedTileSelection(
                            zoom: zoom,
                            x: x,
                            y: y,
                            baseLayer: baseLayer
                        ) {
                            selectedBadTile = selection
                        } else {
                            mapTileNotice = "Selected tile z=\(zoom) x=\(x) y=\(y) is not cached yet."
                        }
                    }
                }
            )
            Text(mapAttribution(inset: inset))
                .font(.system(size: inset ? 8 : 10))
                .foregroundStyle(.primary)
                .padding(.horizontal, 5)
                .padding(.vertical, 3)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 4))
                .padding(5)
                .allowsHitTesting(false)
            if !inset {
                VStack {
                    HStack {
                        Spacer()
                        mapSettingsMenu
                    }
                    Spacer()
                }
                .padding(6)
            }
        }
    }

    private var mapSettingsMenu: some View {
        Menu {
            Menu("Layer: \(baseLayer.label)") {
                ForEach(OperationalMapBaseLayer.allCases, id: \.self) { option in
                    Button {
                        baseLayer = option
                    } label: {
                        if option == baseLayer {
                            Label(option.label, systemImage: "checkmark")
                        } else {
                            Text(option.label)
                        }
                    }
                }
                Divider()
                Button {
                    showContours.toggle()
                } label: {
                    Text("Contours: \(showContours ? "On" : "Off")")
                }
            }
            Button("Predictive Head: \(orgSettings.predictiveHeadEnabled ? "On" : "Off")") {
                orgSettings.setPredictiveHeadEnabled(!orgSettings.predictiveHeadEnabled)
            }
            Button(
                offlineMaps.downloadMenuStatus.map { "Download Map: \($0)" } ?? "Download Map…"
            ) { showOfflinePreparation = true }
            Button("Map Folders…") { showMapItems = true }
                .disabled(artifacts.snapshot.folders.isEmpty)
            Button("Map Management…") { showMapManagement = true }
        } label: {
            Image(systemName: "gearshape.fill")
                .font(.title3)
                .frame(width: 42, height: 42)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
        }
        // SwiftUI otherwise reverses the visual order when this menu opens upward.
        .menuOrder(.fixed)
        .accessibilityLabel("Map settings")
    }

    private func mapAttribution(inset: Bool) -> String {
        let base = baseLayer == .openStreetMap ? "© OpenStreetMap contributors" : "Tiles © Esri"
        return showContours && !inset ? "\(base) · Contours: USGS" : base
    }

    private var aircraftDisplay: [String: AircraftMapDisplay] {
        _ = pilotDisplay.revision
        return Dictionary(uniqueKeysWithValues: mapTracks.map { track in
            let identity = identityStore.identity(for: track.aircraftID)
            return (
                track.aircraftID,
                AircraftMapDisplay(
                    title: identity?.mappedID
                        ?? model.peerTrafficMappedIDByAircraftID[track.aircraftID]
                        ?? track.aircraftID,
                    preference: pilotDisplay.preference(for: identity?.pilotCallsign),
                    showFullFlightTrack: identityStore.isCurrentFlightConfirmed(track.aircraftID)
                )
            )
        })
    }

    private var mapTracks: [RidAircraftTrack] {
        let localByID = Dictionary(uniqueKeysWithValues: model.tracks.map { ($0.aircraftID, $0) })
        let latestPeerByID = Dictionary(
            model.peerTrafficTracks.map { ($0.aircraftID, $0) },
            uniquingKeysWith: { current, candidate in
                candidate.lastObservation.receivedAt > current.lastObservation.receivedAt
                    ? candidate
                    : current
            }
        )
        let peerPreferredIDs = Set(latestPeerByID.compactMap { aircraftID, peerTrack in
            OperationalMapTrackFreshness.prefersPeer(
                localSampleAt: localByID[aircraftID]?.lastObservation.receivedAt,
                peerSampleAt: peerTrack.lastObservation.receivedAt
            ) ? aircraftID : nil
        })
        return model.tracks.filter { !peerPreferredIDs.contains($0.aircraftID) } +
            model.peerTrafficTracks.filter { peerPreferredIDs.contains($0.aircraftID) }
    }

    private var offlineBoundaryOptions: [AppleOfflineMapPreparationView.BoundaryOption] {
        artifacts.visibleSnapshot.polygons.map {
            .init(id: "polygon:\($0.id)", title: $0.title, coordinates: $0.coordinates)
        } + artifacts.visibleSnapshot.lines.map {
            .init(id: "line:\($0.id)", title: $0.title, coordinates: $0.coordinates)
        }
    }

    private var videoPane: some View {
        ZStack {
            Color.black
            AppleStreamsGridView(
                registry: streamRegistry,
                ingestAddress: ingestAddress,
                showsSetupHeader: false,
                showsNavigationTitle: false,
                expandedSessionID: expandedStreamID,
                onSelectSession: toggleExpandedStream,
                onLongPressSession: { streamID in
                    AppleLog.info("Streams", "Manual telemetry pairing requested stream=\(streamID)")
                    pairingStreamID = streamID
                },
                onDoubleTapSession: { streamID, zoomScale, normalizedPan in
                    expandedStreamID = streamID
                    beginSnapshotCapture(
                        streamID: streamID,
                        zoomScale: zoomScale,
                        normalizedPan: normalizedPan
                    )
                },
                onCloseSession: { streamID in
                    streamRegistry.close(streamID)
                    if expandedStreamID == streamID { expandedStreamID = nil }
                },
                onRestartStreams: onRestartStreams,
                telemetryText: streamTelemetryText,
                coordinateText: streamCoordinateText,
                remoteRequesterEmail: peerCoordinator.activeRemoteVideoRequesterEmail,
                coordinateDisplayFormat: coordinateDisplayFormat,
                onCoordinateDisplayFormatChange: { coordinateDisplayFormat = $0 },
                telemetryPairingState: streamTelemetryPairingState,
                centerpointElevationFeet: centerpointElevationFeet
            )
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    Button(action: { beginSnapshotCapture() }) {
                        if capturingSnapshot {
                            ProgressView().tint(.white)
                        } else {
                            Image(systemName: "camera.fill")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .clipShape(Circle())
                    .disabled(capturingSnapshot || model.tracks.isEmpty || videoModel.frameCount == 0)
                    .accessibilityLabel("Capture clue snapshot")
                    // Keep the camera control clear of the enlarged divider grab area.
                    .padding(.trailing, 56)
                }
                Spacer()
            }
            .padding(10)
        }
        .onAppear {
            if videoModel.state == .idle, let streamURL { videoModel.start(url: streamURL) }
            reconcileOperationalStreamSelection()
        }
        .onChange(of: operationalStreamIDs) { _, _ in
            reconcileOperationalStreamSelection()
        }
    }

    private var operationalStreamIDs: [String] {
        streamRegistry.sessions.filter { $0.id != "demo" }.map(\.id)
    }

    private func toggleExpandedStream(_ id: String) {
        automaticallyExpandedStreamID = nil
        if expandedStreamID == id {
            expandedStreamID = nil
        } else {
            expandedStreamID = id
        }
    }

    private func reconcileOperationalStreamSelection() {
        let ids = operationalStreamIDs
        if ids.isEmpty {
            expandedStreamID = nil
            automaticallyExpandedStreamID = nil
        } else if ids.count == 1 {
            let streamID = ids[0]
            if expandedStreamID != streamID || automaticallyExpandedStreamID != nil {
                expandedStreamID = streamID
                automaticallyExpandedStreamID = streamID
            }
        } else {
            if automaticallyExpandedStreamID != nil {
                expandedStreamID = nil
                automaticallyExpandedStreamID = nil
            } else if let expandedStreamID, !ids.contains(expandedStreamID) {
                self.expandedStreamID = nil
            }
        }
    }

    private var pairingSheetPresented: Binding<Bool> {
        Binding(
            get: { pairingStreamID != nil },
            set: { if !$0 { pairingStreamID = nil } }
        )
    }

    private func aircraftID(for streamID: String) -> String? {
        if let bound = streamRegistry.boundAircraftID(for: streamID),
           model.tracks.contains(where: { $0.aircraftID == bound }) {
            return bound
        }
        let normalizedStream = streamID.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let exact = model.tracks.filter { track in
            let identity = identityStore.identity(for: track.aircraftID)
            return track.aircraftID.uppercased() == normalizedStream
                || identity?.mappedID.uppercased() == normalizedStream
        }
        if exact.count == 1 { return exact[0].aircraftID }
        return nil
    }

    private var cameraFovByAircraftID: [String: CameraFovBoundaryBearings] {
        _ = cameraTelemetryRefreshToken
        var result: [String: CameraFovBoundaryBearings] = [:]
        for session in streamRegistry.sessions {
            guard let aircraftID = aircraftID(for: session.id),
                  let telemetry = session.model.freshDJICameraTelemetry(),
                  let azimuth = telemetry.cameraAzimuthDegrees,
                  let boundaries = OperationalMapGeometry.cameraFovBoundaryBearings(
                    cameraAzimuthDegrees: azimuth,
                    horizontalFovDegrees: telemetry.horizontalFovDegrees
                  )
            else { continue }
            result[aircraftID] = boundaries
        }
        return result
    }

    private var activeSEITrackPointsByAircraftID: [String: [AppleSEIMapPoint]] {
        _ = cameraTelemetryRefreshToken
        let cutoff = Date().addingTimeInterval(-30)
        return seiTrackPointsByAircraftID.filter { _, points in
            points.last?.receivedAt ?? .distantPast >= cutoff
        }
    }

    private func refreshSEIMapTails(now: Date = Date()) {
        let activeAircraftIDs = Set(model.tracks.map(\.aircraftID))
        seiTrackPointsByAircraftID = seiTrackPointsByAircraftID.filter {
            activeAircraftIDs.contains($0.key)
        }
        let telemetryByAircraftID = streamRegistry.freshValidatedDJIPositionByAircraftID(
            tracks: model.tracks,
            at: now
        )
        for (aircraftID, telemetry) in telemetryByAircraftID {
            guard let latitude = telemetry.latitudeDegrees,
                  let longitude = telemetry.longitudeDegrees,
                  let track = model.tracks.first(where: { $0.aircraftID == aircraftID })
            else { continue }
            var points = seiTrackPointsByAircraftID[aircraftID, default: []]
            guard points.last?.receivedAt != telemetry.receivedAt else { continue }
            points.append(AppleSEIMapPoint(
                latitude: latitude,
                longitude: longitude,
                altitudeMeters: track.lastObservation.altitudeMeters,
                courseDegrees: telemetry.courseDegrees,
                receivedAt: telemetry.receivedAt
            ))
            peerCoordinator.observeSEITraffic(
                remoteID: aircraftID,
                mappedID: aircraftID,
                latitude: latitude,
                longitude: longitude,
                altitudeMeters: track.lastObservation.altitudeMeters,
                relativeUpMeters: telemetry.relativeUpMeters,
                headingDegrees: telemetry.courseDegrees,
                sampledAt: telemetry.receivedAt,
                altitudeSampledAt: track.lastObservation.receivedAt
            )
            if points.count > 10_000 {
                points.removeFirst(points.count - 10_000)
            }
            seiTrackPointsByAircraftID[aircraftID] = points
        }
    }

    private func streamTelemetryText(_ streamID: String) -> String? {
        guard let aircraftID = aircraftID(for: streamID),
              model.tracks.contains(where: { $0.aircraftID == aircraftID })
        else {
            return model.tracks.isEmpty ? nil : "Long-press to pair"
        }
        let identity = identityStore.identity(for: aircraftID)
        let label = identity?.mappedID.isEmpty == false ? identity!.mappedID : aircraftID
        let altitude = model.altitudeDisplayByAircraftID[aircraftID]
        let heading = model.tracks
            .first(where: { $0.aircraftID == aircraftID })?
            .lastObservation.headingDegrees
        return OperationalAircraftDisplay.streamHeader(
            designator: label,
            atoFeet: altitude?.atoFeet,
            aglFeet: altitude?.aglFeet,
            aglStale: altitude?.aglStale == true,
            rangeFeet: altitude?.rangeFeet,
            headingDegrees: heading
        )
    }

    private var coordinateDisplayFormat: OperationalCoordinateDisplayFormat {
        get { OperationalCoordinateDisplayFormat.restored(from: coordinateDisplayFormatRaw) }
        nonmutating set { coordinateDisplayFormatRaw = newValue.rawValue }
    }

    private func streamCoordinateText(_ streamID: String) -> String? {
        guard let aircraftID = aircraftID(for: streamID),
              let observation = model.tracks.first(where: { $0.aircraftID == aircraftID })?.lastObservation
        else { return nil }
        return OperationalCoordinateFormatter.format(
            latitude: observation.latitude,
            longitude: observation.longitude,
            as: coordinateDisplayFormat
        )
    }

    private func streamTelemetryPairingState(_ streamID: String) -> AppleStreamTelemetryPairingState {
        if aircraftID(for: streamID) != nil { return .paired }
        return model.tracks.isEmpty ? .noTelemetry : .available
    }

    private func centerpointElevationFeet(
        _ streamID: String
    ) async -> OperationalCenterpointElevation.Sample? {
        guard let aircraftID = aircraftID(for: streamID),
              let observation = model.tracks
                .first(where: { $0.aircraftID == aircraftID })?
                .lastObservation,
              let aglFeet = model.altitudeDisplayByAircraftID[aircraftID]?.aglFeet,
              aglFeet.isFinite,
              let session = streamRegistry.sessions.first(where: { $0.id == streamID }),
              let camera = session.model.freshDJICameraTelemetry(),
              let bearing = camera.cameraAzimuthDegrees
        else { return nil }
        return await model.centerpointElevationFeet(
            streamID: streamID,
            observation: observation,
            headingDegrees: bearing,
            aglMeters: aglFeet / 3.28084,
            gimbalAngleDegrees: camera.tiltDegrees
        )
    }

    private func beginSnapshotCapture(
        streamID requestedStreamID: String? = nil,
        zoomScale: Double = 1,
        normalizedPan: CGPoint = .zero
    ) {
        guard !capturingSnapshot else { return }
        let session = requestedStreamID.flatMap { requested in
            streamRegistry.sessions.first(where: { $0.id == requested })
        } ?? streamRegistry.focusedSession
        guard !model.tracks.isEmpty else {
            clueError = "No active aircraft is available to associate with this snapshot."
            return
        }
        let streamID = session.id
        guard let defaultAircraftID = aircraftID(for: streamID) else {
            clueError = "Long-press stream \(streamID) and pair it with a drone before capturing a clue."
            return
        }
        guard let captureTrack = model.tracks.first(where: { $0.aircraftID == defaultAircraftID }) else {
            clueError = "The paired aircraft telemetry is no longer available."
            return
        }
        let ridCaptureObservation = captureTrack.lastObservation
        let captureAltitudeDisplay = model.altitudeDisplayByAircraftID[defaultAircraftID]
        let takeoffMsl = captureTrack.points.first?.altitudeMeters
        let freshDjiCameraTelemetry = session.model.freshDJICameraTelemetry()
        let validatedDjiPositionTelemetry = streamRegistry
            .freshValidatedDJIPositionByAircraftID(tracks: model.tracks)[defaultAircraftID]
        let freshRawSEIPositionAvailable = freshDjiCameraTelemetry?.latitudeDegrees != nil &&
            freshDjiCameraTelemetry?.longitudeDegrees != nil
        let validatedSEIPositionAvailable = validatedDjiPositionTelemetry?.latitudeDegrees != nil &&
            validatedDjiPositionTelemetry?.longitudeDegrees != nil
        if OperationalCluePositionSourcePolicy.shouldBlockRIDFallback(
            seiPositionAuthorityEstablished: streamRegistry.isSEIPositionAuthorityEstablished(
                streamID: streamID
            ),
            freshRawSEIPositionAvailable: freshRawSEIPositionAvailable,
            validatedSEIPositionAvailable: validatedSEIPositionAvailable
        ) {
            AppleLog.warning(
                "Clues",
                "Clue blocked instead of falling back to RID for stream \(streamID)"
            )
            clueError = "Current video aircraft position is unavailable. Wait for SEI telemetry and try again."
            return
        }
        let seiMslAltitude: Double? = validatedDjiPositionTelemetry?.relativeUpMeters.flatMap { relativeUp -> Double? in
            takeoffMsl.map { $0 + relativeUp }
        }
        let captureObservation = RidObservation(
            source: ridCaptureObservation.source,
            aircraftId: ridCaptureObservation.aircraftId,
            receivedAt: ridCaptureObservation.receivedAt,
            latitude: validatedDjiPositionTelemetry?.latitudeDegrees ?? ridCaptureObservation.latitude,
            longitude: validatedDjiPositionTelemetry?.longitudeDegrees ?? ridCaptureObservation.longitude,
            altitudeMeters: seiMslAltitude ?? ridCaptureObservation.altitudeMeters,
            heightMeters: ridCaptureObservation.heightMeters,
            heightReference: ridCaptureObservation.heightReference,
            horizontalAccuracyCode: ridCaptureObservation.horizontalAccuracyCode,
            headingDegrees: ridCaptureObservation.headingDegrees,
            speedMetersPerSecond: ridCaptureObservation.speedMetersPerSecond,
            operatorLatitude: ridCaptureObservation.operatorLatitude,
            operatorLongitude: ridCaptureObservation.operatorLongitude,
            signalStrengthDbm: ridCaptureObservation.signalStrengthDbm,
            droneScoutRelay: ridCaptureObservation.droneScoutRelay
        )
        let captureGimbalPitch = freshDjiCameraTelemetry?.tiltDegrees
            ?? session.model.latestGimbalPitchDegrees
        let captureHeading = OperationalClueGeometry.selectedHeading(
            cameraAzimuthDegrees: freshDjiCameraTelemetry?.cameraAzimuthDegrees,
            videoCourseDegrees: freshDjiCameraTelemetry?.courseDegrees,
            cameraYawDegrees: freshDjiCameraTelemetry == nil
                ? session.model.latestCameraYawDegrees
                : nil,
            streamHeadingDegrees: session.model.latestStreamHeadingDegrees,
            ridHeadingDegrees: captureObservation.headingDegrees,
            derivedHeadingDegrees: OperationalMapGeometry.travelBearingDegrees(
                points: captureTrack.points
            )
        )
        capturingSnapshot = true
        Task {
            defer { capturingSnapshot = false }
            do {
                let snapshot = try await session.model.captureSnapshot(
                    zoomScale: zoomScale,
                    normalizedPan: normalizedPan
                )
                pendingSnapshot = PendingClueSnapshot(
                    snapshot: snapshot,
                    defaultAircraftID: defaultAircraftID,
                    gimbalAngleDegrees: OperationalClueGeometry.selectedGimbalAngleDegrees(
                        streamPitchDegrees: captureGimbalPitch
                    ),
                    observation: captureObservation,
                    altitudeDisplay: captureAltitudeDisplay,
                    heading: captureHeading,
                    djiCameraTelemetry: validatedDjiPositionTelemetry ?? freshDjiCameraTelemetry
                )
            } catch {
                clueError = error.localizedDescription
            }
        }
    }

    private static func demoClueSnapshot() -> AppleVideoSnapshot? {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 960, height: 540))
        let image = renderer.image { context in
            UIColor(red: 0.05, green: 0.12, blue: 0.20, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 960, height: 540))
            UIColor.systemOrange.setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 385, y: 175, width: 190, height: 190))
            let text = "RID2Caltopo Snapshot"
            text.draw(
                at: CGPoint(x: 310, y: 390),
                withAttributes: [
                    .font: UIFont.boldSystemFont(ofSize: 32),
                    .foregroundColor: UIColor.white,
                ]
            )
        }
        guard let jpeg = image.jpegData(compressionQuality: 0.85) else { return nil }
        return AppleVideoSnapshot(jpegData: jpeg, capturedAt: Date(), width: 960, height: 540)
    }

    private func insetFrame<Content: View>(
        size: CGSize,
        onTap: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) -> some View {
        let fullFrameAspectRatio = size.height > 0
            ? Double(size.width / size.height)
            : OperationalPipSizing.aspectRatio
        let insetSize = OperationalPipSizing.insetSize(
            containerWidth: size.width,
            containerHeight: size.height,
            insetFraction: videoPipInsetFraction,
            aspectRatio: fullFrameAspectRatio
        )
        let maximumInsetWidth = max(1, Double(size.width) - OperationalPipSizing.framePadding)

        return ZStack(alignment: .topLeading) {
            content()
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture(perform: onTap)
                .simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.5)
                        .onEnded { _ in pipEditorMode.toggle() }
                )
            if pipEditorMode {
                ZStack {
                    Color.accentColor.opacity(0.85)
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.headline)
                        .foregroundStyle(.white)
                }
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture()
                        .onChanged { value in
                            if pipResizeDragStartFraction == nil {
                                pipResizeDragStartFraction = videoPipInsetFraction
                            }
                            let start = pipResizeDragStartFraction
                                ?? OperationalPipSizing.defaultInsetFraction
                            let delta = (-Double(value.translation.width) - Double(value.translation.height))
                                / maximumInsetWidth
                            videoPipInsetFraction = OperationalPipSizing.clampInsetFraction(start + delta)
                        }
                        .onEnded { _ in pipResizeDragStartFraction = nil }
                )
                .accessibilityLabel("Resize picture in picture")
                .accessibilityHint("Drag diagonally to resize")
            }
        }
            .frame(width: insetSize.width, height: insetSize.height)
            .background(.black)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(pipEditorMode ? Color.accentColor : .white.opacity(0.8), lineWidth: 2)
            )
            .shadow(radius: 5)
            .padding(12)
    }

    private var androidLiveViewStatusBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                if airspace.enabled || notams.state.visible {
                    operationalStatusChip(
                        conciseAirspaceOrNotamChipLabel,
                        tone: usesAirspaceRestrictionStatus ? airspaceTone : notamTone
                    ) {
                        if usesAirspaceRestrictionStatus { showAirspace = true }
                        else { showNotams = true }
                    }
                }
                if landRestrictions.state.visible {
                    operationalStatusChip(
                        OperationalStatusChipText.land(
                            severity: landRestrictions.state.severity,
                            detailedLabel: landRestrictions.state.chipLabel
                        ),
                        tone: landRestrictionTone
                    ) {
                        showLandRestrictions = true
                    }
                }
                Text(ingestAddress.hasPrefix("rtmp://")
                    ? "🟢 In => \(ingestAddress)/<droneDesig>"
                    : "🟡 \(ingestAddress)")
                    .lineLimit(1)
                Button(action: openLiveViewMapActions) {
                    AppleOperationalStatusChipLabel(
                        title: liveViewMapTitle,
                        tone: .accent
                    )
                }
                .buttonStyle(.plain)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .accessibilityLabel("Incident map")
                .accessibilityValue(liveViewMapTitle)
                Text("on \(networkSSID)")
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Label("\(model.tracks.count) active", systemImage: "airplane.circle")
                Text("\(model.acceptedObservationCount) points")
                if offlineOnly { Label("Offline", systemImage: "wifi.slash") }
            }
        }
        .font(.caption.monospacedDigit())
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(.regularMaterial)
    }

    private var usesAirspaceRestrictionStatus: Bool {
        !notams.state.visible || airspace.state.severity != .normal
    }

    private var liveViewMapTitle: String {
        caltopoConfiguration.mapTitle.isEmpty
            ? (caltopoConfiguration.mapID.isEmpty ? "STANDALONE" : caltopoConfiguration.mapID)
            : caltopoConfiguration.mapTitle
    }

    private func openLiveViewMapActions() {
        let hasCredentials = !caltopoConfiguration.teamID.isEmpty
            && !caltopoConfiguration.credentialID.isEmpty
            && !caltopoConfiguration.credentialSecret.isEmpty
        guard hasCredentials, !caltopoConfiguration.mapID.isEmpty else {
            onMapStatusTap()
            return
        }
        showMapOptions = true
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

    private func operationalStatusChip(
        _ title: String,
        tone: AppleOperationalStatusChipTone,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            AppleOperationalStatusChipLabel(title: title, tone: tone)
        }
        .buttonStyle(.plain)
    }

    private var notamTone: AppleOperationalStatusChipTone {
        switch notams.state.chipSeverity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    private var airspaceTone: AppleOperationalStatusChipTone {
        switch airspace.state.severity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    private var landRestrictionTone: AppleOperationalStatusChipTone {
        switch landRestrictions.state.severity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    @ToolbarContentBuilder
    private var mapToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if !streamsFullScreen {
                Button("Enter FS") { streamsFullScreen = true }
                Button(videoPipEnabled ? "PiP:On" : "PiP:Off") {
                    videoPipEnabled.toggle()
                    if !videoPipEnabled { pipEditorMode = false }
                    applyPipPreference()
                }
                BridgeSignalIndicator(rssi: bridgeSignalStrengthDbm)
            }
        }
    }

    private func applyPipPreference() {
        if !videoPipEnabled { pipEditorMode = false }
        layout = layout.withPictureInPicture(videoPipEnabled)
    }

}

struct BridgeSignalIndicator: View {
    let rssi: Int?

    var body: some View {
        HStack(spacing: 5) {
            Text("Bridge \(rssi.map(String.init) ?? "—")")
                .font(.caption2)
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0 ..< 4, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(index < filledBarCount ? filledColor : Color.secondary.opacity(0.2))
                        .frame(width: 4, height: CGFloat(5 + index * 4))
                }
            }
            .frame(height: 18, alignment: .bottom)
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 5)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 9))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Bridge signal strength")
        .accessibilityValue(rssi.map { "\($0) decibels milliwatt" } ?? "not detected")
    }

    private var filledBarCount: Int {
        guard let rssi else { return 0 }
        if rssi >= -60 { return 4 }
        if rssi >= -70 { return 3 }
        if rssi >= -80 { return 2 }
        if rssi >= -90 { return 1 }
        return 0
    }

    private var filledColor: Color {
        switch filledBarCount {
        case 1: .red
        case 2: .yellow
        default: .green
        }
    }
}

private struct BadTileRemovalView: View {
    let selection: AppleCachedMapTileSelection
    let onRemove: (Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var quarantineMatchingHash = true

    var body: some View {
        NavigationStack {
            Form {
                Section("Remove Bad Tile?") {
                    LabeledContent("Tile", value: "z=\(selection.zoom) x=\(selection.x) y=\(selection.y)")
                    LabeledContent("Hash", value: String(selection.hash.prefix(12)) + "…")
                    Toggle("Also quarantine same-hash tiles", isOn: $quarantineMatchingHash)
                }
                Section {
                    Button("Remove", role: .destructive) {
                        onRemove(quarantineMatchingHash)
                    }
                }
            }
            .navigationTitle("Remove Bad Tile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private struct StreamAircraftPairingView: View {
    let streamID: String
    let tracks: [RidAircraftTrack]
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let selectedAircraftID: String?
    let onSelect: (String) -> Void
    let onUnpair: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pendingMismatchedTrack: RidAircraftTrack?

    var body: some View {
        NavigationStack {
            List {
                Section("Stream \(streamID)") {
                    if tracks.isEmpty {
                        ContentUnavailableView(
                            "No drones available",
                            systemImage: "airplane.slash",
                            description: Text("Wait for a Remote ID update, then try again.")
                        )
                    } else {
                        ForEach(orderedTracks) { track in
                            let isClosestMatch = track.aircraftID == closestMatchAircraftID
                            Button {
                                if pairingMatchesStream(track) {
                                    onSelect(track.aircraftID)
                                } else {
                                    pendingMismatchedTrack = track
                                }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(identityStore.identity(for: track.aircraftID)?.mappedID ?? track.aircraftID)
                                        Text(track.aircraftID)
                                            .font(.caption.monospaced())
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if isClosestMatch {
                                        Text("Closest match")
                                            .font(.caption)
                                            .fontWeight(.semibold)
                                            .foregroundStyle(Color.accentColor)
                                    }
                                    if selectedAircraftID == track.aircraftID {
                                        Image(systemName: "checkmark")
                                    }
                                }
                                .padding(.vertical, 6)
                                .overlay {
                                    if isClosestMatch {
                                        RoundedRectangle(cornerRadius: 8)
                                            .stroke(Color.accentColor, lineWidth: 2)
                                    }
                                }
                            }
                        }
                    }
                }
                if selectedAircraftID != nil {
                    Section {
                        Button("Unpair Stream", role: .destructive, action: onUnpair)
                    }
                }
                Section {
                    Text("Pairing lasts for the current app session. Use the stream designator configured in the aircraft profile whenever possible so RID2Caltopo can match it automatically.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Pair Stream")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .alert(
                "Stream Designator Mismatch",
                isPresented: Binding(
                    get: { pendingMismatchedTrack != nil },
                    set: { if !$0 { pendingMismatchedTrack = nil } }
                ),
                presenting: pendingMismatchedTrack
            ) { track in
                Button("Cancel", role: .cancel) { pendingMismatchedTrack = nil }
                Button("Pair Anyway") {
                    pendingMismatchedTrack = nil
                    onSelect(track.aircraftID)
                }
            } message: { track in
                Text(
                    "Stream “\(streamID)” does not match the drone designator “\(displayName(for: track))”. "
                    + "Change the controller's RTMP stream designator to “\(displayName(for: track))” "
                    + "so other tablets can match it automatically. Pair anyway only if you have "
                    + "positively identified this video source."
                )
            }
        }
    }

    private var closestMatchAircraftID: String? {
        OperationalStreamDesignatorMatch.closestCandidateID(
            streamDesignator: streamID,
            candidates: tracks.map { track in
                OperationalStreamDesignatorCandidate(
                    id: track.aircraftID,
                    designator: displayName(for: track)
                )
            }
        )
    }

    private var orderedTracks: [RidAircraftTrack] {
        let closestAircraftID = closestMatchAircraftID
        return tracks.sorted { left, right in
            let leftIsClosest = left.aircraftID == closestAircraftID
            let rightIsClosest = right.aircraftID == closestAircraftID
            if leftIsClosest != rightIsClosest { return leftIsClosest }
            return displayName(for: left).localizedCaseInsensitiveCompare(displayName(for: right))
                == .orderedAscending
        }
    }

    private func pairingMatchesStream(_ track: RidAircraftTrack) -> Bool {
        let stream = normalized(streamID)
        return stream == normalized(track.aircraftID)
            || stream == normalized(identityStore.identity(for: track.aircraftID)?.mappedID ?? "")
    }

    private func displayName(for track: RidAircraftTrack) -> String {
        identityStore.identity(for: track.aircraftID)?.mappedID ?? track.aircraftID
    }

    private func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }
}

private struct PendingClueSnapshot: Identifiable {
    let id = UUID()
    let snapshot: AppleVideoSnapshot
    let defaultAircraftID: String
    let gimbalAngleDegrees: Double
    let observation: RidObservation
    let altitudeDisplay: OperationalAircraftAltitudeDisplay?
    let heading: OperationalClueHeadingSelection
    let djiCameraTelemetry: AppleDJICameraTelemetry?

    init(
        snapshot: AppleVideoSnapshot,
        defaultAircraftID: String,
        gimbalAngleDegrees: Double,
        observation: RidObservation,
        altitudeDisplay: OperationalAircraftAltitudeDisplay?,
        heading: OperationalClueHeadingSelection,
        djiCameraTelemetry: AppleDJICameraTelemetry? = nil
    ) {
        self.snapshot = snapshot
        self.defaultAircraftID = defaultAircraftID
        self.gimbalAngleDegrees = gimbalAngleDegrees
        self.observation = observation
        self.altitudeDisplay = altitudeDisplay
        self.heading = heading
        self.djiCameraTelemetry = djiCameraTelemetry
    }
}

private struct ClueSubmissionView: View {
    private enum FocusedField: Hashable {
        case title
        case description
    }

    let pending: PendingClueSnapshot
    let model: RIDTrackViewModel
    let tracks: [RidAircraftTrack]
    let altitudeDisplay: [String: OperationalAircraftAltitudeDisplay]
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let coordinateDisplayFormat: OperationalCoordinateDisplayFormat
    let onSubmit: (AppleClueDraft, Data, Bool) -> Void
    let onCancel: () -> Void

    @State private var selectedAircraftID: String
    @State private var title: String
    @State private var description: String
    @State private var gimbalAngle: Double
    @State private var cameraHeadingDegrees: Double
    @State private var cameraHeadingSource: String
    @State private var terrainProjection: OperationalClueProjection?
    @State private var terrainProjectionPending = false
    @State private var submissionFeedback: String?
    @FocusState private var focusedField: FocusedField?

    init(
        pending: PendingClueSnapshot,
        model: RIDTrackViewModel,
        tracks: [RidAircraftTrack],
        altitudeDisplay: [String: OperationalAircraftAltitudeDisplay],
        identityStore: AppleDroneConfirmationStore,
        coordinateDisplayFormat: OperationalCoordinateDisplayFormat,
        onSubmit: @escaping (AppleClueDraft, Data, Bool) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.pending = pending
        self.model = model
        self.tracks = tracks
        self.altitudeDisplay = altitudeDisplay
        self.identityStore = identityStore
        self.coordinateDisplayFormat = coordinateDisplayFormat
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        _selectedAircraftID = State(initialValue: pending.defaultAircraftID)
        _gimbalAngle = State(initialValue: pending.gimbalAngleDegrees)
        _cameraHeadingDegrees = State(initialValue: pending.heading.degrees ?? 0)
        _cameraHeadingSource = State(initialValue: Self.headingSourceLabel(pending.heading))
        // Android deliberately opens on an empty, focused title so the
        // operator can immediately type the clue name without clearing a
        // generated value.
        _title = State(initialValue: "")
        _description = State(initialValue: Self.clueDescriptionTemplate(pending.snapshot.capturedAt))
    }

    private var track: RidAircraftTrack? { tracks.first { $0.aircraftID == selectedAircraftID } }
    private var usesCaptureTelemetry: Bool { selectedAircraftID == pending.defaultAircraftID }
    private var observation: RidObservation? {
        usesCaptureTelemetry ? pending.observation : track?.lastObservation
    }
    private var display: OperationalAircraftAltitudeDisplay? {
        usesCaptureTelemetry ? pending.altitudeDisplay : altitudeDisplay[selectedAircraftID]
    }
    private var heading: OperationalClueHeadingSelection {
        OperationalClueHeadingSelection(
            degrees: cameraHeadingDegrees,
            sourceLabel: cameraHeadingSource
        )
    }
    private var designator: String {
        identityStore.identity(for: selectedAircraftID)?.mappedID ?? selectedAircraftID
    }
    private var aglMeters: Double? {
        return display?.aglFeet.map { $0 * 0.3048 }
    }
    private var atoMeters: Double? { display?.atoFeet.map { $0 * 0.3048 } }
    private var projectionHeight: OperationalClueProjectionHeightSelection? {
        OperationalClueGeometry.selectedProjectionHeight(
            freshAGLMeters: display?.aglStale == true ? nil : aglMeters,
            atoMeters: atoMeters,
            validatedDJIRelativeUpMeters: usesCaptureTelemetry && atoMeters != nil
                ? pending.djiCameraTelemetry?.relativeUpMeters
                : nil
        )
    }
    private var flatProjection: OperationalClueProjection? {
        guard let observation, let projectionHeight else { return nil }
        return OperationalClueGeometry.project(
            droneLatitude: observation.latitude,
            droneLongitude: observation.longitude,
            droneAltitudeMeters: observation.altitudeMeters,
            headingDegrees: heading.degrees,
            aglMeters: projectionHeight.meters,
            gimbalAngleDegrees: gimbalAngle
        )
    }
    private var projection: OperationalClueProjection? { terrainProjection ?? flatProjection }
    private var projectionInput: ClueProjectionInput? {
        guard let observation, let projectionHeight else { return nil }
        return ClueProjectionInput(
            aircraftID: selectedAircraftID,
            latitude: observation.latitude,
            longitude: observation.longitude,
            altitudeMeters: observation.altitudeMeters,
            headingDegrees: heading.degrees,
            projectionHeightMeters: projectionHeight.meters,
            gimbalAngleDegrees: gimbalAngle
        )
    }
    private var clueDistanceFeet: Double? {
        guard let observation, let projection else { return nil }
        return CLLocation(
            latitude: observation.latitude,
            longitude: observation.longitude
        ).distance(from: CLLocation(
            latitude: projection.latitude,
            longitude: projection.longitude
        )) * 3.28084
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let image = UIImage(data: pending.snapshot.jpegData) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 240)
                            .frame(maxWidth: .infinity)
                    }
                }
                Section("Aircraft") {
                    Picker("Source aircraft", selection: $selectedAircraftID) {
                        ForEach(tracks) { track in
                            Text(identityStore.identity(for: track.aircraftID)?.mappedID ?? track.aircraftID)
                                .tag(track.aircraftID)
                        }
                    }
                    if let observation, let projection {
                        LabeledContent("Drone", value: coordinate(observation.latitude, observation.longitude))
                        LabeledContent("Clue", value: coordinate(projection.latitude, projection.longitude))
                        LabeledContent("Heading", value: headingMeasurement(heading.degrees))
                        LabeledContent("Heading source", value: heading.sourceLabel ?? "Unavailable")
                        LabeledContent("AGL", value: measurement(display?.aglFeet, suffix: display?.aglStale == true ? "? ft" : " ft"))
                        LabeledContent("ATO", value: measurement(display?.atoFeet, suffix: " ft"))
                        LabeledContent("Distance", value: measurement(clueDistanceFeet, suffix: " ft"))
                    }
                }
                Section("Camera projection") {
                    LabeledContent("Camera heading", value: headingMeasurement(cameraHeadingDegrees))
                    Slider(value: Binding(
                        get: { cameraHeadingDegrees },
                        set: { value in
                            cameraHeadingDegrees = RidHeading.normalized(value) ?? 0
                            cameraHeadingSource = "Operator adjusted"
                        }
                    ), in: 0 ... 359, step: 1)
                    LabeledContent("Gimbal angle", value: "\(Int(gimbalAngle.rounded()))°")
                    Slider(value: $gimbalAngle, in: -90 ... 90, step: 1)
                    Text("-90° is straight down; 0° is the horizon; positive angles look upward.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if terrainProjectionPending {
                        ProgressView("Intersecting camera sightline with DEM…")
                    } else if let terrainProjection, terrainProjection.terrainProjectionApplied {
                        Label(demProjectionLabel(terrainProjection), systemImage: "mountain.2")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else if terrainProjection != nil {
                        Label("Flat-ground estimate; no DEM intersection", systemImage: "exclamationmark.triangle")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else if projectionHeight == nil {
                        Label(
                            "Clue projection unavailable: no fresh AGL or relative altitude.",
                            systemImage: "exclamationmark.triangle.fill"
                        )
                        .font(.caption)
                        .foregroundStyle(.red)
                    }
                }
                Section("Report") {
                    TextField("Title", text: $title)
                        .focused($focusedField, equals: .title)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .description }
                        .onChange(of: title) {
                            if !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                submissionFeedback = nil
                            }
                        }
                    TextField("Description", text: $description, axis: .vertical)
                        .lineLimit(3 ... 8)
                        .focused($focusedField, equals: .description)
                    if let submissionFeedback {
                        Label(submissionFeedback, systemImage: "exclamationmark.circle.fill")
                            .font(.callout)
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("clue-submission-feedback")
                    }
                }
                Section {
                    Button("Local Marker Only", systemImage: "mappin.and.ellipse") {
                        submit(publish: false)
                    }
                    .disabled(projectionHeight == nil)
                    Button {
                        submit(publish: true)
                    } label: {
                        Label("Submit", systemImage: "icloud.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(projectionHeight == nil)
                } footer: {
                    Text("Submit saves the clue locally before starting its CalTopo upload.")
                }
            }
            .navigationTitle("Submit Clue")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
            }
            .onAppear {
                DispatchQueue.main.async {
                    focusedField = .title
                }
            }
            .onChange(of: selectedAircraftID) {
                let selection = usesCaptureTelemetry
                    ? pending.heading
                    : OperationalClueGeometry.selectedHeading(
                        cameraYawDegrees: nil,
                        streamHeadingDegrees: nil,
                        ridHeadingDegrees: observation?.headingDegrees,
                        derivedHeadingDegrees: track.flatMap {
                            OperationalMapGeometry.travelBearingDegrees(points: $0.points)
                        }
                )
                cameraHeadingDegrees = selection.degrees ?? 0
                cameraHeadingSource = Self.headingSourceLabel(selection)
            }
            .task(id: projectionInput) {
                terrainProjection = nil
                guard let input = projectionInput, let observation else { return }
                terrainProjectionPending = true
                let refined = await model.projectClueWithTerrain(
                    observation: observation,
                    headingDegrees: input.headingDegrees,
                    aglMeters: input.projectionHeightMeters,
                    gimbalAngleDegrees: input.gimbalAngleDegrees
                )
                guard !Task.isCancelled, projectionInput == input else { return }
                terrainProjection = refined
                terrainProjectionPending = false
                AppleLog.info(
                    "Clue",
                    String(
                        format: "DEM projection aircraft=%@ lat=%.6f lng=%.6f heading=%@ aglM=%@ gimbal=%.1f terrainApplied=%@ demSource=%@ demResolutionM=%@",
                        input.aircraftID,
                        refined.latitude,
                        refined.longitude,
                        input.headingDegrees.map { String(format: "%.1f", $0) } ?? "nil",
                        input.projectionHeightMeters.map { String(format: "%.1f", $0) } ?? "nil",
                        input.gimbalAngleDegrees,
                        refined.terrainProjectionApplied ? "true" : "false",
                        refined.demSource ?? "none",
                        refined.demResolutionMeters.map { String(format: "%.1f", $0) } ?? "unknown"
                    )
                )
            }
        }
    }

    private func submit(publish: Bool) {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        AppleLog.info(
            "Clue",
            "Clue form action publish=\(publish) aircraft=\(selectedAircraftID) titleLength=\(trimmedTitle.count)"
        )
        guard !trimmedTitle.isEmpty else {
            submissionFeedback = "Title required."
            focusedField = .title
            AppleLog.warning("Clue", "Clue form submission needs a title")
            return
        }
        guard let projectionHeight else {
            submissionFeedback = "Clue projection needs fresh AGL or a valid relative altitude. Wait for altitude telemetry and try again."
            AppleLog.warning(
                "Clue",
                "Clue form submission blocked because projection height is unavailable aircraft=\(selectedAircraftID)"
            )
            return
        }
        guard let observation, let projection else {
            submissionFeedback = "Aircraft telemetry is unavailable. Select an active aircraft and try again."
            AppleLog.error(
                "Clue",
                "Clue form submission blocked because aircraft telemetry is no longer available aircraft=\(selectedAircraftID)"
            )
            return
        }
        submissionFeedback = nil
        let clueAltitude = projection.altitudeMeters.map { String(format: "%.0f'", $0 * 3.28084) } ?? "N/A"
        let primaryPosition = OperationalCoordinateFormatter.format(
            latitude: projection.latitude,
            longitude: projection.longitude,
            as: coordinateDisplayFormat
        ).replacingOccurrences(of: "loc:", with: "")
        var summaryLines = [
            "Projected clue location:",
            "  Position (\(coordinateDisplayFormat.label)): \(primaryPosition) alt \(clueAltitude)"
        ]
        if coordinateDisplayFormat != .decimal {
            summaryLines.append(String(format: "  Decimal: %.6f, %.6f", projection.latitude, projection.longitude))
        }
        summaryLines += [
            "  Camera Azimuth: \(headingMeasurement(heading.degrees))",
            "  Heading source: \(heading.sourceLabel ?? "N/A")",
            "  Gimbal angle at capture: \(String(format: "%.1f°", gimbalAngle))",
            "  AGL: \(measurement(aglMeters.map { $0 * 3.28084 }, suffix: display?.aglStale == true ? "? ft" : " ft"))",
            "  Projection height: \(measurement(projectionHeight.meters * 3.28084, suffix: " ft")) (\(projectionHeight.sourceLabel))",
            clueDemSummary(projection),
            "  ATO: \(measurement(display?.atoFeet, suffix: " ft"))",
            "  Distance to clue: \(measurement(clueDistanceFeet, suffix: " ft"))"
        ]
        if let telemetry = pending.djiCameraTelemetry {
            summaryLines += [
                "",
                String(format: "DJI raw azimuth encoder: %.1f°", telemetry.rawAzimuthCandidateDegrees),
                String(
                    format: "DJI calibrated camera azimuth: %.1f°",
                    telemetry.cameraAzimuthDegrees ?? .nan
                )
            ]
            if let timestamp = telemetry.sourceTimestampMicroseconds {
                summaryLines.append("  Telemetry timestamp(us): \(timestamp)")
            }
            if let latitude = telemetry.latitudeDegrees,
               let longitude = telemetry.longitudeDegrees {
                summaryLines.append(
                    String(
                        format: "  DJI SEI aircraft position (used for clue geometry): %.7f, %.7f relative-up %.1f'",
                        latitude,
                        longitude,
                        (telemetry.relativeUpMeters ?? 0) * 3.28084
                    )
                )
            }
            if let latitude = telemetry.referenceLatitudeDegrees,
               let longitude = telemetry.referenceLongitudeDegrees {
                summaryLines.append(String(format: "  DJI SEI home/reference: %.7f, %.7f", latitude, longitude))
            }
        }
        let summary = summaryLines.joined(separator: "\n")
        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalDescription = trimmedDescription.isEmpty ? summary : trimmedDescription + "\n\n" + summary
        onSubmit(AppleClueDraft(
            capturedAt: pending.snapshot.capturedAt,
            aircraftID: selectedAircraftID,
            designator: designator,
            droneLatitude: observation.latitude,
            droneLongitude: observation.longitude,
            droneAltitudeMeters: observation.altitudeMeters,
            clueLatitude: projection.latitude,
            clueLongitude: projection.longitude,
            clueAltitudeMeters: projection.altitudeMeters,
            headingDegrees: heading.degrees,
            aglMeters: aglMeters,
            atoMeters: atoMeters,
            gimbalAngleDegrees: gimbalAngle,
            title: trimmedTitle,
            description: finalDescription
        ), pending.snapshot.jpegData, publish)
    }

    private func coordinate(_ latitude: Double, _ longitude: Double) -> String {
        let value = OperationalCoordinateFormatter.format(
            latitude: latitude,
            longitude: longitude,
            as: coordinateDisplayFormat
        ).replacingOccurrences(of: "loc:", with: "")
        return "\(value) (\(coordinateDisplayFormat.label))"
    }

    private func measurement(_ value: Double?, suffix: String) -> String {
        guard let value, value.isFinite else { return "--" }
        return String(format: "%.0f%@", value, suffix)
    }

    private func demProjectionLabel(_ projection: OperationalClueProjection) -> String {
        guard let resolution = projection.demResolutionMeters,
              resolution.isFinite, resolution > 0
        else { return "DEM terrain projection applied" }
        return String(format: "DEM terrain projection applied (%.0f m grid)", resolution)
    }

    private func clueDemSummary(_ projection: OperationalClueProjection) -> String {
        guard projection.terrainProjectionApplied else {
            return "  DEM used: none (flat-ground estimate)"
        }
        let sourceLabel: String
        if projection.demSource?.hasPrefix("usgs-geotiff-local-") == true {
            sourceLabel = "local USGS GeoTIFF"
        } else if projection.demSource == "usgs-epqs" || projection.demSource == nil {
            sourceLabel = "USGS elevation service"
        } else {
            sourceLabel = projection.demSource ?? "USGS elevation data"
        }
        let resolutionLabel: String
        if let resolution = projection.demResolutionMeters,
           resolution.isFinite, resolution > 0 {
            resolutionLabel = String(format: " (%.0f m grid)", resolution)
        } else {
            resolutionLabel = " (resolution not reported)"
        }
        return "  DEM used: \(sourceLabel)\(resolutionLabel)\(projection.demSampleStale ? ", cached" : "")"
    }

    private func headingMeasurement(_ value: Double?) -> String {
        guard let heading = RidHeading.roundedWholeDegrees(value) else { return "--" }
        return "\(heading)°"
    }

    private static func headingSourceLabel(_ selection: OperationalClueHeadingSelection) -> String {
        switch selection.sourceLabel {
        case "RID aircraft track":
            return "RID aircraft track"
        case nil:
            return "Operator entry required"
        case let label?:
            return label
        }
    }

    private static let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    private static func clueDescriptionTemplate(_ date: Date) -> String {
        "time: \(timestampFormatter.string(from: date))\nfound by: \nreported to IC: yes|no\n"
    }
}

private struct ClueProjectionInput: Hashable {
    let aircraftID: String
    let latitude: Double
    let longitude: Double
    let altitudeMeters: Double?
    let headingDegrees: Double?
    let projectionHeightMeters: Double?
    let gimbalAngleDegrees: Double
}

private struct ClueDetailView: View {
    let clue: OperationalClueRecord
    @ObservedObject var store: AppleClueStore
    let coordinateDisplayFormat: OperationalCoordinateDisplayFormat
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let image = UIImage(contentsOfFile: store.imageURL(for: clue).path) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 360)
                            .frame(maxWidth: .infinity)
                    }
                    ShareLink(item: store.imageURL(for: clue)) {
                        Label("Share Snapshot", systemImage: "square.and.arrow.up")
                    }
                }
                Section(clue.title) {
                    LabeledContent("Aircraft", value: clue.designator)
                    LabeledContent("Captured", value: clue.capturedAt.formatted(date: .abbreviated, time: .standard))
                    LabeledContent(
                        "Location",
                        value: OperationalCoordinateFormatter.format(
                            latitude: clue.clueLatitude,
                            longitude: clue.clueLongitude,
                            as: coordinateDisplayFormat
                        ).replacingOccurrences(of: "loc:", with: "") + " (\(coordinateDisplayFormat.label))"
                    )
                    LabeledContent("CalTopo", value: clue.uploadState.rawValue)
                    if let error = clue.lastUploadError { Text(error).foregroundStyle(.red) }
                    Text(clue.clueDescription)
                }
                if clue.uploadState == .failed || clue.uploadState == .pending {
                    Section { Button("Retry CalTopo Upload") { store.retry(clue.id) } }
                }
                Section {
                    Button("Delete Local Clue", role: .destructive) {
                        store.delete(clue.id)
                        onClose()
                    }
                }
            }
            .navigationTitle("Clue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done", action: onClose) } }
        }
    }
}

private struct MapVisibilityRow: Identifiable {
    enum Content {
        case folder(CaltopoArtifactFolder)
        case item(CaltopoArtifactItem)
    }

    let id: String
    let depth: Int
    let content: Content
}

private struct MapItemsVisibilityView: View {
    @ObservedObject var model: AppleMapArtifactModel
    let onZoomToItem: (CaltopoArtifactItem) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var expandedFolderIDs: Set<String> = []
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            List(rows) { row in
                switch row.content {
                case let .folder(folder): folderRow(folder, depth: row.depth)
                case let .item(item): itemRow(item, depth: row.depth)
                }
            }
            .overlay {
                if model.snapshot.folders.isEmpty {
                    ContentUnavailableView("No map items", systemImage: "folder", description: Text(model.status))
                }
            }
            .navigationTitle("Map Folders")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, prompt: "Folders and map items")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onAppear {
                if expandedFolderIDs.isEmpty {
                    expandedFolderIDs = Set(model.snapshot.folders.filter { $0.parentID == nil }.map(\.id))
                }
            }
        }
    }

    private var rows: [MapVisibilityRow] {
        let folders = model.snapshot.folders
        let folderIDs = Set(folders.map(\.id))
        let roots = folders.filter { $0.parentID == nil || !folderIDs.contains($0.parentID ?? "") }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        var result: [MapVisibilityRow] = []
        var visited: Set<String> = []
        for folder in roots { append(folder: folder, depth: 0, into: &result, visited: &visited) }
        for folder in folders where !visited.contains(folder.id) {
            append(folder: folder, depth: 0, into: &result, visited: &visited)
        }
        if searchText.isEmpty { return result }
        let query = searchText.localizedLowercase
        return result.filter { row in
            switch row.content {
            case let .folder(folder): folder.title.localizedLowercase.contains(query)
            case let .item(item): item.title.localizedLowercase.contains(query)
            }
        }
    }

    private func append(
        folder: CaltopoArtifactFolder,
        depth: Int,
        into rows: inout [MapVisibilityRow],
        visited: inout Set<String>
    ) {
        guard visited.insert(folder.id).inserted else { return }
        rows.append(.init(id: "folder:\(folder.id)", depth: depth, content: .folder(folder)))
        guard expandedFolderIDs.contains(folder.id) || !searchText.isEmpty else { return }
        guard model.isFolderEffectivelyVisible(folder) || !searchText.isEmpty else { return }
        let children = model.snapshot.folders.filter { $0.parentID == folder.id }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        for child in children { append(folder: child, depth: depth + 1, into: &rows, visited: &visited) }
        for item in model.snapshot.visibilityItems.filter({ $0.folderID == folder.id }) {
            rows.append(.init(id: "item:\(item.id)", depth: depth + 1, content: .item(item)))
        }
    }

    private func folderRow(_ folder: CaltopoArtifactFolder, depth: Int) -> some View {
        let items = model.snapshot.visibilityItems.filter { $0.folderID == folder.id }
        let hasChildren = !items.isEmpty || model.snapshot.folders.contains { $0.parentID == folder.id }
        return HStack(spacing: 10) {
            Button {
                if expandedFolderIDs.contains(folder.id) { expandedFolderIDs.remove(folder.id) }
                else { expandedFolderIDs.insert(folder.id) }
            } label: {
                Image(systemName: hasChildren ? (expandedFolderIDs.contains(folder.id) ? "chevron.down" : "chevron.right") : "minus")
                    .frame(width: 14)
            }
            .buttonStyle(.plain)
            Button { model.toggleFolder(folder) } label: {
                Label(folder.title, systemImage: model.isFolderEffectivelyVisible(folder) ? "checkmark.square.fill" : "square")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            if !items.isEmpty {
                Button {
                    let allVisible = items.allSatisfy(model.isItemEffectivelyVisible)
                    model.setItems(items, visible: !allVisible)
                } label: {
                    Image(systemName: items.allSatisfy(model.isItemEffectivelyVisible) ? "eye" : "eye.slash")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Toggle all items in \(folder.title)")
            }
        }
        .padding(.leading, CGFloat(depth) * 18)
        .fontWeight(.semibold)
    }

    private func itemRow(_ item: CaltopoArtifactItem, depth: Int) -> some View {
        let visible = model.isItemEffectivelyVisible(item)
        let canZoom = visible
            && item.className == "Assignment"
            && !model.snapshot.coordinates(forItemID: item.id).isEmpty
        return HStack(spacing: 8) {
            Button { model.toggleItem(item) } label: {
                Label(item.title, systemImage: visible ? "checkmark.square.fill" : "square")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            if canZoom {
                Button {
                    onZoomToItem(item)
                    dismiss()
                } label: {
                    Image(systemName: "magnifyingglass")
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Zoom to assignment \(item.title)")
            }
        }
        .padding(.leading, CGFloat(depth) * 18 + 24)
    }
}

private struct PilotDisplaySelection: Identifiable {
    let id: String
    let remoteID: String
    let displayName: String
    let pilotCallsign: String
}

private struct AircraftMapDisplay: Equatable {
    let title: String
    let preference: PilotDisplayPreference
    let showFullFlightTrack: Bool
}

private struct AppleSEIMapPoint: Equatable {
    let latitude: Double
    let longitude: Double
    let altitudeMeters: Double?
    let courseDegrees: Double?
    let receivedAt: Date
}

private struct AircraftMapPoint: Equatable {
    let latitude: Double
    let longitude: Double
    let altitudeMeters: Double?
    let headingDegrees: Double?
    let receivedAt: Date
}

private func aircraftTravelBearingDegrees(points: [AircraftMapPoint]) -> Double? {
    guard let latest = points.last else { return nil }
    for earlier in points.dropLast().reversed() {
        guard let relative = RidGeometry.relativePosition(
            fromLatitude: earlier.latitude,
            longitude: earlier.longitude,
            toLatitude: latest.latitude,
            longitude: latest.longitude
        ), relative.distanceMeters >= OperationalMapGeometry.minimumTravelBearingDisplacementMeters
        else { continue }
        return relative.bearingDegrees
    }
    return points.last?.headingDegrees
}

private struct StaticMapRenderState: Equatable {
    let clues: [OperationalClueRecord]
    let artifacts: CaltopoArtifactSnapshot
    let airspaceRecords: [OperationalFacilityMapRecord]
    let notamsEnabled: Bool
    let notamsVisible: Bool
    let notices: [OperationalNotam]
    let landRestrictionsEnabled: Bool
    let landRestrictionsVisible: Bool
    let landAreas: [OperationalLandArea]
    let inset: Bool
}

private struct AircraftTrackRenderInput: Equatable {
    let aircraftID: String
    let points: [AircraftMapPoint]

    init(track: RidAircraftTrack, seiPoints: [AppleSEIMapPoint]) {
        aircraftID = track.aircraftID
        points = track.points.map {
            AircraftMapPoint(
                latitude: $0.latitude,
                longitude: $0.longitude,
                altitudeMeters: $0.altitudeMeters,
                headingDegrees: $0.headingDegrees,
                receivedAt: $0.receivedAt
            )
        } + seiPoints.map {
            AircraftMapPoint(
                latitude: $0.latitude,
                longitude: $0.longitude,
                altitudeMeters: $0.altitudeMeters,
                headingDegrees: $0.courseDegrees,
                receivedAt: $0.receivedAt
            )
        }
    }
}

private struct AircraftMapRenderState: Equatable {
    let tracks: [AircraftTrackRenderInput]
    let display: [String: AircraftMapDisplay]
    let altitude: [String: OperationalAircraftAltitudeDisplay]
    let inset: Bool
    let predictiveHeadEnabled: Bool
    let focusedAircraftID: String?
    let followFocusedDrone: Bool
    let centerLatitude: Double
    let centerLongitude: Double
    let latitudeDelta: Double
    let longitudeDelta: Double
    let width: Double
    let height: Double
    let predictionSecond: Int?
}

private struct PilotDisplaySettingsView: View {
    let selection: PilotDisplaySelection
    let track: RidAircraftTrack?
    let altitudeDisplay: OperationalAircraftAltitudeDisplay?
    @Binding var followFocusedDrone: Bool
    let onCalibrateAltitude: () -> Void
    @ObservedObject var store: ApplePilotDisplayStore
    @Environment(\.dismiss) private var dismiss

    private let palette = [
        defaultActiveTrackColor, defaultArchiveTrackColor, "#E53935", "#FB8C00",
        "#FDD835", "#43A047", "#00ACC1", "#3949AB", "#8E24AA",
        "#6D4C41", "#FFFFFF", "#212121",
    ]

    private var preference: PilotDisplayPreference {
        store.preference(for: selection.pilotCallsign)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let observation = track?.lastObservation {
                    Section(selection.displayName) {
                        LabeledContent(
                            "Location",
                            value: String(format: "%.6f, %.6f", observation.latitude, observation.longitude)
                        )
                        LabeledContent("Altitude MSL", value: measurement(observation.altitudeMeters, scale: 3.28084, unit: "ft"))
                        LabeledContent("ATO", value: feet(altitudeDisplay?.atoFeet))
                        LabeledContent(
                            "AGL",
                            value: feet(altitudeDisplay?.aglFeet, stale: altitudeDisplay?.aglStale == true)
                        )
                        LabeledContent("Range from takeoff", value: feet(altitudeDisplay?.rangeFeet))
                        LabeledContent("Heading", value: measurement(observation.headingDegrees, scale: 1, unit: "°"))
                        LabeledContent("Speed", value: measurement(observation.speedMetersPerSecond, scale: 1.94384, unit: "kt"))
                        Button("Calibrate ATO + AGL at 50 ft", action: onCalibrateAltitude)
                            .disabled(observation.altitudeMeters == nil)
                    }
                }
                Section("Map") {
                    Toggle("Follow focused drone", isOn: $followFocusedDrone)
                }
                if !selection.pilotCallsign.isEmpty {
                    Section("Pilot Display: \(selection.pilotCallsign)") {
                        colorRow("Active", selected: preference.activeTrackColor) { color in
                            var updated = preference
                            updated.activeTrackColor = color
                            store.save(updated, for: selection.pilotCallsign)
                        }
                        colorRow("Archive", selected: preference.archiveTrackColor) { color in
                            var updated = preference
                            updated.archiveTrackColor = color
                            store.save(updated, for: selection.pilotCallsign)
                        }
                        Toggle("Bearing", isOn: Binding(
                            get: { preference.bearingEnabled },
                            set: { enabled in
                                var updated = preference
                                updated.bearingEnabled = enabled
                                store.save(updated, for: selection.pilotCallsign)
                            }
                        ))
                        Button("Reset", role: .destructive) { store.reset(selection.pilotCallsign) }
                    }
                }
            }
            .navigationTitle("Pilot Display")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    private func measurement(_ value: Double?, scale: Double, unit: String) -> String {
        guard let value, value.isFinite else { return "--" }
        return String(format: "%.0f %@", value * scale, unit)
    }

    private func feet(_ value: Double?, stale: Bool = false) -> String {
        guard let value, value.isFinite else { return "--" }
        return String(format: "%.0f%@ ft", value, stale ? "?" : "")
    }

    private func colorRow(
        _ title: String,
        selected: String,
        onSelect: @escaping (String) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Circle().fill(Color(uiColor: UIColor(hex: selected) ?? .systemBlue)).frame(width: 28, height: 28)
                Text(title)
                Spacer()
                Text(selected).foregroundStyle(.secondary).monospaced()
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 10) {
                ForEach(palette, id: \.self) { color in
                    Button { onSelect(color) } label: {
                        Circle()
                            .fill(Color(uiColor: UIColor(hex: color) ?? .systemBlue))
                            .frame(width: 36, height: 36)
                            .overlay(Circle().stroke(.primary, lineWidth: selected == color ? 3 : 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(title) \(color)")
                }
            }
        }
    }
}

private final class ViewportPreservingMKMapView: MKMapView {
    var onBoundsLayout: ((MKMapView) -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onBoundsLayout?(self)
    }
}

private struct OperationalMKMapView: UIViewRepresentable {
    let tracks: [RidAircraftTrack]
    let seiTrackPointsByAircraftID: [String: [AppleSEIMapPoint]]
    let aircraftDisplay: [String: AircraftMapDisplay]
    let altitudeDisplay: [String: OperationalAircraftAltitudeDisplay]
    let cameraFovByAircraftID: [String: CameraFovBoundaryBearings]
    let clues: [OperationalClueRecord]
    let artifacts: CaltopoArtifactSnapshot
    let airspaceState: OperationalAirspaceState
    let notamState: AppleNotamState
    let landRestrictionState: AppleLandRestrictionState
    let baseLayer: OperationalMapBaseLayer
    let showContours: Bool
    let offlineOnly: Bool
    let tileCacheRevision: Int
    let operatorCoordinate: CLLocationCoordinate2D?
    let operatorStatusLines: [String]
    @Binding var viewport: MKCoordinateRegion
    let viewportMemory: AppleMapViewportMemory
    let inset: Bool
    let predictiveHeadEnabled: Bool
    let focusedAircraftID: String?
    let followFocusedDrone: Bool
    let artifactZoomRequest: ArtifactZoomRequest?
    @Binding var operatorAdjustedViewport: Bool
    let onSelectClue: ([UUID]) -> Void
    let onSelectArtifact: (String, String?) -> Void
    let onSelectAircraft: (String) -> Void
    let onOperatorViewportGesture: () -> Void
    let onLongPressTile: (Int, Int, Int) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            viewport: $viewport,
            viewportMemory: viewportMemory,
            operatorAdjustedViewport: $operatorAdjustedViewport,
            onSelectClue: onSelectClue,
            onSelectArtifact: onSelectArtifact,
            onSelectAircraft: onSelectAircraft,
            onOperatorViewportGesture: onOperatorViewportGesture,
            onLongPressTile: onLongPressTile
        )
    }

    func makeUIView(context: Context) -> MKMapView {
        let map = ViewportPreservingMKMapView()
        map.delegate = context.coordinator
        map.showsCompass = false
        map.showsScale = !inset
        map.pointOfInterestFilter = .excludingAll
        map.setRegion(viewportMemory.region, animated: false)
        if !inset {
            let compass = MKCompassButton(mapView: map)
            compass.compassVisibility = .adaptive
            compass.translatesAutoresizingMaskIntoConstraints = false
            compass.accessibilityLabel = "Return map to north up"
            map.addSubview(compass)
            NSLayoutConstraint.activate([
                compass.trailingAnchor.constraint(equalTo: map.trailingAnchor, constant: -8),
                compass.topAnchor.constraint(equalTo: map.safeAreaLayoutGuide.topAnchor, constant: 58),
            ])
        }
        context.coordinator.installLongPress(on: map)
        context.coordinator.installArtifactTap(on: map)
        map.onBoundsLayout = { [weak coordinator = context.coordinator] map in
            coordinator?.restoreViewportBoundsIfNeeded(on: map)
        }
        return map
    }

    static func dismantleUIView(_ map: MKMapView, coordinator: Coordinator) {
        // Capture synchronously into reference storage so a full/PiP replacement
        // cannot outrun SwiftUI binding delivery. Do not write a Binding here:
        // doing so re-enters StoredLocation teardown and can trigger an
        // exclusivity trap when leaving Live View.
        coordinator.captureViewportForTransition(from: map)
        (map as? ViewportPreservingMKMapView)?.onBoundsLayout = nil
        map.delegate = nil
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.onSelectAircraft = onSelectAircraft
        context.coordinator.onSelectClue = onSelectClue
        context.coordinator.onSelectArtifact = onSelectArtifact
        context.coordinator.onOperatorViewportGesture = onOperatorViewportGesture
        context.coordinator.onLongPressTile = onLongPressTile
        map.showsUserLocation = false
        context.coordinator.updateOperatorLocation(
            on: map,
            coordinate: operatorCoordinate,
            inset: inset,
            statusLines: operatorStatusLines
        )
        let fallbackTrackCoordinates = tracks.flatMap { track -> [CLLocationCoordinate2D] in
            var coordinates: [CLLocationCoordinate2D] = []
            if let latitude = track.lastObservation.operatorLatitude,
               let longitude = track.lastObservation.operatorLongitude {
                let operatorCoordinate = CLLocationCoordinate2D(
                    latitude: latitude,
                    longitude: longitude
                )
                if CLLocationCoordinate2DIsValid(operatorCoordinate), latitude != 0, longitude != 0 {
                    coordinates.append(operatorCoordinate)
                }
            }
            let latestSEI = seiTrackPointsByAircraftID[track.aircraftID]?.last
            let aircraftCoordinate = CLLocationCoordinate2D(
                latitude: latestSEI?.latitude ?? track.lastObservation.latitude,
                longitude: latestSEI?.longitude ?? track.lastObservation.longitude
            )
            if CLLocationCoordinate2DIsValid(aircraftCoordinate) {
                coordinates.append(aircraftCoordinate)
            }
            return coordinates
        }
        let fallbackStartupCoordinates =
            fallbackTrackCoordinates
            + artifacts.points.map { $0.coordinate.clCoordinate }
            + artifacts.lines.flatMap { $0.coordinates.map(\.clCoordinate) }
            + artifacts.polygons.flatMap { $0.coordinates.map(\.clCoordinate) }
        context.coordinator.rescueInitialOperationalViewport(
            on: map,
            operatorCoordinate: operatorCoordinate,
            fallbackCoordinates: fallbackStartupCoordinates,
            allowed: !operatorAdjustedViewport
                && focusedAircraftID == nil
        )
        let shouldFollowOperator = operatorCoordinate != nil && !inset && !followFocusedDrone && !operatorAdjustedViewport
        if shouldFollowOperator {
            if let operatorCoordinate {
                context.coordinator.setCenterAndPersist(operatorCoordinate, on: map)
            }
        } else if map.userTrackingMode != .none {
            map.setUserTrackingMode(.none, animated: false)
        }
        context.coordinator.updateTiles(
            on: map,
            baseLayer: baseLayer,
            contours: showContours,
            offlineOnly: offlineOnly,
            revision: tileCacheRevision
        )
        context.coordinator.updateOperationalOverlays(
            on: map,
            tracks: tracks,
            seiTrackPointsByAircraftID: seiTrackPointsByAircraftID,
            aircraftDisplay: aircraftDisplay,
            altitudeDisplay: altitudeDisplay,
            cameraFovByAircraftID: cameraFovByAircraftID,
            clues: clues,
            artifacts: artifacts,
            airspaceState: airspaceState,
            notamState: notamState,
            landRestrictionState: landRestrictionState,
            inset: inset,
            predictiveHeadEnabled: predictiveHeadEnabled,
            focusedAircraftID: focusedAircraftID,
            followFocusedDrone: followFocusedDrone
        )
        if let artifactZoomRequest {
            context.coordinator.zoomToArtifact(artifactZoomRequest, on: map)
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        @Binding private var viewport: MKCoordinateRegion
        private let viewportMemory: AppleMapViewportMemory
        @Binding private var operatorAdjustedViewport: Bool
        private var tileFingerprint = ""
        private var staticRenderState: StaticMapRenderState?
        private var aircraftRenderState: AircraftMapRenderState?
        private var aircraftAnnotationsByRemoteID: [String: AircraftAnnotation] = [:]
        private var operatorCoordinate: CLLocationCoordinate2D?
        private var operatorCircle: MKCircle?
        private var operatorAnnotation: OperatorDeviceAnnotation?
        private var lastArtifactZoomRequestID: UUID?
        private enum InitialViewportSource {
            case none
            case fallbackOperationalData
            case operatorLocation
            case preservedTransition
        }
        private var initialViewportSource: InitialViewportSource
        private var updating = false
        private var currentInset = false
        private var currentFocusedAircraftID: String?
        private let pendingVisibleMapRect: MKMapRect?
        private var restoredViewportBounds = false
        private var regionChangeWasUserGesture = false
        var onSelectClue: ([UUID]) -> Void
        var onSelectArtifact: (String, String?) -> Void
        var onSelectAircraft: (String) -> Void
        var onOperatorViewportGesture: () -> Void
        var onLongPressTile: (Int, Int, Int) -> Void

        init(
            viewport: Binding<MKCoordinateRegion>,
            viewportMemory: AppleMapViewportMemory,
            operatorAdjustedViewport: Binding<Bool>,
            onSelectClue: @escaping ([UUID]) -> Void,
            onSelectArtifact: @escaping (String, String?) -> Void,
            onSelectAircraft: @escaping (String) -> Void,
            onOperatorViewportGesture: @escaping () -> Void,
            onLongPressTile: @escaping (Int, Int, Int) -> Void
        ) {
            _viewport = viewport
            self.viewportMemory = viewportMemory
            _operatorAdjustedViewport = operatorAdjustedViewport
            pendingVisibleMapRect = viewportMemory.hasOperationalViewport
                ? viewportMemory.visibleMapRect
                : nil
            initialViewportSource = pendingVisibleMapRect == nil ? .none : .preservedTransition
            self.onSelectClue = onSelectClue
            self.onSelectArtifact = onSelectArtifact
            self.onSelectAircraft = onSelectAircraft
            self.onOperatorViewportGesture = onOperatorViewportGesture
            self.onLongPressTile = onLongPressTile
        }

        func installLongPress(on map: MKMapView) {
            let gesture = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
            gesture.minimumPressDuration = 0.5
            map.addGestureRecognizer(gesture)
        }

        @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
            guard gesture.state == .began,
                  let map = gesture.view as? MKMapView,
                  map.bounds.width > 0,
                  map.visibleMapRect.width > 0
            else { return }
            let coordinate = map.convert(gesture.location(in: map), toCoordinateFrom: map)
            let zoom = OperationalVisibleMapTile.zoomLevel(
                worldMapWidth: MKMapSize.world.width,
                visibleMapWidth: map.visibleMapRect.width,
                viewportWidth: map.bounds.width
            )
            let tile = OperationalVisibleMapTile.tile(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude,
                zoom: zoom
            )
            onLongPressTile(tile.zoom, tile.x, tile.y)
        }

        func updateTiles(
            on map: MKMapView,
            baseLayer: OperationalMapBaseLayer,
            contours: Bool,
            offlineOnly: Bool,
            revision: Int
        ) {
            let fingerprint = OperationalMapTileLayerState.fingerprint(
                baseLayer: baseLayer,
                contours: contours,
                offlineOnly: offlineOnly,
                revision: revision
            )
            guard fingerprint != tileFingerprint else { return }
            tileFingerprint = fingerprint
            map.removeOverlays(map.overlays.filter { $0 is CachedMapTileOverlay })
            let base = CachedMapTileOverlay(baseLayer: baseLayer, offlineOnly: offlineOnly)
            base.canReplaceMapContent = true
            map.insertOverlay(base, at: 0, level: .aboveRoads)
            if contours {
                map.addOverlay(CachedMapTileOverlay(contoursOfflineOnly: offlineOnly), level: .aboveLabels)
            }
        }

        @MainActor
        func updateOperatorLocation(
            on map: MKMapView,
            coordinate: CLLocationCoordinate2D?,
            inset: Bool,
            statusLines: [String]
        ) {
            currentInset = inset
            guard let coordinate, CLLocationCoordinate2DIsValid(coordinate) else {
                if let operatorCircle { map.removeOverlay(operatorCircle) }
                if let operatorAnnotation { map.removeAnnotation(operatorAnnotation) }
                operatorCircle = nil
                operatorAnnotation = nil
                operatorCoordinate = nil
                return
            }

            let movementMeters = self.operatorCoordinate.map {
                CLLocation(latitude: $0.latitude, longitude: $0.longitude).distance(
                    from: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
                )
            } ?? .greatestFiniteMagnitude
            let insetChanged = operatorAnnotation?.inset != inset
            operatorAnnotation?.coordinate = coordinate
            operatorAnnotation?.statusLines = statusLines
            if let operatorAnnotation,
               let view = map.view(for: operatorAnnotation) as? MKMarkerAnnotationView {
                configureOperatorCallout(view, annotation: operatorAnnotation)
            }
            if movementMeters <= 5, !insetChanged, operatorCircle != nil, operatorAnnotation != nil {
                return
            }

            if let operatorCircle { map.removeOverlay(operatorCircle) }
            operatorCircle = MKCircle(
                center: coordinate,
                radius: OperationalFacilityMap.operatingRadiusStatuteMiles * 1_609.344
            )
            self.operatorCoordinate = coordinate
            if let operatorCircle { map.addOverlay(operatorCircle, level: .aboveLabels) }

            if operatorAnnotation == nil || insetChanged {
                if let operatorAnnotation { map.removeAnnotation(operatorAnnotation) }
                let annotation = OperatorDeviceAnnotation(
                    coordinate: coordinate,
                    inset: inset,
                    statusLines: statusLines
                )
                operatorAnnotation = annotation
                map.addAnnotation(annotation)
            }
        }

        func rescueInitialOperationalViewport(
            on map: MKMapView,
            operatorCoordinate: CLLocationCoordinate2D?,
            fallbackCoordinates: [CLLocationCoordinate2D],
            allowed: Bool
        ) {
            guard allowed,
                  map.bounds.width > 0,
                  map.bounds.height > 0
            else { return }

            // The operator's current position is the authoritative Live View
            // startup center. If Remote ID or CalTopo coordinates arrive first,
            // they may supply a temporary fallback, but a later location fix
            // gets one chance to replace it. A user gesture, focused drone, or
            // preserved full/PiP transition prevents this startup correction at
            // the call site or through the preserved source state.
            if let operatorCoordinate,
               CLLocationCoordinate2DIsValid(operatorCoordinate),
               initialViewportSource != .operatorLocation,
               initialViewportSource != .preservedTransition {
                initialViewportSource = .operatorLocation
                let region = MKCoordinateRegion(
                    center: operatorCoordinate,
                    span: MKCoordinateSpan(latitudeDelta: 0.08, longitudeDelta: 0.08)
                )
                map.setRegion(region, animated: false)
                viewport = region
                viewportMemory.region = region
                viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
                viewportMemory.hasOperationalViewport = true
                AppleLog.info(
                    "MapViewport",
                    "Initial center set from operator location latitude=\(String(format: "%.6f", operatorCoordinate.latitude)) " +
                        "longitude=\(String(format: "%.6f", operatorCoordinate.longitude))"
                )
                return
            }

            guard initialViewportSource == .none else { return }
            let valid = fallbackCoordinates.filter(CLLocationCoordinate2DIsValid)
            guard let first = valid.first else { return }
            initialViewportSource = .fallbackOperationalData
            let points = valid.map(MKMapPoint.init)
            var rect = points.dropFirst().reduce(
                MKMapRect(origin: points[0], size: MKMapSize(width: 0, height: 0))
            ) { partial, point in
                partial.union(MKMapRect(origin: point, size: MKMapSize(width: 0, height: 0)))
            }
            if points.count > 1, rect.width > 0 || rect.height > 0 {
                if rect.width == 0 { rect = rect.insetBy(dx: -max(1, rect.height * 0.05), dy: 0) }
                if rect.height == 0 { rect = rect.insetBy(dx: 0, dy: -max(1, rect.width * 0.05)) }
                map.setVisibleMapRect(
                    rect,
                    edgePadding: UIEdgeInsets(top: 48, left: 48, bottom: 48, right: 48),
                    animated: false
                )
                viewport = map.region
                viewportMemory.region = map.region
                viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
                viewportMemory.hasOperationalViewport = true
            } else {
                let span = MKCoordinateSpan(latitudeDelta: 0.08, longitudeDelta: 0.08)
                let region = MKCoordinateRegion(center: first, span: span)
                map.setRegion(region, animated: false)
                viewport = region
                viewportMemory.region = region
                viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
                viewportMemory.hasOperationalViewport = true
            }
            AppleLog.info("MapViewport", "Initial center set from available operational coordinates")
        }

        func updateOperationalOverlays(
            on map: MKMapView,
            tracks: [RidAircraftTrack],
            seiTrackPointsByAircraftID: [String: [AppleSEIMapPoint]],
            aircraftDisplay: [String: AircraftMapDisplay],
            altitudeDisplay: [String: OperationalAircraftAltitudeDisplay],
            cameraFovByAircraftID: [String: CameraFovBoundaryBearings],
            clues: [OperationalClueRecord],
            artifacts: CaltopoArtifactSnapshot,
            airspaceState: OperationalAirspaceState,
            notamState: AppleNotamState,
            landRestrictionState: AppleLandRestrictionState,
            inset: Bool,
            predictiveHeadEnabled: Bool,
            focusedAircraftID: String?,
            followFocusedDrone: Bool
        ) {
            updating = true
            currentInset = inset
            currentFocusedAircraftID = focusedAircraftID
            defer { updating = false }
            let nextStaticState = StaticMapRenderState(
                clues: clues,
                artifacts: artifacts,
                airspaceRecords: airspaceState.records,
                notamsEnabled: notamState.enabled,
                notamsVisible: notamState.visible,
                notices: notamState.notices,
                landRestrictionsEnabled: landRestrictionState.enabled,
                landRestrictionsVisible: landRestrictionState.visible,
                landAreas: landRestrictionState.areas,
                inset: inset
            )
            let staticChanged = nextStaticState != staticRenderState
            if staticChanged {
                staticRenderState = nextStaticState
                map.removeOverlays(map.overlays.filter { overlay in
                    guard !(overlay is CachedMapTileOverlay) else { return false }
                    return (overlay as? StyledPolyline)?.layer == .staticMap
                        || (overlay as? StyledPolygon)?.layer == .staticMap
                })
                map.removeAnnotations(map.annotations.filter { annotation in
                    (annotation as? MapLayerAnnotation)?.mapLayer == .staticMap
                })
            }

            let now = Date()
            let region = map.region
            let renderInputs = tracks.map { track in
                AircraftTrackRenderInput(
                    track: track,
                    seiPoints: seiTrackPointsByAircraftID[track.aircraftID] ?? []
                )
            }
            let renderInputByAircraftID = Dictionary(
                uniqueKeysWithValues: renderInputs.map { ($0.aircraftID, $0) }
            )
            // Camera FOV is intentionally excluded from this state. SEI changes
            // several times per second and is updated on the existing annotation
            // below so the aircraft icon and labels do not flash.
            let nextAircraftState = AircraftMapRenderState(
                tracks: renderInputs,
                display: aircraftDisplay,
                altitude: altitudeDisplay,
                inset: inset,
                predictiveHeadEnabled: predictiveHeadEnabled,
                focusedAircraftID: focusedAircraftID,
                followFocusedDrone: followFocusedDrone,
                centerLatitude: region.center.latitude,
                centerLongitude: region.center.longitude,
                latitudeDelta: region.span.latitudeDelta,
                longitudeDelta: region.span.longitudeDelta,
                width: map.bounds.width,
                height: map.bounds.height,
                predictionSecond: predictiveHeadEnabled ? Int(now.timeIntervalSinceReferenceDate) : nil
            )
            let aircraftChanged = nextAircraftState != aircraftRenderState
            if aircraftChanged {
                aircraftRenderState = nextAircraftState
                map.removeOverlays(map.overlays.filter { overlay in
                    (overlay as? StyledPolyline)?.layer == .aircraft
                        || (overlay as? StyledPolygon)?.layer == .aircraft
                })
            }

            if aircraftChanged {
            let renderCoordinates = Dictionary(uniqueKeysWithValues: tracks.compactMap { track -> (String, CLLocationCoordinate2D)? in
                guard let points = renderInputByAircraftID[track.aircraftID]?.points,
                      let latest = points.last else { return nil }
                let actual = MapCoordinate(latitude: latest.latitude, longitude: latest.longitude)
                let usingSEI = !(seiTrackPointsByAircraftID[track.aircraftID]?.isEmpty ?? true)
                let predicted = predictiveHeadEnabled && !usingSEI && points.count >= 2
                    ? OperationalAircraftDisplay.predictedCoordinate(
                        previous: MapCoordinate(
                            latitude: points[points.count - 2].latitude,
                            longitude: points[points.count - 2].longitude
                        ),
                        previousTime: points[points.count - 2].receivedAt,
                        current: actual,
                        currentTime: latest.receivedAt,
                        now: now
                    )
                    : nil
                return (track.aircraftID, (predicted ?? actual).clCoordinate)
            })
            let statusLabels = Dictionary(uniqueKeysWithValues: tracks.map { track in
                let altitude = altitudeDisplay[track.aircraftID]
                return (
                    track.aircraftID,
                    OperationalAircraftDisplay.statusLabel(
                        atoFeet: altitude?.atoFeet,
                        aglFeet: altitude?.aglFeet,
                        aglStale: altitude?.aglStale == true,
                        rangeFeet: altitude?.rangeFeet,
                        headingDegrees: renderInputByAircraftID[track.aircraftID]?.points.last?.headingDegrees
                    )
                )
            })
            let labelLayouts: [String: MapAircraftLabelLayout]
            if inset {
                labelLayouts = [:]
            } else {
                let nameFont = UIFont.boldSystemFont(ofSize: 16)
                let statusFont = UIFont.boldSystemFont(ofSize: 13)
                let inputs = tracks.compactMap { track -> MapAircraftLabelInput? in
                    guard let coordinate = renderCoordinates[track.aircraftID] else { return nil }
                    let anchor = map.convert(coordinate, toPointTo: map)
                    let title = aircraftDisplay[track.aircraftID]?.title ?? track.aircraftID
                    let status = statusLabels[track.aircraftID] ?? ""
                    let nameSize = (title as NSString).size(withAttributes: [.font: nameFont])
                    let statusSize = (status as NSString).size(withAttributes: [.font: statusFont])
                    return MapAircraftLabelInput(
                        id: track.aircraftID,
                        anchor: MapScreenPoint(x: anchor.x, y: anchor.y),
                        nameWidth: nameSize.width + 12,
                        nameHeight: nameSize.height + 6,
                        statusWidth: statusSize.width + 10,
                        statusHeight: statusSize.height + 5
                    )
                }
                labelLayouts = Dictionary(uniqueKeysWithValues: OperationalAircraftDisplay.layoutLabels(
                    inputs,
                    viewportWidth: map.bounds.width,
                    viewportHeight: map.bounds.height
                ).map { ($0.id, $0) })
            }
            var renderedAircraftIDs = Set<String>()
            for (index, track) in tracks.enumerated() {
                let display = aircraftDisplay[track.aircraftID]
                    ?? AircraftMapDisplay(title: track.aircraftID, preference: PilotDisplayPreference(), showFullFlightTrack: false)
                let activeColor = UIColor(hex: display.preference.activeTrackColor) ?? .systemBlue
                let archiveColor = UIColor(hex: display.preference.archiveTrackColor) ?? .systemPink
                let iconColor: UIColor = {
                    guard let aglFeet = altitudeDisplay[track.aircraftID]?.aglFeet else { return activeColor }
                    if aglFeet >= 200 { return UIColor(hex: "#D32F2F") ?? .systemRed }
                    if aglFeet >= 180 { return UIColor(hex: "#FBC02D") ?? .systemYellow }
                    return activeColor
                }()
                let renderedPoints = renderInputByAircraftID[track.aircraftID]?.points ?? []
                let coordinates = renderedPoints.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
                if display.showFullFlightTrack, coordinates.count > 1 {
                    map.addOverlay(
                        StyledPolyline(
                            coordinates: coordinates,
                            count: coordinates.count,
                            color: archiveColor,
                            width: inset ? 1 : 2,
                            layer: .aircraft
                        ),
                        level: .aboveLabels
                    )
                }
                let cutoff = Date().addingTimeInterval(-30)
                let firstRecentIndex = renderedPoints.firstIndex { $0.receivedAt >= cutoff }
                let activeStartIndex = max((firstRecentIndex ?? max(renderedPoints.count - 1, 0)) - 1, 0)
                let activeCoordinates = Array(coordinates.dropFirst(activeStartIndex))
                if activeCoordinates.count > 1 {
                    map.addOverlay(
                        StyledPolyline(
                            coordinates: activeCoordinates,
                            count: activeCoordinates.count,
                            color: activeColor,
                            width: inset ? 2 : 4,
                            layer: .aircraft
                        ),
                        level: .aboveLabels
                    )
                }
                if !renderedPoints.isEmpty,
                   let aircraftCoordinate = renderCoordinates[track.aircraftID] {
                    let travelBearingDegrees = aircraftTravelBearingDegrees(points: renderedPoints)
                    if display.preference.bearingEnabled,
                       let end = bearingEndpoint(on: map, from: aircraftCoordinate, headingDegrees: travelBearingDegrees) {
                        map.addOverlay(
                            StyledPolyline(
                                coordinates: [aircraftCoordinate, end],
                                count: 2,
                                color: activeColor,
                                width: inset ? 1 : 2,
                                layer: .aircraft
                            ),
                            level: .aboveLabels
                        )
                    }
                    let anchorPoint = map.convert(aircraftCoordinate, toPointTo: map)
                    let annotation = aircraftAnnotationsByRemoteID[track.aircraftID]
                        ?? AircraftAnnotation(remoteID: track.aircraftID)
                    annotation.update(
                        coordinate: aircraftCoordinate,
                        title: display.title,
                        heading: travelBearingDegrees,
                        cameraFov: cameraFovByAircraftID[track.aircraftID],
                        color: iconColor,
                        inset: inset,
                        labelSide: index.isMultiple(of: 2) ? -1 : 1,
                        statusText: statusLabels[track.aircraftID] ?? "",
                        labelLayout: labelLayouts[track.aircraftID],
                        anchorScreen: MapScreenPoint(x: anchorPoint.x, y: anchorPoint.y),
                        focused: focusedAircraftID == track.aircraftID
                    )
                    if aircraftAnnotationsByRemoteID[track.aircraftID] == nil {
                        aircraftAnnotationsByRemoteID[track.aircraftID] = annotation
                        map.addAnnotation(annotation)
                    } else if let view = map.view(for: annotation) as? AircraftAnnotationView {
                        view.configure(annotation)
                    }
                    renderedAircraftIDs.insert(track.aircraftID)
                }
            }
            let retiredAircraftIDs = Set(aircraftAnnotationsByRemoteID.keys)
                .subtracting(renderedAircraftIDs)
            for remoteID in retiredAircraftIDs {
                guard let annotation = aircraftAnnotationsByRemoteID.removeValue(forKey: remoteID) else {
                    continue
                }
                map.removeAnnotation(annotation)
            }
            if followFocusedDrone,
               let focusedAircraftID,
               let coordinate = renderCoordinates[focusedAircraftID] {
                setCenterAndPersist(coordinate, on: map)
            }
            }

            updateCameraFov(
                on: map,
                cameraFovByAircraftID: cameraFovByAircraftID
            )

            if staticChanged {
            for artifact in artifacts.points {
                map.addAnnotation(ArtifactAnnotation(
                    coordinate: artifact.coordinate.clCoordinate,
                    title: artifact.title,
                    description: artifact.description,
                    symbol: artifact.symbol,
                    color: UIColor(hex: artifact.colorHex) ?? ArtifactAnnotation.defaultColor(for: artifact.symbol),
                    colorHex: artifact.colorHex
                ))
            }
            for artifact in artifacts.lines {
                let coordinates = artifact.coordinates.map(\.clCoordinate)
                map.addOverlay(
                    StyledPolyline(
                        coordinates: coordinates,
                        count: coordinates.count,
                        color: UIColor(hex: artifact.colorHex) ?? .systemOrange,
                        width: artifact.width,
                        artifactTitle: artifact.title,
                        artifactDescription: artifact.description
                    ),
                    level: .aboveLabels
                )
            }
            for artifact in artifacts.polygons {
                let coordinates = artifact.coordinates.map(\.clCoordinate)
                map.addOverlay(
                    StyledPolygon(
                        coordinates: coordinates,
                        count: coordinates.count,
                        stroke: UIColor(hex: artifact.strokeHex) ?? .systemOrange,
                        fill: UIColor(hex: artifact.fillHex) ?? .systemOrange.withAlphaComponent(0.2),
                        width: artifact.width,
                        artifactTitle: artifact.title,
                        artifactDescription: artifact.description
                    ),
                    level: .aboveLabels
                )
            }
            for record in airspaceState.records {
                addFacilityMapRecord(record, to: map)
            }
            if notamState.enabled, notamState.visible {
                for notice in notamState.notices {
                    addNotam(notice, to: map)
                }
            }
            if landRestrictionState.enabled, landRestrictionState.visible {
                for area in landRestrictionState.areas {
                    addLandRestriction(area, to: map)
                }
            }
            for clue in clues {
                map.addAnnotation(ClueAnnotation(clue: clue))
            }
            }
        }

        private func updateCameraFov(
            on map: MKMapView,
            cameraFovByAircraftID: [String: CameraFovBoundaryBearings]
        ) {
            for annotation in map.annotations.compactMap({ $0 as? AircraftAnnotation }) {
                let next = cameraFovByAircraftID[annotation.remoteID]
                guard annotation.cameraFov != next else { continue }
                annotation.cameraFov = next
                (map.view(for: annotation) as? AircraftAnnotationView)?
                    .updateCameraFov(next)
            }
        }

        private func addFacilityMapRecord(_ record: OperationalFacilityMapRecord, to map: MKMapView) {
            let stroke: UIColor = record.ceilingFeet == 0 ? .systemRed : .systemOrange
            let fill = stroke.withAlphaComponent(record.ceilingFeet == 0 ? 0.22 : 0.12)
            for ring in record.rings {
                let coordinates = ring.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
                guard coordinates.count >= 3 else { continue }
                map.addOverlay(
                    StyledPolygon(
                        coordinates: coordinates,
                        count: coordinates.count,
                        stroke: stroke,
                        fill: fill,
                        width: record.ceilingFeet == 0 ? 4 : 2
                    ),
                    level: .aboveLabels
                )
            }
        }

        private func addNotam(_ notice: OperationalNotam, to map: MKMapView) {
            let style: (UIColor, UIColor) = switch notice.severity {
            case .danger: (.systemRed, UIColor.systemRed.withAlphaComponent(0.2))
            case .caution: (.systemOrange, UIColor.systemOrange.withAlphaComponent(0.2))
            case .normal: (.systemGreen, UIColor.systemGreen.withAlphaComponent(0.2))
            case .neutral: (.systemGray, UIColor.systemGray.withAlphaComponent(0.15))
            }
            func add(_ geometry: OperationalNotamGeometry) {
                switch geometry {
                case let .point(coordinate):
                    map.addAnnotation(NotamAnnotation(notice: notice, coordinate: coordinate.clCoordinate))
                case let .line(values):
                    let coordinates = values.map(\.clCoordinate)
                    if coordinates.count >= 2 {
                        map.addOverlay(StyledPolyline(coordinates: coordinates, count: coordinates.count, color: style.0, width: 5), level: .aboveLabels)
                    }
                case let .polygon(rings):
                    guard let outer = rings.first else { return }
                    let coordinates = outer.map(\.clCoordinate)
                    if coordinates.count >= 3 {
                        map.addOverlay(StyledPolygon(coordinates: coordinates, count: coordinates.count, stroke: style.0, fill: style.1, width: 4), level: .aboveLabels)
                    }
                case let .collection(values): values.forEach(add)
                }
            }
            notice.geometries.forEach(add)
        }

        private func addLandRestriction(_ area: OperationalLandArea, to map: MKMapView) {
            let stroke: UIColor = switch area.agency {
            case .nationalParkService: .systemBrown
            case .fishAndWildlifeService: .systemPurple
            case .forestService: .systemGreen
            case .coloradoParksAndWildlife: .systemTeal
            }
            let fill = stroke.withAlphaComponent(area.containsOperator ? 0.22 : 0.12)
            for polygon in area.polygons {
                guard let outer = polygon.first else { continue }
                let coordinates = outer.map(\.clCoordinate)
                guard coordinates.count >= 3 else { continue }
                map.addOverlay(
                    StyledPolygon(
                        coordinates: coordinates,
                        count: coordinates.count,
                        stroke: stroke,
                        fill: fill,
                        width: area.containsOperator ? 4 : 2
                    ),
                    level: .aboveLabels
                )
            }
        }

        private func bearingEndpoint(
            on map: MKMapView,
            from coordinate: CLLocationCoordinate2D,
            headingDegrees: Double?
        ) -> CLLocationCoordinate2D? {
            let start = map.convert(coordinate, toPointTo: map)
            guard let end = OperationalMapGeometry.bearingLineToViewportEdge(
                start: MapScreenPoint(x: start.x, y: start.y),
                headingDegrees: headingDegrees,
                viewportWidth: map.bounds.width,
                viewportHeight: map.bounds.height
            ) else { return nil }
            let coordinate = map.convert(CGPoint(x: end.x, y: end.y), toCoordinateFrom: map)
            guard CLLocationCoordinate2DIsValid(coordinate) else { return nil }
            return coordinate
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            guard !updating else { return }
            persistViewport(from: mapView)
            regionChangeWasUserGesture = false
        }

        func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
            guard !updating, hasActiveViewportGesture(in: mapView) else { return }
            releaseFocusedAircraftForOperatorGesture()
            regionChangeWasUserGesture = true
            persistViewport(from: mapView)
        }

        func setCenterAndPersist(_ coordinate: CLLocationCoordinate2D, on map: MKMapView) {
            guard CLLocationCoordinate2DIsValid(coordinate) else { return }
            updating = true
            map.setCenter(coordinate, animated: false)
            updating = false
            viewport.center = coordinate
            viewportMemory.region.center = coordinate
            viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
            viewportMemory.hasOperationalViewport = true
        }

        func zoomToArtifact(_ request: ArtifactZoomRequest, on map: MKMapView) {
            guard lastArtifactZoomRequestID != request.id else { return }
            let coordinates = request.coordinates.map(\.clCoordinate).filter(CLLocationCoordinate2DIsValid)
            guard let first = coordinates.first else { return }
            lastArtifactZoomRequestID = request.id
            updating = true
            if coordinates.count == 1 {
                map.setRegion(
                    MKCoordinateRegion(
                        center: first,
                        span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
                    ),
                    animated: true
                )
            } else {
                let points = coordinates.map(MKMapPoint.init)
                var rect = points.dropFirst().reduce(
                    MKMapRect(origin: points[0], size: MKMapSize(width: 0, height: 0))
                ) { partial, point in
                    partial.union(MKMapRect(origin: point, size: MKMapSize(width: 0, height: 0)))
                }
                let minimumSpan = 1_000.0
                if rect.width < minimumSpan {
                    rect = rect.insetBy(dx: -(minimumSpan - rect.width) / 2, dy: 0)
                }
                if rect.height < minimumSpan {
                    rect = rect.insetBy(dx: 0, dy: -(minimumSpan - rect.height) / 2)
                }
                map.setVisibleMapRect(
                    rect,
                    edgePadding: UIEdgeInsets(top: 48, left: 48, bottom: 48, right: 48),
                    animated: true
                )
            }
            updating = false
            viewport = map.region
            viewportMemory.region = map.region
            viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
            viewportMemory.hasOperationalViewport = true
            AppleLog.info("MapViewport", "Zoomed to assignment '\(request.title)' points=\(coordinates.count)")
        }

        func persistViewport(from map: MKMapView) {
            guard restoredViewportBounds,
                  map.bounds.width >= 32,
                  map.bounds.height >= 32
            else { return }
            let region = map.region
            guard CLLocationCoordinate2DIsValid(region.center),
                  region.span.latitudeDelta.isFinite,
                  region.span.longitudeDelta.isFinite,
                  region.span.latitudeDelta > 0,
                  region.span.longitudeDelta > 0
            else { return }
            viewport = region
            viewportMemory.region = region
            viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
        }

        func captureViewportForTransition(from map: MKMapView) {
            guard map.bounds.width >= 32,
                  map.bounds.height >= 32
            else { return }
            let region = map.region
            guard CLLocationCoordinate2DIsValid(region.center),
                  region.span.latitudeDelta.isFinite,
                  region.span.longitudeDelta.isFinite,
                  region.span.latitudeDelta > 0,
                  region.span.longitudeDelta > 0
            else { return }
            viewportMemory.region = region
            viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
            if operatorAdjustedViewport {
                viewportMemory.hasOperationalViewport = true
            }
        }

        func restoreViewportBoundsIfNeeded(on map: MKMapView) {
            guard !restoredViewportBounds,
                  map.bounds.width >= 32,
                  map.bounds.height >= 32
            else { return }
            guard let rect = pendingVisibleMapRect,
                  rect.width.isFinite,
                  rect.height.isFinite,
                  rect.width > 0,
                  rect.height > 0
            else {
                restoredViewportBounds = true
                persistViewport(from: map)
                return
            }
            restoredViewportBounds = true
            updating = true
            map.setVisibleMapRect(rect, animated: false)
            updating = false
            persistViewport(from: map)
        }

        private func validVisibleMapRect(from map: MKMapView) -> MKMapRect? {
            let rect = map.visibleMapRect
            guard rect.width.isFinite,
                  rect.height.isFinite,
                  rect.width > 0,
                  rect.height > 0
            else { return nil }
            return rect
        }

        func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
            let userGesture = hasActiveViewportGesture(in: mapView)
            regionChangeWasUserGesture = userGesture
            guard userGesture else { return }
            releaseFocusedAircraftForOperatorGesture()
            operatorAdjustedViewport = true
            viewportMemory.hasOperationalViewport = true
        }

        private func releaseFocusedAircraftForOperatorGesture() {
            guard OperationalMapFocusPolicy.shouldReleaseFocus(
                hasFocusedAircraft: currentFocusedAircraftID != nil,
                isOperatorGesture: true
            ) else { return }
            currentFocusedAircraftID = nil
            onOperatorViewportGesture()
        }

        private func hasActiveViewportGesture(in view: UIView) -> Bool {
            if view.gestureRecognizers?.contains(where: { gesture in
                switch gesture {
                case is UIPanGestureRecognizer, is UIPinchGestureRecognizer:
                    return gesture.state == .began || gesture.state == .changed
                case let tap as UITapGestureRecognizer:
                    return tap.numberOfTapsRequired >= 2 && gesture.state == .ended
                default:
                    return false
                }
            }) == true {
                return true
            }
            return view.subviews.contains(where: hasActiveViewportGesture)
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: tiles) }
            if let circle = overlay as? MKCircle, circle === operatorCircle {
                let renderer = MKCircleRenderer(circle: circle)
                renderer.strokeColor = .systemBlue
                renderer.fillColor = UIColor.systemBlue.withAlphaComponent(0.07)
                renderer.lineWidth = currentInset ? 1 : 2
                return renderer
            }
            if let line = overlay as? StyledPolyline {
                let renderer = MKPolylineRenderer(polyline: line)
                renderer.strokeColor = line.color
                renderer.lineWidth = line.width
                return renderer
            }
            if let polygon = overlay as? StyledPolygon {
                let renderer = MKPolygonRenderer(polygon: polygon)
                renderer.strokeColor = polygon.stroke
                renderer.fillColor = polygon.fill
                renderer.lineWidth = polygon.width
                return renderer
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if annotation is MKUserLocation { return nil }
            if let operatorDevice = annotation as? OperatorDeviceAnnotation {
                let identifier = "operator-device"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: operatorDevice, reuseIdentifier: identifier)
                view.annotation = operatorDevice
                view.markerTintColor = .systemBlue
                view.glyphTintColor = .white
                view.glyphImage = UIImage(systemName: "antenna.radiowaves.left.and.right")
                view.canShowCallout = true
                // Match Android: keep the tablet marker icon-only until the
                // operator selects it, then reveal the device name in the
                // standard MapKit callout.
                view.titleVisibility = .hidden
                view.subtitleVisibility = .hidden
                view.displayPriority = .required
                view.accessibilityLabel = operatorDevice.title
                configureOperatorCallout(view, annotation: operatorDevice)
                return view
            }
            if let aircraft = annotation as? AircraftAnnotation {
                let identifier = "aircraft"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? AircraftAnnotationView)
                    ?? AircraftAnnotationView(annotation: aircraft, reuseIdentifier: identifier)
                view.configure(aircraft)
                return view
            }
            if let clue = annotation as? ClueAnnotation {
                let identifier = "local-clue"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: clue, reuseIdentifier: identifier)
                view.annotation = clue
                view.markerTintColor = clue.published ? UIColor.systemGreen : UIColor.systemPink
                view.glyphImage = UIImage(systemName: "camera.fill")
                view.canShowCallout = true
                return view
            }
            if let notam = annotation as? NotamAnnotation {
                let identifier = "notam"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: notam, reuseIdentifier: identifier)
                view.annotation = notam
                view.markerTintColor = notam.color
                view.glyphImage = UIImage(systemName: "exclamationmark.triangle.fill")
                view.canShowCallout = true
                return view
            }
            guard let artifact = annotation as? ArtifactAnnotation else { return nil }
            let identifier = "artifact"
            let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? ArtifactAnnotationView)
                ?? ArtifactAnnotationView(annotation: artifact, reuseIdentifier: identifier)
            view.configure(artifact)
            return view
        }

        private func configureOperatorCallout(
            _ view: MKMarkerAnnotationView,
            annotation: OperatorDeviceAnnotation
        ) {
            let stack = UIStackView()
            stack.axis = .vertical
            stack.alignment = .leading
            stack.spacing = 2
            for line in annotation.statusLines {
                let label = UILabel()
                label.font = .preferredFont(forTextStyle: .footnote)
                label.textColor = .label
                label.numberOfLines = 0
                label.text = line
                stack.addArrangedSubview(label)
            }
            stack.widthAnchor.constraint(lessThanOrEqualToConstant: 320).isActive = true
            view.detailCalloutAccessoryView = stack
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let clue = view.annotation as? ClueAnnotation {
                let tappedPoint = mapView.convert(clue.coordinate, toPointTo: mapView)
                let candidates = mapView.annotations
                    .compactMap { $0 as? ClueAnnotation }
                    .compactMap { candidate -> (clue: ClueAnnotation, distance: CGFloat)? in
                        let point = mapView.convert(candidate.coordinate, toPointTo: mapView)
                        let distance = hypot(point.x - tappedPoint.x, point.y - tappedPoint.y)
                        return distance <= 44 ? (candidate, distance) : nil
                    }
                    .sorted { lhs, rhs in
                        if lhs.distance != rhs.distance { return lhs.distance < rhs.distance }
                        return (lhs.clue.title ?? "").localizedCaseInsensitiveCompare(
                            rhs.clue.title ?? ""
                        ) == .orderedAscending
                    }
                    .map { $0.clue.clueID }
                mapView.deselectAnnotation(clue, animated: false)
                onSelectClue(candidates.isEmpty ? [clue.clueID] : candidates)
                return
            }
            if let artifact = view.annotation as? ArtifactAnnotation {
                guard !currentInset else { return }
                onSelectArtifact(artifact.title ?? "Map item", artifact.subtitle ?? nil)
                return
            }
            guard let aircraft = view.annotation as? AircraftAnnotation else { return }
            onSelectAircraft(aircraft.remoteID)
        }

        func installArtifactTap(on map: MKMapView) {
            let tap = UITapGestureRecognizer(target: self, action: #selector(handleArtifactTap(_:)))
            tap.cancelsTouchesInView = false
            tap.delegate = self
            map.addGestureRecognizer(tap)
        }

        @objc private func handleArtifactTap(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended,
                  !currentInset,
                  let map = gesture.view as? MKMapView
            else { return }
            let location = gesture.location(in: map)
            if map.annotations.contains(where: { annotation in
                guard !(annotation is MKUserLocation),
                      let view = map.view(for: annotation),
                      !view.isHidden
                else { return false }
                return view.frame.insetBy(dx: -8, dy: -8).contains(location)
            }) {
                return
            }
            let mapPoint = MKMapPoint(map.convert(location, toCoordinateFrom: map))
            for overlay in map.overlays.reversed() {
                if let polygon = overlay as? StyledPolygon,
                   let title = polygon.artifactTitle,
                   let renderer = map.renderer(for: polygon) as? MKPolygonRenderer,
                   renderer.path?.contains(renderer.point(for: mapPoint)) == true {
                    onSelectArtifact(title, polygon.artifactDescription)
                    return
                }
                if let line = overlay as? StyledPolyline,
                   let title = line.artifactTitle,
                   let renderer = map.renderer(for: line) as? MKPolylineRenderer,
                   let path = renderer.path {
                    let hitPath = path.copy(
                        strokingWithWidth: max(renderer.lineWidth, 22),
                        lineCap: .round,
                        lineJoin: .round,
                        miterLimit: 0
                    )
                    if hitPath.contains(renderer.point(for: mapPoint)) {
                        onSelectArtifact(title, line.artifactDescription)
                        return
                    }
                }
            }
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }
    }
}

private final class AircraftAnnotationView: MKAnnotationView {
    private let iconView = UIImageView()
    private let nameLabel = UILabel()
    private let statusLabel = UILabel()
    private let leaderLayer = CAShapeLayer()
    private let cameraFovHaloLayer = CAShapeLayer()
    private let cameraFovRayLayer = CAShapeLayer()

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 800, height: 360)
        centerOffset = .zero
        canShowCallout = false
        cameraFovHaloLayer.strokeColor = UIColor.black.withAlphaComponent(0.7).cgColor
        cameraFovHaloLayer.fillColor = UIColor.clear.cgColor
        cameraFovHaloLayer.lineWidth = 3
        cameraFovHaloLayer.lineCap = .round
        layer.addSublayer(cameraFovHaloLayer)
        cameraFovRayLayer.strokeColor = UIColor(red: 0.50, green: 0.87, blue: 0.92, alpha: 1).cgColor
        cameraFovRayLayer.fillColor = UIColor.clear.cgColor
        cameraFovRayLayer.lineWidth = 1.25
        cameraFovRayLayer.lineCap = .round
        layer.addSublayer(cameraFovRayLayer)
        leaderLayer.strokeColor = UIColor.white.cgColor
        leaderLayer.lineWidth = 2
        leaderLayer.lineCap = .round
        leaderLayer.shadowColor = UIColor.black.cgColor
        leaderLayer.shadowOpacity = 0.8
        leaderLayer.shadowRadius = 1
        layer.addSublayer(leaderLayer)
        iconView.contentMode = .center
        iconView.clipsToBounds = true
        addSubview(iconView)
        configureLabel(nameLabel, fontSize: 16, cornerRadius: 6)
        configureLabel(statusLabel, fontSize: 13, cornerRadius: 5)
        addSubview(nameLabel)
        addSubview(statusLabel)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func configureLabel(_ label: UILabel, fontSize: CGFloat, cornerRadius: CGFloat) {
        label.font = .boldSystemFont(ofSize: fontSize)
        label.textAlignment = .center
        label.textColor = .white
        label.backgroundColor = UIColor.black.withAlphaComponent(0.58)
        label.layer.cornerRadius = cornerRadius
        label.clipsToBounds = true
    }

    func configure(_ aircraft: AircraftAnnotation) {
        annotation = aircraft
        let iconSize: CGFloat = aircraft.inset ? 34 : 50
        let iconX = (bounds.width - iconSize) / 2
        let iconY = (bounds.height - iconSize) / 2
        iconView.frame = CGRect(x: iconX, y: iconY, width: iconSize, height: iconSize)
        iconView.backgroundColor = .clear
        iconView.layer.cornerRadius = 0
        iconView.layer.borderWidth = 0
        iconView.image = AircraftMarkerRenderer.image(
            size: iconSize,
            color: aircraft.color,
            headingDegrees: aircraft.heading,
            focused: aircraft.focused
        )
        iconView.transform = .identity
        configureCameraFov(aircraft.cameraFov, iconSize: iconSize)
        nameLabel.isHidden = aircraft.inset
        statusLabel.isHidden = aircraft.inset
        leaderLayer.isHidden = aircraft.inset
        nameLabel.text = aircraft.title
        statusLabel.text = aircraft.statusText
        if let layout = aircraft.labelLayout {
            nameLabel.frame = localRect(layout.nameBounds, anchor: aircraft.anchorScreen)
            statusLabel.frame = localRect(layout.statusBounds, anchor: aircraft.anchorScreen)
        } else {
            let labelX = aircraft.labelSide < 0 ? bounds.midX - 125 : bounds.midX + 25
            nameLabel.frame = CGRect(x: labelX, y: bounds.midY + 28, width: 100, height: 25)
            statusLabel.frame = CGRect(x: labelX - 55, y: bounds.midY + 56, width: 210, height: 23)
        }
        let path = UIBezierPath()
        path.move(to: CGPoint(x: bounds.midX, y: bounds.midY))
        if let end = aircraft.labelLayout?.leaderEnd {
            path.addLine(to: CGPoint(
                x: bounds.midX + end.x - aircraft.anchorScreen.x,
                y: bounds.midY + end.y - aircraft.anchorScreen.y
            ))
            leaderLayer.isHidden = aircraft.inset
        } else {
            leaderLayer.isHidden = true
        }
        leaderLayer.path = path.cgPath
    }

    func updateCameraFov(_ cameraFov: CameraFovBoundaryBearings?) {
        configureCameraFov(cameraFov, iconSize: iconView.bounds.width)
    }

    private func configureCameraFov(
        _ cameraFov: CameraFovBoundaryBearings?,
        iconSize: CGFloat
    ) {
        guard let cameraFov else {
            cameraFovHaloLayer.path = nil
            cameraFovRayLayer.path = nil
            return
        }
        let scale = iconSize / 50
        let startRadius = 14 * scale
        let endRadius = startRadius + 36 * scale
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        let path = UIBezierPath()
        for bearing in [cameraFov.leftDegrees, cameraFov.rightDegrees] {
            let radians = CGFloat(bearing * .pi / 180)
            let direction = CGVector(dx: sin(radians), dy: -cos(radians))
            path.move(to: CGPoint(
                x: center.x + direction.dx * startRadius,
                y: center.y + direction.dy * startRadius
            ))
            path.addLine(to: CGPoint(
                x: center.x + direction.dx * endRadius,
                y: center.y + direction.dy * endRadius
            ))
        }
        cameraFovHaloLayer.path = path.cgPath
        cameraFovRayLayer.path = path.cgPath
    }

    private func localRect(_ rect: MapLabelRect, anchor: MapScreenPoint) -> CGRect {
        CGRect(
            x: bounds.midX + rect.left - anchor.x,
            y: bounds.midY + rect.top - anchor.y,
            width: rect.width,
            height: rect.height
        )
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        iconView.frame.insetBy(dx: -10, dy: -10).contains(point)
            || (!nameLabel.isHidden && nameLabel.frame.contains(point))
            || (!statusLabel.isHidden && statusLabel.frame.contains(point))
    }
}

/// Renders the marker as one immutable bitmap, matching Android's osmdroid
/// marker strategy. This avoids transform/clipping artifacts when MapKit reuses
/// annotation views and leaves the symmetric drone icon useful even when no
/// confident course can be derived yet.
private enum AircraftMarkerRenderer {
    static func image(
        size: CGFloat,
        color: UIColor,
        headingDegrees: Double?,
        focused: Bool
    ) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format)
        return renderer.image { rendererContext in
            let context = rendererContext.cgContext
            let center = CGPoint(x: size / 2, y: size / 2)
            let scale = size / 50
            let circleRadius = 14 * scale

            if let headingDegrees, headingDegrees.isFinite {
                drawCoursePointer(
                    context: context,
                    center: center,
                    startRadius: circleRadius + 1 * scale,
                    tipRadius: 23 * scale,
                    headingDegrees: headingDegrees,
                    scale: scale
                )
            }

            context.setFillColor(UIColor.white.withAlphaComponent(0.92).cgColor)
            context.setStrokeColor(UIColor.black.withAlphaComponent(0.65).cgColor)
            context.setLineWidth(1.5 * scale)
            context.addArc(center: center, radius: circleRadius, startAngle: 0, endAngle: 2 * .pi, clockwise: false)
            context.drawPath(using: .fillStroke)

            if focused {
                context.setStrokeColor(UIColor.systemYellow.cgColor)
                context.setLineWidth(3 * scale)
                context.addArc(
                    center: center,
                    radius: circleRadius + 1.5 * scale,
                    startAngle: 0,
                    endAngle: 2 * .pi,
                    clockwise: false
                )
                context.strokePath()
            }

            drawDrone(context: context, center: center, color: color, scale: scale)
        }
    }

    private static func drawCoursePointer(
        context: CGContext,
        center: CGPoint,
        startRadius: CGFloat,
        tipRadius: CGFloat,
        headingDegrees: Double,
        scale: CGFloat
    ) {
        let radians = CGFloat(headingDegrees * .pi / 180)
        let direction = CGVector(dx: sin(radians), dy: -cos(radians))
        let start = CGPoint(
            x: center.x + direction.dx * startRadius,
            y: center.y + direction.dy * startRadius
        )
        let tip = CGPoint(
            x: center.x + direction.dx * tipRadius,
            y: center.y + direction.dy * tipRadius
        )
        let perpendicular = CGVector(dx: -direction.dy, dy: direction.dx)
        let arrowLength = 4 * scale
        let arrowWidth = 3 * scale
        let left = CGPoint(
            x: tip.x - direction.dx * arrowLength + perpendicular.dx * arrowWidth,
            y: tip.y - direction.dy * arrowLength + perpendicular.dy * arrowWidth
        )
        let right = CGPoint(
            x: tip.x - direction.dx * arrowLength - perpendicular.dx * arrowWidth,
            y: tip.y - direction.dy * arrowLength - perpendicular.dy * arrowWidth
        )

        for (strokeColor, lineWidth) in [
            (UIColor.black.withAlphaComponent(0.7), 4 * scale),
            (UIColor(red: 1, green: 0.97, blue: 0.88, alpha: 1), 2 * scale)
        ] {
            context.setStrokeColor(strokeColor.cgColor)
            context.setLineWidth(lineWidth)
            context.setLineCap(.round)
            context.setLineJoin(.round)
            context.beginPath()
            context.move(to: start)
            context.addLine(to: tip)
            context.move(to: left)
            context.addLine(to: tip)
            context.addLine(to: right)
            context.strokePath()
        }
    }

    private static func drawDrone(
        context: CGContext,
        center: CGPoint,
        color: UIColor,
        scale: CGFloat
    ) {
        context.setStrokeColor(color.cgColor)
        context.setFillColor(color.cgColor)
        context.setLineWidth(2.2 * scale)
        context.setLineCap(.round)

        let arm = 7 * scale
        context.beginPath()
        context.move(to: CGPoint(x: center.x - arm, y: center.y - arm))
        context.addLine(to: CGPoint(x: center.x + arm, y: center.y + arm))
        context.move(to: CGPoint(x: center.x + arm, y: center.y - arm))
        context.addLine(to: CGPoint(x: center.x - arm, y: center.y + arm))
        context.strokePath()

        let rotorOffset = 8 * scale
        let rotorRadius = 2.8 * scale
        for xSign: CGFloat in [-1, 1] {
            for ySign: CGFloat in [-1, 1] {
                context.addArc(
                    center: CGPoint(
                        x: center.x + xSign * rotorOffset,
                        y: center.y + ySign * rotorOffset
                    ),
                    radius: rotorRadius,
                    startAngle: 0,
                    endAngle: 2 * .pi,
                    clockwise: false
                )
                context.strokePath()
            }
        }

        context.addArc(center: center, radius: 4 * scale, startAngle: 0, endAngle: 2 * .pi, clockwise: false)
        context.fillPath()
        context.setFillColor(UIColor.systemCyan.cgColor)
        context.addArc(center: center, radius: 1.3 * scale, startAngle: 0, endAngle: 2 * .pi, clockwise: false)
        context.fillPath()
    }
}

private final class CachedMapTileOverlay: MKTileOverlay {
    private static let baseSourceMaximumZ = 19
    private static let displayMaximumZ = 22
    private let baseLayer: OperationalMapBaseLayer?
    private let contours: Bool
    private let offlineOnly: Bool
    private let cacheRoot: URL

    init(baseLayer: OperationalMapBaseLayer, offlineOnly: Bool) {
        self.baseLayer = baseLayer
        contours = false
        self.offlineOnly = offlineOnly
        cacheRoot = AppleMapCachePaths.root.appendingPathComponent(baseLayer.cacheKey, isDirectory: true)
        super.init(urlTemplate: nil)
        minimumZ = 0
        maximumZ = Self.displayMaximumZ
        tileSize = CGSize(width: 256, height: 256)
    }

    init(contoursOfflineOnly offlineOnly: Bool) {
        baseLayer = nil
        contours = true
        self.offlineOnly = offlineOnly
        cacheRoot = AppleMapCachePaths.root.appendingPathComponent("usgsContours", isDirectory: true)
        super.init(urlTemplate: nil)
        minimumZ = 0
        maximumZ = Self.displayMaximumZ
        tileSize = CGSize(width: 256, height: 256)
    }

    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        if let baseLayer, let url = baseLayer.tileURL(zoom: path.z, x: path.x, y: path.y) { return url }
        return AppleMapTileRequest.contourURL(zoom: path.z, x: path.x, y: path.y)
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        let completion = TileResultTransfer(result: result)
        let ext = contours ? "png" : (baseLayer?.fileExtension ?? "tile")
        let requestedDestination = cacheDestination(for: path, fileExtension: ext)
        if let data = usableCachedTile(at: requestedDestination) {
            completion.result(data, nil)
            return
        }

        let overzoom = contours ? nil : OperationalOverzoomTile.resolve(
            requestedZoom: path.z,
            requestedX: path.x,
            requestedY: path.y,
            sourceMaximumZoom: Self.baseSourceMaximumZ
        )
        let sourcePath = overzoom.map {
            MKTileOverlayPath(
                x: $0.sourceX,
                y: $0.sourceY,
                z: $0.sourceZoom,
                contentScaleFactor: path.contentScaleFactor
            )
        } ?? path
        let sourceDestination = cacheDestination(for: sourcePath, fileExtension: ext)
        if let sourceData = usableCachedTile(at: sourceDestination) {
            guard let output = Self.displayTileData(
                sourceData: sourceData,
                overzoom: overzoom,
                fileExtension: ext
            ) else {
                completion.result(nil, CocoaError(.fileReadCorruptFile))
                return
            }
            cache(output, at: requestedDestination)
            completion.result(output, nil)
            return
        }
        guard !offlineOnly else {
            completion.result(nil, CocoaError(.fileNoSuchFile))
            return
        }

        let requestURL = url(forTilePath: sourcePath)
        var request = URLRequest(url: requestURL)
        request.setValue("RID2Caltopo/Apple (contact: kjt@uas4sar.com)", forHTTPHeaderField: "User-Agent")
        let transfer = TileLoadTransfer(
            request: request,
            sourceDestination: sourceDestination,
            requestedDestination: requestedDestination,
            overzoom: overzoom,
            fileExtension: ext,
            blockedHashes: AppleBadTilePolicy.hashes(),
            completion: completion
        )
        Self.downloadAndComplete(transfer)
    }

    private nonisolated static func downloadAndComplete(_ transfer: TileLoadTransfer) {
        Task.detached(priority: .userInitiated) {
            do {
                let downloaded = try await AppleMapTileDownloadCoordinator.shared.download(
                    request: transfer.request,
                    destination: transfer.sourceDestination,
                    blockedHashes: transfer.blockedHashes
                )
                AppleMapOfflineManager.noteTileCached(bytes: Int(downloaded.bytesAdded))
                guard let output = Self.displayTileData(
                    sourceData: downloaded.data,
                    overzoom: transfer.overzoom,
                    fileExtension: transfer.fileExtension
                ) else {
                    transfer.completion.result(nil, CocoaError(.fileReadCorruptFile))
                    return
                }
                if transfer.requestedDestination != transfer.sourceDestination {
                    Self.cache(output, at: transfer.requestedDestination)
                }
                transfer.completion.result(output, nil)
            } catch {
                transfer.completion.result(nil, error)
            }
        }
    }

    private func cacheDestination(for path: MKTileOverlayPath, fileExtension: String) -> URL {
        cacheRoot
            .appendingPathComponent(String(path.z), isDirectory: true)
            .appendingPathComponent(String(path.x), isDirectory: true)
            .appendingPathComponent("\(path.y).\(fileExtension)")
    }

    private func usableCachedTile(at destination: URL) -> Data? {
        AppleMapCacheAccess.synchronized {
            let maximumAgeDays = max(
                1,
                min(3_650, UserDefaults.standard.object(forKey: "map.maximumTileAgeDays") as? Int ?? 365)
            )
            let cutoff = Date().addingTimeInterval(-Double(maximumAgeDays) * 86_400)
            let modified = try? destination.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate
            if OperationalCacheFreshness.isFresh(modifiedAt: modified, cutoff: cutoff),
               let data = try? Data(contentsOf: destination),
               AppleMapOfflineManager.dataIsUsableTile(data),
               !AppleBadTilePolicy.isBlocked(data) {
                return data
            }
            if FileManager.default.fileExists(atPath: destination.path) {
                if UserDefaults.standard.object(forKey: "map.autoRemoveBadTiles") as? Bool ?? true {
                    try? FileManager.default.removeItem(at: destination)
                }
            }
            return nil
        }
    }

    private static func displayTileData(
        sourceData: Data,
        overzoom: OperationalOverzoomTile?,
        fileExtension: String
    ) -> Data? {
        guard let overzoom else { return sourceData }
        guard let sourceImage = UIImage(data: sourceData) else { return nil }
        let scale = CGFloat(1 << overzoom.zoomDelta)
        let sourceSize = sourceImage.size
        let cropWidth = sourceSize.width / scale
        let cropHeight = sourceSize.height / scale
        let cropOrigin = CGPoint(
            x: CGFloat(overzoom.childX) * cropWidth,
            y: CGFloat(overzoom.childY) * cropHeight
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = fileExtension == "jpg"
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: 256, height: 256),
            format: format
        )
        let tile = renderer.image { _ in
            sourceImage.draw(in: CGRect(
                x: -cropOrigin.x * scale,
                y: -cropOrigin.y * scale,
                width: sourceSize.width * scale,
                height: sourceSize.height * scale
            ))
        }
        return fileExtension == "jpg"
            ? tile.jpegData(compressionQuality: 0.9)
            : tile.pngData()
    }

    private func cache(_ data: Data, at destination: URL) {
        Self.cache(data, at: destination)
    }

    private static func cache(_ data: Data, at destination: URL) {
        do {
            let bytesAdded = try AppleMapCacheAccess.write(data, to: destination)
            AppleMapOfflineManager.noteTileCached(bytes: Int(bytesAdded))
        } catch {
            AppleLog.warning("MapTiles", "Tile cache write failed: \(error.localizedDescription)")
        }
    }

}

private struct TileResultTransfer: @unchecked Sendable {
    let result: (Data?, Error?) -> Void
}

private struct TileLoadTransfer: @unchecked Sendable {
    let request: URLRequest
    let sourceDestination: URL
    let requestedDestination: URL
    let overzoom: OperationalOverzoomTile?
    let fileExtension: String
    let blockedHashes: Set<String>
    let completion: TileResultTransfer
}

private enum OperationalMapRenderLayer {
    case staticMap
    case aircraft
    case operatorDevice
}

private protocol MapLayerAnnotation: AnyObject {
    var mapLayer: OperationalMapRenderLayer { get }
}

private final class StyledPolyline: MKPolyline {
    var color: UIColor = .systemBlue
    var width: CGFloat = 3
    var layer: OperationalMapRenderLayer = .staticMap
    var artifactTitle: String?
    var artifactDescription: String?

    convenience init(
        coordinates: [CLLocationCoordinate2D],
        count: Int,
        color: UIColor,
        width: Double,
        artifactTitle: String? = nil,
        artifactDescription: String? = nil,
        layer: OperationalMapRenderLayer = .staticMap
    ) {
        self.init(coordinates: coordinates, count: count)
        self.color = color
        self.width = width
        self.artifactTitle = artifactTitle
        self.artifactDescription = artifactDescription
        self.layer = layer
    }
}

private final class StyledPolygon: MKPolygon {
    var stroke: UIColor = .systemOrange
    var fill: UIColor = .systemOrange.withAlphaComponent(0.2)
    var width: CGFloat = 3
    var layer: OperationalMapRenderLayer = .staticMap
    var artifactTitle: String?
    var artifactDescription: String?

    convenience init(
        coordinates: [CLLocationCoordinate2D],
        count: Int,
        stroke: UIColor,
        fill: UIColor,
        width: Double,
        artifactTitle: String? = nil,
        artifactDescription: String? = nil,
        layer: OperationalMapRenderLayer = .staticMap
    ) {
        self.init(coordinates: coordinates, count: count)
        self.stroke = stroke
        self.fill = fill
        self.width = width
        self.artifactTitle = artifactTitle
        self.artifactDescription = artifactDescription
        self.layer = layer
    }
}

private final class AircraftAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer = OperationalMapRenderLayer.aircraft
    let remoteID: String
    @objc dynamic var coordinate = CLLocationCoordinate2D()
    var title: String?
    var heading: Double?
    var cameraFov: CameraFovBoundaryBearings?
    var color = UIColor.systemBlue
    var inset = false
    var labelSide = 1
    var statusText = ""
    var labelLayout: MapAircraftLabelLayout?
    var anchorScreen = MapScreenPoint(x: 0, y: 0)
    var focused = false

    init(remoteID: String) {
        self.remoteID = remoteID
        super.init()
    }

    func update(
        coordinate: CLLocationCoordinate2D,
        title: String,
        heading: Double?,
        cameraFov: CameraFovBoundaryBearings?,
        color: UIColor,
        inset: Bool,
        labelSide: Int,
        statusText: String,
        labelLayout: MapAircraftLabelLayout?,
        anchorScreen: MapScreenPoint,
        focused: Bool
    ) {
        self.coordinate = coordinate
        self.title = title
        self.heading = heading
        self.cameraFov = cameraFov
        self.color = color
        self.inset = inset
        self.labelSide = labelSide
        self.statusText = statusText
        self.labelLayout = labelLayout
        self.anchorScreen = anchorScreen
        self.focused = focused
    }
}

private final class OperatorDeviceAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer = OperationalMapRenderLayer.operatorDevice
    @objc dynamic var coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String? = OperationalFacilityMap.operatingAreaLabel
    let inset: Bool
    var statusLines: [String]

    @MainActor
    init(coordinate: CLLocationCoordinate2D, inset: Bool, statusLines: [String]) {
        self.coordinate = coordinate
        self.inset = inset
        self.statusLines = statusLines
        self.title = "R2C: \(AppleDeviceIdentity.displayName)"
        super.init()
    }
}

private final class ClueAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer = OperationalMapRenderLayer.staticMap
    let clueID: UUID
    dynamic let coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String?
    let published: Bool

    init(clue: OperationalClueRecord) {
        clueID = clue.id
        coordinate = CLLocationCoordinate2D(latitude: clue.clueLatitude, longitude: clue.clueLongitude)
        title = clue.title
        subtitle = clue.uploadState == .published ? "Published to CalTopo" : "Local R2C clue"
        published = clue.uploadState == .published
        super.init()
    }
}

private final class NotamAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer = OperationalMapRenderLayer.staticMap
    dynamic let coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String?
    let color: UIColor

    init(notice: OperationalNotam, coordinate: CLLocationCoordinate2D) {
        self.coordinate = coordinate
        title = notice.title
        subtitle = notice.summary
        color = switch notice.severity {
        case .danger: .systemRed
        case .caution: .systemOrange
        case .normal: .systemGreen
        case .neutral: .systemGray
        }
        super.init()
    }
}

private extension OperationalNotamCoordinate {
    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

private extension OperationalLandCoordinate {
    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

private final class ArtifactAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer: OperationalMapRenderLayer
    dynamic let coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String?
    let systemSymbol: String
    let caltopoSymbol: String
    let color: UIColor
    let colorHex: String?

    init(
        coordinate: CLLocationCoordinate2D,
        title: String,
        description: String? = nil,
        symbol: String,
        color: UIColor,
        colorHex: String?,
        mapLayer: OperationalMapRenderLayer = .staticMap
    ) {
        self.mapLayer = mapLayer
        self.coordinate = coordinate
        self.title = title
        subtitle = description
        caltopoSymbol = symbol
        systemSymbol = Self.systemSymbol(for: symbol)
        self.color = color
        self.colorHex = colorHex
        super.init()
    }

    private static func systemSymbol(for caltopoSymbol: String) -> String {
        switch caltopoSymbol.lowercased() {
        case "aperture", "camera": "camera.fill"
        case "radiotower": "antenna.radiowaves.left.and.right"
        case "drone": "airplane"
        case "person", "rescue": "figure.walk"
        case "fuel": "fuelpump.fill"
        case "automobile", "4wd": "car.fill"
        case "camping": "tent.fill"
        case "hut": "house.fill"
        case "waterfalls": "water.waves"
        case "medevac-site": "cross.fill"
        default: "mappin.circle.fill"
        }
    }

    static func defaultColor(for caltopoSymbol: String) -> UIColor {
        switch caltopoSymbol.lowercased() {
        case "cp", "clue", "medevac-site": UIColor(red: 0.18, green: 0.31, blue: 0.68, alpha: 1)
        case "heatsource", "fire-hotspot", "c:ring", "c:target1", "c:target2", "c:target3", "point": .systemRed
        default: .label
        }
    }
}

private final class ArtifactAnnotationView: MKAnnotationView {
    private static let iconLoader = CaltopoMarkerIconDataLoader()
    private let background = UIView()
    private let imageView = UIImageView()
    private let glyphLabel = UILabel()
    private var iconTask: Task<Void, Never>?
    private var representedIconURL: URL?

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 38, height: 38)
        centerOffset = CGPoint(x: 0, y: -19)
        canShowCallout = true
        collisionMode = .circle
        background.frame = CGRect(x: 3, y: 3, width: 32, height: 32)
        background.layer.cornerRadius = 16
        background.layer.borderWidth = 2
        background.layer.borderColor = UIColor.white.cgColor
        background.layer.shadowOpacity = 0.35
        background.layer.shadowRadius = 2
        background.layer.shadowOffset = .zero
        addSubview(background)
        imageView.frame = CGRect(x: 8, y: 8, width: 22, height: 22)
        imageView.contentMode = .scaleAspectFit
        imageView.tintColor = .white
        addSubview(imageView)
        glyphLabel.frame = bounds
        glyphLabel.textAlignment = .center
        glyphLabel.font = .boldSystemFont(ofSize: 13)
        glyphLabel.textColor = .white
        addSubview(glyphLabel)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func prepareForReuse() {
        super.prepareForReuse()
        iconTask?.cancel()
        iconTask = nil
        representedIconURL = nil
        detailCalloutAccessoryView = nil
    }

    func configure(_ artifact: ArtifactAnnotation) {
        annotation = artifact
        iconTask?.cancel()
        configureDescriptionCallout(artifact.subtitle ?? nil)
        background.backgroundColor = artifact.color
        let style = Self.style(for: artifact.caltopoSymbol)
        showFallback(style)

        guard case .staticMap = artifact.mapLayer else { return }
        guard let iconURL = CaltopoMarkerIcon.url(
            symbol: artifact.caltopoSymbol,
            colorHex: artifact.colorHex
        ) else { return }
        representedIconURL = iconURL
        iconTask = Task { [weak self] in
            guard let image = await Self.iconLoader.image(for: iconURL),
                  !Task.isCancelled,
                  let self,
                  self.representedIconURL == iconURL
            else { return }
            self.background.isHidden = true
            self.glyphLabel.isHidden = true
            self.imageView.frame = self.bounds
            self.imageView.tintColor = nil
            self.imageView.image = image
            self.imageView.isHidden = false
        }
    }

    private func configureDescriptionCallout(_ description: String?) {
        guard let description,
              !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            detailCalloutAccessoryView = nil
            return
        }

        let textView = UITextView()
        textView.text = description
        textView.font = .preferredFont(forTextStyle: .footnote)
        textView.textColor = .label
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = true
        textView.adjustsFontForContentSizeCategory = true
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0

        let width: CGFloat = 300
        let measured = textView.sizeThatFits(
            CGSize(width: width, height: CGFloat.greatestFiniteMagnitude)
        ).height
        textView.frame = CGRect(x: 0, y: 0, width: width, height: min(max(measured, 36), 180))
        detailCalloutAccessoryView = textView
    }

    private func showFallback(_ style: (systemImage: String?, glyph: String?)) {
        background.isHidden = false
        imageView.frame = CGRect(x: 8, y: 8, width: 22, height: 22)
        imageView.tintColor = .white
        imageView.image = style.systemImage.flatMap(UIImage.init(systemName:))
        imageView.isHidden = style.systemImage == nil
        glyphLabel.text = style.glyph
        glyphLabel.isHidden = style.glyph == nil
    }

    private static func style(for raw: String) -> (systemImage: String?, glyph: String?) {
        let symbol = raw.lowercased()
        switch symbol {
        case "aperture", "camera": return ("camera.fill", nil)
        case "radiotower": return ("antenna.radiowaves.left.and.right", nil)
        case "drone": return ("airplane", nil)
        case "person", "rescue": return ("figure.walk", nil)
        case "fuel": return ("fuelpump.fill", nil)
        case "automobile", "4wd": return ("car.fill", nil)
        case "camping": return ("tent.fill", nil)
        case "hut": return ("house.fill", nil)
        case "waterfalls": return ("water.waves", nil)
        case "medevac-site": return ("cross.fill", nil)
        case "point": return (nil, "•")
        case "c:ring": return (nil, "○")
        case "c:target1": return (nil, "1")
        case "c:target2": return (nil, "2")
        case "c:target3": return (nil, "3")
        case "cp": return (nil, "CP")
        case "clue": return (nil, "?")
        case "heatsource": return (nil, "×")
        case "fire-hotspot": return (nil, "⊙")
        default: return ("mappin.circle.fill", nil)
        }
    }
}

private actor CaltopoMarkerIconDataLoader {
    private let memoryCache = NSCache<NSURL, UIImage>()
    private let session: URLSession
    private let cacheDirectory: URL
    private var inFlight: [URL: Task<Data?, Never>] = [:]

    init() {
        let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        cacheDirectory = root.appendingPathComponent(
            "RID2Caltopo/CalTopoMarkerIcons",
            isDirectory: true
        )
        try? FileManager.default.createDirectory(
            at: cacheDirectory,
            withIntermediateDirectories: true
        )
        let configuration = URLSessionConfiguration.default
        configuration.requestCachePolicy = .returnCacheDataElseLoad
        configuration.urlCache = URLCache(
            memoryCapacity: 8 * 1_024 * 1_024,
            diskCapacity: 32 * 1_024 * 1_024
        )
        session = URLSession(configuration: configuration)
    }

    func image(for url: URL) async -> UIImage? {
        if let cached = memoryCache.object(forKey: url as NSURL) {
            return cached
        }
        let diskURL = cacheURL(for: url)
        if let data = try? Data(contentsOf: diskURL, options: .mappedIfSafe),
           let image = UIImage(data: data) {
            memoryCache.setObject(image, forKey: url as NSURL)
            return image
        }

        let task: Task<Data?, Never>
        if let existing = inFlight[url] {
            task = existing
        } else {
            let session = self.session
            task = Task {
                do {
                    let (data, response) = try await session.data(from: url)
                    guard let http = response as? HTTPURLResponse,
                          (200 ..< 300).contains(http.statusCode),
                          !data.isEmpty
                    else { return nil }
                    return data
                } catch {
                    return nil
                }
            }
            inFlight[url] = task
        }
        let data = await task.value
        inFlight[url] = nil
        guard let data, let image = UIImage(data: data) else { return nil }
        try? data.write(to: diskURL, options: .atomic)
        memoryCache.setObject(image, forKey: url as NSURL)
        return image
    }

    private func cacheURL(for url: URL) -> URL {
        let digest = SHA256.hash(data: Data(url.absoluteString.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return cacheDirectory.appendingPathComponent("\(digest).png")
    }
}

private extension MapCoordinate {
    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

private extension MKCoordinateRegion {
    var offlineBounds: OperationalMapBounds {
        OperationalMapBounds(
            north: center.latitude + span.latitudeDelta / 2,
            south: center.latitude - span.latitudeDelta / 2,
            west: center.longitude - span.longitudeDelta / 2,
            east: center.longitude + span.longitudeDelta / 2
        )
    }
}

private extension UIColor {
    convenience init?(hex: String?) {
        guard var value = hex?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        value.removeAll(where: { $0 == "#" })
        guard let raw = UInt64(value, radix: 16) else { return nil }
        switch value.count {
        case 6:
            self.init(red: CGFloat((raw >> 16) & 0xff) / 255, green: CGFloat((raw >> 8) & 0xff) / 255, blue: CGFloat(raw & 0xff) / 255, alpha: 1)
        case 8:
            // CalTopo and Android use #AARRGGBB, not CSS #RRGGBBAA.
            self.init(
                red: CGFloat((raw >> 16) & 0xff) / 255,
                green: CGFloat((raw >> 8) & 0xff) / 255,
                blue: CGFloat(raw & 0xff) / 255,
                alpha: CGFloat((raw >> 24) & 0xff) / 255
            )
        default:
            return nil
        }
    }
}
