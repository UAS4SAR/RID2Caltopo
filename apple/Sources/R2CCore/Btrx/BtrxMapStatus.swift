import Foundation

/// The BTRX status chip at the top of the map (Ken, Nov 16). It follows the Desk Pod link:
/// "BTRX: Active" (green) while the link is READY, or "BTRX: Active - No Drone Pod reports"
/// (orange) while READY with no frame from the paired Drone Pod for `dronePodReportSeconds`;
/// otherwise "BTRX: Inactive - " and why only after READY this session. Before then, a paired
/// Desk Pod gets a two-minute Connecting chip only after an explicit user action (Ken, Nov 18).
/// Off and never-paired devices stay hidden. Same rules as Android's BtrxMapStatus.kt.
public enum BtrxMapStatus {
    public static let active = "BTRX: Active"
    public static let activeNoReports = "BTRX: Active - No Drone Pod reports"
    public static let activePrefix = "BTRX: Active - "
    public static let inactivePrefix = "BTRX: Inactive - "
    public static let btDown = "BT Down"
    public static let connecting = "Connecting"
    public static let bluetoothOff = "Bluetooth off"
    /// The reason in `activeNoReports`.
    public static let noDronePodReports = "No Drone Pod reports"
    public static let pausedUSB = "Paused (USB)"
    public static let linkOff = "Link off"
    public static let noPermission = "No permission"
    public static let unavailable = "Bluetooth unavailable"
    /// The last Bluetooth session ended with the Desk Pod silent (Nov 17): most likely its power.
    public static let deskPodSilent = BtrxDeskPodPower.silent

    /// No frame from the paired Drone Pod for this long while READY: the orange Active chip
    /// (Android DRONE_POD_REPORT_MS, 15 s).
    public static let dronePodReportSeconds: Double = 15

    /// `active`: the Desk Pod link is READY (green or orange chip). `warning`: READY but the
    /// paired Drone Pod is quiet (orange).
    public struct Status: Sendable, Equatable {
        public let text: String
        public let active: Bool
        public let warning: Bool
        public init(text: String, active: Bool, warning: Bool = false) { self.text = text; self.active = active; self.warning = warning }
    }

    /// A frame that shows the paired Drone Pod is reporting: live (not the connect backlog),
    /// from the paired pod, and not PAIR_REQ (a pod asking to pair is not reporting).
    public static func isDronePodReport(frameType: Int, backlog: Bool, fromPairedPod: Bool) -> Bool {
        fromPairedPod && !backlog && frameType != BtrxProtocol.PAIR_REQ
    }

    /// `secondsSinceReport`: nil = none since the link came up.
    public static func dronePodReporting(secondsSinceReport: Double?) -> Bool {
        guard let age = secondsSinceReport else { return false }
        return age >= 0 && age <= dronePodReportSeconds
    }

    /// A remembered Desk Pod (or a USB Desk Pod), not merely the Bluetooth toggle.
    public static func setUp(bluetoothLinkEnabled: Bool, deskPodRemembered: Bool, usbLink: Bool = false, pausedForUSB: Bool = false) -> Bool {
        deskPodRemembered || usbLink
    }

    /// Two minutes after an explicit user connect/pair action. Never started by launch/retries.
    public static let connectingWindow: Double = 120

    /// Pure visibility rule, shared with Android and the cross-platform CSV fixture.
    public static func visibility(paired: Bool, enabled: Bool, ready: Bool, readyEarlier: Bool, requestedAt: Double?, now: Double) -> String {
        guard paired && enabled else { return "hidden" }
        if ready || readyEarlier { return "status" }
        if let requestedAt, now - requestedAt >= 0, now - requestedAt < connectingWindow { return "connecting" }
        return "hidden"
    }

    /// Session-only state; never saved, and automatic reconnect never opens the connecting window.
    public struct Session: Sendable {
        public private(set) var readyEarlier = false
        public private(set) var requestedAt: Double?
        public init() {}
        public mutating func observe(ready: Bool) { if ready { readyEarlier = true } }
        public mutating func request(now: Double) { requestedAt = now }
        public mutating func forget() { readyEarlier = false; requestedAt = nil }
    }

    /// `dronePodReporting`: a Drone Pod report (`isDronePodReport`) in the last
    /// `dronePodReportSeconds`. `bluetoothOff`: the device's Bluetooth is turned off.
    /// `deskPodSilent`: the last Bluetooth session ended with the Desk Pod silent and none has
    /// started since (`deskPodSilent` replaces "BT Down" / "Connecting"). `deskPodNotice`:
    /// "Desk Pod restarted (power-on)" while fresh; orange "BTRX: Active - <notice>" while ready.
    public static func status(setUp: Bool, link: BtrxDeskPodModel.LinkStatus, bluetoothOff: Bool, pausedForUSB: Bool, dronePodReporting: Bool,
                              deskPodSilent silent: Bool = false, deskPodNotice: String? = nil,
                              linkEnabled: Bool = true, readyEarlier: Bool = false, requestedAt: Double? = nil, now: Double = 0) -> Status? {
        switch visibility(paired: setUp, enabled: linkEnabled && link.phase != .off, ready: link.phase == .ready && !pausedForUSB, readyEarlier: readyEarlier, requestedAt: requestedAt, now: now) {
        case "hidden": return nil
        case "connecting":
            let why = bluetoothOff ? Self.bluetoothOff : link.phase == .noPermission ? noPermission : link.phase == .unavailable ? unavailable : connecting
            return Status(text: inactivePrefix + why, active: false)
        default: break
        }
        if link.phase == .ready && !pausedForUSB {
            if let notice = deskPodNotice { return Status(text: activePrefix + notice, active: true, warning: true) }
            return dronePodReporting ? Status(text: active, active: true) : Status(text: activeNoReports, active: true, warning: true)
        }
        return Status(text: inactivePrefix + reason(link, bluetoothOff: bluetoothOff, pausedForUSB: pausedForUSB, silent: silent), active: false)
    }

    private static func reason(_ link: BtrxDeskPodModel.LinkStatus, bluetoothOff: Bool, pausedForUSB: Bool, silent: Bool) -> String {
        if pausedForUSB || link.phase == .pausedForUSB { return pausedUSB }
        if link.kind == .usb { return connecting }
        switch link.phase {
        case .off: return linkOff
        default: break
        }
        if bluetoothOff { return Self.bluetoothOff }
        switch link.phase {
        case .noPermission: return noPermission
        case .unavailable: return unavailable
        case .configuring: return connecting
        // retryWait, scanning, bonding, connecting after the Desk Pod stopped answering.
        case _ where silent && link.kind == .bluetooth: return deskPodSilent
        case .retryWait: return btDown
        // scanning, bonding, connecting: the first connect, or reconnecting after a drop.
        default: return link.failedAttempts > 0 ? btDown : connecting
        }
    }
}
