import R2CCore
import SwiftUI

/// Capture Streams safeguards (Nov 18: a search flight was missed with capture off). Every
/// change is logged with old value, new value and source; the panel can turn capture on
/// without restarting video. Android: CaltopoClient.SetCaptureVideoStreamsFlag, NotCapturingStreams.
@MainActor
final class CaptureStreamsCenter: ObservableObject {
    static let shared = CaptureStreamsCenter()
    static let key = "video.captureStreams"

    /// Streams whose "Not capturing" panel was confirmed (keeps capture off for that publish).
    @Published private(set) var dismissed: Set<String> = []
    /// Source of the next change observed through @AppStorage; Settings saves when unset.
    var pendingSource: String?

    static var current: Bool {
        CaptureStreamsLog.loaded(stored: UserDefaults.standard.object(forKey: key) as? Bool)
    }

    func logLoad() {
        let stored = UserDefaults.standard.object(forKey: Self.key) as? Bool
        AppleLog.info("Settings", "Capture Streams loaded value=\(CaptureStreamsLog.loaded(stored: stored)) saved=\(stored.map(String.init) ?? "missing (defaults ON)") source=\(CaptureStreamsLog.load)")
    }

    /// Called from the single @AppStorage observer, so every path (Settings save, panel,
    /// reset) is logged exactly once.
    func changed(old: Bool, new: Bool) {
        let source = pendingSource ?? CaptureStreamsLog.settings
        pendingSource = nil
        AppleLog.warning("Settings", CaptureStreamsLog.change(old: old, new: new, source: source))
    }

    func confirm(_ stream: NotCapturingStream) {
        dismissed.insert(stream.key)
        AppleLog.info("Settings", "Capture Streams left OFF for \(stream.designator) (confirmed on panel)")
    }

    func turnOn() {
        pendingSource = CaptureStreamsLog.panel
        UserDefaults.standard.set(true, forKey: Self.key)
    }

    func prune(_ keys: Set<String>) {
        let kept = dismissed.intersection(keys)
        if kept != dismissed { dismissed = kept }
    }
}

/// Compact panel on the map while a stream is live and Capture Streams is off.
struct NotCapturingStreamBanner: View {
    @ObservedObject var registry = AppleStreamRegistry.shared
    @ObservedObject var center = CaptureStreamsCenter.shared
    @AppStorage(CaptureStreamsCenter.key) private var captureStreams = true

    private var streams: [NotCapturingStreamsPolicy.Stream] {
        registry.sessions.filter { $0.id != "demo" }.map {
            NotCapturingStreamsPolicy.Stream(sourcePath: $0.sourcePath, designator: $0.id,
                                             publisherID: $0.publisherConnectionID, live: $0.state == .live)
        }
    }

    var body: some View {
        let pending = NotCapturingStreamsPolicy.pending(streams, captureEnabled: captureStreams, dismissed: center.dismissed)
        if let first = pending.first {
            NotCapturingStreamPanel(stream: first, moreCount: pending.count - 1,
                                    onConfirm: { center.confirm(first) },
                                    onTurnOn: { center.turnOn() })
                .onChange(of: streams.map(\.key)) { _, keys in center.prune(Set(keys)) }
        }
    }
}

struct NotCapturingStreamPanel: View {
    let stream: NotCapturingStream
    let moreCount: Int
    let onConfirm: () -> Void
    let onTurnOn: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(NotCapturingStreamsPolicy.title(stream) + (moreCount > 0 ? " (+\(moreCount) more)" : ""))
                .font(.callout.weight(.semibold))
                .lineLimit(1)
                .truncationMode(.tail)
            Button(NotCapturingStreamsPolicy.confirm, action: onConfirm)
                .buttonStyle(.bordered)
            Button(NotCapturingStreamsPolicy.turnOn, action: onTurnOn)
                .buttonStyle(.borderedProminent)
        }
        .controlSize(.small)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.regularMaterial, in: Capsule())
        .accessibilityElement(children: .contain)
        .frame(maxWidth: 640)
    }
}
