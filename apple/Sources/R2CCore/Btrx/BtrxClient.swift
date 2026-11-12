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

/// Arrival details from the Desk Pod for one btrx frame. `backlog` is true for frames
/// delivered before config_complete_id or in the connect burst right after it: old and
/// display-only. Clue code must refuse them.
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

    public static let connectBurstMs: Int64 = 2_000

    public let framing: Framing
    public private(set) var state: State = .idle
    public private(set) var localNode: UInt32?
    public private(set) var localRole: BtrxProtocol.RoleReport?

    private let send: ([UInt8]) -> Void
    private let log: BtrxLogger
    private weak var listener: BtrxClientListener?
    private var rng: any RandomNumberGenerator
    private let clock: () -> Int64
    private let connectBurstMs: Int64
    private var configId: UInt32 = 0
    private var seq = 0
    private var readyAtMs: Int64 = 0
    private var decoder: MeshtasticPhoneAPI.StreamDecoder!
    private(set) var frameCounts: [String: Int] = [:]
    private(set) var backlogCounts: [String: Int] = [:]
    private var rejectCounts: [String: Int] = [:]
    private var refusedNodes = Set<UInt32>()
    private var otherPorts = 0

    public init(send: @escaping ([UInt8]) -> Void, log: BtrxLogger, listener: BtrxClientListener? = nil,
                rng: any RandomNumberGenerator = SystemRandomNumberGenerator(), framing: Framing = .packet,
                clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }, connectBurstMs: Int64 = 0) {
        self.send = send
        self.log = log
        self.listener = listener
        self.rng = rng
        self.framing = framing
        self.clock = clock
        self.connectBurstMs = connectBurstMs
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
        decoder.feed(data)
    }

    /// Bluetooth: one FromRadio protobuf per characteristic read (an empty read means drained).
    public func onFromRadio(_ proto: [UInt8]) {
        guard state != .closed, !proto.isEmpty else { return }
        onProto(proto)
    }

    private func sendToRadio(_ proto: [UInt8]) {
        send(framing == .serial ? MeshtasticPhoneAPI.frame(proto) : proto)
    }

    /// Keep-alive; the link calls this about every 5 minutes.
    public func sendHeartbeat() {
        guard state == .ready else { return }
        sendToRadio(MeshtasticPhoneAPI.toRadioHeartbeat())
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

    public func sendFrame(to: UInt32, frame: [UInt8]) {
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
            readyAtMs = clock()
            log.info("handshake: config complete, Desk Pod node \(localNode.map(Self.nodeName) ?? "unknown")")
            if let node = localNode { listener?.onConfigured(localNode: node) }
            sendRoleGet()
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
        let backlog = state != .ready || (connectBurstMs > 0 && now - readyAtMs < connectBurstMs)
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
        if frame.type == BtrxProtocol.ROLE_REPORT && packet.from == localNode, let report = try? BtrxProtocol.parseRoleReport(frame.payload) {
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

    public static func nodeName(_ node: UInt32) -> String { String(format: "!%08x", node) }
}
