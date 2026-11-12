import Foundation

/// The Desk Pod screen's wording, shared logic so the iPad and Android say the same
/// thing (Android: BtrxDeskPodText.kt; keep them matching).
public enum BtrxDeskPodText {
    public typealias Model = BtrxDeskPodModel
    public static let title = "Desk Pod"
    public static let usbPaused = "Desk Pod is attached by USB; Bluetooth paused"
    public static let bluetoothToggle = "Connect over Bluetooth"
    public static let podStaleHint = "Not asking any more: nod the drone pod twice again."
    public static let pairedPodQuiet = "No HEALTH from the paired Drone Pod for over 2 min; re-pair?"
    /// iPad: the first Bluetooth connection waits up to 90 s for the PIN to be typed.
    public static let enterPIN = "Enter the PIN printed on the Desk Pod (\(MeshtasticPhoneAPI.defaultPIN) by default) when iPadOS asks."
    public static let pinHint = "Bluetooth PIN: printed on the Desk Pod (\(MeshtasticPhoneAPI.defaultPIN) by default for now)."

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
        return "\(kind): \(phaseName(s.link.phase))"
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
        return "Firmware build: \(String(format: "0x%04x", r.buildHash)) (\(s.roleSupported ? "supported" : "not in this app's supported list"))"
    }

    public static func healthLines(_ s: Model.Snapshot) -> [String] {
        var out: [String] = []
        if let h = s.dronePodHealth {
            let hh = h.health
            out.append("Drone Pod \(BtrxClient.nodeName(h.node)): \(hh.confirmed == 1 ? "confirmed" : "not confirmed"), \(hh.scanning == 1 ? "listening" : "not listening")")
            out.append("Last HEALTH: \(ago(s.nowMs - h.arrivalMs)) (pod uptime \(hh.uptimeS) s, flags \(BtrxProtocol.statusFlagNames(hh.flags)))")
            out.append("Desk Pod hears Drone Pod: RSSI \(h.rxRssi) dBm, SNR \(String(format: "%.1f", h.rxSnr)) dB")
            out.append("Drone Pod hears Desk Pod: RSSI \(hh.loraRssi) dBm, SNR \(hh.loraSnr) dB")
        } else {
            out.append("Drone Pod: no HEALTH report yet (one arrives about every 60 s)")
        }
        let last = s.lastDetectMs.map { ", last \(ago(s.nowMs - $0))" } ?? ""
        out.append("Detections: \(s.detectsLastMinute) in the last minute\(last)")
        if s.otherPodNodes > 0 { out.append("Other Drone Pods heard on the channel: \(s.otherPodNodes)") }
        return out
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
            case .waitButton: return Step(title: "Tap the Desk Pod button", status: status, detail: status == .active ? "Waiting for the tap." : "")
            case .waitNod:
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
