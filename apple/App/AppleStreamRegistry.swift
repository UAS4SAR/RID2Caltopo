import AVKit
import Combine
import MapKit
import R2CCore
import SwiftUI
import UIKit

enum AppleStreamState: String, Sendable {
    case connecting
    case live
    case error
    case stopped
}

@MainActor
final class AppleLiveStreamSession: ObservableObject, Identifiable {
    let id: String
    let sourcePath: String
    let controllerProfile: String
    let model: AppleVideoFrameSource
    let endpoint: MediaStreamEndpoint
    @Published var state: AppleStreamState
    @Published var publisherConnectionID: String?
    @Published var errorDetail: String?
    @Published var changedAt: Date

    init(path: String, model: AppleVideoFrameSource? = nil, state: AppleStreamState = .connecting) {
        let parsed = Self.parse(path)
        id = parsed.designator
        sourcePath = parsed.sourcePath
        controllerProfile = parsed.profile
        self.model = model ?? AppleVideoFrameSource()
        endpoint = MediaStreamEndpoint(designator: parsed.sourcePath)
        self.state = state
        changedAt = Date()
    }

    private static func parse(_ raw: String) -> (designator: String, sourcePath: String, profile: String) {
        let path = raw.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        let segments = path.split(separator: "/").map(String.init)
        let profiles = ["RC2", "RCPRO2", "RCPRO1", "ENTERPRISE2", "AUTEL"]
        let first = segments.first?.uppercased() ?? ""
        let profile = profiles.contains(first) ? first : "GENERIC"
        let designator = profile == "GENERIC" ? path : segments.dropFirst().joined(separator: "/")
        return (designator.isEmpty ? raw : designator, path.isEmpty ? raw : path, profile)
    }
}

@MainActor
final class AppleStreamRegistry: ObservableObject {
    static let shared = AppleStreamRegistry()
    static let maximumStreams = 4

    @Published private(set) var sessions: [AppleLiveStreamSession] = []
    @Published var focusedID = "demo"
    @Published private(set) var rejectedPaths: Set<String> = []
    @Published private(set) var managedPresenceRevision = 0

    private var presenceSubscriptions: [ObjectIdentifier: AnyCancellable] = [:]
    private var presenceEligibility: [ObjectIdentifier: Bool] = [:]
    private var flightActivity = PairedVideoFlightActivityStore()
    private var seiPositionContinuationByStreamID: [String: OperationalSEIPositionContinuation] = [:]

    let primaryModel: AppleVideoFrameSource

    init(primaryModel: AppleVideoFrameSource = AppleVideoFrameSource()) {
        self.primaryModel = primaryModel
        sessions = [AppleLiveStreamSession(path: "demo", model: primaryModel, state: .stopped)]
    }

    var focusedSession: AppleLiveStreamSession {
        sessions.first(where: { $0.id == focusedID }) ?? sessions.first!
    }

    var focusedPlaybackURL: URL? {
        let activePaths = Set(sessions.filter { $0.state == .live && $0.id != Self.placeholderID }.map(\.sourcePath))
        guard let path = LiveStreamSelectionPolicy.playbackPath(
            focusedID: focusedID,
            activePublisherPaths: activePaths
        ) else { return nil }
        return matching(path)?.endpoint.loopbackHlsURL
    }

    private static let placeholderID = LiveStreamSelectionPolicy.placeholderID

    func handle(_ event: MediaServerEvent) {
        let observedAt = Date()
        switch event {
        case let .streamConnecting(path):
            admit(path: path, state: .connecting, publisherID: nil)?.model.handleMediaServerEvent(event)
        case let .streamStarted(path, publisherID), let .streamPublisherHandoff(path, publisherID):
            let session = admit(path: path, state: .live, publisherID: publisherID)
            if let session {
                seiPositionContinuationByStreamID.removeValue(forKey: session.id)
                flightActivity.publisherStarted(streamID: session.id, at: observedAt)
            }
            session?.errorDetail = nil
            startDecoderIfNeeded(for: session)
            session?.model.handleMediaServerEvent(event)
        case let .hlsStreamStarted(path):
            let activePaths = Set(sessions.filter { $0.state == .live && $0.publisherConnectionID != nil }.map(\.sourcePath))
            guard LiveStreamSelectionPolicy.shouldAcceptHLSMuxer(path: path, activePublisherPaths: activePaths),
                  let session = matching(path)
            else { break }
            session.model.handleMediaServerEvent(event)
            startDecoderIfNeeded(for: session)
        case let .streamStopped(path, publisherID), let .rtmpSessionClosed(path, publisherID, _):
            if let stoppedSession = stop(path: path, publisherID: publisherID) {
                seiPositionContinuationByStreamID.removeValue(forKey: stoppedSession.id)
                flightActivity.publisherStopped(streamID: stoppedSession.id, at: observedAt)
            }
        case let .streamError(path, publisherID, detail):
            guard let path else { return }
            if let session = matching(path) {
                if let publisherID, let current = session.publisherConnectionID, publisherID != current { return }
                session.state = .error
                session.errorDetail = detail
                session.changedAt = Date()
                flightActivity.publisherStopped(streamID: session.id, at: observedAt)
                session.model.handleMediaServerEvent(event)
            }
        default: break
        }
        pruneStale()
    }

    func focus(_ id: String) {
        guard sessions.contains(where: { $0.id == id }) else { return }
        focusedID = id
    }

    func pair(streamID: String, aircraftID: String) {
        seiPositionContinuationByStreamID.removeValue(forKey: streamID)
        flightActivity.pair(streamID: streamID, aircraftID: aircraftID)
        managedPresenceRevision &+= 1
        objectWillChange.send()
        AppleLog.info("Streams", "Paired stream \(streamID) to Remote ID \(aircraftID)")
    }

    @discardableResult
    func pairIfUnbound(streamID: String, aircraftID: String) -> Bool {
        let paired = flightActivity.pairIfUnbound(streamID: streamID, aircraftID: aircraftID)
        if paired {
            managedPresenceRevision &+= 1
            objectWillChange.send()
            AppleLog.info("Streams", "Automatically paired stream \(streamID) to Remote ID \(aircraftID)")
        }
        return paired
    }

    func unpair(streamID: String) {
        seiPositionContinuationByStreamID.removeValue(forKey: streamID)
        flightActivity.unpair(streamID: streamID)
        managedPresenceRevision &+= 1
        objectWillChange.send()
        AppleLog.info("Streams", "Unpaired stream \(streamID) for the current app session")
    }

    func boundAircraftID(for streamID: String) -> String? {
        flightActivity.boundAircraftID(for: streamID)
    }

    var activePublisherStreamIDs: Set<String> {
        flightActivity.activePublisherStreamIDs
    }

    func flightActivityByAircraftID(at date: Date = Date()) -> [String: Date] {
        flightActivity.activityByAircraftID(at: date)
    }

    func djiSEILastActivityByAircraftID() -> [String: Date] {
        sessions.reduce(into: [:]) { result, session in
            guard let aircraftID = flightActivity.boundAircraftID(for: session.id),
                  let receivedAt = session.model.latestDJICameraTelemetry?.receivedAt
            else { return }
            result[aircraftID] = max(result[aircraftID] ?? .distantPast, receivedAt)
        }
    }

    /** Once true, operational consumers must not silently downgrade this stream to RID. */
    func isSEIPositionAuthorityEstablished(streamID: String) -> Bool {
        seiPositionContinuationByStreamID[streamID]?.positionValidated == true
    }

    func freshValidatedDJIPositionByAircraftID(
        tracks: [RidAircraftTrack],
        at date: Date = Date(),
        maximumAge: TimeInterval = 3
    ) -> [String: AppleDJICameraTelemetry] {
        let tracksByAircraftID = Dictionary(uniqueKeysWithValues: tracks.map { ($0.aircraftID, $0) })
        var result: [String: AppleDJICameraTelemetry] = [:]
        for session in sessions {
            guard let aircraftID = flightActivity.boundAircraftID(for: session.id),
                  let track = tracksByAircraftID[aircraftID],
                  let telemetry = session.model.freshDJICameraTelemetry(now: date, maximumAge: maximumAge)
            else { continue }
            var continuation = seiPositionContinuationByStreamID[session.id]
                ?? OperationalSEIPositionContinuation()
            let accepted = continuation.acceptHorizontalPosition(
                latitudeDegrees: telemetry.latitudeDegrees,
                longitudeDegrees: telemetry.longitudeDegrees,
                ridLatitudeDegrees: track.lastObservation.latitude,
                ridLongitudeDegrees: track.lastObservation.longitude,
                sourceTimestampMicroseconds: telemetry.sourceTimestampMicroseconds
            )
            let relativeUpAccepted = continuation.acceptRelativeUp(
                observedRelativeUpMeters: telemetry.relativeUpMeters,
                ridAltitudeMeters: track.lastObservation.altitudeMeters,
                takeoffReportedAltitudeMeters: track.points.first?.altitudeMeters
            )
            seiPositionContinuationByStreamID[session.id] = continuation
            guard accepted else { continue }
            if let existing = result[aircraftID], existing.receivedAt >= telemetry.receivedAt {
                continue
            }
            result[aircraftID] = telemetry.replacingRelativeUpMeters(
                relativeUpAccepted ? telemetry.relativeUpMeters : nil
            )
        }
        return result
    }

    func allAircraftHaveFreshPairedSEI(
        aircraftIDs: [String],
        at date: Date = Date(),
        maximumAge: TimeInterval = 3
    ) -> Bool {
        guard !aircraftIDs.isEmpty else { return false }
        let activity = djiSEILastActivityByAircraftID()
        return aircraftIDs.allSatisfy { aircraftID in
            guard let receivedAt = activity[aircraftID] else { return false }
            let age = date.timeIntervalSince(receivedAt)
            return age >= 0 && age <= maximumAge
        }
    }

    func close(_ id: String) {
        guard let session = sessions.first(where: { $0.id == id }) else { return }
        seiPositionContinuationByStreamID.removeValue(forKey: id)
        session.model.stop()
        if id == Self.placeholderID {
            session.state = .stopped
        } else {
            stopObservingManagedPresence(for: session)
            sessions.removeAll { $0.id == id }
            if focusedID == id { focusedID = sessions.first?.id ?? Self.placeholderID }
        }
        AppleLog.info(
            "Streams",
            "Operator closed stream \(session.sourcePath) networkSnapshotId=\(AppleNetworkDiagnosticCenter.shared.currentSnapshotID)"
        )
    }

    func shutdown() {
        sessions.forEach { $0.model.stop() }
        presenceSubscriptions.values.forEach { $0.cancel() }
        presenceSubscriptions.removeAll()
        presenceEligibility.removeAll()
        rejectedPaths.removeAll()
        flightActivity = PairedVideoFlightActivityStore()
        seiPositionContinuationByStreamID.removeAll()
        sessions = [AppleLiveStreamSession(path: "demo", model: primaryModel, state: .stopped)]
        focusedID = "demo"
    }

    @discardableResult
    private func admit(path: String, state: AppleStreamState, publisherID: String?) -> AppleLiveStreamSession? {
        if let existing = matching(path) {
            existing.state = state
            existing.publisherConnectionID = publisherID ?? existing.publisherConnectionID
            existing.changedAt = Date()
            rejectedPaths.remove(path)
            objectWillChange.send()
            return existing
        }
        pruneStale()
        let active = sessions.filter { $0.state != .stopped }
        if active.count >= Self.maximumStreams {
            if rejectedPaths.insert(path).inserted {
                AppleLog.warning(
                    "Streams",
                    "Rejected stream '\(path)': maximum \(Self.maximumStreams) active streams " +
                        "networkSnapshotId=\(AppleNetworkDiagnosticCenter.shared.currentSnapshotID)"
                )
            }
            return nil
        }
        let session = AppleLiveStreamSession(
            path: path,
            model: active.isEmpty ? primaryModel : nil,
            state: state
        )
        observeManagedPresence(for: session)
        session.publisherConnectionID = publisherID
        sessions.append(session)
        if state == .live && session.id != Self.placeholderID {
            let activePaths = Set(sessions.filter { $0.state == .live && $0.id != Self.placeholderID }.map(\.sourcePath))
            let previousFocus = focusedID
            focusedID = LiveStreamSelectionPolicy.focusAfterPublisherStarted(
                currentFocus: focusedID,
                publisherPath: session.sourcePath,
                activePublisherPaths: activePaths
            )
            if focusedID != previousFocus {
                AppleLog.info("Streams", "Focused publisher \(session.sourcePath), replacing \(previousFocus)")
            }
        }
        rejectedPaths.remove(path)
        AppleLog.info(
            "Streams",
            "Admitted \(session.sourcePath) as \(session.id) profile=\(session.controllerProfile) " +
                "networkSnapshotId=\(AppleNetworkDiagnosticCenter.shared.currentSnapshotID)"
        )
        return session
    }

    private func stop(path: String, publisherID: String?) -> AppleLiveStreamSession? {
        guard let session = matching(path) else { return nil }
        if let publisherID, let current = session.publisherConnectionID, publisherID != current { return nil }
        session.model.handleMediaServerEvent(.streamStopped(path: session.sourcePath, publisherConnectionID: publisherID))
        if LiveStreamDecoderLifecyclePolicy.shouldResetAfterPublisherStopped(
            sessionPath: session.sourcePath,
            decoderPath: session.model.activeSourcePath
        ) {
            session.model.stop()
            AppleLog.info("Streams", "Reset decoder after publisher stopped path=\(session.sourcePath)")
        }
        session.state = .stopped
        session.publisherConnectionID = nil
        session.changedAt = Date()
        if session.id != "demo" {
            stopObservingManagedPresence(for: session)
            sessions.removeAll { $0.id == session.id }
            if focusedID == session.id { focusedID = sessions.first?.id ?? "demo" }
        }
        rejectedPaths.remove(path)
        return session
    }

    private func startDecoderIfNeeded(for session: AppleLiveStreamSession?) {
        guard let session, let url = session.endpoint.loopbackHlsURL else { return }
        let shouldStart = LiveStreamDecoderLifecyclePolicy.shouldStartDecoder(
            publisherPath: session.sourcePath,
            decoderPath: session.model.activeSourcePath,
            decoderIsIdle: session.model.state == .idle
        )
        guard shouldStart else { return }
        if session.model.state != .idle {
            AppleLog.warning(
                "Streams",
                "Resetting decoder for new publisher path=\(session.sourcePath) previous=\(session.model.activeSourcePath ?? "unknown")"
            )
            session.model.stop()
        }
        session.model.start(url: url)
    }

    private func matching(_ path: String) -> AppleLiveStreamSession? {
        let normalized = path.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        return sessions.first { $0.sourcePath == normalized || $0.id == normalized }
    }

    private func pruneStale(now: Date = Date()) {
        let stale = sessions.filter {
            $0.id != "demo" && (($0.state == .connecting && now.timeIntervalSince($0.changedAt) > 30)
                || ($0.state == .error && now.timeIntervalSince($0.changedAt) > 120))
        }
        guard !stale.isEmpty else { return }
        let ids = Set(stale.map(\.id))
        stale.forEach { $0.model.stop() }
        stale.forEach(stopObservingManagedPresence)
        sessions.removeAll { ids.contains($0.id) }
        if ids.contains(focusedID) { focusedID = sessions.first?.id ?? "demo" }
    }

    private func observeManagedPresence(for session: AppleLiveStreamSession) {
        let key = ObjectIdentifier(session)
        presenceEligibility[key] = Self.isManagedPresenceEligible(session)
        presenceSubscriptions[key] = Publishers.CombineLatest3(
            session.$state,
            session.model.$frameCount,
            session.model.$decodedFrameAgeSeconds
        )
        .map { state, frameCount, decodedFrameAge in
            state == .live && ManagedVideoPresencePolicy.hasRecentDecodedFrame(
                frameCount: frameCount,
                decodedFrameAge: decodedFrameAge
            )
        }
        .removeDuplicates()
        .sink { [weak self, weak session] eligible in
            Task { @MainActor [weak self, weak session] in
                guard let self, let session else { return }
                let currentKey = ObjectIdentifier(session)
                guard self.presenceEligibility[currentKey] != eligible else { return }
                self.presenceEligibility[currentKey] = eligible
                self.managedPresenceRevision &+= 1
            }
        }
    }

    private func stopObservingManagedPresence(for session: AppleLiveStreamSession) {
        let key = ObjectIdentifier(session)
        presenceSubscriptions.removeValue(forKey: key)?.cancel()
        presenceEligibility.removeValue(forKey: key)
    }

    private static func isManagedPresenceEligible(_ session: AppleLiveStreamSession) -> Bool {
        session.state == .live && ManagedVideoPresencePolicy.hasRecentDecodedFrame(
            frameCount: session.model.frameCount,
            decodedFrameAge: session.model.decodedFrameAgeSeconds
        )
    }
}

struct AppleStreamsGridView: View {
    @ObservedObject var registry: AppleStreamRegistry
    @ObservedObject private var networkDiagnostics = AppleNetworkDiagnosticCenter.shared
    var ingestAddress: String? = nil
    var networkSSID: String? = nil
    var showsSetupHeader = true
    var showsNavigationTitle = true
    var expandedSessionID: String? = nil
    var onSelectSession: ((String) -> Void)? = nil
    var onLongPressSession: ((String) -> Void)? = nil
    var onDoubleTapSession: ((String, Double, CGPoint) -> Void)? = nil
    var onCloseSession: ((String) -> Void)? = nil
    var onRestartStreams: (() -> Void)? = nil
    var primaryLabel: ((String) -> String?)? = nil
    var telemetryText: ((String) -> String?)? = nil
    var coordinateText: ((String) -> String?)? = nil
    var remoteRequesterEmail: ((String) -> String?)? = nil
    var coordinateDisplayFormat: OperationalCoordinateDisplayFormat = .decimal
    var onCoordinateDisplayFormatChange: ((OperationalCoordinateDisplayFormat) -> Void)? = nil
    var telemetryPairingState: ((String) -> AppleStreamTelemetryPairingState) = { _ in .noTelemetry }
    var centerpointElevationFeet: ((String) async -> OperationalCenterpointElevation.Sample?)? = nil

    private var visibleSessions: [AppleLiveStreamSession] {
        let operational = registry.sessions.filter { $0.id != "demo" }
        let available = operational.isEmpty ? registry.sessions : operational
        guard let expandedSessionID,
              let expanded = available.first(where: { $0.id == expandedSessionID })
        else { return available }
        return [expanded]
    }

    private var currentNetworkSSID: String? {
        networkDiagnostics.currentWiFiSSID ?? networkSSID
    }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                if showsSetupHeader, let ingestAddress {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Controller RTMP setup").font(.caption.bold())
                        if ingestAddress.hasPrefix("rtmp://") {
                            Text("Example: \(ingestAddress)/DRONE1")
                                .font(.headline.monospaced())
                            if let currentNetworkSSID {
                                Text("Network: \(currentNetworkSSID)")
                                    .font(.subheadline)
                            }
                            Text("Replace DRONE1 with the aircraft designator. The controller and iPad must be on the same Wi-Fi network.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else {
                            Text(ingestAddress).font(.headline)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(.regularMaterial)
                }
                if visibleSessions.count == 1, let session = visibleSessions.first {
                    streamTile(session, fillsAvailableSpace: true)
                        .padding(6)
                } else {
                    GeometryReader { gridGeometry in
                        let columns = visibleSessions.count <= 2 ? 1 : 2
                        let rows = visibleSessions.count <= 1 ? 1 : 2
                        let availableHeight = max(
                            0,
                            gridGeometry.size.height - 12 - CGFloat(rows - 1) * 6
                        )
                        let cellHeight = availableHeight / CGFloat(rows)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: columns), spacing: 6) {
                            ForEach(visibleSessions) { session in
                                streamTile(session, fillsAvailableSpace: false)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: cellHeight)
                            }
                        }
                        .padding(6)
                    }
                }
            }
            .background(.black)
        }
        .modifier(StreamGridNavigationTitle(enabled: showsNavigationTitle))
    }

    private func streamTile(
        _ session: AppleLiveStreamSession,
        fillsAvailableSpace: Bool
    ) -> some View {
        AppleStreamTile(
            session: session,
            ingestAddress: ingestAddress,
            networkSSID: currentNetworkSSID,
            focused: registry.focusedID == session.id,
            fillsAvailableSpace: fillsAvailableSpace,
            primaryLabel: primaryLabel?(session.id),
            telemetryText: telemetryText?(session.id),
            coordinateText: coordinateText?(session.id),
            remoteRequesterEmail: remoteRequesterEmail?(session.id),
            coordinateDisplayFormat: coordinateDisplayFormat,
            telemetryPairingState: telemetryPairingState(session.id),
            centerpointElevation: centerpointElevationFeet.map { provider in
                { await provider(session.id) }
            },
            onCoordinateDisplayFormatChange: onCoordinateDisplayFormatChange,
            onFocus: {
                registry.focus(session.id)
                onSelectSession?(session.id)
            },
            onLongPress: {
                registry.focus(session.id)
                onLongPressSession?(session.id)
            },
            onDoubleTap: {
                registry.focus(session.id)
                onDoubleTapSession?(session.id, $0, $1)
            },
            onClose: onCloseSession.map { callback in
                { callback(session.id) }
            },
            onRestartStreams: onRestartStreams
        )
    }
}

enum AppleStreamTelemetryPairingState {
    case noTelemetry
    case available
    case paired

    var color: Color {
        switch self {
        case .noTelemetry: .red
        case .available: .yellow
        case .paired: .green
        }
    }
}

private struct StreamGridNavigationTitle: ViewModifier {
    let enabled: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if enabled {
            content
                .navigationTitle("Live Streams")
                .navigationBarTitleDisplayMode(.inline)
        } else {
            content
        }
    }
}

private struct AppleStreamTile: View {
    @ObservedObject var session: AppleLiveStreamSession
    @ObservedObject private var model: AppleVideoFrameSource
    @State private var showAnomalySettings = false
    @State private var showAnomalyHelp = false
    @State private var showPerformance = false
    @State private var zoom: CGFloat = 1
    @State private var zoomAtGestureStart: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var panAtGestureStart: CGSize = .zero
    @State private var tileSize: CGSize = .zero
    @State private var centerpointElevationEnabled = false
    @State private var centerpointElevationSample: OperationalCenterpointElevation.Sample?
    @State private var centerpointReferenceElevationFeet: Int?
    @State private var centerpointDisplayMode: OperationalCenterpointElevation.DisplayMode = .msl
    @State private var latestTouchLocation: CGPoint?
    let ingestAddress: String?
    let networkSSID: String?
    let focused: Bool
    let fillsAvailableSpace: Bool
    let primaryLabel: String?
    let telemetryText: String?
    let coordinateText: String?
    let remoteRequesterEmail: String?
    let coordinateDisplayFormat: OperationalCoordinateDisplayFormat
    let telemetryPairingState: AppleStreamTelemetryPairingState
    let centerpointElevation: (() async -> OperationalCenterpointElevation.Sample?)?
    let onCoordinateDisplayFormatChange: ((OperationalCoordinateDisplayFormat) -> Void)?
    let onFocus: () -> Void
    let onLongPress: () -> Void
    let onDoubleTap: (Double, CGPoint) -> Void
    let onClose: (() -> Void)?
    let onRestartStreams: (() -> Void)?

    init(
        session: AppleLiveStreamSession,
        ingestAddress: String?,
        networkSSID: String?,
        focused: Bool,
        fillsAvailableSpace: Bool,
        primaryLabel: String?,
        telemetryText: String?,
        coordinateText: String?,
        remoteRequesterEmail: String?,
        coordinateDisplayFormat: OperationalCoordinateDisplayFormat,
        telemetryPairingState: AppleStreamTelemetryPairingState,
        centerpointElevation: (() async -> OperationalCenterpointElevation.Sample?)?,
        onCoordinateDisplayFormatChange: ((OperationalCoordinateDisplayFormat) -> Void)?,
        onFocus: @escaping () -> Void,
        onLongPress: @escaping () -> Void,
        onDoubleTap: @escaping (Double, CGPoint) -> Void,
        onClose: (() -> Void)?,
        onRestartStreams: (() -> Void)?
    ) {
        self.session = session
        _model = ObservedObject(wrappedValue: session.model)
        self.ingestAddress = ingestAddress
        self.networkSSID = networkSSID
        self.focused = focused
        self.fillsAvailableSpace = fillsAvailableSpace
        self.primaryLabel = primaryLabel
        self.telemetryText = telemetryText
        self.coordinateText = coordinateText
        self.remoteRequesterEmail = remoteRequesterEmail
        self.coordinateDisplayFormat = coordinateDisplayFormat
        self.telemetryPairingState = telemetryPairingState
        self.centerpointElevation = centerpointElevation
        self.onCoordinateDisplayFormatChange = onCoordinateDisplayFormatChange
        self.onFocus = onFocus
        self.onLongPress = onLongPress
        self.onDoubleTap = onDoubleTap
        self.onClose = onClose
        self.onRestartStreams = onRestartStreams
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black
            GeometryReader { geometry in
                let displayRect = AnomalyConfigurationParity.aspectFitRect(
                    containerWidth: geometry.size.width,
                    containerHeight: geometry.size.height,
                    contentAspectRatio: model.videoAspectRatio
                )
                ZStack {
                    if model.usesNativeVideoSurface { AppleLiveVideoSurface(model: model) }
                    else if let player = model.player { VideoPlayer(player: player) }
                    else { waitingForController }
                    if model.anomalyMode != .off {
                        AnomalyBoxOverlay(boxes: model.anomalyBoxes)
                            .allowsHitTesting(false)
                        if model.anomalyConfiguration.showHotOverlay,
                           let hotOverlay = model.anomalyHotOverlay {
                            AnomalyHotOverlayView(overlay: hotOverlay)
                                .allowsHitTesting(false)
                        }
                        if model.anomalyConfiguration.showGuideBoxes {
                            AnomalyGuideOverlay(
                                scanZone: model.anomalyConfiguration.scanZone,
                                smallTargetScreenFraction: model.anomalyConfiguration.smallTargetScreenFraction
                            )
                            .allowsHitTesting(false)
                        }
                    }
                }
                .frame(
                    width: CGFloat(displayRect.width),
                    height: CGFloat(displayRect.height)
                )
                .position(
                    x: CGFloat(displayRect.x + displayRect.width / 2),
                    y: CGFloat(displayRect.y + displayRect.height / 2)
                )
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .scaleEffect(zoom)
            .offset(pan)
            .clipped()
            HStack {
                AppleLiveVideoIndicator(
                    model: model,
                    displayDesignator: primaryLabel,
                    tint: telemetryPairingState.color
                )
                Spacer()
                if focused || fillsAvailableSpace {
                    Menu {
                        Button("AD Mode: \(model.anomalyMode.label)") {
                            showAnomalySettings = true
                        }
                        Button("AD Help") {
                            showAnomalyHelp = true
                        }
                        Button("Performance…", systemImage: "gauge.with.dots.needle.67percent") {
                            showPerformance = true
                        }
                        if let onRestartStreams {
                            Button("Restart Streams Server", systemImage: "arrow.clockwise") {
                                onRestartStreams()
                            }
                        }
                        if let onClose {
                            Button("Close Stream", systemImage: "xmark.rectangle", role: .destructive) {
                                onClose()
                            }
                        }
                    } label: {
                        Image(systemName: "gearshape.fill")
                            .font(.body.bold())
                            .padding(8)
                            .background(.black.opacity(0.6), in: Circle())
                    }
                    .foregroundStyle(.white)
                    .accessibilityLabel(session.id == "demo" ? "Streams settings" : "Anomaly detection settings")
                }
                if focused { Image(systemName: "scope").foregroundStyle(.yellow) }
            }
            .padding(6)
            if telemetryText != nil || coordinateText != nil {
                VStack(alignment: .leading, spacing: 2) {
                    if let telemetryText {
                        Text(zoom > 1.01 ? "\(telemetryText)  \(zoomLabel)" : telemetryText)
                    }
                    if let coordinateText {
                        if let onCoordinateDisplayFormatChange {
                            Menu {
                                ForEach(OperationalCoordinateDisplayFormat.allCases) { format in
                                    Button(format.label) {
                                        onCoordinateDisplayFormatChange(format)
                                    }
                                }
                            } label: {
                                Text("\(coordinateText) (\(coordinateDisplayFormat.label))")
                                    .underline()
                            }
                            .accessibilityLabel("Coordinate format: \(coordinateDisplayFormat.label)")
                        } else {
                            Text("\(coordinateText) (\(coordinateDisplayFormat.label))")
                        }
                    }
                }
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.white)
                .padding(.horizontal, 7)
                .padding(.vertical, 4)
                .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 5))
                .padding(6)
                .padding(.top, 32)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            if remoteRequesterEmail != nil || model.anomalyThermallySuspended {
                VStack(alignment: .leading, spacing: 4) {
                    if let remoteRequesterEmail {
                        Label(
                            "Requested by \(remoteRequesterEmail)",
                            systemImage: "person.crop.circle.badge.checkmark"
                        )
                        .foregroundStyle(.white)
                        .accessibilityLabel("Video requested by \(remoteRequesterEmail)")
                    }
                    if model.anomalyThermallySuspended {
                        Label("AD paused: iPad temperature", systemImage: "thermometer.high")
                            .foregroundStyle(.yellow)
                    }
                }
                .font(.caption.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.horizontal, 7)
                .padding(.vertical, 5)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 5))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                .padding(6)
            }
            if centerpointElevationEnabled, focused {
                AppleCenterpointElevationOverlay(
                    sample: centerpointElevationSample,
                    referenceElevationFeet: centerpointReferenceElevationFeet,
                    displayMode: centerpointDisplayMode,
                    onToggleDisplayMode: {
                        guard centerpointReferenceElevationFeet != nil else { return }
                        centerpointDisplayMode = centerpointDisplayMode == .msl ? .reference : .msl
                    },
                    onSetReference: {
                        if let sample = centerpointElevationSample {
                            centerpointReferenceElevationFeet = sample.elevationFeet
                            centerpointDisplayMode = .reference
                        }
                    }
                )
            }
        }
        .modifier(StreamTileSizing(
            fillsAvailableSpace: fillsAvailableSpace,
            aspectRatio: model.videoAspectRatio
        ))
        .background {
            GeometryReader { geometry in
                Color.clear
                    .onAppear { tileSize = geometry.size }
                    .onChange(of: geometry.size) { _, size in
                        tileSize = size
                        pan = clampedPan(pan, scale: zoom, size: size)
                        panAtGestureStart = pan
                    }
            }
        }
        .overlay(RoundedRectangle(cornerRadius: 5).stroke(focused ? .yellow : .gray, lineWidth: focused ? 3 : 1))
        .contentShape(Rectangle())
        .simultaneousGesture(
            MagnificationGesture()
                .onChanged { value in
                    zoom = min(4, max(1, zoomAtGestureStart * value))
                    pan = clampedPan(pan, scale: zoom, size: tileSize)
                }
                .onEnded { _ in
                    zoomAtGestureStart = zoom
                    panAtGestureStart = pan
                }
        )
        .simultaneousGesture(
            DragGesture(minimumDistance: 8)
                .onChanged { value in
                    guard zoom > 1 else { return }
                    pan = clampedPan(CGSize(
                        width: panAtGestureStart.width + value.translation.width,
                        height: panAtGestureStart.height + value.translation.height
                    ), scale: zoom, size: tileSize)
                }
                .onEnded { _ in
                    panAtGestureStart = pan
                }
        )
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    latestTouchLocation = value.startLocation
                }
        )
        .onTapGesture(count: 2) {
            onDoubleTap(
                Double(zoom),
                CGPoint(
                    x: tileSize.width > 0 ? pan.width / tileSize.width : 0,
                    y: tileSize.height > 0 ? pan.height / tileSize.height : 0
                )
            )
        }
        .onTapGesture { location in
            let minimumDimension = min(tileSize.width, tileSize.height)
            let radius = min(96, max(48, minimumDimension * 0.20))
            if focused, OperationalCenterpointElevation.isNearCenter(
                x: location.x,
                y: location.y,
                width: tileSize.width,
                height: tileSize.height,
                radius: radius
            ) {
                centerpointElevationEnabled.toggle()
                if centerpointElevationEnabled {
                    centerpointElevationSample = nil
                    zoom = 1
                    zoomAtGestureStart = 1
                    pan = .zero
                    panAtGestureStart = .zero
                }
            } else {
                onFocus()
            }
        }
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.5)
                .onEnded { _ in
                    let minimumDimension = min(tileSize.width, tileSize.height)
                    let radius = min(96, max(48, minimumDimension * 0.20))
                    let pressNearCenter = latestTouchLocation.map {
                        OperationalCenterpointElevation.isNearCenter(
                            x: $0.x,
                            y: $0.y,
                            width: tileSize.width,
                            height: tileSize.height,
                            radius: radius
                        )
                    } ?? false
                    if OperationalCenterpointElevation.shouldSetReference(
                        focused: focused,
                        elevationEnabled: centerpointElevationEnabled,
                        pressNearCenter: pressNearCenter
                    ) {
                        if let sample = centerpointElevationSample {
                            centerpointReferenceElevationFeet = sample.elevationFeet
                            centerpointDisplayMode = .reference
                        }
                    } else {
                        onLongPress()
                    }
                }
        )
        .onChange(of: focused) { _, isFocused in
            if !isFocused {
                centerpointElevationEnabled = false
                centerpointElevationSample = nil
            }
        }
        .task(id: centerpointElevationEnabled && focused) {
            guard centerpointElevationEnabled, focused, let centerpointElevation else { return }
            while !Task.isCancelled {
                if let updated = await centerpointElevation(), updated != centerpointElevationSample {
                    centerpointElevationSample = updated
                }
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
        .sheet(isPresented: $showAnomalySettings) {
            NavigationStack {
                AppleAnomalySettingsView(model: model)
                    .navigationTitle("Anomaly Detector")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showAnomalySettings = false }
                        }
                    }
            }
        }
        .sheet(isPresented: $showPerformance) {
            NavigationStack {
                StreamPerformanceView(session: session, model: model)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showPerformance = false }
                }
            }
        }
        .sheet(isPresented: $showAnomalyHelp) {
            NavigationStack {
                AppleAnomalyHelpView()
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showAnomalyHelp = false }
                        }
                    }
            }
        }
        }
    }

    private var waitingForController: some View {
        VStack(spacing: 10) {
            Image(systemName: "video.slash")
                .font(.largeTitle)
            Text("Waiting for controller to connect")
                .font(.headline)
            if session.id == "demo",
               let ingestAddress,
               ingestAddress.hasPrefix("rtmp://") {
                Text(OperationalStreamSetupPresentation.instruction(
                    ingestAddress: ingestAddress,
                    networkSSID: networkSSID
                ))
                    .font(.subheadline.monospaced())
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)
            }
        }
        .foregroundStyle(.white)
        .padding()
    }

    private var zoomLabel: String {
        zoom >= 3.95 ? "4x" : String(format: "%.1fx", zoom)
    }

    private func clampedPan(_ candidate: CGSize, scale: CGFloat, size: CGSize) -> CGSize {
        guard scale > 1.001, size.width > 0, size.height > 0 else { return .zero }
        let maximumX = size.width * (scale - 1) / 2
        let maximumY = size.height * (scale - 1) / 2
        return CGSize(
            width: min(maximumX, max(-maximumX, candidate.width)),
            height: min(maximumY, max(-maximumY, candidate.height))
        )
    }
}

private struct AppleCenterpointElevationOverlay: View {
    let sample: OperationalCenterpointElevation.Sample?
    let referenceElevationFeet: Int?
    let displayMode: OperationalCenterpointElevation.DisplayMode
    let onToggleDisplayMode: () -> Void
    let onSetReference: () -> Void
    private let turquoise = Color(red: 64 / 255, green: 224 / 255, blue: 208 / 255)

    var body: some View {
        GeometryReader { geometry in
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
            Canvas { context, _ in
                let arm: CGFloat = 18
                let gap: CGFloat = 4
                let segments = [
                    (CGPoint(x: center.x - arm, y: center.y), CGPoint(x: center.x - gap, y: center.y)),
                    (CGPoint(x: center.x + gap, y: center.y), CGPoint(x: center.x + arm, y: center.y)),
                    (CGPoint(x: center.x, y: center.y - arm), CGPoint(x: center.x, y: center.y - gap)),
                    (CGPoint(x: center.x, y: center.y + gap), CGPoint(x: center.x, y: center.y + arm)),
                ]
                for (start, end) in segments {
                    var path = Path()
                    path.move(to: start)
                    path.addLine(to: end)
                    context.stroke(path, with: .color(.black), lineWidth: 4)
                    context.stroke(path, with: .color(turquoise), lineWidth: 1.5)
                }
                let circle = Path(ellipseIn: CGRect(
                    x: center.x - 3.5,
                    y: center.y - 3.5,
                    width: 7,
                    height: 7
                ))
                context.stroke(circle, with: .color(.black), lineWidth: 2.5)
                context.stroke(circle, with: .color(turquoise), lineWidth: 1)
            }
            .allowsHitTesting(false)
            Button(action: onToggleDisplayMode) {
                OutlinedCenterpointText(
                    text: OperationalCenterpointElevation.displayText(
                        sample,
                        referenceElevationFeet: referenceElevationFeet,
                        mode: displayMode
                    ),
                    color: turquoise
                )
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5)
                    .onEnded { _ in onSetReference() }
            )
            .position(x: center.x + 84, y: center.y + 30)
            .accessibilityLabel(displayMode == .reference ? "Reference elevation" : "MSL elevation")
            .accessibilityHint(referenceElevationFeet == nil ? "Long press the crosshair to set a reference" : "Toggles MSL and reference elevation")
        }
    }
}

private struct OutlinedCenterpointText: View {
    let text: String
    let color: Color
    private let offsets: [CGSize] = [
        .init(width: -1, height: -1), .init(width: 0, height: -1), .init(width: 1, height: -1),
        .init(width: -1, height: 0), .init(width: 1, height: 0),
        .init(width: -1, height: 1), .init(width: 0, height: 1), .init(width: 1, height: 1),
    ]

    var body: some View {
        ZStack {
            ForEach(Array(offsets.enumerated()), id: \.offset) { _, offset in
                Text(text).offset(offset).foregroundStyle(.black)
            }
            Text(text).foregroundStyle(color)
        }
        .font(.system(size: 17, weight: .bold, design: .rounded))
    }
}

private struct StreamTileSizing: ViewModifier {
    let fillsAvailableSpace: Bool
    let aspectRatio: Double

    @ViewBuilder
    func body(content: Content) -> some View {
        if fillsAvailableSpace {
            content.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            content.aspectRatio(aspectRatio, contentMode: .fit)
        }
    }
}

private struct StreamPerformanceView: View {
    @ObservedObject var session: AppleLiveStreamSession
    @ObservedObject var model: AppleVideoFrameSource

    var body: some View {
        Form {
            Section("Stream") {
                LabeledContent("Designator", value: session.id)
                LabeledContent("Profile", value: session.controllerProfile)
                LabeledContent("Publisher", value: model.mediaPublisherStatus)
                LabeledContent("State", value: session.state.rawValue.capitalized)
                if let detail = session.errorDetail {
                    LabeledContent("Error", value: detail)
                }
            }
            Section("Decoder") {
                LabeledContent("Backend", value: model.decoderBackend)
                LabeledContent("Frame size", value: model.dimensions)
                LabeledContent("Decoded frames", value: model.frameCount.formatted())
                LabeledContent(
                    "Lag",
                    value: LiveVideoLagEstimator.label(milliseconds: model.renderDelayMilliseconds)
                )
                LabeledContent("Recoveries", value: model.recoveryCount.formatted())
                LabeledContent("Last recovery", value: model.lastRecoveryReason)
            }
            Section("Anomaly detector") {
                LabeledContent("Mode", value: model.anomalyMode.label)
                LabeledContent("Analyzed", value: model.analyzedFrameCount.formatted())
                LabeledContent("Dropped", value: model.droppedAnalysisFrameCount.formatted())
                LabeledContent("Boxes", value: model.anomalyCount.formatted())
                LabeledContent("Thermal suspension", value: model.anomalyThermallySuspended ? "Active" : "No")
            }
        }
        .navigationTitle("Performance")
        .navigationBarTitleDisplayMode(.inline)
    }
}

enum AppleExternalDisplayMode: String, CaseIterable, Identifiable {
    case off, appManaged, osMirroring
    var id: String { rawValue }
    var label: String { switch self { case .off: "Off"; case .appManaged: "App-managed"; case .osMirroring: "Use OS mirroring" } }
}

enum AppleExternalDisplayContent: String, CaseIterable, Identifiable {
    case streamsGrid, mapOnly, split, observer
    var id: String { rawValue }
    var label: String { switch self { case .streamsGrid: "Streams Grid"; case .mapOnly: "Map Only"; case .split: "Split: Streams + Map"; case .observer: "Observer Mode" } }
}

enum AppleExternalAlertRouting: String, CaseIterable, Identifiable {
    case phoneOnly, externalOnly, both
    var id: String { rawValue }
    var label: String { switch self { case .phoneOnly: "Phone only"; case .externalOnly: "External display only"; case .both: "Both" } }
}

@MainActor
final class AppleExternalDisplaySettings: ObservableObject {
    static let shared = AppleExternalDisplaySettings()
    @Published var mode: String { didSet { defaults.set(mode, forKey: "external.mode") } }
    @Published var content: String { didSet { defaults.set(content, forKey: "external.content") } }
    @Published var alertRouting: String { didSet { defaults.set(alertRouting, forKey: "external.alertRouting") } }
    @Published var autoOpen: Bool { didSet { defaults.set(autoOpen, forKey: "external.autoOpen") } }
    @Published var allowInteraction: Bool { didSet { defaults.set(allowInteraction, forKey: "external.allowInteraction") } }
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        mode = defaults.string(forKey: "external.mode") ?? AppleExternalDisplayMode.osMirroring.rawValue
        content = defaults.string(forKey: "external.content") ?? AppleExternalDisplayContent.streamsGrid.rawValue
        alertRouting = defaults.string(forKey: "external.alertRouting") ?? AppleExternalAlertRouting.both.rawValue
        autoOpen = defaults.object(forKey: "external.autoOpen") as? Bool ?? true
        allowInteraction = defaults.object(forKey: "external.allowInteraction") as? Bool ?? true
    }
}

@MainActor
final class AppleExternalDisplayData: ObservableObject {
    static let shared = AppleExternalDisplayData()
    @Published var aircraft: [(id: String, coordinate: CLLocationCoordinate2D)] = []
    @Published var alertText: String?

    func update(tracks: [RidAircraftTrack], alertText: String?) {
        aircraft = tracks.map { ($0.aircraftID, CLLocationCoordinate2D(latitude: $0.lastObservation.latitude, longitude: $0.lastObservation.longitude)) }
        self.alertText = alertText
    }
}

struct AppleExternalDisplayView: View {
    @ObservedObject private var settings = AppleExternalDisplaySettings.shared
    @ObservedObject private var registry = AppleStreamRegistry.shared
    @ObservedObject private var data = AppleExternalDisplayData.shared

    var body: some View {
        ZStack(alignment: .top) {
            content
            if let alert = data.alertText,
               AppleExternalAlertRouting(rawValue: settings.alertRouting) != .phoneOnly {
                Label(alert, systemImage: "exclamationmark.triangle.fill")
                    .font(.title2.bold()).padding().background(.red.opacity(0.9)).foregroundStyle(.white).clipShape(RoundedRectangle(cornerRadius: 12)).padding()
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch AppleExternalDisplayContent(rawValue: settings.content) ?? .streamsGrid {
        case .streamsGrid, .observer: AppleStreamsGridView(registry: registry)
        case .mapOnly: externalMap
        case .split: HStack(spacing: 1) { AppleStreamsGridView(registry: registry); externalMap }
        }
    }

    private var externalMap: some View {
        Map {
            ForEach(data.aircraft, id: \.id) { item in
                Annotation(item.id, coordinate: item.coordinate) { Image(systemName: "airplane").padding(8).background(.red).foregroundStyle(.white).clipShape(Circle()) }
            }
        }
    }
}

@MainActor
final class RID2CaltopoAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(primarySceneDidDisconnect(_:)),
            name: UIScene.didDisconnectNotification,
            object: nil
        )
        return true
    }

    @objc
    private func primarySceneDidDisconnect(_ notification: Notification) {
        guard
            let scene = notification.object as? UIScene,
            scene.session.role == .windowApplication
        else { return }
        AppleApplicationCleanupCenter.shared.closePrimaryWindow(
            reason: "window disconnected"
        )
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        Task { @MainActor in
            AppleApplicationCleanupCenter.shared.removeMarkerForBackgrounding()
        }
    }

    func applicationWillTerminate(_ application: UIApplication) {
        Task { @MainActor in
            AppleApplicationCleanupCenter.shared.closePrimaryWindow(
                reason: "application terminating"
            )
        }
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        if connectingSceneSession.role == .windowExternalDisplayNonInteractive {
            configuration.delegateClass = AppleExternalDisplaySceneDelegate.self
        }
        return configuration
    }
}

final class AppleExternalDisplaySceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        Task { @MainActor in
            let settings = AppleExternalDisplaySettings.shared
            guard AppleExternalDisplayMode(rawValue: settings.mode) == .appManaged else { return }
            let window = UIWindow(windowScene: windowScene)
            window.rootViewController = UIHostingController(rootView: AppleExternalDisplayView())
            window.isUserInteractionEnabled = settings.allowInteraction
            window.makeKeyAndVisible()
            self.window = window
            AppleLog.info("ExternalDisplay", "App-managed external display connected")
        }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        Task { @MainActor in AppleLog.info("ExternalDisplay", "External display disconnected") }
        window = nil
    }
}
