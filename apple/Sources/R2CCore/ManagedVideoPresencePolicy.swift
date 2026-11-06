import Foundation

public enum ManagedVideoPresencePolicy {
    public static let maximumDecodedFrameAge: TimeInterval = 6

    public static func hasRecentDecodedFrame(
        frameCount: Int,
        decodedFrameAge: TimeInterval?,
        maximumAge: TimeInterval = maximumDecodedFrameAge
    ) -> Bool {
        guard frameCount > 0, let decodedFrameAge else { return false }
        return decodedFrameAge >= 0 && decodedFrameAge <= maximumAge
    }

    public static func unavailableApprovedSessionIDs(
        approvedSessionIDs: Set<String>,
        liveSessionIDs: Set<String>,
        recordingSessionIDs: Set<String>
    ) -> Set<String> {
        approvedSessionIDs.subtracting(liveSessionIDs.union(recordingSessionIDs))
    }

    /// Wire `thumbnailJpegBase64` only when this revision has JPEG bytes and was not
    /// already delivered to the tracker. Presence still carries `thumbnailRevision`.
    public static func shouldIncludeThumbnailJpeg(
        revision: String,
        hasJpegBytes: Bool,
        lastSentRevision: String?
    ) -> Bool {
        guard hasJpegBytes else { return false }
        let trimmed = revision.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return trimmed != lastSentRevision
    }

    /// Unchanged advertisements refresh the tracker on this cadence, not every inventory tick.
    public static let unchangedAdvertiseInterval: TimeInterval = 5
    /// Distinct advertisements (new thumbnail revision, stream set) still coalesce.
    public static let changedAdvertiseMinInterval: TimeInterval = 0.35

    public struct StreamIdentity: Equatable, Sendable {
        public var sessionId: String
        public var droneDesignator: String
        public var mediaKind: String
        public var sourceWidth: Int
        public var sourceHeight: Int
        public var sourceFps: Double
        public var thumbnailRevision: String
        public var durationMs: Int64
        public var recordedAt: String?

        public init(
            sessionId: String,
            droneDesignator: String,
            mediaKind: String,
            sourceWidth: Int,
            sourceHeight: Int,
            sourceFps: Double,
            thumbnailRevision: String,
            durationMs: Int64,
            recordedAt: String?
        ) {
            self.sessionId = sessionId
            self.droneDesignator = droneDesignator
            self.mediaKind = mediaKind
            self.sourceWidth = sourceWidth
            self.sourceHeight = sourceHeight
            self.sourceFps = sourceFps
            self.thumbnailRevision = thumbnailRevision
            self.durationMs = durationMs
            self.recordedAt = recordedAt
        }
    }

    public struct AdvertiseDecision: Equatable, Sendable {
        public var startSend: Bool
        public var foldIntoInFlight: Bool

        public init(startSend: Bool, foldIntoInFlight: Bool) {
            self.startSend = startSend
            self.foldIntoInFlight = foldIntoInFlight
        }
    }

    /// Stable wire identity. Frame-rate noise inside one fps does not change the fingerprint.
    public static func fingerprint(
        remoteControlEnabled: Bool,
        incidentName: String,
        streams: [StreamIdentity]
    ) -> String {
        let body = streams.map { stream in
            let fps = Int(stream.sourceFps.rounded())
            let duration = stream.durationMs / 1_000
            return [
                stream.sessionId,
                stream.droneDesignator,
                stream.mediaKind,
                String(stream.sourceWidth),
                String(stream.sourceHeight),
                String(fps),
                stream.thumbnailRevision,
                String(duration),
                stream.recordedAt ?? "",
            ].joined(separator: "|")
        }.joined(separator: ";")
        return "\(remoteControlEnabled ? 1 : 0)|\(incidentName)|\(body)"
    }

    /// At most one presence encode/send is in flight. Unchanged payloads wait out the interval.
    /// A `force` follow-up publishes the latest snapshot after the in-flight send finishes.
    public static func advertiseDecision(
        fingerprint: String,
        lastFingerprint: String,
        now: TimeInterval,
        lastSentAt: TimeInterval,
        inFlight: Bool,
        force: Bool = false
    ) -> AdvertiseDecision {
        let changed = fingerprint != lastFingerprint
        let elapsed = now - lastSentAt
        let first = lastFingerprint.isEmpty || lastSentAt <= 0
        let due = first || force || (changed
            ? elapsed >= changedAdvertiseMinInterval
            : elapsed >= unchangedAdvertiseInterval)
        guard due else {
            return AdvertiseDecision(startSend: false, foldIntoInFlight: false)
        }
        if inFlight {
            return AdvertiseDecision(startSend: false, foldIntoInFlight: true)
        }
        return AdvertiseDecision(startSend: true, foldIntoInFlight: false)
    }
}
