import Foundation

/// Pure zoom gate matching App `localPreviewZoomToken`: persisted selection never zooms alone.
public enum MappingLocalPreviewZoom {
    public static func shouldConsume(token: UUID?, lastConsumed: UUID?) -> Bool {
        guard let token else { return false }
        return token != lastConsumed
    }

    public static func shouldZoom(token: UUID?, lastConsumed: UUID?, hasUsableBounds: Bool) -> Bool {
        shouldConsume(token: token, lastConsumed: lastConsumed) && hasUsableBounds
    }
}

/// Return-to-device-GPS recenter after clearing a temporary location override.
public enum DeviceGpsRecenter {
    public struct Plan: Equatable, Sendable {
        public let centerNow: Bool
        public let pendingUntilFix: Bool
        public let consumeRequest: Bool

        public init(centerNow: Bool, pendingUntilFix: Bool, consumeRequest: Bool) {
            self.centerNow = centerNow
            self.pendingUntilFix = pendingUntilFix
            self.consumeRequest = consumeRequest
        }
    }

    public static func plan(token: UUID?, lastConsumed: UUID?, hasDeviceFix: Bool) -> Plan {
        guard let token, token != lastConsumed else {
            return Plan(centerNow: false, pendingUntilFix: false, consumeRequest: false)
        }
        if hasDeviceFix {
            return Plan(centerNow: true, pendingUntilFix: false, consumeRequest: true)
        }
        return Plan(centerNow: false, pendingUntilFix: true, consumeRequest: true)
    }

    public static func shouldApplyPending(pending: Bool, hasDeviceFix: Bool) -> Bool {
        pending && hasDeviceFix
    }
}
