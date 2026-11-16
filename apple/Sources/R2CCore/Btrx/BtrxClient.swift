import Foundation

/// What ``BtrxDeskPodModel`` needs from the current link's ``BtrxClient`` to send frames.
public protocol BtrxFrameSender: AnyObject {
    var isReady: Bool { get }
    func nextSeq() -> Int
    /// One btrx frame on port 256 to `to`, hop_limit 0. Never blocks.
    func sendFrame(to: UInt32, frame: [UInt8])
}

public protocol BtrxLogger: AnyObject {
    func info(_ message: String)
    func warn(_ message: String)
    /// Per-frame detail that arrives every second (STEER).
    func debug(_ message: String)
}

public extension BtrxLogger {
    /// Info unless the app logs debug separately.
    func debug(_ message: String) { info(message) }
}

/// Arrival details from the Desk Pod for one btrx frame. `backlog` is true for frames the
/// Desk Pod queued while R2C was away (Meshtastic keeps the oldest 32 and drops new ones
/// when full, so they can be minutes old): over Bluetooth, everything read in the first
/// drain after config_complete_id, up to the first empty FromRadio read; over serial,
/// everything up to the first ``BtrxClient/serialQuietGapMs`` gap after config_complete_id.
/// Display-only: live health, RSSI, the Desk Pod's
/// role and clue code must refuse them.
public struct BtrxRadioMeta: Sendable, Equatable {
    public let fromNode: UInt32, toNode: UInt32, rxRssi: Int, rxSnr: Float, arrivalMs: Int64, backlog: Bool
    public init(fromNode: UInt32, toNode: UInt32, rxRssi: Int, rxSnr: Float, arrivalMs: Int64, backlog: Bool) {
        self.fromNode = fromNode; self.toNode = toNode; self.rxRssi = rxRssi; self.rxSnr = rxSnr; self.arrivalMs = arrivalMs; self.backlog = backlog
    }
}

public protocol BtrxClientListener: AnyObject {
    func onConfigured(localNode: UInt32)
    func onLocalRoleReport(_ report: BtrxProtocol.RoleReport)
    func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta)
    func onVersionRefused(fromNode: UInt32, version: Int)
    /// A packet that is not a decoded btrx (port 256) packet was dropped; `encrypted`: the node could not decode it.
    func onPacketDropped(encrypted: Bool)
}

public extension BtrxClientListener {
    func onConfigured(localNode: UInt32) {}
    func onLocalRoleReport(_ report: BtrxProtocol.RoleReport) {}
    func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta) {}
    func onVersionRefused(fromNode: UInt32, version: Int) {}
    func onPacketDropped(encrypted: Bool) {}
}

/// Desk Pod client: the Meshtastic phone-API handshake, send/receive on app port 256,
/// every btrx frame decoded into the log, and ROLE_GET to the Desk Pod itself.
/// Same as Android's BtrxClient.kt; keep them matching. Not thread-safe: the Bluetooth
/// link confines it to its own serial queue.
public final class BtrxClient: BtrxFrameSender {
    public enum Framing: Sendable { case serial, packet }
    public enum State: Sendable { case idle, configuring, ready, closed }

    /// The Desk Pod's ATT MTU is 247 and it rejects long writes: 247 - 3.
    public static let maxToRadioBLE = 244
    /// Largest btrx frame R2C sends; leaves room for the ToRadio/MeshPacket wrapper.
    public static let maxSendFrame = 200
    /// Below this ATT MTU even PAIR_CONFIRM's ToRadio does not fit comfortably.
    public static let minUsableMTU = 100
    /// Serial: the connect backlog ends at the first gap this long with no bytes.
    public static let serialQuietGapMs: Int64 = 300
    /// ROLE_REPORT answer timeout, counted from this session's ROLE_GET.
    public static let roleTimeoutMs: Int64 = 10_000
    /// How often the link checks the role wait.
    public static let rolePollMs: Int64 = 1_000
    /// No config_complete_id this long after want_config_id: the Desk Pod is not answering.
    public static let configTimeoutMs: Int64 = 30_000
    /// A connect backlog still draining this long after config_complete is ended (ROLE_GET goes out).
    public static let maxConnectBacklogMs: Int64 = 30_000

    /// What the link should do next while waiting for the Desk Pod's ROLE_REPORT (see ``roleCheck(retried:)``).
    public enum RoleWait: Sendable, Equatable {
        /// The Desk Pod answered.
        case done
        /// Check again after `delayMs`.
        case wait
        /// No answer within ``roleTimeoutMs`` of ROLE_GET: ROLE_GET was sent again.
        case retry
        /// No answer after the retry either.
        case giveUp
        /// No config_complete_id within ``configTimeoutMs``.
        case noConfig
    }

    public struct RoleCheck: Sendable, Equatable {
        public let action: RoleWait
        public let delayMs: Int64
        public init(_ action: RoleWait, _ delayMs: Int64 = 0) { self.action = action; self.delayMs = delayMs }
    }

    public let framing: Framing
    public private(set) var state: State = .idle
    public private(set) var localNode: UInt32?
    public private(set) var localRole: BtrxProtocol.RoleReport?

    private let send: ([UInt8]) -> Void
    private let log: BtrxLogger
    private weak var listener: BtrxClientListener?
    private var rng: any RandomNumberGenerator
    private let clock: () -> Int64
    private var configId: UInt32 = 0
    private var seq = 0
    private var startedMs: Int64 = 0
    private var readyMs: Int64 = 0
    /// When this session's latest ROLE_GET to the Desk Pod went out (nil: none yet).
    private var roleGetSentMs: Int64?
    /// True from config_complete_id until the connect backlog ends. The Desk Pod delivers
    /// its queue right after config_complete. Bluetooth: it ends at the first empty
    /// FromRadio read. Serial: at the first quiet gap of ``serialQuietGapMs``.
    private var firstDrainPending = false
    private var firstDrainFrames = 0
    /// Serial: arrival of the latest bytes while `firstDrainPending`.
    private var lastSerialRxMs: Int64 = 0
    /// Last heartbeat nonce sent; the first one sent is 2 (see ``sendHeartbeat()``).
    private var heartbeatNonce: UInt32 = 1
    /// Largest ToRadio this link may write (Bluetooth: ATT MTU - 3, at most ``maxToRadioBLE``).
    private var maxToRadioBytes = BtrxClient.maxToRadioBLE
    private var decoder: MeshtasticPhoneAPI.StreamDecoder!
    private(set) var frameCounts: [String: Int] = [:]
    private(set) var backlogCounts: [String: Int] = [:]
    private var rejectCounts: [String: Int] = [:]
    private var refusedNodes = Set<UInt32>()
    /// Packets dropped before btrx decoding: encrypted for a channel the Desk Pod cannot read, or another port.
    private var droppedEncrypted = 0
    private var droppedOtherPort = 0
    /// HEALTH/DETECT rx lines written at the session-log level: count per type, and when the last one was.
    private var rateLogged: [Int: Int] = [:]
    private var rateLastMs: [Int: Int64] = [:]
    private var rateSkipped: [Int: Int] = [:]
    private var steerFlags: [UInt32: Int] = [:]

    /// Frame types logged at debug (shown at every app log level): STEER, and the rare
    /// frames that pairing, the role check and IMU calibration turn on. Same as Android.
    /// Frame types logged at debug rate-limited: the first ``rateLogFirst``, then one per ``rateLogIntervalMs``. Same as Android.
    public static let rateLogged: Set<Int> = [BtrxProtocol.HEALTH, BtrxProtocol.DETECT]
    public static let rateLogFirst = 3
    public static let rateLogIntervalMs: Int64 = 10_000
    /// Dropped (non-btrx) packets logged per session; all are counted.
    public static let droppedLogLimit = 5
    public static let alwaysLogged: Set<Int> = [BtrxProtocol.STEER, BtrxProtocol.PAIR_REQ, BtrxProtocol.PAIR_ACK,
                                                BtrxProtocol.DESK_PAIR, BtrxProtocol.ROLE_REPORT, BtrxProtocol.IMU_CAL_ACK]

    /// A packet that is not a decoded port-256 packet. The first ``droppedLogLimit`` of a
    /// session are logged at debug (which reaches the session log) with the MeshPacket
    /// payload_variant, portnum, sender and length; all are counted. Same as Android.
    private func onDropped(_ packet: MeshtasticPhoneAPI.Packet) {
        let encrypted = packet.portnum == nil && packet.encryptedLength != nil
        if encrypted { droppedEncrypted += 1 } else { droppedOtherPort += 1 }
        let n = droppedEncrypted + droppedOtherPort
        if n <= Self.droppedLogLimit {
            log.debug("dropped packet \(n)/\(Self.droppedLogLimit) logged: payload_variant=\(packet.payloadVariant) "
                + "portnum=\(packet.portnum.map(String.init) ?? "-") from=\(Self.nodeName(packet.from)) len=\(packet.payloadLength)"
                + (encrypted ? " (encrypted for a channel the Desk Pod cannot read)" : ""))
        }
        listener?.onPacketDropped(encrypted: encrypted)
    }

    private func logRateLimited(_ type: Int, _ now: Int64, _ line: String) {
        let logged = rateLogged[type] ?? 0
        if logged < Self.rateLogFirst || rateLastMs[type] == nil || now - rateLastMs[type]! >= Self.rateLogIntervalMs {
            let skipped = rateSkipped.removeValue(forKey: type) ?? 0
            rateLogged[type] = logged + 1
            rateLastMs[type] = now
            log.debug(skipped > 0 ? "\(line) (+\(skipped) not logged since the last one)" : line)
        } else {
            rateSkipped[type, default: 0] += 1
            log.info(line)
        }
    }

    /// One line whenever a Drone Pod's STEER flags change (bit 6: IMU calibration in use).
    private func logSteerFlagChange(_ from: UInt32, _ frame: BtrxProtocol.Frame) {
        guard let flags = try? BtrxProtocol.parseSteer(frame.payload).flags else { return }
        let old = steerFlags.updateValue(flags, forKey: from)
        if old != flags {
            log.debug("steer flags from \(Self.nodeName(from)): \(old.map(BtrxProtocol.steerFlagNames) ?? "(first)") -> \(BtrxProtocol.steerFlagNames(flags))"
                + " (imu_cal=\(flags & BtrxProtocol.STEER_F_IMU_CAL != 0 ? "yes" : "no"))")
        }
    }

    public init(send: @escaping ([UInt8]) -> Void, log: BtrxLogger, listener: BtrxClientListener? = nil,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator(), framing: Framing = .packet,
                clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.send = send
        self.log = log
        self.listener = listener
        self.rng = rng
        self.framing = framing
        self.clock = clock
        decoder = MeshtasticPhoneAPI.StreamDecoder(onMessage: { [unowned self] in self.onProto($0) }, onNoise: { [unowned self] skipped in
            self.log.info("serial: skipped \(skipped) byte(s) of non-API output")
        })
    }

    public var isReady: Bool { state == .ready }

    /// Opens the API session: (serial: wake bytes, then) want_config_id.
    public func start() {
        guard state == .idle else { return }
        configId = max(1, UInt32(rng.next() & 0x7FFF_FFFF))
        state = .configuring
        startedMs = clock()
        if framing == .serial { send(MeshtasticPhoneAPI.wakeBytes()) }
        sendToRadio(MeshtasticPhoneAPI.toRadioWantConfig(configId))
        log.info("handshake: want_config_id=\(configId) sent")
    }

    /// USB-style serial bytes (tests and parity).
    public func onBytes(_ data: [UInt8]) {
        guard state != .closed else { return }
        checkQuietGap()
        if firstDrainPending { lastSerialRxMs = clock() }
        decoder.feed(data)
    }

    /// Serial: ends the connect backlog after ``serialQuietGapMs`` without bytes since
    /// config_complete_id (or the burst's last bytes), then sends ROLE_GET.
    public func checkQuietGap() {
        guard framing == .serial, state == .ready, firstDrainPending else { return }
        if clock() - lastSerialRxMs >= Self.serialQuietGapMs { endConnectBacklog("after a \(Self.serialQuietGapMs) ms quiet gap") }
    }

    /// True from config_complete_id until the connect backlog has drained.
    public var connectBacklogPending: Bool { firstDrainPending }

    /// The link's ROLE_REPORT wait, about once a second from session start. The 10 s answer
    /// timeout runs from this session's ROLE_GET, which goes out only when the connect backlog
    /// has drained (a ROLE_REPORT inside the backlog counts as stale). A Desk Pod whose queue
    /// is full (Meshtastic keeps 32) can take 10 s or more to drain, so the wait does not start
    /// at connect. A backlog still draining after ``maxConnectBacklogMs`` is ended here so
    /// ROLE_GET still goes out. `retried`: ROLE_GET was already repeated once. Same as Android.
    public func roleCheck(retried: Bool) -> RoleCheck {
        let now = clock()
        if localRole != nil { return RoleCheck(.done) }
        if state != .ready {
            return now - startedMs >= Self.configTimeoutMs ? RoleCheck(.noConfig) : RoleCheck(.wait, Self.rolePollMs)
        }
        if firstDrainPending {
            if now - readyMs < Self.maxConnectBacklogMs { return RoleCheck(.wait, Self.rolePollMs) }
            log.warn("connect backlog still draining \(Self.maxConnectBacklogMs / 1000) s after config_complete (\(firstDrainFrames) frame(s)); treating what follows as live")
            endConnectBacklog("by the \(Self.maxConnectBacklogMs / 1000) s limit")
            return RoleCheck(.wait, Self.roleTimeoutMs)
        }
        guard let sent = roleGetSentMs else { return RoleCheck(.wait, Self.rolePollMs) }
        let waited = now - sent
        if waited < Self.roleTimeoutMs { return RoleCheck(.wait, Self.roleTimeoutMs - waited) }
        if retried { return RoleCheck(.giveUp) }
        sendRoleGet()
        return RoleCheck(.retry, Self.roleTimeoutMs)
    }

    private func endConnectBacklog(_ how: String) {
        firstDrainPending = false
        log.info("connect backlog drained \(how): \(firstDrainFrames) frame(s) queued while R2C was away")
        sendRoleGet()
    }

    /// Bluetooth: one FromRadio protobuf per characteristic read. Pass every read,
    /// including empty ones: the first empty read after config_complete_id ends the
    /// connect backlog, and only then is ROLE_GET sent, so its ROLE_REPORT is live.
    public func onFromRadio(_ proto: [UInt8]) {
        guard state != .closed else { return }
        if proto.isEmpty {
            if state == .ready && firstDrainPending { endConnectBacklog("at the first empty read") }
            return
        }
        onProto(proto)
    }

    /// Bluetooth: the ATT MTU; ToRadio writes must fit in one ATT write (no long writes).
    public func setAttMtu(_ mtu: Int) {
        maxToRadioBytes = min(Self.maxToRadioBLE, mtu - 3)
        if mtu < Self.minUsableMTU { log.warn("ERROR: Bluetooth MTU \(mtu) is too small for btrx (need at least \(Self.minUsableMTU)); larger ToRadio writes will be refused") }
    }

    @discardableResult
    private func sendToRadio(_ proto: [UInt8]) -> Bool {
        if framing == .packet && proto.count > maxToRadioBytes {
            log.warn("ERROR: ToRadio of \(proto.count) bytes not sent: the Desk Pod accepts at most \(maxToRadioBytes) per Bluetooth write")
            return false
        }
        send(framing == .serial ? MeshtasticPhoneAPI.frame(proto) : proto)
        return true
    }

    /// Keep-alive; the link calls this about every 5 minutes. Each heartbeat carries a
    /// new nonce: Meshtastic drops a ToRadio identical to the previous one. Nonces 0 and 1
    /// are never sent: Meshtastic PhoneAPI treats heartbeat nonce 1 as a trigger to
    /// broadcast node info over LoRa (default hop limit), so the first nonce is 2 and the
    /// counter skips 0 and 1 when it wraps.
    public func sendHeartbeat() {
        guard state == .ready else { return }
        heartbeatNonce = Self.nextHeartbeatNonce(after: heartbeatNonce)
        sendToRadio(MeshtasticPhoneAPI.toRadioHeartbeat(nonce: heartbeatNonce))
    }

    /// Asks a board what it is. `to` nil means the Desk Pod itself (`^local`).
    @discardableResult
    public func sendRoleGet(to: UInt32? = nil) -> Bool {
        guard let dest = to ?? localNode else {
            log.warn("ROLE_GET not sent: the Desk Pod's node number is not known yet")
            return false
        }
        sendFrame(to: dest, frame: BtrxProtocol.roleGetFrame(seq: nextSeq()))
        if dest == localNode { roleGetSentMs = clock() }
        log.info("tx ROLE_GET to \(Self.nodeName(dest))")
        return true
    }

    /// One btrx frame on port 256 (hop_limit 0). Frames over ``maxSendFrame`` bytes are
    /// refused so the ToRadio stays inside one Bluetooth write (chunk FILTER_LIST to fit).
    public func sendFrame(to: UInt32, frame: [UInt8]) {
        guard frame.count <= Self.maxSendFrame else {
            log.warn("ERROR: btrx frame of \(frame.count) bytes not sent to \(Self.nodeName(to)): limit is \(Self.maxSendFrame) (split it)")
            return
        }
        var id = UInt32(truncatingIfNeeded: rng.next())
        if id == 0 { id = 1 }
        sendToRadio(MeshtasticPhoneAPI.toRadioPacket(to: to, packetId: id, portnum: BtrxProtocol.privateAppPort, payload: frame))
    }

    public func nextSeq() -> Int {
        seq = (seq + 1) & 0xFF
        return seq
    }

    public func close() { state = .closed }

    public func summary() -> String {
        func list(_ m: [String: Int]) -> String { m.keys.sorted().map { "\($0)=\(m[$0]!)" }.joined(separator: " ") }
        let frames = frameCounts.isEmpty ? "none" : list(frameCounts)
        let backlog = backlogCounts.isEmpty ? "" : " backlog: " + list(backlogCounts)
        let rejects = rejectCounts.isEmpty ? "" : " rejected: " + list(rejectCounts)
        let dropped = droppedEncrypted + droppedOtherPort == 0 ? "" : " dropped: encrypted=\(droppedEncrypted) other-port=\(droppedOtherPort)"
        return "frames: \(frames)\(backlog)\(rejects)\(dropped)"
    }

    /// Packets dropped before btrx decoding since connecting.
    public func droppedCounts() -> (encrypted: Int, otherPort: Int) { (droppedEncrypted, droppedOtherPort) }

    public func frameCount(_ typeName: String) -> Int { frameCounts[typeName] ?? 0 }
    public func backlogCount(_ typeName: String) -> Int { backlogCounts[typeName] ?? 0 }

    private func onProto(_ proto: [UInt8]) {
        let msg: MeshtasticPhoneAPI.FromRadio
        do { msg = try MeshtasticPhoneAPI.parseFromRadio(proto) } catch {
            log.warn("serial: undecodable FromRadio (\(proto.count) bytes): \((error as? MeshtasticPhoneAPI.DecodeError)?.message ?? "\(error)")")
            return
        }
        switch msg {
        case .myInfo(let node):
            localNode = node
            log.info("handshake: my_info node=\(Self.nodeName(node))")
        case .configComplete(let id):
            guard id == configId else {
                log.info("handshake: config_complete_id=\(id) ignored (want \(configId))")
                return
            }
            state = .ready
            log.info("handshake: config complete, Desk Pod node \(localNode.map(Self.nodeName) ?? "unknown")")
            if let node = localNode { listener?.onConfigured(localNode: node) }
            // Queued frames follow; ask for the role once they are through.
            firstDrainPending = true
            firstDrainFrames = 0
            lastSerialRxMs = clock()
            readyMs = clock()
        case .packet(let p): onPacket(p)
        case .other: break
        }
    }

    private func onPacket(_ packet: MeshtasticPhoneAPI.Packet) {
        guard packet.portnum == BtrxProtocol.privateAppPort, let payload = packet.payload else {
            onDropped(packet)
            return
        }
        let now = clock()
        // Before config_complete_id, or in the burst right after it (Bluetooth: up to the
        // first empty read; serial: up to the first quiet gap).
        let backlog = state != .ready || firstDrainPending
        if backlog && state == .ready { firstDrainFrames += 1 }
        let meta = BtrxRadioMeta(fromNode: packet.from, toNode: packet.to, rxRssi: packet.rxRssi, rxSnr: packet.rxSnr, arrivalMs: now, backlog: backlog)
        let radio = (backlog ? "backlog " : "") + "from=\(Self.nodeName(packet.from)) rssi=\(packet.rxRssi) snr=\(String(format: "%.1f", packet.rxSnr))"
        if refusedNodes.contains(packet.from) {
            rejectCounts["refused-node", default: 0] += 1
            return
        }
        let frame: BtrxProtocol.Frame
        do { frame = try BtrxProtocol.decodeFrame(payload) } catch let e as BtrxProtocol.FrameError {
            rejectCounts[e.reason.rawValue, default: 0] += 1
            if e.reason == .UNSUPPORTED_VERSION {
                refusedNodes.insert(packet.from)
                let v = e.version ?? -1
                let newer = v > BtrxProtocol.protoVersion ? "newer" : "older"
                log.warn("rx \(radio) btrx version \(v) refused (this app understands \(BtrxProtocol.protoVersion)); board firmware is \(newer) than this app; ignoring that board until reconnect")
                listener?.onVersionRefused(fromNode: packet.from, version: v)
            } else {
                log.warn("rx \(radio) bad btrx frame: \(e.message) raw=\(BtrxProtocol.hex(payload))")
            }
            return
        } catch { return }
        if backlog { backlogCounts[frame.typeName, default: 0] += 1 } else { frameCounts[frame.typeName, default: 0] += 1 }
        // STEER (every second) and the rare frames a bench test needs are logged at debug,
        // which shows at every app log level; the rest (DETECT, HEALTH...) at info.
        // HEALTH and DETECT: the first few, then one per type every 10 s, at debug too so
        // they reach the session log; the rest at info as before.
        if Self.alwaysLogged.contains(frame.type) { log.debug("rx \(radio) \(BtrxFrameLog.format(frame))") }
        else if Self.rateLogged.contains(frame.type) { logRateLimited(frame.type, now, "rx \(radio) \(BtrxFrameLog.format(frame))") }
        else { log.info("rx \(radio) \(BtrxFrameLog.format(frame))") }
        if frame.type == BtrxProtocol.STEER { logSteerFlagChange(packet.from, frame) }
        if frame.type == BtrxProtocol.ROLE_REPORT && packet.from == localNode && backlog {
            log.info("stale ROLE_REPORT in the connect backlog ignored; waiting for the answer to this session's ROLE_GET")
        } else if frame.type == BtrxProtocol.ROLE_REPORT && packet.from == localNode, let report = try? BtrxProtocol.parseRoleReport(frame.payload) {
            localRole = report
            if report.role == BtrxProtocol.roleDeskPod {
                let build = BtrxProtocol.buildSupportText(report.buildHash)
                let since = String(format: " %.1f s after connect", locale: Locale(identifier: "en_US_POSIX"), Double(clock() - startedMs) / 1000)
                log.info(String(format: "Desk Pod confirmed: role=3 build_hash=0x%04x (%@)", report.buildHash, build) + since)
            } else {
                log.warn("this board answered role=\(report.role) (\(BtrxProtocol.roleName(report.role))), not a Desk Pod (role=3)")
            }
            listener?.onLocalRoleReport(report)
        }
        listener?.onFrame(frame, meta: meta)
    }

    /// Heartbeat nonce after `previous`: wraps at 32 bits and skips 0 and 1.
    public static func nextHeartbeatNonce(after previous: UInt32) -> UInt32 {
        let next = previous &+ 1
        return next < 2 ? 2 : next
    }

    public static func nodeName(_ node: UInt32) -> String { String(format: "!%08x", node) }
}
