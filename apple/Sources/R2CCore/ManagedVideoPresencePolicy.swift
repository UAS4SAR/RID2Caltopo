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
}
