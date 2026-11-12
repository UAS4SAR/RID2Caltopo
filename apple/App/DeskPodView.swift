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
                    }
                    if s.windowOpen && s.pairing.step == .idle {
                        Text("The Desk Pod pairing window is open (\(s.windowSecondsLeft) s left).").font(.footnote)
                    }
                    pairing(s.pairing)
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
                if p.step == .podFound { Button("Confirm pod") { center.confirmPod() }.buttonStyle(.borderedProminent) }
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
