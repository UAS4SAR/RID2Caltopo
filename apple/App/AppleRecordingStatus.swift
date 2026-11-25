import Combine
import R2CCore
import SwiftUI

/// A connect-time "not recording" prompt (or a tap on the amber marker that explains why).
struct AppleRecordingPrompt: Identifiable, Equatable {
    let key: String
    let designator: String
    let reason: NotRecordingReason
    var fromTap = false
    var id: String { key }
    var title: String { RecordingMarkerPolicy.promptTitle }
    var text: String { RecordingMarkerPolicy.promptText(designator: designator, reason: reason) }
    var offersTurnOn: Bool { reason == .captureOff }
}

/// Live recording state for the marker on every stream view, the map status chip and the
/// connect-time warning (Nov 24 2026: two searches flown with capture off, nothing on screen).
/// "● REC" follows MediaMTX's recorder log lines, not the setting. Android: RecordingStatus,
/// RecordingConnectWarning.
@MainActor
final class AppleRecordingStatus: ObservableObject {
    static let shared = AppleRecordingStatus()

    @Published private(set) var snapshot = RecordingStatusSnapshot()
    /// Advanced once a second only while a marker is waiting on the start grace period.
    @Published private(set) var now: TimeInterval = ProcessInfo.processInfo.systemUptime
    @Published private(set) var prompts: [AppleRecordingPrompt] = []

    private var policy = RecordingConnectWarningPolicy()
    private var ticker: AnyCancellable?
    private var sessionsObserver: AnyCancellable?

    private init() {}

    private var clock: TimeInterval { ProcessInfo.processInfo.systemUptime }

    func start() {
        guard ticker == nil else { return }
        sessionsObserver = AppleStreamRegistry.shared.$sessions
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in Task { @MainActor in self?.evaluate() } }
        ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
            .sink { [weak self] _ in Task { @MainActor in self?.tick() } }
    }

    // MARK: Inputs (MediaMTXViewModel)

    func updateInputs(_ transform: (inout RecordingInputs) -> Void) {
        var inputs = snapshot.inputs
        transform(&inputs)
        guard inputs != snapshot.inputs else { return }
        snapshot.update(inputs)
        evaluate()
    }

    func handle(_ event: MediaServerEvent) {
        switch event {
        case .serverStarted: snapshot.serverStarted(at: clock)
        case let .streamStarted(path, _), let .streamPublisherHandoff(path, _): snapshot.streamStarted(path, at: clock)
        case let .recorderStarted(path):
            AppleLog.debug("MediaMTX", "Recorder started path=\(path)")
            snapshot.recorderStarted(path)
        case let .recorderStopped(path):
            AppleLog.debug("MediaMTX", "Recorder stopped path=\(path)")
            snapshot.recorderStopped(path, at: clock)
        default: return
        }
        now = clock
        evaluate()
    }

    // MARK: Markers

    static func isLive(_ session: AppleLiveStreamSession) -> Bool { session.state == .live && session.id != "demo" }

    func marker(for session: AppleLiveStreamSession) -> RecordingMarker {
        snapshot.marker(sourcePath: session.sourcePath, live: Self.isLive(session), now: now)
    }

    static func key(_ session: AppleLiveStreamSession) -> String {
        NotCapturingStreamsPolicy.Stream(sourcePath: session.sourcePath, designator: session.id,
                                         publisherID: session.publisherConnectionID, live: true).key
    }

    private func tick() {
        let live = AppleStreamRegistry.shared.sessions.filter(Self.isLive)
        guard live.contains(where: { marker(for: $0).state == .starting }) else { return }
        now = clock
        evaluate()
    }

    /// Connect-time warning: spoken once per publish plus a prompt whose default is "Turn on".
    func evaluate() {
        let sessions = AppleStreamRegistry.shared.sessions.filter { $0.id != "demo" }
        let streams = sessions.map {
            RecordingConnectWarningPolicy.Stream(key: Self.key($0), designator: $0.id, marker: marker(for: $0))
        }
        let warnings = policy.evaluate(streams)
        let liveKeys = Set(streams.filter { $0.marker.state != .hidden }.map(\.key))
        var next = prompts.filter { $0.fromTap || liveKeys.contains($0.key) }
        for warning in warnings {
            AppleLog.warning("MediaMTX", "Live stream \(warning.designator) is not being recorded reason=\(warning.reason)")
            AppleSpokenWarningCenter.shared.speak(RecordingMarkerPolicy.spokenPhrase)
            if !next.contains(where: { $0.key == warning.key }) {
                next.append(AppleRecordingPrompt(key: warning.key, designator: warning.designator, reason: warning.reason))
            }
        }
        if next != prompts { prompts = next }
    }

    // MARK: Actions

    func tapped(_ session: AppleLiveStreamSession) {
        let marker = marker(for: session)
        guard marker.state == .notRecording, let reason = marker.reason else { return }
        if RecordingMarkerPolicy.tapTurnsOnCapture(marker) {
            AppleLog.info("MediaMTX", "Recording marker tapped for \(session.id): turning Capture Streams on")
            CaptureStreamsCenter.shared.turnOn()
        } else {
            prompts.removeAll { $0.key == Self.key(session) }
            prompts.append(AppleRecordingPrompt(key: Self.key(session), designator: session.id, reason: reason, fromTap: true))
        }
    }

    func turnOn(_ prompt: AppleRecordingPrompt) {
        AppleLog.info("MediaMTX", "Not-recording prompt for \(prompt.designator): Turn on")
        dismiss(prompt)
        CaptureStreamsCenter.shared.turnOn()
    }

    /// "Not now" on a capture-off prompt also confirms that publish's Not-capturing strip;
    /// the amber marker stays.
    func notNow(_ prompt: AppleRecordingPrompt) {
        AppleLog.info("MediaMTX", "Not-recording prompt for \(prompt.designator): Not now")
        dismiss(prompt)
        if prompt.reason == .captureOff {
            CaptureStreamsCenter.shared.confirm(NotCapturingStream(key: prompt.key, designator: prompt.designator))
        }
    }

    func dismiss(_ prompt: AppleRecordingPrompt) { prompts.removeAll { $0.key == prompt.key } }
}

// MARK: - Views

enum AppleRecordingColors {
    static let red = Color(red: 0xD3 / 255, green: 0x2F / 255, blue: 0x2F / 255)
    static let amber = Color(red: 1, green: 0xA0 / 255, blue: 0)
}

/// Small pill: red "● REC", grey "● REC…" while starting, amber "NOT RECORDING".
struct AppleRecordingMarkerPill: View {
    let marker: RecordingMarker
    var prefix: String?
    var action: (() -> Void)?

    var body: some View {
        if let label = marker.label {
            let text = Text((prefix.map { "\($0) " } ?? "") + label)
                .font(.caption2.weight(.bold))
                .lineLimit(1)
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .foregroundStyle(foreground)
                .background(background, in: RoundedRectangle(cornerRadius: 4))
                .accessibilityLabel(accessibility)
            if let action, marker.state == .notRecording {
                Button(action: action) { text }.buttonStyle(.plain)
            } else {
                text
            }
        }
    }

    private var foreground: Color {
        switch marker.state {
        case .recording: return AppleRecordingColors.red
        case .notRecording: return .black
        default: return .white.opacity(0.7)
        }
    }

    private var background: Color {
        switch marker.state {
        case .notRecording: return AppleRecordingColors.amber
        case .starting: return .black.opacity(0.6)
        default: return .black.opacity(0.72)
        }
    }

    private var accessibility: String {
        switch marker.state {
        case .recording: return "Recording"
        case .starting: return "Recording starting"
        case .notRecording: return "Not recording. " + (marker.reason.map(RecordingMarkerPolicy.reasonText) ?? "")
        case .hidden: return ""
        }
    }
}

/// Marker on a stream tile (full, split, inset and the compact PiP).
struct AppleStreamRecordingMarker: View {
    @ObservedObject var session: AppleLiveStreamSession
    @ObservedObject private var status = AppleRecordingStatus.shared

    var body: some View {
        AppleRecordingMarkerPill(marker: status.marker(for: session)) { status.tapped(session) }
    }
}

/// Map status row chip whenever a stream is live (worst state across live streams).
struct AppleRecordingStatusChip: View {
    @ObservedObject private var registry = AppleStreamRegistry.shared
    @ObservedObject private var status = AppleRecordingStatus.shared

    var body: some View {
        let live = registry.sessions.filter(AppleRecordingStatus.isLive)
        let markers = live.map { (designator: $0.id, marker: status.marker(for: $0)) }
        if let shown = RecordingMarkerPolicy.aggregate(markers) {
            AppleRecordingMarkerPill(marker: shown.marker, prefix: shown.designator) {
                let target = live.first { status.marker(for: $0).state == .notRecording }
                if let target { status.tapped(target) }
            }
        }
    }
}

/// Root-level host for connect-time prompts ("Turn on" is the default button).
struct AppleRecordingPromptHost: ViewModifier {
    @ObservedObject private var status = AppleRecordingStatus.shared

    func body(content: Content) -> some View {
        let prompt = status.prompts.first
        content.alert(
            prompt?.title ?? RecordingMarkerPolicy.promptTitle,
            isPresented: Binding(get: { prompt != nil }, set: { shown in
                if !shown, let prompt, status.prompts.first == prompt { status.dismiss(prompt) }
            }),
            presenting: prompt
        ) { prompt in
            if prompt.offersTurnOn {
                Button(RecordingMarkerPolicy.turnOn) { status.turnOn(prompt) }
                    .keyboardShortcut(.defaultAction)
                Button(RecordingMarkerPolicy.notNow, role: .cancel) { status.notNow(prompt) }
            } else {
                Button(RecordingMarkerPolicy.ok, role: .cancel) { status.dismiss(prompt) }
            }
        } message: { prompt in
            Text(prompt.text)
        }
    }
}
