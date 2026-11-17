import Foundation

/// The BTRX status chip at the top of the map (Ken, Nov 16). It follows the Desk Pod link:
/// "BTRX: Active" (green) while the link is READY, or "BTRX: Active - No Drone Pod reports"
/// (orange) while READY with no frame from the paired Drone Pod for `dronePodReportSeconds`;
/// otherwise "BTRX: Inactive - " and why. Shown only once BTRX is set up (Bluetooth link on
/// or a Desk Pod remembered). Same strings, window and rules as Android's BtrxMapStatus.kt.
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

    /// BTRX is set up on this device: the chip shows.
    public static func setUp(bluetoothLinkEnabled: Bool, deskPodRemembered: Bool, usbLink: Bool = false, pausedForUSB: Bool = false) -> Bool {
        bluetoothLinkEnabled || deskPodRemembered || usbLink || pausedForUSB
    }

    /// `dronePodReporting`: a Drone Pod report (`isDronePodReport`) in the last
    /// `dronePodReportSeconds`. `bluetoothOff`: the device's Bluetooth is turned off.
    /// `deskPodSilent`: the last Bluetooth session ended with the Desk Pod silent and none has
    /// started since (`deskPodSilent` replaces "BT Down" / "Connecting"). `deskPodNotice`:
    /// "Desk Pod restarted (power-on)" while fresh; orange "BTRX: Active - <notice>" while ready.
    public static func status(setUp: Bool, link: BtrxDeskPodModel.LinkStatus, bluetoothOff: Bool, pausedForUSB: Bool, dronePodReporting: Bool,
                              deskPodSilent silent: Bool = false, deskPodNotice: String? = nil) -> Status? {
        guard setUp else { return nil }
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
