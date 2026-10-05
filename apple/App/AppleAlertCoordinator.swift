import Combine
import Foundation
import R2CAppleRadios
import R2CCore
import UIKit

/// Process-wide alert evaluation that does not depend on SwiftUI view lifetime,
/// the iOS counterpart of Android's AlertSpeechCoordinator.
///
/// ContentView's operational subtree (`monitoredRoot`) is replaced by the
/// protected-access gate whenever the app is inactive or in background while
/// organization authentication is configured. That cancels its `.task` loops
/// and drops its `.onReceive`/`.onChange` handlers, so proximity, altitude, and
/// signal-loss evaluation (and MediaMTX health checks) stopped as soon as the
/// display locked. Field log 2026-10-04 18:27:49–18:28:25 PT: no Ingress or
/// restart lines while locked, proximity stale=2 from a cached snapshot, then an
/// immediate "Proximity" alert on unlock.
///
/// ContentView binds its hooks once from its always-installed body; this object
/// owns the subscriptions and the 1 s loop.
@MainActor
final class AppleAlertCoordinator {
    static let shared = AppleAlertCoordinator()

    struct Hooks {
        /// Every new RID track snapshot (proximity plus flight reconciliation).
        var tracks: @MainActor ([RidAircraftTrack]) -> Void
        /// Every second: proximity and operational alert evaluation.
        var evaluate: @MainActor () -> Void
        /// Every 15 s: MediaMTX ingest health and storage pruning.
        var maintain: @MainActor () -> Void
        /// Background heartbeat context (location, proximity, tracks).
        var status: @MainActor () -> String
        /// App entered (true) or left (false) background.
        var lifecycle: @MainActor (_ background: Bool) -> Void
    }

    private var hooks: Hooks?
    private var loop: Task<Void, Never>?
    private var cancellables: Set<AnyCancellable> = []
    private var schedule = AlertCoordinatorSchedule()
    private var inBackground = false
    private var backgroundEnteredAt: Date?
    private var heartbeatCount = 0
    private var ingressAtBackground: BluetoothRIDIngressDiagnostic?
    private weak var scanner: BluetoothRIDScanner?

    private init() {}

    /// Idempotent: a later call refreshes the hooks but keeps one loop and one
    /// set of subscriptions.
    func start(
        hooks: Hooks,
        tracks: AnyPublisher<[RidAircraftTrack], Never>,
        scanner: BluetoothRIDScanner
    ) {
        self.hooks = hooks
        guard loop == nil else { return }
        self.scanner = scanner
        inBackground = UIApplication.shared.applicationState == .background

        tracks
            .sink { [weak self] snapshot in
                MainActor.assumeIsolated { self?.hooks?.tracks(snapshot) }
            }
            .store(in: &cancellables)

        scanner.$ingressDiagnostic
            .compactMap { $0 }
            .sink { [weak self] diagnostic in
                MainActor.assumeIsolated { self?.logIngress(diagnostic) }
            }
            .store(in: &cancellables)

        scanner.$scanRestartCount
            .filter { $0 > 0 }
            .sink { [weak self] count in
                MainActor.assumeIsolated { self?.logScanRestart(count) }
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)
            .sink { [weak self] _ in
                MainActor.assumeIsolated { self?.setBackground(true) }
            }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)
            .sink { [weak self] _ in
                MainActor.assumeIsolated { self?.setBackground(false) }
            }
            .store(in: &cancellables)

        AppleLog.info(
            "AlertCoordinator",
            "Started; alert evaluation runs independently of the UI (evaluate every \(Int(AlertCoordinatorSchedule.evaluationInterval)) s, maintenance every \(Int(AlertCoordinatorSchedule.maintenanceInterval)) s)"
        )
        loop = Task { @MainActor [weak self] in
            while !Task.isCancelled,
                  !AppleApplicationCleanupCenter.shared.isShutdownRequested {
                self?.tick(now: Date())
                try? await Task.sleep(for: .seconds(AlertCoordinatorSchedule.evaluationInterval))
            }
            AppleLog.info("AlertCoordinator", "Stopped")
        }
    }

    private func tick(now: Date) {
        guard let hooks else { return }
        hooks.evaluate()
        if schedule.maintenanceDue(at: now) {
            hooks.maintain()
        }
        if schedule.heartbeatDue(at: now, inBackground: inBackground) {
            heartbeatCount += 1
            let lockedFor = backgroundEnteredAt.map { Int(now.timeIntervalSince($0)) } ?? 0
            AppleLog.info(
                "AlertCoordinator",
                "Background evaluation tick n=\(heartbeatCount) lockedFor=\(lockedFor)s \(bluetoothSinceBackground()) \(hooks.status())"
            )
        }
    }

    private func setBackground(_ background: Bool) {
        guard inBackground != background else { return }
        inBackground = background
        backgroundEnteredAt = background ? Date() : nil
        ingressAtBackground = background ? scanner?.ingressDiagnostic : nil
        heartbeatCount = 0
        hooks?.lifecycle(background)
        let status = hooks?.status() ?? ""
        AppleLog.info(
            "Lifecycle",
            background
                ? "Entered background; alert evaluation continues in the alert coordinator. \(status)"
                : "Returned to foreground. \(status)"
        )
    }

    /// Bluetooth RID yield since the display locked. iOS restricts background
    /// scans to a service filter and reports one discovery per transmitter per
    /// scan, so this shows how much Remote ID still arrives while locked.
    private func bluetoothSinceBackground() -> String {
        guard let current = scanner?.ingressDiagnostic else { return "ble=noPackets" }
        guard let start = ingressAtBackground else {
            return "ble(total) packets=\(current.receivedPackets) observations=\(current.emittedObservations)"
        }
        let packets = current.receivedPackets &- start.receivedPackets
        let observations = current.emittedObservations &- start.emittedObservations
        let locations = current.locationPackets &- start.locationPackets
        return "bleSinceLock packets=+\(packets) locations=+\(locations) observations=+\(observations)"
    }

    private func logScanRestart(_ count: UInt64) {
        AppleLog.info(
            "BluetoothRID",
            "\(inBackground ? "Background" : "High-priority") scan restart=\(count) callbacks=\(scanner?.ingressDiagnostic?.discoveryCallbacks ?? 0)"
        )
    }

    private func logIngress(_ diagnostic: BluetoothRIDIngressDiagnostic) {
        AppleLog.info(
            "BluetoothRID",
            "Ingress\(inBackground ? " (background)" : "") callbacks=\(diagnostic.discoveryCallbacks) " +
                "nonRID=\(diagnostic.nonRemoteIDCallbacks) packets=\(diagnostic.receivedPackets) " +
                "decoded=\(diagnostic.decodedPackets) locations=\(diagnostic.locationPackets) " +
                "observations=\(diagnostic.emittedObservations) relayPings=\(diagnostic.relayPings) " +
                "noFreshLocation=\(diagnostic.noFreshLocation) " +
                "missingIdentity=\(diagnostic.missingIdentity) " +
                "invalidLocation=\(diagnostic.invalidLocation) " +
                "decodeFailures=\(diagnostic.decodeFailures) streamDrops=\(diagnostic.streamDrops) " +
                "lastSequence=\(diagnostic.lastSequence) " +
                "lastTransmitter=\(diagnostic.lastTransmitterID.uuidString) " +
                "lastCounter=\(diagnostic.lastMessageCounter.map(String.init) ?? "unavailable") " +
                "lastKinds=\(diagnostic.lastMessageKinds) rssi=\(diagnostic.lastRSSIDbm) " +
                "serviceData=\(diagnostic.serviceDataSummary) " +
                "manufacturerData=\(diagnostic.manufacturerDataSummary)"
        )
    }
}
