import AVFoundation
import R2CCore
import SwiftUI
import UIKit

/// Hosts frames paced by the native adaptive live buffer. Display immediately
/// here so AVFoundation does not add a second playback queue.
struct AppleLiveVideoSurface: UIViewRepresentable {
    let model: AppleVideoFrameSource

    func makeUIView(context: Context) -> LiveVideoSurfaceView {
        LiveVideoSurfaceView(model: model)
    }

    func updateUIView(_ view: LiveVideoSurfaceView, context: Context) {
        view.bind(model)
    }

    static func dismantleUIView(_ view: LiveVideoSurfaceView, coordinator: Void) {
        view.unbind()
    }
}

struct AppleLiveVideoIndicator: View {
    @ObservedObject var model: AppleVideoFrameSource
    var displayDesignator: String? = nil
    var tint: Color = .white

    var body: some View {
        Text("\(primaryLabel) - \(statusLabel)")
            .font(.caption.monospaced().weight(.bold))
            .foregroundStyle(tint)
            .padding(.horizontal, 7)
            .padding(.vertical, 5)
            .background(.black.opacity(0.68), in: RoundedRectangle(cornerRadius: 4))
            .accessibilityLabel("Stream \(primaryLabel), \(statusLabel)")
    }

    private var primaryLabel: String {
        let proposed = displayDesignator?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return proposed.isEmpty ? model.streamDesignator : proposed
    }

    private var statusLabel: String {
        if model.state == .streaming, let age = model.decodedFrameAgeSeconds, age >= 3 {
            return "No new frames • " + String(format: "%.0fs", age)
        }
        return switch model.state {
        case .idle: "Stopped"
        case .connecting: "Connecting..."
        case .waitingForPublisher: "Waiting"
        case .failed: "Reconnecting"
        case .streaming: "Streaming"
        }
    }
}

final class LiveVideoSurfaceView: UIView {
    private weak var model: AppleVideoFrameSource?
    private let videoLayer: AVSampleBufferDisplayLayer = {
        let layer = AVSampleBufferDisplayLayer()
        layer.videoGravity = .resizeAspect
        return layer
    }()

    init(model: AppleVideoFrameSource) {
        super.init(frame: .zero)
        backgroundColor = .black
        layer.addSublayer(videoLayer)
        bind(model)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func bind(_ model: AppleVideoFrameSource) {
        guard self.model !== model else { return }
        self.model?.unregisterDisplayLayer(videoLayer)
        self.model = model
        model.registerDisplayLayer(videoLayer)
    }

    func unbind() {
        model?.unregisterDisplayLayer(videoLayer)
        model = nil
        videoLayer.flushAndRemoveImage()
        videoLayer.removeFromSuperlayer()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        videoLayer.frame = bounds
    }
}

struct AppleVideoSafetyNotice: View {
    var body: some View {
        Text("Observation only — do not pilot using this video. Images may be delayed or frozen. Use the aircraft’s flight-control system and maintain required visual observation.")
            .font(.caption.weight(.bold)).foregroundStyle(Color(red: 1, green: 0.835, blue: 0.31))
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(6).background(.black)
    }
}
