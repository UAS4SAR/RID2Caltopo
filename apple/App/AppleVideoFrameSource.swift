import AVFoundation
import Combine
import CoreVideo
import CoreImage
import Dispatch
import QuartzCore
import R2CCore

private struct PixelBufferTransfer: @unchecked Sendable {
    let value: CVPixelBuffer
}

private final class WeakVideoDisplayLayer {
    weak var value: AVSampleBufferDisplayLayer?

    init(_ value: AVSampleBufferDisplayLayer) {
        self.value = value
    }
}

struct AppleVideoSnapshot: Sendable, Equatable {
    let jpegData: Data
    let capturedAt: Date
    let width: Int
    let height: Int
}

protocol AppleDecodedVideoFrameConsumer: AnyObject {
    func consumeDecodedVideoFrame(
        _ pixelBuffer: CVPixelBuffer,
        timestampNanoseconds: Int64
    )
}

enum AppleVideoSnapshotError: LocalizedError {
    case noDecodedFrame
    case conversionFailed

    var errorDescription: String? {
        switch self {
        case .noDecodedFrame: "No decoded video frame is available yet."
        case .conversionFailed: "The decoded frame could not be converted to a snapshot."
        }
    }
}

struct AppleAnomalyBox: Sendable, Equatable, Identifiable {
    let id: Int
    let left: Double
    let top: Double
    let right: Double
    let bottom: Double
    let weight: Double
    let red: UInt8
    let green: UInt8
    let blue: UInt8
    let drawsCrosshair: Bool
    let algorithm: Int
}

struct AppleAnomalyResult: Sendable {
    let frameOrdinal: Int64
    let rawBoxCount: Int
    let stableBoxCount: Int
    let annotationCount: Int
    let boxes: [AppleAnomalyBox]
    let hotOverlay: AppleAnomalyHotOverlay?
}

struct AppleAnomalyHotOverlay: Sendable, Equatable {
    let centerX: Double
    let centerY: Double
    let radius: Double
    let stroke: Double
}

enum AppleAnomalyMode: String, CaseIterable, Identifiable, Sendable {
    case off
    case colorUniqueness
    case targetColors
    case infrared

    var id: String { rawValue }

    var label: String {
        switch self {
        case .off: "Off"
        case .colorUniqueness: "Color Uniqueness"
        case .targetColors: "Target Colors"
        case .infrared: "Infrared"
        }
    }

    var compactLabel: String {
        switch self {
        case .off: "Off"
        case .colorUniqueness: "Color"
        case .targetColors: "Target"
        case .infrared: "Infrared"
        }
    }

    var algorithmMask: Int32 {
        switch self {
        case .off: 0
        case .colorUniqueness, .targetColors: R2C_ANOMALY_ALGORITHM_COLOR
        case .infrared: R2C_ANOMALY_ALGORITHM_THERMAL
        }
    }
}

/// Owns the non-Sendable C runtime and processes at most one frame at a time.
/// When analysis cannot keep up, the live path drops analysis work instead of
/// building latency while video rendering continues.
final class AppleAnomalyProcessor: @unchecked Sendable {
    private let queue = DispatchQueue(label: "org.ncssar.rid2caltopo.anomaly", qos: .userInitiated)
    private let stateLock = NSLock()
    private var busy = false
    private var mode: AppleAnomalyMode
    private var configuration: AppleAnomalyConfiguration
    private var runtime: OpaquePointer?
    private let imageContext = CIContext(options: [.cacheIntermediates: false])
    private var conversionBuffer: CVPixelBuffer?

    init(mode: AppleAnomalyMode, configuration: AppleAnomalyConfiguration) {
        self.mode = mode
        self.configuration = configuration
        runtime = R2CAnomalyCreate(configuration.algorithmMask(for: mode), 30)
        applyConfiguration()
    }

    deinit {
        R2CAnomalyDestroy(runtime)
    }

    func submit(
        pixelBuffer: CVPixelBuffer,
        timestampMicroseconds: Int64,
        completion: @escaping @MainActor @Sendable (AppleAnomalyResult) -> Void
    ) -> Bool {
        stateLock.lock()
        guard !busy else {
            stateLock.unlock()
            return false
        }
        busy = true
        stateLock.unlock()

        let transfer = PixelBufferTransfer(value: pixelBuffer)
        queue.async { [self, transfer] in
            let result = process(pixelBuffer: transfer.value, timestampMicroseconds: timestampMicroseconds)
            stateLock.lock()
            busy = false
            stateLock.unlock()
            guard let result else { return }
            Task { @MainActor in
                completion(result)
            }
        }
        return true
    }

    func configure(mode: AppleAnomalyMode, configuration: AppleAnomalyConfiguration) {
        queue.async { [self] in
            self.mode = mode
            self.configuration = configuration
            R2CAnomalyDestroy(runtime)
            runtime = R2CAnomalyCreate(configuration.algorithmMask(for: mode), 30)
            applyConfiguration()
        }
    }

    func reset() {
        queue.async { [self] in
            R2CAnomalyDestroy(runtime)
            runtime = R2CAnomalyCreate(configuration.algorithmMask(for: mode), 30)
            applyConfiguration()
        }
    }

    private func applyConfiguration() {
        guard let runtime else { return }
        var native = configuration.nativeConfiguration(for: mode)
        R2CAnomalyApplyConfiguration(runtime, &native)
    }

    private func process(
        pixelBuffer: CVPixelBuffer,
        timestampMicroseconds: Int64
    ) -> AppleAnomalyResult? {
        guard let runtime, let analysisBuffer = bgraBuffer(from: pixelBuffer) else { return nil }

        CVPixelBufferLockBaseAddress(analysisBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(analysisBuffer, .readOnly) }
        guard let baseAddress = CVPixelBufferGetBaseAddress(analysisBuffer) else { return nil }

        var result = R2CAnomalyFrameResult()
        let status = R2CAnomalyProcessBGRA(
            runtime,
            baseAddress.assumingMemoryBound(to: UInt8.self),
            Int32(CVPixelBufferGetBytesPerRow(analysisBuffer)),
            Int32(CVPixelBufferGetWidth(analysisBuffer)),
            Int32(CVPixelBufferGetHeight(analysisBuffer)),
            timestampMicroseconds,
            &result
        )
        guard status == 0 else { return nil }
        var boxes: [AppleAnomalyBox] = []
        for index in 0 ..< Int(result.annotation_count) {
            var box = R2CAnomalyBox()
            guard R2CAnomalyFrameResultCopyBox(&result, Int32(index), &box) == 0 else { continue }
            boxes.append(AppleAnomalyBox(
                id: index,
                left: Double(box.left),
                top: Double(box.top),
                right: Double(box.right),
                bottom: Double(box.bottom),
                weight: Double(box.weight),
                red: box.red,
                green: box.green,
                blue: box.blue,
                drawsCrosshair: box.draw_crosshair != 0,
                algorithm: Int(box.algorithm)
            ))
        }
        let hotOverlay = result.hot_overlay_valid != 0
            ? AppleAnomalyHotOverlay(
                centerX: Double(result.hot_center_x),
                centerY: Double(result.hot_center_y),
                radius: Double(result.hot_radius),
                stroke: Double(result.hot_stroke)
            )
            : nil
        return AppleAnomalyResult(
            frameOrdinal: result.frame_ordinal,
            rawBoxCount: Int(result.raw_box_count),
            stableBoxCount: Int(result.stable_box_count),
            annotationCount: Int(result.annotation_count),
            boxes: boxes,
            hotOverlay: hotOverlay
        )
    }

    private func bgraBuffer(from source: CVPixelBuffer) -> CVPixelBuffer? {
        if CVPixelBufferGetPixelFormatType(source) == kCVPixelFormatType_32BGRA {
            return source
        }
        let width = CVPixelBufferGetWidth(source)
        let height = CVPixelBufferGetHeight(source)
        if conversionBuffer.map({
            CVPixelBufferGetWidth($0) != width || CVPixelBufferGetHeight($0) != height
        }) ?? true {
            var buffer: CVPixelBuffer?
            let attributes = [
                kCVPixelBufferCGImageCompatibilityKey: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey: true,
                kCVPixelBufferIOSurfacePropertiesKey: [:],
            ] as CFDictionary
            guard CVPixelBufferCreate(
                kCFAllocatorDefault,
                width,
                height,
                kCVPixelFormatType_32BGRA,
                attributes,
                &buffer
            ) == kCVReturnSuccess else { return nil }
            conversionBuffer = buffer
        }
        guard let conversionBuffer else { return nil }
        let image = CIImage(cvPixelBuffer: source)
        imageContext.render(
            image,
            to: conversionBuffer,
            bounds: CGRect(x: 0, y: 0, width: width, height: height),
            colorSpace: CGColorSpace(name: CGColorSpace.sRGB)
        )
        return conversionBuffer
    }
}

/// Pulls decoded BGRA frames from an AVFoundation HLS player at display cadence.
/// Consumers can render the player while the same decoded CVPixelBuffers feed
/// the portable anomaly detector boundary.
struct AppleDJICameraTelemetry: Equatable {
    /** Raw tag-4 azimuth encoder retained alongside the calibrated azimuth. */
    let rawAzimuthCandidateDegrees: Double
    let cameraAzimuthDegrees: Double?
    let courseDegrees: Double?
    let rawTiltDegrees: Double
    let tiltDegrees: Double
    let horizontalFovDegrees: Double
    let verticalFovDegrees: Double
    let attitudeAnglesDegrees: [Double]
    let latitudeDegrees: Double?
    let longitudeDegrees: Double?
    let altitudeMeters: Double?
    let relativeUpMeters: Double?
    let referenceLatitudeDegrees: Double?
    let referenceLongitudeDegrees: Double?
    let referenceAltitudeMeters: Double?
    let sourceTimestampMicroseconds: Int64?
    let receivedAt: Date

    func replacingRelativeUpMeters(_ value: Double?) -> AppleDJICameraTelemetry {
        AppleDJICameraTelemetry(
            rawAzimuthCandidateDegrees: rawAzimuthCandidateDegrees,
            cameraAzimuthDegrees: cameraAzimuthDegrees,
            courseDegrees: courseDegrees,
            rawTiltDegrees: rawTiltDegrees,
            tiltDegrees: tiltDegrees,
            horizontalFovDegrees: horizontalFovDegrees,
            verticalFovDegrees: verticalFovDegrees,
            attitudeAnglesDegrees: attitudeAnglesDegrees,
            latitudeDegrees: latitudeDegrees,
            longitudeDegrees: longitudeDegrees,
            altitudeMeters: altitudeMeters,
            relativeUpMeters: value,
            referenceLatitudeDegrees: referenceLatitudeDegrees,
            referenceLongitudeDegrees: referenceLongitudeDegrees,
            referenceAltitudeMeters: referenceAltitudeMeters,
            sourceTimestampMicroseconds: sourceTimestampMicroseconds,
            receivedAt: receivedAt
        )
    }
}

@MainActor
final class AppleVideoFrameSource: ObservableObject {
    enum State: Equatable {
        case idle
        case connecting
        case streaming
        case waitingForPublisher(String)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var frameCount = 0
    @Published private(set) var dimensions = "--"
    @Published private(set) var sourceWidth = 0
    @Published private(set) var sourceHeight = 0
    @Published private(set) var sourceFrameRate = 0.0
    @Published private(set) var videoAspectRatio = 16.0 / 9.0
    @Published private(set) var analyzedFrameCount = 0
    @Published private(set) var droppedAnalysisFrameCount = 0
    @Published private(set) var anomalyCount = 0
    @Published private(set) var anomalyBoxes: [AppleAnomalyBox] = []
    @Published private(set) var anomalyHotOverlay: AppleAnomalyHotOverlay?
    @Published private(set) var anomalyThermallySuspended = false
    @Published private(set) var anomalyMode: AppleAnomalyMode
    @Published private(set) var anomalyConfiguration: AppleAnomalyConfiguration
    @Published private(set) var player: AVPlayer?
    @Published private(set) var usesNativeVideoSurface = false
    @Published private(set) var decoderBackend = "None"
    @Published private(set) var streamDesignator = "--"

    var activeSourcePath: String? { currentPath }
    @Published private(set) var renderDelayMilliseconds: Int64?
    @Published private(set) var decoderDelayMilliseconds: Int64?
    @Published private(set) var decodedFrameAgeSeconds: Double?
    @Published private(set) var recoveryCount = 0
    @Published private(set) var lastRecoveryReason = "None"
    @Published private(set) var nextRetryDelaySeconds: Double?
    @Published private(set) var mediaPublisherStatus = "Unknown"
    @Published private(set) var latestGimbalPitchDegrees: Double?
    @Published private(set) var latestCameraYawDegrees: Double?
    @Published private(set) var latestStreamHeadingDegrees: Double?
    private(set) var latestDJICameraTelemetry: AppleDJICameraTelemetry?

    func freshDJICameraTelemetry(
        now: Date = Date(),
        maximumAge: TimeInterval = 3
    ) -> AppleDJICameraTelemetry? {
        latestDJICameraTelemetry.flatMap {
            let age = now.timeIntervalSince($0.receivedAt)
            return age >= 0 && age <= maximumAge ? $0 : nil
        }
    }

    private var videoOutput: AVPlayerItemVideoOutput?
    private var ffmpegSession: OpaquePointer?
    private var ffmpegFrameSequence: UInt64 = 0
    private var ffmpegDJICameraTelemetrySequence: UInt64 = 0
    private var loggedNativeFrameFormat = false
    private var loggedNativeDisplayFailure = false
    private var nativeFailureHandled = false
    private var nativeEndHandled = false
    private var displayLink: CADisplayLink?
    private var itemStatusObservation: NSKeyValueObservation?
    private var notificationObservers: [NSObjectProtocol] = []
    private var retryTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var currentURL: URL?
    private var currentPath: String?
    private var playerGeneration = 0
    private var recoveryPolicy = LiveVideoRecoveryPolicy()
    private var decoderSelectionPolicy = LiveVideoDecoderSelectionPolicy()
    private var latestPixelBuffer: CVPixelBuffer?
    private var latestFrameCapturedAt: Date?
    private weak var managedVideoFrameConsumer: AppleDecodedVideoFrameConsumer?
    private var frameRateWindowStartedAt: CFTimeInterval?
    private var frameRateWindowFrameCount = 0
    private var displayLayers: [ObjectIdentifier: WeakVideoDisplayLayer] = [:]
    private var lagEstimator = LiveVideoLagEstimator()
    private var sessionLagEstimator = LiveVideoSessionLagEstimator()
    private let defaults: UserDefaults
    private let anomalyProcessor: AppleAnomalyProcessor

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        defaults.removeObject(forKey: "video.anomalyMode")
        let mode = AppleAnomalyMode.off
        let configuration = AppleAnomalyConfiguration.load(from: defaults)
        anomalyMode = mode
        anomalyConfiguration = configuration
        anomalyProcessor = AppleAnomalyProcessor(mode: mode, configuration: configuration)
    }

    func setManagedVideoFrameConsumer(_ consumer: AppleDecodedVideoFrameConsumer?) {
        managedVideoFrameConsumer = consumer
    }

    func setAnomalyMode(_ mode: AppleAnomalyMode) {
        guard mode != anomalyMode else { return }
        let transitionedConfiguration = anomalyConfiguration.transitioning(to: mode)
        anomalyMode = mode
        anomalyConfiguration = transitionedConfiguration
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyCount = 0
        anomalyBoxes = []
        anomalyHotOverlay = nil
        anomalyProcessor.configure(mode: mode, configuration: transitionedConfiguration)
        AppleLog.info(
            "Anomaly",
            "Detector configured \(transitionedConfiguration.diagnosticSummary(for: mode))"
        )
    }

    func applyAnomalyConfiguration(_ configuration: AppleAnomalyConfiguration) {
        let normalized = configuration.normalized
        anomalyConfiguration = normalized
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyCount = 0
        anomalyBoxes = []
        anomalyHotOverlay = nil
        anomalyProcessor.configure(mode: anomalyMode, configuration: normalized)
        AppleLog.info(
            "Anomaly",
            "Detector configured \(normalized.diagnosticSummary(for: anomalyMode))"
        )
    }

    func start(url: URL) {
        stop()
        currentURL = url
        currentPath = Self.streamPath(from: url)
        streamDesignator = Self.designator(from: currentPath)
        renderDelayMilliseconds = nil
        decoderDelayMilliseconds = nil
        lagEstimator.reset()
        recoveryPolicy.reset(at: Self.now)
        decoderSelectionPolicy.reset()
        startWatchdog()
        installPreferredDecoder(hlsURL: url)
        AppleLog.info(
            "Video",
            "Decoder session requested url=\(url.absoluteString) path=\(currentPath ?? "unknown") " +
                "networkSnapshotId=\(AppleNetworkDiagnosticCenter.shared.currentSnapshotID)"
        )
    }

    func startPlayback(url: URL) {
        stop()
        currentURL = url
        currentPath = url.lastPathComponent
        streamDesignator = url.deletingLastPathComponent().lastPathComponent
        renderDelayMilliseconds = nil
        decoderDelayMilliseconds = nil
        lagEstimator.reset()
        recoveryPolicy.reset(at: Self.now)
        decoderSelectionPolicy.reset()
        startWatchdog()
        installNativePlayback(url: url)
        AppleLog.info("Video", "Local managed-video playback requested file=\(url.lastPathComponent)")
    }

    private func installNativePlayback(url: URL) {
        tearDownPlayer()
        tearDownNativeDecoder()
        playerGeneration &+= 1
        recoveryPolicy.beginRecoveryAttempt(at: Self.now)
        state = .connecting
        nextRetryDelaySeconds = nil
        nativeFailureHandled = false
        nativeEndHandled = false
        ffmpegFrameSequence = 0
        ffmpegDJICameraTelemetrySequence = 0
        loggedNativeFrameFormat = false
        loggedNativeDisplayFailure = false

        guard let session = url.absoluteString.withCString({
            R2CFFmpegSessionCreatePlayback($0)
        }) else {
            AppleLog.warning("Video", "Native recording decoder could not start; using AVPlayer fallback")
            installPlayer(url: url, beginsRecoveryAttempt: false)
            return
        }
        ffmpegSession = session
        usesNativeVideoSurface = true
        decoderBackend = "FFmpeg \(String(cString: R2CFFmpegVersion())) / VideoToolbox recording playback"
        flushDisplayLayers(removeImage: true)
        installDisplayLink()
        AppleLog.info(
            "Video",
            "Native recording decoder opening \(url.lastPathComponent) backend=\(decoderBackend)"
        )
    }

    private func installPreferredDecoder(hlsURL: URL) {
        tearDownPlayer()
        tearDownNativeDecoder()
        playerGeneration &+= 1
        recoveryPolicy.beginRecoveryAttempt(at: Self.now)
        state = .connecting
        nextRetryDelaySeconds = nil
        nativeFailureHandled = false
        nativeEndHandled = false
        ffmpegFrameSequence = 0
        ffmpegDJICameraTelemetrySequence = 0
        loggedNativeFrameFormat = false
        loggedNativeDisplayFailure = false

        if decoderSelectionPolicy.requiresHLSFallback {
            AppleLog.info("Video", "Using HLS fallback after native decoder incompatibility path=\(currentPath ?? "unknown")")
            installPlayer(url: hlsURL, beginsRecoveryAttempt: false)
            return
        }

        guard let rtspURL = Self.rtspURL(fromHLSURL: hlsURL),
              let session = rtspURL.absoluteString.withCString({ R2CFFmpegSessionCreate($0) })
        else {
            AppleLog.warning("Video", "FFmpeg/VideoToolbox decoder could not start; using HLS fallback")
            installPlayer(url: hlsURL, beginsRecoveryAttempt: false)
            return
        }
        ffmpegSession = session
        usesNativeVideoSurface = true
        decoderBackend = "FFmpeg \(String(cString: R2CFFmpegVersion())) / VideoToolbox"
        flushDisplayLayers(removeImage: true)
        installDisplayLink()
        AppleLog.info("Video", "Native newest-frame decoder opening \(rtspURL.absoluteString) backend=\(decoderBackend)")
    }

    private func installPlayer(url: URL, beginsRecoveryAttempt: Bool = true) {
        tearDownPlayer()
        playerGeneration &+= 1
        let generation = playerGeneration
        if beginsRecoveryAttempt {
            recoveryPolicy.beginRecoveryAttempt(at: Self.now)
        }
        state = .connecting
        nextRetryDelaySeconds = nil
        usesNativeVideoSurface = false
        decoderBackend = "AVPlayer HLS fallback"

        let attributes: [String: any Sendable] = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
        ]
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: attributes)
        let item = AVPlayerItem(url: url)
        item.preferredForwardBufferDuration = 0
        item.add(output)

        let player = AVPlayer(playerItem: item)
        player.automaticallyWaitsToMinimizeStalling = false
        self.videoOutput = output
        self.player = player

        itemStatusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor [weak self] in
                guard let self, generation == self.playerGeneration else { return }
                switch item.status {
                case .readyToPlay:
                    AppleLog.info("Video", "AVPlayer ready path=\(self.currentPath ?? "unknown")")
                    player.playImmediately(atRate: 1)
                case .failed:
                    self.handleRecoveryDecision(self.recoveryPolicy.playerFailed(
                        detail: item.error?.localizedDescription ?? "unknown failure"
                    ))
                case .unknown:
                    break
                @unknown default:
                    break
                }
            }
        }

        installDisplayLink()

        let stalled = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.playbackStalledNotification,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, generation == self.playerGeneration else { return }
                AppleLog.warning(
                    "Video",
                    "AVPlayer reported playback stalled path=\(self.currentPath ?? "unknown"); " +
                        "awaiting decoded-frame watchdog networkSnapshotId=\(AppleNetworkDiagnosticCenter.shared.currentSnapshotID)"
                )
            }
        }
        let failedToEnd = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.failedToPlayToEndTimeNotification,
            object: item,
            queue: .main
        ) { [weak self] notification in
            let failureDetail = (notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?
                .localizedDescription ?? "failed before live edge"
            Task { @MainActor [weak self] in
                guard let self, generation == self.playerGeneration else { return }
                self.handleRecoveryDecision(self.recoveryPolicy.playerFailed(
                    detail: failureDetail
                ))
            }
        }
        notificationObservers = [stalled, failedToEnd]
    }

    func stop() {
        retryTask?.cancel()
        retryTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        playerGeneration &+= 1
        tearDownPlayer()
        tearDownNativeDecoder()
        currentURL = nil
        currentPath = nil
        streamDesignator = "--"
        renderDelayMilliseconds = nil
        decoderDelayMilliseconds = nil
        lagEstimator.reset()
        sessionLagEstimator.reset()
        frameCount = 0
        dimensions = "--"
        sourceWidth = 0
        sourceHeight = 0
        sourceFrameRate = 0
        frameRateWindowStartedAt = nil
        frameRateWindowFrameCount = 0
        videoAspectRatio = 16.0 / 9.0
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyCount = 0
        anomalyBoxes = []
        anomalyHotOverlay = nil
        decodedFrameAgeSeconds = nil
        recoveryCount = 0
        decoderSelectionPolicy.reset()
        lastRecoveryReason = "None"
        nextRetryDelaySeconds = nil
        mediaPublisherStatus = "Unknown"
        latestGimbalPitchDegrees = nil
        latestCameraYawDegrees = nil
        latestStreamHeadingDegrees = nil
        latestDJICameraTelemetry = nil
        ffmpegDJICameraTelemetrySequence = 0
        latestPixelBuffer = nil
        latestFrameCapturedAt = nil
        usesNativeVideoSurface = false
        decoderBackend = "None"
        flushDisplayLayers(removeImage: true)
        anomalyProcessor.reset()
        state = .idle
    }

    func handleMediaServerEvent(_ event: MediaServerEvent) {
        let eventPath: String?
        let publisherAvailable: Bool?
        switch event {
        case let .streamStarted(path, _), let .streamPublisherHandoff(path, _):
            eventPath = path
            publisherAvailable = true
            if currentPath == nil || currentPath == path {
                sessionLagEstimator.publisherStarted(atMilliseconds: Self.nowMilliseconds)
            }
        case let .hlsStreamStarted(path):
            eventPath = path
            publisherAvailable = true
        case let .streamStopped(path, _):
            eventPath = path
            publisherAvailable = false
        case let .streamError(path, _, _):
            eventPath = path
            publisherAvailable = false
        default:
            return
        }
        guard currentURL != nil else { return }
        guard eventPath == currentPath, let publisherAvailable else { return }
        mediaPublisherStatus = publisherAvailable ? "Publishing" : "Unavailable"
        AppleLog.info("Video", "MediaMTX publisher path=\(eventPath ?? "unknown") status=\(mediaPublisherStatus)")
        if !publisherAvailable {
            retryTask?.cancel()
            retryTask = nil
            nextRetryDelaySeconds = nil
            // Keep the decoder and managed WebRTC session alive for the
            // recovery grace period, but never present an offline publisher's
            // last decoded image as though it were still live.
            latestPixelBuffer = nil
            latestFrameCapturedAt = nil
            flushDisplayLayers(removeImage: true)
        }
        handleRecoveryDecision(recoveryPolicy.setPublisherAvailable(publisherAvailable))
    }

    private func startWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                let now = Self.now
                decodedFrameAgeSeconds = recoveryPolicy.decodedFrameAge(at: now)
                if inspectNativeDecoderStatus() {
                    continue
                }
                handleRecoveryDecision(recoveryPolicy.evaluate(at: now))
            }
        }
    }

    private func handleRecoveryDecision(_ decision: LiveVideoRecoveryPolicy.Decision?) {
        guard let decision else { return }
        switch decision {
        case let .waitForPublisher(trigger):
            retryTask?.cancel()
            retryTask = nil
            nextRetryDelaySeconds = nil
            lastRecoveryReason = trigger.diagnosticDescription
            state = .waitingForPublisher(trigger.diagnosticDescription)
            logPlayerFailureContext(prefix: "Waiting for MediaMTX publisher", trigger: trigger)
        case let .reconnect(delay, trigger):
            recoveryCount += 1
            lastRecoveryReason = trigger.diagnosticDescription
            nextRetryDelaySeconds = delay
            state = .failed("\(trigger.diagnosticDescription); retry in \(Self.secondsLabel(delay))")
            logPlayerFailureContext(prefix: "Scheduling decoder recovery in \(Self.secondsLabel(delay))", trigger: trigger)
            scheduleReconnect(after: delay)
        }
    }

    private func scheduleReconnect(after delay: TimeInterval) {
        retryTask?.cancel()
        let generation = playerGeneration
        retryTask = Task { [weak self] in
            if delay > 0 {
                try? await Task.sleep(for: .seconds(delay))
            }
            guard !Task.isCancelled,
                  let self,
                  generation == self.playerGeneration,
                  let url = self.currentURL
            else { return }
            if url.isFileURL {
                self.installNativePlayback(url: url)
            } else {
                self.installPreferredDecoder(hlsURL: url)
            }
        }
    }

    private func installDisplayLink() {
        displayLink?.invalidate()
        let displayLink = CADisplayLink(target: self, selector: #selector(pullFrame))
        displayLink.preferredFrameRateRange = CAFrameRateRange(minimum: 10, maximum: 30, preferred: 30)
        displayLink.add(to: .main, forMode: .common)
        self.displayLink = displayLink
    }

    private func tearDownPlayer() {
        displayLink?.invalidate()
        displayLink = nil
        itemStatusObservation = nil
        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        notificationObservers.removeAll()
        player?.pause()
        player = nil
        videoOutput = nil
    }

    private func tearDownNativeDecoder() {
        if let ffmpegSession {
            R2CFFmpegSessionDestroy(ffmpegSession)
            self.ffmpegSession = nil
        }
        ffmpegFrameSequence = 0
        ffmpegDJICameraTelemetrySequence = 0
    }

    @objc private func pullFrame(_ displayLink: CADisplayLink) {
        if let ffmpegSession {
            var sequence: UInt64 = 0
            var presentationTimeMicroseconds: Int64 = 0
            guard let retainedPixelBuffer = R2CFFmpegSessionCopyLatestFrame(
                ffmpegSession,
                &sequence,
                &presentationTimeMicroseconds
            ) else { return }
            let pixelBuffer = retainedPixelBuffer.takeRetainedValue()
            guard sequence != ffmpegFrameSequence else { return }
            ffmpegFrameSequence = sequence
            var djiAzimuthDegrees = 0.0
            var djiTiltDegrees = 0.0
            var djiHorizontalFovDegrees = 0.0
            var djiVerticalFovDegrees = 0.0
            var djiTelemetrySequence: UInt64 = 0
            var djiAttitudeAngles = [Double](repeating: .nan, count: 9)
            var djiPositionValues = [Double](repeating: .nan, count: 7)
            var djiSourceTimestampMicroseconds: Int64 = 0
            let copiedDJITelemetry = djiAttitudeAngles.withUnsafeMutableBufferPointer { angles in
                djiPositionValues.withUnsafeMutableBufferPointer { position in
                    R2CFFmpegSessionCopyLatestDJICameraTelemetry(
                        ffmpegSession,
                        &djiAzimuthDegrees,
                        &djiTiltDegrees,
                        &djiHorizontalFovDegrees,
                        &djiVerticalFovDegrees,
                        angles.baseAddress,
                        Int32(angles.count),
                        position.baseAddress,
                        Int32(position.count),
                        &djiSourceTimestampMicroseconds,
                        &djiTelemetrySequence
                    )
                }
            }
            if copiedDJITelemetry,
               djiTelemetrySequence != ffmpegDJICameraTelemetrySequence {
                ffmpegDJICameraTelemetrySequence = djiTelemetrySequence
                if AppleSEIHexDiagnostics.enabled {
                    var payload = [UInt8](repeating: 0, count: 128)
                    var payloadSize: Int32 = 0
                    var northMillimeters: Int32 = 0
                    var eastMillimeters: Int32 = 0
                    var downMillimeters: Int32 = 0
                    var payloadTimestampMicroseconds: Int64 = 0
                    var payloadSequence: UInt64 = 0
                    let copiedPayload = payload.withUnsafeMutableBufferPointer { bytes in
                        R2CFFmpegSessionCopyLatestDJISEIPayload(
                            ffmpegSession,
                            bytes.baseAddress,
                            Int32(bytes.count),
                            &payloadSize,
                            &northMillimeters,
                            &eastMillimeters,
                            &downMillimeters,
                            &payloadTimestampMicroseconds,
                            &payloadSequence
                        )
                    }
                    if copiedPayload, payloadSize > 0, Int(payloadSize) <= payload.count {
                        let hex = payload.prefix(Int(payloadSize)).map { String(format: "%02x", $0) }.joined()
                        AppleLog.debug(
                            "DjiSeiHex",
                            "DJI_SEI_HEX path=\(currentPath ?? "unknown") sequence=\(payloadSequence) ptsUs=\(payloadTimestampMicroseconds) len=\(payloadSize) northMm=\(northMillimeters) eastMm=\(eastMillimeters) downMm=\(downMillimeters) payload=\(hex)"
                        )
                    }
                }
                latestDJICameraTelemetry = AppleDJICameraTelemetry(
                    rawAzimuthCandidateDegrees: RidHeading.normalized(djiAzimuthDegrees) ?? djiAzimuthDegrees,
                    cameraAzimuthDegrees: OperationalClueGeometry.djiControllerCameraAzimuthDegrees(
                        seiCameraAzimuthDegrees: djiAzimuthDegrees,
                        magneticDeclinationDegrees: AppleMagneticNorth.declinationDegrees
                    ),
                    courseDegrees: RidHeading.normalized(djiPositionValues[6]),
                    rawTiltDegrees: djiTiltDegrees,
                    tiltDegrees: OperationalClueGeometry.djiCalibratedTiltDegrees(
                        rawTiltDegrees: djiTiltDegrees
                    ) ?? min(90, max(-90, djiTiltDegrees)),
                    horizontalFovDegrees: djiHorizontalFovDegrees,
                    verticalFovDegrees: djiVerticalFovDegrees,
                    attitudeAnglesDegrees: djiAttitudeAngles,
                    latitudeDegrees: djiPositionValues[0].isFinite ? djiPositionValues[0] : nil,
                    longitudeDegrees: djiPositionValues[1].isFinite ? djiPositionValues[1] : nil,
                    altitudeMeters: nil,
                    relativeUpMeters: djiPositionValues[2].isFinite ? djiPositionValues[2] : nil,
                    referenceLatitudeDegrees: djiPositionValues[3].isFinite ? djiPositionValues[3] : nil,
                    referenceLongitudeDegrees: djiPositionValues[4].isFinite ? djiPositionValues[4] : nil,
                    referenceAltitudeMeters: djiPositionValues[5].isFinite ? djiPositionValues[5] : nil,
                    sourceTimestampMicroseconds: djiSourceTimestampMicroseconds > 0
                        ? djiSourceTimestampMicroseconds
                        : nil,
                    receivedAt: Date()
                )
            }
            var gimbalPitchDegrees = 0.0
            if R2CFFmpegSessionCopyLatestGimbalPitchDegrees(
                ffmpegSession,
                &gimbalPitchDegrees
            ) {
                let selected = OperationalClueGeometry.selectedGimbalAngleDegrees(
                    streamPitchDegrees: gimbalPitchDegrees
                )
                if latestGimbalPitchDegrees != selected {
                    latestGimbalPitchDegrees = selected
                    AppleLog.info(
                        "Video",
                        "Stream gimbal pitch updated path=\(currentPath ?? "unknown") degrees=\(String(format: "%.1f", selected))"
                    )
                }
            }
            var cameraYawDegrees = 0.0
            if R2CFFmpegSessionCopyLatestCameraYawDegrees(
                ffmpegSession,
                &cameraYawDegrees
            ), let normalized = RidHeading.normalized(cameraYawDegrees),
               latestCameraYawDegrees != normalized {
                latestCameraYawDegrees = normalized
                AppleLog.info(
                    "Video",
                    "Stream camera yaw updated path=\(currentPath ?? "unknown") degrees=\(String(format: "%.1f", normalized))"
                )
            }
            var streamHeadingDegrees = 0.0
            if R2CFFmpegSessionCopyLatestHeadingDegrees(
                ffmpegSession,
                &streamHeadingDegrees
            ), let normalized = RidHeading.normalized(streamHeadingDegrees),
               latestStreamHeadingDegrees != normalized {
                latestStreamHeadingDegrees = normalized
                AppleLog.info(
                    "Video",
                    "Stream heading updated path=\(currentPath ?? "unknown") degrees=\(String(format: "%.1f", normalized))"
                )
            }
            let itemTime = presentationTimeMicroseconds > Int64.min / 2
                ? CMTime(value: presentationTimeMicroseconds, timescale: 1_000_000)
                : CMClockGetTime(CMClockGetHostTimeClock())
            consumeDecodedFrame(
                pixelBuffer,
                itemTime: itemTime,
                sourceTimestampMicroseconds: presentationTimeMicroseconds > 0 ? presentationTimeMicroseconds : nil,
                renderNatively: true
            )
            return
        }
        guard let output = videoOutput else { return }
        let hostTime = displayLink.timestamp + displayLink.duration
        let itemTime = output.itemTime(forHostTime: hostTime)
        guard output.hasNewPixelBuffer(forItemTime: itemTime),
              let pixelBuffer = output.copyPixelBuffer(forItemTime: itemTime, itemTimeForDisplay: nil)
        else { return }

        let itemSeconds = CMTimeGetSeconds(itemTime)
        consumeDecodedFrame(
            pixelBuffer,
            itemTime: itemTime,
            sourceTimestampMicroseconds: itemSeconds.isFinite && itemSeconds > 0
                ? Int64(itemSeconds * 1_000_000)
                : nil,
            renderNatively: false
        )
    }

    private func consumeDecodedFrame(
        _ pixelBuffer: CVPixelBuffer,
        itemTime: CMTime,
        sourceTimestampMicroseconds: Int64?,
        renderNatively: Bool
    ) {
        frameCount += 1
        recoveryPolicy.recordDecodedFrame(at: Self.now)
        decodedFrameAgeSeconds = 0
        nextRetryDelaySeconds = nil
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        dimensions = "\(width) x \(height)"
        sourceWidth = width
        sourceHeight = height
        recordSourceFrameForRateMeasurement()
        if height > 0 {
            videoAspectRatio = Double(width) / Double(height)
        }
        latestPixelBuffer = pixelBuffer
        latestFrameCapturedAt = Date()
        managedVideoFrameConsumer?.consumeDecodedVideoFrame(
            pixelBuffer,
            timestampNanoseconds: Int64(CACurrentMediaTime() * 1_000_000_000)
        )
        let decoderDelay = lagEstimator.observe(
            sourceTimestampMicroseconds: sourceTimestampMicroseconds,
            observedAtMilliseconds: Self.nowMilliseconds
        )
        let sessionDelay = sessionLagEstimator.observe(
            sourceTimestampMicroseconds: sourceTimestampMicroseconds,
            observedAtMilliseconds: Self.nowMilliseconds
        )
        if let decoderDelay {
            decoderDelayMilliseconds = LiveVideoLagEstimator.quantize(milliseconds: decoderDelay)
        }
        if let effectiveDelay = [decoderDelay, sessionDelay].compactMap({ $0 }).max() {
            renderDelayMilliseconds = LiveVideoLagEstimator.quantize(milliseconds: effectiveDelay)
        }
        if renderNatively {
            enqueueForImmediateDisplay(pixelBuffer)
        }
        submitForAnomalyAnalysis(pixelBuffer: pixelBuffer, itemTime: itemTime)
        state = .streaming
    }

    private func recordSourceFrameForRateMeasurement() {
        let now = CACurrentMediaTime()
        guard let startedAt = frameRateWindowStartedAt else {
            frameRateWindowStartedAt = now
            frameRateWindowFrameCount = 1
            return
        }
        frameRateWindowFrameCount += 1
        let elapsed = now - startedAt
        guard elapsed >= 2 else { return }
        sourceFrameRate = Double(frameRateWindowFrameCount - 1) / elapsed
        frameRateWindowStartedAt = now
        frameRateWindowFrameCount = 1
    }

    private func enqueueForImmediateDisplay(_ pixelBuffer: CVPixelBuffer) {
        if !loggedNativeFrameFormat {
            loggedNativeFrameFormat = true
            let pixelFormat = CVPixelBufferGetPixelFormatType(pixelBuffer)
            let format = String(format: "%c%c%c%c",
                (pixelFormat >> 24) & 0xff,
                (pixelFormat >> 16) & 0xff,
                (pixelFormat >> 8) & 0xff,
                pixelFormat & 0xff
            )
            AppleLog.info(
                "Video",
                "Native first frame \(CVPixelBufferGetWidth(pixelBuffer))x\(CVPixelBufferGetHeight(pixelBuffer)) format='\(format)'"
            )
        }
        var formatDescription: CMVideoFormatDescription?
        let formatStatus = CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDescription
        )
        guard formatStatus == noErr, let formatDescription else {
            logNativeDisplayFailureOnce("Could not create video format description status=\(formatStatus)")
            return
        }
        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
            decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        let sampleStatus = CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: formatDescription,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else {
            logNativeDisplayFailureOnce("Could not create display sample status=\(sampleStatus)")
            return
        }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(
            sampleBuffer,
            createIfNecessary: true
        ), CFArrayGetCount(attachments) > 0 {
            let attachment = unsafeBitCast(
                CFArrayGetValueAtIndex(attachments, 0),
                to: CFMutableDictionary.self
            )
            CFDictionarySetValue(
                attachment,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        } else {
            logNativeDisplayFailureOnce("Could not attach display-immediately flag")
            return
        }
        displayLayers = displayLayers.filter { $0.value.value != nil }
        for entry in displayLayers.values {
            guard let displayLayer = entry.value else { continue }
            if displayLayer.status == .failed {
                displayLayer.flush()
            }
            // This is a newest-frame display path. If a particular surface has
            // not consumed its preceding sample, drop this frame for that
            // surface instead of growing a queue.
            guard displayLayer.isReadyForMoreMediaData else { continue }
            displayLayer.enqueue(sampleBuffer)
            if displayLayer.status == .failed {
                logNativeDisplayFailureOnce(
                    "Display layer rejected native frame: \(displayLayer.error?.localizedDescription ?? "unknown error")"
                )
            }
        }
    }

    func registerDisplayLayer(_ layer: AVSampleBufferDisplayLayer) {
        displayLayers[ObjectIdentifier(layer)] = WeakVideoDisplayLayer(layer)
        layer.flushAndRemoveImage()
        AppleLog.info("Video", "Attached native video surface count=\(displayLayers.count)")
    }

    func unregisterDisplayLayer(_ layer: AVSampleBufferDisplayLayer) {
        displayLayers.removeValue(forKey: ObjectIdentifier(layer))
        AppleLog.info("Video", "Detached native video surface count=\(displayLayers.count)")
    }

    private func flushDisplayLayers(removeImage: Bool) {
        displayLayers = displayLayers.filter { $0.value.value != nil }
        for entry in displayLayers.values {
            if removeImage { entry.value?.flushAndRemoveImage() }
            else { entry.value?.flush() }
        }
    }

    private func logNativeDisplayFailureOnce(_ message: String) {
        guard !loggedNativeDisplayFailure else { return }
        loggedNativeDisplayFailure = true
        AppleLog.warning("Video", message)
    }

    private func inspectNativeDecoderStatus() -> Bool {
        guard let ffmpegSession else { return false }
        var detail = [CChar](repeating: 0, count: 256)
        let status = R2CFFmpegSessionGetStatus(ffmpegSession, &detail, Int32(detail.count))
        if status == R2C_FFMPEG_STATUS_ENDED {
            if !nativeEndHandled {
                nativeEndHandled = true
                nextRetryDelaySeconds = nil
                state = .idle
                AppleLog.info("Video", "Native recording playback completed path=\(currentPath ?? "unknown")")
            }
            return true
        }
        guard status == R2C_FFMPEG_STATUS_FAILED, !nativeFailureHandled else { return false }
        nativeFailureHandled = true
        let message = String(decoding: detail.prefix { $0 != 0 }.map(UInt8.init(bitPattern:)), as: UTF8.self)
        let nativeDecodedFrames = R2CFFmpegSessionDecodedFrameCount(ffmpegSession)
        decoderSelectionPolicy.nativeDecoderFailed(decodedFramesThisAttempt: nativeDecodedFrames)
        AppleLog.warning(
            "Video",
            "Native decoder failed path=\(currentPath ?? "unknown") detail=\(message) " +
                "networkSnapshotId=\(AppleNetworkDiagnosticCenter.shared.currentSnapshotID)"
        )
        if decoderSelectionPolicy.requiresHLSFallback, let currentURL {
            tearDownNativeDecoder()
            installPlayer(url: currentURL, beginsRecoveryAttempt: false)
        } else {
            handleRecoveryDecision(recoveryPolicy.playerFailed(detail: message))
        }
        return true
    }

    func captureSnapshot(
        zoomScale: Double = 1,
        normalizedPan: CGPoint = .zero
    ) async throws -> AppleVideoSnapshot {
        guard let latestPixelBuffer, let capturedAt = latestFrameCapturedAt else {
            throw AppleVideoSnapshotError.noDecodedFrame
        }
        let transfer = PixelBufferTransfer(value: latestPixelBuffer)
        let width = CVPixelBufferGetWidth(latestPixelBuffer)
        let height = CVPixelBufferGetHeight(latestPixelBuffer)
        let viewport = OperationalVideoViewport(
            scale: zoomScale,
            normalizedPanX: normalizedPan.x,
            normalizedPanY: normalizedPan.y
        )
        let data = try await Task.detached(priority: .userInitiated) {
            let context = CIContext(options: [.cacheIntermediates: false])
            let extent = CGRect(x: 0, y: 0, width: width, height: height)
            var image = CIImage(cvPixelBuffer: transfer.value)
            if viewport.needsTransform {
                let transform = CGAffineTransform(
                    a: viewport.scale,
                    b: 0,
                    c: 0,
                    d: viewport.scale,
                    tx: viewport.translationX(width: Double(width)),
                    ty: viewport.translationY(height: Double(height))
                )
                let background = CIImage(color: .black).cropped(to: extent)
                image = image.transformed(by: transform).composited(over: background).cropped(to: extent)
            }
            guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
                  let jpeg = context.jpegRepresentation(
                    of: image,
                    colorSpace: colorSpace,
                    options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.85]
                  )
            else { throw AppleVideoSnapshotError.conversionFailed }
            return jpeg
        }.value
        AppleLog.info(
            "Video",
            "Snapshot captured size=\(width)x\(height) bytes=\(data.count) zoom=\(String(format: "%.2f", viewport.scale)) pan=\(String(format: "%.3f,%.3f", viewport.normalizedPanX, viewport.normalizedPanY))"
        )
        return AppleVideoSnapshot(jpegData: data, capturedAt: capturedAt, width: width, height: height)
    }

    private func logPlayerFailureContext(prefix: String, trigger: LiveVideoRecoveryPolicy.Trigger) {
        let item = player?.currentItem
        let errorEvent = item?.errorLog()?.events.last
        let accessEvent = item?.accessLog()?.events.last
        let errorDetail = errorEvent.map {
            "errorDomain=\($0.errorDomain) errorCode=\($0.errorStatusCode) comment=\($0.errorComment ?? "none") uri=\($0.uri ?? "none")"
        } ?? "errorLog=none"
        let accessDetail = accessEvent.map {
            "observedBitrate=\(Int($0.observedBitrate)) indicatedBitrate=\(Int($0.indicatedBitrate)) stalls=\($0.numberOfStalls) server=\($0.serverAddress ?? "unknown")"
        } ?? "accessLog=none"
        AppleLog.warning(
            "Video",
            "\(prefix) reason=\(trigger.diagnosticDescription) frames=\(frameCount) frameAge=\(decodedFrameAgeSeconds.map { String(format: "%.1fs", $0) } ?? "none") media=\(mediaPublisherStatus) \(errorDetail) \(accessDetail) " +
                "networkSnapshotId=\(AppleNetworkDiagnosticCenter.shared.currentSnapshotID)"
        )
    }

    private static var now: TimeInterval { ProcessInfo.processInfo.systemUptime }
    private static var nowMilliseconds: Int64 { Int64(now * 1_000) }

    private static func secondsLabel(_ seconds: TimeInterval) -> String {
        String(format: "%.0fs", seconds)
    }

    private static func streamPath(from url: URL) -> String? {
        let components = url.pathComponents.filter { $0 != "/" }
        guard !components.isEmpty else { return nil }
        return components.last == "index.m3u8"
            ? components.dropLast().joined(separator: "/")
            : components.joined(separator: "/")
    }

    private static func rtspURL(fromHLSURL url: URL) -> URL? {
        guard let path = streamPath(from: url), !path.isEmpty else { return nil }
        return MediaStreamEndpoint(designator: path).loopbackRtspURL
    }

    private static func designator(from path: String?) -> String {
        guard let path, !path.isEmpty else { return "--" }
        let segments = path.split(separator: "/").map(String.init)
        let controllerProfiles = ["RC2", "RCPRO2", "RCPRO1", "ENTERPRISE2", "AUTEL"]
        if let first = segments.first, controllerProfiles.contains(first.uppercased()) {
            let designator = segments.dropFirst().joined(separator: "/")
            return designator.isEmpty ? path : designator
        }
        return path
    }

    private func submitForAnomalyAnalysis(pixelBuffer: CVPixelBuffer, itemTime: CMTime) {
        guard anomalyMode != .off else {
            anomalyCount = 0
            anomalyBoxes = []
            anomalyHotOverlay = nil
            anomalyThermallySuspended = false
            return
        }
        switch ProcessInfo.processInfo.thermalState {
        case .serious, .critical:
            if !anomalyThermallySuspended {
                anomalyThermallySuspended = true
                anomalyCount = 0
                anomalyBoxes = []
                anomalyHotOverlay = nil
                AppleLog.warning("Anomaly", "Analysis suspended due to \(ProcessInfo.processInfo.thermalState) thermal state")
            }
            return
        case .nominal, .fair:
            if anomalyThermallySuspended {
                anomalyThermallySuspended = false
                AppleLog.info("Anomaly", "Analysis resumed after thermal state recovered")
            }
        @unknown default:
            return
        }
        let timestampSeconds = CMTimeGetSeconds(itemTime)
        let accepted = anomalyProcessor.submit(
            pixelBuffer: pixelBuffer,
            timestampMicroseconds: Int64(timestampSeconds * 1_000_000)
        ) { [weak self] result in
            guard let self else { return }
            self.analyzedFrameCount += 1
            self.anomalyCount = result.annotationCount
            self.anomalyBoxes = result.boxes
            self.anomalyHotOverlay = result.hotOverlay
            if self.anomalyConfiguration.troubleshootingDebug {
                let summary = result.boxes.map {
                    String(
                        format: "algo=%d l=%.3f t=%.3f r=%.3f b=%.3f weight=%.2f rgb=%d,%d,%d crosshair=%d",
                        $0.algorithm,
                        $0.left,
                        $0.top,
                        $0.right,
                        $0.bottom,
                        $0.weight,
                        $0.red,
                        $0.green,
                        $0.blue,
                        $0.drawsCrosshair ? 1 : 0
                    )
                }.joined(separator: " | ")
                AppleLog.info(
                    "Anomaly",
                    "frame result designator=\(self.streamDesignator) frame=\(result.frameOrdinal) raw=\(result.rawBoxCount) stable=\(result.stableBoxCount) annotations=\(result.annotationCount) \(summary)"
                )
            }
        }
        if !accepted {
            droppedAnalysisFrameCount += 1
        }
    }
}
