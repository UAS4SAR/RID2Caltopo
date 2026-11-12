import Foundation

/// The few Meshtastic phone-API messages R2C needs, hand-written (the Meshtastic
/// protobuf definitions are GPL-3.0; decision 7 of the btrx brief). Same as Android's
/// MeshtasticSerial.kt and MeshtasticBle; keep them matching.
///
///     ToRadio   1 packet (MeshPacket), 3 want_config_id (uint32), 7 heartbeat (Heartbeat)
///     FromRadio 2 packet, 3 my_info (MyNodeInfo: 1 my_node_num), 7 config_complete_id
///     MeshPacket 1 from (fixed32), 2 to (fixed32), 3 channel, 4 decoded (Data), 6 id (fixed32),
///                8 rx_snr (float), 9 hop_limit (R2C sends 0), 10 want_ack, 12 rx_rssi (int32)
///     Data       1 portnum (enum), 2 payload (bytes)
///
/// Serial (USB) framing is 0x94 0xC3 + big-endian length; the iPad has no USB serial,
/// so it uses the Bluetooth framing: one bare protobuf per ToRadio write / FromRadio read.
public enum MeshtasticPhoneAPI {
    public static let start1: UInt8 = 0x94
    public static let start2: UInt8 = 0xC3
    public static let maxPacket = 512
    public static let broadcast: UInt32 = 0xFFFF_FFFF

    // Bluetooth phone API (meshtastic.org/docs/development/device/client-api).
    public static let serviceUUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"
    public static let toRadioUUID = "f75c76d2-129e-4dad-a1dd-7866124401e7"
    public static let fromRadioUUID = "2c55e69e-4993-11ed-b878-0242ac120002"
    public static let fromNumUUID = "ed9da18c-a800-4f66-a670-aa7547e34453"
    public static let namePrefix = "Meshtastic"
    /// Printed-PIN placeholder until each unit gets its own (btrx provisioning, planned).
    public static let defaultPIN = "123456"

    public static func wakeBytes() -> [UInt8] { [UInt8](repeating: start2, count: 32) }

    public static func frame(_ proto: [UInt8]) -> [UInt8] {
        precondition(proto.count <= maxPacket, "protobuf too long: \(proto.count)")
        return [start1, start2, UInt8(proto.count >> 8), UInt8(proto.count & 0xFF)] + proto
    }

    public static func toRadioWantConfig(_ configId: UInt32) -> [UInt8] { ProtoWriter().varint(3, UInt64(configId)).bytes() }

    public static func toRadioHeartbeat() -> [UInt8] { ProtoWriter().bytesField(7, []).bytes() }

    public static func toRadioPacket(to: UInt32, packetId: UInt32, portnum: Int, payload: [UInt8], channel: Int = 0, hopLimit: Int = 0) -> [UInt8] {
        let data = ProtoWriter().varint(1, UInt64(portnum)).bytesField(2, payload).bytes()
        let packet = ProtoWriter().fixed32(2, to)
        if channel != 0 { packet.varint(3, UInt64(channel)) }
        packet.bytesField(4, data).fixed32(6, packetId)
        // hop_limit 0 (the default, not written) for everything R2C sends (btrx firmware 481b6fb).
        if hopLimit != 0 { packet.varint(9, UInt64(hopLimit)) }
        return ProtoWriter().bytesField(1, packet.bytes()).bytes()
    }

    public enum FromRadio: Equatable, Sendable {
        case myInfo(nodeNum: UInt32)
        case configComplete(id: UInt32)
        case packet(Packet)
        /// Node, config, channel, log and every other record R2C does not use.
        case other(field: Int)
    }

    public struct Packet: Equatable, Sendable {
        public let from: UInt32, to: UInt32, channel: Int, id: UInt32
        public let rxSnr: Float, rxRssi: Int, hopLimit: Int
        /// Nil when the packet was encrypted for a channel this node cannot read.
        public let portnum: Int?
        public let payload: [UInt8]?
    }

    public struct DecodeError: Error, Sendable { public let message: String }

    public static func parseFromRadio(_ buf: [UInt8]) throws -> FromRadio {
        let r = ProtoReader(buf)
        var result: FromRadio?
        while r.hasMore {
            let (field, wire) = try r.tag()
            switch (field, wire) {
            case (2, 2): result = .packet(try parseMeshPacket(try r.lengthDelimited()))
            case (3, 2):
                let info = ProtoReader(try r.lengthDelimited())
                var node: UInt32 = 0
                while info.hasMore {
                    let (f, w) = try info.tag()
                    if f == 1 && w == 0 { node = UInt32(truncatingIfNeeded: try info.varint()) } else { try info.skip(w) }
                }
                result = .myInfo(nodeNum: node)
            case (7, 0): result = .configComplete(id: UInt32(truncatingIfNeeded: try r.varint()))
            case (1, _): try r.skip(wire)
            default:
                if result == nil { result = .other(field: field) }
                try r.skip(wire)
            }
        }
        return result ?? .other(field: 0)
    }

    private static func parseMeshPacket(_ buf: [UInt8]) throws -> Packet {
        let r = ProtoReader(buf)
        var from: UInt32 = 0, to: UInt32 = 0, channel = 0, id: UInt32 = 0
        var snr: Float = 0, rssi = 0, hop = 0
        var port: Int?, payload: [UInt8]?
        while r.hasMore {
            let (f, w) = try r.tag()
            switch (f, w) {
            case (1, 5): from = try r.fixed32()
            case (2, 5): to = try r.fixed32()
            case (3, 0): channel = Int(truncatingIfNeeded: try r.varint())
            case (4, 2):
                let d = ProtoReader(try r.lengthDelimited())
                port = 0
                while d.hasMore {
                    let (df, dw) = try d.tag()
                    switch (df, dw) {
                    case (1, 0): port = Int(truncatingIfNeeded: try d.varint())
                    case (2, 2): payload = try d.lengthDelimited()
                    default: try d.skip(dw)
                    }
                }
                if payload == nil { payload = [] }
            case (6, 5): id = try r.fixed32()
            case (8, 5): snr = Float(bitPattern: try r.fixed32())
            case (9, 0): hop = Int(truncatingIfNeeded: try r.varint())
            case (12, 0): rssi = Int(Int32(truncatingIfNeeded: Int64(bitPattern: try r.varint())))
            default: try r.skip(w)
            }
        }
        return Packet(from: from, to: to, channel: channel, id: id, rxSnr: snr, rxRssi: rssi, hopLimit: hop, portnum: port, payload: payload)
    }

    /// Splits a serial byte stream into protobuf messages (USB; kept for parity and tests).
    public final class StreamDecoder {
        private var buf: [UInt8] = []
        private var state = 0
        private var length = 0
        private var noise = 0
        private let onMessage: ([UInt8]) -> Void
        private let onNoise: (Int) -> Void

        public init(onMessage: @escaping ([UInt8]) -> Void, onNoise: @escaping (Int) -> Void = { _ in }) {
            self.onMessage = onMessage
            self.onNoise = onNoise
        }

        public func feed(_ data: [UInt8]) { data.forEach(accept) }

        private func accept(_ b: UInt8) {
            switch state {
            case 0: if b == MeshtasticPhoneAPI.start1 { state = 1 } else { noise += 1 }
            case 1:
                if b == MeshtasticPhoneAPI.start2 { state = 2 } else { noise += 2; state = b == MeshtasticPhoneAPI.start1 ? 1 : 0 }
            case 2: length = Int(b) << 8; state = 3
            case 3:
                length |= Int(b)
                if length > MeshtasticPhoneAPI.maxPacket { noise += 4; state = 0; return }
                if noise > 0 { onNoise(noise); noise = 0 }
                buf.removeAll(keepingCapacity: true)
                if length == 0 { onMessage([]); state = 0 } else { state = 4 }
            default:
                buf.append(b)
                if buf.count == length { state = 0; onMessage(buf) }
            }
        }
    }

    public final class ProtoWriter {
        private var out: [UInt8] = []
        public init() {}
        private func rawVarint(_ v: UInt64) {
            var x = v
            while x & ~0x7F != 0 { out.append(UInt8((x & 0x7F) | 0x80)); x >>= 7 }
            out.append(UInt8(x))
        }
        private func tag(_ field: Int, _ wire: Int) { rawVarint(UInt64((field << 3) | wire)) }
        @discardableResult public func varint(_ field: Int, _ v: UInt64) -> ProtoWriter { tag(field, 0); rawVarint(v); return self }
        /// int32 fields: negative values are sign-extended to 10-byte varints.
        @discardableResult public func int32(_ field: Int, _ v: Int32) -> ProtoWriter { varint(field, UInt64(bitPattern: Int64(v))) }
        @discardableResult public func fixed32(_ field: Int, _ v: UInt32) -> ProtoWriter {
            tag(field, 5)
            for i in 0..<4 { out.append(UInt8((v >> (8 * UInt32(i))) & 0xFF)) }
            return self
        }
        @discardableResult public func bytesField(_ field: Int, _ b: [UInt8]) -> ProtoWriter {
            tag(field, 2); rawVarint(UInt64(b.count)); out += b; return self
        }
        public func bytes() -> [UInt8] { out }
    }

    public final class ProtoReader {
        private let b: [UInt8]
        private var pos = 0
        public init(_ b: [UInt8]) { self.b = b }
        public var hasMore: Bool { pos < b.count }
        public func varint() throws -> UInt64 {
            var shift: UInt64 = 0
            var result: UInt64 = 0
            while true {
                guard pos < b.count else { throw DecodeError(message: "truncated varint") }
                let x = b[pos]; pos += 1
                result |= UInt64(x & 0x7F) << shift
                if x & 0x80 == 0 { return result }
                shift += 7
                if shift > 63 { throw DecodeError(message: "varint too long") }
            }
        }
        public func tag() throws -> (Int, Int) { let t = try varint(); return (Int(t >> 3), Int(t & 7)) }
        public func fixed32() throws -> UInt32 {
            guard pos + 4 <= b.count else { throw DecodeError(message: "truncated fixed32") }
            let v = UInt32(b[pos]) | (UInt32(b[pos + 1]) << 8) | (UInt32(b[pos + 2]) << 16) | (UInt32(b[pos + 3]) << 24)
            pos += 4
            return v
        }
        public func lengthDelimited() throws -> [UInt8] {
            let n = Int(try varint())
            guard n >= 0, pos + n <= b.count else { throw DecodeError(message: "truncated field") }
            defer { pos += n }
            return Array(b[pos..<(pos + n)])
        }
        public func skip(_ wire: Int) throws {
            switch wire {
            case 0: _ = try varint()
            case 1: guard pos + 8 <= b.count else { throw DecodeError(message: "truncated fixed64") }; pos += 8
            case 2: _ = try lengthDelimited()
            case 5: _ = try fixed32()
            default: throw DecodeError(message: "unsupported wire type \(wire)")
            }
        }
    }
}
