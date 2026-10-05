import CoreLocation
import R2CCore
import SwiftUI
import UIKit

struct AppleSignalLossAlert: Identifiable, Equatable {
    var id: String { remoteID }
    let remoteID: String
    let mappedID: String
    let idleSeconds: Double
    let distanceFeet: Double
    let lastRSSIDbm: Int?
    let lastTransport: RidObservation.Source
    let bridgeRecentlySeen: Bool
}

struct AppleAltitudeComplianceAlert: Identifiable, Equatable {
    var id: String { remoteID }
    let remoteID: String
    let mappedID: String
    let aglFeet: Double
    let severity: OperationalAltitudeSeverity
}

@MainActor
final class AppleOperationalAlertCenter: ObservableObject {
    @Published private(set) var signalLossAlerts: [AppleSignalLossAlert] = []
    @Published private(set) var altitudeAlerts: [AppleAltitudeComplianceAlert] = []
    @Published private(set) var mutedSignalFlights: Set<String> = []
    @Published private(set) var mutedAltitudeFlights: Set<String> = []

    private var exceededBridge: Set<String> = []
    private var lastSpoken: [String: Date] = [:]
    private var altitudeNotifier = OperationalAltitudeAlertNotifier()
    private var lastAltitudeSkipLog: [String: Date] = [:]

    func update(
        tracks: [RidAircraftTrack],
        altitudeDisplay: [String: OperationalAircraftAltitudeDisplay],
        operatorLocation: CLLocation?,
        identityProvider: (String) -> RidAircraftIdentity?,
        alertEligibility: (String) -> Bool,
        bridgeCheckDistanceFeet: Double = 20,
        maximumTrackDelaySeconds: Double = 30,
        bridgeLastSeenAt: Date? = nil,
        pairedSEILastActivityAt: [String: Date] = [:],
        /// Own-ship SEI relative-up (metres) for streamed aircraft only. Used when RID AGL is stale.
        pairedSEIRelativeUpMeters: [String: Double] = [:],
        maximumSampleAgeSeconds: Double = OperationalAltitudeAlertNotifier.maximumSampleAge,
        now: Date = Date()
    ) {
        let activeIDs = Set(tracks.map(\.aircraftID))
        exceededBridge.formIntersection(activeIDs)
        let activeSignalMutes = mutedSignalFlights.intersection(activeIDs)
        let activeAltitudeMutes = mutedAltitudeFlights.intersection(activeIDs)
        if mutedSignalFlights != activeSignalMutes { mutedSignalFlights = activeSignalMutes }
        if mutedAltitudeFlights != activeAltitudeMutes { mutedAltitudeFlights = activeAltitudeMutes }

        var lost: [AppleSignalLossAlert] = []
        var altitude: [AppleAltitudeComplianceAlert] = []
        var altitudeCandidates: [OperationalAltitudeAlertCandidate] = []
        for track in tracks {
            let identity = identityProvider(track.aircraftID)
            let eligible = alertEligibility(track.aircraftID)
            if identity == nil || !eligible {
                // Field-test breadcrumb: AGL can be live on the map while spoken altitude
                // is gated. Log once-class cases when AGL is already over the limit.
                if let agl = altitudeDisplay[track.aircraftID]?.aglFeet,
                   agl.isFinite,
                   agl >= OperationalAltitudeAlertNotifier.limitFeet {
                    let key = track.aircraftID
                    let last = lastAltitudeSkipLog[key] ?? .distantPast
                    if now.timeIntervalSince(last) >= 15 {
                        lastAltitudeSkipLog[key] = now
                        AppleLog.info(
                            "AltitudeAlert",
                            "Skipped over-limit AGL remoteId=\(track.aircraftID) aglFeet=\(Int(agl.rounded())) "
                                + "hasIdentity=\(identity != nil) eligible=\(eligible)"
                        )
                    }
                }
                continue
            }
            guard let identity else { continue }
            if let operatorLocation,
               let currentDistance = Self.distanceFeet(
                   from: operatorLocation.coordinate,
                   to: track.lastObservation.coordinate
               ) {
                let takeoffDistance = track.points.first.flatMap { point in
                    Self.distanceFeet(
                        from: CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude),
                        to: track.lastObservation.coordinate
                    )
                }
                let intervals = zip(track.points.dropFirst(), track.points).map {
                    $0.0.receivedAt.timeIntervalSince($0.1.receivedAt)
                }.filter { $0 > 0 }
                let decision = OperationalSignalLossPolicy.evaluate(.init(
                    signalIdleSeconds: max(0, now.timeIntervalSince(track.lastSignalAt)),
                    trackTelemetryIdleSeconds: max(0, now.timeIntervalSince(track.lastAircraftMessageAt)),
                    pairedSEIIdleSeconds: pairedSEILastActivityAt[track.aircraftID].map {
                        max(0, now.timeIntervalSince($0))
                    },
                    learnedIntervalSeconds: intervals.isEmpty ? nil : intervals.reduce(0, +) / Double(intervals.count),
                    learnedSamples: intervals.count,
                    distanceFromDeviceFeet: currentDistance,
                    distanceFromTakeoffFeet: takeoffDistance,
                    bridgeCheckDistanceFeet: bridgeCheckDistanceFeet,
                    maximumTrackDelaySeconds: maximumTrackDelaySeconds,
                    hasPreviouslyExceededBridgeDistance: exceededBridge.contains(track.aircraftID)
                ))
                if decision.hasExceededBridgeDistance { exceededBridge.insert(track.aircraftID) }
                if decision.alert, !mutedSignalFlights.contains(track.aircraftID) {
                    lost.append(.init(
                        remoteID: track.aircraftID,
                        mappedID: identity.mappedID,
                        idleSeconds: max(0, now.timeIntervalSince(track.lastSignalAt)),
                        distanceFeet: currentDistance,
                        lastRSSIDbm: track.lastSignalStrengthDbm,
                        lastTransport: track.lastSignalSource,
                        bridgeRecentlySeen: bridgeLastSeenAt.map {
                            now.timeIntervalSince($0) <= Double(DroneScoutRelayPing.signalFreshnessSeconds)
                        } ?? false
                    ))
                }
            }

            // Mirrors Android StreamsViewModel: only fresh samples at or above the
            // 200 ft AGL limit become altitude-alert candidates. At 180 ft only the
            // map marker colour changes, on both platforms.
            // Own-ship SEI: when RID/BLE AGL is stale but paired DJI SEI relative-up
            // is fresh, prefer SEI for this altitude advisory only (never proximity).
            guard !mutedAltitudeFlights.contains(track.aircraftID),
                  let sample = OperationalOwnShipAltitudePreference.resolve(
                    ridAglFeet: altitudeDisplay[track.aircraftID]?.aglFeet,
                    ridTelemetryAt: track.lastAircraftMessageAt,
                    seiRelativeUpMeters: pairedSEIRelativeUpMeters[track.aircraftID],
                    seiTelemetryAt: pairedSEILastActivityAt[track.aircraftID],
                    now: now,
                    maximumAgeSeconds: maximumSampleAgeSeconds
                  ),
                  sample.aglFeet >= OperationalAltitudeAlertNotifier.limitFeet
            else { continue }
            let agl = sample.aglFeet
            altitude.append(.init(
                remoteID: track.aircraftID,
                mappedID: identity.mappedID,
                aglFeet: agl,
                severity: OperationalAltitudeAlertPolicy.severity(aglFeet: agl)
            ))
            altitudeCandidates.append(.init(
                remoteID: track.aircraftID,
                aglFeet: agl,
                telemetryAt: sample.telemetryAt,
                maximumAgeSeconds: maximumSampleAgeSeconds
            ))
        }
        lost.sort { $0.idleSeconds > $1.idleSeconds }
        altitude.sort { lhs, rhs in
            lhs.severity != rhs.severity ? lhs.severity > rhs.severity : lhs.aglFeet > rhs.aglFeet
        }
        announceNewSignalAlerts(lost, now: now)
        announceAltitudeAlert(altitudeNotifier.update(candidates: altitudeCandidates, now: now), alerts: altitude, now: now)
        if signalLossAlerts != lost { signalLossAlerts = lost }
        if altitudeAlerts != altitude { altitudeAlerts = altitude }
    }

    func muteSignal(_ remoteID: String) {
        mutedSignalFlights.insert(remoteID)
        signalLossAlerts.removeAll { $0.remoteID == remoteID }
        AppleLog.info("SignalLossAlert", "Muted flight remoteId=\(remoteID)")
    }

    func muteAltitude(_ remoteID: String) {
        mutedAltitudeFlights.insert(remoteID)
        altitudeAlerts.removeAll { $0.remoteID == remoteID }
        AppleLog.info("AltitudeAlert", "Muted flight remoteId=\(remoteID)")
    }

    func clearAltitudeMutes() {
        guard !mutedAltitudeFlights.isEmpty else { return }
        mutedAltitudeFlights.removeAll()
        AppleLog.info("AltitudeAlert", "Cleared all altitude mutes")
    }

    func clearSignalMutes() {
        guard !mutedSignalFlights.isEmpty else { return }
        mutedSignalFlights.removeAll()
        AppleLog.info("SignalLossAlert", "Cleared all signal-loss mutes")
    }

    private func announceNewSignalAlerts(_ alerts: [AppleSignalLossAlert], now: Date) {
        guard AppleAlertBellCenter.shared.allowSpeech(for: .droneSignalLoss) else { return }
        for alert in alerts where shouldSpeak(key: "signal:\(alert.remoteID)", now: now) {
            speak(
                alert.bridgeRecentlySeen
                    ? "Drone location stale, \(alert.mappedID)"
                    : "Drone signal lost, \(alert.mappedID)"
            )
            AppleLog.warning(
                "SignalLossAlert",
                "Alert remoteId=\(alert.remoteID) mappedId=\(alert.mappedID) " +
                    "idleSeconds=\(Int(alert.idleSeconds)) distanceFeet=\(Int(alert.distanceFeet)) " +
                    "lastRssiDbm=\(alert.lastRSSIDbm.map(String.init) ?? "unavailable") " +
                    "lastTransport=\(alert.lastTransport.rawValue) " +
                    "bridgeRecentlySeen=\(alert.bridgeRecentlySeen)"
            )
        }
    }

    /// Android ComplianceAlertHost: every new alert instance (severity change, or
    /// 15 s while over the limit) speaks "Altitude" with a 15 s per-aircraft
    /// cooldown and vibrates.
    private func announceAltitudeAlert(
        _ decision: OperationalAltitudeAlertNotifier.Decision?,
        alerts: [AppleAltitudeComplianceAlert],
        now: Date
    ) {
        guard let decision, decision.shouldNotify else { return }
        let mappedID = alerts.first { $0.remoteID == decision.remoteID }?.mappedID ?? decision.remoteID
        AppleLog.warning(
            "AltitudeAlert",
            "\(decision.severity == .overLimit ? "Over" : "Near") limit remoteId=\(decision.remoteID) " +
                "mappedId=\(mappedID) aglFeet=\(Int(decision.aglFeet.rounded()))"
        )
        guard AppleAlertBellCenter.shared.allowSpeech(for: .altitude) else { return }
        guard shouldSpeak(
            key: "altitude:\(mappedID)",
            interval: OperationalAltitudeAlertNotifier.spokenCooldown,
            now: now
        ) else { return }
        speak(OperationalAltitudeAlertNotifier.spokenPhrase)
    }

    private func shouldSpeak(key: String, interval: TimeInterval = 30, now: Date) -> Bool {
        guard now.timeIntervalSince(lastSpoken[key] ?? .distantPast) >= interval else { return false }
        lastSpoken[key] = now
        return true
    }

    private func speak(_ text: String) {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        AppleSpokenWarningCenter.shared.speak(text)
    }

    private static func distanceFeet(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double? {
        RidGeometry.relativePosition(
            fromLatitude: from.latitude,
            longitude: from.longitude,
            toLatitude: to.latitude,
            longitude: to.longitude
        ).map { $0.distanceMeters * 3.28084 }
    }
}

@MainActor
final class AppleDroneScoutBridgeAlertCenter: ObservableObject {
    @Published private(set) var audioMuted = false

    private var announcementGate = DroneScoutBridgeLossAnnouncementGate()

    func update(monitoringActive: Bool, lastPingAt: Date?, now: Date = Date()) {
        guard announcementGate.shouldAnnounce(
            monitoringActive: monitoringActive,
            lastPingAt: lastPingAt,
            now: now,
            muted: audioMuted || AppleAlertBellCenter.shared.isMuted(.bridgeSignalLoss)
        ) else { return }
        guard AppleAlertBellCenter.shared.allowSpeech(for: .bridgeSignalLoss) else { return }

        AppleSpokenWarningCenter.shared.speak("Bridge Not Detected")
        AppleLog.warning(
            "DroneScoutBridge",
            "Relay ping not detected for more than 32 seconds"
        )
    }

    func toggleAudioMuted() {
        setAudioMuted(!audioMuted)
    }

    func setAudioMuted(_ muted: Bool) {
        audioMuted = muted
        AppleLog.info(
            "DroneScoutBridge",
            audioMuted ? "Bridge warning muted" : "Bridge warning unmuted"
        )
    }
}

struct OperationalAlertBanner: View {
    let signalLoss: AppleSignalLossAlert?
    let altitude: AppleAltitudeComplianceAlert?
    let onMap: () -> Void
    let onMuteSignal: (String) -> Void
    let onMuteAltitude: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let signalLoss {
                Label(
                    signalLoss.bridgeRecentlySeen ? "Drone Location Stale" : "Drone Signal Lost",
                    systemImage: "antenna.radiowaves.left.and.right.slash"
                )
                    .font(.headline).foregroundStyle(.red)
                Text(
                    "\(signalLoss.mappedID): " +
                        (signalLoss.bridgeRecentlySeen ? "location stale" : "signal lost") +
                        " for \(Int(signalLoss.idleSeconds.rounded())) seconds, " +
                        "\(Int(signalLoss.distanceFeet.rounded())) ft from this device."
                )
                controls { onMuteSignal(signalLoss.remoteID) }
            } else if let altitude {
                Label(
                    altitude.severity == .overLimit ? "Altitude Limit Exceeded" : "Approaching Altitude Limit",
                    systemImage: "arrow.up.to.line.compact"
                )
                .font(.headline)
                .foregroundStyle(altitude.severity == .overLimit ? .red : .orange)
                Text("\(altitude.mappedID): \(Int(altitude.aglFeet.rounded())) ft AGL")
                controls { onMuteAltitude(altitude.remoteID) }
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay { RoundedRectangle(cornerRadius: 16).stroke(.red, lineWidth: 2) }
        .shadow(radius: 8)
        .padding()
        .accessibilityIdentifier("operational-alert")
    }

    private func controls(onMute: @escaping () -> Void) -> some View {
        HStack {
            Button("Map", action: onMap).buttonStyle(.borderedProminent)
            Button("Mute Flight", action: onMute).buttonStyle(.bordered)
        }
    }
}

private extension RidObservation {
    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
}
