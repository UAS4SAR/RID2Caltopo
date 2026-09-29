import Foundation

/// Presentation has its own lifetime: retiring eligibility alone does not dismiss a sheet.
public struct PendingFlightConfirmation: Sendable, Equatable {
    public private(set) var remoteID: String?

    public init() {}

    public mutating func present(remoteID: String?) {
        self.remoteID = remoteID
    }

    @discardableResult
    public mutating func endFlight(remoteID: String) -> Bool {
        guard self.remoteID == remoteID else { return false }
        self.remoteID = nil
        return true
    }
}
