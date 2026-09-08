import R2CCore
import SwiftUI
import UIKit

@MainActor
final class AppleApplicationCleanupCenter {
    static let shared = AppleApplicationCleanupCenter()

    typealias Cleanup = @MainActor () async -> Void

    private var markerCleanup: Cleanup?
    private var fullCleanup: Cleanup?
    private var markerCleanupTask: Task<Void, Never>?
    private var fullCleanupTask: Task<Void, Never>?
    private var shutdownState = ApplicationShutdownState()
    private weak var primaryWindowScene: UIWindowScene?
    private var sceneDestructionVerificationTask: Task<Void, Never>?
    private var idleTimeoutTask: Task<Void, Never>?
    private var idleAppStartedAt = Date()
    private var idleLastRIDMessageAt: Date?
    private var idleLastProtectedActivityAt: Date?
    private var idleProtectedActivityActive = false
    private var maximumIdleMinutes = 0

    func register(markerCleanup: @escaping Cleanup, fullCleanup: @escaping Cleanup) {
        self.markerCleanup = markerCleanup
        self.fullCleanup = fullCleanup
        shutdownState.reset()
        sceneDestructionVerificationTask?.cancel()
        sceneDestructionVerificationTask = nil
    }

    var isShutdownRequested: Bool {
        shutdownState.isShutdownRequested
    }

    func registerPrimaryWindowScene(_ scene: UIWindowScene?) {
        guard let scene, scene.session.role == .windowApplication else { return }
        primaryWindowScene = scene
    }

    func configureIdleTimeout(
        appStartedAt: Date,
        lastRIDMessageAt: Date?,
        maximumIdleMinutes: Int
    ) {
        idleAppStartedAt = appStartedAt
        idleLastRIDMessageAt = maxDate(idleLastRIDMessageAt, lastRIDMessageAt)
        self.maximumIdleMinutes = maximumIdleMinutes
        scheduleIdleTimeoutCheck()
    }

    func noteRIDMessage(receivedAt: Date) {
        idleLastRIDMessageAt = maxDate(idleLastRIDMessageAt, receivedAt)
        if idleTimeoutTask == nil { scheduleIdleTimeoutCheck() }
    }

    func setIdleShutdownDeferral(active: Bool, at date: Date = Date()) {
        idleProtectedActivityActive = active
        idleLastProtectedActivityAt = maxDate(idleLastProtectedActivityAt, date)
        scheduleIdleTimeoutCheck()
    }

    func removeMarkerForBackgrounding() {
        guard markerCleanupTask == nil, let markerCleanup else { return }
        markerCleanupTask = runWithBackgroundTime(name: "Remove CalTopo device marker") {
            await markerCleanup()
            self.markerCleanupTask = nil
        }
    }

    func closePrimaryWindow(reason: String) {
        performFullCleanup(reason: reason, dismissWindow: false)
    }

    func quitPrimaryWindow(reason: String) {
        performFullCleanup(reason: reason, dismissWindow: true)
    }

    func declineLaunchDisclaimer() {
        AppleLog.info("Lifecycle", "Launch disclaimer declined; closing primary window")
        dismissPrimaryWindow()
    }

    private func performFullCleanup(reason: String, dismissWindow: Bool) {
        guard let fullCleanup else {
            AppleLog.error("Lifecycle", "Could not close primary session: cleanup is not registered")
            return
        }
        let request = shutdownState.request(dismissWindow: dismissWindow)
        if request.shouldDismissWindow {
            AppleLog.info("Lifecycle", "Application cleanup already completed; retrying primary window dismissal")
            dismissPrimaryWindow()
            return
        }
        guard request.shouldStartCleanup, fullCleanupTask == nil else {
            if dismissWindow {
                AppleLog.info("Lifecycle", "Primary window dismissal queued until application cleanup completes")
            }
            return
        }
        idleTimeoutTask?.cancel()
        idleTimeoutTask = nil
        AppleLog.info("Lifecycle", "Closing primary application session reason=\(reason)")
        fullCleanupTask = runWithBackgroundTime(name: "Close RID2Caltopo session") {
            await fullCleanup()
            let shouldDismissWindow = self.shutdownState.cleanupCompleted()
            AppleLog.info("Lifecycle", "Primary application session cleanup completed")
            if shouldDismissWindow {
                self.dismissPrimaryWindow()
            }
            self.fullCleanupTask = nil
        }
    }

    private func scheduleIdleTimeoutCheck() {
        idleTimeoutTask?.cancel()
        idleTimeoutTask = nil
        guard let remaining = ApplicationIdleTimeoutPolicy.remainingDelay(
            appStartedAt: idleAppStartedAt,
            lastRIDMessageAt: idleLastRIDMessageAt,
            maximumIdleMinutes: maximumIdleMinutes,
            now: Date(),
            lastProtectedActivityAt: idleLastProtectedActivityAt,
            protectedActivityActive: idleProtectedActivityActive
        ) else { return }

        idleTimeoutTask = Task { @MainActor in
            if remaining > 0 {
                try? await Task.sleep(for: .seconds(remaining))
            }
            guard !Task.isCancelled else { return }
            self.idleTimeoutTask = nil
            self.checkIdleTimeout()
        }
    }

    private func checkIdleTimeout() {
        let now = Date()
        guard ApplicationIdleTimeoutPolicy.isExpired(
            appStartedAt: idleAppStartedAt,
            lastRIDMessageAt: idleLastRIDMessageAt,
            maximumIdleMinutes: maximumIdleMinutes,
            now: now,
            lastProtectedActivityAt: idleLastProtectedActivityAt,
            protectedActivityActive: idleProtectedActivityActive
        ) else {
            scheduleIdleTimeoutCheck()
            return
        }

        let baseline = max(
            idleAppStartedAt,
            max(
                idleLastRIDMessageAt ?? idleAppStartedAt,
                idleLastProtectedActivityAt ?? idleAppStartedAt
            )
        )
        let idleMinutes = now.timeIntervalSince(baseline) / 60
        AppleLog.warning(
            "Lifecycle",
            String(
                format: "Maximum idle timeout expired after %.3f/%.3f minutes without RID messages or protected activity; closing the application session",
                idleMinutes,
                Double(maximumIdleMinutes)
            )
        )
        quitPrimaryWindow(reason: "maximum idle timeout")
    }

    private func maxDate(_ lhs: Date?, _ rhs: Date?) -> Date? {
        switch (lhs, rhs) {
        case let (lhs?, rhs?): max(lhs, rhs)
        case let (lhs?, nil): lhs
        case let (nil, rhs?): rhs
        case (nil, nil): nil
        }
    }

    private func dismissPrimaryWindow() {
        let applicationScenes: [UIWindowScene] = UIApplication.shared.connectedScenes.compactMap { scene in
            guard let windowScene = scene as? UIWindowScene,
                  windowScene.session.role == .windowApplication
            else { return nil }
            return windowScene
        }
        let scene = primaryWindowScene.flatMap { registeredScene in
            applicationScenes.first(where: { $0 === registeredScene })
        } ?? applicationScenes.sorted { sceneRank($0) < sceneRank($1) }.first
        guard let scene else {
            AppleLog.error("Lifecycle", "Could not close primary window: no application scene")
            return
        }
        if scene !== primaryWindowScene {
            AppleLog.warning(
                "Lifecycle",
                "Registered primary window was unavailable; using the most active application scene"
            )
        }
        requestSceneDestruction(scene, attempt: 1)
    }

    private func requestSceneDestruction(_ scene: UIWindowScene, attempt: Int) {
        let sessionID = scene.session.persistentIdentifier
        AppleLog.info(
            "Lifecycle",
            "Requesting primary window destruction session=\(sessionID) attempt=\(attempt) state=\(scene.activationState.rawValue)"
        )
        UIApplication.shared.requestSceneSessionDestruction(
            scene.session,
            options: nil
        ) { error in
            AppleLog.error(
                "Lifecycle",
                "Could not close primary window session=\(sessionID): \(error.localizedDescription)"
            )
        }
        sceneDestructionVerificationTask?.cancel()
        sceneDestructionVerificationTask = Task { @MainActor [weak self, weak scene] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, let self, let scene else { return }
            let remainsConnected = UIApplication.shared.connectedScenes.contains(where: {
                $0.session.persistentIdentifier == sessionID
            })
            guard remainsConnected else {
                AppleLog.info("Lifecycle", "Primary window disconnected session=\(sessionID)")
                self.sceneDestructionVerificationTask = nil
                return
            }
            if attempt < 2 {
                AppleLog.warning(
                    "Lifecycle",
                    "Primary window remained connected after destruction request; retrying session=\(sessionID)"
                )
                self.requestSceneDestruction(scene, attempt: attempt + 1)
            } else {
                AppleLog.error(
                    "Lifecycle",
                    "Primary window remained connected after two destruction requests session=\(sessionID)"
                )
                self.sceneDestructionVerificationTask = nil
            }
        }
    }

    private func sceneRank(_ scene: UIWindowScene) -> Int {
        switch scene.activationState {
        case .foregroundActive: 0
        case .foregroundInactive: 1
        case .background: 2
        case .unattached: 3
        @unknown default: 4
        }
    }

    private func runWithBackgroundTime(
        name: String,
        operation: @escaping @MainActor () async -> Void
    ) -> Task<Void, Never> {
        var taskID = UIBackgroundTaskIdentifier.invalid
        taskID = UIApplication.shared.beginBackgroundTask(withName: name) {
            if taskID != .invalid {
                UIApplication.shared.endBackgroundTask(taskID)
                taskID = .invalid
            }
        }
        return Task { @MainActor in
            await operation()
            if taskID != .invalid {
                UIApplication.shared.endBackgroundTask(taskID)
                taskID = .invalid
            }
        }
    }
}

final class PrimaryWindowSceneObserverView: UIView {
    var sceneChanged: ((UIWindowScene?) -> Void)?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        sceneChanged?(window?.windowScene)
    }
}

struct PrimaryWindowSceneReader: UIViewRepresentable {
    let sceneChanged: @MainActor (UIWindowScene?) -> Void

    func makeUIView(context: Context) -> PrimaryWindowSceneObserverView {
        let view = PrimaryWindowSceneObserverView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.sceneChanged = { scene in
            Task { @MainActor in sceneChanged(scene) }
        }
        return view
    }

    func updateUIView(_ uiView: PrimaryWindowSceneObserverView, context: Context) {
        uiView.sceneChanged = { scene in
            Task { @MainActor in sceneChanged(scene) }
        }
        if let scene = uiView.window?.windowScene {
            sceneChanged(scene)
        }
    }
}

private struct LaunchDisclaimerView: View {
    let onAgree: () -> Void
    let onDisagree: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Text("Acknowledgment, Release, and Safety Terms")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                Text(ApplicationLaunchDisclaimer.text)
                    .font(.body)
                VStack(spacing: 12) {
                    Button(action: onAgree) {
                        Text("I agree")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    Button(role: .destructive, action: onDisagree) {
                        Text("I disagree")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                }
            }
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity, minHeight: 640)
            .padding(32)
        }
    }
}

private struct LaunchDisclaimerGate: View {
    @State private var accepted = false

    var body: some View {
        if accepted {
            ContentView()
        } else {
            LaunchDisclaimerView(
                onAgree: {
                    AppleLog.info("Lifecycle", "Launch disclaimer accepted")
                    accepted = true
                },
                onDisagree: AppleApplicationCleanupCenter.shared.declineLaunchDisclaimer
            )
        }
    }
}

@main
struct RID2CaltopoAppleApp: App {
    @UIApplicationDelegateAdaptor(RID2CaltopoAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            LaunchDisclaimerGate()
        }
    }
}
