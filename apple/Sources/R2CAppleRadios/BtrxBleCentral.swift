import Combine
@preconcurrency import CoreBluetooth
import Foundation
import R2CCore

/// Writes btrx log lines to the app's diagnostic log (AppleLog installs the writer).
final class BtrxAppleLogger: BtrxLogger, @unchecked Sendable {
    func info(_ message: String) { OperationalBridgePacketLog.write?("INFO", "Btrx", message) }
    func warn(_ message: String) { OperationalBridgePacketLog.write?("WARN", "Btrx", message) }
}

/// The btrx Desk Pod over Bluetooth on the iPad: the Meshtastic phone API service, one
/// GATT operation at a time, FromRadio drained until empty after want_config and each
/// FromNum notification, reconnect backoff 2-30 s. Same behaviour, timeouts and
/// messages as Android's BtrxBleLink.kt + BtrxManager.kt. The iPad has no USB link, so
/// there is no USB/Bluetooth arbiter here.
///
/// Everything (CoreBluetooth delegate, client, model) is confined to ``queue``; the
/// main actor only receives ``BtrxDeskPodModel/Snapshot`` values. Runs while the iPad is
/// locked through the `bluetooth-central` background mode. Nothing here makes a clue.
final class BtrxBleCentral: NSObject, @unchecked Sendable {
    typealias Model = BtrxDeskPodModel

    struct Events: Sendable {
        var snapshot: @Sendable (Model.Snapshot) -> Void
        var rememberPeripheral: @Sendable (UUID, String?) -> Void
        var paired: @Sendable (Model.PairedPod) -> Void
    }

    private enum Op {
        case enableNotify
        case write([UInt8])
        case readFromRadio
    }

    static let scanSeconds: TimeInterval = 12
    static let scanGapSeconds: TimeInterval = 3
    static let scanRounds = 3
    static let directTries = 2
    static let connectTimeout: TimeInterval = 30
    static let opTimeout: TimeInterval = 10
    static let roleTimeout: TimeInterval = 10
    static let heartbeatSeconds: TimeInterval = 300
    static let summarySeconds: TimeInterval = 60

    private static let service = CBUUID(string: MeshtasticPhoneAPI.serviceUUID)
    private static let toRadioUUID = CBUUID(string: MeshtasticPhoneAPI.toRadioUUID)
    private static let fromRadioUUID = CBUUID(string: MeshtasticPhoneAPI.fromRadioUUID)
    private static let fromNumUUID = CBUUID(string: MeshtasticPhoneAPI.fromNumUUID)

    private let queue = DispatchQueue(label: "org.ncssar.rid2caltopo.apple.btrx-ble", qos: .userInitiated)
    private let log = BtrxAppleLogger()
    private let events: Events
    private var model: Model!
    private var manager: CBCentralManager?

    private var enabled = false
    private var remembered: UUID?
    private var rejected = Set<UUID>()
    private var backoff = BtrxReconnectBackoff()
    private var directTriesUsed = 0
    private var scanRound = 0
    private var scanning = false
    private var found: [UUID: (CBPeripheral, Int)] = [:]
    private var foundNames: [UUID: String] = [:]

    private var peripheral: CBPeripheral?
    private var toRadio: CBCharacteristic?
    private var fromRadio: CBCharacteristic?
    private var fromNum: CBCharacteristic?
    private var gattQueue: BtrxGattQueue<Op>?
    private var drain: BtrxFromRadioDrain?
    private var client: BtrxClient?
    private var handshakeWritten = false
    private var sessionSerial = 0

    private var attemptWork: DispatchWorkItem?
    private var scanWork: DispatchWorkItem?
    private var connectWork: DispatchWorkItem?
    private var opWork: DispatchWorkItem?
    private var sessionTimers: [DispatchWorkItem] = []
    private var tickTimer: DispatchSourceTimer?

    init(events: Events, paired: Model.PairedPod?) {
        self.events = events
        super.init()
        queue.sync {
            model = Model(log: log, onPaired: { pod in events.paired(pod) })
            model.restorePairedPod(paired)
            model.setLink(Model.LinkStatus(kind: .none, phase: .off, detail: "Bluetooth link is off."))
        }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
        tickTimer = timer
    }

    deinit { tickTimer?.cancel() }

    // MARK: actions (any thread)

    func setEnabled(_ on: Bool, remembered id: UUID?) {
        queue.async { [self] in
            remembered = id
            guard on != enabled else { return }
            enabled = on
            log.info("Desk Pod Bluetooth link turned \(on ? "on" : "off")")
            if on {
                backoff.reset()
                if manager == nil {
                    // Creating the manager shows the Bluetooth permission prompt the first time.
                    manager = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionShowPowerAlertKey: false])
                } else {
                    attempt()
                }
            } else {
                stop(reason: "turned off", phase: .off, detail: "Bluetooth link is off.")
            }
            publish()
        }
    }

    func forgetDeskPod() {
        queue.async { [self] in
            remembered = nil
            rejected.removeAll()
            log.info("Desk Pod forgotten; the next Bluetooth connection scans for any Desk Pod")
            if enabled {
                stop(reason: "Desk Pod forgotten", phase: .scanning, detail: "")
                enabled = true
                attempt()
            }
            publish()
        }
    }

    func forgetPairedPod() { queue.async { [self] in model.forgetPairedPod(); publish() } }
    func startPairing() { queue.async { [self] in model.startPairing(); publish() } }
    func confirmPod() { queue.async { [self] in model.confirmPod(); publish() } }
    func cancelPairing() { queue.async { [self] in model.cancelPairing(); publish() } }
    func clearPairingResult() { queue.async { [self] in model.clearPairingResult(); publish() } }

    // MARK: connection

    private func status(_ phase: Model.LinkPhase, _ detail: String, _ name: String? = nil) {
        model.setLink(Model.LinkStatus(kind: phase == .off ? .none : .bluetooth, phase: phase, detail: detail,
                                       deviceName: name ?? peripheral?.name))
        publish()
    }

    private func attempt() {
        attemptWork?.cancel(); attemptWork = nil
        guard enabled, peripheral == nil, !scanning, let manager else { return }
        switch manager.state {
        case .poweredOn: break
        case .unauthorized:
            status(.noPermission, "RID2Caltopo needs Bluetooth permission. Turn it on in Settings > RID2Caltopo.")
            return
        case .unsupported:
            status(.unavailable, "This iPad has no Bluetooth Low Energy.")
            return
        case .poweredOff:
            status(.unavailable, "Bluetooth is off.")
            return
        default:
            status(.unavailable, "Waiting for Bluetooth.")
            return
        }
        if let id = remembered, !rejected.contains(id), directTriesUsed < Self.directTries,
           let p = manager.retrievePeripherals(withIdentifiers: [id]).first {
            directTriesUsed += 1
            connect(p, name: foundNames[id] ?? p.name)
            return
        }
        directTriesUsed = 0
        scan()
    }

    private func scan() {
        guard let manager else { return }
        found.removeAll()
        scanning = true
        scanRound += 1
        manager.scanForPeripherals(withServices: [Self.service], options: nil)
        status(.scanning, "Looking for the Desk Pod (scan \(scanRound) of \(Self.scanRounds)).", nil)
        let w = DispatchWorkItem { [weak self] in self?.finishScan() }
        scanWork = w
        queue.asyncAfter(deadline: .now() + Self.scanSeconds, execute: w)
    }

    private func stopScan() {
        scanWork?.cancel(); scanWork = nil
        guard scanning else { return }
        scanning = false
        manager?.stopScan()
    }

    private func finishScan() {
        guard scanning else { return }
        stopScan()
        guard enabled else { return }
        let pick = remembered.flatMap { found[$0]?.0 } ?? found.values.max(by: { $0.1 < $1.1 })?.0
        guard let pick else {
            if scanRound < Self.scanRounds {
                let w = DispatchWorkItem { [weak self] in self?.scan() }
                scanWork = w
                queue.asyncAfter(deadline: .now() + Self.scanGapSeconds, execute: w)
            } else {
                scanRound = 0
                retryLater(.retryWait, "No Desk Pod found. Check that it is powered and nearby.")
            }
            return
        }
        scanRound = 0
        if found.count > 1 { log.info("ble: \(found.count) Meshtastic devices found; using \(foundNames[pick.identifier] ?? "the strongest")") }
        connect(pick, name: foundNames[pick.identifier] ?? pick.name)
    }

    private func connect(_ p: CBPeripheral, name: String?) {
        guard let manager else { return }
        peripheral = p
        p.delegate = self
        handshakeWritten = false
        sessionSerial += 1
        status(.connecting, "Connecting to \(name ?? "the Desk Pod").", name)
        manager.connect(p, options: nil)
        let serial = sessionSerial
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.sessionSerial == serial else { return }
            self.sessionLost("no connection after \(Int(Self.connectTimeout)) s")
        }
        connectWork = w
        queue.asyncAfter(deadline: .now() + Self.connectTimeout, execute: w)
    }

    private func retryLater(_ phase: Model.LinkPhase, _ detail: String) {
        guard enabled else { return }
        let delay = backoff.nextDelayMs()
        status(phase, "\(detail) Retrying in \(delay / 1000) s.")
        attemptWork?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.attempt() }
        attemptWork = w
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delay)), execute: w)
    }

    private func onConnected(_ p: CBPeripheral) {
        connectWork?.cancel(); connectWork = nil
        log.info("ble: connected to \(p.name ?? "Desk Pod")")
        status(.configuring, "Connected; reading the Desk Pod's configuration.")
        let q = BtrxGattQueue<Op>(start: { [unowned self] in self.startOp($0) },
                                  onStartFailed: { [weak self] _ in
                                      guard let self else { return }
                                      // Leave the queue's call stack before tearing the session down.
                                      self.queue.async { [self] in self.sessionLost("iPadOS refused a Bluetooth operation") }
                                  })
        gattQueue = q
        drain = BtrxFromRadioDrain { q.enqueue(.readFromRadio) }
        p.discoverServices([Self.service])
        armOpTimeout("service discovery")
    }

    private func onCharacteristics(_ p: CBPeripheral, _ error: Error?) {
        opWork?.cancel(); opWork = nil
        let chars = p.services?.first { $0.uuid == Self.service }?.characteristics ?? []
        toRadio = chars.first { $0.uuid == Self.toRadioUUID }
        fromRadio = chars.first { $0.uuid == Self.fromRadioUUID }
        fromNum = chars.first { $0.uuid == Self.fromNumUUID }
        guard error == nil, toRadio != nil, fromRadio != nil, fromNum != nil else {
            sessionLost("the device does not offer the Meshtastic phone API (\(error?.localizedDescription ?? "characteristics missing"))")
            return
        }
        log.info("ble: max write \(p.maximumWriteValueLength(for: .withResponse)) bytes")
        gattQueue?.enqueue(.enableNotify)
    }

    private func startClient() {
        let c = BtrxClient(send: { [weak self] bytes in self?.gattQueue?.enqueue(.write(bytes)) }, log: log, listener: self,
                           framing: .packet, connectBurstMs: BtrxClient.connectBurstMs)
        client = c
        model.attach(c)
        c.start()
        let serial = sessionSerial
        schedule(Self.roleTimeout, serial) { [weak self] in self?.checkRole(c, retry: true) }
        scheduleRepeating(Self.heartbeatSeconds, serial) { [weak self] in self?.client?.sendHeartbeat() }
        scheduleRepeating(Self.summarySeconds, serial) { [weak self] in
            if let c = self?.client { self?.log.info("ble summary: \(c.summary())") }
        }
    }

    private func onReady() {
        backoff.reset()
        directTriesUsed = 0
        guard let p = peripheral else { return }
        remembered = p.identifier
        events.rememberPeripheral(p.identifier, p.name)
        status(.ready, "Connected over Bluetooth.")
    }

    private func checkRole(_ c: BtrxClient, retry: Bool) {
        guard client === c else { return }
        if c.state != .ready {
            log.warn("ble: no Meshtastic config_complete after \(Int(Self.roleTimeout))s")
        } else if c.localRole != nil {
            return
        } else if retry {
            log.warn("ble: no ROLE_REPORT from the Desk Pod; asking again")
            _ = c.sendRoleGet()
            schedule(Self.roleTimeout, sessionSerial) { [weak self] in self?.checkRole(c, retry: false) }
        } else {
            log.warn("ble: no ROLE_REPORT from the Desk Pod after a retry; keeping the link open and decoding frames.")
        }
    }

    // MARK: GATT operations

    private func startOp(_ op: Op) -> Bool {
        guard let p = peripheral, p.state == .connected else { return false }
        switch op {
        case .enableNotify:
            guard let c = fromNum else { return false }
            p.setNotifyValue(true, for: c)
            armOpTimeout("enabling FromNum notifications")
        case .write(let bytes):
            guard let c = toRadio else { return false }
            p.writeValue(Data(bytes), for: c, type: .withResponse)
            armOpTimeout("a ToRadio write")
        case .readFromRadio:
            guard let c = fromRadio else { return false }
            p.readValue(for: c)
            armOpTimeout("a FromRadio read")
        }
        return true
    }

    private func armOpTimeout(_ what: String) {
        opWork?.cancel()
        let serial = sessionSerial
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.sessionSerial == serial else { return }
            self.sessionLost("\(what) got no answer in \(Int(Self.opTimeout)) s")
        }
        opWork = w
        queue.asyncAfter(deadline: .now() + Self.opTimeout, execute: w)
    }

    private func completeOp() {
        opWork?.cancel(); opWork = nil
        gattQueue?.complete()
    }

    private func authHint(_ error: Error, _ what: String) -> String {
        let ns = error as NSError
        if ns.domain == CBATTErrorDomain,
           ns.code == CBATTError.insufficientAuthentication.rawValue || ns.code == CBATTError.insufficientEncryption.rawValue {
            return "\(what): not paired. Enter the PIN printed on the Desk Pod (\(MeshtasticPhoneAPI.defaultPIN) by default); if it was re-flashed, forget it in iPad Settings > Bluetooth first"
        }
        if ns.domain == CBErrorDomain, ns.code == CBError.peerRemovedPairingInformation.rawValue {
            return "\(what): the Desk Pod lost its pairing. Forget it in iPad Settings > Bluetooth, then connect again"
        }
        return "\(what) (\(error.localizedDescription))"
    }

    // MARK: session end

    private func sessionLost(_ reason: String) {
        guard peripheral != nil || client != nil else { return }
        log.warn("ble: session ended: \(reason)")
        closeSession(reason)
        retryLater(.retryWait, "Bluetooth link lost: \(reason).")
    }

    private func closeSession(_ reason: String) {
        opWork?.cancel(); opWork = nil
        connectWork?.cancel(); connectWork = nil
        sessionTimers.forEach { $0.cancel() }
        sessionTimers.removeAll()
        gattQueue?.clear(); gattQueue = nil
        drain?.reset(); drain = nil
        sessionSerial += 1
        if let c = client {
            log.info("ble close (\(reason)); \(c.summary())")
            c.close()
            model.detach(c, reason: reason)
        }
        client = nil
        if let p = peripheral { manager?.cancelPeripheralConnection(p) }
        peripheral = nil
        toRadio = nil; fromRadio = nil; fromNum = nil
    }

    private func stop(reason: String, phase: Model.LinkPhase, detail: String) {
        enabled = false
        attemptWork?.cancel(); attemptWork = nil
        stopScan()
        scanRound = 0
        directTriesUsed = 0
        closeSession(reason)
        status(phase, detail, nil)
    }

    // MARK: timers and publishing

    private func schedule(_ seconds: TimeInterval, _ serial: Int, _ block: @escaping () -> Void) {
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.sessionSerial == serial else { return }
            block()
        }
        sessionTimers.append(w)
        queue.asyncAfter(deadline: .now() + seconds, execute: w)
    }

    private func scheduleRepeating(_ seconds: TimeInterval, _ serial: Int, _ block: @escaping () -> Void) {
        schedule(seconds, serial) { [weak self] in
            block()
            self?.scheduleRepeating(seconds, serial, block)
        }
    }

    private func tick() {
        model.tick()
        publish()
    }

    private func publish() { events.snapshot(model.snapshot()) }
}

// MARK: - CBCentralManagerDelegate

extension BtrxBleCentral: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            attempt()
        } else {
            stopScan()
            if peripheral != nil { closeSession("Bluetooth state \(central.state.rawValue)") }
            attempt() // reports unavailable / permission
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard scanning, !rejected.contains(p.identifier) else { return }
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? p.name
        if found[p.identifier] == nil { log.info("ble: found \(name ?? "unnamed") rssi=\(rssi.intValue)") }
        found[p.identifier] = (p, rssi.intValue)
        if let name { foundNames[p.identifier] = name }
        if p.identifier == remembered { finishScan() }
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        guard p === peripheral else { return }
        onConnected(p)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        guard p === peripheral else { return }
        sessionLost(error.map { authHint($0, "connection failed") } ?? "connection failed")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        guard p === peripheral else { return }
        sessionLost(error.map { authHint($0, "disconnected") } ?? "disconnected")
    }
}

// MARK: - CBPeripheralDelegate

extension BtrxBleCentral: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard p === peripheral else { return }
        guard error == nil, let s = p.services?.first(where: { $0.uuid == Self.service }) else {
            opWork?.cancel(); opWork = nil
            sessionLost("the device does not offer the Meshtastic phone API (\(error?.localizedDescription ?? "service missing"))")
            return
        }
        p.discoverCharacteristics([Self.toRadioUUID, Self.fromRadioUUID, Self.fromNumUUID], for: s)
        armOpTimeout("characteristic discovery")
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard p === peripheral else { return }
        onCharacteristics(p, error)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        guard p === peripheral, c.uuid == Self.fromNumUUID else { return }
        completeOp()
        if let error { sessionLost(authHint(error, "FromNum notifications could not be enabled")); return }
        startClient()
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        guard p === peripheral else { return }
        completeOp()
        if let error { sessionLost(authHint(error, "a ToRadio write failed")); return }
        if !handshakeWritten { handshakeWritten = true; drain?.kick() }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard p === peripheral else { return }
        if c.uuid == Self.fromNumUUID {
            if error == nil { drain?.kick() }
            return
        }
        guard c.uuid == Self.fromRadioUUID else { return }
        completeOp()
        guard let d = drain else { return }
        if let error {
            d.reset()
            sessionLost(authHint(error, "a FromRadio read failed"))
            return
        }
        let value = [UInt8](c.value ?? Data())
        if d.onRead(value) { client?.onFromRadio(value) }
    }
}

// MARK: - BtrxClientListener

extension BtrxBleCentral: BtrxClientListener {
    func onConfigured(localNode: UInt32) {
        model.onConfigured(localNode: localNode)
        onReady()
    }

    func onLocalRoleReport(_ report: BtrxProtocol.RoleReport) {
        model.onLocalRoleReport(report)
        if report.role != BtrxProtocol.roleDeskPod {
            if let id = peripheral?.identifier { rejected.insert(id) }
            // Leave the client's call stack before tearing the session down.
            queue.async { [weak self] in self?.sessionLost("not a Desk Pod (role=\(report.role))") }
        }
        publish()
    }

    func onFrame(_ frame: BtrxProtocol.Frame, meta: BtrxRadioMeta) {
        model.onFrame(frame, meta: meta)
    }

    func onVersionRefused(fromNode: UInt32, version: Int) {}
}

/// Main-actor face of the Desk Pod link for SwiftUI: the published snapshot, the
/// opt-in Bluetooth toggle (default off) and the remembered Desk Pod and paired pod.
@MainActor
public final class BtrxDeskPodCenter: ObservableObject {
    public typealias Model = BtrxDeskPodModel

    @Published public private(set) var snapshot = Model.Snapshot()
    @Published public private(set) var bluetoothEnabled: Bool

    private static let keyEnabled = "btrx.desk_pod.ble_enabled"
    private static let keyPeripheral = "btrx.desk_pod.peripheral"
    private static let keyName = "btrx.desk_pod.name"
    private static let keyPaired = "btrx.desk_pod.paired_pod"

    private let defaults: UserDefaults
    private var central: BtrxBleCentral!

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        bluetoothEnabled = defaults.bool(forKey: Self.keyEnabled)
        let paired = defaults.data(forKey: Self.keyPaired).flatMap { try? JSONDecoder().decode(Model.PairedPod.self, from: $0) }
        let events = BtrxBleCentral.Events(
            snapshot: { [weak self] s in Task { @MainActor in self?.snapshot = s } },
            rememberPeripheral: { [weak self] id, name in Task { @MainActor in self?.remember(id, name) } },
            paired: { [weak self] pod in Task { @MainActor in self?.savePaired(pod) } }
        )
        central = BtrxBleCentral(events: events, paired: paired)
    }

    /// Call once at launch; connects only when the operator turned Bluetooth on.
    public func start() {
        if bluetoothEnabled { central.setEnabled(true, remembered: rememberedPeripheral) }
    }

    public var rememberedPeripheral: UUID? { defaults.string(forKey: Self.keyPeripheral).flatMap(UUID.init(uuidString:)) }

    public func setBluetoothEnabled(_ on: Bool) {
        bluetoothEnabled = on
        defaults.set(on, forKey: Self.keyEnabled)
        central.setEnabled(on, remembered: rememberedPeripheral)
    }

    public func forgetDeskPod() {
        defaults.removeObject(forKey: Self.keyPeripheral)
        defaults.removeObject(forKey: Self.keyName)
        central.forgetDeskPod()
    }

    public func forgetPairedPod() {
        defaults.removeObject(forKey: Self.keyPaired)
        central.forgetPairedPod()
    }

    public func startPairing() { central.startPairing() }
    public func confirmPod() { central.confirmPod() }
    public func cancelPairing() { central.cancelPairing() }
    public func clearPairingResult() { central.clearPairingResult() }

    private func remember(_ id: UUID, _ name: String?) {
        defaults.set(id.uuidString, forKey: Self.keyPeripheral)
        if let name { defaults.set(name, forKey: Self.keyName) }
    }

    private func savePaired(_ pod: Model.PairedPod) {
        if let data = try? JSONEncoder().encode(pod) { defaults.set(data, forKey: Self.keyPaired) }
    }
}
