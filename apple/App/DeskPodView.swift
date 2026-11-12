import R2CAppleRadios
import R2CCore
import SwiftUI

/// Main menu > BTRX: the btrx Desk Pod Bluetooth link, its status and link health,
/// and the "Pair drone pod" flow. Same sections, order and wording as Android's
/// DeskPodScreen. "Dismiss" only hides this panel; the link keeps running (a calibration
/// in progress too). "Cancel pairing" ends a pairing in progress; "Cancel calibration"
/// ends the IMU calibration flow.
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
                    section(Text_.imuCalTitle)
                    imuCalSection(s)

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

    /// Drone Pod IMU calibration (design doc section 29.3): Calibrate (level step at once),
    /// then Start starboard step (countdown, then starboard down), results in plain words,
    /// Retry starboard step after a failed one, Check and Clear saved calibration. Same as
    /// Android's ImuCalSection.
    @ViewBuilder
    private func imuCalSection(_ s: BtrxDeskPodModel.Snapshot) -> some View {
        if let v = s.imuCal {
            let enabled = s.pairedPod != nil
            Text(BtrxImuCal.calibratedLine(v)).fontWeight(v.calibrated == true ? .semibold : .regular)
            Text(Text_.imuCalHint).font(.footnote).foregroundStyle(.secondary)
            if !v.message.isEmpty {
                Text(v.message)
                    .fontWeight(v.step == .CALIBRATED ? .semibold : .regular)
                    .foregroundStyle(v.step == .FAILED ? Color.red : Color.primary)
            }
            if v.step == .STBD_COUNTDOWN {
                Text(Text_.imuCalCountdown(v.countdownS)).font(.title2).monospacedDigit()
            }
            HStack(spacing: 8) {
                if v.canStartStarboard {
                    Button(v.step == .FAILED ? Text_.imuCalStbdRetry : Text_.imuCalStbd) { center.startImuCalStarboard() }
                        .buttonStyle(.borderedProminent).disabled(!enabled)
                }
                if v.canStart {
                    let first = v.step == .IDLE
                    if first && !v.canStartStarboard {
                        Button(Text_.imuCalStart) { center.startImuCal() }.buttonStyle(.borderedProminent).disabled(!enabled)
                    } else {
                        Button(first ? Text_.imuCalStart : Text_.imuCalStartAgain) { center.startImuCal() }.buttonStyle(.bordered).disabled(!enabled)
                    }
                }
                if v.canCancel { Button(Text_.imuCalCancel) { center.cancelImuCal() }.buttonStyle(.bordered) }
                if v.canClose { Button(Text_.imuCalClose) { center.closeImuCalResult() } }
            }
            if v.canCheckOrClear {
                HStack(spacing: 8) {
                    Button(Text_.imuCalCheck) { center.checkImuCal() }.buttonStyle(.bordered).disabled(!enabled)
                    Button(Text_.imuCalClear) { center.clearImuCal() }.buttonStyle(.bordered).disabled(!enabled)
                }
            }
        }
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
/// source's color (the hint word only as a small label), no numbers. Tapping the circle (or "Mute") mutes
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
                // The arrow carries the meaning; the word is a small secondary label only.
                SteeringMark(view: v, size: 132)
                    .contentShape(Circle())
                    .onTapGesture { if v.canMute { center.muteSteeringSource() } }
                    .accessibilityElement()
                    .accessibilityLabel("\(v.hint.word). \(BtrxDeskPodText.mute)")
                    .accessibilityAddTraits(.isButton)
                Text(v.hint.word)
                    .font(.system(size: 10))
                    .foregroundStyle(.white.opacity(0.75))
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
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 10))
        }
    }
}

/// Circle (shape by device kind) and the pilot's arrow (BtrxSteering.arrowGeometry) in the
/// source's palette color: the arrow points the way to turn (up = straight on, U-turn = turn
/// around) and is longer the weaker (farther) the source; a chevron behind the head means
/// closing. Icons instead of words: LEVEL a gray arrow and a wings-level badge, LOST a slashed
/// circle and no arrow, muted a crossed-out speaker. Also the Desk Pod screen's swatch. Same
/// drawing as Android's SteeringMark.
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
            let h = min(sz.width, sz.height) / 2
            let c = CGPoint(x: sz.width / 2, y: sz.height / 2)
            func pt(_ p: BtrxSteering.Pt) -> CGPoint { CGPoint(x: c.x + CGFloat(p.x) * h, y: c.y + CGFloat(p.y) * h) }
            func poly(_ points: [BtrxSteering.Pt]) -> Path {
                var path = Path()
                for (i, p) in points.enumerated() { if i == 0 { path.move(to: pt(p)) } else { path.addLine(to: pt(p)) } }
                return path
            }
            func ring(_ center: CGPoint, _ radius: CGFloat) -> Path {
                Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: 2 * radius, height: 2 * radius))
            }
            let unit = h / 66 // stroke widths scale with the mark (66 = half of the 132 pt overlay)
            let r = CGFloat(BtrxSteering.markCircleR) * h
            let circle = ring(c, r)
            switch view.devClass {
            case BtrxProtocol.DEV_PHONE:
                ctx.fill(circle, with: .color(color))
                ctx.stroke(circle, with: .color(outline), lineWidth: 1.5 * unit)
            case BtrxProtocol.DEV_WEARABLE, BtrxProtocol.DEV_AUDIO:
                ctx.stroke(circle, with: .color(outline), lineWidth: 5 * unit)
                ctx.stroke(circle, with: .color(color), lineWidth: 3 * unit)
                ctx.stroke(ring(c, r / 2), with: .color(color), lineWidth: 3 * unit)
            case BtrxProtocol.DEV_TRACKER:
                ctx.stroke(circle, with: .color(outline), lineWidth: 5 * unit)
                ctx.stroke(circle, with: .color(color), style: StrokeStyle(lineWidth: 3 * unit, dash: [8 * unit, 6 * unit]))
            default:
                ctx.stroke(circle, with: .color(outline), lineWidth: 5 * unit)
                ctx.stroke(circle, with: .color(color), lineWidth: 3 * unit)
            }
            if let dir = view.vector {
                let arrow = BtrxSteering.arrowGeometry(dir, length: view.length, closer: view.closer)
                let shaft = poly(arrow.shaft)
                ctx.stroke(shaft, with: .color(outline), style: StrokeStyle(lineWidth: 12 * unit, lineCap: .round, lineJoin: .round))
                ctx.stroke(shaft, with: .color(vectorColor), style: StrokeStyle(lineWidth: 8 * unit, lineCap: .round, lineJoin: .round))
                var head = poly(arrow.head)
                head.closeSubpath()
                ctx.fill(head, with: .color(vectorColor))
                ctx.stroke(head, with: .color(outline), style: StrokeStyle(lineWidth: 2 * unit, lineJoin: .round))
                if !arrow.chevron.isEmpty {
                    let chev = poly(arrow.chevron)
                    ctx.stroke(chev, with: .color(outline), style: StrokeStyle(lineWidth: 8 * unit, lineCap: .round, lineJoin: .round))
                    ctx.stroke(chev, with: .color(vectorColor), style: StrokeStyle(lineWidth: 5 * unit, lineCap: .round, lineJoin: .round))
                }
            } else {
                // LOST: slash through the circle and a dashed ring; no arrow.
                let d = r * 1.5
                ctx.stroke(ring(c, d), with: .color(outline), lineWidth: 5 * unit)
                ctx.stroke(ring(c, d), with: .color(.white), style: StrokeStyle(lineWidth: 3 * unit, dash: [6 * unit, 5 * unit]))
                var slash = Path()
                slash.move(to: CGPoint(x: c.x - d * 0.72, y: c.y - d * 0.72))
                slash.addLine(to: CGPoint(x: c.x + d * 0.72, y: c.y + d * 0.72))
                ctx.stroke(slash, with: .color(outline), style: StrokeStyle(lineWidth: 7 * unit, lineCap: .round))
                ctx.stroke(slash, with: .color(.white), style: StrokeStyle(lineWidth: 4 * unit, lineCap: .round))
            }
            if view.hint == .level {
                // LEVEL badge: an attitude-indicator ring with a level wings line through it.
                let b = CGPoint(x: c.x - h * 0.72, y: c.y + h * 0.78), s = h * 0.2
                ctx.stroke(ring(b, s), with: .color(outline), lineWidth: 5 * unit)
                ctx.stroke(ring(b, s), with: .color(.white), lineWidth: 3 * unit)
                var wings = Path()
                wings.move(to: CGPoint(x: b.x - s * 1.6, y: b.y)); wings.addLine(to: CGPoint(x: b.x + s * 1.6, y: b.y))
                ctx.stroke(wings, with: .color(outline), style: StrokeStyle(lineWidth: 6 * unit, lineCap: .round))
                ctx.stroke(wings, with: .color(.white), style: StrokeStyle(lineWidth: 3.5 * unit, lineCap: .round))
                ctx.fill(ring(b, 2.5 * unit), with: .color(.white))
            }
            if view.muted {
                // Muted badge: a speaker with a cross (a source is muted on the pod; STEER bit 5).
                let b = CGPoint(x: c.x + h * 0.72, y: c.y + h * 0.78), s = h * 0.17
                var speaker = Path()
                speaker.move(to: CGPoint(x: b.x - s * 1.1, y: b.y - s * 0.35))
                speaker.addLine(to: CGPoint(x: b.x - s * 0.55, y: b.y - s * 0.35))
                speaker.addLine(to: CGPoint(x: b.x, y: b.y - s * 0.9))
                speaker.addLine(to: CGPoint(x: b.x, y: b.y + s * 0.9))
                speaker.addLine(to: CGPoint(x: b.x - s * 0.55, y: b.y + s * 0.35))
                speaker.addLine(to: CGPoint(x: b.x - s * 1.1, y: b.y + s * 0.35))
                speaker.closeSubpath()
                ctx.fill(speaker, with: .color(.white))
                ctx.stroke(speaker, with: .color(outline), lineWidth: 1.5 * unit)
                var cross = Path()
                cross.move(to: CGPoint(x: b.x + s * 0.35, y: b.y - s * 0.45)); cross.addLine(to: CGPoint(x: b.x + s * 1.15, y: b.y + s * 0.45))
                cross.move(to: CGPoint(x: b.x + s * 0.35, y: b.y + s * 0.45)); cross.addLine(to: CGPoint(x: b.x + s * 1.15, y: b.y - s * 0.45))
                ctx.stroke(cross, with: .color(outline), style: StrokeStyle(lineWidth: 5.5 * unit, lineCap: .round))
                ctx.stroke(cross, with: .color(.white), style: StrokeStyle(lineWidth: 3 * unit, lineCap: .round))
            }
        }
        .frame(width: size, height: size)
        .opacity(view.faded ? 0.4 : 1)
    }
}
