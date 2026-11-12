import Foundation

/// btrx frame codec, byte for byte with the btrx firmware (btrx_protocol.cpp) and its
/// reference, firmware/tools/r2c_sim/protocol.py. Same as Android's BtrxProtocol.kt;
/// keep them matching. The frame rides inside a Meshtastic PRIVATE_APP (port 256)
/// payload. Little-endian throughout:
///
///     B7 52 | version | type | seq | pod_id u32 | flags | len | payload | crc u16
///
/// The CRC is CRC-16/CCITT-FALSE over version .. end of payload, low byte first.
public enum BtrxProtocol {
    public static let preamble0: UInt8 = 0xB7
    public static let preamble1: UInt8 = 0x52
    public static let protoVersion = 1
    public static let headerLen = 11
    public static let frameMin = 13
    public static let frameMax = 233
    public static let payloadMax = 220
    public static let nonceLen = 8
    public static let privateAppPort = 256

    /// Board builds this app knows.
    public static let supportedBuilds: Set<Int> = [0x0100]

    public static let HELLO = 0x01, HELLO_ACK = 0x02, PAIR_REQ = 0x10, PAIR_CONFIRM = 0x11, PAIR_ACK = 0x12
    public static let B_CFG = 0x13, GESTURE = 0x14, DESK_PAIR = 0x15, ROLE_GET = 0x20, ROLE_REPORT = 0x21
    public static let ROLE_SET = 0x22, TIME_PING = 0x30, TIME_PONG = 0x31, START = 0x40, STOP = 0x41
    public static let CTRL_ACK = 0x42, SCAN_CFG = 0x50, SCAN_CFG_ACK = 0x51, FILTER_LIST = 0x60
    public static let FILTER_ACK = 0x61, DETECT = 0x70, IMU_SAMPLE = 0x71, ADV_SAMPLE = 0x72, HEALTH = 0x80
    public static let DFU_REQ = 0x90, DFU_ACK = 0x91

    private static let msgNames: [Int: String] = [
        HELLO: "HELLO", HELLO_ACK: "HELLO_ACK", PAIR_REQ: "PAIR_REQ", PAIR_CONFIRM: "PAIR_CONFIRM",
        PAIR_ACK: "PAIR_ACK", B_CFG: "B_CFG", GESTURE: "GESTURE", DESK_PAIR: "DESK_PAIR", ROLE_GET: "ROLE_GET",
        ROLE_REPORT: "ROLE_REPORT", ROLE_SET: "ROLE_SET", TIME_PING: "TIME_PING", TIME_PONG: "TIME_PONG",
        START: "START", STOP: "STOP", CTRL_ACK: "CTRL_ACK", SCAN_CFG: "SCAN_CFG", SCAN_CFG_ACK: "SCAN_CFG_ACK",
        FILTER_LIST: "FILTER_LIST", FILTER_ACK: "FILTER_ACK", DETECT: "DETECT", IMU_SAMPLE: "IMU_SAMPLE",
        ADV_SAMPLE: "ADV_SAMPLE", HEALTH: "HEALTH", DFU_REQ: "DFU_REQ", DFU_ACK: "DFU_ACK",
    ]

    public static func typeName(_ type: Int) -> String { msgNames[type] ?? String(format: "0x%02x", type) }

    public static let roleUnknown = 0, rolePort = 1, roleStarboard = 2, roleDeskPod = 3

    public static func roleName(_ role: Int) -> String {
        switch role {
        case roleUnknown: return "unknown"
        case rolePort: return "port"
        case roleStarboard: return "starboard"
        case roleDeskPod: return "desk-pod"
        default: return String(format: "0x%x", role)
        }
    }

    public static func txName(_ policy: Int) -> String {
        switch policy {
        case 0: return "off"
        case 1: return "pairing"
        case 2: return "on"
        default: return String(format: "0x%x", policy)
        }
    }

    public static let F_ACK_REQ = 0x01, F_IS_ACK = 0x02, F_TO_B = 0x04, F_ERROR = 0x80
    private static let frameFlags: [(Int, String)] = [(F_ACK_REQ, "ack-req"), (F_IS_ACK, "is-ack"), (F_TO_B, "to-b"), (F_ERROR, "error")]

    public static let FLAG_ROLE_MISMATCH = 0x01, FLAG_B_MISSING = 0x02, FLAG_IMU_MISSING = 0x04
    public static let FLAG_TX_INHIBIT = 0x08, FLAG_PAIRING = 0x10, FLAG_CLOCK_UNSYNCED = 0x20, FLAG_SCANNING = 0x40
    private static let statusFlags: [(Int, String)] = [
        (FLAG_ROLE_MISMATCH, "role-mismatch"), (FLAG_B_MISSING, "port-missing"), (FLAG_IMU_MISSING, "imu-missing"),
        (FLAG_TX_INHIBIT, "tx-held-off"), (FLAG_PAIRING, "pairing"), (FLAG_CLOCK_UNSYNCED, "clock-unsynced"),
        (FLAG_SCANNING, "listening"),
    ]

    public static func frameFlagNames(_ v: Int) -> String { flagNames(v, frameFlags) }
    public static func statusFlagNames(_ v: Int) -> String { flagNames(v, statusFlags) }

    private static func flagNames(_ value: Int, _ table: [(Int, String)]) -> String {
        var names = table.filter { value & $0.0 != 0 }.map { $0.1 }
        let known = table.reduce(0) { $0 | $1.0 }
        let unknown = value & ~known
        if unknown != 0 { names.append(String(format: "0x%x", unknown)) }
        return names.isEmpty ? "none" : names.joined(separator: ",")
    }

    /// CRC-16/CCITT-FALSE: init 0xFFFF, poly 0x1021, no reflection, no final xor.
    public static func crc16(_ data: [UInt8], from: Int = 0, to: Int? = nil) -> Int {
        var crc = 0xFFFF
        for i in from..<(to ?? data.count) {
            crc ^= Int(data[i]) << 8
            for _ in 0..<8 {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF
            }
        }
        return crc
    }

    public struct Frame: Sendable, Equatable {
        public let version: Int
        public let type: Int
        public let seq: Int
        public let podId: UInt32
        public let flags: Int
        public let payload: [UInt8]
        public var typeName: String { BtrxProtocol.typeName(type) }
    }

    public enum Rejection: String, Sendable { case TOO_SHORT, BAD_PREAMBLE, UNSUPPORTED_VERSION, LENGTH_TOO_LARGE, TRUNCATED, CRC_MISMATCH }

    public struct FrameError: Error, Sendable {
        public let reason: Rejection
        public let message: String
        public let version: Int?
    }

    public struct PayloadError: Error, Sendable { public let message: String }

    public static func encodeFrame(_ type: Int, seq: Int = 1, podId: UInt32 = 0, flags: Int = 0, payload: [UInt8] = []) -> [UInt8] {
        precondition(payload.count <= payloadMax, "payload too long: \(payload.count)")
        var out = [UInt8](repeating: 0, count: frameMin + payload.count)
        out[0] = preamble0; out[1] = preamble1; out[2] = UInt8(protoVersion); out[3] = UInt8(truncatingIfNeeded: type)
        out[4] = UInt8(truncatingIfNeeded: seq)
        putU32(&out, 5, podId)
        out[9] = UInt8(truncatingIfNeeded: flags); out[10] = UInt8(payload.count)
        out.replaceSubrange(headerLen..<(headerLen + payload.count), with: payload)
        let crc = crc16(out, from: 2, to: headerLen + payload.count)
        out[headerLen + payload.count] = UInt8(crc & 0xFF)
        out[headerLen + payload.count + 1] = UInt8(crc >> 8)
        return out
    }

    /// Decodes one frame or throws ``FrameError``. Mirrors protocol.py decode_frame.
    public static func decodeFrame(_ buf: [UInt8]) throws -> Frame {
        guard buf.count >= frameMin else { throw FrameError(reason: .TOO_SHORT, message: "frame too short (\(buf.count) bytes)", version: nil) }
        guard buf[0] == preamble0, buf[1] == preamble1 else { throw FrameError(reason: .BAD_PREAMBLE, message: "bad preamble", version: nil) }
        let version = Int(buf[2])
        guard version == protoVersion else { throw FrameError(reason: .UNSUPPORTED_VERSION, message: "unsupported version \(version)", version: version) }
        let len = Int(buf[10])
        guard len <= payloadMax else { throw FrameError(reason: .LENGTH_TOO_LARGE, message: "payload length \(len) too large", version: nil) }
        guard buf.count >= frameMin + len else { throw FrameError(reason: .TRUNCATED, message: "truncated frame", version: nil) }
        let expect = crc16(buf, from: 2, to: headerLen + len)
        let got = u16(buf, headerLen + len)
        guard expect == got else {
            throw FrameError(reason: .CRC_MISMATCH, message: String(format: "crc mismatch expect=0x%04x got=0x%04x", expect, got), version: nil)
        }
        return Frame(version: version, type: Int(buf[3]), seq: Int(buf[4]), podId: u32(buf, 5), flags: Int(buf[9]),
                     payload: Array(buf[headerLen..<(headerLen + len)]))
    }

    // MARK: payloads R2C receives

    private static func need(_ p: [UInt8], _ size: Int, _ name: String) throws {
        if p.count < size { throw PayloadError(message: "\(name) payload too short (\(p.count) < \(size))") }
    }

    public struct DeskPair: Sendable, Equatable {
        public let open: Int, source: Int, secondsLeft: Int
        public init(open: Int, source: Int, secondsLeft: Int) { self.open = open; self.source = source; self.secondsLeft = secondsLeft }
    }

    public static func parseDeskPair(_ p: [UInt8]) throws -> DeskPair {
        try need(p, 4, "DESK_PAIR")
        return DeskPair(open: Int(p[0]), source: Int(p[1]), secondsLeft: u16(p, 2))
    }

    public struct PairReq: Sendable, Equatable {
        public let podId: UInt32, devA: UInt32, devB: UInt32, buildA: Int, buildB: Int
        public let imuOk: Int, roleA: Int, roleB: Int, nonce: [UInt8]
    }

    public static func parsePairReq(_ p: [UInt8]) throws -> PairReq {
        try need(p, 27, "PAIR_REQ")
        return PairReq(podId: u32(p, 0), devA: u32(p, 4), devB: u32(p, 8), buildA: u16(p, 12), buildB: u16(p, 14),
                       imuOk: Int(p[16]), roleA: Int(p[17]), roleB: Int(p[18]), nonce: Array(p[19..<27]))
    }

    public struct PairAck: Sendable, Equatable {
        public let aOk: Int, bOk: Int, flags: Int
        public init(aOk: Int, bOk: Int, flags: Int) { self.aOk = aOk; self.bOk = bOk; self.flags = flags }
    }

    public static func parsePairAck(_ p: [UInt8]) throws -> PairAck {
        try need(p, 4, "PAIR_ACK")
        return PairAck(aOk: Int(p[0]), bOk: Int(p[1]), flags: u16(p, 2))
    }

    public struct RoleReport: Sendable, Equatable {
        public let role: Int, source: Int, confirmed: Int, flags: Int, buildHash: Int
        public let devId: UInt32, peerId: UInt32, podId: UInt32, imuWhoami: Int, txPolicy: Int
    }

    public static func parseRoleReport(_ p: [UInt8]) throws -> RoleReport {
        try need(p, 21, "ROLE_REPORT")
        return RoleReport(role: Int(p[0]), source: Int(p[1]), confirmed: Int(p[2]), flags: u16(p, 3), buildHash: u16(p, 5),
                          devId: u32(p, 7), peerId: u32(p, 11), podId: u32(p, 15), imuWhoami: Int(p[19]), txPolicy: Int(p[20]))
    }

    public struct TimeSync: Sendable, Equatable { public let pingSeq: Int, t1: UInt32, t2: UInt32, t3: UInt32 }

    public static func parseTime(_ p: [UInt8]) throws -> TimeSync {
        try need(p, 16, "TIME")
        return TimeSync(pingSeq: Int(p[0]), t1: u32(p, 2), t2: u32(p, 6), t3: u32(p, 10))
    }

    public struct CtrlAck: Sendable, Equatable { public let ackedType: Int, ackedSeq: Int, ok: Int, sessionId: UInt32, actualMs: UInt32 }

    public static func parseCtrlAck(_ p: [UInt8]) throws -> CtrlAck {
        try need(p, 11, "CTRL_ACK")
        return CtrlAck(ackedType: Int(p[0]), ackedSeq: Int(p[1]), ok: Int(p[2]), sessionId: u32(p, 3), actualMs: u32(p, 7))
    }

    public struct ScanCfg: Sendable, Equatable {
        public let interval625us: Int, window625us: Int, phy: Int, rssiFloor: Int, reportMs: Int
        public let dedupMs: Int, channelMask: Int, profileId: Int, activeScan: Int, result: Int?
    }

    public static func parseScanCfg(_ p: [UInt8]) throws -> ScanCfg {
        try need(p, 13, "SCAN_CFG")
        return ScanCfg(interval625us: u16(p, 0), window625us: u16(p, 2), phy: Int(p[4]), rssiFloor: i8(p, 5), reportMs: u16(p, 6),
                       dedupMs: u16(p, 8), channelMask: Int(p[10]), profileId: Int(p[11]), activeScan: Int(p[12]),
                       result: p.count >= 14 ? Int(p[13]) : nil)
    }

    public struct FilterAck: Sendable, Equatable { public let version: Int, op: Int, ok: Int, listCrc: Int, stored: Int }

    public static func parseFilterAck(_ p: [UInt8]) throws -> FilterAck {
        try need(p, 6, "FILTER_ACK")
        return FilterAck(version: Int(p[0]), op: Int(p[1]), ok: Int(p[2]), listCrc: u16(p, 3), stored: Int(p[5]))
    }

    public struct Health: Sendable, Equatable {
        public let uptimeS: UInt32, buildHash: Int, role: Int, source: Int, confirmed: Int
        public let flags: Int, bLinkAgeS: Int, imuOk: Int, dieTempC: Int
        public let advPerSPort: Int, advPerSStbd: Int, loraRssi: Int, loraSnr: Int
        public let clockOffsetMs: Int32, cfgVersion: Int, filterVersion: Int, txPolicy: Int
        public let errorFlags: Int, scanning: Int
    }

    public static func parseHealth(_ p: [UInt8]) throws -> Health {
        try need(p, 30, "HEALTH")
        return Health(uptimeS: u32(p, 0), buildHash: u16(p, 4), role: Int(p[6]), source: Int(p[7]), confirmed: Int(p[8]),
                      flags: u16(p, 9), bLinkAgeS: u16(p, 11), imuOk: Int(p[13]), dieTempC: i8(p, 14),
                      advPerSPort: u16(p, 15), advPerSStbd: u16(p, 17), loraRssi: i8(p, 19), loraSnr: i8(p, 20),
                      clockOffsetMs: Int32(bitPattern: u32(p, 21)), cfgVersion: Int(p[25]), filterVersion: Int(p[26]),
                      txPolicy: Int(p[27]), errorFlags: Int(p[28]), scanning: Int(p[29]))
    }

    public static let detectHeaderLen = 11
    public static let detectRecordLen = 29
    public static let detectMaxRecords = 7
    public static let uuid16RemoteID = 0xFFFA

    public struct DetectRecord: Sendable, Equatable {
        public let ageMs: Int, windowDs: Int
        /// Bluetooth address, least significant byte first as on the wire.
        public let macLsbFirst: [UInt8]
        public let meta: Int, rssiPort: Int, rssiStbd: Int, hits: Int, companyId: Int
        public let fpHash: Int, rxMask: Int, pduType: Int, mfgLen: Int, mfg: [UInt8], uuid16: Int
        public var addressType: Int { meta & 0x03 }
        public var deviceClass: Int { (meta >> 2) & 0x0F }
        public var longRange: Bool { meta & 0x40 != 0 }
        public var hasManufacturerData: Bool { meta & 0x80 != 0 }
        public var isRemoteID: Bool { uuid16 == BtrxProtocol.uuid16RemoteID }
        public var address: String { macLsbFirst.reversed().map { String(format: "%02X", $0) }.joined(separator: ":") }
        /// Last 4 characters of the address (decision 3): never log or show the full address.
        public var addressTail: String { String(address.replacingOccurrences(of: ":", with: "").suffix(4)) }
    }

    public struct Detect: Sendable, Equatable {
        public let windowEndMs: UInt32, rollX2: Int, pitchX2: Int, rollRateDps: Int
        public let attitudeFlags: Int, tiltCode: Int, records: [DetectRecord]
        public var tilted: Bool { attitudeFlags & 0x01 != 0 }
        public var imuOk: Bool { attitudeFlags & 0x02 != 0 }
        public var imuMissing: Bool { attitudeFlags & 0x04 != 0 }
    }

    public static func parseDetect(_ p: [UInt8]) throws -> Detect {
        try need(p, detectHeaderLen, "DETECT")
        let n = Int(p[10])
        if n > detectMaxRecords || p.count < detectHeaderLen + n * detectRecordLen { throw PayloadError(message: "DETECT payload truncated") }
        let records = (0..<n).map { i -> DetectRecord in
            let o = detectHeaderLen + i * detectRecordLen
            let mfgLen = Int(p[o + 18])
            return DetectRecord(ageMs: u16(p, o), windowDs: Int(p[o + 2]), macLsbFirst: Array(p[(o + 3)..<(o + 9)]),
                                meta: Int(p[o + 9]), rssiPort: i8(p, o + 10), rssiStbd: i8(p, o + 11), hits: Int(p[o + 12]),
                                companyId: u16(p, o + 13), fpHash: Int(p[o + 15]), rxMask: Int(p[o + 16]), pduType: Int(p[o + 17]),
                                mfgLen: mfgLen, mfg: Array(p[(o + 19)..<(o + 19 + min(mfgLen, 8))]), uuid16: u16(p, o + 27))
        }
        return Detect(windowEndMs: u32(p, 0), rollX2: i8(p, 4), pitchX2: i8(p, 5), rollRateDps: i16(p, 6),
                      attitudeFlags: Int(p[8]), tiltCode: Int(p[9]), records: records)
    }

    // MARK: payloads R2C sends

    public static func roleGetFrame(seq: Int) -> [UInt8] { encodeFrame(ROLE_GET, seq: seq) }

    public static let pairConfirmLen = 61
    public static let nameLen = 12

    /// PAIR_CONFIRM (0x11), 61 bytes (protocol.py pack_pair_confirm). Version 1 always sends
    /// channel 0 with no key; `channel` and `psk` exist only to reproduce the firmware vector.
    public static func pairConfirmPayload(podId: UInt32, nonce: [UInt8], roleA: Int = roleStarboard, roleB: Int = rolePort,
                                          confirm: Int = 1, channel: Int = 0, psk: [UInt8] = [], name: String = "") -> [UInt8] {
        precondition(nonce.count == nonceLen, "nonce must be 8 bytes")
        precondition(psk.isEmpty || psk.count == 16 || psk.count == 32, "psk must be empty, 16 or 32 bytes")
        var out = [UInt8](repeating: 0, count: pairConfirmLen)
        putU32(&out, 0, podId)
        out.replaceSubrange(4..<12, with: nonce)
        out[12] = UInt8(truncatingIfNeeded: roleA); out[13] = UInt8(truncatingIfNeeded: roleB); out[14] = UInt8(truncatingIfNeeded: confirm)
        out[15] = UInt8(truncatingIfNeeded: channel); out[16] = UInt8(psk.count)
        out.replaceSubrange(17..<(17 + psk.count), with: psk)
        let ascii = name.unicodeScalars.map { (0x20...0x7E).contains($0.value) ? UInt8($0.value) : UInt8(ascii: "?") }.prefix(nameLen)
        for (i, b) in ascii.enumerated() { out[49 + i] = b }
        return out
    }

    /// A complete PAIR_CONFIRM frame; the header carries the pod_id being assigned, as r2c_sim does.
    public static func pairConfirmFrame(seq: Int, podId: UInt32, nonce: [UInt8], roleA: Int = roleStarboard, roleB: Int = rolePort, confirm: Int = 1) -> [UInt8] {
        encodeFrame(PAIR_CONFIRM, seq: seq, podId: podId, payload: pairConfirmPayload(podId: podId, nonce: nonce, roleA: roleA, roleB: roleB, confirm: confirm))
    }

    /// The pod_id R2C gives a Drone Pod at pairing: FNV-1a (32 bit) over dev_a's four
    /// little-endian bytes, never 0. Same as Android's BtrxProtocol.podIdFor.
    public static func podId(forDevA devA: UInt32) -> UInt32 {
        var h: UInt32 = 0x811C_9DC5
        for i in 0..<4 {
            h ^= (devA >> (8 * UInt32(i))) & 0xFF
            h = h &* 0x0100_0193
        }
        return h == 0 ? 1 : h
    }

    // MARK: little-endian helpers

    public static func u16(_ b: [UInt8], _ o: Int) -> Int { Int(b[o]) | (Int(b[o + 1]) << 8) }
    public static func i8(_ b: [UInt8], _ o: Int) -> Int { Int(Int8(bitPattern: b[o])) }
    public static func i16(_ b: [UInt8], _ o: Int) -> Int { Int(Int16(bitPattern: UInt16(u16(b, o)))) }
    public static func u32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | (UInt32(b[o + 1]) << 8) | (UInt32(b[o + 2]) << 16) | (UInt32(b[o + 3]) << 24)
    }
    public static func putU32(_ b: inout [UInt8], _ o: Int, _ v: UInt32) {
        b[o] = UInt8(v & 0xFF); b[o + 1] = UInt8((v >> 8) & 0xFF); b[o + 2] = UInt8((v >> 16) & 0xFF); b[o + 3] = UInt8(v >> 24)
    }

    public static func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

    public static func fromHex(_ s: String) -> [UInt8] {
        let clean = s.filter { !$0.isWhitespace }
        precondition(clean.count % 2 == 0, "odd hex length")
        var out = [UInt8]()
        out.reserveCapacity(clean.count / 2)
        var i = clean.startIndex
        while i < clean.endIndex {
            let j = clean.index(i, offsetBy: 2)
            out.append(UInt8(clean[i..<j], radix: 16)!)
            i = j
        }
        return out
    }
}
