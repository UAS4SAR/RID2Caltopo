import R2CAppleRadios
import R2CCore
import SwiftUI

/// Main menu > BTRX: the btrx Desk Pod Bluetooth link, its status and link health,
/// and the "Pair drone pod" flow. Same sections, order and wording as Android's
/// DeskPodScreen. "Dismiss" only hides this panel; the link keeps running (a calibration
/// in progress too). "Cancel pairing" ends a pairing in progress; "Cancel calibration"
/// ends the IMU calibration flow. One Desk Pod and one Drone Pod per app: "Replace Desk
/// Pod" / "Replace Drone Pod" ask first, then forget or unpair the current one; there is
/// no "Pair another".
struct DeskPodView: View {
    @ObservedObject var center: BtrxDeskPodCenter
    let onDismiss: () -> Void
    @State private var replacing: Replacing?

    private enum Replacing: Identifiable { case deskPod, dronePod, repairDronePod; var id: Self { self } }

    private typealias Text_ = BtrxDeskPodText
    private static let green = Color(red: 0x2E / 255, green: 0x7D / 255, blue: 0x32 / 255)

    var body: some View {
        let s = center.snapshot
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    // The map's button panel is behind this sheet: show it here too.
                    if let v = s.buttonConfirm { BtrxButtonConfirmCard(center: center, view: v).padding(.bottom, 8) }
                    // Opens in Safari (the system browser), like Android's ACTION_VIEW.
                    if let url = URL(string: Text_.learnMoreURL) {
                        Link(Text_.learnMore, destination: url)
                    }
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
                        Button(Text_.replaceDesk) { replacing = .deskPod }.buttonStyle(.bordered)
                    }

                    Divider().padding(.vertical, 6)
                    section("Desk Pod")
                    Text(Text_.roleLine(s))
                    Text(Text_.firmwareLine(s))
                    Text(Text_.buttonConfirmLine(s)).font(.footnote)
                    if let n = s.buttonLastNotice { Text(n.text).font(.footnote) }

                    Divider().padding(.vertical, 6)
                    section("Link health")
                    ForEach(Text_.healthLines(s), id: \.self) { Text($0) }
                    Text(Text_.liveFramesLine(s)).font(.footnote)
                    Text(Text_.countsLine("Backlog frames (old, display only)", s.backlogCounts)).font(.footnote)
                    BtrxBearingStatusLines(center: center)

                    Divider().padding(.vertical, 6)
                    section("Pair drone pod")
                    if s.deskResetAtMs != nil { Text(Text_.deskResetWarning).fontWeight(.semibold).foregroundStyle(.red) }
                    if let line = Text_.pairedLine(s) {
                        Text(line)
                        ForEach(Text_.pairedPodWarnings(s), id: \.self) { Text($0).font(.footnote).foregroundStyle(.red) }
                    }
                    if s.windowOpen && s.pairing.step == .idle {
                        Text("The Desk Pod pairing window is open (\(s.windowSecondsLeft) s left, \(Text_.deskPairSourceText(s.windowSource))).").font(.footnote)
                    }
                    pairing(s.pairing)

                    Divider().padding(.vertical, 6)
                    section(Text_.imuCalTitle)
                    imuCalSection(s)

                    Divider().padding(.vertical, 6)
                    section("Steering")
                    // Ken, Nov 23: read only; telemetry follows the primary window and cannot be turned off.
                    Text(BtrxTelemetryRoute.shownOnLine(center.telemetryWindow)).fontWeight(.semibold)
                    Text(BtrxTelemetryRoute.hint).font(.footnote).foregroundStyle(.secondary)
                    if let st = s.lastSteer {
                        HStack {
                            SteeringMark(view: st, size: 36)
                            Text(Text_.steerLine(st))
                        }
                        Text(st.clue).fontWeight(.semibold)
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

                    Divider().padding(.vertical, 6)
                    section(BtrxBearingText.section)
                    BtrxBearingSection(center: center)
                }
                .frame(maxWidth: 720, alignment: .leading)
                .padding()
            }
            .navigationTitle(Text_.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Dismiss", action: onDismiss) }
            }
            .alert(alertTitle(replacing),
                   isPresented: Binding(get: { replacing != nil }, set: { if !$0 { replacing = nil } }),
                   presenting: replacing) { r in
                switch r {
                case .dronePod: Button(Text_.replacePodOK, role: .destructive) { center.replacePairedPod() }
                case .repairDronePod: Button(Text_.repairPodOK) { center.repairDronePod() }
                case .deskPod: Button(Text_.replaceDeskOK, role: .destructive) { center.forgetDeskPod() }
                }
                Button(Text_.replaceKeep, role: .cancel) {}
            } message: { r in
                switch r {
                case .dronePod: Text(Text_.replacePodMessage(s))
                case .repairDronePod: Text(Text_.repairPodMessage(s))
                case .deskPod: Text(Text_.replaceDeskMessage(s))
                }
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
            HStack { pairOrReplaceButton(retry: false) }
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
                    pairOrReplaceButton(retry: p.step == .failed)
                    Button("Clear result") { center.clearPairingResult() }
                }
            }
            .padding(.top, 4)
        }
    }

    private func alertTitle(_ r: Replacing?) -> String {
        switch r {
        case .dronePod: return Text_.replacePodTitle
        case .repairDronePod: return Text_.repairPodTitle
        default: return Text_.replaceDeskTitle
        }
    }

    /// "Pair drone pod" with none paired; "Re-pair Drone Pod" and "Replace Drone Pod" (both ask
    /// first) once one is paired. Same as Android.
    @ViewBuilder
    private func pairOrReplaceButton(retry: Bool) -> some View {
        if center.snapshot.pairedPod != nil {
            Button(Text_.repairPod) { replacing = .repairDronePod }.buttonStyle(.bordered)
            Button(Text_.replacePod) { replacing = .dronePod }.buttonStyle(.borderedProminent)
        } else {
            Button(retry ? "Try again" : Text_.pairPod) { center.startPairing() }.buttonStyle(.borderedProminent)
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
        // The card only while the stream is the primary window (Ken, Nov 23: no hide).
        let mode = BtrxSteeringTarget.tileOverlay(tile: tile, live: live, chosen: chosen,
                                                  hasView: center.snapshot.steerOverlay != nil,
                                                  onStream: BtrxTelemetryRoute.streamShows(center.telemetryWindow))
        if mode == .pick {
            // Several live streams and none marked yet: offer the pick on each live tile.
            Button { center.chooseSteeringStream(tile) } label: {
                Text(BtrxDeskPodText.steerPick)
                    .font(.system(size: 13))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
        } else if mode == .card, let v = center.snapshot.steerOverlay {
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
                // Clue from STEER company_id and dev_class, e.g. "Apple · wearable".
                Text(v.clue)
                    .font(.system(size: 12))
                    .foregroundStyle(.white)
                    .lineLimit(1)
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

// MARK: - BTRX bearing map layer (docs/btrx/BEARING-LAYER-spec.md sections 2.4, 3.4, 5.2, 7)

/// Developer Tools > BTRX "Bearing map layer": the layer switch, fade time and shape sliders
/// with the live "half strength at m:ss" readout, lag trace, and every source's row. Same
/// controls, labels and order as Android's BtrxBearingSection.
struct BtrxBearingSection: View {
    @ObservedObject var center: BtrxDeskPodCenter
    @State private var minutes: Double?
    @State private var shape: Double?
    @State private var maxPm: Double?
    @State private var minRpt: Double?

    var body: some View {
        let settings = center.bearingSettings
        let d = center.bearingDisplay
        let pm = maxPm ?? Double(BtrxBearingView.sliderFromMaxPm(d.maxPlusMinusM))
        let rpt = minRpt ?? Double(d.minReports)
        let noLimit = Double(BtrxBearingView.sliderFromMaxPm(0))
        let m = minutes ?? Double(settings.fadeMinutes)
        let p = shape ?? Double(settings.fadeShapeP)
        let draft = BtrxBearingSettings(fadeTotalS: Float(m.rounded()) * 60, fadeShapeP: Float(p))
        VStack(alignment: .leading, spacing: 6) {
            // Ken, Nov 23: no on/off; the layer runs while BTRX is enabled and the map shows it while primary.
            Text(BtrxBearingText.layerTitle).fontWeight(.semibold)
            Text(BtrxBearingText.layerHint).font(.footnote).foregroundStyle(.secondary)
            if !center.bearingOn, let error = center.bearingError {
                Text(error).font(.footnote).foregroundStyle(.red)
                Button(BtrxBearingText.layerRetry) { center.retryBearingLayer() }.buttonStyle(.bordered)
            }

            Text("\(BtrxBearingText.fadeTime): \(BtrxBearingText.minutes(Int(m.rounded())))")
            Slider(value: Binding(get: { m }, set: { minutes = $0 }), in: 1...30, step: 1) { editing in
                if !editing { center.setBearingSettings(draft); minutes = nil }
            }
            Text("\(BtrxBearingText.fadeShape): \(BtrxBearingSettings.shapeLabel(Float(p))) (\(BtrxBearingText.halfStrength(draft.halfStrengthS)))")
            HStack {
                Text("Linear").font(.footnote)
                Slider(value: Binding(get: { p }, set: { shape = $0 }), in: 1...4) { editing in
                    if !editing { center.setBearingSettings(draft); shape = nil }
                }
                Text("Accelerating").font(.footnote)
            }
            Toggle(BtrxBearingText.lagTrace, isOn: Binding(get: { center.bearingLagTrace }, set: { center.setBearingLagTrace($0) }))
                .font(.footnote)
            // Nov 20: hide wide estimates and thin labels; mute sources at the takeoff spot. Same as Android.
            Text("\(BtrxBearingView.settingMaxPm): \(BtrxBearingView.maxPlusMinusText(BtrxBearingView.maxPmFromSlider(Float(pm))))")
            Slider(value: Binding(get: { pm }, set: { maxPm = $0 }), in: Double(BtrxBearingDisplay.minPlusMinusM)...noLimit, step: 10) { editing in
                if !editing {
                    var n = d; n.maxPlusMinusM = BtrxBearingView.maxPmFromSlider(Float(pm))
                    center.setBearingDisplay(n); maxPm = nil
                }
            }
            Text("\(BtrxBearingView.settingMinReports): \(BtrxBearingView.minReportsText(Int(rpt.rounded())))")
            Slider(value: Binding(get: { rpt }, set: { minRpt = $0 }), in: 1...Double(BtrxBearingDisplay.maxMinReports), step: 1) { editing in
                if !editing {
                    var n = d; n.minReports = Int(rpt.rounded())
                    center.setBearingDisplay(n); minRpt = nil
                }
            }
            Toggle(BtrxBearingView.settingLaunchMute, isOn: Binding(get: { d.launchAutoMute },
                                                                    set: { var n = d; n.launchAutoMute = $0; center.setBearingDisplay(n) }))
            Text(BtrxBearingView.settingLaunchMuteHint).font(.footnote).foregroundStyle(.secondary)
            if center.bearingOn, let line = center.bearingStatus?.line { Text(line).font(.footnote).foregroundStyle(.red) }
            // Counters also before the first snapshot (no drone pose yet), from the status copy.
            if center.bearingOn, center.bearingSnapshot == nil, let st = center.bearingStatus?.stats {
                Text(BtrxBearingText.countsLine(st)).font(.footnote)
                Text(BtrxBearingText.clockLine(st)).font(.footnote)
            }
            if center.bearingOn, let snap = center.bearingSnapshot {
                Text(BtrxBearingText.countsLine(snap.stats)).font(.footnote)
                Text(BtrxBearingText.clockLine(snap.stats)).font(.footnote)
                Text(BtrxBearingText.sources).fontWeight(.semibold)
                if snap.sources.isEmpty { Text(BtrxBearingText.noSources).font(.footnote) }
                ForEach(snap.sources) { src in
                    BtrxBearingSourceRow(source: src, nowUs: snap.tUs) { shown in center.bearing.setMuted([src.key], muted: !shown) }
                    ownAircraftControl(src)
                }
            }
            if pod.savedIgnoreSupported || !pod.savedIgnores.isEmpty { savedOwnAircraft }
        }
    }

    private var pod: BtrxDeskPodModel.Snapshot { center.snapshot }

    /// Drone Pod 0x0106+: "Own aircraft (saved)" by src_id (STEER_MUTE op 3 / 4); older firmware:
    /// the local "Own aircraft" on this device only. Same as Android.
    @ViewBuilder
    private func ownAircraftControl(_ src: BtrxBearingSource) -> some View {
        if pod.savedIgnoreSupported && src.srcId != 0 {
            if pod.savedIgnores.contains(src.srcId) {
                HStack {
                    Text("\(BtrxDeskPodText.ownSaved): \(BtrxDeskPodText.ownSavedDone)").font(.footnote)
                    Button(BtrxDeskPodText.ownSavedRemove) { center.unignoreSourceSaved(src.srcId) }
                        .font(.footnote).disabled(pod.filterPendingFor(src.srcId))
                }
            } else {
                Button(BtrxDeskPodText.ownSaved) { center.ignoreSourceSaved(src.srcId) }
                    .font(.footnote).disabled(pod.filterPendingFor(src.srcId))
            }
        } else {
            Button(src.ignored ? BtrxBearingText.unignore : BtrxBearingText.ignore) {
                center.bearing.setIgnored(src.key, ignored: !src.ignored)
            }
            .font(.footnote)
        }
    }

    /// The paired Drone Pod's saved own aircraft (build 0x0106), each with Remove, and the latest result.
    @ViewBuilder
    private var savedOwnAircraft: some View {
        Text(BtrxDeskPodText.ownSavedTitle(pod.savedIgnores.count)).fontWeight(.semibold)
        Text(BtrxDeskPodText.ownSavedHint).font(.footnote).foregroundStyle(.secondary)
        ForEach(pod.savedIgnores, id: \.self) { id in
            HStack {
                Text(BtrxDeskPodText.srcIdText(id)).font(.footnote)
                Spacer()
                Button(BtrxDeskPodText.ownSavedRemove) { center.unignoreSourceSaved(id) }
                    .font(.footnote).disabled(pod.filterPendingFor(id) || !pod.savedIgnoreSupported)
            }
        }
        if let m = pod.filterMessage { Text(m).font(.footnote) }
    }
}

/// "Link health" lines (spec 3.4): reports lost while no app, and the lag stats.
struct BtrxBearingStatusLines: View {
    @ObservedObject var center: BtrxDeskPodCenter

    var body: some View {
        if center.bearingOn, let st = center.bearingSnapshot?.stats ?? center.bearingStatus?.stats {
            Text(BtrxBearingText.lostLine(st))
            Text(BtrxBearingText.lagLine(st)).font(.footnote)
        }
    }
}

/// One row: swatch, checkbox (checked = shown), short ID, class, last heard, reports in the fade window.
struct BtrxBearingSourceRow: View {
    let source: BtrxBearingSource
    let nowUs: Int64
    let onShownChange: (Bool) -> Void

    var body: some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 3)
                .fill(Color(rgb: source.rgb))
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color.black.opacity(0.4), lineWidth: 1))
                .frame(width: 16, height: 16)
            Button { onShownChange(source.muted) } label: {
                Image(systemName: source.muted ? "square" : "checkmark.square.fill").imageScale(.large)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(source.muted ? "Show \(source.shortID)" : "Hide \(source.shortID)")
            Text(source.shortID).fontWeight(.semibold).lineLimit(1)
            // Nov 23: a source first heard in the last 10 s is tagged "new". Same as Android.
            if BtrxStaleness.isNew(source, nowUs: nowUs) {
                Text(BtrxStaleness.newTag).font(.caption2.weight(.semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 4).background(Color.accentColor, in: RoundedRectangle(cornerRadius: 4))
            }
            // Two lines (Nov 20 A5 test: in a narrow panel "136 rpt" wrapped one letter per line). Same as Android.
            VStack(alignment: .leading, spacing: 0) {
                Text(source.className).lineLimit(1)
                Text(BtrxBearingText.lastHeard(nowUs: nowUs, lastUs: source.lastHeardUs) + " · " + BtrxBearingText.reports(source.reportsInWindow)
                     + (source.lastRssiDbm.map { " · \($0) dBm" } ?? ""))
                    .font(.footnote).lineLimit(1)
                if source.ownSuspect { Text(BtrxBearingText.ownSuspectTag).font(.footnote).foregroundStyle(.red) }
            }
        }
    }
}

/// A popup's content as is when it fits the height offered, else in a ScrollView of that height
/// (BtrxPopupLayout caps the height above the video PiP; Nov 20). Same as Android's verticalScroll.
struct BtrxScrollsWhenCapped<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        ViewThatFits(in: .vertical) {
            content()
            ScrollView(.vertical) { content() }
        }
    }
}

/// Popup header: the title, then × at the top right (Ken, Nov 23 watch test). × dismisses the
/// popup only: focus, mutes and ignores stay as they are. Same as Android's BtrxPopupHeader.
struct BtrxPopupHeader<Title: View>: View {
    let onDismiss: () -> Void
    @ViewBuilder var title: () -> Title
    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            title()
            Spacer(minLength: 8)
            Button(action: onDismiss) {
                Image(systemName: "xmark").font(.body.weight(.semibold)).frame(width: 32, height: 32).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(BtrxBearingText.dismiss)
        }
    }
}

/// Places one map popup (the tap panel at `tapAt`, or the inspect card `bottomStart` in from the
/// bottom-left) with BtrxPopupPlacement: inside the map, clear of `obstacle` (the video PiP, in
/// this layout's coordinates), flipped left or up, height capped so the popup scrolls (Ken,
/// Nov 20). Same as Android's BtrxPopupHost.
struct BtrxPopupLayout: Layout {
    var tapAt: CGPoint? = nil
    var bottomStart: CGSize? = nil
    var obstacle: CGRect?
    /// Height of the map attribution strip at the bottom: popups stay above it (Ken, Nov 23).
    var reservedBottom: CGFloat = 0
    var margin: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        let maxW = max(1, bounds.width - 2 * margin)
        let natural = child.sizeThatFits(ProposedViewSize(width: maxW, height: nil))
        let cap = plan(bounds.size, natural).maxHeight
        let sized = child.sizeThatFits(ProposedViewSize(width: maxW, height: cap))
        let final = plan(bounds.size, CGSize(width: sized.width, height: min(sized.height, cap)))
        child.place(at: CGPoint(x: bounds.minX + final.x, y: bounds.minY + final.y), anchor: .topLeading,
                    proposal: ProposedViewSize(width: min(sized.width, maxW), height: min(sized.height, cap)))
    }

    private func plan(_ size: CGSize, _ popup: CGSize) -> BtrxPopupPlacement.Plan {
        let obs = obstacle.map { BtrxPopupPlacement.Box(left: $0.minX, top: $0.minY, right: $0.maxX, bottom: $0.maxY) }
        let height = BtrxPopupPlacement.usableHeight(size.height, reservedBottom: reservedBottom)
        if let bottomStart {
            return BtrxPopupPlacement.atBottomStart(width: size.width, height: height, popupW: popup.width, popupH: popup.height,
                                                    left: bottomStart.width,
                                                    bottom: BtrxPopupPlacement.bottomAboveReserved(bottomStart.height, reservedBottom: reservedBottom),
                                                    obstacle: obs, margin: margin)
        }
        return BtrxPopupPlacement.atPoint(width: size.width, height: height, popupW: popup.width, popupH: popup.height,
                                          x: Double(tapAt?.x ?? 0), y: Double(tapAt?.y ?? 0), obstacle: obs, margin: margin)
    }
}

/// The map tap panel (spec 5.2): "BTRX sources here", one row per source in tap order,
/// "Mute all" / "Unmute all" for the rows listed, "Done". Rows only show or hide shading;
/// the row content follows the live snapshot. Same as Android's BtrxBearingPanel.
struct BtrxBearingPanel: View {
    @ObservedObject var center: BtrxDeskPodCenter
    let rows: [UInt64]
    let onDismiss: () -> Void
    var onFocus: (BtrxBearingSource) -> Void = { _ in }
    // Nov 23 (Ken): strongest and freshest first, stale ones sink and leave; re-sorted at most
    // every 12 s and never under a finger, so a row does not move as it is tapped. Same as Android.
    @State private var order = BtrxPanelOrder()
    @GestureState private var holding = false
    @State private var showOlder = false

    var body: some View {
        if let snap = center.bearingSnapshot {
            let byKey = Dictionary(snap.sources.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
            let here = rows.compactMap { byKey[$0] }
            let listed = order.arrange(here, nowUs: snap.tUs, holding: holding, showOlder: showOlder)
            let older = order.olderCount(here, nowUs: snap.tUs)
            VStack(alignment: .leading, spacing: 4) {
            // Title and × stay put while the rows scroll (Ken, Nov 23: Done was below the fold).
            BtrxPopupHeader(onDismiss: onDismiss) { Text(BtrxBearingText.panelTitle).font(.headline) }
            BtrxScrollsWhenCapped { VStack(alignment: .leading, spacing: 4) {
                if listed.isEmpty { Text(BtrxBearingText.panelAllStale).font(.footnote) }
                ForEach(listed) { src in
                    HStack {
                        BtrxBearingSourceRow(source: src, nowUs: snap.tUs) { shown in center.bearing.setMuted([src.key], muted: !shown) }
                        Spacer(minLength: 4)
                        Button(BtrxBearingText.focus) { onFocus(src) }.font(.footnote)
                    }
                    .opacity(0.4 + 0.6 * Double(BtrxStaleness.presence(src, nowUs: snap.tUs)))
                }
                if older > 0 || showOlder {
                    Button(showOlder ? BtrxBearingText.hideOlder : BtrxBearingText.showOlder(older)) { showOlder.toggle() }.font(.footnote)
                }
                HStack(spacing: 8) {
                    Button(BtrxBearingText.muteAll) { center.bearing.setMuted(listed.map(\.key), muted: true) }.buttonStyle(.bordered)
                    Button(BtrxBearingText.unmuteAll) { center.bearing.setMuted(listed.map(\.key), muted: false) }.buttonStyle(.bordered)
                    Button(BtrxBearingText.done, action: onDismiss)
                }
            } }
            // Watch only: a finger on the list holds the order (reset when the touch ends or is cancelled).
            .simultaneousGesture(DragGesture(minimumDistance: 0).updating($holding) { _, state, _ in state = true })
            }
            .padding(12)
            .frame(maxWidth: 420, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .shadow(radius: 6)
        }
    }
}

/// "Save as own aircraft": ignored on this device at once, and on the pod by src_id too when
/// the firmware keeps a saved list. Nov 17: the pod save alone left the layer's source not
/// ignored, so the suggestion card stayed up. Same as Android's btrxSaveOwnAircraft.
@MainActor
func btrxSaveOwnAircraft(_ center: BtrxDeskPodCenter, _ src: BtrxBearingSource) {
    center.bearing.setIgnored(src.key, ignored: true)
    if center.snapshot.savedIgnoreSupported && src.srcId != 0 { center.ignoreSourceSaved(src.srcId) }
}

/// Own-aircraft suggestion (Nov 16 watch test): a source stronger than about -40 dBm for a
/// minute while the drone moved. A suggestion only: nothing is muted until Ken answers. A
/// one-line banner ("QBXFvg (unknown) on the drone?  Own aircraft  Not mine  ×"); × collapses
/// it to a chip, tap the chip to expand. Closes at once on either answer and never comes back
/// for that source. Same as Android.
struct BtrxOwnAircraftSuggestion: View {
    @ObservedObject var center: BtrxDeskPodCenter
    @State private var collapsedKey: UInt64?
    var body: some View {
        if BtrxTelemetryRoute.mapShows(bearingOn: center.bearingOn, window: center.telemetryWindow), let sources = center.bearingSnapshot?.sources,
           let src = BtrxOwnSuggestion.pick(sources, answers: center.ownAnswers) {
            if collapsedKey == src.key {
                Button { collapsedKey = nil } label: {
                    Text(BtrxBearingText.ownSuggestionCollapsed(src)).font(.caption)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Color.black.opacity(0.85), in: Capsule()).foregroundStyle(.white)
                }.buttonStyle(.plain)
            } else {
                HStack(spacing: 10) {
                    Text(BtrxBearingText.ownSuggestion(src)).font(.caption).lineLimit(1).truncationMode(.middle)
                        .frame(maxWidth: 220, alignment: .leading)
                    Button(BtrxBearingText.ownSuggestSave) { center.answerOwnSuggestion(src, own: true) }
                        .font(.caption.weight(.semibold)).foregroundStyle(Color(red: 0.54, green: 0.71, blue: 0.97))
                    Button(BtrxBearingText.ownSuggestKeep) { center.answerOwnSuggestion(src, own: false) }
                        .font(.caption).foregroundStyle(.white)
                    Button { collapsedKey = src.key } label: { Image(systemName: "xmark").font(.caption) }
                        .foregroundStyle(.white).accessibilityLabel(BtrxBearingText.ownSuggestCollapse)
                }
                .buttonStyle(.plain)
                .padding(.leading, 12).padding(.trailing, 10).padding(.vertical, 8)
                .foregroundStyle(.white)
                .background(Color.black.opacity(0.85), in: Capsule())
                .accessibilityElement(children: .contain)
                .accessibilityHint(BtrxBearingText.ownSuggestWhy)
            }
        }
    }
}

/// Inspect card of a pinned (triangulated) source. Same as Android's BtrxPinnedInspectCard.
struct BtrxPinnedInspectCard: View {
    @ObservedObject var center: BtrxDeskPodCenter
    let key: UInt64
    let onFocus: (BtrxBearingSource) -> Void
    let onDismiss: () -> Void
    var body: some View {
        if let snap = center.bearingSnapshot, let src = snap.sources.first(where: { $0.key == key }) {
            VStack(alignment: .leading, spacing: 3) {
            BtrxPopupHeader(onDismiss: onDismiss) {
                HStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 3).fill(Color(rgb: src.rgb)).frame(width: 16, height: 16)
                    Text(BtrxBearingText.pinnedTitle).font(.headline)
                }
            }
            BtrxScrollsWhenCapped { VStack(alignment: .leading, spacing: 3) {
                ForEach(BtrxBearingText.inspectLines(src, nowUs: snap.tUs), id: \.self) { Text($0).font(.footnote) }
                BtrxRestoreIgnoredButton(center: center)
                HStack(spacing: 8) {
                    Button(BtrxBearingText.focus) { onFocus(src) }.buttonStyle(.bordered)
                    BtrxIgnoreAllExceptButton(center: center, source: src)
                    Button(BtrxBearingText.close, action: onDismiss)
                }
            } }
            }
            .padding(12)
            .frame(maxWidth: 420, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .shadow(radius: 6)
        }
    }
}

/// The focus chip at the top of the map. While a source is focused: its short ID, "Ignore all
/// except this" and "Clear focus"; while a class/maker focus is on: what it shows. Always (with
/// sources on the map) "Focus…": the kinds and makers on the map, and "Hide the others"
/// (Nov 20). Also "n muted at takeoff · Show" for sources the layer muted at the launch point,
/// and "Restore ignored (n)" while "Ignore all except this" has ignores to undo. Same as Android.
struct BtrxFocusChip: View {
    @ObservedObject var center: BtrxDeskPodCenter
    @State private var menu = false
    var body: some View {
        let snap = BtrxTelemetryRoute.mapShows(bearingOn: center.bearingOn, window: center.telemetryWindow) ? center.bearingSnapshot : nil
        let src = snap.flatMap { s in s.focusKey == 0 ? nil : s.sources.first(where: { $0.key == s.focusKey }) }
        let restore = !center.ignoredByAll.isEmpty || (center.snapshot.ignoreAll?.saved.isEmpty == false)
        let launch = (snap?.sources ?? []).filter { $0.launch && !$0.ignored }
        let launchMuted = launch.filter(\.muted).count
        if center.bearingOn, let snap, !snap.sources.isEmpty || restore {
            HStack(spacing: 6) {
                if let src {
                    RoundedRectangle(cornerRadius: 2).fill(Color(rgb: src.rgb)).frame(width: 12, height: 12)
                    Text(src.shortID).font(.footnote)
                    BtrxIgnoreAllExceptButton(center: center, source: src).font(.footnote)
                }
                if let text = BtrxBearingView.filterText(snap.focusFilter) {
                    Text((src != nil ? "· " : "") + text).font(.footnote)
                }
                if snap.focusActive {
                    Button(BtrxBearingText.clearFocus) { center.bearing.clearFocus() }.font(.footnote)
                }
                Button(BtrxBearingView.focusMenu) { menu = true }.font(.footnote)
                    .popover(isPresented: $menu) { BtrxFocusMenu(center: center) }
                if let text = BtrxBearingView.launchChipText(mutedCount: launchMuted, flaggedCount: launch.count) {
                    Text(text).font(.footnote)
                    Button(launchMuted > 0 ? BtrxBearingView.launchShow : BtrxBearingView.launchHide) {
                        if launchMuted > 0 { center.bearing.unmuteLaunch() } else { center.bearing.setMuted(launch.map(\.key), muted: true) }
                    }.font(.footnote)
                }
                BtrxRestoreIgnoredButton(center: center).font(.footnote)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(Color(white: 0.125).opacity(0.9), in: Capsule())
        }
    }
}

/// "Focus on" (Nov 20): kinds and makers on the map with counts (tap toggles), "Hide the
/// others", "Clear focus". Applied at once (next tick). Same as Android.
struct BtrxFocusMenu: View {
    @ObservedObject var center: BtrxDeskPodCenter
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        let snap = center.bearingSnapshot
        let f = snap?.focusFilter ?? .none
        let options = BtrxBearingView.focusOptions(snap?.sources ?? [])
        NavigationStack {
            List {
                if options.isEmpty { Text(BtrxBearingView.focusNoneOnMap).font(.footnote) }
                let kinds = options.filter(\.isClass), makers = options.filter { !$0.isClass }
                if !kinds.isEmpty {
                    Section(BtrxBearingView.focusClasses) {
                        ForEach(kinds) { o in
                            row(o, on: f.classMask & (1 << o.value) != 0) { center.bearing.setFocusFilter(f.withClass(o.value)) }
                        }
                    }
                }
                if !makers.isEmpty {
                    Section(BtrxBearingView.focusMakers) {
                        ForEach(makers) { o in
                            row(o, on: f.companyId == o.value) { center.bearing.setFocusFilter(f.withCompany(o.value)) }
                        }
                    }
                }
                Toggle(BtrxBearingView.focusHideOthers, isOn: Binding(get: { f.hideOthers },
                                                                       set: { center.bearing.setFocusFilter(f.withHideOthers($0)) }))
            }
            .navigationTitle(BtrxBearingView.focusMenuTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BtrxBearingText.clearFocus) { center.bearing.clearFocus(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) { Button(BtrxBearingText.done) { dismiss() } }
            }
        }
        .frame(minWidth: 320, minHeight: 420)
    }

    private func row(_ o: BtrxBearingFocusOption, on: Bool, toggle: @escaping () -> Void) -> some View {
        Button(action: toggle) {
            HStack {
                Image(systemName: on ? "checkmark.square.fill" : "square")
                Text("\(o.label) (\(o.count))")
                Spacer()
            }
        }
    }
}

/// "Ignore all except this" (Nov 17): asks first with the count ("Ignore 27 other sources?"),
/// then ignores every other tracked source here and saves the strongest on the Drone Pod's
/// list (op 3, within its 32 entries) on the BTRX queue. Disabled when there is nothing else
/// to ignore. Same as Android.
struct BtrxIgnoreAllExceptButton: View {
    @ObservedObject var center: BtrxDeskPodCenter
    let source: BtrxBearingSource
    @State private var plan: BtrxIgnoreAllPlan?
    var body: some View {
        let preview = center.bearingSnapshot.map {
            BtrxIgnoreAllExcept.plan($0.sources, nowUs: $0.tUs, keep: source, savedSrcIds: center.snapshot.savedIgnores,
                                     podStored: center.snapshot.podListStored, podSupported: center.snapshot.savedIgnoreSupported)
        }
        Button(BtrxBearingText.ignoreAllExcept) { plan = preview }
            .disabled((preview?.count ?? 0) == 0)
            .alert(BtrxBearingText.ignoreAllTitle(plan?.count ?? 0), isPresented: Binding(get: { plan != nil }, set: { if !$0 { plan = nil } }),
                   presenting: plan) { p in
                Button(BtrxBearingText.ignoreAllConfirm, role: .destructive) { center.ignoreAllExcept(p); plan = nil }
                Button(BtrxBearingText.cancel, role: .cancel) { plan = nil }
            } message: { p in
                Text(BtrxBearingText.ignoreAllDetail(p, podSupported: center.snapshot.savedIgnoreSupported))
            }
    }
}

/// "Restore ignored (n)": undoes only what "Ignore all except this" ignored, here and on the
/// pod. Hidden when there is nothing to undo. Same as Android.
struct BtrxRestoreIgnoredButton: View {
    @ObservedObject var center: BtrxDeskPodCenter
    var body: some View {
        let onPod = center.snapshot.ignoreAll.map { $0.saved.count + $0.waiting } ?? 0
        let n = max(center.ignoredByAll.count, onPod)
        if n > 0 {
            Button(BtrxBearingText.restoreLabel(n)) { center.restoreIgnoreAll() }
                .foregroundStyle(Color(red: 0.54, green: 0.71, blue: 0.97))
        }
    }
}

extension Color {
    /// 0xRRGGBB.
    init(rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

/// Desk Pod button confirmation (firmware 0x0108) over the map: a small Confirm/Cancel card at
/// the top, never a sheet or alert, so the map, the stream tiles and the alerts stay usable.
/// Mounted as an overlay at the app root (outside the BTRX pane). While it is on screen and the
/// scene is active it tells the center the panel can be seen: only then does the app send the
/// keepalive and answer "shown", so with the app in the background the Desk Pod button acts on
/// its own. The same card shows inside the Desk Pod pane (a sheet above this). Same as Android.
struct BtrxButtonConfirmHost: View {
    @ObservedObject var center: BtrxDeskPodCenter
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let s = center.snapshot
        VStack(spacing: 0) {
            // Always present (zero size, no hit testing) so the availability below follows this host.
            Color.clear.frame(width: 0, height: 0).allowsHitTesting(false)
            if let v = s.buttonConfirm {
                BtrxButtonConfirmCard(center: center, view: v)
                    .frame(maxWidth: 460)
                    .padding(.horizontal, 12)
            } else if let n = s.buttonNotice {
                Text(n.text)
                    .font(.footnote)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 10))
                    .frame(maxWidth: 460)
                    .padding(.horizontal, 12)
                    .allowsHitTesting(false)
            }
        }
        .padding(.top, 8)
        .onAppear { center.setButtonPanelAvailable(scenePhase == .active) }
        .onChange(of: scenePhase) { _, phase in center.setButtonPanelAvailable(phase == .active) }
        .onDisappear { center.setButtonPanelAvailable(false) }
    }
}

/// The Confirm/Cancel card (map overlay and Desk Pod pane). Factory reset is red and says it cannot be undone.
struct BtrxButtonConfirmCard: View {
    @ObservedObject var center: BtrxDeskPodCenter
    let view: BtrxButtonConfirm.View

    var body: some View {
        let danger = view.factoryReset
        VStack(alignment: .leading, spacing: 6) {
            Text(BtrxDeskPodText.confirmTitle(view)).font(.headline).foregroundStyle(danger ? Color.red : Color.primary)
            Text(BtrxDeskPodText.confirmBody(view)).font(.footnote)
            Text(BtrxDeskPodText.confirmCountdown(view)).font(.footnote).fontWeight(.semibold)
            if !view.confirmSent {
                HStack(spacing: 10) {
                    Button(BtrxDeskPodText.confirmCancel) { center.answerButtonConfirm(false) }.buttonStyle(.bordered)
                    if danger {
                        Button(BtrxDeskPodText.confirmResetOk, role: .destructive) { center.answerButtonConfirm(true) }
                            .buttonStyle(.borderedProminent).tint(.red)
                    } else {
                        Button(BtrxDeskPodText.confirmOk) { center.answerButtonConfirm(true) }.buttonStyle(.borderedProminent)
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(danger ? AnyShapeStyle(Color.red.opacity(0.12)) : AnyShapeStyle(.regularMaterial), in: RoundedRectangle(cornerRadius: 12))
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(danger ? Color.red : Color.clear, lineWidth: 2))
        .shadow(radius: 6)
    }
}
