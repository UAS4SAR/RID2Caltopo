import Foundation

public struct OpenDroneIDAdvertisement: Equatable, Sendable {
    public let messageCounter: UInt8
    public let messages: [OpenDroneIDMessage]

    public init(messageCounter: UInt8, messages: [OpenDroneIDMessage]) {
        self.messageCounter = messageCounter
        self.messages = messages
    }
}

public struct OpenDroneIDMessage: Equatable, Sendable {
    public enum Kind: UInt8, Equatable, Sendable {
        case basicID = 0
        case location = 1
        case authentication = 2
        case selfID = 3
        case system = 4
        case operatorID = 5
        case messagePack = 15
    }

    public indirect enum Payload: Equatable, Sendable {
        case basicID(OpenDroneIDBasicID)
        case location(OpenDroneIDLocation)
        case selfID(OpenDroneIDSelfID)
        case system(OpenDroneIDSystem)
        case messagePack([OpenDroneIDMessage])
        case opaque(kind: Kind, bytes: Data)
    }

    public let kind: Kind
    public let protocolVersion: UInt8
    public let payload: Payload
}

public struct OpenDroneIDSelfID: Equatable, Sendable {
    public let descriptionType: UInt8
    public let operationDescription: String
}

public struct OpenDroneIDBasicID: Equatable, Sendable {
    public let idType: UInt8
    public let aircraftType: UInt8
    public let uasID: String

    public var isSerialNumber: Bool { idType == 1 }
}

public struct DroneScoutRelayMetadata: Equatable, Sendable {
    public let droneToBridgeRssiDbm: Int
    public let receptionMode: String
    public let sourceKind: String?

    public static func parse(_ description: String) -> Self? {
        let pattern = #"(?:^|\s)DS\s+(WIFI\s+B|BT5|WIB)\s+(-?\d{1,3})(?:\s*dBm)?(?:\s+(drone|addon|grounded))?"#
        guard let expression = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }
        let range = NSRange(description.startIndex..., in: description)
        guard let match = expression.firstMatch(in: description, range: range),
              let rssiRange = Range(match.range(at: 2), in: description),
              let rssi = Int(description[rssiRange]),
              (-127 ... -1).contains(rssi),
              let modeRange = Range(match.range(at: 1), in: description)
        else {
            return nil
        }
        let sourceKind = Range(match.range(at: 3), in: description)
            .map { String(description[$0]).lowercased() }
        return Self(
            droneToBridgeRssiDbm: rssi,
            receptionMode: String(description[modeRange])
                .split(whereSeparator: \.isWhitespace)
                .joined(separator: " ")
                .uppercased(),
            sourceKind: sourceKind
        )
    }
}

public enum DroneScoutRelayPing {
    // Match the loss-announcement decision so normal ping jitter never blanks the most recent
    // signal reading before the bridge has actually been classified as absent.
    public static let signalFreshnessSeconds: Int64 = 32

    private static let defaultIdentity = "DRONESCOUTBRIDGE"

    public static func matches(_ advertisement: OpenDroneIDAdvertisement) -> Bool {
        advertisement.messages.contains(where: messageMatches)
    }

    public static func matches(identity: String) -> Bool {
        let normalized = identity.uppercased().filter { character in
            character.isLetter || character.isNumber
        }
        return normalized.hasPrefix(defaultIdentity)
    }

    private static func messageMatches(_ message: OpenDroneIDMessage) -> Bool {
        switch message.payload {
        case let .basicID(basicID):
            return matches(identity: basicID.uasID)
        case let .messagePack(messages):
            return messages.contains(where: messageMatches)
        case .location, .selfID, .system, .opaque:
            return false
        }
    }
}

public struct DroneScoutBridgeLossAnnouncementGate: Sendable {
    public static let defaultThreshold: TimeInterval = 32

    private var monitoringStartedAt: Date?
    private var lossActive = false

    public init() {}

    public mutating func shouldAnnounce(
        monitoringActive: Bool,
        lastPingAt: Date?,
        now: Date,
        muted: Bool,
        threshold: TimeInterval = Self.defaultThreshold
    ) -> Bool {
        guard monitoringActive else {
            monitoringStartedAt = nil
            lossActive = false
            return false
        }

        let startedAt = monitoringStartedAt ?? now
        monitoringStartedAt = startedAt
        let baseline = max(startedAt, lastPingAt ?? startedAt)
        let missing = now.timeIntervalSince(baseline) > threshold
        guard missing else {
            lossActive = false
            return false
        }
        guard !lossActive else { return false }
        lossActive = true
        return !muted
    }
}

public struct OpenDroneIDLocation: Equatable, Sendable {
    public let status: UInt8
    public let heightType: UInt8
    public let latitude: Double
    public let longitude: Double
    public let pressureAltitudeMeters: Double
    public let geodeticAltitudeMeters: Double
    public let heightMeters: Double
    public let horizontalAccuracyCode: UInt8
    public var verticalAccuracyCode: UInt8 = 0
    public var barometerAccuracyCode: UInt8 = 0
    public let directionDegrees: Double?
    public let horizontalSpeedMetersPerSecond: Double?
    public let verticalSpeedMetersPerSecond: Double?
    public let timestampTenths: UInt16

    public var preferredAltitudeMeters: Double? {
        for candidate in [pressureAltitudeMeters, geodeticAltitudeMeters, heightMeters]
        where candidate > -999.0 {
            return candidate
        }
        return nil
    }
}

public struct OpenDroneIDSystem: Equatable, Sendable {
    public let operatorLatitude: Double
    public let operatorLongitude: Double
    public let operatorAltitudeMeters: Double
    public let timestamp: UInt32
}

public enum OpenDroneIDParserError: Error, Equatable, Sendable {
    case invalidApplicationCode(UInt8?)
    case truncatedMessage(expected: Int, actual: Int)
    case unknownMessageType(UInt8)
    case invalidMessagePack(size: Int, count: Int, availableBytes: Int)
}

public enum OpenDroneIDParser {
    public static let bluetoothServiceUUID = "FFFA"
    public static let applicationCode: UInt8 = 0x0D
    public static let messageSize = 25
    public static let maximumMessagesPerPack = 9

    /// Parses the value from CoreBluetooth's `CBAdvertisementDataServiceDataKey`
    /// for service UUID FFFA. CoreBluetooth has already removed the AD length,
    /// AD type, and 16-bit UUID that precede this payload in the raw packet.
    public static func parseBluetoothServiceData(_ data: Data) throws -> OpenDroneIDAdvertisement {
        guard data.first == applicationCode else {
            throw OpenDroneIDParserError.invalidApplicationCode(data.first)
        }
        guard data.count >= 2 else {
            throw OpenDroneIDParserError.truncatedMessage(expected: 2, actual: data.count)
        }

        let counter = data[data.startIndex + 1]
        let messageData = Data(data.dropFirst(2))
        let message = try parseMessage(messageData)
        let messages: [OpenDroneIDMessage]
        if case let .messagePack(contents) = message.payload {
            messages = contents
        } else {
            messages = [message]
        }
        return OpenDroneIDAdvertisement(messageCounter: counter, messages: messages)
    }

    public static func parseMessage(_ data: Data) throws -> OpenDroneIDMessage {
        guard data.count >= messageSize else {
            throw OpenDroneIDParserError.truncatedMessage(expected: messageSize, actual: data.count)
        }

        let header = data[data.startIndex]
        let typeValue = header >> 4
        guard let kind = OpenDroneIDMessage.Kind(rawValue: typeValue) else {
            throw OpenDroneIDParserError.unknownMessageType(typeValue)
        }
        let version = header & 0x0F

        let payload: OpenDroneIDMessage.Payload
        switch kind {
        case .basicID:
            payload = .basicID(parseBasicID(data))
        case .location:
            payload = .location(parseLocation(data))
        case .system:
            payload = .system(parseSystem(data))
        case .selfID:
            payload = .selfID(parseSelfID(data))
        case .messagePack:
            payload = .messagePack(try parseMessagePack(data))
        case .authentication, .operatorID:
            payload = .opaque(kind: kind, bytes: Data(data.prefix(messageSize)))
        }

        return OpenDroneIDMessage(kind: kind, protocolVersion: version, payload: payload)
    }

    private static func parseBasicID(_ data: Data) -> OpenDroneIDBasicID {
        let type = byte(data, 1)
        let idBytes = dataBytes(data, 2 ..< 22)
        let uasID = String(bytes: idBytes.prefix { $0 != 0 }, encoding: .ascii) ?? ""
        return OpenDroneIDBasicID(
            idType: type >> 4,
            aircraftType: type & 0x0F,
            uasID: uasID
        )
    }

    private static func parseSelfID(_ data: Data) -> OpenDroneIDSelfID {
        let descriptionBytes = dataBytes(data, 2 ..< messageSize)
        let description = String(
            bytes: descriptionBytes.prefix { $0 != 0 },
            encoding: .ascii
        ) ?? ""
        return OpenDroneIDSelfID(
            descriptionType: byte(data, 1),
            operationDescription: description
        )
    }

    private static func parseLocation(_ data: Data) -> OpenDroneIDLocation {
        let flags = byte(data, 1)
        let eastWest = (flags >> 1) & 0x01
        let speedMultiplier = flags & 0x01
        let rawDirection = byte(data, 2)
        let rawHorizontalSpeed = byte(data, 3)
        let rawVerticalSpeed = Int8(bitPattern: byte(data, 4))
        let decodedDirection = Double(rawDirection) + (eastWest == 0 ? 0 : 180)
        let decodedVerticalSpeed = Double(rawVerticalSpeed) * 0.5

        return OpenDroneIDLocation(
            status: flags >> 4,
            heightType: (flags >> 2) & 0x01,
            latitude: Double(int32LE(data, 5)) * 1e-7,
            longitude: Double(int32LE(data, 9)) * 1e-7,
            pressureAltitudeMeters: altitude(uint16LE(data, 13)),
            geodeticAltitudeMeters: altitude(uint16LE(data, 15)),
            heightMeters: altitude(uint16LE(data, 17)),
            horizontalAccuracyCode: byte(data, 19) & 0x0F,
            verticalAccuracyCode: byte(data, 19) >> 4,
            barometerAccuracyCode: byte(data, 20) >> 4,
            // ASTM F3411 reserves 361 degrees for unavailable direction. Do not
            // normalize that sentinel to 1 degree and accidentally display it.
            directionDegrees: (0 ... 360).contains(decodedDirection)
                ? RidHeading.normalized(decodedDirection)
                : nil,
            // These wire-format sentinel values mean speed is unavailable.
            horizontalSpeedMetersPerSecond: rawHorizontalSpeed == 0xFF
                ? nil
                : (speedMultiplier == 0
                    ? Double(rawHorizontalSpeed) * 0.25
                    : Double(rawHorizontalSpeed) * 0.75 + 63.75),
            verticalSpeedMetersPerSecond: decodedVerticalSpeed == 63 ? nil : decodedVerticalSpeed,
            timestampTenths: uint16LE(data, 21)
        )
    }

    private static func parseSystem(_ data: Data) -> OpenDroneIDSystem {
        OpenDroneIDSystem(
            operatorLatitude: Double(int32LE(data, 2)) * 1e-7,
            operatorLongitude: Double(int32LE(data, 6)) * 1e-7,
            operatorAltitudeMeters: altitude(uint16LE(data, 17)),
            timestamp: uint32LE(data, 19)
        )
    }

    private static func parseMessagePack(_ data: Data) throws -> [OpenDroneIDMessage] {
        let size = Int(byte(data, 1))
        let count = Int(byte(data, 2))
        let required = 3 + size * count
        guard size == messageSize,
              count > 0,
              count <= maximumMessagesPerPack,
              data.count >= required
        else {
            throw OpenDroneIDParserError.invalidMessagePack(
                size: size,
                count: count,
                availableBytes: data.count
            )
        }

        return try (0 ..< count).map { index in
            let start = 3 + index * size
            return try parseMessage(data.subdata(in: start ..< start + size))
        }
    }

    private static func altitude(_ raw: UInt16) -> Double {
        Double(raw) / 2.0 - 1000.0
    }

    private static func byte(_ data: Data, _ offset: Int) -> UInt8 {
        data[data.startIndex + offset]
    }

    private static func dataBytes(_ data: Data, _ range: Range<Int>) -> Data {
        data.subdata(in: range)
    }

    private static func uint16LE(_ data: Data, _ offset: Int) -> UInt16 {
        UInt16(byte(data, offset)) | UInt16(byte(data, offset + 1)) << 8
    }

    private static func int32LE(_ data: Data, _ offset: Int) -> Int32 {
        Int32(bitPattern: uint32LE(data, offset))
    }

    private static func uint32LE(_ data: Data, _ offset: Int) -> UInt32 {
        UInt32(byte(data, offset))
            | UInt32(byte(data, offset + 1)) << 8
            | UInt32(byte(data, offset + 2)) << 16
            | UInt32(byte(data, offset + 3)) << 24
    }
}
