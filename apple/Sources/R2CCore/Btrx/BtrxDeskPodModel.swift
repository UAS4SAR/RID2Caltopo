import Foundation

/// Everything the Desk Pod screen shows, and the "Pair drone pod" flow, independent of
/// CoreBluetooth so it is unit tested. Same as Android's BtrxDeskPodModel.kt; keep them
/// matching (steps, timeouts, messages). Not thread-safe: the Bluetooth link confines it
/// to its serial queue and publishes ``Snapshot`` values to the main actor.
///
/// Pairing (brief section 6): tap the Desk Pod button (DESK_PAIR open=1, a backlog tap
/// still inside its window counts), nod the drone pod twice (a live PAIR_REQ while the
/// window is open), the operator confirms, PAIR_CONFIRM with the nonce echoed and a
/// non-zero pod_id, then PAIR_ACK with a_ok / b_ok. Nothing here ever makes a clue.
public final class BtrxDeskPodModel {
    public enum LinkKind: Sendable { case none, bluetooth, usb }
    public enum LinkPhase: Sendable { case off, unavailable, noPermission, scanning, bonding, connecting, configuring, ready, retryWait, pausedForUSB }

    public struct LinkStatus: Sendable, Equatable {
        public var kind: LinkKind = .none
        public var phase: LinkPhase = .off
        public var detail = ""
        public var deviceName: String?
        public init(kind: LinkKind = .none, phase: LinkPhase = .off, detail: String = "", deviceName: String? = nil) {
            self.kind = kind; self.phase = phase; self.detail = detail; self.deviceName = deviceName
        }
    }

    public struct PairedPod: Sendable, Equatable, Codable {
        public let node: UInt32, podId: UInt32, devA: UInt32, devB: UInt32
        public init(node: UInt32, podId: UInt32, devA: UInt32, devB: UInt32) { self.node = node; self.podId = podId; self.devA = devA; self.devB = devB }
    }

    public enum PairStep: Sendable { case idle, waitButton, waitNod, podFound, confirming, paired, failed, cancelled }
    public static let activeSteps: Set<PairStep> = [.waitButton, .waitNod, .podFound, .confirming]

    public struct Candidate: Sendable, Equatable {
        public let node: UInt32, assignedPodId: UInt32, devA: UInt32, devB: UInt32, buildA: Int, buildB: Int
        public let imuOk: Int, roleA: Int, roleB: Int
        public var nonceHex: String, rssi: Int, snr: Float

        public var warnings: [String] {
            var w: [String] = []
            if devB == 0 || roleB != BtrxProtocol.rolePort { w.append("The port board did not answer; check the cable between the boards. It can still pair but cannot nod again.") }
            if imuOk == 0 { w.append("The Drone Pod's motion sensor is not OK.") }
            return w
        }
    }

    public struct Pairing: Sendable, Equatable {
        public var step: PairStep = .idle
        public var message = ""
        public var failedAt: PairStep?
        public var windowSecondsLeft = 0
        public var candidate: Candidate?
        public var aOk: Int?
        public var bOk: Int?
        public var ackFlags: Int?
        public var confirmTries = 0
        public init() {}
    }

    public struct HealthReading: Sendable, Equatable {
        public let node: UInt32, arrivalMs: Int64, rxRssi: Int, rxSnr: Float, health: BtrxProtocol.Health
    }

    public struct Snapshot: Sendable, Equatable {
        public var nowMs: Int64 = 0
        public var link = LinkStatus()
        public var bluetoothPausedForUSB = false
        public var localNode: UInt32?
        public var role: BtrxProtocol.RoleReport?
        public var dronePodHealth: HealthReading?
        public var otherPodNodes = 0
        public var detectsLastMinute = 0
        public var lastDetectMs: Int64?
        public var lastPacketRssi: Int?
        public var lastPacketSnr: Float?
        public var liveCounts: [String: Int] = [:]
        public var backlogCounts: [String: Int] = [:]
        public var windowOpen = false
        public var windowSecondsLeft = 0
        public var windowFromBacklog = false
        public var pairing = Pairing()
        public var pairedPod: PairedPod?
        public init() {}
        public var roleSupported: Bool { role.map { BtrxProtocol.supportedBuilds.contains($0.buildHash) } ?? false }
    }

    public static let buttonWaitMs: Int64 = 120_000
    public static let ackTimeoutMs: Int64 = 30_000
    public static let maxConfirmTries = 8
    public static let detectWindowMs: Int64 = 60_000

    private let log: BtrxLogger
    private let clock: () -> Int64
    private let onPaired: (PairedPod) -> Void
    private weak var sender: BtrxFrameSender?
    private var link = LinkStatus()
    private var pausedForUSB = false
    private var localNode: UInt32?
    private var role: BtrxProtocol.RoleReport?
    private var window = BtrxDeskWindow()
    private var pairing = Pairing()
    private var stepDeadlineMs: Int64 = 0
    private var pairedPod: PairedPod?
    private var lastHealth: HealthReading?
    private var otherPods = Set<UInt32>()
    private var detectTimes: [Int64] = []
    private var lastDetectMs: Int64?
    private var lastRssi: Int?
    private var lastSnr: Float?
    private var liveCounts: [String: Int] = [:]
    private var backlogCounts: [String: Int] = [:]

    public init(log: BtrxLogger, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }, onPaired: @escaping (PairedPod) -> Void = { _ in }) {
        self.log = log
        self.clock = clock
        self.onPaired = onPaired
    }

    // MARK: link

    public func setLink(_ status: LinkStatus, bluetoothPausedForUSB: Bool = false) {
        link = status
        pausedForUSB = bluetoothPausedForUSB
    }

    /// A new session on the current link. Counters restart; the window and the paired pod do not.
    public func attach(_ sender: BtrxFrameSender) {
        self.sender = sender
        localNode = nil
        role = nil
        liveCounts = [:]; backlogCounts = [:]
        detectTimes.removeAll()
    }

    public func detach(_ sender: BtrxFrameSender, reason: String) {
        guard self.sender === sender else { return }
        self.sender = nil
        if Self.activeSteps.contains(pairing.step) { fail(pairing.step, "The link to the Desk Pod was lost (\(reason)).") }
    }

    public func restorePairedPod(_ pod: PairedPod?) { pairedPod = pod }
    public func forgetPairedPod() { pairedPod = nil; lastHealth = nil; otherPods.removeAll() }

    // MARK: client listener

    public func onConfigured(localNode node: UInt32) { localNode = node }
    public func onLocalRoleReport(_ report: BtrxProtocol.RoleReport) { role = report }

    public func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta) {
        if meta.backlog { backlogCounts[frame.typeName, default: 0] += 1 } else { liveCounts[frame.typeName, default: 0] += 1 }
        let fromDesk = localNode == nil || meta.fromNode == localNode
        if !fromDesk { lastRssi = meta.rxRssi; lastSnr = meta.rxSnr }
        do {
            switch frame.type {
            case BtrxProtocol.DESK_PAIR: if fromDesk { try onDeskPair(frame, meta) }
            case BtrxProtocol.PAIR_REQ: onPairReq(try BtrxProtocol.parsePairReq(frame.payload), meta)
            case BtrxProtocol.PAIR_ACK: onPairAck(try BtrxProtocol.parsePairAck(frame.payload), meta)
            case BtrxProtocol.HEALTH: onHealth(try BtrxProtocol.parseHealth(frame.payload), meta)
            case BtrxProtocol.DETECT:
                if !meta.backlog {
                    detectTimes.append(meta.arrivalMs)
                    lastDetectMs = meta.arrivalMs
                    trimDetects(meta.arrivalMs)
                }
            default: break
            }
        } catch {
            // Logged with decode-error by BtrxFrameLog; nothing to act on.
        }
    }

    private func onDeskPair(_ frame: BtrxProtocol.Frame, _ meta: BtrxRadioMeta) throws {
        let dp = try BtrxProtocol.parseDeskPair(frame.payload)
        window.onDeskPair(seq: frame.seq, dp, arrivalMs: meta.arrivalMs, backlog: meta.backlog)
        let now = meta.arrivalMs
        if dp.open == 1 {
            if window.openedFromBacklog { log.info("pairing: Desk Pod window open from a tap that arrived with the connect backlog (\(window.secondsLeft(nowMs: now)) s left)") }
            // A new tap while waiting for the nod restarts the countdown on the new window.
            if pairing.step == .waitButton || pairing.step == .waitNod { enterWaitNod(now) }
        } else {
            switch pairing.step {
            case .waitNod: fail(.waitNod, "The Desk Pod closed its pairing window before a Drone Pod asked to pair. Tap the Desk Pod button and nod the drone pod again.")
            case .podFound: fail(.podFound, "The Desk Pod closed its pairing window before the pod was confirmed. Tap the Desk Pod button and start again.")
            default: break
            }
        }
    }

    private func onPairReq(_ req: BtrxProtocol.PairReq, _ meta: BtrxRadioMeta) {
        let now = meta.arrivalMs
        if meta.backlog {
            log.info("pairing: PAIR_REQ from \(BtrxClient.nodeName(meta.fromNode)) in the connect backlog ignored (stale)")
            return
        }
        guard window.isOpen(nowMs: now) else {
            log.info("pairing: PAIR_REQ from \(BtrxClient.nodeName(meta.fromNode)) ignored: no Desk Pod window open")
            return
        }
        let nonce = BtrxProtocol.hex(req.nonce)
        switch pairing.step {
        case .waitNod:
            let c = Candidate(node: meta.fromNode, assignedPodId: BtrxProtocol.podId(forDevA: req.devA), devA: req.devA, devB: req.devB,
                              buildA: req.buildA, buildB: req.buildB, imuOk: req.imuOk, roleA: req.roleA, roleB: req.roleB,
                              nonceHex: nonce, rssi: meta.rxRssi, snr: meta.rxSnr)
            pairing.step = .podFound
            pairing.candidate = c
            pairing.message = "Drone Pod \(BtrxClient.nodeName(c.node)) asked to pair. Confirm it is the pod in your hand."
            log.info(String(format: "pairing: PAIR_REQ from %@ dev_a=%08x dev_b=%08x imu_ok=%d; waiting for the operator", BtrxClient.nodeName(c.node), c.devA, c.devB, c.imuOk))
        case .podFound:
            guard var c = pairing.candidate else { return }
            if meta.fromNode == c.node {
                c.nonceHex = nonce; c.rssi = meta.rxRssi; c.snr = meta.rxSnr
                pairing.candidate = c
            } else {
                log.info("pairing: another Drone Pod \(BtrxClient.nodeName(meta.fromNode)) also asked to pair; ignored")
            }
        case .confirming:
            guard let c = pairing.candidate else { return }
            if meta.fromNode == c.node && nonce == c.nonceHex && pairing.confirmTries < Self.maxConfirmTries {
                pairing.confirmTries += 1
                log.info("pairing: PAIR_REQ repeated; answering again (try \(pairing.confirmTries))")
                sendConfirm(c)
            }
        default: break
        }
    }

    private func onPairAck(_ ack: BtrxProtocol.PairAck, _ meta: BtrxRadioMeta) {
        guard !meta.backlog, pairing.step == .confirming, let c = pairing.candidate, meta.fromNode == c.node else { return }
        pairing.aOk = ack.aOk; pairing.bOk = ack.bOk; pairing.ackFlags = ack.flags
        if ack.aOk == 1 {
            let pod = PairedPod(node: c.node, podId: c.assignedPodId, devA: c.devA, devB: c.devB)
            pairedPod = pod
            pairing.step = .paired
            pairing.failedAt = nil
            pairing.message = ack.bOk == 1 ? "Drone Pod paired." : "Paired, but the port board did not answer. Check the cable between the boards."
            log.info(String(format: "pairing: PAIR_ACK a_ok=%d b_ok=%d; paired %@ pod_id=%08x", ack.aOk, ack.bOk, BtrxClient.nodeName(c.node), c.assignedPodId))
            onPaired(pod)
        } else {
            fail(.confirming, "The Drone Pod did not take the pairing (a_ok=0, flags=\(BtrxProtocol.statusFlagNames(ack.flags))).")
        }
    }

    private func onHealth(_ h: BtrxProtocol.Health, _ meta: BtrxRadioMeta) {
        if meta.fromNode == localNode { return }
        if let paired = pairedPod, meta.fromNode != paired.node {
            otherPods.insert(meta.fromNode)
            return
        }
        lastHealth = HealthReading(node: meta.fromNode, arrivalMs: meta.arrivalMs, rxRssi: meta.rxRssi, rxSnr: meta.rxSnr, health: h)
    }

    // MARK: pairing actions

    public func startPairing() {
        let now = clock()
        guard let s = sender, s.isReady else {
            pairing = Pairing()
            pairing.step = .failed; pairing.failedAt = .waitButton; pairing.message = "The Desk Pod is not connected."
            return
        }
        pairing = Pairing()
        pairing.step = .waitButton
        pairing.message = "Tap the Desk Pod button."
        stepDeadlineMs = now + Self.buttonWaitMs
        if window.isOpen(nowMs: now) { enterWaitNod(now) }
    }

    public func confirmPod() {
        let now = clock()
        guard pairing.step == .podFound, let c = pairing.candidate else { return }
        guard window.isOpen(nowMs: now) else {
            fail(.podFound, "The pairing window ran out before the pod was confirmed. Tap the Desk Pod button and start again.")
            return
        }
        pairing.step = .confirming
        pairing.confirmTries = 1
        pairing.message = "Waiting for the Drone Pod to acknowledge."
        stepDeadlineMs = now + Self.ackTimeoutMs
        log.info(String(format: "pairing: PAIR_CONFIRM to %@ nonce=%@ pod_id=%08x", BtrxClient.nodeName(c.node), c.nonceHex, c.assignedPodId))
        sendConfirm(c)
    }

    /// "Cancel pairing": ends the flow. Nothing is sent; the pod's own window runs out.
    public func cancelPairing() {
        guard Self.activeSteps.contains(pairing.step) else { return }
        log.info("pairing: cancelled by the operator at \(pairing.step)")
        pairing.step = .cancelled
        pairing.message = "Pairing cancelled."
        pairing.failedAt = nil
    }

    public func clearPairingResult() {
        if !Self.activeSteps.contains(pairing.step) { pairing = Pairing() }
    }

    /// Timeouts; call about once a second.
    public func tick() {
        let now = clock()
        trimDetects(now)
        let open = window.isOpen(nowMs: now)
        switch pairing.step {
        case .waitButton: if now >= stepDeadlineMs { fail(.waitButton, "The Desk Pod button was not tapped. Tap it, then start again.") }
        case .waitNod: if !open { fail(.waitNod, windowEndedMessage("No Drone Pod asked to pair. Nod the drone pod twice within the window.")) }
        case .podFound: if !open { fail(.podFound, windowEndedMessage("The pod was not confirmed in time.")) }
        case .confirming: if now >= stepDeadlineMs { fail(.confirming, "No PAIR_ACK from the Drone Pod within \(Self.ackTimeoutMs / 1000) s. Tap the Desk Pod button and try again.") }
        default: break
        }
        if Self.activeSteps.contains(pairing.step) { pairing.windowSecondsLeft = window.secondsLeft(nowMs: now) }
    }

    public func snapshot() -> Snapshot {
        let now = clock()
        trimDetects(now)
        var s = Snapshot()
        s.nowMs = now; s.link = link; s.bluetoothPausedForUSB = pausedForUSB; s.localNode = localNode; s.role = role
        s.dronePodHealth = lastHealth; s.otherPodNodes = otherPods.count; s.detectsLastMinute = detectTimes.count
        s.lastDetectMs = lastDetectMs; s.lastPacketRssi = lastRssi; s.lastPacketSnr = lastSnr
        s.liveCounts = liveCounts; s.backlogCounts = backlogCounts
        s.windowOpen = window.isOpen(nowMs: now); s.windowSecondsLeft = window.secondsLeft(nowMs: now); s.windowFromBacklog = window.openedFromBacklog
        var p = pairing
        if Self.activeSteps.contains(p.step) { p.windowSecondsLeft = window.secondsLeft(nowMs: now) }
        s.pairing = p
        s.pairedPod = pairedPod
        return s
    }

    // MARK: internals

    private func enterWaitNod(_ now: Int64) {
        pairing.step = .waitNod
        pairing.windowSecondsLeft = window.secondsLeft(nowMs: now)
        pairing.message = "Nod the drone pod twice."
        log.info("pairing: Desk Pod window open, \(window.secondsLeft(nowMs: now)) s left; waiting for PAIR_REQ")
    }

    private func windowEndedMessage(_ fallback: String) -> String {
        window.lastClose == .closedByDeskPod ? "The Desk Pod closed its pairing window. \(fallback)" : "The pairing window ran out. \(fallback)"
    }

    private func fail(_ at: PairStep, _ message: String) {
        log.warn("pairing failed at \(at): \(message)")
        pairing.step = .failed
        pairing.failedAt = at
        pairing.message = message
        pairing.windowSecondsLeft = 0
    }

    private func sendConfirm(_ c: Candidate) {
        guard let s = sender else { return }
        let frame = BtrxProtocol.pairConfirmFrame(seq: s.nextSeq(), podId: c.assignedPodId, nonce: BtrxProtocol.fromHex(c.nonceHex),
                                                  roleA: c.roleA != 0 ? c.roleA : BtrxProtocol.roleStarboard,
                                                  roleB: c.roleB != 0 ? c.roleB : BtrxProtocol.rolePort)
        s.sendFrame(to: c.node, frame: frame)
    }

    private func trimDetects(_ now: Int64) {
        while let first = detectTimes.first, now - first > Self.detectWindowMs { detectTimes.removeFirst() }
    }
}
