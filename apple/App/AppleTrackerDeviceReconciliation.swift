import Foundation
import R2CCore
import SwiftUI

@MainActor
final class AppleTrackerDeviceReconciliation: ObservableObject {
    @Published var presented = false
    @Published var namingNewDevice = false
    @Published var newDeviceName = ""
    @Published private(set) var reauthenticationURL: URL?
    @Published private(set) var authorizationRejected = false
    @Published private(set) var candidates: [TrackerDeviceReplacementCandidate] = []
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    private let defaults = UserDefaults.standard
    private let pendingKey = "tracker.deviceReconciliationPending"
    private var deferredForInvocation = false
    private var lastAttempt = Date.distantPast
    private var scope = ""
    private var credential = ""
    private var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        return URLSession(configuration: configuration, delegate: TrackerIdentityNoRedirect(), delegateQueue: nil)
    }()

    func markPending() {
        defaults.set(true, forKey: pendingKey)
        lastAttempt = .distantPast
        deferredForInvocation = false
        reauthenticationURL = nil
        authorizationRejected = false
    }

    func check(baseURL: String, token: String) async {
        guard !token.isEmpty else { return }
        // Offer reconciliation once for Apple installs that predate this UI.
        if !defaults.bool(forKey: "tracker.deviceReconciliationAvailable") {
            defaults.set(true, forKey: "tracker.deviceReconciliationAvailable")
            markPending()
        }
        guard defaults.bool(forKey: pendingKey), !busy, !presented, !deferredForInvocation,
              Date().timeIntervalSince(lastAttempt) >= 30 else { return }
        AppleLog.info("TrackerPeer", "Checking for earlier tablet authorizations")
        lastAttempt = Date()
        scope = baseURL; credential = token
        busy = true
        defer { busy = false }
        do {
            let data = try await perform(TrackerDeviceReconciliation.request(baseURL: baseURL, token: token))
            guard isCurrent else { return }
            candidates = try TrackerDeviceReconciliation.candidates(from: data)
            error = nil
            AppleLog.info("TrackerPeer", "Device identity check returned \(candidates.count) eligible earlier tablets")
            namingNewDevice = candidates.isEmpty
            newDeviceName = suggestedName
            presented = true
        } catch let failure as TrackerDeviceAuthorizationError {
            guard isCurrent else { return }
            switch failure {
            case .reauthenticationRequired(let url):
                reauthenticationURL = url
                AppleLog.warning("TrackerPeer", "Device identity check requires Tracker sign-in")
            case .authorizationRejected:
                authorizationRejected = true
                AppleLog.warning("TrackerPeer", "Device identity check rejected the saved Tracker authorization")
            case .httpStatus(let status):
                AppleLog.warning("TrackerPeer", "Device identity check deferred: HTTP \(status)")
            }
        } catch {
            // Leave pending through offline/unauthorized responses, including an
            // early foreground notification before browser sign-in completes.
            AppleLog.warning("TrackerPeer", "Device identity check deferred: \(error.localizedDescription)")
        }
    }

    private var isCurrent: Bool {
        credential == AppleOrgConfigSettings.loadTrackerAPIKey() &&
            scope == UserDefaults.standard.string(forKey: "org.trackerURLPrefix")
    }

    func deferUntilNextLaunch() {
        deferredForInvocation = true
        presented = false
    }

    private var suggestedName: String {
        if ProcessInfo.processInfo.isiOSAppOnMac {
            return OperationalDeviceName.displayName(fromHostname: ProcessInfo.processInfo.hostName) ?? "Mac"
        }
        return AppleDeviceIdentity.displayName
    }

    func beginNaming(baseURL: String, token: String) {
        guard !token.isEmpty, !busy else { return }
        scope = baseURL; credential = token
        newDeviceName = AppleDeviceIdentity.displayName
        namingNewDevice = true
        error = nil
        presented = true
    }

    func keepNew() {
        guard isCurrent else { presented = false; return }
        newDeviceName = suggestedName
        namingNewDevice = true
        error = nil
    }

    func saveNewDeviceName() async -> Bool {
        guard isCurrent, !busy else { return false }
        busy = true; error = nil
        defer { busy = false }
        do {
            let data = try await perform(TrackerDeviceReconciliation.request(
                baseURL: scope, token: credential, deviceName: newDeviceName))
            guard isCurrent else { return false }
            AppleDeviceIdentity.applyManagedDisplayName(try TrackerDeviceReconciliation.canonicalName(from: data))
            defaults.removeObject(forKey: pendingKey)
            namingNewDevice = false
            presented = false
            return true
        } catch {
            self.error = "Could not save the device name. \(error.localizedDescription) Try again, or choose Later."
            return false
        }
    }

    func replace(_ candidate: TrackerDeviceReplacementCandidate) async -> Bool {
        guard isCurrent, !busy, candidates.contains(candidate) else { presented = false; return false }
        busy = true; error = nil
        defer { busy = false }
        do {
            let data = try await perform(TrackerDeviceReconciliation.request(baseURL: scope, token: credential, replacementID: candidate.id))
            guard isCurrent else { presented = false; return false }
            let name = try TrackerDeviceReconciliation.canonicalName(from: data)
            AppleDeviceIdentity.applyManagedDisplayName(name)
            defaults.removeObject(forKey: pendingKey)
            presented = false
            AppleLog.info("TrackerPeer", "Earlier tablet authorization restored")
            return true
        } catch {
            self.error = "Could not restore that tablet. \(error.localizedDescription) Try again, or choose Later."
            return false
        }
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        try TrackerDeviceReconciliation.validateResponse(data, statusCode: http.statusCode)
        return data
    }
}

struct TrackerDeviceReconciliationModifier: ViewModifier {
    @ObservedObject var model: AppleTrackerDeviceReconciliation
    let signInRequired: (URL) -> Void
    let authorizationRejected: () -> Void
    let restored: () -> Void
    func body(content: Content) -> some View {
        content
            .onChange(of: model.reauthenticationURL, initial: true) { _, url in
                if let url { signInRequired(url) }
            }
            .onChange(of: model.authorizationRejected, initial: true) { _, rejected in
                if rejected { authorizationRejected() }
            }
            .sheet(isPresented: $model.presented) {
            NavigationStack {
                List {
                    if model.namingNewDevice {
                        Section("Name this device") {
                            TextField("Device name", text: $model.newDeviceName)
                                .autocorrectionDisabled()
                            Text("Use a name your team can recognize in Tracker and on the map.")
                            Button("Save device name") {
                                Task { if await model.saveNewDeviceName() { restored() } }
                            }.disabled(model.busy || model.newDeviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                        if model.busy { ProgressView("Saving device name…") }
                        if let error = model.error { Text(error).foregroundStyle(.red) }
                    } else {
                    Section {
                        Text("Tracker already knows these devices. If this is the same physical device, choose its earlier name to reuse that identity. Otherwise, keep this as a new device.")
                    }
                    Section("Reuse existing device") {
                        ForEach(model.candidates) { candidate in
                            Button {
                                Task { if await model.replace(candidate) { restored() } }
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(candidate.deviceName)
                                    Text(candidate.deviceModel).font(.caption).foregroundStyle(.secondary)
                                }
                            }.disabled(model.busy)
                        }
                    }
                    Section {
                        Button("This is a new device") { model.keepNew() }.disabled(model.busy)
                        if model.busy { ProgressView("Restoring tablet…") }
                        if let error = model.error { Text(error).foregroundStyle(.red) }
                    }
                    }
                }
                .navigationTitle(model.namingNewDevice ? "Name this device" : "Which device is this?")
                .toolbar { ToolbarItem(placement: .cancellationAction) {
                    Button("Later") { model.deferUntilNextLaunch() }.disabled(model.busy)
                } }
                .interactiveDismissDisabled()
            }
        }
    }
}

private final class TrackerIdentityNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
