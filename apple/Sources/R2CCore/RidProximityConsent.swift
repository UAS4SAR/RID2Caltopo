import Foundation

/// Local acknowledgment; never inferred from organization settings or the spacing value.
public struct RidProximityConsent: Sendable, Equatable {
    public static let noticeVersion = 1
    public static let title = "Enable proximity alerts?"
    public static let notice = """
Proximity alerts provide supplemental situational awareness. They do not detect all aircraft or guarantee safe separation.

Received telemetry may be inaccurate, delayed, intermittent, or unavailable. Altitude values may use different reference points. DJI video telemetry accuracy has not been verified; the app’s uncertainty allowances are estimates, not guarantees.

Alerts may occur late, occur unnecessarily, or fail to occur. The configured distance is an alert threshold—not a guaranteed safe separation distance. No alert does not mean the airspace is clear.

Continue visual observation, pilot coordination, and applicable operating procedures. Do not rely on these alerts to avoid a collision.
"""
    public private(set) var enabled: Bool
    public private(set) var noticePending = false

    public init(acceptedVersion: Int = 0, acceptedDeviceID: String? = nil, currentDeviceID: String? = nil) {
        enabled = acceptedVersion == Self.noticeVersion && currentDeviceID != nil && acceptedDeviceID == currentDeviceID
    }
    public mutating func requestEnable() { if !enabled { noticePending = true } }
    public mutating func cancel() { noticePending = false }
    public mutating func confirmEnable() {
        guard noticePending else { return }
        enabled = true
        noticePending = false
    }
    public mutating func disable() { enabled = false; noticePending = false }
}
