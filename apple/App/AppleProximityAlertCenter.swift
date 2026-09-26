import CoreLocation
import R2CCore
import SwiftUI
import UIKit

struct AppleProximityPair: Identifiable, Equatable {
    var id: String { [firstRemoteID, secondRemoteID].sorted().joined(separator: "|") }
    let firstRemoteID: String
    let secondRemoteID: String
    let firstMappedID: String
    let secondMappedID: String
    let horizontalFeet: Double
    let verticalFeet: Double?
    let threeDimensionalFeet: Double?
    let alerting: Bool
}

@MainActor
final class AppleProximityAlertCenter: ObservableObject {
    @Published private(set) var activeAlert: RidProximityAlertState?
    @Published private(set) var suspendedAlert: RidProximityAlertState?
    @Published private(set) var canResume = false
    @Published private(set) var isSuspended = false
    @Published private(set) var stalePositionCount = 0
    @Published private(set) var pairs: [AppleProximityPair] = []

    @Published private(set) var alertAllAircraft: Bool
    @Published private(set) var consent: RidProximityConsent
    private let consentDefaults: UserDefaults
    private let consentDeviceID: String?
    private static let consentVersionKey = "proximity.localConsent.version"
    private static let consentDeviceKey = "proximity.localConsent.device"
    private static let consentDateKey = "proximity.localConsent.acceptedAt"

    init(defaults: UserDefaults = .standard, deviceID: String? = UIDevice.current.identifierForVendor?.uuidString) {
        alertAllAircraft = defaults.bool(forKey: "proximity.alertAllAircraft")
        consentDefaults = defaults
        consentDeviceID = deviceID
        consent = RidProximityConsent(acceptedVersion: defaults.integer(forKey: Self.consentVersionKey),
            acceptedDeviceID: defaults.string(forKey: Self.consentDeviceKey), currentDeviceID: deviceID)
        freshnessTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                if let input = self.latestInput, self.consent.enabled {
                    self.evaluate(drones: input.drones, thresholdFeet: input.threshold, predictiveEnabled: input.predictive, now: Date())
                }
            }
        }
    }

    deinit { freshnessTask?.cancel() }

    func setAlertAllAircraft(_ value: Bool) {
        guard alertAllAircraft != value else { return }
        consentDefaults.set(value, forKey: "proximity.alertAllAircraft")
        AppleSpokenWarningCenter.shared.cancelProximityWarning()
        activeAlert = nil
        suspendedAlert = nil
        canResume = false
        lastAnnouncementByPair.removeAll()
        alertAllAircraft = value
    }

    var status: String { !consent.enabled ? "Off" : isSuspended ? "Suspended" : "On" }
    func requestEnable() { consent.requestEnable() }
    func cancelEnable() { consent.cancel() }
    func confirmEnable() {
        consent.confirmEnable()
        guard consent.enabled else { return }
        // Device binding prevents a restored backup from enabling another device.
        if let consentDeviceID {
            consentDefaults.set(RidProximityConsent.noticeVersion, forKey: Self.consentVersionKey)
            consentDefaults.set(consentDeviceID, forKey: Self.consentDeviceKey)
            consentDefaults.set(Date(), forKey: Self.consentDateKey)
        }
    }
    func disable() {
        consent.disable()
        AppleSpokenWarningCenter.shared.cancelProximityWarning()
        consentDefaults.removeObject(forKey: Self.consentVersionKey)
        consentDefaults.removeObject(forKey: Self.consentDeviceKey)
        consentDefaults.removeObject(forKey: Self.consentDateKey)
        engine.reset()
        activeAlert = nil
        suspendedAlert = nil
        canResume = false
        isSuspended = false
        pairs = []
        stalePositionCount = 0
        lastAnnouncementByPair.removeAll()
    }

    private var freshnessTask: Task<Void, Never>?
    private var latestInput: (drones: [RidProximityDrone], threshold: Int, predictive: Bool)?
    private var lastDiagnosticAt = Date.distantPast
    private var engine = RidProximityAlertEngine()
    private var lastEvaluationSummary = ""
    private var lastAnnouncementByPair: [String: Date] = [:]

    func update(
        tracks: [RidAircraftTrack],
        thresholdFeet: Int,
        predictiveEnabled: Bool,
        operatorLocation: CLLocation?,
        identityProvider: (String) -> RidAircraftIdentity?,
        alertEligibility: (String) -> Bool,
        now: Date = Date()
    ) {
        let drones = tracks.map { track in
            let identity = identityProvider(track.aircraftID)
            let observation = track.lastObservation
            let distance = operatorLocation.flatMap { location in
                RidGeometry.relativePosition(
                    fromLatitude: location.coordinate.latitude,
                    longitude: location.coordinate.longitude,
                    toLatitude: observation.latitude,
                    longitude: observation.longitude
                )?.distanceMeters
            }
            return RidProximityDrone(
                remoteID: track.aircraftID,
                mappedID: identity?.mappedID ?? track.aircraftID,
                latitude: observation.latitude,
                longitude: observation.longitude,
                altitudeMeters: observation.altitudeMeters,
                sampleDate: observation.receivedAt,
                distanceToOperatorMeters: distance,
                teamDrone: identity != nil,
                localAlertEligible: alertEligibility(track.aircraftID),
                telemetry: observation.proximityTelemetry
            )
        }
        latestInput = (drones, thresholdFeet, predictiveEnabled)
        evaluate(drones: drones, thresholdFeet: thresholdFeet, predictiveEnabled: predictiveEnabled, now: now)
    }

    private func evaluate(drones: [RidProximityDrone], thresholdFeet: Int, predictiveEnabled: Bool, now: Date) {
        let fresh = drones.filter { (0...RidProximityTelemetry.maximumPositionAgeSeconds).contains(now.timeIntervalSince($0.sampleDate)) }
        stalePositionCount = consent.enabled && drones.count >= 2 ? drones.count - fresh.count : 0
        let summary = "stale=\(stalePositionCount) allAircraft=\(alertAllAircraft) enabled=\(consent.enabled) suspended=\(isSuspended) tracks=\(drones.count) team=\(drones.filter(\.teamDrone).count) eligible=\(drones.filter(\.localAlertEligible).map(\.remoteID).sorted().joined(separator: ","))"
        if summary != lastEvaluationSummary {
            lastEvaluationSummary = summary
            AppleLog.info("ProximityAlert", "Evaluation \(summary)")
        }
        let output = engine.update(
                drones: drones,
                thresholdFeet: Double(thresholdFeet),
                enabled: consent.enabled,
                alertAllAircraft: alertAllAircraft,
                predictiveEnabled: predictiveEnabled,
                now: now
            )
        let teamDrones = alertAllAircraft ? fresh : fresh.filter(\.teamDrone)
        pairs = teamDrones.indices.flatMap { firstIndex in
            teamDrones.indices.compactMap { secondIndex -> AppleProximityPair? in
                guard secondIndex > firstIndex else { return nil }
                let first = teamDrones[firstIndex], second = teamDrones[secondIndex]
                guard let relative = RidGeometry.relativePosition(fromLatitude: first.latitude,
                    longitude: first.longitude, toLatitude: second.latitude, longitude: second.longitude) else { return nil }
                let known = first.telemetry.hasUsableAltitude && second.telemetry.hasUsableAltitude
                    && first.telemetry.altitudeReference == second.telemetry.altitudeReference
                    && (0...5).contains(now.timeIntervalSince(first.sampleDate))
                    && (0...5).contains(now.timeIntervalSince(second.sampleDate))
                let vertical = known ? abs(first.telemetry.absoluteAltitudeMeters! - second.telemetry.absoluteAltitudeMeters!) / 0.3048 : nil
                let key = [first.remoteID, second.remoteID].sorted().joined(separator: "|")
                return AppleProximityPair(firstRemoteID: first.remoteID, secondRemoteID: second.remoteID,
                    firstMappedID: first.mappedID, secondMappedID: second.mappedID,
                    horizontalFeet: relative.distanceMeters / 0.3048, verticalFeet: vertical,
                    threeDimensionalFeet: vertical.map { hypot(relative.distanceMeters / 0.3048, $0) },
                    alerting: output.activeAlert?.pairKey == key)
            }
        }
        if let alert = output.activeAlert, now.timeIntervalSince(lastDiagnosticAt) >= 5 {
            lastDiagnosticAt = now
            let quality = drones.filter { $0.remoteID == alert.pairKey.components(separatedBy: "|").first || $0.remoteID == alert.pairKey.components(separatedBy: "|").last }
                .map { "\($0.remoteID):age=\(String(format: "%.1f", now.timeIntervalSince($0.sampleDate)))s,error=\(String(format: "%.1f", $0.telemetry.horizontalAccuracyMeters))m" }.joined(separator: ";")
            AppleLog.info("ProximityAlert", "Active horizontalFt=\(Int(alert.horizontalSeparationFeet)) quality=\(quality)")
        }
        if activeAlert != nil && output.activeAlert == nil {
            AppleLog.info("ProximityAlert", "Alert cleared stalePositions=\(stalePositionCount)")
        }
        apply(output, now: now)
    }

    func suspend() {
        apply(engine.suspend(), now: Date(), announce: false)
        AppleLog.info("ProximityAlert", "Current proximity alert suspended")
    }

    func resume() {
        guard consent.enabled else { return }
        apply(engine.resume(), now: Date(), announce: false)
        AppleLog.info("ProximityAlert", "Suspended proximity alert resumed")
    }

    private func apply(
        _ output: RidProximityAlertOutput,
        now: Date,
        announce: Bool = true
    ) {
        let previousID = activeAlert?.alertInstanceID
        activeAlert = output.activeAlert
        suspendedAlert = output.suspendedAlert
        canResume = output.canResume
        isSuspended = output.isSuspended
        guard consent.enabled, announce,
              let alert = output.activeAlert,
              alert.alertInstanceID != previousID
        else { return }

        let last = lastAnnouncementByPair[alert.pairKey] ?? .distantPast
        guard now.timeIntervalSince(last) >= 30 else { return }
        lastAnnouncementByPair[alert.pairKey] = now
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        AppleSpokenWarningCenter.shared.speak("Proximity warning")
        AppleLog.info(
            "ProximityAlert",
            "Alert pair=\(alert.pairKey) horizontalFt=\(Int(alert.horizontalSeparationFeet.rounded())) verticalFt=\(Int(alert.verticalSeparationFeet.rounded())) currentHorizontalFt=\(Int(alert.currentHorizontalSeparationFeet.rounded())) currentVerticalFt=\(Int(alert.currentVerticalSeparationFeet.rounded())) projected=\(alert.usesProjection) thresholdFt=\(Int(alert.thresholdFeet.rounded()))"
        )
    }
}

struct ProximityAlertBanner: View {
    let alert: RidProximityAlertState
    let onMap: () -> Void
    let onSuspend: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Proximity Alert", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(alert.highSeverity ? .red : .orange)
            Text("\(feet(alert.thresholdFeet)) base spacing plus position uncertainty.\(alert.verticalSeparationKnown ? "" : " Vertical separation unknown.")")
                .font(.subheadline)
            HStack {
                VStack(alignment: .leading) {
                    Text("Near: \(alert.nearestDroneMappedID)")
                    if alert.verticalSeparationKnown { Text("High: \(alert.highestDroneMappedID)") }
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text("\(alert.usesProjection ? "Projected H" : "H"): \(feet(alert.horizontalSeparationFeet))")
                    Text(alert.verticalSeparationKnown ? "V: \(feet(alert.verticalSeparationFeet))" : "V: Unknown")
                }
                .fontWeight(.semibold)
            }
            HStack {
                Button("Map", action: onMap)
                    .buttonStyle(.borderedProminent)
                Button("Suspend", action: onSuspend)
                    .buttonStyle(.bordered)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .stroke(alert.highSeverity ? .red : .orange, lineWidth: 2)
        }
        .shadow(radius: 8)
        .padding()
        .accessibilityIdentifier("proximity-alert")
    }

    private func feet(_ value: Double) -> String {
        "\(Int(value.rounded())) ft"
    }
}


/// Presentation only. Hiding the notice never acknowledges, suspends, or clears
/// the collision engine; the bell remains while an alert or degraded state exists.
struct AppleProximityWarningHost: View {
    @ObservedObject var center: AppleProximityAlertCenter
    let onMap: () -> Void
    @State private var noticeID: Int64?
    @State private var showDetails = false

    private var visible: Bool {
        center.consent.enabled && (center.activeAlert != nil || center.isSuspended || center.stalePositionCount > 0)
    }
    private var bellLabel: String {
        center.isSuspended ? "Proximity alerts suspended. Tap to resume or view details." :
        center.activeAlert != nil ? "Active proximity warning. Tap for details or Suspend." :
        "Proximity telemetry unavailable. Tap for details."
    }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if let alert = center.activeAlert, noticeID == alert.alertInstanceID {
                HStack(alignment: .top, spacing: 8) {
                    Button { showDetails = true } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Label("Proximity warning", systemImage: "exclamationmark.triangle.fill")
                                .font(.subheadline.bold()).foregroundStyle(.red)
                            Text("\(alert.nearestDroneMappedID) · \(Int(alert.horizontalSeparationFeet.rounded())) ft horizontal")
                                .font(.caption).lineLimit(2)
                            Text("Tap the bell for details or Suspend.").font(.caption2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .multilineTextAlignment(.leading)
                    }
                    .buttonStyle(.plain)
                    Button { noticeID = nil } label: {
                        Image(systemName: "xmark").frame(width: 32, height: 32)
                    }
                    .accessibilityLabel("Hide proximity notice; warning stays active")
                }
                .padding(10)
                .frame(maxWidth: 330)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(.red, lineWidth: 1))
                .accessibilityIdentifier("proximity-brief-notice")
            }
            if visible {
                Button { showDetails.toggle() } label: {
                    Image(systemName: center.isSuspended ? "bell.slash.fill" : "bell.badge.fill")
                        .font(.title3)
                        .foregroundStyle(center.activeAlert != nil ? Color.red : Color.orange)
                        .frame(width: 48, height: 48)
                        .background(.regularMaterial, in: Circle())
                        .overlay(Circle().stroke(center.activeAlert != nil ? Color.red : Color.orange, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(bellLabel)
                .accessibilityIdentifier("proximity-alarm-bell")
                .popover(isPresented: $showDetails, arrowEdge: .top) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                Text("Proximity alerts: \(center.status)").font(.headline)
                                Spacer()
                                Button("Done") { showDetails = false }
                            }.padding(.horizontal)
                            if let alert = center.activeAlert {
                                ProximityAlertBanner(alert: alert, onMap: {
                                    showDetails = false
                                    onMap()
                                }, onSuspend: {
                                    center.suspend()
                                    showDetails = false
                                })
                            } else if center.isSuspended {
                                Text("Proximity warnings are suspended.").padding(.horizontal)
                                Button("Resume proximity alerts") {
                                    center.resume()
                                    showDetails = false
                                }.buttonStyle(.borderedProminent).padding(.horizontal)
                            } else {
                                Text("No active proximity warning.").padding(.horizontal)
                            }
                            if center.stalePositionCount > 0 {
                                Text("Proximity unavailable for \(center.stalePositionCount) aircraft: position telemetry is over 5 seconds old or has an invalid time.")
                                    .font(.footnote).padding(.horizontal)
                            }
                        }.padding(.vertical)
                    }
                    .frame(idealWidth: 420, maxWidth: 460, idealHeight: 340, maxHeight: 440)
                    .presentationCompactAdaptation(.popover)
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .task(id: center.activeAlert?.alertInstanceID) {
            guard let id = center.activeAlert?.alertInstanceID else {
                noticeID = nil
                return
            }
            noticeID = id
            AppleLog.info("ProximityAlert", "Brief notice displayed; bell remains after timeout pair=\(center.activeAlert?.pairKey ?? "")")
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            guard !Task.isCancelled else { return }
            noticeID = nil
        }
        .onChange(of: visible) { _, isVisible in
            if !isVisible { showDetails = false; noticeID = nil }
        }
    }
}
