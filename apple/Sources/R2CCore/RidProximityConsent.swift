import Foundation

/// Local acknowledgment; never inferred from organization settings or the spacing value.
public struct RidProximityConsent: Sendable, Equatable {
    /// Version 2 adds per-paragraph checkboxes; bumping it asks earlier acceptors once more.
    public static let noticeVersion = 2
    public static let noticeParagraphCount = 4
    public static let enableLabel = "Enable alerts"
    public static let confirmEachLabel = "Confirm each paragraph"
    public static let title = "Enable proximity alerts?"
    public static let notice = """
Proximity alerts provide supplemental situational awareness. They do not detect all aircraft or guarantee safe separation.

Received telemetry may be inaccurate, delayed, intermittent, or unavailable. Altitude values may use different reference points. DJI video telemetry accuracy has not been verified; the app’s uncertainty allowances are estimates, not guarantees.

Alerts may occur late, occur unnecessarily, or fail to occur. The configured distance is an alert threshold—not a guaranteed safe separation distance. No alert does not mean the airspace is clear.

Continue visual observation, pilot coordination, and applicable operating procedures. Do not rely on these alerts to avoid a collision.
"""
    /// One required checkbox per paragraph; the text itself is unchanged.
    public static let noticeParagraphs = notice.components(separatedBy: "\n\n")
    public private(set) var enabled: Bool
    public private(set) var noticePending = false
    /// Indices of notice paragraphs checked in the open dialog; cleared whenever it opens or closes.
    public private(set) var acknowledged: Set<Int> = []
    /// Enabling requires a separate checked acknowledgment for every notice paragraph.
    public var canConfirm: Bool { noticePending && (0..<Self.noticeParagraphCount).allSatisfy(acknowledged.contains) }
    /// Confirm button label; also its accessible name, so the disabled state explains itself.
    public var confirmLabel: String { canConfirm ? Self.enableLabel : Self.confirmEachLabel }

    public init(acceptedVersion: Int = 0, acceptedDeviceID: String? = nil, currentDeviceID: String? = nil) {
        enabled = acceptedVersion == Self.noticeVersion && currentDeviceID != nil && acceptedDeviceID == currentDeviceID
    }
    /// An older notice accepted on this device restores alerts off and offers the current notice once.
    public static func needsReacknowledgment(acceptedVersion: Int, acceptedDeviceID: String?, currentDeviceID: String?) -> Bool {
        (1..<noticeVersion).contains(acceptedVersion) && currentDeviceID != nil && acceptedDeviceID == currentDeviceID
    }
    public mutating func requestEnable() { if !enabled { noticePending = true; acknowledged = [] } }
    public mutating func toggleAcknowledgment(_ index: Int) {
        guard noticePending, (0..<Self.noticeParagraphCount).contains(index) else { return }
        if acknowledged.remove(index) == nil { acknowledged.insert(index) }
    }
    public mutating func cancel() { noticePending = false; acknowledged = [] }
    public mutating func confirmEnable() {
        guard canConfirm else { return }
        enabled = true
        noticePending = false
        acknowledged = []
    }
    public mutating func disable() { enabled = false; noticePending = false; acknowledged = [] }
}
