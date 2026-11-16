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

    /// Oldest board build this app supports. Builds are sequential (btrx PROTOCOL.md "Build
    /// number"; 0x0103 multi-source STEER, 0x0104 34-byte HEALTH) and newer builds only append
    /// fields, so any build at or above this is accepted; only older ones get a notice (M2).
    public static let minSupportedBuild = 0x0100
    public static func buildSupported(_ buildHash: Int) -> Bool { buildHash >= minSupportedBuild }
    /// Notice text for a build below `minSupportedBuild`.
    public static func buildSupportText(_ buildHash: Int) -> String {
        buildSupported(buildHash) ? "supported" : String(format: "older than this app supports; update to 0x%04x or newer", minSupportedBuild)
    }
    /// First build that sends STEER (DETECT every 5 s from this build).
    public static let buildSteer = 0x0101
    /// Firmware that answers IMU_CAL (btrx PR #18, af2549e).
    public static let buildImuCal = 0x0102
    /// Build 0x0106 (btrx 74ed238): 44-byte HEALTH, PAIR_OPEN, STEER_MUTE ops 3 / 4 (saved ignore
    /// list, answered with FILTER_ACK), src_id = 16-bit hash of the address (stable across
    /// restarts), and a paired pod ignores nods. Same as Android.
    public static let buildStableIds = 0x0106
    public static func savedIgnoreSupported(_ buildHash: Int?) -> Bool { (buildHash ?? 0) >= buildStableIds }
    /// Build 0x0107 (btrx de212c2): FILTER_ACK is 8 bytes with the STEER_MUTE src_id echoed, op 4
    /// also removes saved entries by the id they were ignored under (works after the source left
    /// the pod's table), op 3 for an already-saved id answers ok, and the Desk Pod opens its
    /// window on PAIR_OPEN from its app (DESK_PAIR source 3). The app keeps working with 0x0106.
    /// Same as Android.
    public static let buildFilterSrcId = 0x0107
    /// Build 0x0108 (btrx ec1d105): with a responsive app the Desk Pod button asks it first
    /// (CONFIRM_REQ / CONFIRM_RSP). Older Desk Pods ignore CONFIRM_RSP but still record the
    /// frame's RSSI / SNR (0 from the phone link) as their own lora_rssi / lora_snr, so the
    /// keepalive goes only to a Desk Pod reporting 0x0108 or later. Same as Android.
    public static let buildButtonConfirm = 0x0108
    public static func buttonConfirmSupported(_ buildHash: Int?) -> Bool { (buildHash ?? 0) >= buildButtonConfirm }

    public static let HELLO = 0x01, HELLO_ACK = 0x02, PAIR_REQ = 0x10, PAIR_CONFIRM = 0x11, PAIR_ACK = 0x12
    public static let B_CFG = 0x13, GESTURE = 0x14, DESK_PAIR = 0x15, PAIR_OPEN = 0x16,
        CONFIRM_REQ = 0x17, CONFIRM_RSP = 0x18, ROLE_GET = 0x20, ROLE_REPORT = 0x21
    public static let ROLE_SET = 0x22, TIME_PING = 0x30, TIME_PONG = 0x31, START = 0x40, STOP = 0x41
    public static let CTRL_ACK = 0x42, SCAN_CFG = 0x50, SCAN_CFG_ACK = 0x51, FILTER_LIST = 0x60
    public static let FILTER_ACK = 0x61, DETECT = 0x70, IMU_SAMPLE = 0x71, ADV_SAMPLE = 0x72, HEALTH = 0x80
    public static let DFU_REQ = 0x90, DFU_ACK = 0x91
    public static let STEER = 0x73, STEER_MUTE = 0x74
    public static let IMU_CAL = 0x75, IMU_CAL_ACK = 0x76

    private static let msgNames: [Int: String] = [
        HELLO: "HELLO", HELLO_ACK: "HELLO_ACK", PAIR_REQ: "PAIR_REQ", PAIR_CONFIRM: "PAIR_CONFIRM",
        PAIR_ACK: "PAIR_ACK", B_CFG: "B_CFG", GESTURE: "GESTURE", DESK_PAIR: "DESK_PAIR", PAIR_OPEN: "PAIR_OPEN",
        CONFIRM_REQ: "CONFIRM_REQ", CONFIRM_RSP: "CONFIRM_RSP", ROLE_GET: "ROLE_GET",
        ROLE_REPORT: "ROLE_REPORT", ROLE_SET: "ROLE_SET", TIME_PING: "TIME_PING", TIME_PONG: "TIME_PONG",
        START: "START", STOP: "STOP", CTRL_ACK: "CTRL_ACK", SCAN_CFG: "SCAN_CFG", SCAN_CFG_ACK: "SCAN_CFG_ACK",
        FILTER_LIST: "FILTER_LIST", FILTER_ACK: "FILTER_ACK", DETECT: "DETECT", IMU_SAMPLE: "IMU_SAMPLE",
        ADV_SAMPLE: "ADV_SAMPLE", HEALTH: "HEALTH", DFU_REQ: "DFU_REQ", DFU_ACK: "DFU_ACK",
        STEER: "STEER", STEER_MUTE: "STEER_MUTE", IMU_CAL: "IMU_CAL", IMU_CAL_ACK: "IMU_CAL_ACK",
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

    // STEER flags (btrx_types.h BTRX_STEER_F_*; names as protocol.py STEER_FLAGS).
    public static let STEER_F_TARGET = 0x01, STEER_F_NEW = 0x02, STEER_F_LINKED = 0x04
    public static let STEER_F_GATED = 0x08, STEER_F_IMU_OK = 0x10, STEER_F_MUTED = 0x20
    /// Firmware 0x0102: pod B has a saved IMU calibration (set only while the IMU is ok).
    public static let STEER_F_IMU_CAL = 0x40
    private static let steerFlags: [(Int, String)] = [
        (STEER_F_TARGET, "target"), (STEER_F_NEW, "new"), (STEER_F_LINKED, "linked"),
        (STEER_F_GATED, "gated"), (STEER_F_IMU_OK, "imu"), (STEER_F_MUTED, "muted"),
        (STEER_F_IMU_CAL, "cal"),
    ]

    // IMU_CAL ops and IMU_CAL_ACK status and flags (btrx_imucal.h; protocol.py IMUCAL_*).
    public static let IMUCAL_OP_CLEAR = 0, IMUCAL_OP_LEVEL = 1, IMUCAL_OP_STBD = 2, IMUCAL_OP_QUERY = 3
    public static let IMUCAL_OK = 0, IMUCAL_MOTION = 1, IMUCAL_TILT_SMALL = 2, IMUCAL_NOT_LEVEL = 3, IMUCAL_NEED_LEVEL = 4
    public static let IMUCAL_NO_IMU = 5, IMUCAL_NOT_ROLL = 6, IMUCAL_BAD_OP = 7, IMUCAL_SAVE_FAIL = 8, IMUCAL_BUSY = 9
    public static let IMUCAL_F_CALIBRATED = 0x01, IMUCAL_F_LEVEL_HELD = 0x02, IMUCAL_F_IMU_OK = 0x04
    /// IMU_CAL_ACK face / y_points value for "not known".
    public static let IMU_UNKNOWN = 0xFF

    // STEER_MUTE ops (BTRX_MUTE_*).
    public static let MUTE_CLEAR = 0, MUTE_ADD = 1, MUTE_REMOVE = 2
    /// Build 0x0106: put the address behind src_id on (3) / take it off (4) the pod's saved ignore
    /// list; answered with FILTER_ACK op 1 / 2.
    public static let MUTE_IGNORE = 3, MUTE_UNIGNORE = 4
    // FILTER_ACK op (FILTER_LIST ops; STEER_MUTE 3 answers 1, 4 answers 2).
    public static let FILTER_OP_ADD = 1, FILTER_OP_REMOVE = 2
    /// FILTER_ACK op 0: a FILTER_LIST replace (never an answer to STEER_MUTE).
    public static let FILTER_OP_REPLACE = 0

    // DESK_PAIR source: what opened the Desk Pod's window. 2: DESK_PAIR sent to the Desk Pod from
    // its USB or phone link (this app's Re-pair, or a bench test); 3: PAIR_OPEN from the app (0x0107).
    public static let deskSrcButton = 1, deskSrcLink = 2, deskSrcPairOpen = 3
    public static func deskPairSourceName(_ src: Int) -> String {
        switch src {
        case deskSrcButton: return "button"
        case deskSrcLink: return "phone/USB-opened"
        case deskSrcPairOpen: return "app-opened"
        default: return String(src)
        }
    }

    // STEER device kind (BTRX_DEV_*).
    public static let DEV_UNKNOWN = 0, DEV_PHONE = 1, DEV_WEARABLE = 2, DEV_TRACKER = 3, DEV_AUDIO = 4, DEV_COMPUTER = 5

    public static func devClassName(_ kind: Int) -> String {
        switch kind {
        case DEV_UNKNOWN: return "unknown"
        case DEV_PHONE: return "phone"
        case DEV_WEARABLE: return "wearable"
        case DEV_TRACKER: return "tracker"
        case DEV_AUDIO: return "audio"
        case DEV_COMPUTER: return "computer"
        default: return String(kind)
        }
    }

    public static func steerFlagNames(_ v: Int) -> String { flagNames(v, steerFlags) }
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

    /// FILTER_ACK (0x61). 6 bytes from build 0x0106; 8 from 0x0107, where bytes 6-7 echo the
    /// STEER_MUTE src_id (`srcId`, 0 for a FILTER_LIST answer). `srcId` is nil for the 6-byte form.
    public struct FilterAck: Sendable, Equatable {
        public let version: Int, op: Int, ok: Int, listCrc: Int, stored: Int
        public let srcId: Int?
        public init(version: Int, op: Int, ok: Int, listCrc: Int, stored: Int, srcId: Int? = nil) {
            self.version = version; self.op = op; self.ok = ok; self.listCrc = listCrc; self.stored = stored; self.srcId = srcId
        }
    }

    public static let filterAckLen = 6, filterAckSrcLen = 8

    public static func parseFilterAck(_ p: [UInt8]) throws -> FilterAck {
        try need(p, filterAckLen, "FILTER_ACK")
        return FilterAck(version: Int(p[0]), op: Int(p[1]), ok: Int(p[2]), listCrc: u16(p, 3), stored: Int(p[5]),
                         srcId: p.count >= filterAckSrcLen ? u16(p, 6) : nil)
    }

    public struct Health: Sendable, Equatable {
        public let uptimeS: UInt32, buildHash: Int, role: Int, source: Int, confirmed: Int
        public let flags: Int, bLinkAgeS: Int, imuOk: Int, dieTempC: Int
        public let advPerSPort: Int, advPerSStbd: Int, loraRssi: Int, loraSnr: Int
        public let clockOffsetMs: Int32, cfgVersion: Int, filterVersion: Int, txPolicy: Int
        public let errorFlags: Int, scanning: Int
        /// Offset 30, u32 LE (build 0x0104, btrx e116ac6): messages this board dropped from its
        /// full app queue since it started (saturates at 0xFFFFFFFF). Nil for 30-byte HEALTH
        /// from older firmware: unknown, never 0. Same as Android.
        public var phoneLost: UInt32? = nil
        /// Offsets 34-43 (build 0x0106); nil for shorter HEALTH.
        public var ext: HealthExt? = nil
    }

    /// HEALTH fields from build 0x0106 (PROTOCOL.md HEALTH offsets 34-43).
    public struct HealthExt: Sendable, Equatable {
        /// nRF52 RESETREAS at boot; 0 = power-on or brown-out.
        public let resetReason: UInt32
        /// What opened the last pairing window since boot: 0 none, 1 nod, 2 PAIR_OPEN.
        public let pairSource: Int
        public let nodsIgnored: Int
        public let excludedPerS: Int
        /// Bit 0 own address known, 1 other board, 2 Desk Pod, 3 DJI left out (starboard only).
        public let excludeFlags: Int
        public init(resetReason: UInt32, pairSource: Int, nodsIgnored: Int, excludedPerS: Int, excludeFlags: Int) {
            self.resetReason = resetReason; self.pairSource = pairSource; self.nodsIgnored = nodsIgnored
            self.excludedPerS = excludedPerS; self.excludeFlags = excludeFlags
        }
    }

    public static let healthLen = 30
    public static let healthLostLen = 34
    public static let healthExtLen = 44

    public static let pairSrcNone = 0, pairSrcNod = 1, pairSrcApp = 2
    public static func pairSourceName(_ src: Int) -> String {
        switch src {
        case pairSrcNone: return "none"
        case pairSrcNod: return "nod"
        case pairSrcApp: return "app (PAIR_OPEN)"
        default: return String(src)
        }
    }

    // HEALTH exclude_flags (build 0x0106; starboard board only, 0 on the Desk Pod).
    public static let EXCL_OWN = 0x01, EXCL_OTHER_BOARD = 0x02, EXCL_DESK_POD = 0x04, EXCL_DJI = 0x08
    private static let excludeFlags: [(Int, String)] = [(EXCL_OWN, "own-board"), (EXCL_OTHER_BOARD, "other-board"), (EXCL_DESK_POD, "desk-pod"), (EXCL_DJI, "dji")]
    public static func excludeFlagNames(_ v: Int) -> String { flagNames(v, excludeFlags) }

    // HEALTH reset_reason: nRF52 RESETREAS bits; 0 = power-on or brown-out.
    private static let resetReasons: [(UInt32, String)] = [
        (0x1, "reset-pin"), (0x2, "watchdog"), (0x4, "soft-reset"), (0x8, "lockup"),
        (0x10000, "wake-pin"), (0x20000, "wake-lpcomp"), (0x40000, "wake-debug"), (0x80000, "wake-nfc"), (0x100000, "wake-usb"),
    ]
    public static func resetReasonNames(_ v: UInt32) -> String {
        if v == 0 { return "power-on" }
        var names = resetReasons.filter { v & $0.0 != 0 }.map { $0.1 }
        let rest = v & ~resetReasons.reduce(UInt32(0)) { $0 | $1.0 }
        if rest != 0 { names.append(String(format: "0x%x", rest)) }
        return names.joined(separator: ",")
    }

    public static func parseHealth(_ p: [UInt8]) throws -> Health {
        try need(p, healthLen, "HEALTH")
        return Health(uptimeS: u32(p, 0), buildHash: u16(p, 4), role: Int(p[6]), source: Int(p[7]), confirmed: Int(p[8]),
                      flags: u16(p, 9), bLinkAgeS: u16(p, 11), imuOk: Int(p[13]), dieTempC: i8(p, 14),
                      advPerSPort: u16(p, 15), advPerSStbd: u16(p, 17), loraRssi: i8(p, 19), loraSnr: i8(p, 20),
                      clockOffsetMs: Int32(bitPattern: u32(p, 21)), cfgVersion: Int(p[25]), filterVersion: Int(p[26]),
                      txPolicy: Int(p[27]), errorFlags: Int(p[28]), scanning: Int(p[29]),
                      phoneLost: p.count >= healthLostLen ? u32(p, 30) : nil,
                      ext: p.count >= healthExtLen ? HealthExt(resetReason: u32(p, 34), pairSource: Int(p[38]), nodsIgnored: u16(p, 39),
                                                               excludedPerS: u16(p, 41), excludeFlags: Int(p[43])) : nil)
    }

    /// TIME_PING payload (protocol.py pack_time): ping_seq, 0, t1, t2 = 0, t3 = 0, u16 0.
    public static func timePingPayload(pingSeq: Int, t1Ms: UInt32) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 16)
        out[0] = UInt8(truncatingIfNeeded: pingSeq)
        putU32(&out, 2, t1Ms)
        return out
    }

    public static func timePingFrame(seq: Int, podId: UInt32, pingSeq: Int, t1Ms: UInt32) -> [UInt8] {
        encodeFrame(TIME_PING, seq: seq, podId: podId, payload: timePingPayload(pingSeq: pingSeq, t1Ms: t1Ms))
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

    public static let steerLen = 27
    /// STEER RSSI value for "not heard on this patch".
    public static let rssiNotHeard = -128

    /// STEER (0x73), 27 bytes, firmware/PROTOCOL.md "STEER": the single strongest source
    /// in the pod's last 1 s window. Port is pod B (the IMU board), starboard pod A; port
    /// stronger means the source is to the left.
    public struct Steer: Sendable, Equatable {
        public let steerSeq: Int, flags: Int, windowEndMs: UInt32, ageMs: Int, srcId: Int
        public let rssiPort: Int, rssiStbd: Int, peakRssi: Int, hitsPort: Int, hitsStbd: Int
        public let companyId: Int, devClass: Int, addrType: Int
        /// Address as received, least significant byte first (as in DETECT).
        public let macLsbFirst: [UInt8]
        public let rollX2: Int, pitchX2: Int
        /// Build 0x0103 multi-source records (byte 27 count 1-2); empty for 27-byte frames.
        public let extras: [SteerExtra]
        /// Byte 27 bits 4-7 (record format); 0 for 27-byte frames.
        public let extFormat: Int
        /// The extension was present but unreadable: only bytes 0-26 were used.
        public let extTruncated: Bool

        public init(steerSeq: Int, flags: Int, windowEndMs: UInt32, ageMs: Int, srcId: Int, rssiPort: Int, rssiStbd: Int,
                    peakRssi: Int, hitsPort: Int, hitsStbd: Int, companyId: Int, devClass: Int, addrType: Int,
                    macLsbFirst: [UInt8], rollX2: Int, pitchX2: Int,
                    extras: [SteerExtra] = [], extFormat: Int = 0, extTruncated: Bool = false) {
            self.extras = extras; self.extFormat = extFormat; self.extTruncated = extTruncated
            self.steerSeq = steerSeq; self.flags = flags; self.windowEndMs = windowEndMs; self.ageMs = ageMs; self.srcId = srcId
            self.rssiPort = rssiPort; self.rssiStbd = rssiStbd; self.peakRssi = peakRssi; self.hitsPort = hitsPort; self.hitsStbd = hitsStbd
            self.companyId = companyId; self.devClass = devClass; self.addrType = addrType; self.macLsbFirst = macLsbFirst
            self.rollX2 = rollX2; self.pitchX2 = pitchX2
        }

        public var target: Bool { flags & BtrxProtocol.STEER_F_TARGET != 0 }
        public var newSource: Bool { flags & BtrxProtocol.STEER_F_NEW != 0 }
        public var linked: Bool { flags & BtrxProtocol.STEER_F_LINKED != 0 }
        public var gated: Bool { flags & BtrxProtocol.STEER_F_GATED != 0 }
        public var imuOk: Bool { flags & BtrxProtocol.STEER_F_IMU_OK != 0 }
        public var muted: Bool { flags & BtrxProtocol.STEER_F_MUTED != 0 }
        /// Bit 6 (firmware 0x0102): a saved IMU calibration is in use.
        public var imuCal: Bool { flags & BtrxProtocol.STEER_F_IMU_CAL != 0 }
        public var portHeard: Bool { rssiPort != BtrxProtocol.rssiNotHeard }
        public var stbdHeard: Bool { rssiStbd != BtrxProtocol.rssiNotHeard }
        /// Pod B (the port patch and the IMU) is missing from this report: port not heard and
        /// the IMU-ok flag clear. That is the board-to-board link, not a direction. Same as Android.
        public var portBoardSilent: Bool { !portHeard && !imuOk }
        /// Last 4 characters of the address: never log or show the full address.
        public var addressTail: String { String(macLsbFirst.reversed().map { String(format: "%02X", $0) }.joined().suffix(4)) }
    }

    /// One extra STEER source record (build 0x0103, PROTOCOL.md "STEER" multi-source): 6 bytes
    /// src_id u16, rssi_port i8, rssi_stbd i8, hits nibbles (port low, stbd high), rflags
    /// (bit 0 new, bit 1 linked, bits 2-4 class). No address. Same as Android.
    public struct SteerExtra: Sendable, Equatable {
        public let srcId: Int, rssiPort: Int, rssiStbd: Int, hitsPort: Int, hitsStbd: Int
        public let newSource: Bool, linked: Bool, devClass: Int
    }

    public static let steerExtraLen = 6
    public static let steerMaxExtras = 2

    public static func parseSteer(_ p: [UInt8]) throws -> Steer {
        try need(p, steerLen, "STEER")
        var extras: [SteerExtra] = []
        var format = 0
        var truncated = false
        if p.count > steerLen {
            let ext = Int(p[steerLen])
            let count = ext & 0x0F
            format = ext >> 4
            // Same rules as btrx_bearing_parse_steer: extras only with a target and format >= 1.
            if Int(p[1]) & STEER_F_TARGET != 0 && format != 0 && count > 0 {
                let recLen = (p.count - (steerLen + 1)) / count
                if count > steerMaxExtras || recLen < steerExtraLen {
                    truncated = true
                } else {
                    extras = (0..<count).map { i in
                        let o = steerLen + 1 + i * recLen
                        let hits = Int(p[o + 4]), rf = Int(p[o + 5])
                        return SteerExtra(srcId: u16(p, o), rssiPort: i8(p, o + 2), rssiStbd: i8(p, o + 3),
                                          hitsPort: hits & 0x0F, hitsStbd: hits >> 4,
                                          newSource: rf & 0x01 != 0, linked: rf & 0x02 != 0, devClass: (rf >> 2) & 0x07)
                    }
                }
            }
        }
        return Steer(steerSeq: Int(p[0]), flags: Int(p[1]), windowEndMs: u32(p, 2), ageMs: u16(p, 6), srcId: u16(p, 8),
                     rssiPort: i8(p, 10), rssiStbd: i8(p, 11), peakRssi: i8(p, 12), hitsPort: Int(p[13]), hitsStbd: Int(p[14]),
                     companyId: u16(p, 15), devClass: Int(p[17]), addrType: Int(p[18]), macLsbFirst: Array(p[19..<25]),
                     rollX2: i8(p, 25), pitchX2: i8(p, 26), extras: extras, extFormat: format, extTruncated: truncated)
    }

    /// STEER encoder (protocol.py pack_steer), for tests and the bench simulator.
    public static func steerPayload(_ s: Steer) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: steerLen)
        func b(_ v: Int) -> UInt8 { UInt8(truncatingIfNeeded: v) }
        out[0] = b(s.steerSeq); out[1] = b(s.flags)
        putU32(&out, 2, s.windowEndMs)
        out[6] = b(s.ageMs); out[7] = b(s.ageMs >> 8)
        out[8] = b(s.srcId); out[9] = b(s.srcId >> 8)
        out[10] = b(s.rssiPort); out[11] = b(s.rssiStbd); out[12] = b(s.peakRssi)
        out[13] = b(s.hitsPort); out[14] = b(s.hitsStbd)
        out[15] = b(s.companyId); out[16] = b(s.companyId >> 8)
        out[17] = b(s.devClass); out[18] = b(s.addrType)
        for (i, v) in s.macLsbFirst.prefix(6).enumerated() { out[19 + i] = v }
        out[25] = b(s.rollX2); out[26] = b(s.pitchX2)
        return out
    }

    public struct SteerMute: Sendable, Equatable {
        public let op: Int, srcId: Int
        public init(op: Int, srcId: Int) { self.op = op; self.srcId = srcId }
    }

    public static func parseSteerMute(_ p: [UInt8]) throws -> SteerMute {
        try need(p, 3, "STEER_MUTE")
        return SteerMute(op: Int(p[0]), srcId: u16(p, 1))
    }

    public static func muteOpName(_ op: Int) -> String {
        switch op {
        case MUTE_CLEAR: return "clear"
        case MUTE_ADD: return "add"
        case MUTE_REMOVE: return "remove"
        case MUTE_IGNORE: return "ignore"
        case MUTE_UNIGNORE: return "unignore"
        default: return String(op)
        }
    }

    public static func imuCalOpName(_ op: Int) -> String {
        switch op {
        case IMUCAL_OP_CLEAR: return "clear"
        case IMUCAL_OP_LEVEL: return "level"
        case IMUCAL_OP_STBD: return "starboard"
        case IMUCAL_OP_QUERY: return "status"
        default: return String(op)
        }
    }

    /// Short status names, as protocol.py IMUCAL_STATUS_NAME (logs; the screen uses BtrxImuCal.statusText).
    public static func imuCalStatusName(_ status: Int) -> String {
        switch status {
        case IMUCAL_OK: return "ok"
        case IMUCAL_MOTION: return "too much motion"
        case IMUCAL_TILT_SMALL: return "tilt too small"
        case IMUCAL_NOT_LEVEL: return "not level"
        case IMUCAL_NEED_LEVEL: return "needs level first"
        case IMUCAL_NO_IMU: return "no IMU"
        case IMUCAL_NOT_ROLL: return "not a roll"
        case IMUCAL_BAD_OP: return "bad op"
        case IMUCAL_SAVE_FAIL: return "worked out but not saved"
        case IMUCAL_BUSY: return "still measuring"
        default: return String(status)
        }
    }

    public static let imuCalLen = 2
    public static let imuCalAckLen = 15

    public struct ImuCal: Sendable, Equatable {
        public let op: Int, tag: Int
        public init(op: Int, tag: Int) { self.op = op; self.tag = tag }
    }

    /// IMU_CAL (0x75), 2 bytes: op, tag. Logged when the app sends it.
    public static func parseImuCal(_ p: [UInt8]) throws -> ImuCal {
        try need(p, imuCalLen, "IMU_CAL")
        return ImuCal(op: Int(p[0]), tag: Int(p[1]))
    }

    /// IMU_CAL_ACK (0x76), 15 bytes little-endian (PROTOCOL.md "IMU_CAL"): pod B's answer,
    /// relayed by pod A (or pod A's own "no IMU"). `<BBBBbbBBBhhh`: op, tag, status, flags,
    /// roll_x2, pitch_x2, tilt_x2, face, y_points, gyro_bias_x10[3].
    public struct ImuCalAck: Sendable, Equatable {
        public let op: Int, tag: Int, status: Int, flags: Int
        public let rollX2: Int, pitchX2: Int, tiltX2: Int, face: Int, yPoints: Int
        public let gyroBiasX10: [Int]

        public init(op: Int, tag: Int, status: Int, flags: Int, rollX2: Int, pitchX2: Int, tiltX2: Int, face: Int, yPoints: Int, gyroBiasX10: [Int]) {
            self.op = op; self.tag = tag; self.status = status; self.flags = flags
            self.rollX2 = rollX2; self.pitchX2 = pitchX2; self.tiltX2 = tiltX2; self.face = face; self.yPoints = yPoints
            self.gyroBiasX10 = gyroBiasX10
        }

        public var calibrated: Bool { flags & BtrxProtocol.IMUCAL_F_CALIBRATED != 0 }
        public var levelHeld: Bool { flags & BtrxProtocol.IMUCAL_F_LEVEL_HELD != 0 }
        public var imuOk: Bool { flags & BtrxProtocol.IMUCAL_F_IMU_OK != 0 }
    }

    public static func parseImuCalAck(_ p: [UInt8]) throws -> ImuCalAck {
        try need(p, imuCalAckLen, "IMU_CAL_ACK")
        return ImuCalAck(op: Int(p[0]), tag: Int(p[1]), status: Int(p[2]), flags: Int(p[3]),
                         rollX2: i8(p, 4), pitchX2: i8(p, 5), tiltX2: Int(p[6]), face: Int(p[7]), yPoints: Int(p[8]),
                         gyroBiasX10: [i16(p, 9), i16(p, 11), i16(p, 13)])
    }

    /// IMU_CAL_ACK encoder (protocol.py pack_imu_cal_ack), for tests.
    public static func imuCalAckPayload(_ a: ImuCalAck) -> [UInt8] {
        func b(_ v: Int) -> UInt8 { UInt8(truncatingIfNeeded: v) }
        var out: [UInt8] = [b(a.op), b(a.tag), b(a.status), b(a.flags), b(a.rollX2), b(a.pitchX2), b(a.tiltX2), b(a.face), b(a.yPoints)]
        for i in 0..<3 {
            let g = i < a.gyroBiasX10.count ? a.gyroBiasX10[i] : 0
            out.append(b(g)); out.append(b(g >> 8))
        }
        return out
    }

    public static func imuFaceName(_ face: Int) -> String {
        switch face { case 0: return "up"; case 1: return "down"; default: return "?" }
    }

    public static func imuYName(_ y: Int) -> String {
        switch y { case 0: return "fwd"; case 1: return "aft"; case 2: return "port"; case 3: return "stbd"; default: return "?" }
    }

    public static func imuCalFlagNames(_ flags: Int) -> String {
        var names: [String] = []
        if flags & IMUCAL_F_CALIBRATED != 0 { names.append("calibrated") }
        if flags & IMUCAL_F_LEVEL_HELD != 0 { names.append("level-held") }
        if flags & IMUCAL_F_IMU_OK != 0 { names.append("imu") }
        return names.isEmpty ? "none" : names.joined(separator: ",")
    }

    // MARK: payloads R2C sends

    /// IMU_CAL (0x75) payload: op, tag (protocol.py pack_imu_cal).
    public static func imuCalPayload(op: Int, tag: Int) -> [UInt8] {
        [UInt8(truncatingIfNeeded: op), UInt8(truncatingIfNeeded: tag)]
    }

    /// A complete IMU_CAL frame, sent like STEER_MUTE: to the paired Drone Pod's node (pod
    /// A relays it to pod B), with that pod's pod_id in the header.
    public static func imuCalFrame(seq: Int, podId: UInt32, op: Int, tag: Int) -> [UInt8] {
        encodeFrame(IMU_CAL, seq: seq, podId: podId, payload: imuCalPayload(op: op, tag: tag))
    }

    /// STEER_MUTE (0x74) payload: op u8, src_id u16 (protocol.py pack_steer_mute).
    public static func steerMutePayload(op: Int, srcId: Int = 0) -> [UInt8] {
        [UInt8(truncatingIfNeeded: op), UInt8(truncatingIfNeeded: srcId), UInt8(truncatingIfNeeded: srcId >> 8)]
    }

    /// A complete STEER_MUTE frame. R2C sends it to the paired Drone Pod's node (the
    /// starboard board, which builds STEER); the header carries that pod's pod_id.
    public static func steerMuteFrame(seq: Int, podId: UInt32, op: Int, srcId: Int = 0) -> [UInt8] {
        encodeFrame(STEER_MUTE, seq: seq, podId: podId, payload: steerMutePayload(op: op, srcId: srcId))
    }

    public static func roleGetFrame(seq: Int) -> [UInt8] { encodeFrame(ROLE_GET, seq: seq) }

    // MARK: Desk Pod button confirmation (build 0x0108, PROTOCOL.md "Desk Pod button confirmation")

    public static let CONFIRM_VERSION = 1, CONFIRM_REQ_LEN = 6, CONFIRM_RSP_LEN = 4
    /// CONFIRM_REQ action.
    public static let CFM_ACTION_DESK_PAIR = 1, CFM_ACTION_FACTORY_RESET = 2
    /// CONFIRM_REQ state.
    public static let CFM_ASK = 0, CFM_DONE = 1, CFM_DROPPED = 2, CFM_DONE_NO_APP = 3
    /// CONFIRM_RSP answer.
    public static let CFM_CANCEL = 0, CFM_CONFIRM = 1, CFM_SHOWN = 2, CFM_KEEPALIVE = 3
    /// The keepalive cadence for a responsive app (the Desk Pod waits 10 s).
    public static let confirmKeepaliveMs: Int64 = 5_000
    /// The Desk Pod acts without asking when nothing answers a token this soon.
    public static let confirmShownMs: Int64 = 3_000
    public static let confirmTimeoutS = 15

    public struct ConfirmReq: Sendable, Equatable {
        public let version: Int, action: Int, token: Int, timeoutS: Int, state: Int
        public init(version: Int, action: Int, token: Int, timeoutS: Int, state: Int) {
            self.version = version; self.action = action; self.token = token; self.timeoutS = timeoutS; self.state = state
        }
    }

    /// CONFIRM_REQ (0x17): 6 bytes; a version other than 1 is parsed and left to the caller to drop.
    public static func parseConfirmReq(_ p: [UInt8]) throws -> ConfirmReq {
        try need(p, CONFIRM_REQ_LEN, "CONFIRM_REQ")
        return ConfirmReq(version: Int(p[0]), action: Int(p[1]), token: u16(p, 2), timeoutS: Int(p[4]), state: Int(p[5]))
    }

    public struct ConfirmRsp: Sendable, Equatable {
        public let version: Int, token: Int, answer: Int
        public init(version: Int, token: Int, answer: Int) { self.version = version; self.token = token; self.answer = answer }
    }

    public static func parseConfirmRsp(_ p: [UInt8]) throws -> ConfirmRsp {
        try need(p, CONFIRM_RSP_LEN, "CONFIRM_RSP")
        return ConfirmRsp(version: Int(p[0]), token: u16(p, 1), answer: Int(p[3]))
    }

    public static func confirmRspPayload(token: Int, answer: Int) -> [UInt8] {
        [UInt8(CONFIRM_VERSION), UInt8(token & 0xFF), UInt8((token >> 8) & 0xFF), UInt8(answer & 0xFF)]
    }

    /// CONFIRM_RSP to the Desk Pod's own node (pod_id 0).
    public static func confirmRspFrame(seq: Int, token: Int, answer: Int) -> [UInt8] {
        encodeFrame(CONFIRM_RSP, seq: seq, payload: confirmRspPayload(token: token, answer: answer))
    }

    public static func confirmActionName(_ a: Int) -> String {
        switch a { case CFM_ACTION_DESK_PAIR: return "desk-pair"; case CFM_ACTION_FACTORY_RESET: return "factory-reset"; default: return String(a) }
    }
    public static func confirmStateName(_ s: Int) -> String {
        switch s { case CFM_ASK: return "ask"; case CFM_DONE: return "done"; case CFM_DROPPED: return "dropped"; case CFM_DONE_NO_APP: return "done-no-app"; default: return String(s) }
    }
    public static func confirmAnswerName(_ a: Int) -> String {
        switch a { case CFM_CANCEL: return "cancel"; case CFM_CONFIRM: return "confirm"; case CFM_SHOWN: return "shown"; case CFM_KEEPALIVE: return "keepalive"; default: return String(a) }
    }

    /// PAIR_OPEN (0x16, build 0x0106) payload: seconds (10...120) or none for the pod's 60 s.
    public static let pairOpenDefaultS = 60
    public static func pairOpenPayload(seconds: Int?) -> [UInt8] {
        guard let s = seconds else { return [] }
        return [UInt8(clamping: s)]
    }

    /// PAIR_OPEN to the paired Drone Pod's node: the only way a paired pod (0x0106) opens a
    /// pairing window. No reply; its PAIR_REQ with a new nonce is the answer.
    public static func pairOpenFrame(seq: Int, podId: UInt32, seconds: Int? = nil) -> [UInt8] {
        encodeFrame(PAIR_OPEN, seq: seq, podId: podId, payload: pairOpenPayload(seconds: seconds))
    }

    /// DESK_PAIR to the Desk Pod's own node from its phone link: the firmware's bench-test path
    /// opens the Desk Pod window as if its button were tapped (answered by DESK_PAIR open=1 source 2).
    public static func deskPairOpenFrame(seq: Int) -> [UInt8] { encodeFrame(DESK_PAIR, seq: seq) }

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
