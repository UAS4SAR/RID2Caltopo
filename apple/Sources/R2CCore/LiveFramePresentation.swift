import Foundation

/// Live pixels are enqueued on the display queue. The UI actor never gates presentation.
public struct LiveFramePresentationPlan: Equatable, Sendable {
    public var enqueueOnDisplayQueue: Bool
    public var coalesceTelemetryOnMain: Bool

    public init(enqueueOnDisplayQueue: Bool, coalesceTelemetryOnMain: Bool) {
        self.enqueueOnDisplayQueue = enqueueOnDisplayQueue
        self.coalesceTelemetryOnMain = coalesceTelemetryOnMain
    }
}

public enum LiveFramePresentation {
    /// `mainActorBlocked` is intentionally absent: a wedged UI actor must not drop paced frames.
    public static func plan(hasFrame: Bool, renderingSuspended: Bool) -> LiveFramePresentationPlan {
        LiveFramePresentationPlan(
            enqueueOnDisplayQueue: hasFrame && !renderingSuspended,
            coalesceTelemetryOnMain: hasFrame
        )
    }

    public static func presentedFrameCount(pacedFrames: Int, mainActorBlocked: Bool) -> Int {
        _ = mainActorBlocked
        return max(0, pacedFrames)
    }
}

/// Compact RTMP publisher timing already emitted by MediaMTX. No coordinates.
public struct LiveIngressTimingSnapshot: Equatable, Sendable {
    public var path: String
    public var socketWaitMs: Int
    public var afterMediaMs: Int
    public var maxSocketWaitMs: Int
    public var maxAfterMediaMs: Int
    public var maxMediaGapMs: Int

    public init(
        path: String,
        socketWaitMs: Int,
        afterMediaMs: Int,
        maxSocketWaitMs: Int,
        maxAfterMediaMs: Int,
        maxMediaGapMs: Int
    ) {
        self.path = path
        self.socketWaitMs = socketWaitMs
        self.afterMediaMs = afterMediaMs
        self.maxSocketWaitMs = maxSocketWaitMs
        self.maxAfterMediaMs = maxAfterMediaMs
        self.maxMediaGapMs = maxMediaGapMs
    }
}

public enum LiveIngressTimingLog {
    public static func parse(_ line: String) -> LiveIngressTimingSnapshot? {
        guard let marker = line.range(of: "RTMP timing ") else { return nil }
        var values: [String: String] = [:]
        for token in line[marker.upperBound...].split(separator: " ") {
            let parts = token.split(separator: "=", maxSplits: 1)
            guard parts.count == 2 else { continue }
            values[String(parts[0])] = String(parts[1])
        }
        guard let path = values["path"], !path.isEmpty,
              let socketWait = values["socketWaitMs"].flatMap(Int.init),
              let afterMedia = values["afterMediaMs"].flatMap(Int.init)
        else { return nil }
        return LiveIngressTimingSnapshot(
            path: path,
            socketWaitMs: socketWait,
            afterMediaMs: afterMedia,
            maxSocketWaitMs: values["maxSocketWaitMs"].flatMap(Int.init) ?? socketWait,
            maxAfterMediaMs: values["maxAfterMediaMs"].flatMap(Int.init) ?? afterMedia,
            maxMediaGapMs: values["maxMediaGapMs"].flatMap(Int.init) ?? 0
        )
    }
}

public final class LiveIngressTimingStore: @unchecked Sendable {
    public static let shared = LiveIngressTimingStore()
    private let lock = NSLock()
    private var latestByPath: [String: LiveIngressTimingSnapshot] = [:]

    public func record(line: String) {
        guard let sample = LiveIngressTimingLog.parse(line) else { return }
        lock.lock()
        latestByPath[sample.path] = sample
        if latestByPath.count > 8, let extra = latestByPath.keys.first(where: { $0 != sample.path }) {
            latestByPath.removeValue(forKey: extra)
        }
        lock.unlock()
    }

    public func latest(path: String?) -> LiveIngressTimingSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        guard let path else { return nil }
        return latestByPath[path]
    }

    public func resetForTest() {
        lock.lock()
        latestByPath.removeAll()
        lock.unlock()
    }
}
