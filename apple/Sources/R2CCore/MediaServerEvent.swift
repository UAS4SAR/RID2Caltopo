import Foundation

public enum MediaServerEvent: Equatable, Sendable {
    case serverStarted(version: String)
    case streamConnecting(path: String)
    case streamStarted(path: String, publisherConnectionID: String?)
    case streamPublisherHandoff(path: String, publisherConnectionID: String?)
    case streamStopped(path: String, publisherConnectionID: String?)
    case streamError(path: String?, publisherConnectionID: String?, detail: String)
    case rtmpSessionClosed(path: String, publisherConnectionID: String?, reason: String?)
    case rtmpPublishDiagnostic(
        path: String,
        publisherConnectionID: String?,
        phase: String,
        elapsedMilliseconds: Int?,
        detail: String?
    )
    case hlsStreamStarted(path: String)
    case recordFileCompleted(path: String, filePath: String, durationMilliseconds: Int)
    /// MediaMTX's recorder began writing this path ("[recorder] recording N track(s)").
    case recorderStarted(path: String)
    /// The recorder stopped ("recording stopped", or "no supported tracks found").
    case recorderStopped(path: String)
}

/// Lifecycle seam implemented by the future Go mobile bridge.
public protocol MediaServerController: Sendable {
    var events: AsyncStream<MediaServerEvent> { get }

    func start(configuration: Data) async throws
    func stop() async
}
