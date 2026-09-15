import Combine
@preconcurrency import CoreBluetooth
import Foundation
import R2CCore

public struct DroneScoutBridgePacketDiagnostic: Equatable, Sendable {
    public enum Classification: String, Equatable, Sendable {
        case relayPing
        case relayedAircraft
    }

    public let eventCount: UInt64
    public let classification: Classification
    public let transmitterID: UUID
    public let messageCounter: UInt8
    public let aircraftID: String
    public let bridgeToDeviceRssiDbm: Int
}

public struct BluetoothRIDIngressDiagnostic: Equatable, Sendable {
    public let discoveryCallbacks: UInt64
    public let nonRemoteIDCallbacks: UInt64
    public let receivedPackets: UInt64
    public let decodedPackets: UInt64
    public let locationPackets: UInt64
    public let emittedObservations: UInt64
    public let relayPings: UInt64
    public let noFreshLocation: UInt64
    public let missingIdentity: UInt64
    public let invalidLocation: UInt64
    public let decodeFailures: UInt64
    public let streamDrops: UInt64
    public let lastSequence: UInt64
    public let lastTransmitterID: UUID
    public let lastMessageCounter: UInt8?
    public let lastMessageKinds: String
    public let lastRSSIDbm: Int
}

private struct BluetoothRIDRawPacket: Sendable {
    let discoveryCallbacks: UInt64
    let nonRemoteIDCallbacks: UInt64
    let sequence: UInt64
    let serviceData: Data
    let transmitterID: UUID
    let rssi: Int
    let receivedAt: Date
}

private struct BluetoothRIDBridgePacket: Sendable {
    let classification: DroneScoutBridgePacketDiagnostic.Classification
    let transmitterID: UUID
    let messageCounter: UInt8
    let aircraftID: String
    let rssi: Int
    let receivedAt: Date
}

private struct BluetoothRIDPipelineEvent: Sendable {
    let observation: RidObservation?
    let aircraftMessage: RidAircraftMessage?
    let ridMessageReceivedAt: Date?
    let bridgePacket: BluetoothRIDBridgePacket?
    let decodeError: String?
    let diagnostic: BluetoothRIDIngressDiagnostic?
}

private actor BluetoothRIDPacketPipeline {
    private let assembler = OpenDroneIDTrackAssembler()
    private var receivedPackets: UInt64 = 0
    private var decodedPackets: UInt64 = 0
    private var locationPackets: UInt64 = 0
    private var emittedObservations: UInt64 = 0
    private var relayPings: UInt64 = 0
    private var noFreshLocation: UInt64 = 0
    private var missingIdentity: UInt64 = 0
    private var invalidLocation: UInt64 = 0
    private var decodeFailures: UInt64 = 0
    private var streamDrops: UInt64 = 0
    private var lastSequence: UInt64?
    private var lastDiagnosticAt = Date.distantPast

    func ingest(_ packet: BluetoothRIDRawPacket) async -> BluetoothRIDPipelineEvent {
        receivedPackets &+= 1
        if let lastSequence, packet.sequence > lastSequence &+ 1 {
            streamDrops &+= packet.sequence - lastSequence - 1
        }
        lastSequence = packet.sequence

        do {
            let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(packet.serviceData)
            decodedPackets &+= 1
            let kinds = advertisement.messages.map { String(describing: $0.kind) }
            if advertisement.messages.contains(where: { $0.kind == .location }) {
                locationPackets &+= 1
            }

            if DroneScoutRelayPing.matches(advertisement) {
                relayPings &+= 1
                return BluetoothRIDPipelineEvent(
                    observation: nil,
                    aircraftMessage: nil,
                    ridMessageReceivedAt: nil,
                    bridgePacket: BluetoothRIDBridgePacket(
                        classification: .relayPing,
                        transmitterID: packet.transmitterID,
                        messageCounter: advertisement.messageCounter,
                        aircraftID: "DRONESCOUTBRIDGE",
                        rssi: packet.rssi,
                        receivedAt: packet.receivedAt
                    ),
                    decodeError: nil,
                    diagnostic: diagnosticIfDue(
                        packet: packet,
                        messageCounter: advertisement.messageCounter,
                        messageKinds: kinds
                    )
                )
            }

            let source: RidObservation.Source = packet.serviceData.count > 27
                ? .bluetoothExtended
                : .bluetoothLegacy
            let result = await assembler.ingestWithResult(
                advertisement,
                transmitterID: packet.transmitterID,
                source: source,
                receivedAt: packet.receivedAt,
                signalStrengthDbm: packet.rssi
            )
            switch result.disposition {
            case .observation:
                emittedObservations &+= 1
            case .noFreshLocation:
                noFreshLocation &+= 1
            case .missingIdentity:
                missingIdentity &+= 1
            case .invalidLocation:
                invalidLocation &+= 1
            }

            let aircraftMessage = result.aircraftID.map {
                RidAircraftMessage(
                    source: source,
                    aircraftID: $0,
                    receivedAt: packet.receivedAt
                )
            }
            let bridgePacket = result.droneScoutRelay == nil
                ? nil
                : result.aircraftID.map {
                    BluetoothRIDBridgePacket(
                        classification: .relayedAircraft,
                        transmitterID: packet.transmitterID,
                        messageCounter: advertisement.messageCounter,
                        aircraftID: $0,
                        rssi: packet.rssi,
                        receivedAt: packet.receivedAt
                    )
                }
            return BluetoothRIDPipelineEvent(
                observation: result.observation,
                aircraftMessage: aircraftMessage,
                ridMessageReceivedAt: packet.receivedAt,
                bridgePacket: bridgePacket,
                decodeError: nil,
                diagnostic: diagnosticIfDue(
                    packet: packet,
                    messageCounter: advertisement.messageCounter,
                    messageKinds: kinds
                )
            )
        } catch {
            decodeFailures &+= 1
            return BluetoothRIDPipelineEvent(
                observation: nil,
                aircraftMessage: nil,
                ridMessageReceivedAt: nil,
                bridgePacket: nil,
                decodeError: String(describing: error),
                diagnostic: diagnosticIfDue(
                    packet: packet,
                    messageCounter: nil,
                    messageKinds: ["decodeFailure"]
                )
            )
        }
    }

    private func diagnosticIfDue(
        packet: BluetoothRIDRawPacket,
        messageCounter: UInt8?,
        messageKinds: [String]
    ) -> BluetoothRIDIngressDiagnostic? {
        guard packet.receivedAt.timeIntervalSince(lastDiagnosticAt) >= 5 else { return nil }
        lastDiagnosticAt = packet.receivedAt
        return BluetoothRIDIngressDiagnostic(
            discoveryCallbacks: packet.discoveryCallbacks,
            nonRemoteIDCallbacks: packet.nonRemoteIDCallbacks,
            receivedPackets: receivedPackets,
            decodedPackets: decodedPackets,
            locationPackets: locationPackets,
            emittedObservations: emittedObservations,
            relayPings: relayPings,
            noFreshLocation: noFreshLocation,
            missingIdentity: missingIdentity,
            invalidLocation: invalidLocation,
            decodeFailures: decodeFailures,
            streamDrops: streamDrops,
            lastSequence: packet.sequence,
            lastTransmitterID: packet.transmitterID,
            lastMessageCounter: messageCounter,
            lastMessageKinds: messageKinds.joined(separator: ","),
            lastRSSIDbm: packet.rssi
        )
    }
}

private final class BluetoothRIDCentral: NSObject, @unchecked Sendable {
    typealias StateHandler = @Sendable (BluetoothRIDScanner.State) -> Void
    typealias PacketHandler = @Sendable (BluetoothRIDRawPacket) -> Void
    typealias RestartHandler = @Sendable (UInt64, Date) -> Void

    private let queue = DispatchQueue(
        label: "org.ncssar.rid2caltopo.apple.bluetooth-rid",
        qos: .userInteractive,
        autoreleaseFrequency: .workItem
    )
    private let stateHandler: StateHandler
    private let packetHandler: PacketHandler
    private let restartHandler: RestartHandler
    private var scanRequested = false
    private var restartWorkItem: DispatchWorkItem?
    private var restartCount: UInt64 = 0
    private var discoveryCallbacks: UInt64 = 0
    private var nonRemoteIDCallbacks: UInt64 = 0
    private var sequence: UInt64 = 0
    private lazy var manager = CBCentralManager(delegate: self, queue: queue)

    private static let serviceUUID = CBUUID(string: OpenDroneIDParser.bluetoothServiceUUID)
    private static let highPriorityRestartInterval: TimeInterval = 120

    init(
        stateHandler: @escaping StateHandler,
        packetHandler: @escaping PacketHandler,
        restartHandler: @escaping RestartHandler
    ) {
        self.stateHandler = stateHandler
        self.packetHandler = packetHandler
        self.restartHandler = restartHandler
        super.init()
    }

    func start() {
        queue.async { [self] in
            scanRequested = true
            stateHandler(.waitingForBluetooth)
            startScanWhenReady()
        }
    }

    func stop() {
        queue.async { [self] in
            scanRequested = false
            cancelScheduledRestart()
            manager.stopScan()
            stateHandler(.idle)
        }
    }

    private func startScanWhenReady() {
        guard scanRequested else { return }
        guard manager.state == .poweredOn else {
            stateHandler(Self.state(for: manager.state))
            return
        }
        guard !manager.isScanning else {
            stateHandler(.scanning)
            return
        }
        manager.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        scheduleHighPriorityRestart()
        stateHandler(.scanning)
    }

    private func scheduleHighPriorityRestart() {
        cancelScheduledRestart()
        guard scanRequested, manager.state == .poweredOn, manager.isScanning else { return }

        let workItem = DispatchWorkItem { [weak self] in
            self?.performHighPriorityRestart()
        }
        restartWorkItem = workItem
        queue.asyncAfter(
            deadline: .now() + Self.highPriorityRestartInterval,
            execute: workItem
        )
    }

    private func cancelScheduledRestart() {
        restartWorkItem?.cancel()
        restartWorkItem = nil
    }

    private func performHighPriorityRestart() {
        restartWorkItem = nil
        guard scanRequested, manager.state == .poweredOn else { return }

        manager.stopScan()
        manager.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        restartCount &+= 1
        restartHandler(restartCount, Date())
        stateHandler(.scanning)
        scheduleHighPriorityRestart()
    }

    private static func state(for bluetoothState: CBManagerState) -> BluetoothRIDScanner.State {
        switch bluetoothState {
        case .poweredOn:
            return .scanning
        case .poweredOff:
            return .bluetoothDisabled
        case .unauthorized:
            return .unavailable("Bluetooth permission denied")
        case .unsupported:
            return .unavailable("Bluetooth scanning is unsupported")
        case .resetting, .unknown:
            return .waitingForBluetooth
        @unknown default:
            return .unavailable("Unknown Bluetooth state")
        }
    }
}

extension BluetoothRIDCentral: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn, scanRequested {
            startScanWhenReady()
        } else {
            cancelScheduledRestart()
            stateHandler(Self.state(for: central.state))
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        discoveryCallbacks &+= 1
        guard let serviceData = advertisementData[CBAdvertisementDataServiceDataKey]
            as? [CBUUID: Data],
            let remoteIDData = serviceData[Self.serviceUUID]
        else {
            nonRemoteIDCallbacks &+= 1
            return
        }

        sequence &+= 1
        packetHandler(BluetoothRIDRawPacket(
            discoveryCallbacks: discoveryCallbacks,
            nonRemoteIDCallbacks: nonRemoteIDCallbacks,
            sequence: sequence,
            serviceData: remoteIDData,
            transmitterID: peripheral.identifier,
            rssi: RSSI.intValue,
            receivedAt: Date()
        ))
    }
}

@MainActor
public final class BluetoothRIDScanner: ObservableObject, RidObservationProvider {
    public enum State: Equatable, Sendable {
        case idle
        case bluetoothDisabled
        case waitingForBluetooth
        case scanning
        case unavailable(String)
    }

    public nonisolated let observations: AsyncStream<RidObservation>
    public nonisolated let aircraftMessages: AsyncStream<RidAircraftMessage>
    public nonisolated let ridMessageTimes: AsyncStream<Date>

    @Published public private(set) var state: State = .idle
    @Published public private(set) var observationCount = 0
    @Published public private(set) var rejectedAdvertisementCount = 0
    @Published public private(set) var lastAircraftID: String?
    @Published public private(set) var lastDecodeError: String?
    @Published public private(set) var bridgeSignalStrengthDbm: Int?
    @Published public private(set) var bridgeLastSeenAt: Date?
    @Published public private(set) var bridgeEventCount: UInt64 = 0
    @Published public private(set) var lastBridgePacketDiagnostic: DroneScoutBridgePacketDiagnostic?
    @Published public private(set) var ingressDiagnostic: BluetoothRIDIngressDiagnostic?
    @Published public private(set) var scanRestartCount: UInt64 = 0
    @Published public private(set) var lastScanRestartAt: Date?

    private nonisolated let continuation: AsyncStream<RidObservation>.Continuation
    private nonisolated let aircraftMessageContinuation: AsyncStream<RidAircraftMessage>.Continuation
    private nonisolated let ridMessageTimeContinuation: AsyncStream<Date>.Continuation
    private let packetContinuation: AsyncStream<BluetoothRIDRawPacket>.Continuation
    private var central: BluetoothRIDCentral!
    private var processingTask: Task<Void, Never>?
    private var bridgeExpiryTask: Task<Void, Never>?

    private static let bridgeSignalLifetime: Duration = .seconds(
        DroneScoutRelayPing.signalFreshnessSeconds
    )

    public init() {
        let observationPair = AsyncStream<RidObservation>.makeStream(
            bufferingPolicy: .bufferingNewest(256)
        )
        observations = observationPair.stream
        continuation = observationPair.continuation

        let aircraftMessagePair = AsyncStream<RidAircraftMessage>.makeStream(
            bufferingPolicy: .bufferingNewest(2_048)
        )
        aircraftMessages = aircraftMessagePair.stream
        aircraftMessageContinuation = aircraftMessagePair.continuation

        let ridMessageTimePair = AsyncStream<Date>.makeStream(
            bufferingPolicy: .bufferingNewest(2_048)
        )
        ridMessageTimes = ridMessageTimePair.stream
        ridMessageTimeContinuation = ridMessageTimePair.continuation

        let packetPair = AsyncStream<BluetoothRIDRawPacket>.makeStream(
            bufferingPolicy: .bufferingNewest(2_048)
        )
        packetContinuation = packetPair.continuation
        central = BluetoothRIDCentral(
            stateHandler: { [weak self] nextState in
                Task { @MainActor [weak self] in self?.state = nextState }
            },
            packetHandler: { packet in
                packetPair.continuation.yield(packet)
            },
            restartHandler: { [weak self] count, restartedAt in
                Task { @MainActor [weak self] in
                    self?.scanRestartCount = count
                    self?.lastScanRestartAt = restartedAt
                }
            }
        )

        let pipeline = BluetoothRIDPacketPipeline()
        processingTask = Task { [weak self] in
            for await packet in packetPair.stream {
                guard !Task.isCancelled else { break }
                let event = await pipeline.ingest(packet)
                self?.apply(event)
            }
        }
    }

    deinit {
        continuation.finish()
        aircraftMessageContinuation.finish()
        ridMessageTimeContinuation.finish()
        packetContinuation.finish()
        processingTask?.cancel()
    }

    public func start() async throws {
        central.start()
    }

    public func stop() async {
        central.stop()
        bridgeExpiryTask?.cancel()
        bridgeExpiryTask = nil
        bridgeSignalStrengthDbm = nil
        bridgeLastSeenAt = nil
    }

    private func apply(_ event: BluetoothRIDPipelineEvent) {
        if let error = event.decodeError {
            rejectedAdvertisementCount += 1
            lastDecodeError = error
        }
        if let diagnostic = event.diagnostic {
            ingressDiagnostic = diagnostic
        }
        if let bridgePacket = event.bridgePacket {
            recordConfirmedBridgePacket(bridgePacket)
        }
        if let aircraftMessage = event.aircraftMessage {
            aircraftMessageContinuation.yield(aircraftMessage)
        }
        if let receivedAt = event.ridMessageReceivedAt {
            ridMessageTimeContinuation.yield(receivedAt)
        }
        if let observation = event.observation {
            observationCount += 1
            lastAircraftID = observation.aircraftId
            continuation.yield(observation)
        }
    }

    private func recordConfirmedBridgePacket(_ packet: BluetoothRIDBridgePacket) {
        bridgeEventCount &+= 1
        lastBridgePacketDiagnostic = DroneScoutBridgePacketDiagnostic(
            eventCount: bridgeEventCount,
            classification: packet.classification,
            transmitterID: packet.transmitterID,
            messageCounter: packet.messageCounter,
            aircraftID: packet.aircraftID,
            bridgeToDeviceRssiDbm: packet.rssi
        )
        bridgeSignalStrengthDbm = packet.rssi
        bridgeLastSeenAt = packet.receivedAt
        bridgeExpiryTask?.cancel()
        bridgeExpiryTask = Task { [weak self] in
            try? await Task.sleep(for: Self.bridgeSignalLifetime)
            guard !Task.isCancelled else { return }
            self?.bridgeSignalStrengthDbm = nil
        }
    }
}
