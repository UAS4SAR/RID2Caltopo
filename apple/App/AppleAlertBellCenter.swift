import Combine
import R2CCore
import SwiftUI
import UIKit

/// Session-scoped mute store and alert-bell colours. Mutes are in-memory only —
/// they do not survive process restart and are distinct from Settings enable/disable.
@MainActor
final class AppleAlertBellCenter: ObservableObject {
    static let shared = AppleAlertBellCenter()

    @Published private(set) var state = AlertBellSessionState()
    @Published var showPanel = false

    /// Cleared when Altitude is unmuted from the panel (restores speech).
    var onAltitudeTypeUnmuted: (() -> Void)?
    /// Cleared when Drone signal loss is unmuted from the panel.
    var onDroneSignalLossTypeUnmuted: (() -> Void)?
    /// Suspend / resume proximity through the existing collision engine.
    var onProximityMuteChanged: ((Bool) -> Void)?
    /// Bridge audio mute (session).
    var onBridgeMuteChanged: ((Bool) -> Void)?

    private init() {}

    var showBell: Bool { state.showBell }
    var aggregateColor: AlertBellColor { state.aggregateColor }

    func isMuted(_ kind: AlertBellKind) -> Bool { state.isMuted(kind) }

    func setMuted(_ kind: AlertBellKind, muted: Bool) {
        guard state.isMuted(kind) != muted else { return }
        var next = state
        next.setMuted(kind, muted: muted)
        state = next
        switch kind {
        case .proximity:
            onProximityMuteChanged?(muted)
        case .bridgeSignalLoss:
            onBridgeMuteChanged?(muted)
        case .altitude:
            if !muted { onAltitudeTypeUnmuted?() }
        case .droneSignalLoss:
            if !muted { onDroneSignalLossTypeUnmuted?() }
        case .distance, .wifiStrength, .videoRequest:
            break
        }
        AppleLog.info(
            "AlertBell",
            muted ? "Muted \(kind.displayName)" : "Unmuted \(kind.displayName)"
        )
    }

    func toggleMuted(_ kind: AlertBellKind) {
        setMuted(kind, muted: !isMuted(kind))
    }

    /// Updates mute flags without invoking mute side effects (Settings sync).
    func reflectExternalMute(_ kind: AlertBellKind, muted: Bool) {
        guard state.isMuted(kind) != muted else { return }
        var next = state
        next.setMuted(kind, muted: muted)
        state = next
    }

    func updateMetrics(_ metrics: AlertBellMetrics) {
        var next = state
        next.updateColors(metrics.colors())
        if next != state {
            state = next
        }
    }

    func noteAlarmFired() {
        guard !state.hasEverAlarmed else { return }
        var next = state
        next.hasEverAlarmed = true
        state = next
    }

    /// Returns false when the kind is session-muted (caller should skip speech).
    @discardableResult
    func allowSpeech(for kind: AlertBellKind) -> Bool {
        // Latch visibility whenever an alarm would speak, even if this kind is muted.
        noteAlarmFired()
        return !isMuted(kind)
    }

    func resetForTests() {
        state = AlertBellSessionState()
        showPanel = false
        onAltitudeTypeUnmuted = nil
        onDroneSignalLossTypeUnmuted = nil
        onProximityMuteChanged = nil
        onBridgeMuteChanged = nil
    }
}

extension AlertBellColor {
    var swiftUIColor: Color {
        switch self {
        case .white: return .white
        case .orange: return Color(red: 0.96, green: 0.49, blue: 0)
        case .red: return Color(red: 0.90, green: 0.22, blue: 0.21)
        }
    }
}

struct AppleAlertStatusBell: View {
    @ObservedObject var center: AppleAlertBellCenter

    var body: some View {
        if center.showBell {
            Button {
                center.showPanel = true
            } label: {
                ZStack {
                    Circle()
                        .fill(Color(white: 0.26))
                        .frame(width: 28, height: 28)
                    Image(systemName: "bell.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(center.aggregateColor.swiftUIColor)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Alert panel")
            .accessibilityValue(center.aggregateColor == .red ? "active" :
                center.aggregateColor == .orange ? "approaching" : "idle")
            .accessibilityIdentifier("alert-status-bell")
        }
    }
}

struct AppleAlertStatusPanel: View {
    @ObservedObject var center: AppleAlertBellCenter

    var body: some View {
        NavigationStack {
            List {
                Text("Tap a bell to mute or unmute speech for that alert. Dismissing this panel only hides it.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
                ForEach(AlertBellKind.allCases) { kind in
                    HStack {
                        Text(kind.displayName)
                        Spacer()
                        Button {
                            center.toggleMuted(kind)
                        } label: {
                            Image(systemName: center.isMuted(kind) ? "bell.slash.fill" : "bell.fill")
                                .foregroundStyle(
                                    center.isMuted(kind)
                                        ? Color.secondary
                                        : rowTint(center.state.colors[kind] ?? .white)
                                )
                                .frame(width: 36, height: 36)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(center.isMuted(kind) ? "Unmute \(kind.displayName)" : "Mute \(kind.displayName)")
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { center.toggleMuted(kind) }
                }
            }
            .navigationTitle("Alerts")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { center.showPanel = false }
                }
            }
        }
        // Compact so the iPad top bar stays uncrowded when presented as a sheet.
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func rowTint(_ color: AlertBellColor) -> Color {
        switch color {
        case .white: return Color.primary
        case .orange, .red: return color.swiftUIColor
        }
    }
}
