import Foundation

/// The Desk Pod's pairing window, tracked from DESK_PAIR frames by arrival order. Same
/// as Android's BtrxDeskWindow.kt. A tap that lands while the link is being made arrives
/// in the connect backlog and still counts; the window is open from the latest open=1
/// until a later open=0 or `seconds_left` (default 60) after the open frame arrived.
public struct BtrxDeskWindow: Sendable {
    public enum CloseReason: Sendable { case none, closedByDeskPod, expired }

    public static let defaultSeconds = 60

    public private(set) var openedAtMs: Int64 = 0
    public private(set) var expiresAtMs: Int64 = 0
    public private(set) var source = 0
    public private(set) var openedFromBacklog = false
    public private(set) var generation = 0
    public private(set) var lastClose: CloseReason = .none
    private var isOpenFlag = false
    private var openSeq = -1

    public init() {}

    public mutating func onDeskPair(seq: Int, _ dp: BtrxProtocol.DeskPair, arrivalMs: Int64, backlog: Bool) {
        if dp.open == 1 {
            if isOpenFlag && seq == openSeq && arrivalMs < expiresAtMs { return }
            let seconds = dp.secondsLeft > 0 ? dp.secondsLeft : Self.defaultSeconds
            isOpenFlag = true
            openSeq = seq
            openedAtMs = arrivalMs
            expiresAtMs = arrivalMs + Int64(seconds) * 1000
            source = dp.source
            openedFromBacklog = backlog
            lastClose = .none
            generation += 1
        } else {
            if isOpenFlag { lastClose = .closedByDeskPod }
            isOpenFlag = false
            openSeq = -1
        }
    }

    public mutating func isOpen(nowMs: Int64) -> Bool {
        if isOpenFlag && nowMs >= expiresAtMs {
            isOpenFlag = false
            lastClose = .expired
        }
        return isOpenFlag
    }

    public mutating func secondsLeft(nowMs: Int64) -> Int {
        isOpen(nowMs: nowMs) ? Int((expiresAtMs - nowMs + 999) / 1000) : 0
    }

    public mutating func reset() { isOpenFlag = false; openSeq = -1; lastClose = .none }
}
