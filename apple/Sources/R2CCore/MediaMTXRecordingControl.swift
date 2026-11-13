import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// What MediaMTX's path defaults should say about recording. Matches Android
/// `MediaMTXRecordingState`.
public struct MediaMTXRecordingState: Equatable, Sendable {
    public let record: Bool
    public let recordingRoot: URL?
    public init(record: Bool, recordingRoot: URL?) {
        self.record = record
        self.recordingRoot = recordingRoot
    }
    public var recordPath: String? { recordingRoot.map(MediaMTXRuntimeConfiguration.recordPath(root:)) }
}

/// PATCH /v3/config/pathdefaults/patch on the local API: hot reload, no restart.
public enum MediaMTXRecordingAPI {
    public static let defaultBaseURL = URL(string: "http://127.0.0.1:9997")!

    public static func patchBody(_ state: MediaMTXRecordingState) -> Data {
        var object: [String: Any] = ["record": state.record]
        if let path = state.recordPath {
            object["recordPath"] = path
            object["recordFormat"] = "fmp4"
            object["recordDeleteAfter"] = "0s"
        }
        return (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data()
    }

    public static func request(_ state: MediaMTXRecordingState, baseURL: URL = defaultBaseURL) -> URLRequest {
        var request = URLRequest(url: baseURL.appendingPathComponent("v3/config/pathdefaults/patch"))
        request.httpMethod = "PATCH"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = patchBody(state)
        request.timeoutInterval = 2
        return request
    }

    /// True once MediaMTX accepted the change.
    public static func apply(_ state: MediaMTXRecordingState, baseURL: URL = defaultBaseURL) async -> Bool {
        do {
            let (_, response) = try await URLSession.shared.data(for: request(state, baseURL: baseURL))
            return ((response as? HTTPURLResponse)?.statusCode ?? 0) / 100 == 2
        } catch {
            return false
        }
    }
}

/// Keeps MediaMTX recording in line with capture and storage without restarting the
/// server, so the RTMP listener and live publishers are never interrupted. Same rules as
/// Android `MediaMTXRecordingReconciler`: if turning recording off is not accepted after
/// the retries, `onDisableFailed` runs (the caller falls back to a restart).
public actor MediaMTXRecordingReconciler {
    public typealias Apply = @Sendable (MediaMTXRecordingState) async -> Bool
    private let apply: Apply
    private let sleep: @Sendable (UInt64) async -> Void
    private let retryDelaysMs: [UInt64]
    private let onApplied: @Sendable (MediaMTXRecordingState) async -> Void
    private let onDisableFailed: @Sendable () async -> Void

    private var generation = 0
    private var applied: MediaMTXRecordingState?
    private var captureEnabled = false
    private var storageBlocked = true
    private var recordingRoot: URL?
    private var draining = false

    public init(
        apply: @escaping Apply = { await MediaMTXRecordingAPI.apply($0) },
        sleep: @escaping @Sendable (UInt64) async -> Void = { try? await Task.sleep(nanoseconds: $0 * 1_000_000) },
        retryDelaysMs: [UInt64] = [50, 100, 200, 400, 800, 1_600, 3_200],
        onApplied: @escaping @Sendable (MediaMTXRecordingState) async -> Void = { _ in },
        onDisableFailed: @escaping @Sendable () async -> Void = {}
    ) {
        self.apply = apply
        self.sleep = sleep
        self.retryDelaysMs = retryDelaysMs
        self.onApplied = onApplied
        self.onDisableFailed = onDisableFailed
    }

    public func serverStarted(configured: MediaMTXRecordingState, captureEnabled: Bool, storageBlocked: Bool, recordingRoot: URL?) async {
        generation += 1
        applied = configured
        self.captureEnabled = captureEnabled
        self.storageBlocked = storageBlocked
        self.recordingRoot = recordingRoot
        await reconcile()
    }

    public func serverStopped() { generation += 1; applied = nil }

    public func updateStorageBlocked(_ blocked: Bool) async { storageBlocked = blocked; await reconcile() }

    public func updateCaptureEnabled(_ enabled: Bool) async { captureEnabled = enabled; await reconcile() }

    public var desired: MediaMTXRecordingState {
        MediaMTXRecordingState(record: captureEnabled && !storageBlocked && recordingRoot != nil,
                               recordingRoot: recordingRoot ?? applied?.recordingRoot)
    }

    public var appliedState: MediaMTXRecordingState? { applied }

    private func matches(_ current: MediaMTXRecordingState, _ target: MediaMTXRecordingState) -> Bool {
        current.record == target.record && (!target.record || current.recordingRoot == target.recordingRoot)
    }

    private func reconcile() async {
        guard !draining else { return }
        draining = true
        defer { draining = false }
        while let current = applied, !matches(current, desired) {
            let target = desired
            let startedGeneration = generation
            var accepted = false
            for delay in [0] + retryDelaysMs {
                if delay > 0 { await sleep(delay) }
                guard generation == startedGeneration else { break }
                if await apply(target) { accepted = true; break }
            }
            guard generation == startedGeneration else { continue }
            if accepted {
                applied = target
                await onApplied(target)
            } else {
                if !target.record { await onDisableFailed() }
                return
            }
        }
    }
}
