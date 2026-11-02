import Foundation

/// Map and tracker publication require an explicit current-flight Confirmed decision.
/// Ignore keeps local recording but never publishes. Unconfirmed flights publish nothing.
public enum CurrentFlightPublicationGate: Sendable, Equatable {
    case allowed
    case gatedUnconfirmed
    case gatedIgnored

    public static func decide(confirmed: Bool, ignored: Bool) -> CurrentFlightPublicationGate {
        if ignored { return .gatedIgnored }
        if confirmed { return .allowed }
        return .gatedUnconfirmed
    }

    public var allowsPublication: Bool { self == .allowed }

    public var logLabel: String {
        switch self {
        case .allowed: return "allowed"
        case .gatedUnconfirmed: return "gated_unconfirmed"
        case .gatedIgnored: return "gated_ignored"
        }
    }
}
