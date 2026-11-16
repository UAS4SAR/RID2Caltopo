import Foundation

/// The Desk Pod screen's wording, shared logic so the iPad and Android say the same
/// thing (Android: BtrxDeskPodText.kt; keep them matching).
public enum BtrxDeskPodText {
    public typealias Model = BtrxDeskPodModel
    /// Developer Tools entry and screen title (the btrx receiver system; the pods keep their names inside).
    public static let title = "BTRX"
    /// Top of the BTRX pane (Settings > Developer Tools > BTRX): opens the BTRX page in the system browser.
    public static let learnMore = "Learn more about BTRX"
    public static let learnMoreURL = "https://rid2caltopo.com/btrx"
    public static let usbPaused = "Desk Pod is attached by USB; Bluetooth paused"
    public static let bluetoothToggle = "Connect over Bluetooth"
    public static let podStaleHint = "Not asking any more: nod the drone pod twice again."
    public static let pairedPodQuiet = "No HEALTH from the paired Drone Pod for over 2 min; re-pair?"
    /// iPad: the first Bluetooth connection waits up to 90 s for the PIN to be typed.
    public static let enterPIN = "Enter the PIN printed on the Desk Pod (\(MeshtasticPhoneAPI.defaultPIN) by default) when iPadOS asks."
    public static let pinHint = "Bluetooth PIN: printed on the Desk Pod (\(MeshtasticPhoneAPI.defaultPIN) by default for now)."
    // MARK: Desk Pod button confirmation (firmware 0x0108). Same as Android.

    public static let confirmPairTitle = "Change Drone Pod pairing?"
    public static let confirmPairBody = "The Desk Pod button was tapped. Confirm opens the Desk Pod's pairing window so a Drone Pod can pair. Cancel leaves the pairing as it is."
    public static let confirmResetTitle = "Factory reset the Desk Pod?"
    public static let confirmResetBody = "The Desk Pod button was held 10 s. A factory reset erases the Desk Pod's saved settings and its pairing, and cannot be undone. The Drone Pod must be paired again afterwards."
    public static let confirmOk = "Confirm"
    public static let confirmResetOk = "Factory reset"
    public static let confirmCancel = "Cancel"
    public static let confirmWaiting = "Confirmed. Waiting for the Desk Pod…"
    public static let deskResetWarning = "The Desk Pod was factory reset from its button and is no longer paired. Re-pair the Drone Pod (or Pair drone pod)."

    public static func confirmTitle(_ v: BtrxButtonConfirm.View) -> String {
        switch v.action {
        case BtrxProtocol.CFM_ACTION_DESK_PAIR: return confirmPairTitle
        case BtrxProtocol.CFM_ACTION_FACTORY_RESET: return confirmResetTitle
        default: return "Desk Pod button action \(v.action)?"
        }
    }

    public static func confirmBody(_ v: BtrxButtonConfirm.View) -> String {
        switch v.action {
        case BtrxProtocol.CFM_ACTION_DESK_PAIR: return confirmPairBody
        case BtrxProtocol.CFM_ACTION_FACTORY_RESET: return confirmResetBody
        default: return "The Desk Pod asks to confirm a button action this app does not know."
        }
    }

    public static func confirmCountdown(_ v: BtrxButtonConfirm.View) -> String {
        v.confirmSent ? confirmWaiting : "Nothing happens unless you confirm (\(v.secondsLeft) s left)."
    }

    /// Desk Pod pane: whether the Desk Pod asks this app before its button acts.
    public static func buttonConfirmLine(_ s: Model.Snapshot) -> String {
        let tail: String
        switch s.buttonConfirmLink {
        case .on: tail = "asks this app to confirm pairing or a factory reset"
        case .noLink: tail = "acts at once (no link to the Desk Pod)"
        case .oldFirmware: tail = "acts at once (confirmation needs Desk Pod firmware 0x0108)"
        case .notInFront: tail = "acts at once (this app is not in front)"
        }
        return "Desk Pod button: " + tail
    }

    public static let mute = "Mute"
    public static let clearMutes = "Clear mutes"
    // One Desk Pod and one Drone Pod per app: replace, never add another.
    public static let pairPod = "Pair drone pod"
    public static let replacePod = "Replace Drone Pod"
    public static let replacePodTitle = "Replace Drone Pod?"
    public static let replacePodOK = "Unpair and pair new"
    public static let replaceDesk = "Replace Desk Pod"
    public static let replaceDeskTitle = "Replace Desk Pod?"
    public static let replaceDeskOK = "Forget and scan"
    public static let replaceKeep = "Keep current"
    // Re-pair (firmware 0x0106: a paired pod ignores nods; PAIR_OPEN opens its window).
    public static let repairPod = "Re-pair Drone Pod"
    public static let repairPodTitle = "Re-pair Drone Pod?"
    public static let repairPodOK = "Open pairing"

    /// Body of the "Re-pair Drone Pod?" confirmation.
    public static func repairPodMessage(_ s: Model.Snapshot) -> String {
        let pod = s.pairedPod.map { "Drone Pod \(BtrxClient.nodeName($0.node))" } ?? "The Drone Pod"
        return "This opens the pairing window on the Desk Pod and on \(pod) for 60 s, then asks you to confirm the pod. "
            + "Do it on the ground, not in flight. Steering keeps working meanwhile; if you do not confirm, the pod stays paired as it was."
    }

    // Own aircraft (saved): STEER_MUTE op 3 / 4 (firmware 0x0106); the local toggle stays for older builds.
    public static let ownSaved = "Own aircraft (saved)"
    public static let ownSavedDone = "Saved"
    public static let ownSavedRemove = "Remove"
    public static let ownSavedHint = "Saved on the Drone Pod: it leaves the address out of STEER and DETECT, also after a restart. Pick a source heard in the last 60 s."
    public static func ownSavedTitle(_ count: Int) -> String { "Own aircraft saved on the Drone Pod: \(count)" }
    public static func srcIdText(_ id: Int) -> String { String(format: "source %04X", id) }

    /// Body of the "Replace Drone Pod?" confirmation.
    public static func replacePodMessage(_ s: Model.Snapshot) -> String {
        let pod = s.pairedPod.map { String(format: "Drone Pod %@ (pod_id %08x)", BtrxClient.nodeName($0.node), $0.podId) } ?? "The current Drone Pod"
        return "\(pod) will be unpaired from this app, then pairing starts for the new pod. "
            + "The old pod keeps its own pairing and keeps sending until it is paired again or factory reset; this app ignores it."
    }

    /// Body of the "Replace Desk Pod?" confirmation.
    public static func replaceDeskMessage(_ s: Model.Snapshot) -> String {
        let desk = s.link.deviceName.map { "Desk Pod \($0)" } ?? "The current Desk Pod"
        return "\(desk) will be forgotten and the app will connect to the next Desk Pod it finds. The paired Drone Pod stays paired."
    }

    // Steering overlay hide/show (only the card on the stream tile). Same as Android.
    public static let overlayToggle = "Show steering overlay on the stream"
    public static let overlayToggleHint = "Off hides only the card on the stream tile; the Drone Pod stays connected, mutes and this Steering section keep working. Tap \"BTRX hidden\" on the tile to show it again."
    public static let overlayHide = "Hide"
    public static let overlayHidden = "BTRX hidden"

    public static let steerPick = "Drone Pod is on this drone"
    public static let steerChooseAgain = "Choose another drone"
    public static let steerRoll = "Roll correction (debug; only after the bench roll check)"
    public static func steerTileLine(chosen: String?, liveCount: Int) -> String {
        if let chosen { return "Steering overlay on: \(chosen)" }
        if liveCount > 1 { return "Several streams are live: tap \"\(steerPick)\" on the drone carrying the pod." }
        return "Steering overlay on the live stream (one live)."
    }
    public static let steerNone = "No STEER yet (firmware 0x0101 or later sends one about every second once the pod is paired)."

    // Drone Pod IMU calibration (design doc section 29.3).
    public static let imuCalTitle = "Drone Pod IMU"
    public static let imuCalHint = "Calibrate once after the lid goes on, and again whenever the IMU board is refitted. Set the Drone Pod level and keep it still, then tap Calibrate. Needs Drone Pod firmware 0x0102 or later."
    public static let imuCalStart = "Calibrate"
    public static let imuCalStartAgain = "Start again"
    public static let imuCalStbd = "Start starboard step"
    public static let imuCalStbdRetry = "Retry starboard step"
    public static let imuCalCancel = "Cancel calibration"
    public static let imuCalClose = "Close"
    public static let imuCalCheck = "Check"
    public static let imuCalClear = "Clear saved calibration"
    public static func imuCalCountdown(_ seconds: Int) -> String { "Measuring in \(seconds) s" }
    public static let steerMuted = "A steering source is muted on the Drone Pod."
    public static let steerMuteHint = "Tap the overlay on the stream view to mute its source (for example a search team); the next strongest takes its place. The pod holds up to 8 mutes; they clear when it restarts, and a muted source unheard for 60 s comes back under a new color."

    /// Bench line for the Desk Pod screen: src, hint word, kind, age (the swatch is drawn beside it).
    public static func steerLine(_ v: BtrxSteering.View) -> String {
        "Last STEER: src \(v.srcId) (\(BtrxProtocol.devClassName(v.devClass))), \(v.hint.word), \(ago(v.ageMs)) (steer_seq \(v.steerSeq))"
    }

    public enum StepStatus: Sendable { case pending, active, done, failed }

    public struct Step: Sendable, Equatable {
        public let title: String, status: StepStatus, detail: String
    }

    public static func linkLine(_ s: Model.Snapshot) -> String {
        let kind: String
        switch s.link.kind {
        case .bluetooth: kind = "Bluetooth"
        case .usb: kind = "USB (debug)"
        case .none: kind = "None"
        }
        return "\(kind): \(phaseName(s.link.phase))" + reconnectSuffix(s.link)
    }

    /// Bluetooth connects failing in a row: "; reconnecting, attempt N (last failure: ...)". Same as Android.
    public static func reconnectSuffix(_ link: Model.LinkStatus) -> String {
        if link.failedAttempts <= 0 || link.phase == .ready || link.phase == .off || link.phase == .pausedForUSB { return "" }
        let last = link.lastFailure.map { " (last failure: \($0))" } ?? ""
        return "; reconnecting, attempt \(link.failedAttempts + 1)\(last)"
    }

    public static func phaseName(_ p: Model.LinkPhase) -> String {
        switch p {
        case .off: return "off"
        case .unavailable: return "unavailable"
        case .noPermission: return "permission needed"
        case .scanning: return "looking for the Desk Pod"
        case .bonding: return "pairing with the Desk Pod (enter the PIN)"
        case .connecting: return "connecting"
        case .configuring: return "reading configuration"
        case .ready: return "connected"
        case .retryWait: return "waiting to retry"
        case .pausedForUSB: return "paused (USB attached)"
        }
    }

    public static func deviceLine(_ s: Model.Snapshot) -> String? {
        let node = s.localNode.map(BtrxClient.nodeName)
        switch (s.link.deviceName, node) {
        case let (name?, node?): return "\(name) (node \(node))"
        case let (name?, nil): return name
        case let (nil, node?): return "Node \(node)"
        default: return nil
        }
    }

    public static func roleLine(_ s: Model.Snapshot) -> String {
        guard let r = s.role else { return "Role: not reported yet" }
        return r.role == BtrxProtocol.roleDeskPod ? "Role: Desk Pod" : "Role: \(BtrxProtocol.roleName(r.role)) (not a Desk Pod)"
    }

    public static func firmwareLine(_ s: Model.Snapshot) -> String {
        guard let r = s.role else { return "Firmware: not reported yet" }
        return "Firmware build: \(String(format: "0x%04x", r.buildHash)) (\(BtrxProtocol.buildSupportText(r.buildHash)))"
    }

    public static func healthLines(_ s: Model.Snapshot) -> [String] {
        var out: [String] = []
        if let h = s.dronePodHealth {
            let hh = h.health
            out.append("Drone Pod \(BtrxClient.nodeName(h.node)): \(hh.confirmed == 1 ? "confirmed" : "not confirmed"), \(hh.scanning == 1 ? "listening" : "not listening")")
            out.append("Last HEALTH: \(ago(s.nowMs - h.arrivalMs)) (pod uptime \(hh.uptimeS) s, flags \(BtrxProtocol.statusFlagNames(hh.flags)))")
            out.append("Desk Pod hears Drone Pod: RSSI \(h.rxRssi) dBm, SNR \(String(format: "%.1f", h.rxSnr)) dB")
            out.append("Drone Pod hears Desk Pod: RSSI \(hh.loraRssi) dBm, SNR \(hh.loraSnr) dB")
            if let e = hh.ext { out.append(contentsOf: dronePodExtLines(e)) }
            if let lost = hh.scanLostPerS { out.append(scanLostLine(lost)) }
        } else {
            out.append("Drone Pod: no HEALTH report yet (one arrives about every 60 s)")
        }
        let last = s.lastDetectMs.map { ", last \(ago(s.nowMs - $0))" } ?? ""
        out.append("Detections: \(s.detectsLastMinute) in the last minute\(last)")
        if s.otherPodNodes > 0 { out.append("Other Drone Pods heard on the channel: \(s.otherPodNodes)") }
        if let lost = s.deskPhoneLost { out.append(deskPhoneLostLine(lost)) }
        // Desk Pod HEALTH from byte 30: only phone_lost and reset_reason mean anything (38-43 are always 0).
        if let e = s.deskHealthExt { out.append("Desk Pod last reset: \(resetReasonText(e.resetReason))") }
        out.append(deskWindowLine(s))
        return out
    }

    /// The Desk Pod's pairing state, from DESK_PAIR (not HEALTH). Same as Android.
    public static func deskWindowLine(_ s: Model.Snapshot) -> String {
        s.windowOpen ? "Desk Pod pairing window: open, \(s.windowSecondsLeft) s left (\(deskPairSourceText(s.windowSource)))"
            : "Desk Pod pairing window: closed"
    }

    /// DESK_PAIR source: 1 button, 2 phone/USB-opened (DESK_PAIR), 3 app-opened (PAIR_OPEN, build 0x0107).
    public static func deskPairSourceText(_ src: Int) -> String {
        switch src {
        case BtrxProtocol.deskSrcButton: return "button"
        case BtrxProtocol.deskSrcLink: return "phone/USB-opened"
        case BtrxProtocol.deskSrcPairOpen: return "app-opened"
        default: return "source \(src)"
        }
    }

    /// Drone Pod HEALTH offset 44 (build 0x010A; absent on 0x0109). Same as Android.
    public static func scanLostLine(_ perS: Int) -> String { "Drone Pod scan reports lost: \(perS) per second" }

    /// Drone Pod HEALTH fields from build 0x0106 (offsets 34-43).
    public static func dronePodExtLines(_ e: BtrxProtocol.HealthExt) -> [String] {
        ["Drone Pod last reset: \(resetReasonText(e.resetReason))",
         "Drone Pod pairing window last opened by: \(pairSourceText(e.pairSource)); nods ignored while paired: \(e.nodsIgnored)",
         "Drone Pod leaves out: \(excludeText(e.excludeFlags)); \(e.excludedPerS) ad(s) left out per second"]
    }

    /// reset_reason (nRF52 RESETREAS) in words; the hex value too unless it is 0.
    public static func resetReasonText(_ v: UInt32) -> String {
        if v == 0 { return "power on or brown-out" }
        let words = ["reset-pin": "reset pin", "watchdog": "watchdog", "soft-reset": "software reset", "lockup": "CPU lockup",
                     "wake-pin": "wake from pin", "wake-lpcomp": "wake from LPCOMP", "wake-debug": "debug interface",
                     "wake-nfc": "wake from NFC", "wake-usb": "wake from USB"]
        let names = BtrxProtocol.resetReasonNames(v).split(separator: ",").map { words[String($0)] ?? String($0) }.joined(separator: ", ")
        return "\(names) (\(String(format: "0x%x", v)))"
    }

    public static func pairSourceText(_ src: Int) -> String {
        switch src {
        case BtrxProtocol.pairSrcNone: return "nothing since it started"
        case BtrxProtocol.pairSrcNod: return "a nod (not paired yet)"
        case BtrxProtocol.pairSrcApp: return "this app (Re-pair)"
        default: return "unknown (\(src))"
        }
    }

    /// exclude_flags: which of its own boards, the Desk Pod and DJI the pod leaves out of STEER and DETECT.
    public static func excludeText(_ flags: Int) -> String {
        let items: [(Int, String)] = [(BtrxProtocol.EXCL_OWN, "its own board"), (BtrxProtocol.EXCL_OTHER_BOARD, "the other board"),
                                      (BtrxProtocol.EXCL_DESK_POD, "the Desk Pod"), (BtrxProtocol.EXCL_DJI, "DJI")]
        let on = items.filter { flags & $0.0 != 0 }.map { $0.1 }
        let off = items.filter { flags & $0.0 == 0 }.map { $0.1 }
        if on.isEmpty { return "nothing yet (\(off.joined(separator: ", ")) not known)" }
        if off.isEmpty { return on.joined(separator: ", ") }
        return on.joined(separator: ", ") + " (not yet: " + off.joined(separator: ", ") + ")"
    }

    /// Desk Pod HEALTH phone_lost (build 0x0104): messages dropped from its full queue to this app.
    public static func deskPhoneLostLine(_ lost: UInt32) -> String {
        "Desk Pod to app: \(lost == UInt32.max ? "4294967295+" : String(lost)) message(s) dropped since the Desk Pod started (queue full)"
    }

    /// "Live frames: ..." plus the packets dropped before btrx decoding, when there are any.
    public static func liveFramesLine(_ s: Model.Snapshot) -> String {
        let dropped = s.droppedEncrypted + s.droppedOtherPort == 0 ? ""
            : "; dropped: encrypted=\(s.droppedEncrypted) other-port=\(s.droppedOtherPort)"
        return countsLine("Live frames", s.liveCounts) + dropped
    }

    public static func countsLine(_ label: String, _ counts: [String: Int]) -> String {
        "\(label): " + (counts.isEmpty ? "none" : counts.keys.sorted().map { "\($0)=\(counts[$0]!)" }.joined(separator: " "))
    }

    public static func pairedLine(_ s: Model.Snapshot) -> String? {
        s.pairedPod.map { String(format: "Paired Drone Pod: %@, pod_id %08x, dev_a %08x, dev_b %08x", BtrxClient.nodeName($0.node), $0.podId, $0.devA, $0.devB) }
    }

    /// Warnings under the paired pod: an unexpected pod_id from it, or no HEALTH for a while.
    public static func pairedPodWarnings(_ s: Model.Snapshot) -> [String] {
        guard s.pairedPod != nil else { return [] }
        var w: [String] = []
        if let id = s.lastMismatchPodId, s.pairedPodIdMismatches > 0 {
            w.append(String(format: "Paired Drone Pod is sending pod_id %08x (%d frame(s) ignored); re-pair it.", id, s.pairedPodIdMismatches))
        }
        if s.pairedPodQuiet { w.append(pairedPodQuiet) }
        return w
    }

    public static func steps(_ p: Model.Pairing) -> [Step] {
        let order: [Model.PairStep] = [.waitButton, .waitNod, .podFound, .confirming]
        let current: Model.PairStep?
        switch p.step {
        case .failed: current = p.failedAt
        case .cancelled, .idle, .paired: current = nil
        default: current = p.step
        }
        let currentIndex = current.flatMap { order.firstIndex(of: $0) } ?? (p.step == .paired ? order.count : -1)
        let c = p.candidate
        return order.enumerated().map { i, step in
            let status: StepStatus
            if p.step == .idle { status = .pending }
            else if p.step == .cancelled { status = (c != nil && i <= 1) ? .done : .pending }
            else if i < currentIndex { status = .done }
            else if i == currentIndex { status = p.step == .failed ? .failed : .active }
            else { status = .pending }
            switch step {
            case .waitButton:
                if p.repair {
                    return Step(title: "Desk Pod opens its pairing window", status: status,
                                detail: status == .active ? "Asked over the link. If it does not open, tap the Desk Pod button." : "")
                }
                return Step(title: "Tap the Desk Pod button", status: status, detail: status == .active ? "Waiting for the tap." : "")
            case .waitNod:
                if p.repair && !p.repairByNod {
                    return Step(title: "Drone Pod asks to pair", status: status,
                                detail: status == .active ? "\(p.windowSecondsLeft) s left in the pairing window. PAIR_OPEN sent; the paired pod answers with PAIR_REQ (no nod)." : "")
                }
                return Step(title: "Nod the drone pod twice", status: status,
                            detail: status == .active ? "\(p.windowSecondsLeft) s left in the pairing window. Hold the pod still for 3 s, then tip it at least 25° and back, twice." : "")
            case .podFound:
                return Step(title: "Confirm the Drone Pod", status: status,
                            detail: (c.map(candidateLine) ?? "") + (p.podStale && status == .active ? "\n\(podStaleHint)" : ""))
            default:
                let detail: String
                if let a = p.aOk {
                    detail = "a_ok=\(a) b_ok=\(p.bOk.map(String.init) ?? "null")" + (p.ackFlags.map { " flags=\(BtrxProtocol.statusFlagNames($0))" } ?? "")
                } else if status == .active {
                    detail = "PAIR_CONFIRM sent (try \(p.confirmTries)); waiting for PAIR_ACK."
                } else { detail = "" }
                return Step(title: "Drone Pod acknowledges", status: status, detail: detail)
            }
        }
    }

    public static func candidateLine(_ c: Model.Candidate) -> String {
        String(format: "%@  dev_a %08x  dev_b %08x  imu_ok %d  RSSI %d dBm  SNR %.1f dB", BtrxClient.nodeName(c.node), c.devA, c.devB, c.imuOk, c.rssi, c.snr)
    }

    public static func ago(_ ms: Int64) -> String {
        let s = max(ms, 0) / 1000
        return s < 120 ? "\(s) s ago" : "\(s / 60) min ago"
    }
}
