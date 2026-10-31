import Foundation

/// Keeps a decoder choice stable after the native path proves incompatible
/// with the current publisher. The failure count is scoped to the current
/// native decoder instance, not to frames decoded earlier by another backend.
public struct LiveVideoDecoderSelectionPolicy: Sendable {
    public private(set) var requiresHLSFallback = false
    /// True when the native open failed because the MediaMTX path has no publisher.
    /// Callers should wait for the publisher instead of hammering on-demand HLS.
    public private(set) var publisherPathAbsent = false

    public init() {}

    public mutating func reset() {
        requiresHLSFallback = false
        publisherPathAbsent = false
    }

    public mutating func nativeDecoderFailed(
        decodedFramesThisAttempt: UInt64,
        detail: String = ""
    ) {
        publisherPathAbsent = false
        if decodedFramesThisAttempt == 0 {
            if Self.isPublisherPathAbsent(detail: detail) {
                publisherPathAbsent = true
                return
            }
            requiresHLSFallback = true
        }
    }

    public static func isPublisherPathAbsent(detail: String) -> Bool {
        let lowered = detail.lowercased()
        return lowered.contains("404")
            || lowered.contains("no stream is available")
            || lowered.contains("stream not found")
            || (lowered.contains("rtsp open failed") && lowered.contains("not found"))
    }
}

/// Testable recovery state for one live video decoder.
///
/// AVPlayer can remain nominally ready while a live HLS presentation stops
/// producing frames. This policy therefore uses decoded-frame progress as the
/// authoritative health signal and uses MediaMTX publisher state to avoid
/// repeatedly reopening a decoder when no upstream exists.
public struct LiveVideoRecoveryPolicy: Sendable {
    public struct Configuration: Equatable, Sendable {
        public let connectionTimeout: TimeInterval
        public let frameStallTimeout: TimeInterval
        public let retryDelays: [TimeInterval]

        public init(
            connectionTimeout: TimeInterval = 12,
            frameStallTimeout: TimeInterval = 5,
            retryDelays: [TimeInterval] = [1, 2, 4, 8]
        ) {
            precondition(connectionTimeout > 0)
            precondition(frameStallTimeout > 0)
            precondition(!retryDelays.isEmpty && retryDelays.allSatisfy { $0 >= 0 })
            self.connectionTimeout = connectionTimeout
            self.frameStallTimeout = frameStallTimeout
            self.retryDelays = retryDelays
        }
    }

    public enum PublisherAvailability: Equatable, Sendable {
        case unknown
        case publishing
        case unavailable
    }

    public enum Trigger: Equatable, Sendable {
        case connectionTimedOut
        case decodedFramesStalled
        case playerFailed(String)
        case publisherReturned

        public var diagnosticDescription: String {
            switch self {
            case .connectionTimedOut: "no decoded frame before connection timeout"
            case .decodedFramesStalled: "decoded frame progress stalled"
            case let .playerFailed(detail): "AVPlayer failed: \(detail)"
            case .publisherReturned: "MediaMTX publisher returned"
            }
        }
    }

    public enum Decision: Equatable, Sendable {
        case reconnect(after: TimeInterval, trigger: Trigger)
        case waitForPublisher(trigger: Trigger)
    }

    public private(set) var publisherAvailability: PublisherAvailability = .unknown
    public private(set) var consecutiveRecoveryCount = 0

    private let configuration: Configuration
    private var attemptStartedAt: TimeInterval?
    private var lastFrameAt: TimeInterval?
    private var recoveryPending = false
    private var pendingTrigger: Trigger?

    public init(configuration: Configuration = Configuration()) {
        self.configuration = configuration
    }

    /// Starts a user-requested playback session and clears prior backoff.
    public mutating func reset(at now: TimeInterval) {
        attemptStartedAt = now
        lastFrameAt = nil
        recoveryPending = false
        pendingTrigger = nil
        consecutiveRecoveryCount = 0
        publisherAvailability = .unknown
    }

    /// Records installation of a replacement player while preserving backoff.
    public mutating func beginRecoveryAttempt(at now: TimeInterval) {
        attemptStartedAt = now
        lastFrameAt = nil
        recoveryPending = false
        pendingTrigger = nil
    }

    /// A decoded frame proves the entire MediaMTX -> HLS -> AVPlayer path.
    public mutating func recordDecodedFrame(at now: TimeInterval) {
        lastFrameAt = now
        recoveryPending = false
        pendingTrigger = nil
        consecutiveRecoveryCount = 0
    }

    public func decodedFrameAge(at now: TimeInterval) -> TimeInterval? {
        lastFrameAt.map { max(0, now - $0) }
    }

    public mutating func evaluate(at now: TimeInterval) -> Decision? {
        guard !recoveryPending, let attemptStartedAt else { return nil }
        if let lastFrameAt {
            guard now - lastFrameAt >= configuration.frameStallTimeout else { return nil }
            return recoveryDecision(for: .decodedFramesStalled)
        }
        guard now - attemptStartedAt >= configuration.connectionTimeout else { return nil }
        return recoveryDecision(for: .connectionTimedOut)
    }

    public mutating func playerFailed(detail: String) -> Decision? {
        guard !recoveryPending else { return nil }
        return recoveryDecision(for: .playerFailed(detail))
    }

    /// Returns an immediate retry only when a decoder was explicitly waiting
    /// for an absent publisher. Merely learning that a publisher exists does
    /// not disturb healthy playback.
    public mutating func setPublisherAvailable(_ available: Bool) -> Decision? {
        let wasWaiting = publisherAvailability == .unavailable && recoveryPending
        publisherAvailability = available ? .publishing : .unavailable
        if available, wasWaiting {
            return .reconnect(after: 0, trigger: .publisherReturned)
        }
        if !available, recoveryPending, let pendingTrigger {
            return .waitForPublisher(trigger: pendingTrigger)
        }
        return nil
    }

    private mutating func recoveryDecision(for trigger: Trigger) -> Decision {
        recoveryPending = true
        pendingTrigger = trigger
        guard publisherAvailability != .unavailable else {
            return .waitForPublisher(trigger: trigger)
        }
        let delayIndex = min(consecutiveRecoveryCount, configuration.retryDelays.count - 1)
        let delay = configuration.retryDelays[delayIndex]
        consecutiveRecoveryCount += 1
        return .reconnect(after: delay, trigger: trigger)
    }
}
