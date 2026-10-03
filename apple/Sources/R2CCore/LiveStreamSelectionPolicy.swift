import Foundation

/// Keeps the UI attached to a real RTMP publisher instead of the empty
/// placeholder used before the first stream arrives.
public enum LiveStreamSelectionPolicy {
    public static let placeholderID = "demo"

    public struct TileFocusPresentation: Equatable, Sendable {
        public let effectiveFocused: Bool
        public let showFocusBorder: Bool
    }

    /// A sole tile (including the waiting placeholder) is implicitly focused.
    /// A border is only useful when distinguishing a selection among tiles.
    public static func tileFocusPresentation(
        displayedTileCount: Int,
        explicitlyFocused: Bool
    ) -> TileFocusPresentation {
        let singleTile = displayedTileCount == 1
        return TileFocusPresentation(
            effectiveFocused: singleTile || explicitlyFocused,
            showFocusBorder: !singleTile && explicitlyFocused
        )
    }

    public static func focusAfterPublisherStarted(
        currentFocus: String,
        publisherPath: String,
        activePublisherPaths: Set<String>
    ) -> String {
        let normalized = normalize(publisherPath)
        guard !normalized.isEmpty else { return currentFocus }
        if currentFocus == placeholderID || !activePublisherPaths.contains(normalize(currentFocus)) {
            return normalized
        }
        return currentFocus
    }

    public static func shouldAcceptHLSMuxer(
        path: String,
        activePublisherPaths: Set<String>
    ) -> Bool {
        let normalized = normalize(path)
        return normalized != placeholderID && activePublisherPaths.contains(normalized)
    }

    public static func playbackPath(
        focusedID: String,
        activePublisherPaths: Set<String>
    ) -> String? {
        let normalized = normalize(focusedID)
        guard normalized != placeholderID, activePublisherPaths.contains(normalized) else { return nil }
        return normalized
    }

    private static func normalize(_ value: String) -> String {
        value.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
    }
}

/// Keeps the reusable primary decoder, and therefore the stream's sole operational telemetry
/// timeline, bound to the publisher that owns it.
/// A terminal publisher removal must release that decoder before a different
/// stream can inherit it, while a same-path republish can use normal recovery.
public enum LiveStreamDecoderLifecyclePolicy {
    public static func shouldResetAfterPublisherStopped(
        sessionPath: String,
        decoderPath: String?
    ) -> Bool {
        guard let decoderPath else { return false }
        return normalize(sessionPath) == normalize(decoderPath)
    }

    public static func shouldStartDecoder(
        publisherPath: String,
        decoderPath: String?,
        decoderIsIdle: Bool
    ) -> Bool {
        if decoderIsIdle { return true }
        guard let decoderPath else { return true }
        return normalize(publisherPath) != normalize(decoderPath)
    }

    private static func normalize(_ value: String) -> String {
        value.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
    }
}
