import R2CAppleRadios
import R2CCore
import SwiftUI

/// Main menu > Desk Pod: the btrx Desk Pod Bluetooth link, its status and link health,
/// and the "Pair drone pod" flow. Same sections, order and wording as Android's
/// DeskPodScreen. "Dismiss" only hides this panel; the link keeps running.
/// "Cancel pairing" ends a pairing in progress.
struct DeskPodView: View {
    @ObservedObject var center: BtrxDeskPodCenter
    let onDismiss: () -> Void

    private typealias Text_ = BtrxDeskPodText
    private static let green = Color(red: 0x2E / 255, green: 0x7D / 255, blue: 0x32 / 255)

    var body: some View {
        let s = center.snapshot
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    section("Connection")
                    Toggle(Text_.bluetoothToggle, isOn: Binding(get: { center.bluetoothEnabled }, set: { center.setBluetoothEnabled($0) }))
                    if s.bluetoothPausedForUSB {
                        Text(Text_.usbPaused).fontWeight(.semibold).foregroundStyle(.orange)
                    }
                    Text(Text_.linkLine(s)).fontWeight(.semibold)
                    if !s.link.detail.isEmpty { Text(s.link.detail).font(.footnote) }
                    if let device = Text_.deviceLine(s) { Text(device) }
                    Text(Text_.pinHint).font(.footnote).foregroundStyle(.secondary)
                    if center.bluetoothEnabled {
                        Button("Forget this Desk Pod") { center.forgetDeskPod() }.buttonStyle(.bordered)
                    }

                    Divider().padding(.vertical, 6)
                    section("Desk Pod")
                    Text(Text_.roleLine(s))
                    Text(Text_.firmwareLine(s))

                    Divider().padding(.vertical, 6)
                    section("Link health")
                    ForEach(Text_.healthLines(s), id: \.self) { Text($0) }
                    Text(Text_.countsLine("Live frames", s.liveCounts)).font(.footnote)
                    Text(Text_.countsLine("Backlog frames (old, display only)", s.backlogCounts)).font(.footnote)

                    Divider().padding(.vertical, 6)
                    section("Pair drone pod")
                    if let line = Text_.pairedLine(s) {
                        HStack {
                            Text(line)
                            Spacer()
                            Button("Forget") { center.forgetPairedPod() }
                        }
                        ForEach(Text_.pairedPodWarnings(s), id: \.self) { Text($0).font(.footnote).foregroundStyle(.red) }
                    }
                    if s.windowOpen && s.pairing.step == .idle {
                        Text("The Desk Pod pairing window is open (\(s.windowSecondsLeft) s left).").font(.footnote)
                    }
                    pairing(s.pairing)

                    Divider().padding(.vertical, 6)
                    section("Steering")
                    if let st = s.lastSteer {
                        HStack {
                            SteeringMark(view: st, size: 36)
                            Text(Text_.steerLine(st))
                        }
                        if st.muted { Text(Text_.steerMuted).fontWeight(.semibold) }
                    } else {
                        Text(Text_.steerNone).font(.footnote)
                    }
                    Text(Text_.steerMuteHint).font(.footnote).foregroundStyle(.secondary)
                    if s.pairedPod != nil {
                        Button(Text_.clearMutes) { center.clearSteeringMutes() }.buttonStyle(.bordered)
                    }
                    Text(Text_.steerTileLine(chosen: center.steeringStreamID, liveCount: center.liveSteeringStreams.count)).font(.footnote)
                    if center.steeringStreamID != nil {
                        Button(Text_.steerChooseAgain) { center.chooseSteeringStream(nil) }.buttonStyle(.bordered)
                    }
                    Toggle(Text_.steerRoll, isOn: Binding(get: { s.steeringRollCorrection }, set: { center.setSteeringRollCorrection($0) }))
                        .font(.footnote)
                }
                .frame(maxWidth: 720, alignment: .leading)
                .padding()
            }
            .navigationTitle(Text_.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Dismiss", action: onDismiss) }
            }
        }
    }

    private func section(_ title: String) -> some View {
        Text(title).font(.headline).padding(.top, 4)
    }

    @ViewBuilder
    private func pairing(_ p: BtrxDeskPodModel.Pairing) -> some View {
        let active = BtrxDeskPodModel.activeSteps.contains(p.step)
        if p.step == .idle {
            Button("Pair drone pod") { center.startPairing() }.buttonStyle(.borderedProminent)
        } else {
            ForEach(Array(Text_.steps(p).enumerated()), id: \.offset) { i, step in
                HStack(alignment: .top, spacing: 8) {
                    Text(mark(step.status)).bold().foregroundStyle(color(step.status)).frame(width: 20, alignment: .leading)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(i + 1). \(step.title)").fontWeight(step.status == .active ? .semibold : .regular)
                        if !step.detail.isEmpty { Text(step.detail).font(.footnote) }
                    }
                }
            }
            ForEach(p.candidate?.warnings ?? [], id: \.self) { Text($0).font(.footnote).foregroundStyle(.red) }
            if !p.message.isEmpty {
                Text(p.message).fontWeight(.semibold)
                    .foregroundStyle(p.step == .failed ? Color.red : p.step == .paired ? Self.green : Color.primary)
            }
            HStack(spacing: 8) {
                if p.step == .podFound && !p.podStale { Button("Confirm pod") { center.confirmPod() }.buttonStyle(.borderedProminent) }
                if active { Button("Cancel pairing") { center.cancelPairing() }.buttonStyle(.bordered) }
                if !active {
                    Button(p.step == .paired ? "Pair another" : "Try again") { center.startPairing() }.buttonStyle(.borderedProminent)
                    Button("Clear result") { center.clearPairingResult() }
                }
            }
            .padding(.top, 4)
        }
    }

    private func mark(_ status: BtrxDeskPodText.StepStatus) -> String {
        switch status {
        case .done: return "✓"
        case .failed: return "✗"
        case .active: return "▶"
        case .pending: return "○"
        }
    }

    private func color(_ status: BtrxDeskPodText.StepStatus) -> Color {
        switch status {
        case .done: return Self.green
        case .failed: return .red
        case .active: return .accentColor
        case .pending: return .secondary
        }
    }
}

// MARK: steering overlay (btrx design doc section 41)

private struct BtrxDeskPodKey: EnvironmentKey {
    static let defaultValue: BtrxDeskPodCenter? = nil
}

extension EnvironmentValues {
    /// The Desk Pod link, for the stream tiles' steering overlay (set by ContentView).
    var btrxDeskPod: BtrxDeskPodCenter? {
        get { self[BtrxDeskPodKey.self] }
        set { self[BtrxDeskPodKey.self] = newValue }
    }
}

/// The pilot's steering overlay on a live stream tile: one circle and vector in the
/// source's color and one hint word, no numbers. Tapping the circle (or "Mute") mutes
/// that source on the pod. Shows only while the Desk Pod link is up, a Drone Pod is
/// paired and STEER keeps arriving, and only on the drone carrying the pod
/// (BtrxSteeringTarget): the single live tile, or with several live the one marked
/// "Drone Pod is on this drone". Same as Android's BtrxSteeringOverlay.
struct AppleSteeringOverlay: View {
    let tile: String
    @Environment(\.btrxDeskPod) private var center

    var body: some View {
        if let center { AppleSteeringOverlayContent(center: center, tile: tile) }
    }
}

private struct AppleSteeringOverlayContent: View {
    @ObservedObject var center: BtrxDeskPodCenter
    let tile: String

    var body: some View {
        let live = AppleStreamRegistry.shared.liveSteeringStreamIDs
        let chosen = center.steeringStreamID
        if center.snapshot.steerOverlay != nil, !BtrxSteeringTarget.showsOverlay(tile: tile, live: live, chosen: chosen) {
            // Several live streams and none marked yet: offer the pick on each live tile.
            if BtrxSteeringTarget.offersPick(tile: tile, live: live, chosen: chosen) {
                Button { center.chooseSteeringStream(tile) } label: {
                    Text(BtrxDeskPodText.steerPick)
                        .font(.system(size: 13))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            }
        } else if let v = center.snapshot.steerOverlay {
            VStack(spacing: 2) {
                SteeringMark(view: v, size: 64)
                    .contentShape(Circle())
                    .onTapGesture { if v.canMute { center.muteSteeringSource() } }
                    .accessibilityElement()
                    .accessibilityLabel("\(v.hint.word). \(BtrxDeskPodText.mute)")
                    .accessibilityAddTraits(.isButton)
                Text(v.hint.word)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.white)
                if v.canMute {
                    Button { center.muteSteeringSource() } label: {
                        Text(BtrxDeskPodText.mute)
                            .font(.caption)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 3)
                            .background(.white.opacity(0.18), in: RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 10))
        }
    }
}

/// Circle (shape by device kind) and vector in the source's palette color. Also the Desk
/// Pod screen's swatch. Same drawing as Android's SteeringMark.
struct SteeringMark: View {
    let view: BtrxSteering.View
    let size: CGFloat

    static func color(_ rgb: Int) -> Color {
        Color(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }

    var body: some View {
        let color = Self.color(view.colorIndex.map { BtrxSteering.palette[$0] } ?? BtrxSteering.gray)
        let vectorColor = view.gray ? Self.color(BtrxSteering.gray) : color
        let outline = Self.color(BtrxSteering.outline)
        Canvas { ctx, sz in
            let m = min(sz.width, sz.height)
            let r = m * 0.22
            let c = CGPoint(x: sz.width / 2, y: sz.height / 2)
            let circle = Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
            switch view.devClass {
            case BtrxProtocol.DEV_PHONE:
                ctx.fill(circle, with: .color(color))
                ctx.stroke(circle, with: .color(outline), lineWidth: 1.5)
            case BtrxProtocol.DEV_WEARABLE, BtrxProtocol.DEV_AUDIO:
                ctx.stroke(circle, with: .color(outline), lineWidth: 5)
                ctx.stroke(circle, with: .color(color), lineWidth: 3)
                let inner = Path(ellipseIn: CGRect(x: c.x - r / 2, y: c.y - r / 2, width: r, height: r))
                ctx.stroke(inner, with: .color(color), lineWidth: 3)
            case BtrxProtocol.DEV_TRACKER:
                ctx.stroke(circle, with: .color(outline), lineWidth: 5)
                ctx.stroke(circle, with: .color(color), style: StrokeStyle(lineWidth: 3, dash: [4, 3]))
            default:
                ctx.stroke(circle, with: .color(outline), lineWidth: 5)
                ctx.stroke(circle, with: .color(color), lineWidth: 3)
            }
            guard let dir = view.vector else { return }
            let rad = Double(dir.degrees) * .pi / 180
            let dx = CGFloat(sin(rad)), dy = CGFloat(-cos(rad))
            let len = m * 0.46
            let start = CGPoint(x: c.x + dx * (r + 2), y: c.y + dy * (r + 2))
            let end = CGPoint(x: c.x + dx * len, y: c.y + dy * len)
            var shaft = Path()
            shaft.move(to: start); shaft.addLine(to: end)
            let head = CGFloat(8)
            for side in [-1.0, 1.0] {
                let a = rad + side * 150 * .pi / 180
                shaft.move(to: end)
                shaft.addLine(to: CGPoint(x: end.x + CGFloat(sin(a)) * head, y: end.y - CGFloat(cos(a)) * head))
            }
            ctx.stroke(shaft, with: .color(outline), style: StrokeStyle(lineWidth: 7, lineCap: .round))
            ctx.stroke(shaft, with: .color(vectorColor), style: StrokeStyle(lineWidth: 4, lineCap: .round))
        }
        .frame(width: size, height: size)
        .opacity(view.faded ? 0.4 : 1)
    }
}
