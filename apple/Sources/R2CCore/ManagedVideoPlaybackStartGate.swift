/// A finite recording starts once per media attempt, never during negotiation
/// or after cancellation. Connection recovery must not rewind that recording.
public struct ManagedVideoPlaybackStartGate: Sendable {
    private var pending = true

    public init() {}

    public mutating func shouldStart(connected: Bool) -> Bool {
        guard pending, connected else { return false }
        pending = false
        return true
    }

    public mutating func cancel() { pending = false }
}
