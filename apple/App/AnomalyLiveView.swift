import AVKit
import R2CCore
import SwiftUI

struct AnomalyLiveView: View {
    @ObservedObject var model: AppleVideoFrameSource
    let streamURL: URL?

    var body: some View {
        VStack(spacing: 0) {
            AppleVideoSafetyNotice()
            ZStack {
                Color.black
                if model.usesNativeVideoSurface {
                    AppleLiveVideoSurface(model: model)
                    if model.anomalyMode != .off {
                        AnomalyBoxOverlay(boxes: model.anomalyBoxes)
                            .allowsHitTesting(false)
                        if model.anomalyConfiguration.showHotOverlay,
                           let hotOverlay = model.anomalyHotOverlay {
                            AnomalyHotOverlayView(overlay: hotOverlay)
                                .allowsHitTesting(false)
                        }
                        if model.anomalyConfiguration.showGuideBoxes {
                            anomalyGuides
                        }
                    }
                } else if let player = model.player {
                    VideoPlayer(player: player)
                    if model.anomalyMode != .off {
                        AnomalyBoxOverlay(boxes: model.anomalyBoxes)
                            .allowsHitTesting(false)
                        if model.anomalyConfiguration.showHotOverlay,
                           let hotOverlay = model.anomalyHotOverlay {
                            AnomalyHotOverlayView(overlay: hotOverlay)
                                .allowsHitTesting(false)
                        }
                        if model.anomalyConfiguration.showGuideBoxes {
                            anomalyGuides
                        }
                    }
                } else {
                    ContentUnavailableView(
                        "Video disconnected",
                        systemImage: "video.slash",
                        description: Text("Start MediaMTX and connect the Apple decoder.")
                    )
                    .foregroundStyle(.white)
                }
                VStack {
                    HStack {
                        AppleLiveVideoIndicator(model: model)
                        Spacer()
                    }
                    Spacer()
                }
                .padding(8)
            }
            // Match the decoded raster so AVPlayer's aspect-fit image and the
            // detector's normalized coordinates occupy the same rectangle.
            .aspectRatio(model.videoAspectRatio, contentMode: .fit)

            HStack {
                Label(videoStatus, systemImage: "waveform.path.ecg")
                Spacer()
                Text("\(model.analyzedFrameCount) analyzed")
                Text("\(model.droppedAnalysisFrameCount) dropped")
                Text("\(model.anomalyCount) boxes")
            }
            .font(.subheadline.monospacedDigit())
            .padding()

            if model.recoveryCount > 0 || model.mediaPublisherStatus != "Unknown" {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Decoder: \(model.decoderBackend)")
                    Text("MediaMTX: \(model.mediaPublisherStatus) • recoveries: \(model.recoveryCount)")
                    Text("Last frame: \(frameAge) • last recovery: \(model.lastRecoveryReason)")
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal)
                .padding(.bottom, 8)
            }

            Picker("Detector", selection: Binding(
                get: { model.anomalyMode },
                set: { model.setAnomalyMode($0) }
            )) {
                ForEach(AppleAnomalyMode.allCases) { mode in
                    Text(mode.compactLabel).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            Text(detectorDescription)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
                .padding(.top, 6)

            Button(model.state == .idle ? "Connect Decoder" : "Disconnect Decoder") {
                if model.state == .idle {
                    if let streamURL { model.start(url: streamURL) }
                } else {
                    model.stop()
                }
            }
            .buttonStyle(.borderedProminent)
            .padding(.bottom)
        }
        .navigationTitle("Live Anomaly View")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink { AppleAnomalySettingsView(model: model) } label: {
                    Label("Anomaly Settings", systemImage: "slider.horizontal.3")
                }
            }
        }
    }

    private var videoStatus: String {
        switch model.state {
        case .idle: "Idle"
        case .connecting: "Connecting"
        case .streaming: "Streaming"
        case .waitingForPublisher: "Waiting for publisher"
        case .failed: "Reconnecting"
        }
    }

    private var frameAge: String {
        model.decodedFrameAgeSeconds.map { String(format: "%.1fs", $0) } ?? "none decoded"
    }

    private var detectorDescription: String {
        switch model.anomalyMode {
        case .off:
            "Video remains connected while anomaly analysis is disabled."
        case .colorUniqueness:
            "Color Uniqueness uses the shared Android fresh-RGBA detector; boxes are yellow."
        case .targetColors:
            "Target Colors limits the shared color detector to the selected color families."
        case .infrared:
            "Infrared uses the shared Android thermal detector; boxes are red."
        }
    }

    private var anomalyGuides: some View {
        AnomalyGuideOverlay(
            scanZone: model.anomalyConfiguration.scanZone,
            smallTargetScreenFraction: model.anomalyConfiguration.smallTargetScreenFraction
        )
        .allowsHitTesting(false)
    }
}

struct AnomalyBoxOverlay: View {
    let boxes: [AppleAnomalyBox]

    var body: some View {
        GeometryReader { geometry in
            ForEach(boxes) { box in
                let width = CGFloat(max(0, box.right - box.left)) * geometry.size.width
                let height = CGFloat(max(0, box.bottom - box.top)) * geometry.size.height
                let center = CGPoint(
                    x: CGFloat((box.left + box.right) * 0.5) * geometry.size.width,
                    y: CGFloat((box.top + box.bottom) * 0.5) * geometry.size.height
                )
                let strokeMaximum = min(max(min(geometry.size.width, geometry.size.height) * 0.006, 2), 8)
                let stroke = min(max((strokeMaximum * CGFloat(box.weight)).rounded(), 1), strokeMaximum)
                let underlay = stroke + 2
                if box.drawsCrosshair {
                    let path = crosshairPath(center: center, width: width, height: height, stroke: stroke)
                    path.stroke(.black, lineWidth: underlay)
                    path.stroke(boxColor(box), lineWidth: stroke)
                } else {
                    Rectangle()
                        .stroke(.black, lineWidth: underlay)
                        .overlay(Rectangle().stroke(boxColor(box), lineWidth: stroke))
                        .frame(width: width, height: height)
                        .position(center)
                }
            }
        }
    }

    private func boxColor(_ box: AppleAnomalyBox) -> Color {
        Color(
            red: Double(box.red) / 255,
            green: Double(box.green) / 255,
            blue: Double(box.blue) / 255
        )
    }

    private func crosshairPath(
        center: CGPoint,
        width: CGFloat,
        height: CGFloat,
        stroke: CGFloat
    ) -> Path {
        let left = center.x - width / 2
        let right = center.x + width / 2
        let top = center.y - height / 2
        let bottom = center.y + height / 2
        let halfWidth = width / 2
        let halfHeight = height / 2
        let maximumGapX = halfWidth - stroke
        let maximumGapY = halfHeight - stroke
        let gapX = maximumGapX <= stroke * 2
            ? stroke
            : min(max(halfWidth / 3, stroke * 2), maximumGapX)
        let gapY = maximumGapY <= stroke * 2
            ? stroke
            : min(max(halfHeight / 3, stroke * 2), maximumGapY)
        return Path { path in
            path.move(to: CGPoint(x: left, y: center.y))
            path.addLine(to: CGPoint(x: center.x - gapX, y: center.y))
            path.move(to: CGPoint(x: center.x + gapX, y: center.y))
            path.addLine(to: CGPoint(x: right, y: center.y))
            path.move(to: CGPoint(x: center.x, y: top))
            path.addLine(to: CGPoint(x: center.x, y: center.y - gapY))
            path.move(to: CGPoint(x: center.x, y: center.y + gapY))
            path.addLine(to: CGPoint(x: center.x, y: bottom))
        }
    }
}

struct AnomalyGuideOverlay: View {
    let scanZone: Double
    let smallTargetScreenFraction: Double
    var maximumTargetFraction = 0.35
    var opacity = 0.70
    var lineWidth: CGFloat = 1.5

    var body: some View {
        GeometryReader { geometry in
            let guide = AnomalyConfigurationParity.guideGeometry(
                frameWidth: geometry.size.width,
                frameHeight: geometry.size.height,
                scanZone: scanZone,
                smallTargetScreenFraction: smallTargetScreenFraction,
                maximumTargetFraction: maximumTargetFraction
            )
            let color = Color(red: 128.0 / 255.0, green: 203.0 / 255.0, blue: 196.0 / 255.0)
                .opacity(opacity)
            ZStack {
                Rectangle()
                    .stroke(color, lineWidth: lineWidth)
                    .frame(width: CGFloat(guide.scanWidth), height: CGFloat(guide.scanHeight))
                Rectangle()
                    .stroke(color, lineWidth: lineWidth)
                    .frame(width: CGFloat(guide.targetSpan), height: CGFloat(guide.targetSpan))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

struct AnomalyHotOverlayView: View {
    let overlay: AppleAnomalyHotOverlay

    var body: some View {
        GeometryReader { geometry in
            let minimumDimension = min(geometry.size.width, geometry.size.height)
            let radius = CGFloat(overlay.radius) * minimumDimension
            let stroke = max(2, CGFloat(overlay.stroke) * minimumDimension)
            Circle()
                .stroke(
                    Color(red: 1, green: 48.0 / 255.0, blue: 48.0 / 255.0),
                    lineWidth: stroke
                )
                .frame(width: radius * 2, height: radius * 2)
                .position(
                    x: CGFloat(overlay.centerX) * geometry.size.width,
                    y: CGFloat(overlay.centerY) * geometry.size.height
                )
        }
    }
}
