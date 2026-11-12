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
}

public extension BtrxClientListener {
    func onConfigured(localNode: UInt32) {}
    func onLocalRoleReport(_ report: BtrxProtocol.RoleReport) {}
    func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta) {}
    func onVersionRefused(fromNode: UInt32, version: Int) {}
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
    private var otherPorts = 0

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
        let other = otherPorts == 0 ? "" : " other-port packets=\(otherPorts)"
        return "frames: \(frames)\(backlog)\(rejects)\(other)"
    }

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
        case .packet(let p): onPacket(p)
        case .other: break
        }
    }

    private func onPacket(_ packet: MeshtasticPhoneAPI.Packet) {
        guard packet.portnum == BtrxProtocol.privateAppPort, let payload = packet.payload else {
            otherPorts += 1
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
        log.info("rx \(radio) \(BtrxFrameLog.format(frame))")
        if frame.type == BtrxProtocol.ROLE_REPORT && packet.from == localNode && backlog {
            log.info("stale ROLE_REPORT in the connect backlog ignored; waiting for the answer to this session's ROLE_GET")
        } else if frame.type == BtrxProtocol.ROLE_REPORT && packet.from == localNode, let report = try? BtrxProtocol.parseRoleReport(frame.payload) {
            localRole = report
            if report.role == BtrxProtocol.roleDeskPod {
                let build = BtrxProtocol.supportedBuilds.contains(report.buildHash) ? "supported" : "not in the supported list"
                log.info(String(format: "Desk Pod confirmed: role=3 build_hash=0x%04x (%@)", report.buildHash, build))
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
