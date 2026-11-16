import Foundation

/// Desk Pod button confirmation (firmware 0x0108, btrx ec1d105, PROTOCOL.md "Desk Pod button
/// confirmation"). Owned by ``BtrxDeskPodModel``, which sends the CONFIRM_RSP frames this
/// returns to the Desk Pod's own node. Same as Android's BtrxButtonConfirm.kt; keep them
/// matching. Not thread-safe: the model's queue confines it.
///
/// While the panel can be shown (a ready link to a 0x0108+ Desk Pod and the app in front), a
/// keepalive (token 0, answer 3) goes out every ``BtrxProtocol/confirmKeepaliveMs``, so the
/// Desk Pod asks before a button tap opens its pairing window or a 10 s hold factory resets
/// it. A CONFIRM_REQ ask (state 0) opens the panel and is answered "shown" at once (the Desk
/// Pod acts on its own when nothing answers in 3 s). Confirm / Cancel answer with the same
/// token. The panel closes on state 1 / 2 / 3 for its token, or 15 s after the ask. A token
/// this app never saw asked is ignored; so is a repeat of an outcome.
public final class BtrxButtonConfirm {
    typealias P = BtrxProtocol

    /// The panel on screen. `answer` is nil until Confirm or Cancel was tapped.
    public struct Panel: Sendable, Equatable {
        public let action: Int, token: Int, askedMs: Int64, timeoutMs: Int64
        public var answer: Int?
    }

    public struct View: Sendable, Equatable {
        public let action: Int, token: Int, secondsLeft: Int, confirmSent: Bool
        public var factoryReset: Bool { action == BtrxProtocol.CFM_ACTION_FACTORY_RESET }
    }

    /// A brief, non-blocking result line for the map and the Desk Pod pane.
    public struct Notice: Sendable, Equatable {
        public let text: String, atMs: Int64, showMs: Int64
    }

    private final class Known {
        let action: Int
        var finished = false, cancelledHere = false
        init(action: Int) { self.action = action }
    }

    /// A CONFIRM_REQ's effects: answers to send (token, answer), and whether the Desk Pod was just factory reset.
    public struct Result: Equatable {
        public var answers: [Answer] = []
        public var deskReset = false
    }

    public struct Answer: Equatable { public let token: Int, answer: Int }

    public static let noticeMs: Int64 = 8_000
    public static let resetNoticeMs: Int64 = 30_000
    public static let knownMax = 8
    public static let resetDone = "Desk Pod factory reset (confirmed). It comes back unpaired: re-pair the Drone Pod."
    public static let resetDoneNoAsk = "Desk Pod button: factory reset done without asking. It comes back unpaired: re-pair the Drone Pod."

    public private(set) var panel: Panel?
    public private(set) var notice: Notice?
    /// A CONFIRM_REQ arrived this session: the Desk Pod has the feature whatever its reported build.
    public private(set) var seenThisSession = false
    private var known: [Int: Known] = [:]
    private var knownOrder: [Int] = []
    private var lastKeepaliveMs: Int64?
    private let log: BtrxLogger

    public init(log: BtrxLogger) { self.log = log }

    public func view(nowMs: Int64) -> View? {
        panel.map { p in
            let left = Int(max(0, (p.askedMs + p.timeoutMs - nowMs + 999) / 1000))
            return View(action: p.action, token: p.token, secondsLeft: left, confirmSent: p.answer == P.CFM_CONFIRM)
        }
    }

    /// The notice while it is still shown on the map (nil after its time).
    public func liveNotice(nowMs: Int64) -> Notice? {
        guard let n = notice, nowMs - n.atMs < n.showMs else { return nil }
        return n
    }

    /// A new session: the keepalive starts again at once.
    public func newSession() {
        lastKeepaliveMs = nil
        seenThisSession = false
    }

    /// The link dropped: close the panel (the Desk Pod drops or acts on its own).
    public func linkLost(nowMs: Int64, reason: String) {
        lastKeepaliveMs = nil
        if let p = panel {
            log.warn("desk button: panel for token=\(p.token) closed: link lost (\(reason))")
            notice = Notice(text: "Desk Pod button: the link to the Desk Pod was lost; nothing was answered.", atMs: nowMs, showMs: Self.noticeMs)
            panel = nil
        }
    }

    /// True when a keepalive is due now (and counts it as sent). `enabled`: link ready, 0x0108+, panel can be shown.
    public func keepaliveDue(nowMs: Int64, enabled: Bool) -> Bool {
        guard enabled else { lastKeepaliveMs = nil; return false }
        if let last = lastKeepaliveMs, nowMs - last < P.confirmKeepaliveMs { return false }
        lastKeepaliveMs = nowMs
        return true
    }

    /// A CONFIRM_REQ from the Desk Pod. `canShow`: the panel host is on screen (app in front).
    public func onRequest(_ r: BtrxProtocol.ConfirmReq, nowMs: Int64, canShow: Bool) -> Result {
        guard r.version == P.CONFIRM_VERSION else {
            log.warn("desk button: CONFIRM_REQ version \(r.version) ignored")
            return Result()
        }
        seenThisSession = true
        let what = "action=\(P.confirmActionName(r.action)) token=\(r.token)"
        if r.state == P.CFM_ASK {
            if r.token == 0 { log.warn("desk button: ask with token 0 ignored"); return Result() }
            if let cur = panel, cur.token == r.token {
                // A repeat of the ask on screen: say "shown" again (harmless to the Desk Pod).
                return Result(answers: [Answer(token: r.token, answer: P.CFM_SHOWN)])
            }
            if known[r.token]?.finished == true { return Result() }
            remember(r.token, action: r.action)
            guard canShow else {
                log.info("desk button: ask \(what) not shown: the app is not in front; the Desk Pod acts on its own in \(P.confirmShownMs / 1000) s")
                return Result()
            }
            if let cur = panel { log.info("desk button: ask token=\(r.token) replaces token=\(cur.token)") }
            let timeout = Int64(r.timeoutS > 0 ? r.timeoutS : P.confirmTimeoutS) * 1000
            panel = Panel(action: r.action, token: r.token, askedMs: nowMs, timeoutMs: timeout, answer: nil)
            log.info("desk button: ask \(what) timeout_s=\(r.timeoutS): panel shown")
            return Result(answers: [Answer(token: r.token, answer: P.CFM_SHOWN)])
        }
        guard let k = known[r.token], !k.finished else {
            log.debug("desk button: CONFIRM_REQ \(what) state=\(P.confirmStateName(r.state)) for a token not asked here (or already closed); ignored")
            return Result()
        }
        k.finished = true
        if panel?.token == r.token { panel = nil }
        let reset = r.action == P.CFM_ACTION_FACTORY_RESET
        let text: String
        switch r.state {
        case P.CFM_DONE: text = reset ? Self.resetDone : "Desk Pod button: pairing opened (confirmed)."
        case P.CFM_DONE_NO_APP: text = reset ? Self.resetDoneNoAsk : "Desk Pod button: pairing opened."
        case P.CFM_DROPPED: text = k.cancelledHere ? "Desk Pod button: cancelled; nothing was changed."
            : "Desk Pod button: request dropped (no answer in time, or replaced); nothing was changed."
        default: text = "Desk Pod button: request ended (state \(r.state))."
        }
        log.info("desk button: \(what) state=\(P.confirmStateName(r.state)): \(text)")
        let deskReset = reset && (r.state == P.CFM_DONE || r.state == P.CFM_DONE_NO_APP)
        notice = Notice(text: text, atMs: nowMs, showMs: deskReset ? Self.resetNoticeMs : Self.noticeMs)
        return Result(answers: [], deskReset: deskReset)
    }

    /// Confirm or Cancel on the panel: the answer to send, or nil when there is nothing to answer.
    public func answer(confirm: Bool, nowMs: Int64) -> Answer? {
        guard var p = panel, p.answer == nil else { return nil }
        let a = confirm ? P.CFM_CONFIRM : P.CFM_CANCEL
        log.info("desk button: \(P.confirmAnswerName(a)) token=\(p.token) (\(P.confirmActionName(p.action)))")
        if confirm {
            p.answer = a
            panel = p // stays up until the Desk Pod says done (state 1)
        } else {
            known[p.token]?.cancelledHere = true
            panel = nil
            notice = Notice(text: "Desk Pod button: cancelled; nothing was changed.", atMs: nowMs, showMs: Self.noticeMs)
        }
        return Answer(token: p.token, answer: a)
    }

    /// The 15 s limit (the Desk Pod drops the request then too).
    public func tick(nowMs: Int64) {
        guard let p = panel, nowMs - p.askedMs >= p.timeoutMs else { return }
        log.info("desk button: token=\(p.token) closed after \(p.timeoutMs / 1000) s with no outcome from the Desk Pod")
        panel = nil
        notice = Notice(text: p.answer == P.CFM_CONFIRM ? "Desk Pod button: no word from the Desk Pod after Confirm; check the Desk Pod."
                            : "Desk Pod button: no answer in \(p.timeoutMs / 1000) s; nothing was changed.",
                        atMs: nowMs, showMs: Self.noticeMs)
    }

    private func remember(_ token: Int, action: Int) {
        if known[token] == nil { knownOrder.append(token) }
        known[token] = Known(action: action)
        while knownOrder.count > Self.knownMax { known.removeValue(forKey: knownOrder.removeFirst()) }
    }
}
