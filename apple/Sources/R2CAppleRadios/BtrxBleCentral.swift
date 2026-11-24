import Combine
@preconcurrency import CoreBluetooth
import Foundation
import R2CCore

/// Writes btrx log lines to the app's diagnostic log (AppleLog installs the writer).
final class BtrxAppleLogger: BtrxLogger, @unchecked Sendable {
    init() {
        BtrxYawLog.sink = { message in
            OperationalBridgePacketLog.write?("DEBUG", "Btrx", message)
        }
    }
    func info(_ message: String) { OperationalBridgePacketLog.write?("INFO", "Btrx", message) }
    func warn(_ message: String) { OperationalBridgePacketLog.write?("WARN", "Btrx", message) }
    func debug(_ message: String) { OperationalBridgePacketLog.write?("DEBUG", "Btrx", message) }
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
        /// The saved-ignore src ids changed (build 0x0106; persisted with the paired pod).
        var savedIgnores: @Sendable ([Int]) -> Void = { _ in }
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
    /// While a patient connect waits: one Meshtastic-UUID scan this often. Same as Android.
    static let patientScanSeconds: TimeInterval = 60
    static let opTimeout: TimeInterval = 10
    /// First connection to a Desk Pod: iPadOS shows its PIN sheet when FromNum
    /// notifications are enabled (an encrypted write) and waits for the operator.
    static let pinEntryTimeout: TimeInterval = 90
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
    /// A patient connect (no timeout) to the remembered Desk Pod is pending (Nov 16 field test:
    /// after a drop the Desk Pod stopped advertising for minutes). Same as Android.
    private var patient = false
    /// The running scan is a patient-wait scan (no status change, one round).
    private var patientScanning = false
    private var patientWork: DispatchWorkItem?
    private let poweredOffLock = NSLock()
    private var poweredOff = false
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
    private var attMtu = 23
    private var sessionSerial = 0
    private var completedSessions = Set<UUID>()

    private var attemptWork: DispatchWorkItem?
    private var scanWork: DispatchWorkItem?
    private var connectWork: DispatchWorkItem?
    private var opWork: DispatchWorkItem?
    private var sessionTimers: [DispatchWorkItem] = []
    private var tickTimer: DispatchSourceTimer?
    /// The BTRX bearing layer (Developer Tools); confined to ``queue`` like the model.
    private(set) var bearing: BtrxBearingLayer!
    private var bearingTimer: DispatchSourceTimer?

    init(events: Events, paired: Model.PairedPod?, savedIgnores: [Int] = []) {
        self.events = events
        super.init()
        queue.sync {
            model = Model(log: log, onPaired: { pod in events.paired(pod) }, onSavedIgnores: { ids in events.savedIgnores(ids) })
            model.restorePairedPod(paired, savedIgnoreIds: savedIgnores)
            model.setLink(Model.LinkStatus(kind: .none, phase: .off, detail: "Bluetooth link is off."))
        }
        let log = self.log
        bearing = BtrxBearingLayer(log: { log.info($0) }, traceLog: { log.info($0) })
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
        tickTimer = timer
    }

    deinit { tickTimer?.cancel(); bearingTimer?.cancel() }

    // MARK: bearing map layer (docs/btrx/BEARING-LAYER-spec.md section 7)

    /// Turns the layer on or off on ``queue``; `done(false)` when it could not start.
    func setBearing(_ on: Bool, settings: BtrxBearingSettings, trace: Bool, steering: String?, liveStreams: Int,
                    onSnapshot: @escaping @Sendable (BtrxBearingSnapshot?) -> Void,
                    onStatus: @escaping @Sendable (BtrxBearingStatus?) -> Void,
                    done: @escaping @Sendable (Bool, String?) -> Void) {
        queue.async { [self] in
            if on {
                bearing.steeringDesignator = steering
                bearing.liveStreamCount = liveStreams
                bearing.onSnapshot = onSnapshot
                bearing.onStatus = onStatus
                guard bearing.start(settings: settings, trace: trace, linkUp: model.snapshot().link.phase == .ready) else {
                    done(false, bearing.lastError); return
                }
                BtrxBearingFeed.sink = bearing
                if bearingTimer == nil {
                    let t = DispatchSource.makeTimerSource(queue: queue)
                    let ms = BtrxBearingLayer.tickMs
                    t.schedule(deadline: .now() + .milliseconds(ms), repeating: .milliseconds(ms), leeway: .milliseconds(20))
                    t.setEventHandler { [weak self] in
                        guard let self else { return }
                        self.bearing.tick { seq, t1 in self.model.sendTimePing(pingSeq: seq, t1Ms: t1) }
                    }
                    t.resume()
                    bearingTimer = t
                }
                done(true, nil)
            } else {
                bearingTimer?.cancel()
                bearingTimer = nil
                BtrxBearingFeed.sink = nil
                bearing.stop()
                done(false, nil)
            }
        }
    }

    func setBearingFade(_ settings: BtrxBearingSettings) { queue.async { [self] in bearing.setFade(settings) } }
    func setBearingLaunchAutoMute(_ on: Bool) { queue.async { [self] in bearing.setLaunchAutoMute(on) } }
    func setBearingSteering(_ id: String?, liveStreams: Int) {
        if bearing.steeringDesignator != id { log.debug("bearing: pose stream \(id ?? "none")") }
        bearing.steeringDesignator = id
        bearing.liveStreamCount = liveStreams
    }

    // The map chip's "No Drone Pod reports" (Ken, Nov 16): last live frame from the paired pod.
    private let frameClockLock = NSLock()
    private var lastDronePodReportUptimeNs: UInt64 = 0
    /// Seconds since the last Drone Pod report (`BtrxMapStatus.isDronePodReport`, any thread); nil = none yet.
    func secondsSinceDronePodReport() -> Double? {
        frameClockLock.lock(); defer { frameClockLock.unlock() }
        guard lastDronePodReportUptimeNs != 0 else { return nil }
        return Double(DispatchTime.now().uptimeNanoseconds &- lastDronePodReportUptimeNs) / 1e9
    }

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
    func replacePairedPod() { queue.async { [self] in model.replacePairedPod(); publish() } }
    func startPairing() { queue.async { [self] in model.startPairing(); publish() } }
    func confirmPod() { queue.async { [self] in model.confirmPod(); publish() } }
    func repairDronePod() { queue.async { [self] in model.repairDronePod(); publish() } }
    func answerButtonConfirm(_ confirm: Bool) { queue.async { [self] in model.answerButtonConfirm(confirm); publish() } }
    func setButtonPanelAvailable(_ available: Bool) { queue.async { [self] in model.setButtonPanelAvailable(available); publish() } }
    func ignoreSourceSaved(_ srcId: Int) { queue.async { [self] in model.ignoreSourceSaved(srcId); publish() } }
    func ignoreAllExcept(_ srcIds: [Int], keepId: String, localCount: Int) {
        queue.async { [self] in model.ignoreAllExcept(srcIds, keepId: keepId, localCount: localCount); publish() }
    }
    func restoreIgnoreAll() { queue.async { [self] in model.restoreIgnoreAll(); publish() } }
    func unignoreSourceSaved(_ srcId: Int) { queue.async { [self] in model.unignoreSourceSaved(srcId); publish() } }
    func cancelPairing() { queue.async { [self] in model.cancelPairing(); publish() } }
    func clearPairingResult() { queue.async { [self] in model.clearPairingResult(); publish() } }
    func muteSteeringSource() { queue.async { [self] in model.muteSteeringSource(); publish() } }
    func clearSteeringMutes() { queue.async { [self] in model.clearSteeringMutes(); publish() } }
    func setSteeringRollCorrection(_ on: Bool) { queue.async { [self] in model.setSteeringRollCorrection(on); publish() } }
    func startImuCal() { queue.async { [self] in model.startImuCal(); publish() } }
    func startImuCalStarboard() { queue.async { [self] in model.startImuCalStarboard(); publish() } }
    func checkImuCal() { queue.async { [self] in model.checkImuCal(); publish() } }
    func clearImuCal() { queue.async { [self] in model.clearImuCal(); publish() } }
    func cancelImuCal() { queue.async { [self] in model.cancelImuCal(); publish() } }
    func closeImuCalResult() { queue.async { [self] in model.closeImuCalResult(); publish() } }

    // MARK: connection

    private func status(_ phase: Model.LinkPhase, _ detail: String, _ name: String? = nil) {
        model.setLink(Model.LinkStatus(kind: phase == .off ? .none : .bluetooth, phase: phase, detail: detail,
                                       deviceName: name ?? peripheral?.name,
                                       failedAttempts: backoff.failures, lastFailure: backoff.lastFailure))
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
        if let id = remembered, !rejected.contains(id),
           let p = manager.retrievePeripherals(withIdentifiers: [id]).first {
            if directTriesUsed < Self.directTries {
                directTriesUsed += 1
                connect(p, name: foundNames[id] ?? p.name)
            } else {
                // Direct tries used up: one pending connect, no timeout, until it connects or the link stops.
                log.debug("ble: \(Self.directTries) direct connects failed; patient connect to \(foundNames[id] ?? p.name ?? "the Desk Pod") "
                    + "pending until it advertises (scan every \(Int(Self.patientScanSeconds)) s)")
                connect(p, name: foundNames[id] ?? p.name, patientWait: true)
            }
            return
        }
        directTriesUsed = 0
        scan()
    }

    /// `patientRound`: one scan while a patient connect is pending; it keeps the CONNECTING
    /// status and the pending connect unless it finds a Desk Pod.
    private func scan(patientRound: Bool = false) {
        guard let manager else { return }
        found.removeAll()
        scanning = true
        patientScanning = patientRound
        manager.scanForPeripherals(withServices: [Self.service], options: nil)
        if patientRound {
            log.debug("ble: scanning for the Desk Pod while the patient connect waits")
        } else {
            scanRound += 1
            status(.scanning, "Looking for the Desk Pod (scan \(scanRound) of \(Self.scanRounds)).", nil)
        }
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
        let wasPatient = patientScanning
        patientScanning = false
        stopScan()
        guard enabled else { return }
        let pick = remembered.flatMap { found[$0]?.0 } ?? found.values.max(by: { $0.1 < $1.1 })?.0
        if wasPatient {
            guard patient else { return }
            guard let pick else {
                log.debug("ble: Desk Pod not advertising; the patient connect keeps waiting")
                schedulePatientScan()
                return
            }
            log.debug("ble: \(foundNames[pick.identifier] ?? "Desk Pod") advertising again; connecting directly")
            closeSession("patient connect replaced by a direct connect")
            connect(pick, name: foundNames[pick.identifier] ?? pick.name)
            return
        }
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

    /// `patientWait`: no timeout (an iOS connect stays pending until the peripheral
    /// advertises), with a scan every `patientScanSeconds`.
    private func connect(_ p: CBPeripheral, name: String?, patientWait: Bool = false) {
        guard let manager else { return }
        peripheral = p
        p.delegate = self
        handshakeWritten = false
        sessionSerial += 1
        status(.connecting, patientWait
               ? "Waiting for \(name ?? "the Desk Pod") to come back; it reconnects as soon as the Desk Pod advertises."
               : "Connecting to \(name ?? "the Desk Pod").", name)
        manager.connect(p, options: nil)
        if patientWait {
            patient = true
            schedulePatientScan()
            return
        }
        let serial = sessionSerial
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.sessionSerial == serial else { return }
            self.sessionLost("no connection after \(Int(Self.connectTimeout)) s")
        }
        connectWork = w
        queue.asyncAfter(deadline: .now() + Self.connectTimeout, execute: w)
    }

    private func schedulePatientScan() {
        patientWork?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.enabled, self.patient, self.client == nil, !self.scanning else { return }
            self.scan(patientRound: true)
        }
        patientWork = w
        queue.asyncAfter(deadline: .now() + Self.patientScanSeconds, execute: w)
    }

    /// The device's Bluetooth is turned off (any thread); for the map status chip.
    func bluetoothPoweredOff() -> Bool {
        poweredOffLock.lock(); defer { poweredOffLock.unlock() }
        return poweredOff
    }

    /// `failure`: short reason for the Desk Pod screen's "reconnecting" line (default: the detail).
    private func retryLater(_ phase: Model.LinkPhase, _ detail: String, failure: String? = nil) {
        guard enabled else { return }
        let delay = backoff.nextDelayMs(failure: failure ?? (detail.hasSuffix(".") ? String(detail.dropLast()) : detail))
        status(phase, "\(detail) Retrying in \(delay / 1000) s.")
        attemptWork?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.attempt() }
        attemptWork = w
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delay)), execute: w)
    }

    private func onConnected(_ p: CBPeripheral) {
        connectWork?.cancel(); connectWork = nil
        patientWork?.cancel(); patientWork = nil
        if patient { log.debug("ble: patient connect succeeded") }
        patient = false
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
        // The Desk Pod rejects long writes, so a ToRadio must fit one ATT write (MTU - 3).
        // iPadOS reports MTU - 3 as the without-response length.
        attMtu = p.maximumWriteValueLength(for: .withoutResponse) + 3
        log.info("ble: ATT MTU \(attMtu)")
        if attMtu < BtrxClient.minUsableMTU { log.warn("ERROR: ble: negotiated MTU \(attMtu) is below \(BtrxClient.minUsableMTU); btrx writes may not fit") }
        gattQueue?.enqueue(.enableNotify)
    }

    private func startClient() {
        let c = BtrxClient(send: { [weak self] bytes in self?.gattQueue?.enqueue(.write(bytes)) }, log: log, listener: self,
                           framing: .packet)
        client = c
        c.setAttMtu(attMtu)
        model.attach(c)
        c.start()
        let serial = sessionSerial
        schedule(Double(BtrxClient.rolePollMs) / 1000, serial) { [weak self] in self?.checkRole(c, retried: false) }
        scheduleRepeating(Self.heartbeatSeconds, serial) { [weak self] in self?.client?.sendHeartbeat() }
        scheduleRepeating(Self.summarySeconds, serial) { [weak self] in
            if let c = self?.client { self?.log.info("ble summary: \(c.summary())") }
        }
    }

    private func onReady() {
        backoff.reset()
        directTriesUsed = 0
        guard let p = peripheral else { return }
        completedSessions.insert(p.identifier)
        remembered = p.identifier
        events.rememberPeripheral(p.identifier, p.name)
        status(.ready, "Connected over Bluetooth.")
    }

    /// The ROLE_REPORT wait (timing in ``BtrxClient/roleCheck(retried:)``: from ROLE_GET, after the connect backlog).
    private func checkRole(_ c: BtrxClient, retried: Bool) {
        guard client === c else { return }
        let r = c.roleCheck(retried: retried)
        switch r.action {
        case .done: break
        case .wait:
            schedule(Double(r.delayMs) / 1000, sessionSerial) { [weak self] in self?.checkRole(c, retried: retried) }
        case .retry:
            log.warn("ble: no ROLE_REPORT from the Desk Pod within \(BtrxClient.roleTimeoutMs / 1000) s of ROLE_GET; asked again")
            schedule(Double(r.delayMs) / 1000, sessionSerial) { [weak self] in self?.checkRole(c, retried: true) }
        case .giveUp:
            log.warn("ble: no ROLE_REPORT from the Desk Pod after a retry; keeping the link open and decoding frames.")
        case .noConfig:
            log.warn("ble: no Meshtastic config_complete after \(BtrxClient.configTimeoutMs / 1000)s")
        }
    }

    // MARK: GATT operations

    private func startOp(_ op: Op) -> Bool {
        guard let p = peripheral, p.state == .connected else { return false }
        switch op {
        case .enableNotify:
            guard let c = fromNum else { return false }
            p.setNotifyValue(true, for: c)
            if firstSession(with: p) {
                // Until a session with this Desk Pod has completed, the PIN sheet may be up.
                status(.bonding, BtrxDeskPodText.enterPIN)
                armOpTimeout("enabling FromNum notifications (PIN entry)", seconds: Self.pinEntryTimeout)
            } else {
                armOpTimeout("enabling FromNum notifications")
            }
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

    private func armOpTimeout(_ what: String, seconds: TimeInterval = BtrxBleCentral.opTimeout) {
        opWork?.cancel()
        let serial = sessionSerial
        let w = DispatchWorkItem { [weak self] in
            guard let self, self.sessionSerial == serial else { return }
            self.sessionLost("\(what) got no answer in \(Int(seconds)) s")
        }
        opWork = w
        queue.asyncAfter(deadline: .now() + seconds, execute: w)
    }

    /// True until a session with this peripheral reached READY (it is then remembered).
    private func firstSession(with p: CBPeripheral) -> Bool {
        !completedSessions.contains(p.identifier) && remembered != p.identifier
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
        retryLater(.retryWait, "Bluetooth link lost: \(reason).", failure: reason)
    }

    private func closeSession(_ reason: String) {
        opWork?.cancel(); opWork = nil
        connectWork?.cancel(); connectWork = nil
        patientWork?.cancel(); patientWork = nil
        patient = false
        if patientScanning { patientScanning = false; stopScan() }
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
        poweredOffLock.lock(); poweredOff = central.state == .poweredOff; poweredOffLock.unlock()
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
        // Debug level, as Android: the session log keeps debug lines.
        if found[p.identifier] == nil { log.debug("ble: found \(name ?? "unnamed") rssi=\(rssi.intValue)") }
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
        status(.configuring, "Connected; reading the Desk Pod's configuration.")
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
        _ = d.onRead(value)
        // Empty reads too: the first one after config_complete ends the connect backlog.
        client?.onFromRadio(value)
    }
}

// MARK: - BtrxClientListener

extension BtrxBleCentral: BtrxClientListener {
    func onConfigured(localNode: UInt32) {
        model.onConfigured(localNode: localNode)
        bearing.onConnected()
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
        let origin = model.bearingOrigin(frame, meta: meta)
        if BtrxMapStatus.isDronePodReport(frameType: frame.type, backlog: meta.backlog, fromPairedPod: origin == Model.bearingFromPod) {
            frameClockLock.lock(); lastDronePodReportUptimeNs = DispatchTime.now().uptimeNanoseconds; frameClockLock.unlock()
        }
        model.onFrame(frame, meta: meta)
        // The overlay follows each STEER (about every second), not only the 1 s tick.
        if frame.type == BtrxProtocol.STEER && !meta.backlog { publish() }
        if bearing.isRunning, frame.type == BtrxProtocol.STEER || frame.type == BtrxProtocol.TIME_PONG || frame.type == BtrxProtocol.HEALTH {
            if origin != Model.bearingFromOther {
                bearing.onFrame(frame, meta: meta, fromPairedPod: origin == Model.bearingFromPod, fromDeskPod: origin == Model.bearingFromDesk)
            }
        }
    }

    func onVersionRefused(fromNode: UInt32, version: Int) {}

    func onPacketDropped(encrypted: Bool) { model.onPacketDropped(encrypted: encrypted) }
    func onDeskDeviceMetrics(_ metrics: MeshtasticPhoneAPI.DeviceMetrics, source: String) { model.onDeskDeviceMetrics(metrics, source: source) }
}

/// Main-actor face of the Desk Pod link for SwiftUI: the published snapshot, the
/// opt-in Bluetooth toggle (default off) and the remembered Desk Pod and paired pod.
@MainActor
public final class BtrxDeskPodCenter: ObservableObject {
    public typealias Model = BtrxDeskPodModel

    @Published public private(set) var snapshot = Model.Snapshot()
    @Published public private(set) var bluetoothEnabled: Bool
    /// Steering overlay card shown on the stream tile (hide/show; persisted). Same as Android.
    @Published public private(set) var overlayShown: Bool

    private static let keyEnabled = "btrx.desk_pod.ble_enabled"
    private static let keyOverlayShown = "btrx.steer_overlay_shown"
    private static let keyPeripheral = "btrx.desk_pod.peripheral"
    private static let keyName = "btrx.desk_pod.name"
    private static let keyPaired = "btrx.desk_pod.paired_pod"
    /// src ids the paired Drone Pod saved on its ignore list (build 0x0106). Same as Android.
    private static let keySavedIgnores = "btrx.desk_pod.paired_pod_saved_ignores"

    private let defaults: UserDefaults
    private var central: BtrxBleCentral!

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        bluetoothEnabled = defaults.bool(forKey: Self.keyEnabled)
        overlayShown = defaults.object(forKey: Self.keyOverlayShown) as? Bool ?? true
        bearingSettings = (defaults.data(forKey: Self.keyBearingSettings).flatMap { try? JSONDecoder().decode(BtrxBearingSettings.self, from: $0) } ?? BtrxBearingSettings()).clamped()
        bearingLagTrace = defaults.bool(forKey: Self.keyBearingLagTrace)
        bearingDisplay = (defaults.data(forKey: Self.keyBearingDisplay).flatMap { try? JSONDecoder().decode(BtrxBearingDisplay.self, from: $0) } ?? BtrxBearingDisplay()).clamped()
        let paired = defaults.data(forKey: Self.keyPaired).flatMap { try? JSONDecoder().decode(Model.PairedPod.self, from: $0) }
        let events = BtrxBleCentral.Events(
            snapshot: { [weak self] s in Task { @MainActor in self?.apply(s) } },
            rememberPeripheral: { [weak self] id, name in Task { @MainActor in self?.remember(id, name) } },
            paired: { [weak self] pod in Task { @MainActor in self?.savePaired(pod) } },
            savedIgnores: { [weak self] ids in Task { @MainActor in self?.saveSavedIgnores(ids) } }
        )
        let savedIgnores = (defaults.array(forKey: Self.keySavedIgnores) as? [Int]) ?? []
        central = BtrxBleCentral(events: events, paired: paired, savedIgnores: savedIgnores)
        central.bearing.setLaunchAutoMute(bearingDisplay.launchAutoMute)
    }

    /// "BTRX: Active" / "BTRX: Inactive - <reason>" for the chip at the top of the map;
    /// nil (no chip) until BTRX is set up. Same as Android.
    @Published public private(set) var mapStatus: BtrxMapStatus.Status?
    private var chipSession = BtrxMapStatus.Session()

    /// Explicit link-on, connect or pair action: two minutes of "Connecting", then hide if no Desk Pod.
    private func requestChipConnect() {
        chipSession.request(now: ProcessInfo.processInfo.systemUptime)
        updateMapStatus()
        DispatchQueue.main.asyncAfter(deadline: .now() + BtrxMapStatus.connectingWindow + 1) { [weak self] in self?.updateMapStatus() }
    }

    private func forgetChipSession() {
        chipSession.forget()
        updateMapStatus()
    }

    private func apply(_ s: Model.Snapshot) {
        snapshot = s
        updateMapStatus()
    }

    private func updateMapStatus() {
        chipSession.observe(ready: snapshot.link.phase == .ready && !snapshot.bluetoothPausedForUSB)
        let setUp = BtrxMapStatus.setUp(bluetoothLinkEnabled: bluetoothEnabled, deskPodRemembered: rememberedPeripheral != nil)
        let next = BtrxMapStatus.status(setUp: setUp, link: snapshot.link, bluetoothOff: setUp && central.bluetoothPoweredOff(),
                                        pausedForUSB: snapshot.bluetoothPausedForUSB,
                                        dronePodReporting: BtrxMapStatus.dronePodReporting(secondsSinceReport: central.secondsSinceDronePodReport()),
                                        deskPodSilent: snapshot.deskSilent, deskPodNotice: snapshot.deskRestartNotice,
                                        linkEnabled: bluetoothEnabled, readyEarlier: chipSession.readyEarlier,
                                        requestedAt: chipSession.requestedAt, now: ProcessInfo.processInfo.systemUptime,
                                        deskPodPowerOk: snapshot.deskSilentPowerOk)
        guard next != mapStatus else { return }
        mapStatus = next
        OperationalBridgePacketLog.write?("DEBUG", "Btrx", "map status: \(next?.text ?? "hidden")")
    }

    /// The BTRX status chip: opens the Desk Pod view whatever the link state.
    public func openDeskPodScreenFromStatus() {
        OperationalBridgePacketLog.write?("DEBUG", "Btrx", "map status tap: Desk Pod view")
        deskPodScreenRequest += 1
    }

    /// Call once at launch; connects only when the operator turned Bluetooth on (and then
    /// turns the bearing layer on, Nov 23).
    public func start() {
        if bluetoothEnabled { central.setEnabled(true, remembered: rememberedPeripheral) }
        if bluetoothEnabled { autoBearingOn("BTRX enabled at launch") }
    }

    public var rememberedPeripheral: UUID? { defaults.string(forKey: Self.keyPeripheral).flatMap(UUID.init(uuidString:)) }

    /// Hide or show the steering overlay card on the stream tile. Only the card: the link,
    /// STEER and DETECT handling, mutes and the Steering section keep working. Persisted.
    public func setOverlayShown(_ shown: Bool) {
        overlayShown = shown
        defaults.set(shown, forKey: Self.keyOverlayShown)
        OperationalBridgePacketLog.write?("INFO", "Btrx", "steering overlay \(shown ? "shown" : "hidden") (link, STEER and mutes unaffected)")
    }

    public func setBluetoothEnabled(_ on: Bool) {
        bluetoothEnabled = on
        defaults.set(on, forKey: Self.keyEnabled)
        central.setEnabled(on, remembered: rememberedPeripheral)
        if on { requestChipConnect() } else { forgetChipSession() }
        if on { autoBearingOn("Desk Pod Bluetooth link turned on") }
    }

    /// BTRX enabled, so the bearing layer goes on (unless it already is). Ken, Nov 23.
    private func autoBearingOn(_ reason: String) {
        guard BtrxBearingAutoOn.shouldTurnOn(btrxEnabled: true, bearingOn: bearingOn) else { return }
        OperationalBridgePacketLog.write?("INFO", "Btrx", "bearing layer on: \(reason)")
        setBearingOn(true)
    }

    public func forgetDeskPod() {
        defaults.removeObject(forKey: Self.keyPeripheral)
        defaults.removeObject(forKey: Self.keyName)
        central.forgetDeskPod()
        forgetChipSession()
    }

    public func forgetPairedPod() {
        defaults.removeObject(forKey: Self.keyPaired)
        defaults.removeObject(forKey: Self.keySavedIgnores)
        central.forgetPairedPod()
    }

    /// "Replace Drone Pod" after its confirmation: unpair the current pod here, then pair.
    public func replacePairedPod() {
        defaults.removeObject(forKey: Self.keyPaired)
        defaults.removeObject(forKey: Self.keySavedIgnores)
        requestChipConnect()
        central.replacePairedPod()
    }

    public func startPairing() { requestChipConnect(); central.startPairing() }
    public func confirmPod() { requestChipConnect(); central.confirmPod() }
    /// "Re-pair Drone Pod" after its confirmation: PAIR_OPEN plus the Desk Pod window (build 0x0106).
    public func repairDronePod() { requestChipConnect(); central.repairDronePod() }
    /// Desk Pod button panel (firmware 0x0108): Confirm / Cancel. Same as Android.
    public func answerButtonConfirm(_ confirm: Bool) { central.answerButtonConfirm(confirm) }
    /// The button panel can be seen (app active): keepalive and "shown" go out only then.
    public func setButtonPanelAvailable(_ available: Bool) { central.setButtonPanelAvailable(available) }
    /// "Own aircraft (saved)" / "Remove" on a source (STEER_MUTE op 3 / 4, build 0x0106).
    public func ignoreSourceSaved(_ srcId: Int) { central.ignoreSourceSaved(srcId) }
    /// Own-aircraft suggestion answers, published so the banner closes at once (Nov 17).
    @Published public private(set) var ownAnswers = BtrxOwnAnswers()
    /// The own-aircraft suggestion answered: recorded in the layer (never shown again for this
    /// source); "Own aircraft" is ignored here at once and saved on the pod when it keeps a list.
    public func answerOwnSuggestion(_ src: BtrxBearingSource, own: Bool) {
        bearing.answerOwnSuggestion(src, own: own)
        ownAnswers = bearing.ownAnswers
        if own && snapshot.savedIgnoreSupported && src.srcId != 0 { ignoreSourceSaved(src.srcId) }
    }
    public func unignoreSourceSaved(_ srcId: Int) { central.unignoreSourceSaved(srcId) }
    /// Keys "Ignore all except this" ignored here, published so "Restore ignored" shows at once (Nov 17).
    @Published public private(set) var ignoredByAll: Set<UInt64> = []
    /// "Ignore all except this": the planned keys ignored here (a flag write), the planned src_ids
    /// saved on the pod on the BTRX queue, paced by the model. Same as Android's BtrxManager.
    public func ignoreAllExcept(_ plan: BtrxIgnoreAllPlan) {
        bearing.applyIgnoreAll(plan)
        ignoredByAll = bearing.ignoredByAll
        central.ignoreAllExcept(plan.podSrcIds, keepId: plan.keepId, localCount: plan.count)
    }
    /// "Restore ignored": undoes only what "Ignore all except this" ignored, here and on the pod.
    public func restoreIgnoreAll() {
        bearing.restoreIgnoreAll()
        ignoredByAll = bearing.ignoredByAll
        central.restoreIgnoreAll()
    }
    public func cancelPairing() { central.cancelPairing() }
    public func clearPairingResult() { central.clearPairingResult() }
    /// "Mute" on the stream-view steering overlay.
    public func muteSteeringSource() { central.muteSteeringSource() }
    /// "Clear mutes" on the Desk Pod screen.
    public func clearSteeringMutes() { central.clearSteeringMutes() }
    /// Debug: steering roll correction (off until the bench roll check).
    public func setSteeringRollCorrection(_ on: Bool) { central.setSteeringRollCorrection(on) }
    /// Drone Pod IMU calibration (Desk Pod screen; design doc section 29.3).
    public func startImuCal() { central.startImuCal() }
    public func startImuCalStarboard() { central.startImuCalStarboard() }
    public func checkImuCal() { central.checkImuCal() }
    public func clearImuCal() { central.clearImuCal() }
    public func cancelImuCal() { central.cancelImuCal() }
    public func closeImuCalResult() { central.closeImuCalResult() }

    /// The stream the operator marked "Drone Pod is on this drone" (see BtrxSteeringTarget).
    @Published public private(set) var steeringStreamID: String?
    /// Live stream ids last reported by the app (the tiles that can carry the overlay).
    @Published public private(set) var liveSteeringStreams: Set<String> = []

    public func chooseSteeringStream(_ id: String?) {
        if steeringStreamID != id { OperationalBridgePacketLog.write?("INFO", "Btrx", "steering: overlay drone \(id ?? "cleared")") }
        steeringStreamID = id
        central.setBearingSteering(bearingSteeringStreamID, liveStreams: liveSteeringStreams.count)
    }

    /// The bearing layer's pose stream: the "Drone Pod is on this drone" choice, else the
    /// only live stream (the overlay's own rule, `BtrxSteeringTarget.effective`).
    public var bearingSteeringStreamID: String? { BtrxSteeringTarget.effective(steeringStreamID, live: liveSteeringStreams) }

    /// Increments when the map asks for the Desk Pod view (double tap while it is talking).
    @Published public private(set) var deskPodScreenRequest = 0

    /// The Desk Pod link is READY. (Until Nov 16 this also needed a Desk Pod frame within 10 s,
    /// which flapped with no Drone Pod reporting: the Desk Pod's own HEALTH comes every 30 s.)
    public func deskPodTalking() -> Bool { snapshot.link.phase == .ready }

    /// Map double tap: opens the Desk Pod view and returns true only while it is talking.
    @discardableResult
    public func openDeskPodScreenFromMap() -> Bool {
        guard deskPodTalking() else { return false }
        OperationalBridgePacketLog.write?("DEBUG", "Btrx", "map double tap: Desk Pod view")
        deskPodScreenRequest += 1
        return true
    }

    // MARK: bearing map layer (Developer Tools > BTRX; docs/btrx/BEARING-LAYER-spec.md)

    private static let keyBearingSettings = "btrx.bearing.fade"
    private static let keyBearingLagTrace = "btrx.bearing.lag_trace"
    private static let keyBearingDisplay = "btrx.bearing.display"

    /// "Show bearing layer on map". Ken, Nov 23: on by default, turned on whenever BTRX is enabled
    /// (at launch with the link on, and when the link is turned on); the switch still turns it
    /// off for the session. Not persisted, so no install keeps it off.
    @Published public private(set) var bearingOn = false
    @Published public private(set) var bearingSettings = BtrxBearingSettings()
    @Published public private(set) var bearingLagTrace = false
    /// Map thresholds and launch auto mute (Developer Tools > BTRX, Nov 20).
    @Published public private(set) var bearingDisplay = BtrxBearingDisplay()
    @Published public private(set) var bearingSnapshot: BtrxBearingSnapshot?
    @Published public private(set) var bearingError: String?
    /// Why the layer draws nothing, about once a second while it is on (nil while off).
    @Published public private(set) var bearingStatus: BtrxBearingStatus?
    /// The layer itself, for the map's tap query, mute and render notes (main-thread calls).
    public var bearing: BtrxBearingLayer { central.bearing }

    public func setBearingOn(_ on: Bool) {
        bearingOn = on
        central.setBearing(on, settings: bearingSettings, trace: bearingLagTrace, steering: bearingSteeringStreamID,
            liveStreams: liveSteeringStreams.count,
            onSnapshot: { [weak self] snap in Task { @MainActor in self?.bearingSnapshot = snap } },
            onStatus: { [weak self] status in Task { @MainActor in
                if self?.bearingStatus != status { self?.bearingStatus = status }
            } },
            done: { [weak self] running, error in Task { @MainActor in
                guard let self else { return }
                if on && !running { self.bearingOn = false }
                self.bearingError = error
                if !running { self.bearingSnapshot = nil; self.bearingStatus = nil }
            } })
    }

    /// Fade time and shape sliders: applied at once (next tick) and saved.
    public func setBearingSettings(_ settings: BtrxBearingSettings) {
        let s = settings.clamped()
        bearingSettings = s
        if let data = try? JSONEncoder().encode(s) { defaults.set(data, forKey: Self.keyBearingSettings) }
        central.setBearingFade(s)
    }

    /// Display thresholds and launch auto mute: applied at once and saved.
    public func setBearingDisplay(_ display: BtrxBearingDisplay) {
        let d = display.clamped()
        bearingDisplay = d
        if let data = try? JSONEncoder().encode(d) { defaults.set(data, forKey: Self.keyBearingDisplay) }
        central.setBearingLaunchAutoMute(d.launchAutoMute)
    }

    /// Lag trace (BBv1/BBLAG/BBRND lines in the session log); restarts a running layer.
    public func setBearingLagTrace(_ on: Bool) {
        bearingLagTrace = on
        defaults.set(on, forKey: Self.keyBearingLagTrace)
        if bearingOn { setBearingOn(false); setBearingOn(true) }
    }

    private var lastBearingPointID: UUID?

    /// RID track point of the "Drone Pod is on this drone" aircraft (ContentView), with its AGL.
    public func bearingRIDPoint(pointID: UUID, latitude: Double, longitude: Double, aglFeet: Double?, fixTime: Date) {
        guard bearingOn, let stream = bearingSteeringStreamID, pointID != lastBearingPointID else { return }
        lastBearingPointID = pointID
        let layer = central.bearing!
        layer.noteAgl(designator: stream, aglM: aglFeet.map { $0 * 0.3048 })
        layer.onPosition(designator: stream, lat: latitude, lon: longitude, fixWallMs: Int64(fixTime.timeIntervalSince1970 * 1000))
    }

    /// Called about once a second with the live stream ids; the choice ends with its stream.
    public func updateLiveSteeringStreams(_ live: Set<String>) {
        if live != liveSteeringStreams { liveSteeringStreams = live }
        if let chosen = steeringStreamID, BtrxSteeringTarget.retained(chosen, live: live) == nil {
            OperationalBridgePacketLog.write?("INFO", "Btrx", "steering: stream \(chosen) ended; overlay drone choice cleared")
            steeringStreamID = nil
        }
        central.setBearingSteering(bearingSteeringStreamID, liveStreams: live.count)
    }

    private func remember(_ id: UUID, _ name: String?) {
        let newPod = rememberedPeripheral != id
        defaults.set(id.uuidString, forKey: Self.keyPeripheral)
        if let name { defaults.set(name, forKey: Self.keyName) }
        if newPod { requestChipConnect() }
    }

    private func savePaired(_ pod: Model.PairedPod) {
        if let data = try? JSONEncoder().encode(pod) { defaults.set(data, forKey: Self.keyPaired) }
    }

    private func saveSavedIgnores(_ ids: [Int]) {
        if ids.isEmpty { defaults.removeObject(forKey: Self.keySavedIgnores) } else { defaults.set(ids, forKey: Self.keySavedIgnores) }
    }
}
