import Foundation

/// Presentation only; selecting Inspect never authorizes publication.
public enum OperationalWorkspacePolicy {
    public enum AlertTone: Equatable, Sendable { case hidden, quiet, caution, active }
    public static func alertTone(hasPlayed: Bool, active: Bool, caution: Bool) -> AlertTone {
        guard hasPlayed else { return .hidden }
        if active { return .active }
        return caution ? .caution : .quiet
    }
    /// Ten percent visual approach band; never changes detection or audio scheduling.
    public static func separationCaution(horizontal: Double, vertical: Double?, threshold: Double) -> Bool {
        threshold.isFinite && threshold > 0 && horizontal.isFinite && horizontal >= 0 && horizontal <= threshold * 1.10
            && (vertical.map { $0.isFinite && $0 >= 0 && $0 <= threshold * 1.10 } ?? true)
    }
    public enum DroneAction: Equatable, Sendable { case add, confirm, inspect }
    public static func droneAction(known: Bool, publishing: Bool) -> DroneAction {
        if publishing { return .inspect }
        return known ? .confirm : .add
    }
}
