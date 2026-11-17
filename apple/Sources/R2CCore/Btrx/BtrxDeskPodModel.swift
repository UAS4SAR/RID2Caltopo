import Foundation

/// Everything the Desk Pod screen shows, and the "Pair drone pod" flow, independent of
/// CoreBluetooth so it is unit tested. Same as Android's BtrxDeskPodModel.kt; keep them
/// matching (steps, timeouts, messages). Not thread-safe: the Bluetooth link confines it
/// to its serial queue and publishes ``Snapshot`` values to the main actor.
///
/// Pairing (brief section 6): tap the Desk Pod button (DESK_PAIR open=1, a backlog tap
/// still inside its window counts), nod the drone pod twice (a live PAIR_REQ while the
/// window is open), the operator confirms, PAIR_CONFIRM with the nonce echoed and a
/// non-zero pod_id, then PAIR_ACK with a_ok / b_ok. The pod only takes PAIR_CONFIRM
/// inside its own 60 s nod window, while it repeats PAIR_REQ every 2 s; once a pod has
/// asked, the Desk Pod's window is advisory, and no PAIR_REQ for ``podRequestStaleMs``
/// marks the pod stale and asks for another nod. Only the same pod can revive a stale
/// candidate; a different pod needs a new Desk Pod tap (a new DESK_PAIR open=1 goes back
/// to the nod). A stale pod ends the flow once the Desk Pod window has closed or
/// ``podFoundMaxMs`` after it was found.
///
/// Pod traffic (HEALTH, DETECT, RSSI/SNR) counts only when live: backlog frames can be
/// minutes old. Once a pod is paired only its node is accepted (the Desk Pod does not
/// filter by pod_id); a non-zero header pod_id other than the paired one is refused,
/// counted and shown ("re-pair"). No HEALTH from the paired pod for ``pairedQuietMs`` is
/// shown too: an unconfirmed pod (pod_id reset after a 10 s hold) stops transmitting.
///
/// Steering (design doc section 41): live STEER frames from the paired pod (by node,
/// pod_id 0 or the paired one, like other pod traffic) feed ``BtrxSteering``; the stream
/// view overlay shows only while the link is connected, a pod is paired and STEER keeps
/// arriving. ``muteSteeringSource()`` / ``clearSteeringMutes()`` send STEER_MUTE to that pod.
/// Nothing here ever makes a clue.
///
/// Re-pair (firmware 0x0106): a paired pod ignores nods (motor vibration read as nods at
/// takeoff). ``repairDronePod(seconds:)`` sends PAIR_OPEN to the paired pod and DESK_PAIR to the
/// Desk Pod (its phone-link bench-test path opens the Desk Pod window), then runs the normal
/// flow with only the paired node accepted. No confirm leaves the pod paired as it was.
///
/// Own aircraft (saved) (firmware 0x0106): STEER_MUTE op 3 / 4 by src_id puts the address on
/// (takes it off) the pod's saved ignore list; FILTER_ACK answers. `savedIgnores` is this app's
/// record of what the pod took (per paired pod, persisted by the caller). Same as Android.
///
/// IMU calibration (design doc section 29.3, firmware 0x0102): ``BtrxImuCal`` runs the flow;
/// IMU_CAL goes to the paired pod's node like STEER_MUTE, and only that pod's live
/// IMU_CAL_ACK (same filter as other pod traffic) answers it. STEER bit 6 feeds "Calibrated".
public final class BtrxDeskPodModel {
    public enum LinkKind: Sendable { case none, bluetooth, usb }
    public enum LinkPhase: Sendable { case off, unavailable, noPermission, scanning, bonding, connecting, configuring, ready, retryWait, pausedForUSB }

    public struct LinkStatus: Sendable, Equatable {
        public var kind: LinkKind = .none
        public var phase: LinkPhase = .off
        public var detail = ""
        public var deviceName: String?
        /// Bluetooth: connect attempts that failed in a row (0 once a session reaches READY).
        public var failedAttempts = 0
        /// Bluetooth: why the latest of those attempts failed, e.g. "connection failed (status 147)".
        public var lastFailure: String?
        public init(kind: LinkKind = .none, phase: LinkPhase = .off, detail: String = "", deviceName: String? = nil,
                    failedAttempts: Int = 0, lastFailure: String? = nil) {
            self.kind = kind; self.phase = phase; self.detail = detail; self.deviceName = deviceName
            self.failedAttempts = failedAttempts; self.lastFailure = lastFailure
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
        /// Arrival of the latest PAIR_REQ from this pod (they repeat every 2 s inside its nod window).
        public var lastRequestMs: Int64 = 0
        /// Arrival of the PAIR_REQ that made this the candidate.
        public var foundMs: Int64 = 0

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
        /// The pod stopped repeating PAIR_REQ: its own window has probably closed. Nod again.
        public var podStale = false
        /// "Re-pair Drone Pod": only the paired node may answer; a failure leaves it paired as it was.
        public var repair = false
        /// Re-pair of a pod older than 0x0106 (no PAIR_OPEN): it opens its window on a nod.
        public var repairByNod = false
        public init() {}
    }

    /// A STEER_MUTE op 3 / 4 waiting for its FILTER_ACK. Up to `maxPendingFilters` may wait (one
    /// per src_id); an 8-byte FILTER_ACK (0x0107) is matched by its echoed src_id, a 6-byte one
    /// (0x0106) to the oldest waiting request of that op. Same as Android.
    public struct PendingFilter: Sendable, Equatable {
        public let op: Int, srcId: Int, sentMs: Int64
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
        /// Live frames from the paired node carrying an unexpected non-zero pod_id (refused).
        public var pairedPodIdMismatches = 0
        public var lastMismatchPodId: UInt32?
        /// Paired, connected, and no HEALTH from the paired pod for over ``pairedQuietMs``.
        public var pairedPodQuiet = false
        /// The stream-view steering overlay, or nil when it must not show.
        public var steerOverlay: BtrxSteering.View?
        /// The latest STEER whatever the link state, for the Desk Pod screen's bench line.
        public var lastSteer: BtrxSteering.View?
        /// Debug: roll correction of the steering hint (off until the bench roll check).
        public var steeringRollCorrection = BtrxSteering.rollCorrectionDefault
        /// The Drone Pod IMU calibration flow (Desk Pod screen).
        public var imuCal: BtrxImuCal.View?
        /// phone_lost from the Desk Pod's own HEALTH (build 0x0104); nil before one or on older builds.
        public var deskPhoneLost: UInt32?
        /// This session: packets dropped before btrx decoding, encrypted for another channel.
        public var droppedEncrypted = 0
        /// This session: packets dropped before btrx decoding, another port (not 256).
        public var droppedOtherPort = 0
        /// The Desk Pod's own HEALTH fields from build 0x0106 (reset reason etc.), or nil.
        public var deskHealthExt: BtrxProtocol.HealthExt?
        /// Paired pod build 0x0106+: "Own aircraft (saved)" is offered; else the local toggle only.
        public var savedIgnoreSupported = false
        /// src ids the paired pod confirmed on its saved ignore list (FILTER_ACK ok=1).
        public var savedIgnores: [Int] = []
        /// Op 3 / 4 requests waiting for FILTER_ACK, oldest first.
        public var filterPending: [PendingFilter] = []
        /// The latest saved-ignore result, for the BTRX pane.
        public var filterMessage: String?
        /// DESK_PAIR source of the latest open (1 button, 2 phone/USB, 3 PAIR_OPEN).
        public var deskWindowSource = 0
        /// Desk Pod button confirmation (build 0x0108): the Confirm/Cancel panel, or nil.
        public var buttonConfirm: BtrxButtonConfirm.View?
        /// The latest button result while it is shown on the map (non-blocking), or nil.
        public var buttonNotice: BtrxButtonConfirm.Notice?
        /// The latest button result, kept for the Desk Pod pane.
        public var buttonLastNotice: BtrxButtonConfirm.Notice?
        public var buttonConfirmLink: ButtonConfirmLink = .noLink
        /// When the Desk Pod reported a factory reset from its button (it comes back unpaired), until a pairing completes.
        public var deskResetAtMs: Int64?
        /// The last session ended with the Desk Pod silent (Nov 17), and no session has started since.
        public var deskSilent = false
        /// Why that session ended.
        public var deskSilentReason: String?
        /// "Desk Pod restarted (power-on)" while fresh (the map chip), else nil.
        public var deskRestartNotice: String?
        /// The latest restart after a link drop, kept for the Desk Pod screen.
        public var deskRestart: BtrxDeskPodPower.Restart?
        /// The Desk Pod's own standard DeviceMetrics, when it sent any.
        public var deskDeviceMetrics: MeshtasticPhoneAPI.DeviceMetrics?
        public var deskDeviceMetricsAtMs: Int64?
        public init() {}
        /// An op 3 / 4 for `srcId` is waiting for its FILTER_ACK.
        public func filterPendingFor(_ srcId: Int) -> Bool { filterPending.contains { $0.srcId == srcId } }
        /// What opened the Desk Pod's current window (DESK_PAIR source), 0 when closed.
        public var windowSource: Int { windowOpen ? deskWindowSource : 0 }
        public var roleSupported: Bool { role.map { BtrxProtocol.buildSupported($0.buildHash) } ?? false }
    }

    public static let buttonWaitMs: Int64 = 120_000
    public static let ackTimeoutMs: Int64 = 30_000
    public static let maxConfirmTries = 8
    public static let detectWindowMs: Int64 = 60_000
    /// PAIR_REQ repeats every 2 s while the pod's own window is open.
    public static let podRequestStaleMs: Int64 = 7_000
    /// A stale candidate ends the flow this long after it was found, even with the window open.
    public static let podFoundMaxMs: Int64 = 120_000
    /// No HEALTH (about every 60 s) from the paired pod for this long: hint to re-pair.
    public static let pairedQuietMs: Int64 = 120_000
    public static let mismatchLogEvery = 50
    /// Re-pair: the Desk Pod answers DESK_PAIR at once; a manual tap also counts until then.
    public static let repairWindowWaitMs: Int64 = 30_000
    public static let filterAckTimeoutMs: Int64 = 10_000
    /// Op 3 / 4 requests that may wait for FILTER_ACK at once (one per src_id).
    public static let maxPendingFilters = 4
    /// The pod's saved ignore list holds 32 addresses (PROTOCOL.md STEER_MUTE).
    public static let filterListMax = 32
    public static let repairKept = "The Drone Pod stays paired as it was."
    public static let repairNotPaired = "No Drone Pod is paired. Use Pair drone pod."
    public static let repairOpening = "Opening the pairing windows on the Desk Pod and the Drone Pod…"
    public static let repairOpeningNod = "Opening the Desk Pod's pairing window… Then nod the drone pod twice (its firmware is older than 0x0106)."
    public static let staleEndedMessage = "The Drone Pod stopped asking to pair and its pairing window is over. Tap the Desk Pod button and nod the drone pod twice again."
    public static let staleMessage = "The Drone Pod stopped asking to pair; its own pairing window has probably closed. Nod the drone pod twice again, then confirm."

    private let log: BtrxLogger
    private let clock: () -> Int64
    private let onPaired: (PairedPod) -> Void
    private let onSavedIgnores: ([Int]) -> Void
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
    private var deskPhoneLost: UInt32?
    private var deskHealthExt: BtrxProtocol.HealthExt?
    private var savedIgnores = Set<Int>()
    private var pendingFilters: [PendingFilter] = []
    private var filterMessage: String?
    /// The Desk Pod's own latest live HEALTH this session: uptime_s and arrival (logged when the link drops).
    private var deskUptimeS: UInt32?
    private var deskHealthAtMs: Int64 = 0
    private var droppedEncrypted = 0
    private var droppedOtherPort = 0
    private var otherPods = Set<UInt32>()
    private var detectTimes: [Int64] = []
    private var lastDetectMs: Int64?
    private var lastRssi: Int?
    private var lastSnr: Float?
    private var liveCounts: [String: Int] = [:]
    private var backlogCounts: [String: Int] = [:]
    private var podIdMismatches = 0
    private var lastMismatchPodId: UInt32?
    /// Start of the current wait for the paired pod's HEALTH (session start, pairing, restore).
    private var quietWatchSinceMs: Int64 = 0
    private let steering = BtrxSteering()
    private let imuCal = BtrxImuCal()
    private lazy var buttonConfirm = BtrxButtonConfirm(log: log)
    /// The panel host is on screen (app in front); set by the UI.
    private var buttonPanelAvailable = false
    /// The Desk Pod's build from its own HEALTH, when no ROLE_REPORT gave it.
    private var deskHealthBuild: Int?
    private var deskResetAtMs: Int64?
    private let power = BtrxDeskPodPower()

    /// Whether this app keeps the Desk Pod asking before its button acts (the keepalive). Same as Android.
    public enum ButtonConfirmLink: Sendable, Equatable {
        /// Keepalive every 5 s: the Desk Pod asks here first.
        case on
        /// No ready link to the Desk Pod.
        case noLink
        /// The Desk Pod reports a build before 0x0108 (or none yet): its button acts at once.
        case oldFirmware
        /// The app is not in front, so it cannot show the panel: the button acts at once.
        case notInFront
    }

    public init(log: BtrxLogger, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }, onPaired: @escaping (PairedPod) -> Void = { _ in },
                onSavedIgnores: @escaping ([Int]) -> Void = { _ in }) {
        self.log = log
        self.clock = clock
        self.onPaired = onPaired
        self.onSavedIgnores = onSavedIgnores
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
        deskPhoneLost = nil
        deskHealthExt = nil
        deskUptimeS = nil; deskHealthAtMs = 0
        droppedEncrypted = 0; droppedOtherPort = 0
        liveCounts = [:]; backlogCounts = [:]
        detectTimes.removeAll()
        deskHealthBuild = nil
        buttonConfirm.newSession()
        quietWatchSinceMs = clock()
        power.onSessionStarted()
    }

    /// The Desk Pod's own standard Meshtastic DeviceMetrics (node_info or TELEMETRY_APP).
    public func onDeskDeviceMetrics(_ metrics: MeshtasticPhoneAPI.DeviceMetrics, source: String) {
        power.onDeviceMetrics(metrics, source: source, nowMs: clock())
    }

    public func detach(_ sender: BtrxFrameSender, reason: String) {
        guard self.sender === sender else { return }
        self.sender = nil
        log.debug(linkDropLine(reason: reason, nowMs: clock()))
        if let line = power.onDrop(reason: reason, nowMs: clock()) { log.warn(line) }
        if Self.activeSteps.contains(pairing.step) { fail(pairing.step, "The link to the Desk Pod was lost (\(reason)).") }
        if !pendingFilters.isEmpty { filterMessage = "No answer from the Drone Pod: the link to the Desk Pod was lost." }
        pendingFilters.removeAll()
        imuCal.linkLost(reason: reason)
        buttonConfirm.linkLost(nowMs: clock(), reason: reason)
    }

    /// Logged when a session ends (Nov 16: tell a Desk Pod reboot from a radio drop): the
    /// Desk Pod's and the Drone Pod's last HEALTH uptime and age. Same text as Android.
    private func linkDropLine(reason: String, nowMs: Int64) -> String {
        let desk = deskUptimeS.map { "Desk Pod last HEALTH uptime_s=\($0), \((nowMs - deskHealthAtMs) / 1000) s ago" }
            ?? "no Desk Pod HEALTH this session"
        let pod = lastHealth.map { "Drone Pod last HEALTH uptime_s=\($0.health.uptimeS), \((nowMs - $0.arrivalMs) / 1000) s ago" }
            ?? "no Drone Pod HEALTH"
        return "link drop (\(reason)): \(desk); \(pod)"
    }

    public func restorePairedPod(_ pod: PairedPod?, savedIgnoreIds: [Int] = []) {
        if pod != pairedPod { imuCal.reset() } // another pod: its calibration state is not known
        pairedPod = pod
        savedIgnores = pod == nil ? [] : Set(savedIgnoreIds.filter { (1...0xFFFF).contains($0) })
        pendingFilters.removeAll(); filterMessage = nil
        quietWatchSinceMs = clock()
    }

    /// "Replace Drone Pod" (after the operator confirmed): unpair the current pod in this app,
    /// then start pairing. Pod side (firmware 0x0102, btrx af2549e): there is no unpair message.
    /// A pod drops its pairing only on PAIR_CONFIRM with confirm=0, which it takes only inside
    /// its own nod-opened pair window with that window's nonce, or on a factory reset. So the
    /// old pod stays confirmed with its pod_id and keeps sending; this app ignores it from now
    /// on, and pairing the same pod again simply gives it a new pod_id. Same as Android.
    public func replacePairedPod() {
        let old = pairedPod
        forgetPairedPod()
        if let old {
            log.debug(String(format: "pairing: Drone Pod %@ pod_id=%08x unpaired in this app to replace it; ", BtrxClient.nodeName(old.node), old.podId)
                + "the pod keeps its own pairing until it is paired again or factory reset")
        }
        startPairing()
    }

    public func forgetPairedPod() {
        if !savedIgnores.isEmpty {
            // The old pod keeps its list in flash; this app no longer knows it is paired to it.
            log.debug("own aircraft (saved): forgetting \(savedIgnores.count) saved id(s) with the unpaired pod")
            savedIgnores.removeAll(); onSavedIgnores([])
        }
        pendingFilters.removeAll(); filterMessage = nil
        pairedPod = nil; lastHealth = nil; otherPods.removeAll()
        podIdMismatches = 0; lastMismatchPodId = nil
        steering.reset()
        imuCal.reset()
    }

    // MARK: client listener

    public func onConfigured(localNode node: UInt32) { localNode = node }
    /// A non-btrx packet the client dropped (encrypted for another channel, or another port).
    public func onPacketDropped(encrypted: Bool) { if encrypted { droppedEncrypted += 1 } else { droppedOtherPort += 1 } }
    public func onLocalRoleReport(_ report: BtrxProtocol.RoleReport) { role = report }

    public func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta) {
        if meta.backlog { backlogCounts[frame.typeName, default: 0] += 1 } else { liveCounts[frame.typeName, default: 0] += 1 }
        let fromDesk = localNode == nil || meta.fromNode == localNode
        do {
            switch frame.type {
            case BtrxProtocol.DESK_PAIR: if fromDesk { try onDeskPair(frame, meta) }
            // Desk Pod button confirmation (0x0108): live asks from the Desk Pod itself only.
            case BtrxProtocol.CONFIRM_REQ: if fromDesk && !meta.backlog { onConfirmReq(try BtrxProtocol.parseConfirmReq(frame.payload)) }
            // Pairing frames come from whichever pod is being paired, so they are not filtered.
            case BtrxProtocol.PAIR_REQ: onPairReq(try BtrxProtocol.parsePairReq(frame.payload), meta)
            case BtrxProtocol.PAIR_ACK: onPairAck(try BtrxProtocol.parsePairAck(frame.payload), meta)
            // The Desk Pod's own HEALTH (sent to its app from build 0x0104): only phone_lost is shown.
            case BtrxProtocol.HEALTH where localNode != nil && meta.fromNode == localNode:
                if !meta.backlog {
                    let h = try BtrxProtocol.parseHealth(frame.payload)
                    if let lost = h.phoneLost { deskPhoneLost = lost }
                    if let e = h.ext { deskHealthExt = e }
                    deskUptimeS = h.uptimeS
                    deskHealthBuild = h.buildHash
                    deskHealthAtMs = meta.arrivalMs
                    if let line = power.onDeskHealth(uptimeS: Int64(h.uptimeS), resetReason: h.ext?.resetReason, arrivalMs: meta.arrivalMs) {
                        log.warn(line)
                    }
                }
            default: if meta.fromNode != localNode && acceptsPodTraffic(frame, meta) { try onPodTraffic(frame, meta) }
            }
        } catch {
            // Logged with decode-error by BtrxFrameLog; nothing to act on.
        }
    }

    /// Live frames only; once paired, only the paired node (pod_id 0 or the paired one).
    private func acceptsPodTraffic(_ frame: BtrxProtocol.Frame, _ meta: BtrxRadioMeta) -> Bool {
        if meta.backlog { return false }
        guard let paired = pairedPod else { return true }
        if meta.fromNode != paired.node {
            otherPods.insert(meta.fromNode)
            return false
        }
        // Frames queued before pairing still carry pod_id 0 from the right node; accept by node.
        if frame.podId == 0 || frame.podId == paired.podId { return true }
        podIdMismatches += 1
        if frame.podId != lastMismatchPodId || podIdMismatches % Self.mismatchLogEvery == 0 {
            log.warn(String(format: "paired node %@ sending pod_id %08x (paired as %08x, %d frame(s) refused); re-pair",
                            BtrxClient.nodeName(paired.node), frame.podId, paired.podId, podIdMismatches))
        }
        lastMismatchPodId = frame.podId
        return false
    }

    private func onPodTraffic(_ frame: BtrxProtocol.Frame, _ meta: BtrxRadioMeta) throws {
        lastRssi = meta.rxRssi; lastSnr = meta.rxSnr
        switch frame.type {
        case BtrxProtocol.HEALTH:
            lastHealth = HealthReading(node: meta.fromNode, arrivalMs: meta.arrivalMs, rxRssi: meta.rxRssi, rxSnr: meta.rxSnr,
                                       health: try BtrxProtocol.parseHealth(frame.payload))
        case BtrxProtocol.DETECT:
            detectTimes.append(meta.arrivalMs)
            lastDetectMs = meta.arrivalMs
            trimDetects(meta.arrivalMs)
        // The pod sends STEER only once confirmed; before pairing it is not ours to steer by.
        case BtrxProtocol.STEER:
            if pairedPod != nil {
                let st = try BtrxProtocol.parseSteer(frame.payload)
                if steering.onSteer(st, nowMs: meta.arrivalMs) {
                    log.info("steering: window_end_ms went back to \(st.windowEndMs): the Drone Pod restarted; src ids, colors and mutes reset")
                }
                imuCal.onSteer(imuOk: st.imuOk, imuCal: st.imuCal)
            }
        case BtrxProtocol.FILTER_ACK:
            if pairedPod != nil { onFilterAck(try BtrxProtocol.parseFilterAck(frame.payload), frameFlags: frame.flags) }
        // Only from the paired pod: an answer before pairing is not ours.
        case BtrxProtocol.IMU_CAL_ACK:
            if pairedPod != nil {
                let ack = try BtrxProtocol.parseImuCalAck(frame.payload)
                if imuCal.onAck(ack) {
                    log.debug("imu cal: \(BtrxProtocol.imuCalOpName(ack.op)) tag=\(ack.tag) answered: \(BtrxProtocol.imuCalStatusName(ack.status))")
                } else {
                    log.debug("imu cal: IMU_CAL_ACK tag=\(ack.tag) op=\(BtrxProtocol.imuCalOpName(ack.op)) matches no request out; ignored")
                }
            }
        default: break
        }
    }

    private func onDeskPair(_ frame: BtrxProtocol.Frame, _ meta: BtrxRadioMeta) throws {
        let dp = try BtrxProtocol.parseDeskPair(frame.payload)
        window.onDeskPair(seq: frame.seq, dp, arrivalMs: meta.arrivalMs, backlog: meta.backlog)
        let now = meta.arrivalMs
        if dp.open == 1 {
            if window.openedFromBacklog { log.debug("pairing: Desk Pod window open from a tap that arrived with the connect backlog (\(window.secondsLeft(nowMs: now)) s left)") }
            // A new tap while waiting for the nod restarts the countdown on the new window.
            if pairing.step == .waitButton || pairing.step == .waitNod {
                enterWaitNod(now)
            } else if pairing.step == .podFound && pairing.podStale && !meta.backlog {
                // A live tap while the found pod is stale starts over at the nod (any pod may answer).
                log.debug("pairing: new Desk Pod tap while \(pairing.candidate.map { BtrxClient.nodeName($0.node) } ?? "nil") is stale; waiting for a nod")
                pairing.candidate = nil
                pairing.podStale = false
                enterWaitNod(now)
            }
        } else {
            switch pairing.step {
            case .waitNod: fail(.waitNod, "The Desk Pod closed its pairing window before a Drone Pod asked to pair. Tap the Desk Pod button and nod the drone pod again.")
            // .podFound: the pod's own window decides (see onPairReq / tick); the Desk Pod's is advisory.
            default: break
            }
        }
    }

    private func onPairReq(_ req: BtrxProtocol.PairReq, _ meta: BtrxRadioMeta) {
        let now = meta.arrivalMs
        if meta.backlog {
            log.debug("pairing: PAIR_REQ from \(BtrxClient.nodeName(meta.fromNode)) in the connect backlog ignored (stale)")
            return
        }
        let nonce = BtrxProtocol.hex(req.nonce)
        let open = window.isOpen(nowMs: now)
        func newCandidate() {
            if pairing.repair, let paired = pairedPod, meta.fromNode != paired.node {
                log.debug("pairing: PAIR_REQ from \(BtrxClient.nodeName(meta.fromNode)) ignored: re-pairing \(BtrxClient.nodeName(paired.node)) only")
                return
            }
            guard open else {
                log.debug("pairing: PAIR_REQ from \(BtrxClient.nodeName(meta.fromNode)) ignored: no Desk Pod window open")
                return
            }
            let c = Candidate(node: meta.fromNode, assignedPodId: BtrxProtocol.podId(forDevA: req.devA), devA: req.devA, devB: req.devB,
                              buildA: req.buildA, buildB: req.buildB, imuOk: req.imuOk, roleA: req.roleA, roleB: req.roleB,
                              nonceHex: nonce, rssi: meta.rxRssi, snr: meta.rxSnr, lastRequestMs: now, foundMs: now)
            pairing.step = .podFound
            pairing.candidate = c
            pairing.podStale = false
            pairing.message = askedMessage(c)
            log.debug(String(format: "pairing: PAIR_REQ from %@ dev_a=%08x dev_b=%08x imu_ok=%d; waiting for the operator", BtrxClient.nodeName(c.node), c.devA, c.devB, c.imuOk))
        }
        switch pairing.step {
        case .waitNod:
            newCandidate()
        case .podFound:
            guard var c = pairing.candidate else { return }
            if meta.fromNode == c.node {
                if pairing.podStale { log.debug("pairing: \(BtrxClient.nodeName(c.node)) is asking to pair again") }
                c.nonceHex = nonce; c.rssi = meta.rxRssi; c.snr = meta.rxSnr; c.lastRequestMs = now
                pairing.candidate = c
                pairing.podStale = false
                pairing.message = askedMessage(c)
            } else {
                // Only the same pod may revive a stale candidate; another pod needs a new Desk Pod tap.
                log.debug("pairing: another Drone Pod \(BtrxClient.nodeName(meta.fromNode)) also asked to pair; ignored"
                         + (pairing.podStale ? " (tap the Desk Pod button to pair a different pod)" : ""))
            }
        case .confirming:
            guard let c = pairing.candidate else { return }
            if meta.fromNode == c.node && nonce == c.nonceHex && pairing.confirmTries < Self.maxConfirmTries {
                pairing.confirmTries += 1
                log.debug("pairing: PAIR_REQ repeated; answering again (try \(pairing.confirmTries))")
                sendConfirm(c)
            }
        default:
            if !open { log.debug("pairing: PAIR_REQ from \(BtrxClient.nodeName(meta.fromNode)) ignored: no Desk Pod window open") }
        }
    }

    private func askedMessage(_ c: Candidate) -> String {
        "Drone Pod \(BtrxClient.nodeName(c.node)) asked to pair. Confirm it is the pod in your hand."
    }

    private func onPairAck(_ ack: BtrxProtocol.PairAck, _ meta: BtrxRadioMeta) {
        guard !meta.backlog, pairing.step == .confirming, let c = pairing.candidate, meta.fromNode == c.node else { return }
        pairing.aOk = ack.aOk; pairing.bOk = ack.bOk; pairing.ackFlags = ack.flags
        if ack.aOk == 1 {
            let pod = PairedPod(node: c.node, podId: c.assignedPodId, devA: c.devA, devB: c.devB)
            if pairedPod?.node != pod.node {
                steering.reset()
                if !savedIgnores.isEmpty { savedIgnores.removeAll(); onSavedIgnores([]) }
            }
            if pairedPod != pod { imuCal.reset() }
            pairedPod = pod
            podIdMismatches = 0; lastMismatchPodId = nil
            quietWatchSinceMs = meta.arrivalMs
            pairing.step = .paired
            pairing.failedAt = nil
            pairing.message = ack.bOk != 1 ? "Paired, but the port board did not answer. Check the cable between the boards."
                : pairing.repair ? "Drone Pod re-paired." : "Drone Pod paired."
            log.debug(String(format: "pairing: PAIR_ACK a_ok=%d b_ok=%d; paired %@ pod_id=%08x", ack.aOk, ack.bOk, BtrxClient.nodeName(c.node), c.assignedPodId))
            deskResetAtMs = nil
            onPaired(pod)
        } else {
            fail(.confirming, "The Drone Pod did not take the pairing (a_ok=0, flags=\(BtrxProtocol.statusFlagNames(ack.flags))).")
        }
    }

    // MARK: pairing actions

    /// "Pair drone pod". One app pairs exactly one Drone Pod: with a pod already paired this
    /// does nothing; the screen offers ``replacePairedPod()`` instead. Same as Android.
    public func startPairing() {
        let now = clock()
        if let p = pairedPod {
            log.debug("pairing: not started: Drone Pod \(BtrxClient.nodeName(p.node)) is paired; use Replace Drone Pod")
            return
        }
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

    /// "Re-pair Drone Pod" (after the operator confirmed): a paired pod ignores nods from build
    /// 0x0106, so PAIR_OPEN (0x16) to it opens its pairing window, and DESK_PAIR to the Desk Pod
    /// opens the Desk Pod's (its phone-link bench-test path, answered by DESK_PAIR open=1). Then
    /// the normal flow, only the paired node accepted. A paired pod on an older build (HEALTH
    /// says so) gets no PAIR_OPEN: it still opens its window on a nod. Nothing is unpaired: if
    /// no confirm happens the pod stays paired as it was, here and on the pod. Same as Android.
    public func repairDronePod(seconds: Int? = nil) {
        let now = clock()
        if Self.activeSteps.contains(pairing.step) {
            log.debug("pairing: re-pair not started: pairing already in progress at \(pairing.step)")
            return
        }
        guard let pod = pairedPod else {
            pairing = Pairing(); pairing.repair = true
            pairing.step = .failed; pairing.failedAt = .waitButton; pairing.message = Self.repairNotPaired
            return
        }
        guard let s = sender, s.isReady, let local = localNode else {
            pairing = Pairing(); pairing.repair = true
            pairing.step = .failed; pairing.failedAt = .waitButton; pairing.message = "The Desk Pod is not connected. \(Self.repairKept)"
            return
        }
        let build = lastHealth.flatMap { $0.node == pod.node ? $0.health.buildHash : nil }
        let pairOpen = build == nil || build! >= BtrxProtocol.buildStableIds
        if pairOpen {
            log.info(String(format: "pairing: re-pair: PAIR_OPEN seconds=%@ to %@ pod_id=%08x, DESK_PAIR to the Desk Pod",
                            seconds.map { String($0) } ?? "default", BtrxClient.nodeName(pod.node), pod.podId))
            s.sendFrame(to: pod.node, frame: BtrxProtocol.pairOpenFrame(seq: s.nextSeq(), podId: pod.podId, seconds: seconds))
        } else {
            log.info(String(format: "pairing: re-pair: Drone Pod build 0x%04x has no PAIR_OPEN (nod it); DESK_PAIR to the Desk Pod", build ?? 0))
        }
        s.sendFrame(to: local, frame: BtrxProtocol.deskPairOpenFrame(seq: s.nextSeq()))
        pairing = Pairing()
        pairing.step = .waitButton
        pairing.repair = true
        pairing.repairByNod = !pairOpen
        pairing.message = pairOpen ? Self.repairOpening : Self.repairOpeningNod
        stepDeadlineMs = now + Self.repairWindowWaitMs
        if window.isOpen(nowMs: now) { enterWaitNod(now) }
    }

    public func confirmPod() {
        let now = clock()
        guard pairing.step == .podFound, let c = pairing.candidate else { return }
        if pairing.podStale || now - c.lastRequestMs > Self.podRequestStaleMs {
            markStale(c)
            return
        }
        pairing.step = .confirming
        pairing.confirmTries = 1
        pairing.message = "Waiting for the Drone Pod to acknowledge."
        stepDeadlineMs = now + Self.ackTimeoutMs
        log.debug(String(format: "pairing: PAIR_CONFIRM to %@ nonce=%@ pod_id=%08x", BtrxClient.nodeName(c.node), c.nonceHex, c.assignedPodId))
        sendConfirm(c)
    }

    /// "Cancel pairing": ends the flow. Nothing is sent; the pod's own window runs out.
    public func cancelPairing() {
        guard Self.activeSteps.contains(pairing.step) else { return }
        log.debug("pairing: cancelled by the operator at \(pairing.step)")
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
        case .waitButton:
            if now >= stepDeadlineMs {
                fail(.waitButton, pairing.repair ? "The Desk Pod did not open its pairing window. Tap the Desk Pod button, then try Re-pair again."
                                                 : "The Desk Pod button was not tapped. Tap it, then start again.")
            }
        case .waitNod: if !open { fail(.waitNod, windowEndedMessage("No Drone Pod asked to pair. Nod the drone pod twice within the window.")) }
        case .podFound:
            if let c = pairing.candidate {
                if !pairing.podStale && now - c.lastRequestMs > Self.podRequestStaleMs { markStale(c) }
                if pairing.podStale && (!open || now - c.foundMs > Self.podFoundMaxMs) { fail(.podFound, Self.staleEndedMessage) }
            }
        case .confirming: if now >= stepDeadlineMs { fail(.confirming, "No PAIR_ACK from the Drone Pod within \(Self.ackTimeoutMs / 1000) s. Tap the Desk Pod button and try again.") }
        default: break
        }
        if Self.activeSteps.contains(pairing.step) { pairing.windowSecondsLeft = window.secondsLeft(nowMs: now) }
        for pf in pendingFilters where now - pf.sentMs > Self.filterAckTimeoutMs {
            log.warn(String(format: "own aircraft (saved): no FILTER_ACK for %@ src=0x%04x within %d s", BtrxProtocol.muteOpName(pf.op), pf.srcId, Int(Self.filterAckTimeoutMs / 1000)))
            filterMessage = "No answer from the Drone Pod for \(BtrxDeskPodText.srcIdText(pf.srcId)). Try again."
        }
        pendingFilters.removeAll { now - $0.sentMs > Self.filterAckTimeoutMs }
        if let r = imuCal.tick(nowMs: now) { sendImuCal(r) }
        buttonConfirm.tick(nowMs: now)
        if buttonConfirm.keepaliveDue(nowMs: now, enabled: buttonConfirmLinkNow == .on) {
            sendToDesk { BtrxProtocol.confirmRspFrame(seq: $0, token: 0, answer: BtrxProtocol.CFM_KEEPALIVE) }
        }
    }

    // MARK: Desk Pod button confirmation (firmware 0x0108)

    /// The Desk Pod's build: its ROLE_REPORT, else its own HEALTH; nil before either.
    private var deskBuild: Int? {
        if let r = role, r.role == BtrxProtocol.roleDeskPod { return r.buildHash }
        return deskHealthBuild
    }

    private var buttonConfirmLinkNow: ButtonConfirmLink {
        if link.phase != .ready || !(sender?.isReady ?? false) || localNode == nil { return .noLink }
        if !BtrxProtocol.buttonConfirmSupported(deskBuild) && !buttonConfirm.seenThisSession { return .oldFirmware }
        if !buttonPanelAvailable { return .notInFront }
        return .on
    }

    /// The UI says whether the Confirm/Cancel panel can be seen (app in front, view on screen).
    /// Off: no keepalive and no "shown", so the Desk Pod button acts on its own (3 s after an ask).
    public func setButtonPanelAvailable(_ available: Bool) {
        if available != buttonPanelAvailable { log.info("desk button: panel \(available ? "available" : "not available (app not in front)")") }
        buttonPanelAvailable = available
    }

    private func sendToDesk(_ frame: (Int) -> [UInt8]) {
        guard let s = sender, let local = localNode else { return }
        s.sendFrame(to: local, frame: frame(s.nextSeq()))
    }

    private func onConfirmReq(_ r: BtrxProtocol.ConfirmReq) {
        let now = clock()
        let res = buttonConfirm.onRequest(r, nowMs: now, canShow: buttonPanelAvailable)
        for a in res.answers { sendToDesk { BtrxProtocol.confirmRspFrame(seq: $0, token: a.token, answer: a.answer) } }
        if res.deskReset {
            deskResetAtMs = now
            // It keeps the link but forgets its saved role and pairing: ask what it is now.
            log.warn("desk button: the Desk Pod factory reset; it comes back unpaired. ROLE_GET sent.")
            sendToDesk { BtrxProtocol.roleGetFrame(seq: $0) }
        }
    }

    /// Confirm (true) or Cancel (false) on the Desk Pod button panel.
    public func answerButtonConfirm(_ confirm: Bool) {
        guard let a = buttonConfirm.answer(confirm: confirm, nowMs: clock()) else { return }
        sendToDesk { BtrxProtocol.confirmRspFrame(seq: $0, token: a.token, answer: a.answer) }
    }

    public func snapshot() -> Snapshot {
        let now = clock()
        trimDetects(now)
        var s = Snapshot()
        s.nowMs = now; s.link = link; s.bluetoothPausedForUSB = pausedForUSB; s.localNode = localNode; s.role = role
        s.deskPhoneLost = deskPhoneLost; s.dronePodHealth = lastHealth; s.otherPodNodes = otherPods.count; s.detectsLastMinute = detectTimes.count
        s.lastDetectMs = lastDetectMs; s.lastPacketRssi = lastRssi; s.lastPacketSnr = lastSnr
        s.liveCounts = liveCounts; s.backlogCounts = backlogCounts
        s.droppedEncrypted = droppedEncrypted; s.droppedOtherPort = droppedOtherPort
        s.windowOpen = window.isOpen(nowMs: now); s.windowSecondsLeft = window.secondsLeft(nowMs: now); s.windowFromBacklog = window.openedFromBacklog
        var p = pairing
        if Self.activeSteps.contains(p.step) { p.windowSecondsLeft = window.secondsLeft(nowMs: now) }
        s.pairing = p
        s.pairedPod = pairedPod
        s.pairedPodIdMismatches = podIdMismatches; s.lastMismatchPodId = lastMismatchPodId
        s.pairedPodQuiet = pairedQuiet(now)
        s.steerOverlay = steeringLinkUp ? steering.view(nowMs: now) : nil
        s.lastSteer = steering.lastView(nowMs: now)
        s.steeringRollCorrection = steering.rollCorrection
        s.imuCal = imuCal.view(nowMs: now)
        s.deskHealthExt = deskHealthExt
        s.savedIgnoreSupported = savedIgnoreSupportedNow
        s.savedIgnores = savedIgnores.sorted()
        s.filterPending = pendingFilters
        s.filterMessage = filterMessage
        s.deskWindowSource = window.source
        s.buttonConfirm = buttonConfirm.view(nowMs: now)
        s.buttonNotice = buttonConfirm.liveNotice(nowMs: now)
        s.buttonLastNotice = buttonConfirm.notice
        s.buttonConfirmLink = buttonConfirmLinkNow
        s.deskResetAtMs = deskResetAtMs
        s.deskSilent = power.silentSinceMs != nil
        s.deskSilentReason = power.silentReason
        s.deskRestartNotice = power.restartNotice(nowMs: now)
        s.deskRestart = power.restart
        s.deskDeviceMetrics = power.metrics
        s.deskDeviceMetricsAtMs = power.metricsAtMs
        return s
    }

    private var savedIgnoreSupportedNow: Bool {
        guard let pod = pairedPod else { return false }
        return BtrxProtocol.savedIgnoreSupported(lastHealth.flatMap { $0.node == pod.node ? $0.health.buildHash : nil })
    }

    // MARK: own aircraft (saved): STEER_MUTE op 3 / 4, FILTER_ACK (firmware 0x0106)

    /// "Own aircraft (saved)": the pod puts the address behind `srcId` on its saved ignore list.
    public func ignoreSourceSaved(_ srcId: Int) { filterAction(op: BtrxProtocol.MUTE_IGNORE, srcId: srcId) }

    /// "Remove": the pod takes the address behind `srcId` off its saved ignore list.
    public func unignoreSourceSaved(_ srcId: Int) { filterAction(op: BtrxProtocol.MUTE_UNIGNORE, srcId: srcId) }

    private func filterAction(op: Int, srcId: Int) {
        let now = clock()
        let busy = pendingFilters.contains { $0.srcId == srcId && now - $0.sentMs <= Self.filterAckTimeoutMs }
        var blocked: String?
        if pairedPod == nil { blocked = "No Drone Pod is paired." }
        else if !savedIgnoreSupportedNow { blocked = "The Drone Pod's firmware does not save ignored sources (needs build 0x0106). Use Own aircraft on this device." }
        else if !(sender?.isReady ?? false) { blocked = "The Desk Pod is not connected." }
        else if !(1...0xFFFF).contains(srcId) { blocked = "That source has no Drone Pod id yet." }
        else if busy { blocked = "Waiting for the Drone Pod to answer the last change to \(BtrxDeskPodText.srcIdText(srcId))." }
        else if pendingFilters.count >= Self.maxPendingFilters { blocked = "Waiting for the Drone Pod to answer earlier changes." }
        if let blocked {
            log.warn(String(format: "own aircraft (saved): %@ src=0x%04x not sent: %@", BtrxProtocol.muteOpName(op), srcId, blocked))
            filterMessage = blocked
            return
        }
        guard let pod = pairedPod, let s = sender else { return }
        log.info(String(format: "own aircraft (saved): STEER_MUTE %@ src=0x%04x to %@", BtrxProtocol.muteOpName(op), srcId, BtrxClient.nodeName(pod.node)))
        s.sendFrame(to: pod.node, frame: BtrxProtocol.steerMuteFrame(seq: s.nextSeq(), podId: pod.podId, op: op, srcId: srcId))
        pendingFilters.removeAll { $0.srcId == srcId } // an expired one not yet ticked out
        pendingFilters.append(PendingFilter(op: op, srcId: srcId, sentMs: now))
        filterMessage = op == BtrxProtocol.MUTE_IGNORE ? "Saving on the Drone Pod…" : "Removing from the Drone Pod…"
    }

    private func onFilterAck(_ ack: BtrxProtocol.FilterAck, frameFlags: Int) {
        typealias P = BtrxProtocol
        let wantOp: Int? = ack.op == P.FILTER_OP_ADD ? P.MUTE_IGNORE : ack.op == P.FILTER_OP_REMOVE ? P.MUTE_UNIGNORE : nil
        let idx: Int?
        if wantOp == nil || ack.srcId == 0 { idx = nil }           // op 0 replace / 0x0107 FILTER_LIST answer: not ours
        else if let sid = ack.srcId { idx = pendingFilters.firstIndex { $0.srcId == sid && $0.op == wantOp } }   // 0x0107: by echoed id
        else { idx = pendingFilters.firstIndex { $0.op == wantOp } } // 0x0106: oldest waiting request of that op
        guard let idx else {
            log.debug("own aircraft (saved): FILTER_ACK op=\(ack.op) ok=\(ack.ok) stored=\(ack.stored) src_id=\(ack.srcId.map { String(format: "0x%04x", $0) } ?? "none") matches no request out; ignored")
            return
        }
        let pend = pendingFilters.remove(at: idx)
        let id = BtrxDeskPodText.srcIdText(pend.srcId)
        log.info(String(format: "own aircraft (saved): FILTER_ACK op=%d ok=%d list_crc=0x%04x stored=%d flags=0x%02x for src=0x%04x (%@)",
                        ack.op, ack.ok, ack.listCrc, ack.stored, frameFlags, pend.srcId, ack.srcId != nil ? "by src_id" : "6-byte ack, oldest waiting"))
        let added = pend.op == P.MUTE_IGNORE
        if ack.ok == 1 {
            let changed = added ? savedIgnores.insert(pend.srcId).inserted : savedIgnores.remove(pend.srcId) != nil
            if changed { onSavedIgnores(savedIgnores.sorted()) }
            filterMessage = added ? "Saved \(id) on the Drone Pod (\(ack.stored) of \(Self.filterListMax) saved)."
                : "Removed \(id) from the Drone Pod (\(ack.stored) of \(Self.filterListMax) saved)."
        } else if added {
            filterMessage = "The Drone Pod did not save \(id): it saves only a source heard in the last 60 s, and holds \(Self.filterListMax)."
        } else if ack.srcId == nil {
            // 0x0106: op 4 resolves the id in its live table only; the saved row stays (Remove again on 0x0107).
            filterMessage = "The Drone Pod no longer tracks \(id), so it could not remove it; that needs Drone Pod firmware 0x0107. It stays in this list."
        } else {
            // 0x0107: op 4 removes every saved entry ignored under that id; ok=0 means none was on the pod.
            if savedIgnores.remove(pend.srcId) != nil { onSavedIgnores(savedIgnores.sorted()) }
            filterMessage = "The Drone Pod had nothing saved under \(id); removed from this list."
        }
    }

    private var steeringLinkUp: Bool { pairedPod != nil && link.phase == .ready && sender != nil }

    // MARK: steering actions

    /// "Mute": STEER_MUTE op 1 for the current steering source, to the paired pod.
    public func muteSteeringSource() {
        guard let pod = pairedPod else { log.warn("steering: Mute not sent: no paired Drone Pod"); return }
        guard let v = steering.view(nowMs: clock()), v.canMute else { log.warn("steering: Mute not sent: no current steering source"); return }
        guard let s = sender, s.isReady else { log.warn("steering: Mute not sent: the Desk Pod is not connected"); return }
        log.info("steering: STEER_MUTE mute src=\(v.srcId) to \(BtrxClient.nodeName(pod.node))")
        s.sendFrame(to: pod.node, frame: BtrxProtocol.steerMuteFrame(seq: s.nextSeq(), podId: pod.podId, op: BtrxProtocol.MUTE_ADD, srcId: v.srcId))
    }

    // MARK: bearing map layer (docs/btrx/BEARING-LAYER-spec.md section 7)

    public static let bearingFromOther = 0, bearingFromPod = 1, bearingFromDesk = 2

    /// Where a frame comes from, for the bearing layer: the paired Drone Pod's node (backlog
    /// included: the layer judges frames by window time), the Desk Pod itself (its own
    /// HEALTH), or neither. Same as Android's `bearingOrigin`.
    public func bearingOrigin(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta) -> Int {
        if let local = localNode, meta.fromNode == local { return Self.bearingFromDesk }
        guard let paired = pairedPod, meta.fromNode == paired.node else { return Self.bearingFromOther }
        return frame.podId == 0 || frame.podId == paired.podId ? Self.bearingFromPod : Self.bearingFromOther
    }

    /// TIME_PING to the paired Drone Pod (spec 3.2). False with no paired pod or no ready link.
    @discardableResult
    public func sendTimePing(pingSeq: Int, t1Ms: UInt32) -> Bool {
        guard let pod = pairedPod, let s = sender, s.isReady else { return false }
        s.sendFrame(to: pod.node, frame: BtrxProtocol.timePingFrame(seq: s.nextSeq(), podId: pod.podId, pingSeq: pingSeq, t1Ms: t1Ms))
        return true
    }

    /// Debug switch on the Desk Pod screen; off by default until the bench roll check.
    public func setSteeringRollCorrection(_ on: Bool) {
        if steering.rollCorrection != on { log.info("steering: roll correction \(on ? "on" : "off") (debug)") }
        steering.rollCorrection = on
    }

    // MARK: IMU calibration actions (design doc section 29.3)

    /// "Calibrate" / "Start again": the level step at once.
    public func startImuCal() { imuCalAction { imuCal.start(nowMs: clock()) } }

    /// "Start starboard step" / "Retry starboard step": the countdown, then the starboard-down step (from ``tick()``).
    public func startImuCalStarboard() {
        if let blocked = imuCalBlocked {
            log.warn("imu cal: starboard step not started: \(blocked)")
            imuCal.notSent(nil, reason: blocked)
        } else if imuCal.startStarboard(nowMs: clock()) {
            log.debug("imu cal: starboard step countdown")
        }
    }

    /// "Check": which calibration the pod uses.
    public func checkImuCal() { imuCalAction { imuCal.check(nowMs: clock()) } }

    /// "Clear saved calibration": back to the mounting set in the pod's build.
    public func clearImuCal() { imuCalAction { imuCal.clear(nowMs: clock()) } }

    /// "Cancel calibration": ends the flow; nothing is sent.
    public func cancelImuCal() {
        if BtrxImuCal.flowSteps.contains(imuCal.currentStep) { log.debug("imu cal: cancelled by the operator at \(imuCal.currentStep.rawValue)") }
        imuCal.cancel()
    }

    /// "Close": ends a finished calibration result.
    public func closeImuCalResult() { imuCal.close() }

    private func imuCalAction(_ start: () -> BtrxImuCal.Request?) {
        if let blocked = imuCalBlocked {
            log.warn("imu cal: not sent: \(blocked)")
            imuCal.notSent(nil, reason: blocked)
        } else if let r = start() {
            sendImuCal(r)
        }
    }

    /// Why IMU_CAL cannot go out now, or nil.
    private var imuCalBlocked: String? {
        if pairedPod == nil { return "No Drone Pod is paired. Pair it first." }
        if !(sender?.isReady ?? false) { return "The Desk Pod is not connected." }
        return nil
    }

    private func sendImuCal(_ r: BtrxImuCal.Request) {
        guard let pod = pairedPod, let s = sender, s.isReady else {
            let reason = imuCalBlocked ?? "No Drone Pod is paired."
            log.warn("imu cal: \(BtrxProtocol.imuCalOpName(r.op)) not sent: \(reason)")
            imuCal.notSent(r, reason: reason)
            return
        }
        log.debug("imu cal: IMU_CAL \(BtrxProtocol.imuCalOpName(r.op)) tag=\(r.tag) to \(BtrxClient.nodeName(pod.node))")
        s.sendFrame(to: pod.node, frame: BtrxProtocol.imuCalFrame(seq: s.nextSeq(), podId: pod.podId, op: r.op, tag: r.tag))
    }

    /// "Clear mutes": STEER_MUTE op 0, to the paired pod.
    public func clearSteeringMutes() {
        guard let pod = pairedPod else { log.warn("steering: Clear mutes not sent: no paired Drone Pod"); return }
        guard let s = sender, s.isReady else { log.warn("steering: Clear mutes not sent: the Desk Pod is not connected"); return }
        log.info("steering: STEER_MUTE clear to \(BtrxClient.nodeName(pod.node))")
        s.sendFrame(to: pod.node, frame: BtrxProtocol.steerMuteFrame(seq: s.nextSeq(), podId: pod.podId, op: BtrxProtocol.MUTE_CLEAR))
    }

    private func pairedQuiet(_ now: Int64) -> Bool {
        guard let pod = pairedPod, link.phase == .ready, sender != nil else { return false }
        let lastHeard = lastHealth.flatMap { $0.node == pod.node ? $0.arrivalMs : nil } ?? 0
        return now - max(lastHeard, quietWatchSinceMs) > Self.pairedQuietMs
    }

    // MARK: internals

    private func enterWaitNod(_ now: Int64) {
        pairing.step = .waitNod
        pairing.windowSecondsLeft = window.secondsLeft(nowMs: now)
        pairing.message = pairing.repair && !pairing.repairByNod ? "Waiting for the Drone Pod to ask to pair." : "Nod the drone pod twice."
        log.debug("pairing: Desk Pod window open, \(window.secondsLeft(nowMs: now)) s left; waiting for PAIR_REQ")
    }

    private func markStale(_ c: Candidate) {
        if !pairing.podStale { log.debug("pairing: no PAIR_REQ from \(BtrxClient.nodeName(c.node)) for over \(Self.podRequestStaleMs / 1000) s; asking for another nod") }
        pairing.podStale = true
        pairing.message = Self.staleMessage
    }

    private func windowEndedMessage(_ fallback: String) -> String {
        window.lastClose == .closedByDeskPod ? "The Desk Pod closed its pairing window. \(fallback)" : "The pairing window ran out. \(fallback)"
    }

    private func fail(_ at: PairStep, _ message: String) {
        let m = pairing.repair && pairedPod != nil && !message.contains(Self.repairKept) ? "\(message) \(Self.repairKept)" : message
        log.warn("pairing failed at \(at): \(m)")
        pairing.step = .failed
        pairing.failedAt = at
        pairing.message = m
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
