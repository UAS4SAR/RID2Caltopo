import Darwin
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
    private var manuallyClosedPublisherIDs: Set<String> = []
    private var manuallyClosedPaths: Set<String> = []

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
            if manuallyClosedPaths.contains(path) { break }
            admit(path: path, state: .connecting, publisherID: nil)?.model.handleMediaServerEvent(event)
        case let .streamStarted(path, publisherID), let .streamPublisherHandoff(path, publisherID):
            if let publisherID, manuallyClosedPublisherIDs.contains(publisherID) {
                AppleLog.info("Streams", "Ignoring publisher callback for manually closed connection path=\(path)")
                break
            }
            manuallyClosedPaths.remove(path)
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
            if let publisherID { manuallyClosedPublisherIDs.remove(publisherID) }
            manuallyClosedPaths.remove(path)
            if let stoppedSession = stop(path: path, publisherID: publisherID) {
                seiPositionContinuationByStreamID.removeValue(forKey: stoppedSession.id)
                if let receivedAt = stoppedSession.model.latestDJICameraTelemetry?.receivedAt {
                    flightActivity.telemetryReceived(streamID: stoppedSession.id, at: receivedAt)
                }
                flightActivity.publisherStopped(streamID: stoppedSession.id, at: observedAt)
            }
        case let .streamError(path, publisherID, detail):
            guard let path else { return }
            if let publisherID { manuallyClosedPublisherIDs.remove(publisherID) }
            manuallyClosedPaths.remove(path)
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

    func pairConfiguredPublishers(mappings: [(remoteID: String, designator: String)]) {
        let added = flightActivity.pairConfiguredPublishers(mappings: mappings)
        guard !added.isEmpty else { return }
        managedPresenceRevision &+= 1
        objectWillChange.send()
        for (stream, aircraft) in added {
            AppleLog.info("Streams", "Configured video telemetry binding stream=\(stream) aircraft=\(aircraft)")
        }
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
        for session in sessions where flightActivity.isPublisherActive(streamID: session.id) {
            if let receivedAt = session.model.latestDJICameraTelemetry?.receivedAt {
                flightActivity.telemetryReceived(streamID: session.id, at: receivedAt)
            }
        }
        return flightActivity.activityByAircraftID(at: date)
    }

    func djiSEILastActivityByAircraftID() -> [String: Date] {
        sessions.reduce(into: [:]) { result, session in
            guard let aircraftID = flightActivity.boundAircraftID(for: session.id),
                  let receivedAt = session.model.latestDJICameraTelemetry?.receivedAt
            else { return }
            result[aircraftID] = max(result[aircraftID] ?? .distantPast, receivedAt)
        }
    }

    /// Latest SEI relative-up (metres) per bound aircraft for own-ship altitude alerts
    /// when RID/BLE AGL is stale. Not used for proximity between aircraft.
    func djiSEIRelativeUpMetersByAircraftID() -> [String: Double] {
        var bestAt: [String: Date] = [:]
        var result: [String: Double] = [:]
        for session in sessions {
            guard let aircraftID = flightActivity.boundAircraftID(for: session.id),
                  let telemetry = session.model.latestDJICameraTelemetry,
                  let up = telemetry.relativeUpMeters, up.isFinite
            else { continue }
            if let existing = bestAt[aircraftID], existing >= telemetry.receivedAt { continue }
            bestAt[aircraftID] = telemetry.receivedAt
            result[aircraftID] = up
        }
        return result
    }

    /** Once true, operational consumers must not silently downgrade this stream to RID. */
    func isSEIPositionAuthorityEstablished(streamID: String) -> Bool {
        seiPositionContinuationByStreamID[streamID]?.positionValidated == true
    }

    func freshOperationalDJIPositionByAircraftID(
        tracks: [RidAircraftTrack],
        at date: Date = Date(),
        maximumAge: TimeInterval = 3
    ) -> [String: AppleDJICameraTelemetry] {
        // The configured stream binding supplies identity. Complete DJI position/height
        // is an independent source and does not require a previous RID track.
        var result: [String: AppleDJICameraTelemetry] = [:]
        for session in sessions {
            guard flightActivity.isPublisherActive(streamID: session.id),
                  let aircraftID = flightActivity.boundAircraftID(for: session.id),
                  let telemetry = session.model.freshDJICameraTelemetry(now: date, maximumAge: maximumAge),
                  let lat = telemetry.latitudeDegrees, let lng = telemetry.longitudeDegrees,
                  lat.isFinite, lng.isFinite, (-90...90).contains(lat), (-180...180).contains(lng),
                  lat != 0, lng != 0,
                  let height = telemetry.relativeUpMeters, height.isFinite,
                  (-1000...30000).contains(height)
            else { continue }
            if let existing = result[aircraftID], existing.receivedAt >= telemetry.receivedAt { continue }
            result[aircraftID] = telemetry
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
        AppleLog.info("Streams", "Operator close requested stream \(session.sourcePath)")
        if let publisherID = session.publisherConnectionID {
            manuallyClosedPublisherIDs.insert(publisherID)
        }
        manuallyClosedPaths.insert(session.sourcePath)
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

    /// Remove a tile when MediaMTX missed the publisher-stop event but the
    /// server no longer reports its publisher. A decoder stall alone does not
    /// establish publisher loss. This keeps a dead stream
    /// from remaining onscreen forever in its reconnect loop.
    func reconcileStaleSessions(activePublisherPaths: Set<String>, now: Date = Date()) {
        let stale = sessions.filter { session in
            guard session.id != Self.placeholderID else { return false }
            let decoderLost: Bool
            switch session.model.state {
            case .failed, .waitingForPublisher:
                decoderLost = true
            default:
                decoderLost = false
            }
            guard decoderLost else { return false }
            return LiveStreamDecoderLifecyclePolicy.shouldPruneStaleSession(
                publisherPath: session.sourcePath, activePublisherPaths: activePublisherPaths,
                decoderLost: decoderLost, frameAge: session.model.decodedFrameAgeSeconds,
                sessionAge: now.timeIntervalSince(session.changedAt))
        }
        for session in stale {
            AppleLog.warning(
                "Streams",
                "Pruning stale stream after publisher event was missed path=\(session.sourcePath)"
            )
            close(session.id)
        }
    }

    func shutdown() {
        sessions.forEach { $0.model.stop() }
        presenceSubscriptions.values.forEach { $0.cancel() }
        presenceSubscriptions.removeAll()
        presenceEligibility.removeAll()
        rejectedPaths.removeAll()
        manuallyClosedPublisherIDs.removeAll()
        manuallyClosedPaths.removeAll()
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
    var incidentMapTitle: String? = nil
    var onIncidentMapTap: (() -> Void)? = nil
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
    var onCalibrationRequested: ((String) -> Void)? = nil
    var coordinateText: ((String) -> String?)? = nil
    var remoteRequesterEmail: ((String) -> String?)? = nil
    var coordinateDisplayFormat: OperationalCoordinateDisplayFormat = .decimal
    var onCoordinateDisplayFormatChange: ((OperationalCoordinateDisplayFormat) -> Void)? = nil
    var telemetryPairingState: ((String) -> AppleStreamTelemetryPairingState) = { _ in .noTelemetry }
    var centerpointElevationFeet: ((String) async -> OperationalCenterpointElevation.Sample?)? = nil
    var registeredDroneDesignators: [String] = []
    var aircraftDetailsView: (() -> AnyView)? = nil
    var operationalAlertOverlay: AnyView? = nil
    @State private var showRegisteredDesignators = false
    @State private var showAircraftDetails = false

    private var visibleSessions: [AppleLiveStreamSession] {
        let operational = registry.sessions.filter { $0.id != "demo" }
        let available = operational.isEmpty ? registry.sessions : operational
        guard let expandedSessionID,
              let expanded = available.first(where: { $0.id == expandedSessionID })
        else { return available }
        return [expanded]
    }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                if showsSetupHeader {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Controller RTMP setup").font(.caption.bold())
                        AppleControllerConnectionURLs(onDesignatorsTapped: {
                            showRegisteredDesignators = true
                        }).font(.headline.monospaced())
                        Text("Replace droneDesig with the aircraft designator. Use the address for the network connecting this device and the controller.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
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
        .onChange(of: visibleSessions.map(\.id), initial: true) { _, ids in
            if ids.count == 1, let id = ids.first, registry.focusedID != id {
                registry.focus(id)
            }
        }
        .modifier(StreamGridNavigationTitle(enabled: showsNavigationTitle))
        .sheet(isPresented: $showRegisteredDesignators) {
            RegisteredDroneDesignatorsView(
                values: registeredDroneDesignators,
                onAddAircraft: aircraftDetailsView.map { _ in
                    {
                        showRegisteredDesignators = false
                        showAircraftDetails = true
                    }
                }
            )
        }
        .sheet(isPresented: $showAircraftDetails) {
            aircraftDetailsView?() ?? AnyView(EmptyView())
        }
    }

    private func streamTile(
        _ session: AppleLiveStreamSession,
        fillsAvailableSpace: Bool
    ) -> some View {
        let focus = LiveStreamSelectionPolicy.tileFocusPresentation(
            displayedTileCount: visibleSessions.count,
            explicitlyFocused: registry.focusedID == session.id
        )
        return AppleStreamTile(
            session: session,
            incidentMapTitle: incidentMapTitle,
            onIncidentMapTap: onIncidentMapTap,
            onDesignatorsTapped: {
                showRegisteredDesignators = true
            },
            focused: focus.effectiveFocused,
            showFocusBorder: focus.showFocusBorder,
            fillsAvailableSpace: fillsAvailableSpace,
            primaryLabel: primaryLabel?(session.id),
            telemetryText: telemetryText?(session.id),
            onCalibrationRequested: onCalibrationRequested.map { callback in { callback(session.id) } },
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
            onRestartStreams: onRestartStreams,
            operationalAlertOverlay: operationalAlertOverlay
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
    @ObservedObject private var network = AppleNetworkDiagnosticCenter.shared
    @State private var zoom: CGFloat = 1
    @State private var zoomAtGestureStart: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var panAtGestureStart: CGSize = .zero
    @State private var tileSize: CGSize = .zero
    @State private var centerpointElevationEnabled = false
    @State private var centerpointElevationSample: OperationalCenterpointElevation.Sample?
    @State private var centerpointReferenceElevationFeet: Int?
    @State private var centerpointDisplayMode: OperationalCenterpointElevation.DisplayMode = .msl
    let incidentMapTitle: String?
    let onIncidentMapTap: (() -> Void)?
    let onDesignatorsTapped: (() -> Void)?
    let focused: Bool
    let showFocusBorder: Bool
    let fillsAvailableSpace: Bool
    let primaryLabel: String?
    let telemetryText: String?
    let onCalibrationRequested: (() -> Void)?
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
    let operationalAlertOverlay: AnyView?

    init(
        session: AppleLiveStreamSession,
        incidentMapTitle: String?,
        onIncidentMapTap: (() -> Void)?,
        onDesignatorsTapped: (() -> Void)? = nil,
        focused: Bool,
        showFocusBorder: Bool,
        fillsAvailableSpace: Bool,
        primaryLabel: String?,
        telemetryText: String?,
        onCalibrationRequested: (() -> Void)? = nil,
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
        onRestartStreams: (() -> Void)?,
        operationalAlertOverlay: AnyView? = nil
    ) {
        self.session = session
        self.incidentMapTitle = incidentMapTitle
        self.onIncidentMapTap = onIncidentMapTap
        _model = ObservedObject(wrappedValue: session.model)
        self.onDesignatorsTapped = onDesignatorsTapped
        self.focused = focused
        self.showFocusBorder = showFocusBorder
        self.fillsAvailableSpace = fillsAvailableSpace
        self.primaryLabel = primaryLabel
        self.telemetryText = telemetryText
        self.onCalibrationRequested = onCalibrationRequested
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
        self.operationalAlertOverlay = operationalAlertOverlay
    }

    var body: some View {
        VStack(spacing: 0) {
            AppleVideoSafetyNotice()
            telemetryStrip
            videoContent
        }
    }

    @Environment(\.colorScheme) private var colorScheme
    @ScaledMetric(relativeTo: .caption2) private var telemetryFontSize = 10.0

    private var telemetryStrip: some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal) {
                HStack(spacing: 12) {
                    AppleLiveVideoIndicator(model: model, displayDesignator: primaryLabel, tint: telemetryPairingState.color)
                    if let telemetryText {
                        let metrics = telemetryText.range(of: "ATO:").map { String(telemetryText[$0.lowerBound...]) } ?? telemetryText
                        let line = stableVideoTelemetryText(metrics) + (zoom > 1.01 ? "  \(zoomLabel)" : "")
                        if line.contains("CAL") {
                            Button { onCalibrationRequested?() } label: { Text(line) }
                                .buttonStyle(.plain)
                        } else { Text(line) }
                    }
                    if let coordinateText {
                        if let onCoordinateDisplayFormatChange {
                            Menu {
                                ForEach(OperationalCoordinateDisplayFormat.allCases) { format in
                                    Button(format.label) { onCoordinateDisplayFormatChange(format) }
                                }
                            } label: {
                                Text("\(coordinateText) (\(coordinateDisplayFormat.label))").underline()
                            }
                            .accessibilityLabel("Coordinate format: \(coordinateDisplayFormat.label)")
                        } else {
                            Text("\(coordinateText) (\(coordinateDisplayFormat.label))")
                        }
                    }
                }
                .font(.system(size: telemetryFontSize, design: .monospaced))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
            }
            .scrollIndicators(.visible)
            .foregroundStyle(.white)
            .tint(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            if focused || fillsAvailableSpace {
                AppleStreamSettingsControl(
                    session: session, model: model, anomalyModeLabel: model.anomalyMode.label,
                    onClose: onClose, onRestartStreams: onRestartStreams, onDesignatorsTapped: onDesignatorsTapped)
                    .padding(.trailing, 4)
            }
        }
        .background(.black)
    }

    private var videoContent: some View {
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
            // One input surface above native video and below all actual controls.
            AppleStreamGestureSurface(
                onTap: handleStreamTap,
                onDoubleTap: { location in
                    AppleLog.info("StreamGesture", "Double tap stream=\(session.id) x=\(Int(location.x)) y=\(Int(location.y))")
                    onDoubleTap(Double(zoom), CGPoint(
                        x: tileSize.width > 0 ? pan.width / tileSize.width : 0,
                        y: tileSize.height > 0 ? pan.height / tileSize.height : 0))
                },
                onLongPress: handleStreamLongPress,
                onPinch: { scale, ended in
                    zoom = min(4, max(1, zoomAtGestureStart * scale))
                    pan = clampedPan(pan, scale: zoom, size: tileSize)
                    if ended { zoomAtGestureStart = zoom; panAtGestureStart = pan }
                },
                onPan: { translation, ended in
                    guard zoom > 1 else { return }
                    pan = clampedPan(CGSize(width: panAtGestureStart.width + translation.x,
                                           height: panAtGestureStart.height + translation.y), scale: zoom, size: tileSize)
                    if ended { panAtGestureStart = pan }
                }
            )
            .allowsHitTesting(session.id != "demo")
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
            if focused {
                AppleCenterpointElevationOverlay(
                    sample: centerpointElevationSample,
                    showLabel: centerpointElevationEnabled,
                    coordinateFormat: coordinateDisplayFormat,
                    referenceElevationFeet: centerpointReferenceElevationFeet,
                    displayMode: centerpointDisplayMode

                )
            }
        }
        .overlay(alignment: .topLeading) {
            if focused, let operationalAlertOverlay { operationalAlertOverlay }
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
        .overlay(RoundedRectangle(cornerRadius: 5).stroke(showFocusBorder ? .yellow : .clear, lineWidth: showFocusBorder ? 3 : 0))
        .contentShape(Rectangle())
        .onChange(of: focused) { _, isFocused in
            if !isFocused {
                centerpointElevationEnabled = false
                centerpointElevationSample = nil
            }
        }
        .onChange(of: centerpointElevationEnabled && focused, initial: true) { _, enabled in
            model.crosshairReadoutActive = enabled
        }
        .task(id: centerpointElevationEnabled && focused) {
            guard centerpointElevationEnabled, focused, let centerpointElevation else { return }
            while !Task.isCancelled {
                let updated = await centerpointElevation()
                if updated != centerpointElevationSample { centerpointElevationSample = updated }
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
    }

    private var waitingForController: some View {
        VStack(spacing: 10) {
            Image(systemName: "video.slash")
                .font(.largeTitle)
            Text("Network: \(network.currentControllerConnectionLabel)")
            Text("Waiting for controller to connect")
                .font(.headline)
            if session.id == "demo" {
                AppleControllerConnectionURLs(onDesignatorsTapped: onDesignatorsTapped)
                    .font(.subheadline.monospaced())
                    .multilineTextAlignment(.center)
                if let incidentMapTitle, let onIncidentMapTap {
                    Button(action: onIncidentMapTap) {
                        Text("Incident Map: \(incidentMapTitle)")
                            .font(.subheadline)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .frame(maxWidth: 280)
                            .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(.gray))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 2)
                }
            }
        }
        .foregroundStyle(.white)
        .padding()
    }

    private var zoomLabel: String {
        zoom >= 3.95 ? "4x" : String(format: "%.1fx", zoom)
    }

    private func nearStreamCenter(_ point: CGPoint) -> Bool {
        OperationalCenterpointElevation.isNearCenter(x: point.x, y: point.y,
            width: tileSize.width, height: tileSize.height,
            radius: min(96, max(48, min(tileSize.width, tileSize.height) * 0.20)))
    }

    private func handleStreamTap(_ location: CGPoint) {
        let nearCenter = nearStreamCenter(location)
        AppleLog.info("StreamGesture", "Single tap stream=\(session.id) focused=\(focused) center=\(nearCenter) x=\(Int(location.x)) y=\(Int(location.y))")
        if focused && nearCenter {
            let nextMode = OperationalCenterpointElevation.nextMode(centerpointElevationEnabled ? centerpointDisplayMode : .crosshairOnly)
            centerpointDisplayMode = nextMode
            centerpointElevationEnabled = nextMode != .crosshairOnly
            switch nextMode {
            case .msl:
                centerpointElevationSample = nil
                zoom = 1; zoomAtGestureStart = 1; pan = .zero; panAtGestureStart = .zero
            case .reference:
                centerpointReferenceElevationFeet = centerpointElevationSample?.elevationFeet
            case .crosshairOnly:
                centerpointReferenceElevationFeet = nil
            }
        } else { onFocus() }
    }

    private func handleStreamLongPress(_ location: CGPoint) {
        onLongPress()
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
    let showLabel: Bool
    let coordinateFormat: OperationalCoordinateDisplayFormat
    let referenceElevationFeet: Int?
    let displayMode: OperationalCenterpointElevation.DisplayMode
    @AppStorage("crosshair.style") private var style = "Simple"
    @AppStorage("crosshair.widthPx") private var widthPx = 1.0
    @AppStorage("crosshair.sizePercent") private var sizePercent = 5.0
    @AppStorage("crosshair.mainColor") private var mainColor = "FFFFFF"
    @AppStorage("crosshair.borderColor") private var borderColor = "000000"
    @AppStorage("crosshair.showCoordinates") private var showCoordinates = true
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        GeometryReader { geometry in
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
            let arm = min(geometry.size.width, geometry.size.height) * min(25, max(2, sizePercent)) / 200
            let width = max(0.1, widthPx) / displayScale
            Canvas { context, _ in
                if style != "None" {
                    var path = Path()
                    path.move(to: CGPoint(x: center.x - arm, y: center.y))
                    path.addLine(to: CGPoint(x: center.x + arm, y: center.y))
                    path.move(to: CGPoint(x: center.x, y: center.y - arm))
                    path.addLine(to: CGPoint(x: center.x, y: center.y + arm))
                    if style == "Bordered" {
                        context.stroke(path, with: .color(crosshairColor(borderColor)), lineWidth: width + 2 / displayScale)
                    }
                    context.stroke(path, with: .color(crosshairColor(mainColor)), lineWidth: width)
                }
            }
            if showLabel, let sample {
                Text(OperationalCenterpointElevation.readout(sample, referenceElevationFeet: referenceElevationFeet,
                    mode: displayMode, coordinateFormat: coordinateFormat, showCoordinates: showCoordinates) ?? "")
                .font(.caption.bold())
                .foregroundStyle(crosshairColor(mainColor))
                .padding(4)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 4))
                .position(x: center.x, y: center.y + arm + 32)
            }
        }
        .allowsHitTesting(false)
    }
}

private func crosshairColor(_ hex: String) -> Color {
    let value = UInt32(hex, radix: 16) ?? 0xFFFFFF
    return Color(red: Double((value >> 16) & 255) / 255, green: Double((value >> 8) & 255) / 255, blue: Double(value & 255) / 255)
}

struct CrosshairConfigurationSection: View {
    @AppStorage("crosshair.style") private var style = "Simple"
    @AppStorage("crosshair.widthPx") private var widthPx = 1.0
    @AppStorage("crosshair.sizePercent") private var sizePercent = 5.0
    @AppStorage("crosshair.mainColor") private var mainColor = "FFFFFF"
    @AppStorage("crosshair.borderColor") private var borderColor = "000000"
    @AppStorage("crosshair.showCoordinates") private var showCoordinates = true

    private func colorBinding(_ storage: Binding<String>) -> Binding<Color> {
        Binding(get: { crosshairColor(storage.wrappedValue) }, set: { color in
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            guard UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a) else { return }
            storage.wrappedValue = String(format: "%02X%02X%02X", Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
        })
    }
    var body: some View {
        Section("Crosshair Configuration:") {
            Picker("Style", selection: $style) {
                Text("None").tag("None")
                Text("Simple").tag("Simple")
                Text("Bordered").tag("Bordered")
            }
            HStack {
                Text("Width in px")
                TextField("Width", value: $widthPx, format: .number)
                    .keyboardType(.decimalPad)
                    .onChange(of: widthPx) { _, value in
                        if !value.isFinite || value <= 0 { widthPx = 1 }
                    }
            }
            Text("Size: \(Int(min(25, max(2, sizePercent))))% of the narrower video dimension")
            Slider(value: $sizePercent, in: 2...25, step: 1)
            ColorPicker("Main color", selection: colorBinding($mainColor), supportsOpacity: false)
            ColorPicker("Border color", selection: colorBinding($borderColor), supportsOpacity: false)
            Toggle("Coordinates in MSL and REF", isOn: $showCoordinates)
            Text("Center taps cycle MSL → REF → crosshair only. Settings apply to all streams.")
                .font(.footnote)
        }
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

// Keep menu and presentation state outside the frame-observing tile. The panels
// observe their own data, while the menu only needs the current mode label.
private struct AppleStreamSettingsControl: View {
    let session: AppleLiveStreamSession
    let model: AppleVideoFrameSource
    let anomalyModeLabel: String
    let onClose: (() -> Void)?
    let onRestartStreams: (() -> Void)?
    let onDesignatorsTapped: (() -> Void)?
    @State private var openDesignatorsAfterDismiss = false
    @State private var panel: Panel?
    @State private var menuOpen = false
    @State private var pendingAction: (() -> Void)?

    private enum Panel: String, Identifiable {
        case settings, help, server
        var id: String { rawValue }
    }

    var body: some View {
        Button { menuOpen = true } label: {
            Image(systemName: "gearshape.fill")
                .font(.body.bold())
                .padding(8)
                .background(.black.opacity(0.6), in: Circle())
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .foregroundStyle(.white)
        .accessibilityLabel(session.id == "demo" ? "Streams settings" : "Anomaly detection settings")
        .popover(isPresented: $menuOpen) {
            VStack(alignment: .leading, spacing: 18) {
                Button("AD Mode: \(anomalyModeLabel)") { select { panel = .settings } }
                Button("AD Help") { select { panel = .help } }
                Button("Streams Server", systemImage: "network") { select { panel = .server } }
                if let onClose {
                    Button("Close Stream", systemImage: "xmark.rectangle", role: .destructive, action: { select(onClose) })
                }
            }
            .buttonStyle(.plain)
            .padding(20)
            .presentationCompactAdaptation(.popover)
            .onDisappear {
                let action = pendingAction
                pendingAction = nil
                action?()
            }
        }
        .sheet(item: $panel, onDismiss: {
            if openDesignatorsAfterDismiss {
                openDesignatorsAfterDismiss = false
                onDesignatorsTapped?()
            }
        }) { selected in
            NavigationStack {
                Group {
                    switch selected {
                    case .settings:
                        AppleAnomalySettingsView(model: model)
                            .navigationTitle("Anomaly Detector")
                            .navigationBarTitleDisplayMode(.inline)
                    case .help:
                        AppleAnomalyHelpView()
                    case .server:
                        AppleStreamsServerPanel(session: session, model: model, onRestart: onRestartStreams,
                            onDesignatorsTapped: onDesignatorsTapped.map { _ in
                                {
                                    openDesignatorsAfterDismiss = true
                                    panel = nil
                                }
                            })
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { panel = nil }
                    }
                }
            }
        }
    }
    private func select(_ action: @escaping () -> Void) {
        pendingAction = action
        menuOpen = false
    }

}

private struct StreamPerformanceSections: View {
    @ObservedObject var session: AppleLiveStreamSession
    @ObservedObject var model: AppleVideoFrameSource

    var body: some View {
        Group {
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

struct RegisteredDroneDesignatorsView: View {
    let values: [String]
    let onAddAircraft: (() -> Void)?

    private var sortedValues: [String] {
        values.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .reduce(into: [String]()) { result, value in
                if !result.contains(where: { $0.caseInsensitiveCompare(value) == .orderedSame }) {
                    result.append(value)
                }
            }
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            List {
                if sortedValues.isEmpty {
                    Text("No registered droneDesig values.").foregroundStyle(.secondary)
                } else {
                    ForEach(sortedValues, id: \.self) { Text($0) }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if let onAddAircraft {
                    Button("Add aircraft", systemImage: "plus", action: onAddAircraft)
                        .buttonStyle(.borderedProminent)
                        .padding(.vertical, 8)
                }
            }
            .navigationTitle("droneDesig's for registered drones")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

@MainActor
final class RID2CaltopoAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Open the session log for every launch, foreground or background,
        // before any UI (terms gate, ContentView) exists.
        let launchOptionKeys = (launchOptions ?? [:]).keys.map(\.rawValue)
        Task { @MainActor in
            await AppleDiagnosticsCenter.startAtLaunch(launchOptionKeys: launchOptionKeys)
        }
        AppleUserInteractionObserver.install()
        // Safety net: raw AOL lidar work only lives while Download Map stays open; remove any left by a killed app.
        Task.detached(priority: .background) {
            let removed = OperationalSurfaceSourceReuse.deleteWorkDirectories(in: FileManager.default.temporaryDirectory)
            if removed > 0 { AppleLog.info("MapOffline", "Removed \(removed) leftover AOL work folder(s)") }
        }
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

/// Explicit recognizer precedence prevents touch-location tracking from consuming taps.
@MainActor
private struct AppleStreamGestureSurface: UIViewRepresentable {
    let onTap: (CGPoint) -> Void
    let onDoubleTap: (CGPoint) -> Void
    let onLongPress: (CGPoint) -> Void
    let onPinch: (CGFloat, Bool) -> Void
    let onPan: (CGPoint, Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isMultipleTouchEnabled = true
        view.accessibilityIdentifier = "stream-gesture-surface"
        let coordinator = context.coordinator
        let single = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.tap(_:)))
        let double = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.doubleTap(_:)))
        double.numberOfTapsRequired = 2
        let hold = UILongPressGestureRecognizer(target: coordinator, action: #selector(Coordinator.hold(_:)))
        hold.minimumPressDuration = 0.5
        let pinch = UIPinchGestureRecognizer(target: coordinator, action: #selector(Coordinator.pinch(_:)))
        let pan = UIPanGestureRecognizer(target: coordinator, action: #selector(Coordinator.pan(_:)))
        single.require(toFail: double)
        single.require(toFail: hold)
        for recognizer in [single, double, hold, pinch, pan] {
            recognizer.delegate = coordinator
            view.addGestureRecognizer(recognizer)
        }
        return view
    }
    func updateUIView(_ view: UIView, context: Context) { context.coordinator.owner = self }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var owner: AppleStreamGestureSurface
        init(_ owner: AppleStreamGestureSurface) { self.owner = owner }
        @objc func tap(_ gesture: UITapGestureRecognizer) { owner.onTap(gesture.location(in: gesture.view)) }
        @objc func doubleTap(_ gesture: UITapGestureRecognizer) { owner.onDoubleTap(gesture.location(in: gesture.view)) }
        @objc func hold(_ gesture: UILongPressGestureRecognizer) {
            if gesture.state == .began { owner.onLongPress(gesture.location(in: gesture.view)) }
        }
        @objc func pinch(_ gesture: UIPinchGestureRecognizer) {
            owner.onPinch(gesture.scale, gesture.state == .ended || gesture.state == .cancelled)
        }
        @objc func pan(_ gesture: UIPanGestureRecognizer) {
            owner.onPan(gesture.translation(in: gesture.view), gesture.state == .ended || gesture.state == .cancelled)
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            (gestureRecognizer is UIPinchGestureRecognizer && otherGestureRecognizer is UIPanGestureRecognizer) ||
            (gestureRecognizer is UIPanGestureRecognizer && otherGestureRecognizer is UIPinchGestureRecognizer)
        }
    }
}


struct AppleStreamsServerPanel: View {
    let session: AppleLiveStreamSession
    let model: AppleVideoFrameSource
    let onRestart: (() -> Void)?
    let onDesignatorsTapped: (() -> Void)?
    @ObservedObject private var status = AppleStreamsServerStatus.shared
    @ObservedObject private var network = AppleNetworkDiagnosticCenter.shared
    @State private var confirmReset = false
    var body: some View {
        Form {
            Text("Network: \(network.currentControllerConnectionLabel)")
            AppleControllerConnectionURLs(onDesignatorsTapped: onDesignatorsTapped)
            LabeledContent("MediaMTX", value: status.version)
            if let started = status.startedAt {
                HStack { Text("Runtime"); Spacer(); Text(started, style: .timer) }
            } else { Text("Stopped") }
            if onRestart != nil { Button("Reset Streams Server") { confirmReset = true } }
            AppleStreamDeviceLoadSection()
            StreamPerformanceSections(session: session, model: model)
        }.navigationTitle("Streams Server")
            .confirmationDialog("Reset Streams Server? Active streams and recordings will be interrupted.", isPresented: $confirmReset, titleVisibility: .visible) {
                Button("Reset", role: .destructive) { onRestart?() }
                Button("Cancel", role: .cancel) {}
            }
    }
}

/// MediaMTX is embedded on iOS, so process measurements include the server.
private struct AppleStreamDeviceLoadSection: View {
    @State private var cpuPercent: Double?
    @State private var peakMemoryBytes: Int64?
    @State private var thermal = "Unknown"
    @State private var headroom = "unknown"
    @State private var liveStreams = 0
    @State private var anomalyStreams = 0

    var body: some View {
        Section("Device load") {
            LabeledContent("Estimated anomaly headroom", value: headroom)
            Text(headroom == "ok" ? "Device appears to have room for anomaly work."
                : headroom == "limit" ? "Additional streams or anomaly load may cause lag."
                : headroom == "hot" ? "Thermal or CPU pressure is high. Reduce load; anomaly detection may pause."
                : "Waiting for a live stream and CPU samples before estimating anomaly capacity.")
                .font(.footnote).foregroundStyle(.secondary)
            LabeledContent("Live streams", value: String(liveStreams))
            LabeledContent("Anomaly-enabled streams", value: String(anomalyStreams))
            LabeledContent("App CPU load", value: cpuPercent.map { String(format: "%.0f%% of available core capacity", $0) } ?? "Sampling…")
            LabeledContent("App peak memory", value: peakMemoryBytes.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .memory) } ?? "Unavailable")
            LabeledContent("Thermal status", value: thermal)
            LabeledContent("Low power mode", value: ProcessInfo.processInfo.isLowPowerModeEnabled ? "On" : "Off")
            Text("MediaMTX runs inside the app on iOS. CPU and memory include the server, video decoding, maps, and other app work.")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .task {
            var previous: (time: Double, cpu: Double)?
            while !Task.isCancelled {
                var usage = rusage()
                let now = ProcessInfo.processInfo.systemUptime
                if getrusage(RUSAGE_SELF, &usage) == 0 {
                    let cpu = Double(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec)
                        + Double(usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1_000_000
                    if let last = previous, now > last.time {
                        cpuPercent = max(0, (cpu - last.cpu) / (now - last.time)
                            / Double(max(1, ProcessInfo.processInfo.activeProcessorCount)) * 100)
                    }
                    previous = (now, cpu)
                    peakMemoryBytes = Int64(usage.ru_maxrss)
                }
                switch ProcessInfo.processInfo.thermalState {
                case .nominal: thermal = "Nominal"
                case .fair: thermal = "Fair"
                case .serious: thermal = "Serious"
                case .critical: thermal = "Critical"
                @unknown default: thermal = "Unknown"
                }
                let sessions = AppleStreamRegistry.shared.sessions.filter { $0.id != "demo" && $0.state == .live }
                liveStreams = sessions.count
                anomalyStreams = sessions.filter { $0.model.anomalyMode != .off }.count
                let pressure: Int
                switch ProcessInfo.processInfo.thermalState {
                case .nominal: pressure = 0
                case .fair: pressure = 1
                case .serious, .critical: pressure = 2
                @unknown default: pressure = 1
                }
                headroom = OperationalAnomalyHeadroom.assess(cpuFraction: cpuPercent.map { $0 / 100 },
                    thermalPressure: pressure, liveStreams: liveStreams,
                    softwareDecodedStreams: sessions.filter { $0.model.decoderBackend.lowercased().contains("ffmpeg") }.count,
                    anomalyEnabledStreams: anomalyStreams)
                do { try await Task.sleep(for: .seconds(2)) } catch { break }
            }
        }
    }
}

func stampClueCrosshair(_ image: UIImage, coordinates: String?) -> UIImage {
    let prefs = UserDefaults.standard
    let style = prefs.string(forKey: "crosshair.style") ?? "Simple"
    guard style != "None" else { return image }
    let width = max(0.1, prefs.object(forKey: "crosshair.widthPx") as? Double ?? 1)
    let percent = min(25, max(2, prefs.object(forKey: "crosshair.sizePercent") as? Double ?? 5))
    let size = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
    let format = UIGraphicsImageRendererFormat(); format.scale = 1
    return UIGraphicsImageRenderer(size: size, format: format).image { output in
        image.draw(in: CGRect(origin: .zero, size: size))
        let context = output.cgContext
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let arm = min(size.width, size.height) * percent / 200
        let path = CGMutablePath()
        path.move(to: CGPoint(x: center.x-arm, y: center.y)); path.addLine(to: CGPoint(x: center.x+arm, y: center.y))
        path.move(to: CGPoint(x: center.x, y: center.y-arm)); path.addLine(to: CGPoint(x: center.x, y: center.y+arm))
        if style == "Bordered" {
            context.addPath(path); context.setStrokeColor(UIColor(crosshairColor(prefs.string(forKey: "crosshair.borderColor") ?? "000000")).cgColor)
            context.setLineWidth(width + 2); context.strokePath()
        }
        context.addPath(path); context.setStrokeColor(UIColor(crosshairColor(prefs.string(forKey: "crosshair.mainColor") ?? "FFFFFF")).cgColor)
        context.setLineWidth(width); context.strokePath()
        if let coordinates, prefs.object(forKey: "crosshair.showCoordinates") as? Bool ?? true {
            let font = UIFont.monospacedSystemFont(ofSize: max(14, min(size.width,size.height)*0.018), weight: .semibold)
            let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
            let label = coordinates as NSString; let labelSize = label.size(withAttributes: attributes)
            let rect = CGRect(x: center.x-labelSize.width/2, y: center.y+arm+8, width: labelSize.width, height: labelSize.height)
            UIColor.black.withAlphaComponent(0.75).setFill(); UIBezierPath(rect: rect.insetBy(dx: -4, dy: -3)).fill()
            label.draw(in: rect, withAttributes: attributes)
        }
    }
}
