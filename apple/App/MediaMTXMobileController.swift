import Foundation
import Combine
import Darwin
import MediaMTXMobile
import R2CCore

enum MediaMTXMobileControllerError: Error {
    case alreadyRunning
    case startFailed(status: Int32)
}

actor MediaMTXMobileController: MediaServerController {
    nonisolated let events: AsyncStream<MediaServerEvent>

    private nonisolated let continuation: AsyncStream<MediaServerEvent>.Continuation
    private var parser = MediaMTXLogEventParser()
    private var retainedSelf: Unmanaged<MediaMTXMobileController>?
    private var configurationURL: URL?
    private var running = false
    private var finalizationTokens: [String: [UUID]] = [:]

    init() {
        let pair = AsyncStream<MediaServerEvent>.makeStream(bufferingPolicy: .bufferingNewest(256))
        events = pair.stream
        continuation = pair.continuation
    }

    deinit {
        for token in finalizationTokens.values.flatMap({ $0 }) {
            RecordingFinalizationActivity.shared.finish(token)
        }
        continuation.finish()
    }

    func start(configuration: Data) async throws {
        guard !running else { throw MediaMTXMobileControllerError.alreadyRunning }

        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RID2Caltopo-MediaMTX", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let configurationURL = directory.appendingPathComponent("mediamtx.yml")
        #if DEBUG
        let effectiveConfiguration: Data
        if var text = String(data: configuration, encoding: .utf8) {
            text = text.replacingOccurrences(of: "logLevel: info", with: "logLevel: debug")
            effectiveConfiguration = Data(text.utf8)
        } else {
            effectiveConfiguration = configuration
        }
        #else
        let effectiveConfiguration = configuration
        #endif
        try effectiveConfiguration.write(to: configurationURL, options: .atomic)

        let retainedSelf = Unmanaged.passRetained(self)
        let context = UInt(bitPattern: retainedSelf.toOpaque())
        R2CMediaMTXSetLogCallback(mediaMTXSwiftLogCallback, context)

        let status = configurationURL.path.withCString { path in
            R2CMediaMTXStart(UnsafeMutablePointer(mutating: path))
        }
        guard status == 0 else {
            R2CMediaMTXSetLogCallback(nil, 0)
            retainedSelf.release()
            throw MediaMTXMobileControllerError.startFailed(status: status)
        }

        self.configurationURL = configurationURL
        self.retainedSelf = retainedSelf
        running = true
    }

    func stop() async {
        guard running else { return }
        // Close the log pipe first so forwardLogLines exits, then clear the callback
        // before releasing the Unmanaged retain (callback invokes retain themselves).
        R2CMediaMTXStop()
        R2CMediaMTXSetLogCallback(nil, 0)
        retainedSelf?.release()
        retainedSelf = nil
        configurationURL = nil
        running = false
    }

    #if DEBUG
    func simulateSilentListenerExit() {
        guard running else { return }
        R2CMediaMTXStop()
        AppleLog.info("MediaMTX", "Simulated silent native listener exit")
    }
    #endif

    fileprivate func receive(logLine: String) {
        LiveIngressTimingStore.shared.record(line: logLine)
        let handshakeNoise = logLine.contains("RTMP handshake failed")
        let suppressHandshake = handshakeNoise
            && !LogThrottle.shouldLog(key: "mediamtx.rtmp.handshake", intervalMs: 60_000)
        if !suppressHandshake && !logLine.contains("RTMP control: sent Acknowledge") {
            AppleLog.debug("MediaMTX", logLine)
        }
        if let event = parser.parse(line: logLine) {
            if case let .recordFileCompleted(path, _, _) = event {
                finalizationTokens[path, default: []].append(
                    RecordingFinalizationActivity.shared.begin(path: path)
                )
            }
            switch continuation.yield(event) {
            case let .dropped(dropped):
                if case let .recordFileCompleted(path, _, _) = dropped {
                    finishRecordingEvent(path: path)
                }
            case .terminated:
                if case let .recordFileCompleted(path, _, _) = event {
                    finishRecordingEvent(path: path)
                }
            default: break
            }
        }
    }

    func finishRecordingEvent(path: String) {
        guard var tokens = finalizationTokens[path], !tokens.isEmpty else { return }
        RecordingFinalizationActivity.shared.finish(tokens.removeFirst())
        if tokens.isEmpty { finalizationTokens.removeValue(forKey: path) }
        else { finalizationTokens[path] = tokens }
    }

    func pendingFinalizationCount() -> Int {
        finalizationTokens.values.reduce(0) { $0 + $1.count }
    }

    func discardPendingRecordingEvents() {
        for token in finalizationTokens.values.flatMap({ $0 }) {
            RecordingFinalizationActivity.shared.finish(token)
        }
        finalizationTokens.removeAll()
    }
}

private func mediaMTXSwiftLogCallback(
    context: UInt,
    line: UnsafePointer<CChar>?
) {
    guard context != 0, let line, let pointer = UnsafeRawPointer(bitPattern: context) else { return }
    // Retain across the hop: stop() may release the start-time retain while this
    // callback is still between Go's unlocked invoke and the actor Task.
    let unmanaged = Unmanaged<MediaMTXMobileController>.fromOpaque(pointer)
    unmanaged.retain()
    let controller = unmanaged.takeUnretainedValue()
    let logLine = String(cString: line)
    Task {
        await controller.receive(logLine: logLine)
        unmanaged.release()
    }
}

@MainActor
final class MediaMTXViewModel: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var status = "Stopped"
    @Published private(set) var activePublisherPaths: Set<String> = []
    @Published var storageWarning: String?
    @Published private(set) var storageNeedsAllowance = false
    var eventHandler: ((MediaServerEvent) -> Void)?

    private let controller = MediaMTXMobileController()
    private let storageControl = OperationalWorkQueue(label: "org.ncssar.media-storage.control")
    private var eventTask: Task<Void, Never>?
    private var healthCheckTask: Task<Void, Never>?
    private var recordingAllowed = true
    private var storagePressureReported = false
    // Storage and capture toggle recording through the MediaMTX API, never by restarting
    // the server. A restart is only the fallback for turning recording off.
    private lazy var recordingReconciler = MediaMTXRecordingReconciler(
        onApplied: { [storageControl] state in
            await storageControl.perform { AppleFlightStorage.setRecorderRunning(state.record) }
            AppleLog.debug("MediaMTX", "Recording \(state.record ? "on" : "off") via API")
        },
        onDisableFailed: { [weak self] in
            await MainActor.run { self?.restartForStorageSafety() }
        }
    )

    func start(captureStreams: Bool? = nil) {
        guard !isRunning, status != "Starting" else { return }
        status = "Starting"
        let captureStreams = captureStreams
            ?? (UserDefaults.standard.object(forKey: "video.captureStreams") as? Bool ?? true)

        if eventTask == nil {
            eventTask = Task { [controller] in
                for await event in controller.events {
                    guard !Task.isCancelled else { return }
                    // Publisher lifecycle is delivered immediately. Recording finalization
                    // (protect, localize, normalize, deliver, release) runs on its own
                    // serial chain so it can never hold up streamStarted/streamStopped.
                    switch event {
                    case let .serverStarted(version):
                        AppleStreamsServerStatus.shared.version = version
                        AppleStreamsServerStatus.shared.startedAt = Date()
                        status = "Running \(version)"
                        eventHandler?(event)
                    case let .streamStarted(path, _):
                        activePublisherPaths.insert(path)
                        status = "Streaming \(path)"
                        eventHandler?(event)
                    case let .streamStopped(path, _):
                        activePublisherPaths.remove(path)
                        status = "Stopped stream \(path)"
                        eventHandler?(event)
                    case let .streamError(path, _, detail):
                        if let path { activePublisherPaths.remove(path) }
                        status = "Error \(path ?? "stream"): \(detail)"
                        eventHandler?(event)
                    case let .recordFileCompleted(path, filePath, _):
                        status = "Recorded \(path)"
                        AppleLog.info("MediaMTX", "Recording file complete path=\(filePath)")
                        enqueueRecordingFinalization(event)
                    default:
                        eventHandler?(event)
                    }
                }
            }
        }

        Task {
            do {
                guard let url = Bundle.main.url(forResource: "mediamtx", withExtension: "yml") else {
                    status = "Configuration missing"
                    return
                }
                let (baseConfiguration, rootChoice) = try await Task.detached(priority: .userInitiated) {
                    (try Data(contentsOf: url), RecordingRootChoice.resolve(
                        durable: { try Self.capturedStreamsDirectory() },
                        temporary: FileManager.default.temporaryDirectory.appendingPathComponent("RID2Caltopo-recording-staging", isDirectory: true)))
                }.value
                let recordingRoot = rootChoice.root
                if let warning = rootChoice.warning { AppleLog.error("MediaMTX", warning) }
                // Retention cleanup is housekeeping: the listener opens on the known
                // estimate and the sweep runs afterwards; recording follows via the API.
                let storage = AppleFlightStorage.recordingStartEstimate()
                recordingAllowed = !storage.blocked && rootChoice.recordable
                reportStoragePressure(storage)
                // No day-level "recorder" owner: it pinned the start day after
                // midnight. Today is always protected, and incomplete segments are
                // protected for this recorder run via setRecorderRunning.
                let capturesRecordings = captureStreams && recordingAllowed
                // Logged at every start so a missed flight can be traced to the setting in force.
                if captureStreams { AppleLog.info("MediaMTX", CaptureStreamsLog.serverStart(true)) }
                else { AppleLog.warning("MediaMTX", CaptureStreamsLog.serverStart(false)) }
                let configuration = try MediaMTXRuntimeConfiguration.build(
                    base: baseConfiguration,
                    captureStreams: captureStreams,
                    restrictNetworkAccess: UserDefaults.standard.object(forKey: "video.restrictMediaServerAccess") as? Bool ?? true,
                    recordingRoot: recordingRoot,
                    recordNow: capturesRecordings
                )
                let recorderStartedAt = Date()
                try await controller.start(configuration: configuration)
                await storageControl.perform {
                    AppleFlightStorage.setRecorderRunning(capturesRecordings, startedAt: recorderStartedAt)
                }
                await recordingReconciler.serverStarted(
                    configured: MediaMTXRecordingState(record: capturesRecordings, recordingRoot: recordingRoot),
                    captureEnabled: captureStreams,
                    storageBlocked: !recordingAllowed,
                    recordingRoot: recordingRoot
                )
                if !leftoverRecoveryScheduled {
                    // Once per process: anything raw-named and untouched since this
                    // recorder started was left by an earlier run.
                    leftoverRecoveryScheduled = true
                    scheduleLeftoverRecordingRecovery(cutoff: recorderStartedAt)
                }
                Task.detached(priority: .utility) {
                    let swept = AppleFlightStorage.maintain()
                    await self.handleStorageSnapshot(swept)
                }
                isRunning = true
                if status == "Starting" {
                    status = captureStreams && recordingAllowed ? "Running • capturing streams" : (captureStreams ? "Capture paused: storage limit" : "Running")
                startStorageMonitor()
                }
            } catch {
                AppleLog.error("MediaMTX", "Start failed: \(error)")
                eventTask?.cancel()
                eventTask = nil
                await controller.discardPendingRecordingEvents()
                status = "Start failed: \(error)"
            }
        }
    }

    private let recordingFinalization = SerialTaskChain()
    private let leftoverRecovery = SerialTaskChain()
    private var leftoverRecoveryScheduled = false

    /// Startup recovery for segments an earlier run never finished (killed, updated or
    /// crashed mid-segment). Runs after the listener is open, on its own background
    /// chain, so it never delays the UI, publishers or this run's own finalization.
    /// Matches Android's startup orphan sync (1c366086).
    private func scheduleLeftoverRecordingRecovery(cutoff: Date) {
        leftoverRecovery.enqueue(priority: .background) { [weak self, storageControl] in
            let ledger = LeftoverRecordingFailureLedger()
            let found = LeftoverRecordingRecovery.candidates(
                root: AppleFlightStorage.root,
                notTouchedSince: cutoff,
                failures: ledger
            )
            AppleLog.info("MediaMTX", "Leftover recording scan found=\(found.count)")
            for candidate in found {
                await Self.recoverLeftoverRecording(candidate, ledger: ledger, storageControl: storageControl) { event in
                    await MainActor.run { self?.eventHandler?(event) }
                }
            }
        }
    }

    private nonisolated static func recoverLeftoverRecording(
        _ candidate: LeftoverRecordingRecovery.Candidate,
        ledger: LeftoverRecordingFailureLedger,
        storageControl: OperationalWorkQueue,
        deliver: @escaping @Sendable (MediaServerEvent) async -> Void
    ) async {
        let raw = candidate.url
        let header = LeftoverRecordingRecovery.checkHeader(raw)
        guard header == .playable else {
            AppleLog.error("MediaMTX", "Leftover recording left in place file=\(raw.lastPathComponent) size=\(candidate.size) reason=\(header.reason)")
            ledger.record(candidate, reason: header.reason)
            return
        }
        AppleLog.info("MediaMTX", "Recovering leftover recording file=\(raw.lastPathComponent) size=\(candidate.size) folder=\(raw.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent)")
        let owner = "leftover-" + UUID().uuidString
        // Protect both the folder it sits in (often the UTC date) and the local day it moves to.
        let sourceDay = raw.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent
        let localDay = ManagedVideoRecordingIdentity.recordingStartedAt(forPath: raw.path).map(AppleFlightStorage.dayName)
        await storageControl.perform {
            AppleFlightStorage.protect(sourceDay, owner: owner + "-source")
            if let localDay { AppleFlightStorage.protect(localDay, owner: owner + "-local") }
        }
        defer {
            storageControl.enqueue {
                AppleFlightStorage.release(owner: owner + "-source")
                AppleFlightStorage.release(owner: owner + "-local")
            }
        }
        let completed = MediaServerEvent.recordFileCompleted(path: candidate.streamPath, filePath: raw.path, durationMilliseconds: 0)
        let localized = localizeCompletedRecordingIfNeeded(completed)
        guard case let .recordFileCompleted(_, localizedPath, _) = localized, localizedPath != raw.path else {
            AppleLog.error("MediaMTX", "Leftover recording left in place file=\(raw.lastPathComponent) reason=rename failed")
            ledger.record(candidate, reason: "rename failed")
            return
        }
        let (delivered, converted) = await normalizeCompletedRecording(localized)
        guard converted else {
            // Never delete: put the untouched original back under its raw name.
            let localizedURL = URL(fileURLWithPath: localizedPath)
            if !FileManager.default.fileExists(atPath: raw.path),
               (try? FileManager.default.moveItem(at: localizedURL, to: raw)) != nil {
                AppleLog.error("MediaMTX", "Leftover recording left in place file=\(raw.lastPathComponent) reason=conversion failed")
                if let values = try? raw.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]),
                   let size = values.fileSize, let modified = values.contentModificationDate {
                    ledger.record(.init(url: raw, size: Int64(size), modified: modified), reason: "conversion failed")
                }
            } else {
                AppleLog.error("MediaMTX", "Leftover recording conversion failed; kept original as \(localizedURL.lastPathComponent)")
            }
            return
        }
        ledger.clear(path: raw.path)
        LeftoverRecordingRecovery.restoreRecordedTime(of: URL(fileURLWithPath: localizedPath), to: candidate)
        AppleLog.info("MediaMTX", "Recovered leftover recording from=\(raw.lastPathComponent) to=\(URL(fileURLWithPath: localizedPath).path.replacingOccurrences(of: AppleFlightStorage.root.path + "/", with: ""))")
        await deliver(delivered)
    }

    private func enqueueRecordingFinalization(_ event: MediaServerEvent) {
        guard case let .recordFileCompleted(path, filePath, _) = event else { return }
        recordingFinalization.enqueue(priority: .utility) { [weak self, controller, storageControl] in
            let storageOwner = "finalize-" + UUID().uuidString
            if let date = ManagedVideoRecordingIdentity.recordingStartedAt(forPath: filePath) {
                await storageControl.perform { AppleFlightStorage.protect(AppleFlightStorage.dayName(date), owner: storageOwner) }
            }
            let localized = Self.localizeCompletedRecordingIfNeeded(event)
            let delivered = await Self.normalizeCompletedRecordingIfNeeded(localized)
            await MainActor.run { self?.eventHandler?(delivered) }
            storageControl.enqueue { AppleFlightStorage.release(owner: storageOwner) }
            await controller.finishRecordingEvent(path: path)
        }
    }

    private nonisolated static func localizeCompletedRecordingIfNeeded(
        _ event: MediaServerEvent
    ) -> MediaServerEvent {
        guard case let .recordFileCompleted(path, filePath, durationMs) = event else {
            return event
        }
        let sourceURL = URL(fileURLWithPath: filePath)
        guard let localizedURL = ManagedVideoRecordingIdentity.localizedMediaMTXRecordingURL(
            for: sourceURL
        ), localizedURL != sourceURL else {
            return event
        }

        do {
            let startedAt = ManagedVideoRecordingIdentity.recordingStartedAt(forPath: sourceURL.path) ?? Date()
            let localDay = AppleFlightStorage.root.appendingPathComponent(AppleFlightStorage.dayName(startedAt))
                .appendingPathComponent(sourceURL.deletingLastPathComponent().lastPathComponent)
            try FileManager.default.createDirectory(at: localDay, withIntermediateDirectories: true)
            let availableURL = ManagedVideoRecordingIdentity.availableRecordingURL(
                preferred: localDay.appendingPathComponent(localizedURL.lastPathComponent),
                fileExists: FileManager.default.fileExists(atPath:)
            )
            try FileManager.default.moveItem(at: sourceURL, to: availableURL)
            AppleLog.info(
                "MediaMTX",
                "Localized recording filename from=\(sourceURL.lastPathComponent) to=\(availableURL.lastPathComponent)"
            )
            return .recordFileCompleted(
                path: path,
                filePath: availableURL.path,
                durationMilliseconds: durationMs
            )
        } catch {
            AppleLog.error(
                "MediaMTX",
                "Unable to localize recording filename path=\(filePath): \(error)"
            )
            return event
        }
    }

    private static func normalizeCompletedRecordingIfNeeded(
        _ event: MediaServerEvent
    ) async -> MediaServerEvent {
        await normalizeCompletedRecording(event).0
    }

    /// Converts in place for iOS playback; `true` once the converted file replaced the
    /// original. On failure the original is untouched.
    private nonisolated static func normalizeCompletedRecording(
        _ event: MediaServerEvent
    ) async -> (MediaServerEvent, Bool) {
        guard case let .recordFileCompleted(path, filePath, durationMs) = event else {
            return (event, false)
        }
        let preserveOriginal = await MainActor.run { AppleSEIHexDiagnostics.preserveOriginals }
        let result = await Task.detached(priority: .utility) { () -> (Bool, String) in
            let sourceURL = URL(fileURLWithPath: filePath)
            if preserveOriginal {
                let originalURL = sourceURL.deletingPathExtension().appendingPathExtension("sei-original.mp4")
                do {
                    try FileManager.default.copyItem(at: sourceURL, to: originalURL)
                    AppleLog.info("DjiSeiPayload", "Preserved original recording: \(originalURL.lastPathComponent)")
                } catch {
                    // Never replace the sole original when preservation fails.
                    return (false, "Research original preservation failed: \(error.localizedDescription)")
                }
            }
            let temporaryURL = sourceURL.deletingLastPathComponent().appendingPathComponent(
                ".\(sourceURL.deletingPathExtension().lastPathComponent)-ios-compatible-\(UUID().uuidString).mp4"
            )
            AppleFlightStorage.prepareWrite(Int64((try? sourceURL.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0))
            var detail = [CChar](repeating: 0, count: 256)
            let status = sourceURL.path.withCString { sourcePath in
                temporaryURL.path.withCString { destinationPath in
                    R2CFFmpegNormalizeRecording(
                        sourcePath,
                        destinationPath,
                        &detail,
                        Int32(detail.count)
                    )
                }
            }
            let message = String(
                decoding: detail.prefix { $0 != 0 }.map(UInt8.init(bitPattern:)),
                as: UTF8.self
            )
            guard status == 0 else {
                try? FileManager.default.removeItem(at: temporaryURL)
                return (false, message.isEmpty ? "error=\(status)" : message)
            }
            do {
                _ = try FileManager.default.replaceItemAt(
                    sourceURL,
                    withItemAt: temporaryURL,
                    backupItemName: nil,
                    options: []
                )
                return (true, message)
            } catch {
                try? FileManager.default.removeItem(at: temporaryURL)
                return (false, "replace failed: \(error.localizedDescription)")
            }
        }.value
        if result.0 {
            AppleLog.info(
                "MediaMTX",
                "Normalized recording for iOS playback file=\(URL(fileURLWithPath: filePath).lastPathComponent) \(result.1)"
            )
        } else {
            AppleLog.error(
                "MediaMTX",
                "Recording normalization failed file=\(URL(fileURLWithPath: filePath).lastPathComponent) detail=\(result.1)"
            )
        }
        return (.recordFileCompleted(
            path: path,
            filePath: filePath,
            durationMilliseconds: durationMs
        ), result.0)
    }

    func stop() {
        guard isRunning else { return }
        Task {
            await controller.stop()
            await recordingReconciler.serverStopped()
            await storageControl.perform {
                AppleFlightStorage.release(owner: "recorder")
                AppleFlightStorage.setRecorderRunning(false)
            }
            for path in activePublisherPaths {
                eventHandler?(.streamStopped(path: path, publisherConnectionID: nil))
            }
            activePublisherPaths.removeAll()
            isRunning = false
        AppleStreamsServerStatus.shared.startedAt = nil
            status = "Stopped"
        }
    }

    private func reportStoragePressure(_ snapshot: AppleFlightStorage.Snapshot) {
        if snapshot.blocked && !storagePressureReported {
            storageWarning = snapshot.message
            storageNeedsAllowance = snapshot.allowanceInsufficient
        }
        storagePressureReported = snapshot.blocked
        if !snapshot.blocked { storageWarning = nil }
    }

    /// Storage verdicts switch recording on/off through the API; the server keeps running.
    private func handleStorageSnapshot(_ snapshot: AppleFlightStorage.Snapshot) {
        reportStoragePressure(snapshot)
        let allowed = !snapshot.blocked
        if allowed != recordingAllowed {
            recordingAllowed = allowed
            AppleLog.debug("MediaMTX", "Storage capture \(allowed ? "allowed" : "blocked"); updating recording via API")
            let requested = UserDefaults.standard.object(forKey: "video.captureStreams") as? Bool ?? true
            if isRunning, status.hasPrefix("Running") || status.hasPrefix("Capture paused") {
                status = requested && allowed ? "Running • capturing streams" : (requested ? "Capture paused: storage limit" : "Running")
            }
        }
        Task { await recordingReconciler.updateStorageBlocked(!allowed) }
    }

    /// Capture Streams changed: switch recording through the API without restarting the server.
    func updateCaptureEnabled(_ enabled: Bool) {
        AppleLog.info("MediaMTX", "Capture Streams \(enabled ? "ON" : "OFF"); updating recording via API (no restart)")
        if isRunning, status.hasPrefix("Running") || status.hasPrefix("Capture paused") {
            status = enabled && recordingAllowed ? "Running • capturing streams" : (enabled ? "Capture paused: storage limit" : "Running")
        }
        Task { await recordingReconciler.updateCaptureEnabled(enabled) }
    }

    /// Fallback when MediaMTX did not accept turning recording off.
    private func restartForStorageSafety() {
        AppleLog.error("MediaMTX", "Recording API did not accept record=no; restarting to enforce the storage limit")
        restart(captureStreams: UserDefaults.standard.object(forKey: "video.captureStreams") as? Bool ?? true)
    }

    private func startStorageMonitor() {
        storageControl.enqueue { [weak self] in
        AppleFlightStorage.observe { [weak self] snapshot in
            Task { @MainActor [weak self] in
                self?.handleStorageSnapshot(snapshot)
            }
        }
        }
    }

    /// Quit-time bound for finishing in-flight localize/normalize. Unfinished
    /// recordings remain on disk and are recoverable on the next launch.
    private static let quitRecordingDrainTimeoutSeconds: TimeInterval = 5

    func shutdown() async {
        storageControl.enqueue { AppleFlightStorage.stopObserving() }
        AppleFlightStorage.cancelPendingMaintenance()
        healthCheckTask?.cancel()
        healthCheckTask = nil

        // Stop the native server first so no new publishers/recordings start, but keep
        // the event loop briefly so buffered recordFileCompleted work can finish.
        await controller.stop()
        await recordingReconciler.serverStopped()
        await drainRecordingFinalizers(timeoutSeconds: Self.quitRecordingDrainTimeoutSeconds)

        eventTask?.cancel()
        eventTask = nil
        // Remaining tokens / un-normalized files are left recoverable for next launch.
        await controller.discardPendingRecordingEvents()
        await storageControl.perform {
            AppleFlightStorage.release(owner: "recorder")
            AppleFlightStorage.setRecorderRunning(false)
        }
        for path in activePublisherPaths {
            eventHandler?(.streamStopped(path: path, publisherConnectionID: nil))
        }
        activePublisherPaths.removeAll()
        isRunning = false
        AppleStreamsServerStatus.shared.startedAt = nil
        status = "Stopped"
    }

    /// Wait briefly for outstanding recording finalizers. Never unbounded — quit and
    /// durable track/clue work must not wait on MediaMTX normalize.
    private func drainRecordingFinalizers(timeoutSeconds: TimeInterval) async {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            let controllerPending = await controller.pendingFinalizationCount()
            if controllerPending == 0 && !RecordingFinalizationActivity.shared.hasPending {
                return
            }
            try? await Task.sleep(for: .milliseconds(50))
        }
        AppleLog.warning(
            "MediaMTX",
            "Quit recording drain timed out after \(Int(timeoutSeconds))s; leaving recordings recoverable on next launch"
        )
    }

    func restart(captureStreams: Bool) {
        guard isRunning else {
            start(captureStreams: captureStreams)
            return
        }
        status = "Restarting"
        Task {
            await controller.stop()
            await recordingReconciler.serverStopped()
            await storageControl.perform {
                AppleFlightStorage.release(owner: "recorder")
                AppleFlightStorage.setRecorderRunning(false)
            }
            for path in activePublisherPaths {
                eventHandler?(.streamStopped(path: path, publisherConnectionID: nil))
            }
            activePublisherPaths.removeAll()
            isRunning = false
        AppleStreamsServerStatus.shared.startedAt = nil
            status = "Stopped"
            start(captureStreams: captureStreams)
        }
    }

    func ensureHealthy(captureStreams: Bool) {
        guard healthCheckTask == nil else { return }
        guard isRunning else {
            if status != "Starting", status != "Restarting" {
                AppleLog.error("MediaMTX", "RTMP listener unavailable while server is stopped; starting")
                start(captureStreams: captureStreams)
            }
            return
        }
        healthCheckTask = Task { [weak self] in
            let listenerAvailable = await Self.localListenerAvailable()
            guard let self else { return }
            self.healthCheckTask = nil
            guard !listenerAvailable else { return }
            AppleLog.error(
                "MediaMTX",
                "RTMP listener health check failed while state was '\(self.status)'; restarting"
            )
            self.restart(captureStreams: captureStreams)
        }
    }

    #if DEBUG
    func simulateSilentListenerExit() {
        Task { await controller.simulateSilentListenerExit() }
    }
    #endif

    /// Probe the RTMP ingest listener, not HLS. Live playback uses RTSP; polling
    /// `http://127.0.0.1:8888/` only produced 404 spam when nothing needed HLS.
    /// nonisolated so the DispatchQueue work item is not a @MainActor-isolated closure.
    private nonisolated static func localListenerAvailable() async -> Bool {
        await withCheckedContinuation { continuation in
            let work: @Sendable () -> Void = {
                let socket = socket(AF_INET, SOCK_STREAM, 0)
                guard socket >= 0 else {
                    continuation.resume(returning: false)
                    return
                }
                defer { close(socket) }
                var addr = sockaddr_in()
                addr.sin_family = sa_family_t(AF_INET)
                addr.sin_port = in_port_t(1935).bigEndian
                addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
                let connectResult: Int32 = withUnsafePointer(to: &addr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { pointer in
                        // Non-blocking connect with a short poll so health checks stay off HLS.
                        let flags = fcntl(socket, F_GETFL, 0)
                        _ = fcntl(socket, F_SETFL, flags | O_NONBLOCK)
                        let result = connect(socket, pointer, socklen_t(MemoryLayout<sockaddr_in>.size))
                        if result == 0 { return 0 }
                        if errno != EINPROGRESS { return -1 }
                        var pollFD = pollfd(fd: socket, events: Int16(POLLOUT), revents: 0)
                        let polled = poll(&pollFD, 1, 400)
                        guard polled > 0 else { return -1 }
                        var soError: Int32 = 0
                        var length = socklen_t(MemoryLayout<Int32>.size)
                        getsockopt(socket, SOL_SOCKET, SO_ERROR, &soError, &length)
                        return soError == 0 ? 0 : -1
                    }
                }
                continuation.resume(returning: connectResult == 0)
            }
            DispatchQueue.global(qos: .utility).async(execute: work)
        }
    }

    private nonisolated static func capturedStreamsDirectory() throws -> URL {
        let documents = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = documents.appendingPathComponent(
            "RID2Caltopo/FlightStorage",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        return directory
    }
}


@MainActor
final class AppleStreamsServerStatus: ObservableObject {
    static let shared = AppleStreamsServerStatus()
    @Published var version = "Unavailable"
    @Published var startedAt: Date?
}
