import Foundation

/// The BTRX status chip at the top of the map (Ken, Nov 16): "BTRX: Active" while the
/// Desk Pod link is up and talking (the map double-tap rule), else "BTRX: Inactive - "
/// and why. Shown only once BTRX is set up (Bluetooth link on or a Desk Pod remembered).
/// Same strings and rules as Android's BtrxMapStatus.kt.
public enum BtrxMapStatus {
    public static let active = "BTRX: Active"
    public static let inactivePrefix = "BTRX: Inactive - "
    public static let btDown = "BT Down"
    public static let connecting = "Connecting"
    public static let bluetoothOff = "Bluetooth off"
    public static let noDronePodReports = "No Drone Pod reports"
    public static let pausedUSB = "Paused (USB)"
    public static let linkOff = "Link off"
    public static let noPermission = "No permission"
    public static let unavailable = "Bluetooth unavailable"

    public struct Status: Sendable, Equatable {
        public let text: String
        public let active: Bool
        public init(text: String, active: Bool) { self.text = text; self.active = active }
    }

    /// BTRX is set up on this device: the chip shows.
    public static func setUp(bluetoothLinkEnabled: Bool, deskPodRemembered: Bool, usbLink: Bool = false, pausedForUSB: Bool = false) -> Bool {
        bluetoothLinkEnabled || deskPodRemembered || usbLink || pausedForUSB
    }

    /// `talking`: link READY and a Desk Pod frame within the talking window (10 s, as the
    /// map double tap). `bluetoothOff`: the device's Bluetooth is turned off.
    public static func status(setUp: Bool, link: BtrxDeskPodModel.LinkStatus, bluetoothOff: Bool, pausedForUSB: Bool, talking: Bool) -> Status? {
        guard setUp else { return nil }
        if link.phase == .ready && talking { return Status(text: active, active: true) }
        return Status(text: inactivePrefix + reason(link, bluetoothOff: bluetoothOff, pausedForUSB: pausedForUSB), active: false)
    }

    private static func reason(_ link: BtrxDeskPodModel.LinkStatus, bluetoothOff: Bool, pausedForUSB: Bool) -> String {
        if pausedForUSB || link.phase == .pausedForUSB { return pausedUSB }
        if link.kind == .usb { return link.phase == .ready ? noDronePodReports : connecting }
        switch link.phase {
        case .off: return linkOff
        default: break
        }
        if bluetoothOff { return Self.bluetoothOff }
        switch link.phase {
        case .noPermission: return noPermission
        case .unavailable: return unavailable
        case .ready: return noDronePodReports
        case .configuring: return connecting
        case .retryWait: return btDown
        // scanning, bonding, connecting: the first connect, or reconnecting after a drop.
        default: return link.failedAttempts > 0 ? btDown : connecting
        }
    }
}
