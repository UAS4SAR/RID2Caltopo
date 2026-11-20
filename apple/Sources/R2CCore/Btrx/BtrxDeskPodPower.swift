import Foundation

/// Desk Pod power diagnostics (Ken, Nov 17). At the Nov 16 training the Desk Pod lost power
/// 25-50 s after each power-on (reset_reason 0, then minutes without advertising) and the chip
/// only said the link was down. This tells the operator what the log showed:
///
/// - a session that ended because the Desk Pod stopped answering (supervision timeout, a read
///   or write that failed or got no answer) is "Desk Pod went silent — check its power" until
///   the next session starts; when its last HEALTH or DeviceMetrics just before the drop showed
///   good power (`powerOkAtDrop`) it is "Desk Pod link dropped — reconnecting" instead (Nov 20:
///   7 drops after landing, with USB power and a full battery and the Desk Pod's uptime unbroken);
/// - a Desk Pod HEALTH with uptime under `restartUptimeS` within `restartAfterDropMs` of a link
///   drop is "Desk Pod restarted (power-on)" (or the reset reason it reported), shown for
///   `restartShowMs`;
/// - the Desk Pod's standard Meshtastic DeviceMetrics (battery_level, voltage), when it sends
///   them, are kept for the Desk Pod screen.
///
/// Pure state, driven by ``BtrxDeskPodModel``. Same rules and strings as Android's BtrxDeskPodPower.kt.
public final class BtrxDeskPodPower {
    public struct Restart: Equatable, Sendable {
        public let text: String
        public let atMs: Int64
        public let uptimeS: Int64
        public let downMs: Int64?
    }

    public static let silent = "Desk Pod went silent — check its power"
    public static let linkDropped = "Desk Pod link dropped — reconnecting"
    /// A Desk Pod HEALTH at most this old at the drop counts for `powerOkAtDrop`.
    public static let powerHealthFreshMs: Int64 = 60_000
    /// DeviceMetrics come less often (Meshtastic telemetry interval).
    public static let powerMetricsFreshMs: Int64 = 15 * 60_000
    /// A battery at or above this (mV) is fine without USB.
    public static let powerOkVbatMv = 3600
    /// battery_level at or above this is fine (101 = external power).
    public static let powerOkLevel = 20
    public static let restarted = "Desk Pod restarted"
    /// A Desk Pod HEALTH with uptime below this soon after a drop: it restarted.
    public static let restartUptimeS: Int64 = 60
    /// How long after a drop a low uptime counts as a restart.
    public static let restartAfterDropMs: Int64 = 10 * 60_000
    /// The map chip shows the restart this long.
    public static let restartShowMs: Int64 = 60_000

    /// When the latest session that ended with the Desk Pod silent ended; nil once a new session starts.
    public private(set) var silentSinceMs: Int64?
    /// Why that session ended.
    public private(set) var silentReason: String?
    /// The Desk Pod's power was fine just before that session ended: the radio link dropped.
    public private(set) var silentPowerOk = false
    /// `silent` or `linkDropped` while silent, else nil.
    public var silentText: String? { silentSinceMs == nil ? nil : (silentPowerOk ? Self.linkDropped : Self.silent) }
    /// The latest link drop not yet matched to a restart.
    private var lastDropAtMs: Int64?
    /// The latest restart seen after a drop.
    public private(set) var restart: Restart?
    public private(set) var metrics: MeshtasticPhoneAPI.DeviceMetrics?
    public private(set) var metricsAtMs: Int64?
    public private(set) var metricsSource: String?

    public init() {}

    /// The session ended. Returns the warning to log when the Desk Pod went silent, else nil.
    public func onDrop(reason: String, nowMs: Int64, powerOk: Bool = false) -> String? {
        lastDropAtMs = nowMs
        guard Self.isSilentDrop(reason) else { return nil }
        silentSinceMs = nowMs
        silentReason = reason
        silentPowerOk = powerOk
        return powerOk ? "\(Self.linkDropped) (\(reason); its power was fine)" : "\(Self.silent) (\(reason))"
    }

    /// A new session started: the Desk Pod answered a connect, so it is not silent.
    public func onSessionStarted() {
        silentSinceMs = nil
        silentReason = nil
        silentPowerOk = false
    }

    /// The Desk Pod's power was fine just before a drop: a fresh HEALTH with USB power or a good
    /// battery and no low-supply flags, or fresh DeviceMetrics with external power or a good
    /// battery. Ages are nil when nothing was received.
    public static func powerOkAtDrop(health: BtrxProtocol.HealthPower?, healthAgeMs: Int64?,
                                     metrics: MeshtasticPhoneAPI.DeviceMetrics?, metricsAgeMs: Int64?) -> Bool {
        if let h = health, let age = healthAgeMs, age >= 0, age <= powerHealthFreshMs {
            let f = h.powerFlags
            if f & (BtrxProtocol.PWR_VDD_LOW | BtrxProtocol.PWR_POF | BtrxProtocol.PWR_VBUS_LOST) != 0 { return false }
            if f & BtrxProtocol.PWR_VBUS != 0 || h.vbatMv >= powerOkVbatMv { return true }
        }
        if let m = metrics, let age = metricsAgeMs, age >= 0, age <= powerMetricsFreshMs,
           let level = m.batteryLevel, level >= powerOkLevel { return true }
        return false
    }

    /// The Desk Pod's own live HEALTH. Returns the warning to log when it shows a restart after
    /// a recent drop (once per drop), else nil.
    public func onDeskHealth(uptimeS: Int64, resetReason: UInt32?, arrivalMs: Int64) -> String? {
        guard let drop = lastDropAtMs else { return nil }
        guard arrivalMs - drop <= Self.restartAfterDropMs, uptimeS < Self.restartUptimeS else { return nil }
        lastDropAtMs = nil
        let text = Self.restartText(resetReason)
        let bootMs = arrivalMs - uptimeS * 1000
        let downMs: Int64? = bootMs - drop >= 0 ? bootMs - drop : nil
        restart = Restart(text: text, atMs: arrivalMs, uptimeS: uptimeS, downMs: downMs)
        let down = downMs.map { "; it came back about \($0 / 1000) s after the link dropped" } ?? ""
        return "\(text): HEALTH uptime_s=\(uptimeS), \((arrivalMs - drop) / 1000) s after the link dropped\(down)"
    }

    public func onDeviceMetrics(_ m: MeshtasticPhoneAPI.DeviceMetrics, source: String, nowMs: Int64) {
        metrics = m; metricsAtMs = nowMs; metricsSource = source
    }

    /// The restart notice while it is fresh (`restartShowMs`), for the map chip.
    public func restartNotice(nowMs: Int64) -> String? {
        guard let r = restart, nowMs - r.atMs >= 0, nowMs - r.atMs <= Self.restartShowMs else { return nil }
        return r.text
    }

    /// "Desk Pod restarted (power-on)"; just "Desk Pod restarted" when the build sends no reset_reason.
    public static func restartText(_ resetReason: UInt32?) -> String {
        guard let r = resetReason else { return restarted }
        return "\(restarted) (\(BtrxProtocol.resetReasonNames(r)))"
    }

    /// The Desk Pod stopped answering (as opposed to a session this app closed, a refused bond,
    /// the wrong device, or the Desk Pod closing the link on purpose). Reasons are the Bluetooth
    /// links' sessionLost texts (iPad and Android).
    public static func isSilentDrop(_ reason: String) -> Bool {
        let r = reason.lowercased()
        if notSilent.contains(where: { r.contains($0) }) { return false }
        return silentMarkers.contains(where: { r.contains($0) })
    }

    private static let silentMarkers = [
        "timed out", "got no answer", "no connection after", "disconnected", "write failed", "read failed",
        "notifications could not be enabled", "connection failed",
    ]
    private static let notSilent = [
        "not bonded", "not paired", "lost its pairing", "not a desk pod", "does not offer", "refused", "replaced", "usb", "turned off",
        "bluetooth state", "(status 19)", "(status 22)",
    ]

    /// "Desk Pod battery: 87%, 3.95 V" / "Desk Pod power: external (battery_level 101), 5.02 V"; nil with no fields.
    public static func metricsLine(_ m: MeshtasticPhoneAPI.DeviceMetrics) -> String? {
        let v = m.voltage.map { String(format: "%.2f V", $0) }
        if let level = m.batteryLevel, level > 100 { return "Desk Pod power: external (battery_level \(level))" + (v.map { ", \($0)" } ?? "") }
        if let level = m.batteryLevel { return "Desk Pod battery: \(level)%" + (v.map { ", \($0)" } ?? "") }
        if let v { return "Desk Pod voltage: \(v)" }
        return nil
    }
}
